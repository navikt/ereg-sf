package no.nav.ereg

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import mu.KotlinLogging
import no.nav.sf.library.AKafkaConsumer
import no.nav.sf.library.AnEnvironment
import no.nav.sf.library.KafkaConsumerStates
import no.nav.sf.library.PROGNAME

private val log = KotlinLogging.logger {}

const val EV_kafkaConsumerTopic = "KAFKA_TOPIC"
val kafkaEregTopic = AnEnvironment.getEnvOrDefault(EV_kafkaConsumerTopic, "$PROGNAME-consumer")

interface Investigate {
    companion object {
        fun writeText(text: String, append: Boolean = false) {
            val timeStamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                .withZone(ZoneOffset.systemDefault())
                .format(Instant.now())
            FileOutputStream("/tmp/investigate", append).bufferedWriter().use { writer ->
                writer.write("$timeStamp : $text \n")
            }
        }
    }
}

internal fun investigate(ws: WorkSettings) {
    val lastFiveKeys: MutableList<String> = mutableListOf()
    val lastFiveKeysPattern: MutableList<String> = mutableListOf("912477746", "829488442", "929521471", "929628314", "929745086")

    val kafkaConsumer = AKafkaConsumer<ByteArray, ByteArray>(
        config = ws.kafkaConfigGcp, // Separate clientId - do not affect offset of normal read
        fromBeginning = true,
        topics = listOf(kafkaCacheTopicGcp)
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
        orgObjectBases.forEach {
            if (it is OrgObject) {
                lastFiveKeys.add(it.key.orgNumber)
            } else if (it is OrgObjectTombstone) {
                lastFiveKeys.add(it.key.orgNumber)
            } else {
                log.error { "Unknown data on cache queue" }
            }
            if (lastFiveKeys.size > 5) {
                lastFiveKeys.removeAt(0)
            }

            if (lastFiveKeys.size == 5 && lastFiveKeys[0] == lastFiveKeysPattern[0] && lastFiveKeys[1] == lastFiveKeysPattern[1] && lastFiveKeys[2] == lastFiveKeysPattern[2] && lastFiveKeys[3] == lastFiveKeysPattern[3]) {
                log.info { "Found key pattern" }
                File("/tmp/found").appendText("Found key pattern\n")
            }
        }
        /*
        val orgObjects = orgObjectBases.filterIsInstance<OrgObject>()
                .filter { it.key.orgNumber.isNotEmpty() && it.value.orgAsJson.isNotEmpty() }
        orgObjects.filter { o -> o.key.orgNumber == "132760304" }.forEach {
            log.info { "Found chosen org entry in cache" }
            recordInCache.add(it.value.orgAsJson)
        }

         */

        KafkaConsumerStates.IsOk
    }

    log.info { "Investigate run done" }
    File("/tmp/lastfivekeys").writeText(lastFiveKeys.joinToString("\n"))

    /*
    var msg: String = "Not found"
    if (recordInCache.isNotEmpty()) {
        msg = ""
        recordInCache.forEach { r -> msg += r + "\n" }
    }

    log.info { "Attempt file storage" }
    File("/tmp/investigate").writeText("Test output result: $msg")
    log.info { "File storage done" }

     */
}
