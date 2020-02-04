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

private fun SFAuthorization.isOk(): Boolean = access_token.isNotEmpty() && instance_url.isNotEmpty() && token_type.isNotEmpty()

private fun SFAuthorization.getBaseRequest(ev: EnvVar): Request = Request(
    Method.POST, "${instance_url}${ev.sfRestEndpoint}")
    .header("Authorization", "$token_type $access_token")
    .header("Content-Type", "application/json;charset=UTF-8")

private fun getHTTPClient(hp: String = EnvVarFactory.envVar.httpsProxy) =
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

internal fun getSalesforcePost(ev: EnvVar, doSomething: (doPost: (List<OrgObject>) -> Boolean) -> Unit): Boolean =
    getHTTPClient().let { client ->

        val accessTokenRequest = Request(Method.POST, ev.sfOAuthUrl)
            .query("grant_type", "password")
            .query("client_id", ev.sfClientID)
            .query("client_secret", ev.sfClientSecret)
            .query("username", ev.sfUsername)
            .query("password", ev.sfPassword) // TODO should be url encoded in case of incompatible pwd?
            .body("")

        // the user for getting access token must be preauthorized in Salesforce

        val getSfAuth: () -> SFAuthorization = {
            val responseLens = Body.auto<SFAuthorization>().toLens()
            val response = client.invoke(accessTokenRequest)

            if (response.status == Status.OK)
                try { responseLens(response)
                } catch (e: Exception) {
                    log.error { "Couldn't parse 200(OK) JSON authorization response from Salesforce" }
                    ServerState.state = ServerStates.SalesforceIssues
                    SFAuthorization()
                }
            else {
                log.error { "Failed authorization request to Salesforce - ${response.status.description}" }
                ServerState.state = ServerStates.SalesforceIssues
                SFAuthorization()
            }
        }

        var sfAuth = getSfAuth()

        if (sfAuth.isOk()) {

            val doPost: (List<OrgObject>) -> Boolean = { list ->
                val responseTime = Metrics.responseLatency.startTimer()
                val response = client.invoke(sfAuth.getBaseRequest(ev).body(list.toJsonPayload(ev))).also {
                    responseTime.observeDuration() }

                val retryOk = when (response.status) {
                    Status.OK -> {
                        log.info { "Posted ${list.size} organisations to Salesforce - ${response.status.description}" }
                        Metrics.successfulRequest.inc()
                        list.forEach { o -> Metrics.sentOrgs.labels(o.key.orgType.toString()).inc() }
                        false
                    }
                    Status.UNAUTHORIZED -> {
                        // refresh sf authorization and another attempt
                        log.info { "${response.status.description} - refresh of token and retry" }
                        Metrics.failedRequest.inc()

                        sfAuth = getSfAuth()
                        client.invoke(sfAuth.getBaseRequest(ev).body(list.toJsonPayload(ev))).also {
                            if (it.status == Status.OK) {
                                log.info { "Successful retry - posted ${list.size} organisations to Salesforce - ${it.status.description}" }
                                Metrics.successfulRequest.inc()
                                list.forEach { o -> Metrics.sentOrgs.labels(o.key.orgType.toString()).inc() }
                            } else {
                                log.info { "Failed retry" }
                                ServerState.state = ServerStates.SalesforceIssues
                                Metrics.failedRequest.inc()
                            }
                        }.status == Status.OK
                    }
                    else -> {
                        log.error { "Couldn't post ${list.size} organisations to Salesforce - ${response.status.description}" }
                        ServerState.state = ServerStates.SalesforceIssues
                        Metrics.failedRequest.inc()
                        false
                    }
                }
                response.status == Status.OK || retryOk
            }

            doSomething(doPost)
            true
        } else false
    }

private fun List<OrgObject>.toJsonPayload(ev: EnvVar): String = Jackson.let { json ->

    json.obj(
        "allOrNone" to json.boolean(true),
        "records" to json.array(this.map { it.toJson(ev) })
    ).toString()
}

private fun OrgObject.toJson(ev: EnvVar): JsonNode = Jackson.let { json ->

    json.obj(
        "attributes" to json.obj("type" to json.string("KafkaMessage__c")),
        "CRM_Topic__c" to json.string(ev.kafkaTopic),
        "CRM_Key__c" to json.string("${this.key.orgNumber}#${this.key.orgType}#${this.value.jsonHashCode}"),
        "CRM_Value__c" to json.string(Base64.getEncoder().encodeToString(this.value.orgAsJson.toByteArray()))
    )
}
