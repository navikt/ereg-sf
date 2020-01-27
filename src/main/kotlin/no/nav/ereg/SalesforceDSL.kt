package no.nav.ereg

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.util.Base64
import mu.KotlinLogging
import org.apache.http.HttpHost
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClients
import org.http4k.client.ApacheClient
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.format.Jackson
import org.http4k.format.Jackson.auto

private val log = KotlinLogging.logger { }

internal data class SFAuthorization(
    val access_token: String = "",
    val instance_url: String = "",
    val id: String = "",
    val token_type: String = "",
    val issued_at: String = "",
    val signature: String = ""
)

internal fun getHTTPClient(hp: String = EnvVarFactory.envVar.httpsProxy) =
    if (hp.isNotEmpty())
        ApacheClient(client =
        HttpClients.custom()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setProxy(HttpHost(URI(hp).host, URI(hp).port, URI(hp).scheme))
                    .setRedirectsEnabled(false)
                    .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                    .build())
            .build()
        ) else ApacheClient()

internal fun getSFAuthorization(ev: EnvVar): SFAuthorization {

    val responseLens = Body.auto<SFAuthorization>().toLens()

    val resp = getHTTPClient().invoke(
        Request(Method.POST, ev.sfOAuthUrl)
            .query("grant_type", "password")
            .query("client_id", ev.sfClientID)
            .query("client_secret", ev.sfClientSecret)
            .query("username", ev.sfUsername)
            .query("password", ev.sfPassword)
            .body("")
    )

    return if (resp.status == Status.OK) {
        try { responseLens(resp) } catch (e: Exception) {
            log.error { "Couldn't parse 200(OK) JSON authorization response from Salesforce" }
            ServerState.state = ServerStates.SalesforceIssues
            SFAuthorization()
        }
    } else {
        log.error { "Invalid authorization request sent to Salesforce - ${resp.status.description}" }
        ServerState.state = ServerStates.SalesforceIssues
        SFAuthorization()
    }
}

internal fun postToSalsesforce(
    ev: EnvVar,
    sfAuth: SFAuthorization,
    body: String,
    noOfOrgs: Int
): Boolean {

    val responseTime = Metrics.responseLatency.startTimer()

    return getHTTPClient().invoke(
        Request(
            Method.POST,
            "${sfAuth.instance_url}${ev.sfRestEndpoint}"
        )
            .header("Authorization", "Bearer ${sfAuth.access_token}")
            .header("Content-Type", "application/json;charset=UTF-8")
            .body(body)
    ).also {
        responseTime.observeDuration()

        if (it.status == Status.OK)
            log.info { "Posted $noOfOrgs organisations to Salesforce - ${it.status.description}" }
        else {
            log.error { "Couldn't post $noOfOrgs organisations to Salesforce - ${it.status.description}" }
            ServerState.state = ServerStates.SalesforceIssues
        }
    }.status == Status.OK
}

internal fun List<OrgObject>.postToSalesforce(ev: EnvVar, sfAuth: SFAuthorization): Boolean {

    val json = Jackson
    val orgsAsJson: List<JsonNode> = this.map { it.toJson(ev) }
    val body: String = json.obj(
        "allOrNone" to json.boolean(true),
        "records" to json.array(orgsAsJson)
    ).toString()

    return postToSalsesforce(ev, sfAuth, body, this.size).also { postOk ->
        if (postOk) {
            Metrics.successfulRequest.inc()
            this.forEach { Metrics.sentOrgs.labels(it.key.orgType.toString()).inc() }
        } else Metrics.failedRequest.inc()
    }
}

internal fun OrgObject.toJson(ev: EnvVar): JsonNode {

    val json = Jackson
    return json.obj(
        "attributes" to json.obj("type" to json.string("KafkaMessage__c")),
        "CRM_Topic__c" to json.string(ev.kafkaTopic),
        "CRM_Key__c" to json.string("${this.key.orgNumber}#${this.key.orgType}#${this.value.jsonHashCode}"),
        "CRM_Value__c" to json.string(Base64.getEncoder().encodeToString(this.value.orgAsJson.toByteArray()))
    )
}
