package no.nav.ereg

import io.prometheus.client.Gauge
import mu.KotlinLogging
import no.nav.sf.library.AKafkaConsumer
import no.nav.sf.library.AnEnvironment
import no.nav.sf.library.EV_kafkaClientID
import no.nav.sf.library.KafkaConsumerStates
import no.nav.sf.library.KafkaMessage
import no.nav.sf.library.PROGNAME
import no.nav.sf.library.SFsObjectRest
import no.nav.sf.library.SalesforceClient
import no.nav.sf.library.encodeB64
import no.nav.sf.library.isSuccess
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

private val log = KotlinLogging.logger {}

private const val EV_kafka_topic_tombstones = "KAFKA_TOPIC_TOMBSTONES"

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
    val kafkaConfigAlternative: Map<String, Any> = AKafkaConsumer.configBase + mapOf<String, Any>(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.GROUP_ID_CONFIG to AnEnvironment.getEnvOrDefault(EV_kafkaClientID, PROGNAME) + "_init",
            ConsumerConfig.CLIENT_ID_CONFIG to AnEnvironment.getEnvOrDefault(EV_kafkaClientID, PROGNAME) + "_init"
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
    val noOfConsumedTombstones: Gauge = Gauge
            .build()
            .name("kafka_consumed_tombstones_gauge")
            .help("kafka_consumed_tombstones_gauge")
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
        .help("producer issues ")
        .register(),
    val noOfInvestigatedEvents: Gauge = Gauge
        .build()
        .name("kafka_investigated_event_gauge")
        .help("No. of investigated activity events from kafka since last work session")
        .register()
) {
    fun clearAll() {
        noOfConsumedEvents.clear()
        noOfConsumedTombstones.clear()
        noOfPostedEvents.clear()
        consumerIssues.clear()
        producerIssues.clear()
    }
}

val workMetrics = WMetrics()

// var localLogExample = false

internal fun work(ws: WorkSettings): Pair<WorkSettings, ExitReason> {

    var latestOffset = -1L
    log.info { "bootstrap work session starting" }
    workMetrics.clearAll()

    var exitReason: ExitReason = ExitReason.NoSFClient

    ws.sfClient.enablesObjectPost { postActivities ->

        exitReason = ExitReason.NoKafkaClient
        val kafkaConsumer = AKafkaConsumer<ByteArray, ByteArray?>(
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
            val topicKafkaTombstones = AnEnvironment.getEnvOrDefault(EV_kafka_topic_tombstones, "NOT FOUND Kafka topic tombstones")

            val orgObjects = orgObjectBases.filterIsInstance<OrgObject>()
                .filter { it.key.orgNumber.isNotEmpty() && it.value.orgAsJson.isNotEmpty() }

            val orgTombStones = orgObjectBases.filterIsInstance<OrgObjectTombstone>()
                    .filter { it.key.orgNumber.isNotEmpty() }

            consumerRecords.lastOrNull()?.let {
                latestOffset = it.offset()
            }

            workMetrics.noOfConsumedTombstones.inc(orgTombStones.size.toDouble())

            val body = SFsObjectRest(
                    records = orgObjects.map {
                        KafkaMessage(
                                topic = topic,
                                key = "${it.key.orgNumber}#${it.key.orgType}#${it.value.jsonHashCode}",
                                value = it.value.orgAsJson.encodeB64()
                        )
                    } + orgTombStones.map {
                        KafkaMessage(
                                topic = topicKafkaTombstones,
                                key = "${it.key.orgNumber}",
                                value = "${it.key.orgNumber}")
                    }
            ).toJson()

            /*
            if (!localLogExample) {
                val bodyReadable = SFsObjectRest(
                    records = orgObjects.map {
                        KafkaMessage(
                            topic = topic,
                            key = "${it.key.orgNumber}#${it.key.orgType}#${it.value.jsonHashCode}",
                            value = it.value.orgAsJson
                        )
                    } + orgTombStones.map {
                        KafkaMessage(
                            topic = topicKafkaTombstones,
                            key = "${it.key.orgNumber}",
                            value = "${it.key.orgNumber}")
                    }
                ).toJson()
                localLogExample = true
                Investigate.writeText("Body of a post:\n$bodyReadable")
            }

             */

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
    log.info { "bootstrap work session finished - $exitReason . No of consumed total events ${workMetrics.noOfConsumedEvents.get().toInt()} of which tombstones: ${workMetrics.noOfConsumedTombstones.get().toInt()}, latest offset $latestOffset" }

    return Pair(ws, exitReason)
}
