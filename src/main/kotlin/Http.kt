package com.tally

import com.tally.config.AppConfig
import com.tally.kite.KiteException
import com.tally.util.TallyJson
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class ApiError(val error: String, val hint: String? = null)

private const val TOKEN_HEADER = "X-Tally-Token"

fun Application.configureHttp(config: AppConfig) {
    install(ContentNegotiation) { json(TallyJson) }
    install(CallLogging)

    install(CORS) {
        // Local dashboard only. Anything wider would let any website you visit drive your broker account.
        allowHost("localhost:5173")
        allowHost("127.0.0.1:5173")
        allowHost("localhost:8090")
        allowHost("127.0.0.1:8090")
        // The same dashboard served over HTTPS by Caddy; its requests carry that origin, not the loopback one.
        if (config.publicHost.isNotBlank()) allowHost(config.publicHost, schemes = listOf("https"))
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(TOKEN_HEADER)
    }

    install(StatusPages) {
        exception<KiteException> { call, e ->
            if (e.isTokenError) {
                // Never 401: behind Caddy's basic auth a 401 makes browsers drop the saved password and prompt again.
                call.respond(HttpStatusCode.Forbidden, ApiError(e.message ?: "Kite session invalid", "Log in at /auth/login"))
            } else {
                call.respond(HttpStatusCode.BadGateway, ApiError("Kite: ${e.message}", e.errorType))
            }
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request"))
        }
        exception<IllegalStateException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ApiError(e.message ?: "Invalid state"))
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled error on ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    // Every /api call must carry the shared token; /health and the Kite browser login flow are open.
    val expected = config.apiToken.toByteArray()
    intercept(ApplicationCallPipeline.Plugins) {
        if (!call.request.path().startsWith("/api") || call.request.httpMethod == HttpMethod.Options) return@intercept
        val given = call.request.headers[TOKEN_HEADER]?.toByteArray() ?: ByteArray(0)
        if (!MessageDigest.isEqual(given, expected)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("Missing or invalid $TOKEN_HEADER header"))
            finish()
        }
    }
}
