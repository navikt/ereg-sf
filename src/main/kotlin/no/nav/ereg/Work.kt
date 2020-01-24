package no.nav.ereg

import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

private val log = KotlinLogging.logger {}

internal fun work(ev: EnvVar) {

    log.info { "bootstrap work session starting" }

    getKafkaConsumerByConfig<ByteArray, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to ev.kafkaBrokers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.GROUP_ID_CONFIG to ev.kafkaClientID,
            ConsumerConfig.CLIENT_ID_CONFIG to ev.kafkaClientID,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false"
        ).let { cMap ->
            if (ev.kafkaSecurityEnabled())
                cMap.addKafkaSecurity(ev.kafkaUser, ev.kafkaPassword, ev.kafkaSecProt, ev.kafkaSaslMec)
            else cMap
        },
        listOf(ev.kafkaTopic)
    ) { cRecords ->
        if (!cRecords.isEmpty) {
            Metrics.sentOrgs.inc(cRecords.count().toDouble())
            // TODO post records to SF, with error handling
            // TODO feedback to KafkaDSL should be IsOk in order to commit
            ConsumerStates.IsOkNoCommit
        } else {
            log.info { "Cache completed - leaving kafka consumer loop" }
            ConsumerStates.IsFinished
        }
    }
}
