package com.tally.ai

import com.tally.config.AnthropicConfig
import com.tally.util.IST
import com.tally.util.TallyJson
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

class ClaudeApiException(message: String, val status: Int) : RuntimeException(message)

@Serializable
data class AiSpend(val day: String, val usd: Double, val calls: Int, val webSearches: Int)

/** Result of one logical request (possibly several HTTP round-trips when a server tool pauses the turn). */
data class ClaudeResult(
    val content: List<JsonObject>,
    val stopReason: String?,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val webSearches: Int,
    val costUsd: Double,
) {
    val text: String
        get() = content.filter { it["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it["text"]!!.jsonPrimitive.content }
}

/**
 * Minimal Messages API client over the shared Ktor client: retries, pause_turn continuation for server
 * tools (web search), and a per-day spend tally so scheduled AI work can respect a budget.
 */
class ClaudeApi(private val cfg: AnthropicConfig, private val http: HttpClient, dataDir: java.nio.file.Path? = null) {
    private val log = LoggerFactory.getLogger(ClaudeApi::class.java)
    private val spendFile = dataDir?.resolve("ai_spend.json")
    private val spend = AtomicReference(
        runCatching { spendFile?.takeIf { java.nio.file.Files.exists(it) }?.let { TallyJson.decodeFromString(AiSpend.serializer(), java.nio.file.Files.readString(it)) } }.getOrNull()
            ?: AiSpend(LocalDate.now(IST).toString(), 0.0, 0, 0),
    )

    val configured get() = cfg.configured
    val model get() = cfg.model

    fun spendToday(): AiSpend {
        val today = LocalDate.now(IST).toString()
        return spend.updateAndGet { if (it.day == today) it else AiSpend(today, 0.0, 0, 0) }
    }

    /**
     * Sends [body] (without "messages") with a single user turn. Continues automatically on pause_turn,
     * up to [maxContinuations] times, by echoing the partial assistant turn back unchanged.
     *
     * [cachedPrefix] is the slow-changing start of the user turn (news brief, playbook). When given, the system
     * prompt and that prefix are cached for an hour, so repeat calls within the hour pay ~5% for those tokens.
     */
    suspend fun create(body: JsonObject, userContent: String, maxContinuations: Int = 4, cachedPrefix: String? = null): ClaudeResult {
        check(cfg.configured) { "ANTHROPIC_API_KEY is not set" }
        val content = mutableListOf<JsonObject>()
        var inTok = 0L
        var outTok = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var searches = 0
        var cost = 0.0
        var stop: String? = null
        var model = cfg.model
        val sendBody = if (cachedPrefix == null) body else withCachedSystem(body)

        for (round in 0..maxContinuations) {
            val messages = buildJsonArray {
                addJsonObject {
                    put("role", "user")
                    if (cachedPrefix == null) put("content", userContent)
                    else putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", cachedPrefix); put("cache_control", CACHE_1H) }
                        addJsonObject { put("type", "text"); put("text", userContent) }
                    }
                }
                if (content.isNotEmpty()) addJsonObject { put("role", "assistant"); put("content", JsonArray(content)) }
            }
            val request = JsonObject(sendBody + ("messages" to messages))
            val root = post(request.toString())
            root["content"]?.jsonArray?.forEach { content += it.jsonObject }
            stop = root["stop_reason"]?.jsonPrimitive?.contentOrNull
            model = root["model"]?.jsonPrimitive?.contentOrNull ?: model
            root["usage"]?.jsonObject?.let { u ->
                val plain = u.long("input_tokens")
                val write = u.long("cache_creation_input_tokens")
                val read = u.long("cache_read_input_tokens")
                val o = u.long("output_tokens")
                val w = (u["server_tool_use"] as? JsonObject)?.long("web_search_requests")?.toInt() ?: 0
                inTok += plain + write + read; outTok += o; searches += w; cacheRead += read; cacheWrite += write
                // Count spend per HTTP round so a failure in a later round can't hide money already spent.
                // 1-hour cache writes bill at 2x input, cache reads at 0.05x.
                val roundCost = (plain + write * 2.0 + read * 0.05) * INPUT_USD_PER_TOKEN + o * OUTPUT_USD_PER_TOKEN + w * WEB_SEARCH_USD
                cost += roundCost
                spendToday()
                val updated = spend.updateAndGet { it.copy(usd = it.usd + roundCost, calls = it.calls + if (round == 0) 1 else 0, webSearches = it.webSearches + w) }
                spendFile?.let { f -> runCatching { java.nio.file.Files.writeString(f, TallyJson.encodeToString(AiSpend.serializer(), updated)) } }
            }
            if (stop != "pause_turn") break
        }

        log.info("Claude call: {} in ({} cache read, {} cache write) / {} out tokens, {} searches, \${}, stop={}",
            inTok, cacheRead, cacheWrite, outTok, searches, "%.3f".format(cost), stop)
        return ClaudeResult(content, stop, model, inTok, outTok, searches, cost)
    }

    /** Turns a plain-string system prompt into a text block cached for an hour. */
    private fun withCachedSystem(body: JsonObject): JsonObject {
        val system = (body["system"] as? JsonPrimitive)?.contentOrNull ?: return body
        return JsonObject(body + ("system" to buildJsonArray {
            addJsonObject { put("type", "text"); put("text", system); put("cache_control", CACHE_1H) }
        }))
    }

    private suspend fun post(body: String): JsonObject {
        var attempt = 0
        while (true) {
            attempt++
            val resp = http.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", cfg.apiKey)
                header("anthropic-version", "2023-06-01")
                header("anthropic-beta", "server-side-fallback-2026-07-01")
                contentType(ContentType.Application.Json)
                setBody(body)
                // Long answers and web research can take minutes; the shared client's 30s default is for Kite.
                timeout {
                    requestTimeoutMillis = 10 * 60_000
                    socketTimeoutMillis = 10 * 60_000
                }
            }
            val text = resp.bodyAsText()
            val code = resp.status.value
            if (resp.status.isSuccess()) return TallyJson.parseToJsonElement(text).jsonObject

            if ((code == 429 || code >= 500) && attempt < 4) {
                val wait = resp.headers["retry-after"]?.toLongOrNull()?.times(1000) ?: (2_000L shl (attempt - 1))
                log.warn("Claude API {} (attempt {}), retrying in {} ms", code, attempt, wait)
                delay(wait)
                continue
            }
            val message = runCatching {
                TallyJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull() ?: text.take(300)
            throw ClaudeApiException("Claude API $code: $message", code)
        }
    }

    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull ?: 0L

    companion object {
        // Claude Opus 5.5 list prices: $4 / $20 per million tokens; web search $10 per 1,000 searches.
        private const val INPUT_USD_PER_TOKEN = 4.0 / 1_000_000
        private const val OUTPUT_USD_PER_TOKEN = 20.0 / 1_000_000
        private const val WEB_SEARCH_USD = 10.0 / 1_000
        // Calls repeat every few minutes, longer than the default 5-minute cache lifetime, so cache for an hour.
        private val CACHE_1H = buildJsonObject { put("type", "ephemeral"); put("ttl", "1h") }

        /** Common request fields. Refusal fallback lets the API retry a declined request on a fallback model. */
        fun baseBody(model: String, system: String, maxTokens: Int, effort: String) = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            putJsonObject("output_config") { put("effort", effort) }
            put("fallbacks", "default")
        }
    }
}
