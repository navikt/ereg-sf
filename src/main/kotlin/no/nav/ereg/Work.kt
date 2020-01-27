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

    log.info { "Get salesforce authorization" }
    val sfAuth = getSFAuthorization(ev)

    if (sfAuth.access_token.isEmpty() || sfAuth.instance_url.isEmpty()) return

    // TODO to be removed - give Magnus something to work with - not to much
    var counter = 0

    getKafkaConsumerByConfig<ByteArray, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to ev.kafkaBrokers,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
            ConsumerConfig.GROUP_ID_CONFIG to ev.kafkaClientID,
            ConsumerConfig.CLIENT_ID_CONFIG to ev.kafkaClientID,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false"
        ).let { cMap ->
            if (ev.kafkaSecurityEnabled())
                cMap.addKafkaSecurity(ev.kafkaUser, ev.kafkaPassword, ev.kafkaSecProt, ev.kafkaSaslMec)
            else cMap
        },
        listOf(ev.kafkaTopic)
    ) { cRecords ->
        if (!cRecords.isEmpty) {
            counter += cRecords.count() // TODO to be removed

            when (cRecords
                .map { OrgObject(it.key().protobufSafeParseKey(), it.value().protobufSafeParseValue()) }
                .filter { it.key.orgNumber.isNotEmpty() && it.value.orgAsJson.isNotEmpty() }
                .chunked(199)
                .listIterator().postToSalesforce(ev, sfAuth)) {
                true -> if (counter > 100) ConsumerStates.IsFinished else ConsumerStates.IsOkNoCommit
                false -> ConsumerStates.HasIssues
            }
        } else {
            log.info { "Kafka events completed for now - leaving kafka consumer loop" }
            ConsumerStates.IsFinished
        }
    }
}

private tailrec fun ListIterator<List<OrgObject>>.postToSalesforce(ev: EnvVar, sfAuth: SFAuthorization, postOk: Boolean = true): Boolean =
    if (!this.hasNext() || !postOk) postOk
    else this.postToSalesforce(ev, sfAuth, this.next().postToSalesforce(ev, sfAuth))
