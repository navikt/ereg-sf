package no.nav.ereg

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Summary
import io.prometheus.client.hotspot.DefaultExports
import mu.KotlinLogging

object Metrics {

    private val log = KotlinLogging.logger { }

    val cRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry

    val responseLatency: Summary = Summary
        .build()
        .name("response_latency_seconds")
        .help("Salesforce post response latency")
        .register()

    val successfulRequest: Counter = Counter
        .build()
        .name("successful_request_counter")
        .help("No. of successful requests to Salesforce since last restart")
        .register()

    val failedRequest: Counter = Counter
        .build()
        .name("failed_request_counter")
        .help("No. of failed requests to Salesforce since last restart")
        .register()

    val sentOrgs: Counter = Counter
        .build()
        .name("sent_organisation_counter")
        .labelNames("type")
        .help("No. of organisations sent to Salesforce in last work session")
        .register()

    init {
        DefaultExports.initialize()
        log.info { "Prometheus metrics are ready" }
    }

    fun sessionReset() {
        sentOrgs.clear()
    }

    fun resetAll() {
        responseLatency.clear()
        successfulRequest.clear()
        failedRequest.clear()
        sentOrgs.clear()
    }
}
