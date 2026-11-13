package com.tally.util

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

/**
 * Sends a short plain-text alert by POSTing it to [url] (for example an ntfy.sh topic, which pushes it to a phone).
 * With no URL configured, alerts are only logged.
 */
class Alerts(private val url: String, private val http: HttpClient) {
    private val log = LoggerFactory.getLogger(Alerts::class.java)

    suspend fun send(title: String, message: String) {
        log.warn("ALERT {}: {}", title, message)
        if (url.isBlank()) return
        runCatching {
            val resp = http.post(url) {
                header("Title", title)
                header("Priority", "high")
                contentType(ContentType.Text.Plain)
                setBody(message)
            }
            if (!resp.status.isSuccess()) log.warn("Alert POST returned {}: {}", resp.status.value, resp.bodyAsText().take(200))
        }.onFailure { log.warn("Alert POST failed: {}", it.message) }
    }
}
