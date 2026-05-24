package no.nav.ereg.salesforce

import mu.KotlinLogging
import no.nav.ereg.config_SALESFORCE_API_VERSION
import org.http4k.client.OkHttp
import org.http4k.core.Headers
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.io.File

private val log = KotlinLogging.logger {}

/**
 * Adapter code for merge old client with new access token handler. TODO Rewrite to more standard practice (two separate aoos not needed)
 */
class SalesforceClient(
    private val httpClient: HttpHandler = OkHttp(),
    private val accessTokenHandler: NewAccessTokenHandler = NewAccessTokenHandler(),
) {
    private fun post(body: String): Response {
        val dstUrl =
            "${accessTokenHandler.instanceUrl}/services/data/$config_SALESFORCE_API_VERSION/composite/sobjects"

        val headers: Headers =
            listOf(
                "Authorization" to "Bearer ${accessTokenHandler.accessToken}",
                "Content-Type" to "application/json;charset=UTF-8",
            )

        val request =
            Request(Method.POST, dstUrl)
                .headers(headers)
                .body(body)

        File("/tmp/latestPostRequest").writeText(request.toMessage())

        return httpClient(request)
    }

    /**
     * Compatibility layer for legacy callers.
     */
    fun enablesObjectPost(doSomething: ((String) -> Response) -> Unit): Boolean {
        // Verify token exists before exposing callback
        if (!accessTokenHandler.testAccess()) {
            log.error { "Unable to obtain Salesforce access token" }
            return false
        }

        val transfer: (String) -> Response = { body ->

            var response = post(body)

            log.debug {
                "SF doPost initial response with http status - ${response.status}"
            }

            // Retry once if token expired
            if (response.status == Status.UNAUTHORIZED) {
                log.info { "Salesforce token expired, refreshing token" }

                val refreshed =
                    runCatching {
                        accessTokenHandler.accessToken
                    }.isSuccess

                if (refreshed) {
                    response = post(body)
                } else {
                    log.error { "Failed to refresh Salesforce access token" }
                }
            }

            when (response.status) {
                Status.OK ->
                    log.debug { "Returned status OK" }

                Status.CREATED ->
                    log.info { "Returned status CREATED" }

                else ->
                    log.error {
                        "SF doPost issue with http status - ${response.status}"
                    }
            }

            response
        }

        return runCatching {
            doSomething(transfer)
        }.onSuccess {
            log.info {
                "Salesforce - end of sObject post availability with success"
            }
        }.onFailure {
            log.error {
                "Salesforce - end of sObject post availability failed - ${it.stackTraceToString()}"
            }
        }.isSuccess
    }

    fun postRecords(kafkaMessages: List<KafkaMessage>): Response {
        val requestBody = SFsObjectRest(records = kafkaMessages).toJson()
        return post(requestBody)
    }
}
