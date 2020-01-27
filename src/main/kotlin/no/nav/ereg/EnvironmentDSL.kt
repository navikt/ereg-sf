package no.nav.ereg

object EnvVarFactory {

    private var envVar_: EnvVar? = null
    val envVar: EnvVar
        get() {
            if (envVar_ == null) envVar_ = EnvVar()
            return envVar_ ?: throw AssertionError("Environment factory, null for environment variables!")
        }
}

data class EnvVar(
    // kafka details
    val kafkaBrokers: String = System.getenv("KAFKA_BROKERS")?.toString() ?: "",
    val kafkaClientID: String = System.getenv("KAFKA_CLIENTID")?.toString() ?: "",
    val kafkaSecurity: String = System.getenv("KAFKA_SECURITY")?.toString()?.toUpperCase() ?: "",
    val kafkaSecProt: String = System.getenv("KAFKA_SECPROT")?.toString() ?: "",
    val kafkaSaslMec: String = System.getenv("KAFKA_SASLMEC")?.toString() ?: "",
    val kafkaUser: String = System.getenv("KAFKA_USER")?.toString() ?: "",
    val kafkaPassword: String = System.getenv("KAFKA_PASSWORD")?.toString() ?: "",
    val kafkaTopic: String = System.getenv("KAFKA_TOPIC")?.toString() ?: "",

    // salesforce details
    val sfOAuthUrl: String = System.getenv("SF_OAUTHURL")?.toString() ?: "",
    val sfToken: String = System.getenv("SF_TOKEN")?.toString() ?: "",
    val sfRestEndpoint: String = System.getenv("SF_RESTENDPOINT")?.toString() ?: "",

    // other details
    val httpsProxy: String = System.getenv("HTTPS_PROXY")?.toString() ?: "",
    val msBetweenWork: Long = System.getenv("MS_BETWEEN_WORK")?.toLong() ?: 5 * 60 * 1_000
)

fun EnvVar.kafkaSecurityEnabled(): Boolean = kafkaSecurity == "TRUE"

fun EnvVar.kafkaSecurityComplete(): Boolean =
    kafkaSecProt.isNotEmpty() && kafkaSaslMec.isNotEmpty() && kafkaUser.isNotEmpty() && kafkaPassword.isNotEmpty()

fun EnvVar.sfDetailsComplete(): Boolean =
    sfOAuthUrl.isNotEmpty() && sfToken.isNotEmpty() && sfRestEndpoint.isNotEmpty()
