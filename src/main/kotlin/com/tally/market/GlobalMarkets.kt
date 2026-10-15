package com.tally.market

import com.tally.util.TallyJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

@Serializable
data class GlobalQuote(
    val symbol: String,
    val name: String,
    val group: String,
    val price: Double?,
    val previousClose: Double?,
    val changePct: Double?,
    val marketTime: Long?,
)

/**
 * Overseas indices, futures, FX, rates and commodities that move the Indian open. Kite has none of these,
 * so they come from Yahoo Finance's public chart endpoint: unofficial and best-effort. A failed symbol
 * just shows as unavailable; nothing in the order path depends on it.
 */
class GlobalMarkets(private val http: HttpClient) {
    private val log = LoggerFactory.getLogger(GlobalMarkets::class.java)
    private val mutex = Mutex()
    private var cache: Pair<Long, List<GlobalQuote>>? = null

    suspend fun snapshot(maxAgeMs: Long = 60_000): List<GlobalQuote> = mutex.withLock {
        cache?.takeIf { System.currentTimeMillis() - it.first < maxAgeMs }?.let { return it.second }
        val quotes = coroutineScope { SYMBOLS.map { s -> async { fetch(s) } }.awaitAll() }
        cache = System.currentTimeMillis() to quotes
        quotes
    }

    private suspend fun fetch(s: Sym): GlobalQuote = runCatching {
        val resp = http.get("https://query1.finance.yahoo.com/v8/finance/chart/${s.symbol.encodeURLPathPart()}") {
            parameter("range", "1d")
            parameter("interval", "15m")
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (tally personal dashboard)")
        }
        val meta = TallyJson.parseToJsonElement(resp.bodyAsText()).jsonObject["chart"]!!
            .jsonObject["result"]!!.jsonArray[0].jsonObject["meta"]!!.jsonObject
        val price = meta["regularMarketPrice"]?.jsonPrimitive?.doubleOrNull
        val prev = (meta["chartPreviousClose"] ?: meta["previousClose"])?.jsonPrimitive?.doubleOrNull
        GlobalQuote(
            s.symbol, s.name, s.group, price, prev,
            if (price != null && prev != null && prev != 0.0) (price - prev) / prev * 100 else null,
            meta["regularMarketTime"]?.jsonPrimitive?.longOrNull,
        )
    }.getOrElse {
        log.debug("Global quote {} failed: {}", s.symbol, it.message)
        GlobalQuote(s.symbol, s.name, s.group, null, null, null, null)
    }

    private data class Sym(val symbol: String, val name: String, val group: String)

    companion object {
        private val SYMBOLS = listOf(
            Sym("ES=F", "S&P 500 fut", "US"),
            Sym("NQ=F", "Nasdaq fut", "US"),
            Sym("YM=F", "Dow fut", "US"),
            Sym("^VIX", "CBOE VIX", "US"),
            Sym("^N225", "Nikkei 225", "Asia"),
            Sym("^HSI", "Hang Seng", "Asia"),
            Sym("^KS11", "KOSPI", "Asia"),
            Sym("^STOXX50E", "Euro Stoxx 50", "Europe"),
            Sym("BZ=F", "Brent crude", "Commodities"),
            Sym("GC=F", "Gold", "Commodities"),
            Sym("INR=X", "USD/INR", "FX & rates"),
            Sym("DX-Y.NYB", "Dollar index", "FX & rates"),
            Sym("^TNX", "US 10Y yield", "FX & rates"),
        )
    }
}
