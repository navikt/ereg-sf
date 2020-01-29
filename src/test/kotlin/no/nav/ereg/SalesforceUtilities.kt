package no.nav.ereg

import mu.KotlinLogging
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Netty
import org.http4k.server.asServer

private val log = KotlinLogging.logger {}

internal sealed class SFScenarios {
    object FailedAuth : SFScenarios()
    object OkAuthInvalidToken : SFScenarios()
    object OkAuthValidToken : SFScenarios()
    object OkAuthValidTokenNotExpired : SFScenarios()
    object OkAuthValidTokenExpired : SFScenarios()
    object FailedPost : SFScenarios()
}

internal fun sfAPI(
    port: Int,
    scenario: SFScenarios,
    doSomething: (String, String) -> Unit
) {

    val authEndpoint = "/authorization"
    val authURL = "http://localhost:$port$authEndpoint"
    val restEndpoint = "/rest"
    val correctAuthExpired = """
                    {"access_token":"Expired","instance_url":"http://localhost:$port","id":"","token_type":"Bearer","issued_at":"test","signature":"test"}
                """.trimIndent()
    val correctAuthNotExpired = """
                    {"access_token":"notExpired","instance_url":"http://localhost:$port","id":"","token_type":"Bearer","issued_at":"test","signature":"test"}
                """.trimIndent()

    var changeScenario = scenario // in order to go from expired to not expired

    routes(
        authEndpoint bind Method.POST to {
            when (changeScenario) {
                SFScenarios.FailedAuth -> Response(Status.SERVICE_UNAVAILABLE).body("")
                SFScenarios.OkAuthInvalidToken -> Response(Status.OK).body("very invalid token...")
                SFScenarios.OkAuthValidToken -> Response(Status.OK).body(correctAuthNotExpired)
                SFScenarios.OkAuthValidTokenNotExpired -> Response(Status.OK).body(correctAuthNotExpired)
                SFScenarios.OkAuthValidTokenExpired -> Response(Status.OK).body(correctAuthExpired)
                SFScenarios.FailedPost -> Response(Status.OK).body(correctAuthNotExpired)
            }
        },
        restEndpoint bind Method.POST to {
            when (changeScenario) {
                SFScenarios.OkAuthValidTokenNotExpired -> Response(Status.OK).body("")
                SFScenarios.OkAuthValidTokenExpired -> {
                    changeScenario = SFScenarios.OkAuthValidTokenNotExpired
                    Response(Status.UNAUTHORIZED).body("")
                }
                else -> Response(Status.SERVICE_UNAVAILABLE).body("")
            }
        }
    ).asServer(Netty(port)).use { srv ->
        try {
            srv.start()
            log.info { "sfAPI started" }
            doSomething(authURL, restEndpoint)
        } catch (e: Exception) {
            log.error { "Couldn't activate sfAPI - ${e.message}" }
        } finally {
            srv.stop()
            log.info { "sfAPI stopped" }
        }
    }
}
