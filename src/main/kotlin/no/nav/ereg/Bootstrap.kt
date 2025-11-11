package no.nav.ereg

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.ereg.nais.PrestopHook
import no.nav.ereg.nais.enableNAISAPI

private const val EV_BOOTSTRAP_WAIT_TIME = "MS_BETWEEN_WORK" // default to 10 minutes
private val bootstrapWaitTime = getEnvOrDefault(EV_BOOTSTRAP_WAIT_TIME, "60000").toLong()

object Bootstrap {
    private val log = KotlinLogging.logger { }

    fun start(ws: WorkSettings = WorkSettings()) {
        log.info { "Starting" }
        enableNAISAPI {
            // investigate(ws)
            loop(ws)
        }
        log.info { "Finished!" }
    }

    private tailrec fun loop(ws: WorkSettings) {
        val stop = PrestopHook.isActive()
        when {
            stop -> Unit
            !stop ->
                loop(
                    work(ws)
                        .let { prevWS ->
                            // re-read of vault entries in case of changes, keeping relevant access tokens and static env. vars.
                            prevWS.first.copy(
                                sfClient = prevWS.first.sfClient,
                            )
                        }.also { conditionalWait() },
                )
        }
    }

    public fun conditionalWait(ms: Long = bootstrapWaitTime) =
        runBlocking {
            log.info { "Will wait $ms ms before starting all over" }

            val cr =
                launch {
                    runCatching { delay(ms) }
                        .onSuccess { log.info { "waiting completed" } }
                        .onFailure { log.info { "waiting interrupted" } }
                }

            tailrec suspend fun loop(): Unit =
                when {
                    cr.isCompleted -> Unit
                    PrestopHook.isActive() -> cr.cancel()
                    else -> {
                        delay(250L)
                        loop()
                    }
                }

            loop()
            cr.join()
        }
}
