package no.nav.ereg

import mu.KotlinLogging
import no.nav.ereg.proto.EregOrganisationEventKey
import no.nav.ereg.proto.EregOrganisationEventValue
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

private val log = KotlinLogging.logger {}

internal data class OrgObject(
    val key: EregOrganisationEventKey,
    val value: EregOrganisationEventValue
)

internal fun work(ev: EnvVar) {

    log.info { "bootstrap work session starting" }

    log.info { "Enable salesforce client" }

    getSalesforcePost(ev) { doPost ->

        getKafkaConsumerByConfig<ByteArray, ByteArray>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to ev.kafkaBrokers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
                ConsumerConfig.GROUP_ID_CONFIG to ev.kafkaClientID,
                ConsumerConfig.CLIENT_ID_CONFIG to ev.kafkaClientID,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 200, // 200 is the maximum batch size accepted by salesforce
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false"
            ).let { cMap ->
                if (ev.kafkaSecurityEnabled())
                    cMap.addKafkaSecurity(ev.kafkaUser, ev.kafkaPassword, ev.kafkaSecProt, ev.kafkaSaslMec)
                else cMap
            },
            listOf(ev.kafkaTopic)
        ) { cRecords ->
            if (!cRecords.isEmpty) {

                when (doPost(cRecords
                        .map { OrgObject(it.key().protobufSafeParseKey(), it.value().protobufSafeParseValue()) }
                        .filter { it.key.orgNumber.isNotEmpty() && it.value.orgAsJson.isNotEmpty() })) {
                    true -> ConsumerStates.IsOkNoCommit
                    false -> ConsumerStates.HasIssues
                }
            } else {
                log.info { "Kafka events completed for now - leaving kafka consumer loop" }
                ConsumerStates.IsFinished
            }
        }
    }
}
