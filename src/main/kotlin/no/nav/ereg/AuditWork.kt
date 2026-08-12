package no.nav.ereg

import com.google.gson.Gson
import com.google.gson.JsonObject
import mu.KotlinLogging
import no.nav.ereg.kafka.AKafkaConsumer
import no.nav.ereg.kafka.KafkaConsumerStates
import no.nav.ereg.proto.EregOrganisationEventKey
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class CachedKafkaEvent(
    val offset: Long,
    val orgNumber: String,
    val orgType: EregOrganisationEventKey.OrgType,
    val name: String?,
    val registrationDate: LocalDate?,
    val json: String?,
    val isTombstone: Boolean,
)

data class AuditEventRow(
    val offset: Long,
    val orgNumber: String,
    val orgType: String,
    val name: String?,
    val registrationDate: String?,
    val isTombstone: Boolean,
)

fun CachedKafkaEvent.toAuditEventRow(): AuditEventRow =
    AuditEventRow(
        offset = offset,
        orgNumber = orgNumber,
        orgType = orgType.name,
        name = name,
        registrationDate = registrationDate?.toString(),
        isTombstone = isTombstone,
    )

private val gson = Gson()

fun extractAuditFields(json: String): Pair<String?, LocalDate?> =
    try {
        val root = gson.fromJson(json, JsonObject::class.java)

        val name =
            root
                .get("navn")
                ?.takeIf { !it.isJsonNull }
                ?.asString

        val registrationDate =
            root
                .get("registreringsdatoEnhetsregisteret")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let(LocalDate::parse)

        Pair(name, registrationDate)
    } catch (e: Exception) {
//        log.warn(e) {
//            "Could not extract audit fields from JSON"
//        }

        Pair(null, null)
    }

class KafkaEventAuditCache {
    private val eventsByOrg =
        ConcurrentHashMap<String, MutableList<CachedKafkaEvent>>()

    private val eventsByType =
        ConcurrentHashMap<
            EregOrganisationEventKey.OrgType,
            MutableList<CachedKafkaEvent>,
        >()

    fun add(event: CachedKafkaEvent) {
        eventsByOrg
            .computeIfAbsent(event.orgNumber) {
                Collections.synchronizedList(mutableListOf())
            }.add(event)

        eventsByType
            .computeIfAbsent(event.orgType) {
                Collections.synchronizedList(mutableListOf())
            }.add(event)
    }

    fun byOrg(orgNumber: String): List<CachedKafkaEvent> =
        eventsByOrg[orgNumber]?.let { synchronized(it) { it.toList() } }
            ?: emptyList()

    fun byType(orgType: EregOrganisationEventKey.OrgType): List<CachedKafkaEvent> =
        eventsByType[orgType]?.let { synchronized(it) { it.toList() } }
            ?: emptyList()

    fun all(): List<CachedKafkaEvent> =
        eventsByOrg.values.flatMap { list ->
            synchronized(list) {
                list.toList()
            }
        }

    fun count(): Int = eventsByOrg.values.sumOf { it.size }

    fun count(orgType: EregOrganisationEventKey.OrgType): Int = eventsByType[orgType]?.size ?: 0

    fun clear() {
        eventsByOrg.clear()
        eventsByType.clear()
    }
}

internal fun auditWork(
    ws: WorkSettings,
    auditCache: KafkaEventAuditCache,
): Pair<WorkSettings, ExitReason> {
    var latestOffset = -1L

    val log = KotlinLogging.logger {}

    log.info { "audit work session starting client ${ws.sfClient}" }

    var exitReason: ExitReason = ExitReason.NoSFClient

    val kafkaConsumer =
        AKafkaConsumer<ByteArray, ByteArray?>(
            config = ws.kafkaConfigAudit,
            fromBeginning = true,
            hasRunOnce = false,
            topic =
                getEnvOrDefault(
                    EV_KAFKA_TOPIC_CACHE,
                    "NOT FOUND Kafka topic",
                ),
        )

    kafkaConsumer.consume { consumerRecords ->

        if (consumerRecords.isEmpty()) {
            exitReason = ExitReason.NoEvents
            return@consume KafkaConsumerStates.IsFinished
        }

        exitReason = ExitReason.Work

        consumerRecords.forEach { record ->

            latestOffset = maxOf(latestOffset, record.offset())

            val parsed =
                OrgObjectBase.fromProto(
                    record.key(),
                    record.value(),
                )

            when (parsed) {
                is OrgObject -> {
                    val orgNumber = parsed.key.orgNumber

                    if (orgNumber.isNotEmpty()) {
                        val json = parsed.value.orgAsJson as String

                        val (name, registrationDate) =
                            extractAuditFields(json)

                        auditCache.add(
                            CachedKafkaEvent(
                                offset = record.offset(),
                                orgNumber = orgNumber,
                                orgType = parsed.key.orgType,
                                name = name,
                                registrationDate = registrationDate,
                                json = json,
                                isTombstone = false,
                            ),
                        )
                    }
                }

                is OrgObjectTombstone -> {
                    val orgNumber = parsed.key.orgNumber

                    if (orgNumber.isNotEmpty()) {
                        auditCache.add(
                            CachedKafkaEvent(
                                offset = record.offset(),
                                orgNumber = orgNumber,
                                orgType = parsed.key.orgType,
                                name = null,
                                registrationDate = null,
                                json = null,
                                isTombstone = true,
                            ),
                        )
                    }
                }

                is OrgObjectProtobufIssue -> {
                    log.error {
                        "Audit consumer protobuf issue " +
                            "offset=${record.offset()} " +
                            "partition=${record.partition()}"
                    }

                    return@consume KafkaConsumerStates.HasIssues
                }
            }
        }

        log.info {
            "Audit cache consumed ${consumerRecords.count()} events, " +
                "cache now contains ${auditCache.count()} events"
        }

        auditCache
            .byType(EregOrganisationEventKey.OrgType.ENHET)
            .firstOrNull()
            ?.let { event ->
                log.info {
                    """
                    AUDIT SAMPLE ENHET
                    orgNumber=${event.orgNumber}
                    isTombstone=${event.isTombstone}
                    json=${event.json}
                    """.trimIndent()
                }
            }

        auditCache
            .byType(EregOrganisationEventKey.OrgType.UNDERENHET)
            .firstOrNull()
            ?.let { event ->
                log.info {
                    """
                    AUDIT SAMPLE UNDERENHET
                    orgNumber=${event.orgNumber}
                    isTombstone=${event.isTombstone}
                    json=${event.json}
                    """.trimIndent()
                }
            }

        KafkaConsumerStates.IsOk
    }

    log.info {
        "audit work session finished - $exitReason. " +
            "latest offset $latestOffset, " +
            "cached events ${auditCache.count()}, " +
            "ENHET=${auditCache.count(EregOrganisationEventKey.OrgType.ENHET)}, " +
            "UNDERENHET=${auditCache.count(EregOrganisationEventKey.OrgType.UNDERENHET)}"
    }

    return Pair(ws, exitReason)
}
