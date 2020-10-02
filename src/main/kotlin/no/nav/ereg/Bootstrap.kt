package no.nav.ereg

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.sf.library.AnEnvironment
import no.nav.sf.library.PrestopHook
import no.nav.sf.library.ShutdownHook
import no.nav.sf.library.enableNAISAPI

private const val EV_bootstrapWaitTime = "MS_BETWEEN_WORK" // default to 10 minutes
private val bootstrapWaitTime = AnEnvironment.getEnvOrDefault(EV_bootstrapWaitTime, "60000").toLong()

object Bootstrap {

    private val log = KotlinLogging.logger { }

    fun start(ws: WorkSettings = WorkSettings()) {
        log.info { "Starting" }
        enableNAISAPI {
            investigate(ws)
            loop(ws)
        }
        log.info { "Finished!" }
    }

    private tailrec fun loop(ws: WorkSettings) {
        val stop = ShutdownHook.isActive() || PrestopHook.isActive()
        when {
            stop -> Unit
            !stop -> loop(work(ws)
                .let { prevWS ->
                    // re-read of vault entries in case of changes, keeping relevant access tokens and static env. vars.
                    prevWS.first.copy(
                        sfClient = prevWS.first.sfClient.copyRelevant()
                    )
                }
                .also { conditionalWait() }
            )
        }
    }

    private fun conditionalWait(ms: Long = bootstrapWaitTime) =
        runBlocking {

            log.info { "Will wait $ms ms before starting all over" }

            val cr = launch {
                runCatching { delay(ms) }
                    .onSuccess { log.info { "waiting completed" } }
                    .onFailure { log.info { "waiting interrupted" } }
            }

            tailrec suspend fun loop(): Unit = when {
                cr.isCompleted -> Unit
                ShutdownHook.isActive() || PrestopHook.isActive() -> cr.cancel()
                else -> {
                    delay(250L)
                    loop()
                }
            }

            loop()
            cr.join()
        }
}
