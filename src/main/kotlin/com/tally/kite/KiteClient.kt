package com.tally.kite

import com.tally.config.KiteConfig
import com.tally.util.IST
import com.tally.util.TallyJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalSerializationApi::class)
internal val KiteJson = Json(TallyJson) { namingStrategy = JsonNamingStrategy.SnakeCase }

/** Spaces out calls so we stay under Kite's per-endpoint rate limits (quote 1/s, historical 3/s, orders 10/s). */
private class Throttle(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.withLock {
            val wait = last + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
        }
        return block()
    }
}

/** Thin wrapper over the Kite Connect v3 REST API. */
class KiteClient(
    private val cfg: KiteConfig,
    private val sessions: KiteSessionStore,
    private val http: HttpClient,
) {
    private val log = LoggerFactory.getLogger(KiteClient::class.java)
    private val quoteThrottle = Throttle(1_050)
    private val historyThrottle = Throttle(350)
    private val orderThrottle = Throttle(110)
    private val defaultThrottle = Throttle(110)
    private val historicalTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val isLoggedIn get() = sessions.current() != null

    /** [state] comes back on the redirect via Kite's redirect_params, guarding against login CSRF. */
    fun loginUrl(state: String) = "${cfg.loginUrl}?v=3&api_key=${cfg.apiKey}&redirect_params=" +
        java.net.URLEncoder.encode("state=$state", Charsets.UTF_8)

    suspend fun createSession(requestToken: String): KiteSession {
        val checksum = sha256Hex(cfg.apiKey + requestToken + cfg.apiSecret)
        val resp = call(
            SessionResponse.serializer(), HttpMethod.Post, "/session/token",
            form = mapOf("api_key" to cfg.apiKey, "request_token" to requestToken, "checksum" to checksum),
            authenticated = false,
        )
        if (cfg.userId.isNotBlank() && !resp.userId.equals(cfg.userId, ignoreCase = true)) {
            // Someone else logged in through this app: kill their token and keep ours.
            runCatching {
                call(JsonElement.serializer(), HttpMethod.Delete, "/session/token",
                    query = listOf("api_key" to cfg.apiKey, "access_token" to resp.accessToken), authenticated = false)
            }
            log.warn("Rejected Kite login from unexpected user {}", resp.userId)
            throw KiteException("This app only accepts logins from ${cfg.userId}", "UserNotAllowed", 403)
        }
        val session = KiteSession(resp.userId, resp.userName, resp.accessToken, System.currentTimeMillis() / 1000)
        sessions.save(session)
        log.info("Kite session created for {}", resp.userId)
        return session
    }

    suspend fun logout() {
        val session = sessions.current() ?: return
        runCatching {
            call(JsonElement.serializer(), HttpMethod.Delete, "/session/token",
                query = listOf("api_key" to cfg.apiKey, "access_token" to session.accessToken))
        }
        sessions.clear()
    }

    suspend fun profile(): Profile = call(Profile.serializer(), HttpMethod.Get, "/user/profile")

    suspend fun margins(): JsonElement = call(JsonElement.serializer(), HttpMethod.Get, "/user/margins")

    suspend fun holdings(): List<Holding> =
        call(ListSerializer(Holding.serializer()), HttpMethod.Get, "/portfolio/holdings")

    suspend fun positions(): Positions = call(Positions.serializer(), HttpMethod.Get, "/portfolio/positions")

    suspend fun orders(): List<Order> = call(ListSerializer(Order.serializer()), HttpMethod.Get, "/orders")

    suspend fun placeOrder(req: OrderRequest): String = orderThrottle.run {
        val form = buildMap {
            put("exchange", req.exchange)
            put("tradingsymbol", req.tradingsymbol)
            put("transaction_type", req.transactionType.name)
            put("quantity", req.quantity.toString())
            put("product", req.product.name)
            put("order_type", req.orderType.wire)
            put("validity", "DAY")
            req.price?.let { put("price", it.toString()) }
            req.triggerPrice?.let { put("trigger_price", it.toString()) }
            req.tag?.let { put("tag", it.take(20)) }
            // Kite requires market protection on MARKET / SL-M orders placed via API; -1 = exchange default band.
            if (req.orderType == OrderType.MARKET || req.orderType == OrderType.SL_M) put("market_protection", "-1")
        }
        call(OrderResult.serializer(), HttpMethod.Post, "/orders/${req.variety}", form = form).orderId
    }

    suspend fun cancelOrder(orderId: String, variety: String = "regular"): String =
        orderThrottle.run {
            call(OrderResult.serializer(), HttpMethod.Delete, "/orders/$variety/$orderId").orderId
        }

    /** Full quote. Keys are "EXCHANGE:TRADINGSYMBOL", e.g. "NSE:INFY" or "NSE:NIFTY 50". */
    suspend fun quote(keys: Collection<String>): Map<String, Quote> = quoteThrottle.run {
        call(MapSerializer(String.serializer(), Quote.serializer()), HttpMethod.Get, "/quote",
            query = keys.map { "i" to it })
    }

    suspend fun ltp(keys: Collection<String>): Map<String, Ltp> = quoteThrottle.run {
        call(MapSerializer(String.serializer(), Ltp.serializer()), HttpMethod.Get, "/quote/ltp",
            query = keys.map { "i" to it })
    }

    /** Interval: minute, 3minute, 5minute, 10minute, 15minute, 30minute, 60minute, day. */
    suspend fun historical(
        instrumentToken: Long,
        interval: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<Candle> = historyThrottle.run {
        val data = call(
            HistoricalData.serializer(), HttpMethod.Get,
            "/instruments/historical/$instrumentToken/$interval",
            query = listOf("from" to from.format(historicalTimeFormat), "to" to to.format(historicalTimeFormat)),
        )
        data.candles.map { row ->
            Candle(
                time = row[0].jsonPrimitive.content,
                open = row[1].jsonPrimitive.double,
                high = row[2].jsonPrimitive.double,
                low = row[3].jsonPrimitive.double,
                close = row[4].jsonPrimitive.double,
                volume = row[5].jsonPrimitive.long,
                oi = row.getOrNull(6)?.jsonPrimitive?.longOrNull,
            )
        }
    }

    /** Raw instrument dump CSV for an exchange (NSE, NFO, BSE, ...). Large; cache it. */
    suspend fun instrumentsCsv(exchange: String): String = defaultThrottle.run {
        val resp = http.get("${cfg.baseUrl}/instruments/$exchange") { applyAuth() }
        if (!resp.status.isSuccess()) throw KiteException("instruments $exchange: ${resp.status}", null, resp.status.value)
        resp.bodyAsText()
    }

    private fun HttpRequestBuilder.applyAuth() {
        header("X-Kite-Version", "3")
        val session = sessions.current() ?: throw KiteException("Not logged in to Kite", "TokenException", 403)
        header(HttpHeaders.Authorization, "token ${cfg.apiKey}:${session.accessToken}")
    }

    private suspend fun <T> call(
        serializer: KSerializer<T>,
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        form: Map<String, String>? = null,
        authenticated: Boolean = true,
    ): T {
        val resp = http.request("${cfg.baseUrl}$path") {
            this.method = method
            if (authenticated) applyAuth() else header("X-Kite-Version", "3")
            query.forEach { (k, v) -> parameter(k, v) }
            if (form != null) {
                setBody(FormDataContent(Parameters.build { form.forEach { (k, v) -> append(k, v) } }))
            }
        }
        val text = resp.bodyAsText()
        val root = runCatching { KiteJson.parseToJsonElement(text).jsonObject }.getOrElse {
            throw KiteException("Unexpected Kite response (${resp.status}): ${text.take(200)}", null, resp.status.value)
        }
        if (root["status"]?.jsonPrimitive?.content != "success") {
            val ex = KiteException(
                root["message"]?.jsonPrimitive?.content ?: "Kite error ${resp.status}",
                root["error_type"]?.jsonPrimitive?.content,
                resp.status.value,
            )
            if (ex.isTokenError && authenticated) {
                log.warn("Kite token rejected, clearing session: {}", ex.message)
                sessions.clear()
            }
            throw ex
        }
        return KiteJson.decodeFromJsonElement(serializer, root["data"] ?: JsonNull)
    }

    companion object {
        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun parseCandleTime(time: String): ZonedDateTime =
            ZonedDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).withZoneSameInstant(IST)
    }
}
