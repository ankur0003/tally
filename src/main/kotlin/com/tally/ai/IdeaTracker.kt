package com.tally.ai

import com.tally.kite.Candle
import com.tally.kite.KiteClient
import com.tally.market.MarketDataService
import com.tally.util.MarketClock
import com.tally.util.TallyJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.ZonedDateTime

@Serializable
data class TradeIdea(
    val id: String,
    val createdAt: String,
    val index: String,
    val quoteKey: String,
    /** INDEX (NIFTY/BANKNIFTY) or STOCK (F&O stock). [index] holds the underlying symbol either way. */
    val kind: String = "INDEX",
    val action: String,
    val confidence: Int,
    val entry: Double,
    val stopLoss: Double,
    val target: Double,
    val horizonMinutes: Int,
    val rationale: String,
    val invalidation: String,
    /** Index LTP when the idea was generated; outcomes are measured from here. */
    val refPrice: Double,
    val qualified: Boolean = false,
    /** Passed every rule check except possibly the confidence threshold: can be taken manually. */
    val levelsOk: Boolean = false,
    val note: String = "",
    /** OPEN, TARGET, STOP, EXPIRED, NO_TRADE */
    val status: String = "OPEN",
    val resolvedAt: String? = null,
    val resolvedPrice: Double? = null,
    val autoTraded: Boolean = false,
    /** Claude's take on the idea as a small speculative punt, ignoring the confidence bar: TAKE, SKIP or NA. */
    val riskyVerdict: String = "NA",
    val riskyReason: String = "",
)

@Serializable
data class BucketStats(val bucket: String, val ideas: Int, val resolved: Int, val targets: Int, val stops: Int, val expired: Int, val hitRatePct: Double?)

/**
 * Keeps every idea and scores it against what the index actually did: TARGET if the target traded before the
 * stop within the horizon, STOP if the stop traded first (or both in the same minute - the conservative call),
 * otherwise EXPIRED. This is the honest answer to "how often is a 90% idea actually right?".
 */
class IdeaTracker(private val market: MarketDataService, private val clock: MarketClock, dataDir: Path) {
    private val log = LoggerFactory.getLogger(IdeaTracker::class.java)
    private val file = dataDir.resolve("ai_ideas.json")
    private val mutex = Mutex()
    private val ideas: MutableList<TradeIdea> = runCatching {
        if (Files.exists(file)) TallyJson.decodeFromString(ListSerializer(TradeIdea.serializer()), Files.readString(file)).toMutableList() else null
    }.getOrNull() ?: mutableListOf()

    suspend fun add(newIdeas: List<TradeIdea>) = mutex.withLock {
        ideas += newIdeas
        while (ideas.size > 2000) ideas.removeAt(0)
        save()
    }

    suspend fun recent(limit: Int = 50): List<TradeIdea> = mutex.withLock { ideas.takeLast(limit).asReversed() }

    suspend fun get(id: String): TradeIdea? = mutex.withLock { ideas.firstOrNull { it.id == id } }

    suspend fun markAutoTraded(id: String) = update(id) { it.copy(autoTraded = true) }

    suspend fun stats(): List<BucketStats> = mutex.withLock {
        // Paper test ideas are not Claude's and must not count toward its track record.
        val directional = ideas.filter { (it.action == "LONG" || it.action == "SHORT") && !it.id.startsWith("test-") }
        listOf("90+" to (90..100), "80-89" to (80..89), "60-79" to (60..79), "<60" to (0..59)).map { (name, range) ->
            val b = directional.filter { it.confidence in range }
            val resolved = b.filter { it.status in setOf("TARGET", "STOP", "EXPIRED") }
            val targets = resolved.count { it.status == "TARGET" }
            BucketStats(
                name, b.size, resolved.size, targets, resolved.count { it.status == "STOP" }, resolved.count { it.status == "EXPIRED" },
                if (resolved.isEmpty()) null else targets * 100.0 / resolved.size,
            )
        }
    }

    /** Resolves open ideas using 1-minute index candles since each idea was created. */
    suspend fun evaluate() {
        val open = mutex.withLock { ideas.filter { it.status == "OPEN" } }
        if (open.isEmpty()) return
        val now = clock.now()
        for ((key, group) in open.groupBy { it.quoteKey }) {
            val (exchange, symbol) = key.split(":", limit = 2)
            val candles = runCatching { market.candles(exchange, symbol, "minute", 2) }.getOrElse {
                log.debug("Idea evaluation skipped for {}: {}", key, it.message); continue
            }
            group.forEach { idea -> resolve(idea, candles, now)?.let { r -> update(idea.id) { r } } }
        }
    }

    private fun resolve(idea: TradeIdea, candles: List<Candle>, now: ZonedDateTime): TradeIdea? {
        val created = ZonedDateTime.parse(idea.createdAt)
        val deadline = minOf(created.plusMinutes(idea.horizonMinutes.toLong()), created.with(LocalTime.of(15, 15)))
        val long = idea.action == "LONG"
        // Only candles that start after the idea was created count.
        val window = candles.filter {
            val t = KiteClient.parseCandleTime(it.time)
            t.isAfter(created.minusSeconds(1)) && !t.isAfter(deadline)
        }
        for (c in window) {
            val stopHit = if (long) c.low <= idea.stopLoss else c.high >= idea.stopLoss
            val targetHit = if (long) c.high >= idea.target else c.low <= idea.target
            when {
                stopHit -> return idea.copy(status = "STOP", resolvedAt = c.time, resolvedPrice = idea.stopLoss)
                targetHit -> return idea.copy(status = "TARGET", resolvedAt = c.time, resolvedPrice = idea.target)
            }
        }
        if (now.isAfter(deadline.plusMinutes(2))) {
            return idea.copy(status = "EXPIRED", resolvedAt = deadline.toString(), resolvedPrice = window.lastOrNull()?.close)
        }
        return null
    }

    private suspend fun update(id: String, f: (TradeIdea) -> TradeIdea) = mutex.withLock {
        val i = ideas.indexOfFirst { it.id == id }
        if (i >= 0) { ideas[i] = f(ideas[i]); save() }
    }

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, TallyJson.encodeToString(ListSerializer(TradeIdea.serializer()), ideas))
        }.onFailure { log.error("Saving ideas failed", it) }
    }
}
