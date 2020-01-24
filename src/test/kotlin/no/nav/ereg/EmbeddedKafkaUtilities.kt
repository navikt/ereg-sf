package no.nav.ereg

import java.util.Properties
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.acl.AccessControlEntry
import org.apache.kafka.common.acl.AclBinding
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.acl.AclPermissionType
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourceType

// utility function for setting access control list
internal fun KafkaEnvironment.addProducerToTopic(username: String, topic: String) = this.let { ke ->
    ke.adminClient?.createAcls(
        listOf(AclOperation.DESCRIBE, AclOperation.WRITE, AclOperation.CREATE)
            .map { aclOp ->
                AclBinding(
                    ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL),
                    AccessControlEntry("User:$username", "*", aclOp, AclPermissionType.ALLOW)
                )
            }
    )
    ke
}

internal fun KafkaEnvironment.addConsumerToTopic(username: String, topic: String) = this.let { ke ->
    ke.adminClient?.createAcls(
        listOf(AclOperation.DESCRIBE, AclOperation.READ)
            .map { aclOp ->
                AclBinding(
                    ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL),
                    AccessControlEntry("User:$username", "*", aclOp, AclPermissionType.ALLOW)
                )
            }
    )
    ke
}

internal fun <K, V> getKafkaProducerByConfig(config: Map<String, Any>, doProduce: KafkaProducer<K, V>.() -> Unit): Boolean =
    try {
        KafkaProducer<K, V>(
            Properties().apply { config.forEach { set(it.key, it.value) } }
        ).use {
            it.doProduce()
        }
        true
    } catch (e: Exception) {
        ServerState.state = ServerStates.KafkaIssues
        false
    }

internal fun <K, V> KafkaProducer<K, V>.send(topic: String, key: K, value: V): Boolean = this.runCatching {
    send(ProducerRecord(topic, key, value)).get().hasOffset()
}
    .onFailure {
        ServerState.state = ServerStates.KafkaIssues
    }
    .getOrDefault(false)
