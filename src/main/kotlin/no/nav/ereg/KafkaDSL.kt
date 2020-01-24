package no.nav.ereg

import java.io.Serializable
import java.time.Duration
import java.util.Properties
import kotlin.Exception
import mu.KotlinLogging
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SaslConfigs

private val log = KotlinLogging.logger {}

internal sealed class ConsumerStates {
    object IsOk : ConsumerStates()
    object IsOkNoCommit : ConsumerStates()
    object HasIssues : ConsumerStates()
    object IsFinished : ConsumerStates()
}

internal fun <K, V> getKafkaConsumerByConfig(
    config: Map<String, Any>,
    topics: List<String>,
    fromBeginning: Boolean = false,
    doConsume: (ConsumerRecords<K, V>) -> ConsumerStates
): Boolean =
    try {
        KafkaConsumer<K, V>(Properties().apply { config.forEach { set(it.key, it.value) } })
            .apply {
                if (fromBeginning)
                    this.runCatching {
                        assign(
                            topics.flatMap { topic ->
                                partitionsFor(topic).map { TopicPartition(it.topic(), it.partition()) }
                            }
                        )
                    }.onFailure {
                        ServerState.state = ServerStates.KafkaIssues
                        log.error { "Failure for topic partition(s) assignment for $topics - ${it.message}" }
                    }
                else
                    this.runCatching {
                        subscribe(topics)
                    }.onFailure {
                        ServerState.state = ServerStates.KafkaIssues
                        log.error { "Failure during subscription for $topics -  ${it.message}" }
                    }
            }
            .use { c ->

                if (fromBeginning) c.runCatching {
                    c.seekToBeginning(emptyList())
                }.onFailure {
                    ServerState.state = ServerStates.KafkaIssues
                    log.error { "Failure for SeekToBeginning - ${it.message}" }
                }

                var keepGoing = true
                while (!ShutdownHook.isActive() && ServerState.isOk() && keepGoing) {
                    keepGoing = c.pollAndConsumptionIsOk(doConsume)
                }
                log.info { "Closing KafkaConsumer" }
            }
        true
    } catch (e: Exception) {
        ServerState.state = ServerStates.KafkaIssues
        log.error { "Failure during kafka consumer construction - ${e.message}" }
        false
    }

private fun <K, V> KafkaConsumer<K, V>.pollAndConsumptionIsOk(doConsume: (ConsumerRecords<K, V>) -> ConsumerStates): Boolean = this.let { c ->
    try {
        c.poll(Duration.ofMillis(5_000)).let { cRecords ->
            when (doConsume(cRecords)) {
                ConsumerStates.IsOk -> {
                    c.commitSync()
                    true
                }
                ConsumerStates.IsOkNoCommit -> true
                ConsumerStates.HasIssues -> {
                    ServerState.state = ServerStates.KafkaConsumerIssues
                    false
                }
                ConsumerStates.IsFinished -> {
                    log.warn { "Consumer logic requests stop of consumption" }
                    false
                }
            }
        }
    } catch (e: Exception) {
        ServerState.state = ServerStates.KafkaIssues
        log.error { "Failure during poll or commit, leaving - ${e.message}" }
        false
    }
}

internal fun Map<String, Serializable>.addKafkaSecurity(
    username: String,
    password: String,
    secProtocol: String = "SASL_PLAINTEXT",
    saslMechanism: String = "PLAIN"
): Map<String, Any> = this.let {

    val mMap = this.toMutableMap()

    mMap[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = secProtocol
    mMap[SaslConfigs.SASL_MECHANISM] = saslMechanism

    val jaasPainLogin = "org.apache.kafka.common.security.plain.PlainLoginModule"
    val jaasRequired = "required"

    mMap[SaslConfigs.SASL_JAAS_CONFIG] = "$jaasPainLogin $jaasRequired " +
            "username=\"$username\" password=\"$password\";"

    mMap.toMap()
}
