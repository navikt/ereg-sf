package no.nav.ereg

import java.io.File
import mu.KotlinLogging
import no.nav.sf.library.AKafkaConsumer
import no.nav.sf.library.AnEnvironment
import no.nav.sf.library.KafkaConsumerStates
import no.nav.sf.library.PROGNAME

private val log = KotlinLogging.logger {}

const val EV_kafkaConsumerTopic = "KAFKA_TOPIC"
val kafkaEregTopic = AnEnvironment.getEnvOrDefault(EV_kafkaConsumerTopic, "$PROGNAME-consumer")

internal fun investigate(ws: WorkSettings) {

    val kafkaConsumer = AKafkaConsumer<ByteArray, ByteArray>(
            config = ws.kafkaConfigAlternative, // Separate clientId - do not affect offset of normal read
            fromBeginning = true,
            topics = listOf(kafkaEregTopic)
    )

    val recordInCache: MutableList<String> = mutableListOf()
    workMetrics.noOfInvestigatedEvents.clear()

    kafkaConsumer.consume { consumerRecords ->

        if (consumerRecords.isEmpty) {
            log.info { "Investigate  - No more consumer records found on topic" }
            return@consume KafkaConsumerStates.IsFinished
        }
        log.debug { "Investigate - Consumer records batch of ${consumerRecords.count()} found" }

        workMetrics.noOfInvestigatedEvents.inc(consumerRecords.count().toDouble())

        val orgObjectBases = consumerRecords.map {
            OrgObjectBase.fromProto(it.key(), it.value()).also { oob ->
                if (oob is OrgObjectProtobufIssue)
                    log.error { "Investigate - Protobuf parsing issue for offset ${it.offset()} in partition ${it.partition()}" }
            }
        }
        val orgObjects = orgObjectBases.filterIsInstance<OrgObject>()
                .filter { it.key.orgNumber.isNotEmpty() && it.value.orgAsJson.isNotEmpty() }

        orgObjects.filter { o -> o.key.orgNumber == "922869820" }.forEach {
            log.info { "Found chosen org entry in cache" }
            recordInCache.add(it.value.orgAsJson)
        }

        KafkaConsumerStates.IsOk
    }
    var msg: String = "Not found"
    if (recordInCache.isNotEmpty()) {
        msg = ""
        recordInCache.forEach { r -> msg += r + "\n" }
    }

    log.info { "Attempt file storage" }
    File("/tmp/investigate").writeText("Test output result: $msg")
    log.info { "File storage done" }
}
