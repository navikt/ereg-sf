package no.nav.ereg

import io.prometheus.client.Gauge
import mu.KotlinLogging
import no.nav.ereg.kafka.AKafkaConsumer
import no.nav.ereg.kafka.KafkaConsumerStates
import no.nav.ereg.salesforce.KafkaMessage
import no.nav.ereg.salesforce.SFsObjectRest
import no.nav.ereg.salesforce.SalesforceClient
import no.nav.ereg.salesforce.encodeB64
import no.nav.ereg.salesforce.isSuccess
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import java.io.File

private val log = KotlinLogging.logger {}

private const val EV_kafka_topic_cache = "KAFKA_TOPIC_CACHE"
private const val EV_kafka_topic = "KAFKA_TOPIC"
private const val EV_kafka_topic_tombstones = "KAFKA_TOPIC_TOMBSTONES"

sealed class ExitReason {
    object NoSFClient : ExitReason()
    object NoKafkaClient : ExitReason()
    object NoEvents : ExitReason()
    object Work : ExitReason()
}

val kafkaCacheTopicGcp = "team-dialog.ereg-cache"

const val EV_kafkaKeystorePath = "KAFKA_KEYSTORE_PATH"
const val EV_kafkaCredstorePassword = "KAFKA_CREDSTORE_PASSWORD"
const val EV_kafkaTruststorePath = "KAFKA_TRUSTSTORE_PATH"

fun fetchEnv(env: String): String {
    return getEnvOrDefault(env, "$env missing")
}

var samples = 5

data class WorkSettings(
    val kafkaConfig: Map<String, Any> = AKafkaConsumer.configBase + mapOf<String, Any>(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to getEnvOrDefault("KAFKA_BROKERS_ON_PREM", "KAFKA_LOCAL")
    ),
    val kafkaConfigAlternative: Map<String, Any> = AKafkaConsumer.configBase + mapOf<String, Any>(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ConsumerConfig.GROUP_ID_CONFIG to getEnvOrDefault(env_KAFKA_CLIENTID, "LOCAL") + "_init",
        ConsumerConfig.CLIENT_ID_CONFIG to getEnvOrDefault(env_KAFKA_CLIENTID, "LOCAL") + "_init",
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to getEnvOrDefault("KAFKA_BROKERS_ON_PREM", "KAFKA_LOCAL")
    ),
    val kafkaConfigGcp: Map<String, Any> = AKafkaConsumer.configBase + mapOf<String, Any>(
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        "security.protocol" to "SSL",
        SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG to fetchEnv(EV_kafkaKeystorePath),
        SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG to fetchEnv(EV_kafkaCredstorePassword),
        SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG to fetchEnv(EV_kafkaTruststorePath),
        SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG to fetchEnv(EV_kafkaCredstorePassword)
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
    log.info { "bootstrap work session starting client ${ws.sfClient}" }
    workMetrics.clearAll()

    var exitReason: ExitReason = ExitReason.NoSFClient

    ws.sfClient.enablesObjectPost { postActivities ->

        exitReason = ExitReason.NoKafkaClient
        val kafkaConsumer = AKafkaConsumer<ByteArray, ByteArray?>(
            config = ws.kafkaConfigGcp,
            fromBeginning = false,
            topic = getEnvOrDefault(EV_kafka_topic_cache, "NOT FOUND Kafka topic")
        )

        kafkaConsumer.consume { consumerRecordsBeforeFilter ->

            exitReason = ExitReason.NoEvents
            if (consumerRecordsBeforeFilter.isEmpty) return@consume KafkaConsumerStates.IsFinished

            // runOnce = true

            val consumerRecords = consumerRecordsBeforeFilter.filter { it.offset() > 6286018L }

            if (consumerRecords.isEmpty()) return@consume KafkaConsumerStates.IsOk // Only records under threshold, fetch new batch
            exitReason = ExitReason.Work
            workMetrics.noOfConsumedEvents.inc(consumerRecords.count().toDouble())

            val orgObjectBasesAndOffsets = consumerRecords.map {
                Pair(OrgObjectBase.fromProto(it.key(), it.value()), it.offset()).also { oob ->
                    if (oob.first is OrgObjectProtobufIssue)
                        log.error { "Protobuf parsing issue for offset ${it.offset()} in partition ${it.partition()}" }
                }
            }

            if (orgObjectBasesAndOffsets.filter { it.first is OrgObjectProtobufIssue }.isNotEmpty()) {
                log.error { "Protobuf issues - leaving kafka consumer loop" }
                workMetrics.consumerIssues.inc()
                return@consume KafkaConsumerStates.HasIssues
            }

            val topic = getEnvOrDefault(EV_kafka_topic, "NOT FOUND Kafka topic")

            val topicKafkaTombstones = getEnvOrDefault(EV_kafka_topic_tombstones, "NOT FOUND Kafka topic tombstones")

            val orgObjects = orgObjectBasesAndOffsets.filter { it.first is OrgObject }
                .filter { (it.first as OrgObject).key.orgNumber.isNotEmpty() && (it.first as OrgObject).value.orgAsJson.isNotEmpty() }.map { Pair(it.first as OrgObject, it.second) }

            val orgTombStones = orgObjectBasesAndOffsets.filter { it.first is OrgObjectTombstone }
                .filter { (it.first as OrgObjectTombstone).key.orgNumber.isNotEmpty() }.map { Pair(it.first as OrgObjectTombstone, it.second) }

            // if (!foundFirstThresholdEvent) {
            //    orgTombStones.firstOrNull { it.first.key.orgNumber == "929745086" }?.let {
            //        foundFirstThresholdEvent = true
            //        log.info { "Found threshold event at offset ${it.second}" }
            //    }
            // }

            consumerRecords.lastOrNull()?.let {
                latestOffset = it.offset()
            }

            workMetrics.noOfConsumedTombstones.inc(orgTombStones.size.toDouble())

            val body = SFsObjectRest(
                records = orgObjects.map {
                    if (samples > 0) {
                        File("/tmp/samples").appendText("KEY: ${it.first.key.orgNumber}#${it.first.key.orgType}#${it.first.value.jsonHashCode}\nVALUE:${it.first.value.orgAsJson}\n\n")
                        samples--
                    }
                    KafkaMessage(
                        CRM_Topic__c = topic,
                        CRM_Key__c = "${it.first.key.orgNumber}#${it.first.key.orgType}#${it.first.value.jsonHashCode}",
                        CRM_Value__c = it.first.value.orgAsJson.encodeB64()
                    )
                } + orgTombStones.map {
                    KafkaMessage(
                        CRM_Topic__c = topicKafkaTombstones,
                        CRM_Key__c = "${it.first.key.orgNumber}",
                        CRM_Value__c = "${it.first.key.orgNumber}"
                    )
                }
            ).toJson()

            when (postActivities(body).isSuccess()) {
                true -> {
                    workMetrics.noOfPostedEvents.inc(consumerRecords.count().toDouble())
                    log.info { "Posted a batch of ${consumerRecords.count()}" }
                    KafkaConsumerStates.IsOk
                }
                false -> {
                    workMetrics.producerIssues.inc()
                    KafkaConsumerStates.HasIssues
                }
            }

            KafkaConsumerStates.IsOk
        }
    }
    log.info { "bootstrap work session finished - $exitReason . No of consumed total events ${workMetrics.noOfConsumedEvents.get().toInt()} of which tombstones: ${workMetrics.noOfConsumedTombstones.get().toInt()}, latest offset $latestOffset" }

    return Pair(ws, exitReason)
}
