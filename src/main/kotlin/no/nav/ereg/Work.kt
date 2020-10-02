package no.nav.ereg

import io.prometheus.client.Gauge
import mu.KotlinLogging
import no.nav.sf.library.AKafkaConsumer
import no.nav.sf.library.KafkaConsumerStates
import no.nav.sf.library.KafkaMessage
import no.nav.sf.library.SFsObjectRest
import no.nav.sf.library.SalesforceClient
import no.nav.sf.library.encodeB64
import no.nav.sf.library.isSuccess
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer

private val log = KotlinLogging.logger {}

sealed class ExitReason {
    object NoSFClient : ExitReason()
    object NoKafkaClient : ExitReason()
    object NoEvents : ExitReason()
    object Work : ExitReason()
}

data class WorkSettings(
    val kafkaConfig: Map<String, Any> = AKafkaConsumer.configBase + mapOf<String, Any>(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java
    ),
    val kafkaConfigAlternative: Map<String, Any> = AKafkaConsumer.configAlternativeBase + mapOf<String, Any>(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java
    ),
    val sfClient: SalesforceClient = SalesforceClient()
)

// some work metrics
data class WMetrics(
    val noOfConsumedEvents: Gauge = Gauge
        .build()
        .name("kafka_consumed_event_gauge")
        .help("No. of consumed records from kafka since last work session")
        .register(),
    val noOfPostedEvents: Gauge = Gauge
        .build()
        .name("sf_posted_event_gauge")
        .help("No. of posted records to Salesforce since last work session")
        .register(),
    val consumerIssues: Gauge = Gauge
        .build()
        .name("consumer_issues")
        .help("consumer issues")
        .register(),
    val producerIssues: Gauge = Gauge
        .build()
        .name("producer_issues")
        .help("producer issues")
        .register(),
    val noOfInvestigatedEvents: Gauge = Gauge
        .build()
        .name("kafka_investigated_event_gauge")
        .help("No. of investigated activity events from kafka since last work session")
        .register()
) {
    fun clearAll() {
        noOfConsumedEvents.clear()
        noOfPostedEvents.clear()
        consumerIssues.clear()
        producerIssues.clear()
    }
}

val workMetrics = WMetrics()

internal fun work(ws: WorkSettings): Pair<WorkSettings, ExitReason> {

    log.info { "bootstrap work session starting" }
    workMetrics.clearAll()

    var exitReason: ExitReason = ExitReason.NoSFClient

    ws.sfClient.enablesObjectPost { postActivities ->

        exitReason = ExitReason.NoKafkaClient
        val kafkaConsumer = AKafkaConsumer<ByteArray, ByteArray>(
            config = ws.kafkaConfig,
            fromBeginning = false
        )

        kafkaConsumer.consume { consumerRecords ->

            exitReason = ExitReason.NoEvents
            if (consumerRecords.isEmpty) return@consume KafkaConsumerStates.IsFinished

            exitReason = ExitReason.Work
            workMetrics.noOfConsumedEvents.inc(consumerRecords.count().toDouble())

            val orgObjectBases = consumerRecords.map {
                OrgObjectBase.fromProto(it.key(), it.value()).also { oob ->
                    if (oob is OrgObjectProtobufIssue)
                        log.error { "Protobuf parsing issue for offset ${it.offset()} in partition ${it.partition()}" }
                }
            }

            if (orgObjectBases.filterIsInstance<OrgObjectProtobufIssue>().isNotEmpty()) {
                log.error { "Protobuf issues - leaving kafka consumer loop" }
                workMetrics.consumerIssues.inc()
                return@consume KafkaConsumerStates.HasIssues
            }

            val topic = kafkaConsumer.topics.first()

            val orgObjects = orgObjectBases.filterIsInstance<OrgObject>()
                .filter { it.key.orgNumber.isNotEmpty() && it.value.orgAsJson.isNotEmpty() }

            val body = SFsObjectRest(
                records = orgObjects.map {
                    KafkaMessage(
                        topic = topic,
                        key = "${it.key.orgNumber}#${it.key.orgType}#${it.value.jsonHashCode}",
                        value = it.value.orgAsJson.encodeB64()
                    )
                }
            ).toJson()

            when (postActivities(body).isSuccess()) {
                true -> {
                    workMetrics.noOfPostedEvents.inc(consumerRecords.count().toDouble())
                    KafkaConsumerStates.IsOk
                }
                false -> {
                    workMetrics.producerIssues.inc()
                    KafkaConsumerStates.HasIssues
                }
            }
        }
    }
    log.info { "bootstrap work session finished - $exitReason" }

    return Pair(ws, exitReason)
}
