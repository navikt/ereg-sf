package no.nav.ereg.nais

import filesHandler
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.common.TextFormat
import mu.KotlinLogging
import no.nav.ereg.metrics.Metrics.cRegistry
import no.nav.ereg.salesforce.NewAccessTokenHandler
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Netty
import org.http4k.server.asServer
import java.io.File
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger { }

const val NAIS_URL = "http://localhost:"
const val NAIS_DEFAULT_PORT = 8080

fun naisAPI(): HttpHandler =
    routes(
        "/isAlive" bind Method.GET to { Response(Status.OK) },
        "/isReady" bind Method.GET to { Response(Status.OK) },
        "/metrics" bind Method.GET to {
            runCatching {
                StringWriter()
                    .let { str ->
                        TextFormat.write004(str, cRegistry.metricFamilySamples())
                        str
                    }.toString()
            }.onFailure {
                log.error { "/prometheus failed writing metrics - ${it.localizedMessage}" }
            }.getOrDefault("")
                .responseByContent()
        },
        "/stop" bind Method.GET to {
            preStopHook.inc()
            PrestopHook.activate()
            log.info { "Received PreStopHook from NAIS" }
            Response(Status.OK)
        },
        "/internal/testAccess/new" bind Method.GET to testAccessHandlerNew,
        "/internal/files" bind Method.GET to filesHandler(File("/tmp/files")),
        "/internal/files/{path:.*}" bind Method.GET to filesHandler(File("/tmp/files")),
    )

fun enableNAISAPI(
    port: Int = NAIS_DEFAULT_PORT,
    doSomething: () -> Unit,
): Boolean =
    naisAPI().asServer(Netty(port)).let { srv ->
        try {
            srv.start().use {
                log.info { "NAIS DSL is up and running at port $port" }
                runCatching(doSomething)
                    .onFailure {
                        log.error { "Failure during run inside enableNAISAPI - ${it.localizedMessage} Stack: ${it.printStackTrace()}" }
                    }
            }
            true
        } catch (e: Exception) {
            log.error { "Failure during enable/disable NAIS api for port $port - ${e.localizedMessage}" }
            false
        } finally {
            srv.close()
            log.info { "NAIS DSL is stopped at port $port" }
        }
    }

private fun String.responseByContent(): Response = if (this.isNotEmpty()) Response(Status.OK).body(this) else Response(Status.NO_CONTENT)

object ShutdownHook {
    private val log = KotlinLogging.logger { }

    @Volatile
    private var shutdownhookActiveOrOther = false
    private val mainThread: Thread = Thread.currentThread()

    init {
        log.info { "Installing shutdown hook" }
        Runtime
            .getRuntime()
            .addShutdownHook(
                object : Thread() {
                    override fun run() {
                        shutdownhookActiveOrOther = true
                        log.info { "shutdown hook activated" }
                        mainThread.join()
                    }
                },
            )
    }

    fun isActive() = shutdownhookActiveOrOther

    fun reset() {
        shutdownhookActiveOrOther = false
    }
}

internal val preStopHook: Gauge =
    Gauge
        .build()
        .name("pre_stop__hook_gauge")
        .help("No. of preStopHook activations since last restart")
        .register()

object PrestopHook {
    private val log = KotlinLogging.logger { }

    @Volatile
    private var prestopHook = false

    init {
        log.info { "Installing prestop hook" }
    }

    fun isActive() = prestopHook

    fun activate() {
        prestopHook = true
    }

    fun reset() {
        prestopHook = false
    }
}

val currentTimeStamp: String get() = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME)

private val testAccessHandlerNew: HttpHandler = {
    val newAccessTokenHandler = NewAccessTokenHandler()
    Response(OK).body("$currentTimeStamp\nTest access (new) successful: " + newAccessTokenHandler.testAccess())
}
