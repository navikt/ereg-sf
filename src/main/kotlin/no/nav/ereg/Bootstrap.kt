package no.nav.ereg

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

object Bootstrap {

    private val log = KotlinLogging.logger { }

    fun start(ev: EnvVar = EnvVarFactory.envVar) {

        ShutdownHook.reset()

        NaisDSL.enabled { conditionalSchedule(ev) } // end of use for NAISDSL - shutting down REST API
    }

    private tailrec fun conditionalSchedule(ev: EnvVar) {

        // some resets before next attempt/work session
        ServerState.reset()
        Metrics.sessionReset()
        work(ev) // ServerState will be updated according to any issues
        wait(ev)

        if (!ShutdownHook.isActive()) conditionalSchedule(ev)
    }

    private fun wait(ev: EnvVar) {
        val msDelay = ev.msBetweenWork
        log.info { "Will wait $msDelay ms before starting all over" }
        runCatching { runBlocking { delay(msDelay) } }
            .onSuccess { log.info { "waiting completed" } }
            .onFailure { log.info { "waiting interrupted" } }
    }
}
