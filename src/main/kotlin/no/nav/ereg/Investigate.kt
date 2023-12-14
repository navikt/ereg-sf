package no.nav.ereg

import mu.KotlinLogging
import no.nav.ereg.kafka.AKafkaConsumer
import no.nav.ereg.kafka.KafkaConsumerStates
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

const val EV_kafkaConsumerTopic = "KAFKA_TOPIC"
val kafkaEregTopic = getEnvOrDefault(EV_kafkaConsumerTopic, "No topic set")

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
    workMetrics.noOfInvestigatedEvents.clear()
    var hasReadFromCache = false
    val lastFiveKeys: MutableList<String> = mutableListOf()
    val lastFiveKeysAndType: MutableList<String> = mutableListOf()
    val lastFiveKeysPattern: MutableList<String> = mutableListOf("912477746", "829488442", "929521471", "929628314", "929745086")

    val kafkaConsumer = AKafkaConsumer<ByteArray, ByteArray>(
        config = ws.kafkaConfigAlternative, // Separate clientId - do not affect offset of normal read
        fromBeginning = true // ,
        // topics = listOf(kafkaCacheTopicGcp)
    )

    val recordInCache: MutableList<String> = mutableListOf()
    workMetrics.noOfInvestigatedEvents.clear()

    kafkaConsumer.consume { consumerRecords ->

        if (consumerRecords.isEmpty) {
            log.info { "Investigate  - No more consumer records found on topic" }
            if (!hasReadFromCache) {
                log.info { "Investigate - cache not ready will attempt again in a minute" }
                Bootstrap.conditionalWait(60000)
                return@consume KafkaConsumerStates.IsOk
            }
            return@consume KafkaConsumerStates.IsFinished
        }
        hasReadFromCache = true
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
                lastFiveKeysAndType.add("${it.key.orgNumber} OBJECT")
                if (lastFiveKeysPattern.contains(it.key.orgNumber)) {
                    log.info { "Investigate Key match ${it.key.orgNumber} object" }
                }
            } else if (it is OrgObjectTombstone) {
                lastFiveKeys.add(it.key.orgNumber)
                lastFiveKeysAndType.add("${it.key.orgNumber} TOMBSTONE")
                if (lastFiveKeysPattern.contains(it.key.orgNumber)) {
                    log.info { "Investigate Key match ${it.key.orgNumber} tombstone" }
                }
            } else {
                log.error { "Unknown data on cache queue" }
            }
            if (lastFiveKeys.size > 5) {
                lastFiveKeys.removeAt(0)
                lastFiveKeysAndType.removeAt(0)
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

    log.info { "Investigate run done ${workMetrics.noOfInvestigatedEvents.get().toInt()} records examined" }
    File("/tmp/lastfivekeys").writeText(lastFiveKeys.joinToString("\n"))
    File("/tmp/lastfivekeysandtype").writeText(lastFiveKeysAndType.joinToString("\n"))

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
