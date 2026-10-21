package com.tally.ai

import com.tally.config.AiConfig
import com.tally.kite.Candle
import com.tally.kite.KiteClient
import com.tally.market.GlobalMarkets
import com.tally.market.InstrumentService
import com.tally.market.MarketDataService
import com.tally.strategy.Indicators
import com.tally.util.IST
import com.tally.util.MarketClock
import com.tally.util.TallyJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.abs

@Serializable
data class Source(val title: String, val url: String)

@Serializable
data class MarketBrief(
    val at: String,
    val markdown: String,
    val sources: List<Source>,
    val costUsd: Double,
)

@Serializable
data class IdeaRun(
    val at: String,
    val marketView: String,
    val ideaIds: List<String>,
    val costUsd: Double,
    /** Casual one-liner for the dashboard's "bot says" bubble. */
    val botSays: String = "",
)

/** Indices the AI covers, with their Kite quote keys. */
val AI_INDICES = linkedMapOf("NIFTY" to "NSE:NIFTY 50", "BANKNIFTY" to "NSE:NIFTY BANK")
const val INDIA_VIX = "NSE:INDIA VIX"

/**
 * Two Claude jobs:
 *  - brief(): web-searched international + domestic news and how it should ripple into Indian indices.
 *  - ideas(): structured intraday trade ideas on NIFTY / BANKNIFTY from live Kite data + the latest brief.
 * Ideas are recorded and scored by [IdeaTracker] so the dashboard can show Claude's real hit rate.
 */
class MarketIntel(
    private val api: ClaudeApi,
    private val cfg: AiConfig,
    private val market: MarketDataService,
    private val global: GlobalMarkets,
    private val tracker: IdeaTracker,
    private val instruments: InstrumentService,
    private val clock: MarketClock,
    dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(MarketIntel::class.java)
    private val briefFile = dataDir.resolve("ai_brief.json")
    private val briefLock = Mutex()
    private val ideaLock = Mutex()

    @Volatile var latestBrief: MarketBrief? = runCatching {
        if (Files.exists(briefFile)) TallyJson.decodeFromString(MarketBrief.serializer(), Files.readString(briefFile)) else null
    }.getOrNull()
        private set

    // Last runs survive restarts so the scheduler doesn't re-run (and re-pay for) a scan right after a deploy.
    private val runFile = dataDir.resolve("ai_last_run.json")
    private val stockRunFile = dataDir.resolve("ai_last_stock_run.json")
    private fun loadRun(f: Path): IdeaRun? = runCatching {
        if (Files.exists(f)) TallyJson.decodeFromString(IdeaRun.serializer(), Files.readString(f)) else null
    }.getOrNull()
    private fun saveRun(f: Path, run: IdeaRun) = runCatching { Files.writeString(f, TallyJson.encodeToString(IdeaRun.serializer(), run)) }

    @Volatile var latestRun: IdeaRun? = loadRun(runFile)
        private set

    // ---------------------------------------------------------------- brief

    suspend fun refreshBrief(kiteAvailable: Boolean): MarketBrief = briefLock.withLock {
        val now = clock.now()
        val context = buildJsonObject {
            put("nowIST", now.toString())
            put("indianMarketOpen", clock.isOpen(now))
            put("global", TallyJson.encodeToJsonElement(global.snapshot()))
            if (kiteAvailable) runCatching { put("india", indiaQuotes()) }
        }
        val body = ClaudeApi.baseBody(api.model, BRIEF_SYSTEM, 12000, "medium").let {
            JsonObject(it + ("tools" to buildJsonArray {
                addJsonObject {
                    put("type", "web_search_20260209")
                    put("name", "web_search")
                    put("max_uses", 6)
                }
            }))
        }
        val prompt = """
            Current market data (prices may be delayed; the Indian quotes are live from the broker):
            <market_data>
            $context
            </market_data>

            Search the web for the latest news that matters for Indian equity indices today: overnight US/European
            moves and why, Asian session, crude, dollar/rupee, US yields, Fed/RBI and other central-bank news,
            geopolitics, FII/DII flows, major Indian corporate or policy news, and any scheduled events today.

            Then write the brief with these sections:
            ## Bottom line  (2-3 bullets: expected tone for NIFTY and BANKNIFTY today and the single biggest driver)
            ## Global overnight
            ## Key news
            ## Ripple effects on India  (how each driver transmits: which sectors/indices, direction, and how strongly)
            ## Levels and events to watch today
            Keep it under 450 words. Mark anything you could not verify as unconfirmed.
        """.trimIndent()

        val r = api.create(body, prompt)
        val sources = r.content
            .filter { it["type"]?.jsonPrimitive?.content == "web_search_tool_result" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "web_search_result" }
            .mapNotNull { s ->
                val url = s["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Source(s["title"]?.jsonPrimitive?.contentOrNull ?: url, url)
            }
            .distinctBy { it.url }
            .take(12)
        val markdown = if (r.stopReason == "refusal") "_Claude declined to produce a brief this time._" else r.text.trim()
        val brief = MarketBrief(now.toString(), markdown, sources, r.costUsd)
        latestBrief = brief
        runCatching { Files.writeString(briefFile, TallyJson.encodeToString(MarketBrief.serializer(), brief)) }
        brief
    }

    // ---------------------------------------------------------------- ideas

    /** Learning mode trades more so there is more to learn from; the AI trader still filters every idea. */
    private fun callRule() = if (cfg.learningMode)
        "LEARNING MODE (paper money): give LONG or SHORT whenever there is a tradeable setup with a logical stop and target; " +
            "use NO_TRADE only when there is no defined setup at all. The fund manager filters, and every trade is reviewed so you learn."
    else "Use NO_TRADE unless the setup is genuinely strong."

    /** Lessons from reviewed trades ([TradeJournal.playbookPrompt]); empty until the first review. */
    var playbook: () -> String = { "" }

    suspend fun generateIdeas(): IdeaRun = ideaLock.withLock {
        val now = clock.now()
        val snapshots = AI_INDICES.mapValues { (_, key) -> indexSnapshot(key, now) }
        val context = buildJsonObject {
            put("nowIST", now.toString())
            put("sessionEndsIST", "15:30; all ideas must resolve by 15:15")
            put("indices", JsonObject(snapshots))
            put("india", indiaQuotes())
            put("global", TallyJson.encodeToJsonElement(global.snapshot()))
        }
        val brief = latestBrief?.let { "Latest news brief (${it.at}):\n<brief>\n${it.markdown.take(6000)}\n</brief>" }
            ?: "No news brief is available yet."

        val body = buildJsonObject {
            put("model", api.model)
            put("max_tokens", 16000)
            put("system", IDEA_SYSTEM.replace("{MIN_RR}", cfg.minRewardRisk.toString()))
            putJsonObject("output_config") {
                put("effort", "high")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", ideaSchema("index", AI_INDICES.keys.toList()))
                }
            }
            put("fallbacks", "default")
        }
        val prompt = """
            $brief

            ${playbook()}

            Live data:
            <market_data>
            $context
            </market_data>

            Give one entry per index (NIFTY and BANKNIFTY). ${callRule()}
        """.trimIndent()

        val r = api.create(body, prompt)
        if (r.stopReason == "refusal" || r.stopReason == "max_tokens") {
            throw IllegalStateException("Idea generation stopped: ${r.stopReason}")
        }
        val parsed = TallyJson.parseToJsonElement(r.text).jsonObject
        val ideas = parsed["ideas"]!!.jsonArray.map { it.jsonObject }.mapNotNull { j ->
            val index = j.str("index").uppercase()
            val key = AI_INDICES[index] ?: return@mapNotNull null
            val ltp = snapshots[index]?.jsonObject?.get("ltp")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            validate(
                TradeIdea(
                    id = UUID.randomUUID().toString().take(8),
                    createdAt = now.toString(),
                    index = index,
                    quoteKey = key,
                    action = j.str("action").uppercase(),
                    confidence = j["confidence"]!!.jsonPrimitive.int.coerceIn(0, 100),
                    entry = j.num("entry"),
                    stopLoss = j.num("stopLoss"),
                    target = j.num("target"),
                    horizonMinutes = j["horizonMinutes"]!!.jsonPrimitive.int.coerceIn(5, 360),
                    rationale = j.str("rationale"),
                    invalidation = j.str("invalidation"),
                    riskyVerdict = j.str("riskyVerdict").uppercase().ifBlank { "NA" },
                    riskyReason = j.str("riskyReason"),
                    refPrice = ltp,
                ),
                now,
            )
        }
        tracker.add(ideas)
        val run = IdeaRun(now.toString(), parsed["marketView"]?.jsonPrimitive?.contentOrNull.orEmpty(), ideas.map { it.id }, r.costUsd,
            parsed["botSays"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200))
        latestRun = run
        saveRun(runFile, run)
        log.info("AI ideas: {}", ideas.joinToString { "${it.index} ${it.action} ${it.confidence}% ${if (it.qualified) "QUALIFIED" else it.note}" })
        run
    }

    // ---------------------------------------------------------------- stock option ideas

    @Volatile var latestStockRun: IdeaRun? = loadRun(stockRunFile)
        private set
    private val stockLock = Mutex()

    /**
     * Screens the F&O stock watchlist for the day's biggest movers (cheap, one quote call), then asks Claude
     * for strict LONG / SHORT / NO_TRADE calls on the shortlist only, executed as ATM stock options.
     */
    suspend fun generateStockIdeas(): IdeaRun = stockLock.withLock {
        val now = clock.now()
        val optionable = cfg.stocks.filter { runCatching { instruments.options(it).isNotEmpty() }.getOrDefault(false) }
        check(optionable.isNotEmpty()) { "None of the configured stocks have NFO options" }
        val quotes = market.quote(optionable.map { "NSE:$it" })
        val shortlist = optionable.mapNotNull { sym ->
            val q = quotes["NSE:$sym"] ?: return@mapNotNull null
            if (q.ohlc.close <= 0) return@mapNotNull null
            sym to abs(q.lastPrice - q.ohlc.close) / q.ohlc.close
        }.sortedByDescending { it.second }.take(cfg.stockShortlist).map { it.first }

        val snapshots = shortlist.associateWith { indexSnapshot("NSE:$it", now) }
        val context = buildJsonObject {
            put("nowIST", now.toString())
            put("sessionEndsIST", "15:30; all ideas must resolve by 15:15")
            put("stocks", JsonObject(snapshots))
            put("india", indiaQuotes())
            put("global", TallyJson.encodeToJsonElement(global.snapshot()))
        }
        val brief = latestBrief?.let { "Latest news brief (${it.at}):\n<brief>\n${it.markdown.take(6000)}\n</brief>" }
            ?: "No news brief is available yet."
        val body = buildJsonObject {
            put("model", api.model)
            put("max_tokens", 20000)
            put("system", STOCK_IDEA_SYSTEM.replace("{MIN_RR}", cfg.minRewardRisk.toString()))
            putJsonObject("output_config") {
                put("effort", "high")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", ideaSchema("symbol", shortlist))
                }
            }
            put("fallbacks", "default")
        }
        val prompt = """
            $brief

            ${playbook()}

            Live data for today's biggest movers among liquid F&O stocks:
            <market_data>
            $context
            </market_data>

            Give one entry per stock (${shortlist.joinToString()}). ${callRule()}
        """.trimIndent()

        val r = api.create(body, prompt)
        if (r.stopReason == "refusal" || r.stopReason == "max_tokens") throw IllegalStateException("Stock idea generation stopped: ${r.stopReason}")
        val parsed = TallyJson.parseToJsonElement(r.text).jsonObject
        val ideas = parsed["ideas"]!!.jsonArray.map { it.jsonObject }.mapNotNull { j ->
            val sym = j.str("symbol").uppercase()
            if (sym !in shortlist) return@mapNotNull null
            val ltp = snapshots[sym]?.get("ltp")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            validate(
                TradeIdea(
                    id = UUID.randomUUID().toString().take(8), createdAt = now.toString(), index = sym, quoteKey = "NSE:$sym",
                    kind = "STOCK", action = j.str("action").uppercase(),
                    confidence = j["confidence"]!!.jsonPrimitive.int.coerceIn(0, 100),
                    entry = j.num("entry"), stopLoss = j.num("stopLoss"), target = j.num("target"),
                    horizonMinutes = j["horizonMinutes"]!!.jsonPrimitive.int.coerceIn(5, 360),
                    rationale = j.str("rationale"), invalidation = j.str("invalidation"), refPrice = ltp,
                    riskyVerdict = j.str("riskyVerdict").uppercase().ifBlank { "NA" }, riskyReason = j.str("riskyReason"),
                ),
                now,
            )
        }
        tracker.add(ideas)
        val run = IdeaRun(now.toString(), parsed["marketView"]?.jsonPrimitive?.contentOrNull.orEmpty(), ideas.map { it.id }, r.costUsd,
            parsed["botSays"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200))
        latestStockRun = run
        saveRun(stockRunFile, run)
        log.info("AI stock ideas: {}", ideas.joinToString { "${it.index} ${it.action} ${it.confidence}% ${if (it.qualified) "QUALIFIED" else it.note}" })
        run
    }

    /** Deterministic checks on top of Claude's confidence. Only qualified ideas can be auto-traded. */
    private fun validate(idea: TradeIdea, now: ZonedDateTime): TradeIdea {
        if (idea.action == "NO_TRADE") return idea.copy(status = "NO_TRADE", note = "No trade")
        val long = idea.action == "LONG"
        val confidenceProblem = "confidence ${idea.confidence} < ${cfg.minConfidence}"
        val problems = buildList {
            if (idea.action !in setOf("LONG", "SHORT")) add("unknown action")
            if (long && !(idea.stopLoss < idea.entry && idea.entry < idea.target)) add("levels not ordered for LONG")
            if (!long && !(idea.stopLoss > idea.entry && idea.entry > idea.target)) add("levels not ordered for SHORT")
            val risk = abs(idea.entry - idea.stopLoss)
            val reward = abs(idea.target - idea.entry)
            if (risk > 0 && reward / risk < cfg.minRewardRisk) add("reward/risk %.2f < %.1f".format(reward / risk, cfg.minRewardRisk))
            val stock = idea.kind == "STOCK"
            val (minStop, maxStop) = if (stock) 0.2 to 3.0 else 0.08 to 1.5
            val stopPct = risk / idea.refPrice * 100
            if (stopPct < minStop || stopPct > maxStop) add("stop distance %.2f%% outside %.2f-%.1f%%".format(stopPct, minStop, maxStop))
            val maxEntryGap = if (stock) 0.003 else 0.002
            if (abs(idea.entry - idea.refPrice) / idea.refPrice > maxEntryGap) add("entry %.1f is away from market %.1f".format(idea.entry, idea.refPrice))
            if (!clock.isOpen(now)) add("market closed")
            if (idea.confidence < cfg.minConfidence) add(confidenceProblem)
        }
        val levelsOk = problems.all { it == confidenceProblem }
        return if (problems.isEmpty()) idea.copy(qualified = true, levelsOk = true, note = "Qualified")
        else idea.copy(qualified = false, levelsOk = levelsOk, note = problems.joinToString("; "))
    }

    // ---------------------------------------------------------------- data

    suspend fun indiaQuotes(): JsonObject {
        val q = market.quote(AI_INDICES.values + listOf("NSE:NIFTY FIN SERVICE", INDIA_VIX))
        return buildJsonObject {
            q.forEach { (k, v) ->
                putJsonObject(k) {
                    put("ltp", v.lastPrice)
                    put("prevClose", v.ohlc.close)
                    if (v.ohlc.close > 0) put("changePct", round2((v.lastPrice - v.ohlc.close) / v.ohlc.close * 100))
                    put("dayOpen", v.ohlc.open); put("dayHigh", v.ohlc.high); put("dayLow", v.ohlc.low)
                }
            }
        }
    }

    /** Live + historical picture of one underlying (index or NSE stock key), as sent to Claude. */
    suspend fun snapshot(key: String): JsonObject = indexSnapshot(key, clock.now())

    private suspend fun indexSnapshot(key: String, now: ZonedDateTime): JsonObject {
        val (exchange, symbol) = key.split(":", limit = 2)
        val intraday = market.candles(exchange, symbol, "5minute", 5)
        // A year of daily bars: long-term trend and 52-week extremes, not just the last few weeks.
        val daily = market.candles(exchange, symbol, "day", 365)
        val ltp = market.ltp(key)
        val close5 = intraday.map { it.close }
        val today = intraday.filter { KiteClient.parseCandleTime(it.time).toLocalDate() == now.toLocalDate() }
        val prevDay = daily.lastOrNull { KiteClient.parseCandleTime(it.time).toLocalDate().isBefore(now.toLocalDate()) }
        val dClose = daily.map { it.close }
        return buildJsonObject {
            put("ltp", ltp)
            put("ema9_5m", last(Indicators.ema(close5, 9)))
            put("ema21_5m", last(Indicators.ema(close5, 21)))
            put("rsi14_5m", last(Indicators.rsi(close5, 14)))
            put("atr14_5m", last(Indicators.atr(intraday.map { it.high }, intraday.map { it.low }, close5, 14)))
            put("ema20_daily", last(Indicators.ema(dClose, 20)))
            put("ema50_daily", last(Indicators.ema(dClose, 50)))
            put("rsi14_daily", last(Indicators.rsi(dClose, 14)))
            put("atr14_daily", last(Indicators.atr(daily.map { it.high }, daily.map { it.low }, dClose, 14)))
            put("ema200_daily", last(Indicators.ema(dClose, 200)))
            if (daily.isNotEmpty()) {
                put("high52w", daily.maxOf { it.high })
                put("low52w", daily.minOf { it.low })
            }
            fun ret(bars: Int) = dClose.getOrNull(dClose.size - 1 - bars)?.takeIf { it > 0 }?.let { round2((ltp - it) / it * 100) }
            ret(5)?.let { put("return5dPct", it) }
            ret(20)?.let { put("return20dPct", it) }
            ret(60)?.let { put("return60dPct", it) }
            prevDay?.let { putJsonObject("prevDay") { put("high", it.high); put("low", it.low); put("close", it.close) } }
            if (today.isNotEmpty()) {
                putJsonObject("today") {
                    put("open", today.first().open)
                    put("high", today.maxOf { it.high })
                    put("low", today.minOf { it.low })
                    val or = today.take(3)
                    put("openingRange15mHigh", or.maxOf { it.high })
                    put("openingRange15mLow", or.minOf { it.low })
                }
            }
            put("last36Bars5m", compact(intraday.takeLast(36)))
            put("last10Daily", compact(daily.takeLast(10)))
        }
    }

    private fun compact(c: List<Candle>) = buildJsonArray {
        c.forEach { add("${it.time.substring(5, 16)} O${it.open} H${it.high} L${it.low} C${it.close}") }
    }

    private fun last(a: DoubleArray): Double? = a.lastOrNull()?.takeIf { !it.isNaN() }?.let(::round2)
    private fun round2(d: Double) = Math.round(d * 100) / 100.0
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.num(k: String) = this[k]!!.jsonPrimitive.double

    companion object {
        private val BRIEF_SYSTEM = """
            You are a markets strategist briefing one retail trader in India who trades NIFTY and BANKNIFTY intraday.
            Use web search to find what happened overnight and this morning, then explain plainly how it is likely to
            transmit into Indian indices. Be specific (numbers, levels, sectors) and concise. Separate facts from your
            inference. Cite nothing you did not find.
        """.trimIndent()

        private val IDEA_SYSTEM = """
            You generate intraday trade ideas on Indian index levels (NIFTY 50, NIFTY BANK) for one retail trader.
            The trader executes by BUYING at-the-money index options (call for LONG, put for SHORT), so the trade
            needs a clean directional move soon: option premiums decay while price goes nowhere. All prices you
            give are INDEX levels; the system converts them to option premiums.

            For each index decide LONG, SHORT or NO_TRADE for the next 15-120 minutes, ending by 15:15 IST.
            - entry must be the current index price (the "ltp"); do not give limit levels away from the market.
            - stopLoss goes where the idea is clearly wrong (structure, not an arbitrary number); target at a level
              price can realistically reach within the horizon. Reward/risk must be at least {MIN_RR}.
            - confidence is your estimated probability (0-100) that price reaches target BEFORE stopLoss within the
              horizon. Be calibrated: intraday index direction is close to a coin flip most of the time, so most
              readings belong in 45-70. Reserve 90+ for rare cases where trend, momentum, levels, volatility and the
              news backdrop all agree. Your past ideas are scored against outcomes, and over-confidence is visible.
            - NO_TRADE is the right answer whenever the picture is mixed, near major events, or in the first 10 minutes.
            Keep rationale to 2-3 sentences citing specific data; invalidation is one sentence.
            riskyVerdict / riskyReason: a separate judgement for a high-risk, high-reward section where the trader
            may take LONG/SHORT ideas below the confidence bar as small speculative punts with a capped loss.
            Ignore your confidence threshold here and ask: is the payoff worth the gamble? TAKE when reward clearly
            outweighs risk (room to run, fresh catalyst, a tight logical stop, enough time left for the option to
            pay); SKIP when it is a chase, a coin flip with poor payoff, or likely to bleed premium. NA for NO_TRADE.
            riskyReason is one plain sentence.
            botSays: one casual, first-person sentence (max 20 words) telling the trader whether now is a good
            time to trade and why, in plain words, e.g. "Oh no, not a good time to trade: banks are dumping and
            VIX is climbing." or "Markets feel shaky, something's brewing in US futures. I'd wait." Honest, not hype.
        """.trimIndent()

        private val STOCK_IDEA_SYSTEM = """
            You generate intraday trade ideas on liquid Indian F&O stocks for one retail trader. The trader executes
            by BUYING at-the-money stock options (call for LONG, put for SHORT), so the idea needs a clean move
            soon: option premiums decay while price goes nowhere. All prices you give are STOCK prices; the system
            converts them to option premiums.

            For each stock decide LONG, SHORT or NO_TRADE for the next 15-120 minutes, ending by 15:15 IST.
            - entry must be the current stock price (the "ltp"); do not give limit levels away from the market.
            - stopLoss goes where the idea is clearly wrong (a level from the data: opening range, prior day
              high/low, VWAP-like area, recent swing), between 0.2% and 3% from entry. target at a level price can
              realistically reach within the horizon. Reward/risk must be at least {MIN_RR}.
            - These stocks were shortlisted because they are moving most today. A big move is not a reason by
              itself: chasing extended moves into resistance/support is how option buyers lose money.
            - confidence is your estimated probability (0-100) that price reaches target BEFORE stopLoss within the
              horizon. Be calibrated: most readings belong in 45-70. Reserve 90+ for rare cases where trend,
              momentum, levels, the index direction, sector and news all agree. Your past ideas are scored against
              outcomes, and over-confidence is visible.
            - NO_TRADE is the right answer whenever the picture is mixed, the move is exhausted, earnings or other
              stock-specific events are close, or in the first 15 minutes.
            Keep rationale to 2-3 sentences citing specific data; invalidation is one sentence.
            riskyVerdict / riskyReason: a separate judgement for a high-risk, high-reward section where the trader
            may take LONG/SHORT ideas below the confidence bar as small speculative punts with a capped loss.
            Ignore your confidence threshold here and ask: is the payoff worth the gamble? TAKE when reward clearly
            outweighs risk (room to run, fresh catalyst, a tight logical stop, enough time left for the option to
            pay); SKIP when it is a chase, a coin flip with poor payoff, or likely to bleed premium. NA for NO_TRADE.
            riskyReason is one plain sentence.
            botSays: one casual, first-person sentence (max 20 words) telling the trader whether now is a good
            time to trade and why, in plain words, e.g. "Oh no, not a good time to trade: banks are dumping and
            VIX is climbing." or "Markets feel shaky, something's brewing in US futures. I'd wait." Honest, not hype.
        """.trimIndent()

        private fun ideaSchema(field: String, values: List<String>) = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("marketView"); add("botSays"); add("ideas") }
            putJsonObject("properties") {
                putJsonObject("marketView") { put("type", "string") }
                putJsonObject("botSays") { put("type", "string") }
                putJsonObject("ideas") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        val fields = listOf(field, "action", "confidence", "entry", "stopLoss", "target", "horizonMinutes", "rationale", "invalidation", "riskyVerdict", "riskyReason")
                        putJsonArray("required") { fields.forEach { add(it) } }
                        putJsonObject("properties") {
                            putJsonObject(field) { put("type", "string"); putJsonArray("enum") { values.forEach { add(it) } } }
                            putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add("LONG"); add("SHORT"); add("NO_TRADE") } }
                            putJsonObject("confidence") { put("type", "integer") }
                            putJsonObject("entry") { put("type", "number") }
                            putJsonObject("stopLoss") { put("type", "number") }
                            putJsonObject("target") { put("type", "number") }
                            putJsonObject("horizonMinutes") { put("type", "integer") }
                            putJsonObject("rationale") { put("type", "string") }
                            putJsonObject("invalidation") { put("type", "string") }
                            putJsonObject("riskyVerdict") { put("type", "string"); putJsonArray("enum") { add("TAKE"); add("SKIP"); add("NA") } }
                            putJsonObject("riskyReason") { put("type", "string") }
                        }
                    }
                }
            }
        }
    }
}
