package com.tally.ai

import com.tally.config.AiConfig
import com.tally.market.GlobalMarkets
import com.tally.util.IST
import com.tally.util.MarketClock
import com.tally.util.TallyJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZonedDateTime

@Serializable
data class DeskPick(val ideaId: String, val underlying: String, val action: String, val riskPct: Int, val reason: String, val result: String)

@Serializable
data class DeskRun(
    val at: String,
    /** Overall market call: LONG, SHORT or NEUTRAL. */
    val stance: String,
    val marketView: String,
    val considered: List<String>,
    val picks: List<DeskPick>,
    /** Why the rest (or everything) was passed on. */
    val passReason: String,
    val costUsd: Double,
)

@Serializable
data class AiTraderDeskState(
    var enabled: Boolean = false,
    /** INR of premium the AI trader may have deployed at once. */
    var fund: Double = 0.0,
    var day: String = "",
    val runs: MutableList<DeskRun> = mutableListOf(),
    /** Ideas already shown to the fund-manager call today, so the same idea is not paid for twice. */
    val seen: MutableList<String> = mutableListOf(),
)

@Serializable
data class AiTraderDeskStatus(
    val enabled: Boolean,
    val fund: Double,
    val fundInUse: Double,
    val fundFree: Double,
    val runs: List<DeskRun>,
)

/**
 * The autonomous "AI trader": a fund-manager Claude call that sits on top of the idea pipeline.
 * After every idea run it looks at the fresh LONG/SHORT ideas (rule-checked levels, any confidence), adds a
 * year of price history, the news brief, global markets, today's results and the idea track record, and decides
 * which ones to trade and how much of the per-trade loss cap to risk. Execution goes through [AiTrader], so
 * every hard limit still applies: the fund cap, max loss per trade, the daily loss limit, the profit goal,
 * max open trades, one trade per underlying, broker-side stop-losses and the 15:15 exit.
 */
class AiTraderDesk(
    private val api: ClaudeApi,
    private val cfg: AiConfig,
    private val intel: MarketIntel,
    private val tracker: IdeaTracker,
    private val trader: AiTrader,
    private val settings: TradeSettingsStore,
    private val global: GlobalMarkets,
    private val clock: MarketClock,
    live: Boolean,
    dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(AiTraderDesk::class.java)
    // Separate per mode, like the trader: enabling it in PAPER must never carry over to LIVE.
    private val file = dataDir.resolve(if (live) "ai_desk_live.json" else "ai_desk.json")
    private val mutex = Mutex()
    /** Held for a whole decision (Claude call included) so runs never overlap; [mutex] only guards state briefly. */
    private val decideLock = Mutex()
    private val state: AiTraderDeskState = runCatching {
        if (Files.exists(file)) TallyJson.decodeFromString(AiTraderDeskState.serializer(), Files.readString(file)) else null
    }.getOrNull() ?: AiTraderDeskState()

    suspend fun status(): AiTraderDeskStatus = mutex.withLock {
        rollDay()
        val inUse = trader.aiTraderFundInUse()
        AiTraderDeskStatus(state.enabled, state.fund, inUse, (state.fund - inUse).coerceAtLeast(0.0), state.runs.toList().asReversed())
    }

    suspend fun configure(enabled: Boolean, fund: Double): AiTraderDeskStatus {
        require(fund in 0.0..10_000_000.0) { "fund must be 0 - 1,00,00,000" }
        require(!enabled || fund >= MIN_FUND) { "Give the AI trader at least ₹%,.0f to work with".format(MIN_FUND) }
        mutex.withLock {
            state.enabled = enabled
            state.fund = fund
            save()
        }
        log.info("AI trader {} with fund ₹{}", if (enabled) "ENABLED" else "disabled", fund)
        return status()
    }

    /**
     * One fund-manager decision. Skips (returns null, no Claude call) when the AI trader is off, nothing new is
     * on the table, or the limits leave no room for another trade.
     */
    suspend fun decide(): DeskRun? = decideLock.withLock {
        mutex.withLock { rollDay() }
        if (!state.enabled || !api.configured || api.spendToday().usd >= cfg.dailyBudgetUsd) return null
        val now = clock.now()
        if (!clock.isOpen(now)) return null
        val t = trader.status()
        if (t.slotsLeft < 1 || t.lossBudgetLeft < 1 || t.profitGoalReached) return null
        val fundFree = state.fund - trader.aiTraderFundInUse()
        if (fundFree < MIN_FUND) return null

        val busy = (t.opens.map { it.trade.index } + t.pendings.map { it.index }).toSet()
        val candidates = tracker.recent(80).filter {
            val created = ZonedDateTime.parse(it.createdAt)
            it.status == "OPEN" && it.levelsOk && (it.action == "LONG" || it.action == "SHORT") && (it.action == "LONG" || cfg.allowShort) &&
                !it.id.startsWith("test-") && it.index !in busy &&
                created.toLocalDate() == now.toLocalDate() && now.isBefore(created.plusMinutes(it.horizonMinutes.toLong()))
        }.distinctBy { it.index }
        if (candidates.none { it.id !in state.seen }) return null

        val plans = trader.plans(candidates)
        val charts = candidates.associate { it.id to runCatching { intel.snapshot(it.quoteKey) }.getOrNull() }
        val india = runCatching { intel.indiaQuotes() }.getOrNull()
        val globalQuotes = TallyJson.encodeToJsonElement(global.snapshot())
        val stats = TallyJson.encodeToJsonElement(ListSerializer(BucketStats.serializer()), tracker.stats())
        val context = buildJsonObject {
            put("nowIST", now.toString())
            put("lastNewEntryIST", "14:45; everything is closed by 15:15")
            putJsonObject("book") {
                put("fundTotal", state.fund)
                put("fundFree", fundFree)
                put("maxLossPerTradeINR", settings.current.maxLossPerTrade)
                put("dailyLossLeftINR", t.lossBudgetLeft)
                put("dailyProfitGoalINR", settings.current.dailyProfitGoal)
                put("realisedTodayINR", t.realizedPnlToday)
                put("openPnlINR", t.openPnl)
                put("slotsLeft", t.slotsLeft)
                putJsonArray("openTrades") { t.opens.forEach { add("${it.trade.index} ${it.trade.symbol} [${it.trade.source}] P&L ${it.pnl?.let(::r0)}") } }
                putJsonArray("closedToday") { t.closedToday.forEach { add("${it.index} ${it.symbol} [${it.source}] P&L ${it.pnl?.let(::r0)} (${it.exitReason})") } }
            }
            put("ideaTrackRecord", stats)
            put("india", india ?: JsonNull)
            put("global", globalQuotes)
            putJsonArray("candidates") {
                candidates.forEach { i ->
                    addJsonObject {
                        put("ideaId", i.id)
                        put("underlying", i.index)
                        put("kind", i.kind)
                        put("action", i.action)
                        put("analystConfidence", i.confidence)
                        put("createdAtIST", i.createdAt)
                        put("entry", i.entry); put("stopLoss", i.stopLoss); put("target", i.target)
                        put("horizonMinutes", i.horizonMinutes)
                        put("rationale", i.rationale)
                        put("invalidation", i.invalidation)
                        put("speculativeVerdict", "${i.riskyVerdict}: ${i.riskyReason}")
                        plans[i.id]?.let { p ->
                            put("option", "${p.symbol} premium ${p.premium}, lot ${p.lotSize}, 1 lot costs ${r0(p.oneLotCost)}, " +
                                "1 lot risks ${r0(p.oneLotRisk)}, target ${p.targetMultiple}x, spread ${p.spreadPct}%")
                        }
                        put("chart", charts[i.id] ?: JsonNull)
                    }
                }
            }
        }
        val brief = intel.latestBrief?.let { "Latest news brief (${it.at}):\n<brief>\n${it.markdown.take(5000)}\n</brief>" }
            ?: "No news brief is available yet."
        val body = buildJsonObject {
            put("model", api.model)
            put("max_tokens", 16000)
            put("system", SYSTEM)
            putJsonObject("output_config") {
                put("effort", "high")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", schema(candidates.map { it.id }))
                }
            }
            put("fallbacks", "default")
        }
        val prompt = """
            $brief

            <desk>
            $context
            </desk>

            Decide which candidates (if any) to trade right now and how much of the per-trade loss cap to risk on each.
        """.trimIndent()

        mutex.withLock {
            state.seen += candidates.map { it.id }.filter { it !in state.seen }
            save()
        }
        val r = api.create(body, prompt)
        if (r.stopReason == "refusal" || r.stopReason == "max_tokens") throw IllegalStateException("AI trader decision stopped: ${r.stopReason}")
        val parsed = TallyJson.parseToJsonElement(r.text).jsonObject
        val wanted = parsed["trades"]!!.jsonArray.map { it.jsonObject }
        val byId = candidates.associateBy { it.id }

        var free = fundFree
        val picks = wanted.mapNotNull { j ->
            val idea = byId[j.str("ideaId")] ?: return@mapNotNull null
            val riskPct = j["riskPct"]!!.jsonPrimitive.int.coerceIn(25, 100)
            val maxLoss = settings.current.maxLossPerTrade * riskPct / 100
            val result = runCatching { trader.aiTraderTake(idea.id, maxLoss, free) }.getOrElse { "Not placed: ${it.message}" }
            free = state.fund - trader.aiTraderFundInUse()
            DeskPick(idea.id, idea.index, idea.action, riskPct, j.str("reason"), result)
        }
        val run = DeskRun(
            at = now.toString(), stance = parsed.str("stance").ifBlank { "NEUTRAL" }, marketView = parsed.str("marketView"),
            considered = candidates.map { "${it.index} ${it.action} ${it.confidence}%" }, picks = picks,
            passReason = parsed.str("passReason"), costUsd = r.costUsd,
        )
        mutex.withLock {
            state.runs += run
            while (state.runs.size > 30) state.runs.removeAt(0)
            save()
        }
        log.info("AI trader: stance {} picks {}", run.stance, picks.joinToString { "${it.underlying} ${it.action} ${it.riskPct}%: ${it.result}" })
        run
    }

    private fun rollDay() {
        val today = LocalDate.now(IST).toString()
        if (state.day == today) return
        state.day = today
        state.runs.clear()
        state.seen.clear()
        save()
    }

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, TallyJson.encodeToString(AiTraderDeskState.serializer(), state))
        }.onFailure { log.error("Saving AI trader desk state failed", it) }
    }

    private fun r0(d: Double) = Math.round(d).toDouble()
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    companion object {
        const val MIN_FUND = 2000.0

        private val SYSTEM = """
            You are the senior fund manager running a small intraday options book for one retail client in India.
            The client has handed you a fixed fund and wants profit. Your job is to grow it over weeks, which means
            protecting it on bad days: a blown-up book makes nothing tomorrow.

            How trades work: every trade BUYS an at-the-money option (call for LONG, put for SHORT) on NIFTY,
            BANKNIFTY or a liquid F&O stock, intraday only. Premiums decay when price stalls, so only a clean,
            timely move pays. A broker stop-loss, a trailing stop and a target are placed automatically; you choose
            WHICH analyst ideas to execute and how much of the per-trade loss cap (riskPct 25-100) to put on each.

            Read everything before deciding:
            - charts: trend agreement across 5-minute, daily and 200-day (ema200_daily), 52-week extremes, recent
              returns, RSI and ATR. Avoid buying calls into resistance or puts into support; avoid chasing a move
              that has already used most of its daily ATR.
            - news brief and global markets: does the backdrop support the direction? Is a scheduled event close?
            - the analysts' confidence is an estimate, not a fact. The ideaTrackRecord shows how ideas really
              did; if a bucket wins below ~45%, discount it.
            - the book: realised and open P&L today, what already won or lost and why. After two losses, or with
              little daily loss left, be stricter and smaller. Never add a second bet that is really the same bet
              (e.g. NIFTY LONG plus BANKNIFTY LONG on the same driver) unless conviction is high.
            - execution: a wide option spread or a 1-lot risk that eats most of the cap is a reason to pass.

            Passing is often the best trade; take none when nothing is genuinely good. When you trade, size by
            conviction: 100 only for strong, multi-signal agreement; 50 for decent; 25 for speculative.
            stance is your overall call on Indian equities for the rest of today (LONG, SHORT or NEUTRAL).
            marketView: 2-3 plain sentences a client can follow. Each trade reason: one or two sentences citing data.
            passReason: one sentence on what you skipped and why.
        """.trimIndent()

        private fun schema(ids: List<String>) = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("stance"); add("marketView"); add("trades"); add("passReason") }
            putJsonObject("properties") {
                putJsonObject("stance") { put("type", "string"); putJsonArray("enum") { add("LONG"); add("SHORT"); add("NEUTRAL") } }
                putJsonObject("marketView") { put("type", "string") }
                putJsonObject("passReason") { put("type", "string") }
                putJsonObject("trades") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonArray("required") { add("ideaId"); add("riskPct"); add("reason") }
                        putJsonObject("properties") {
                            putJsonObject("ideaId") { put("type", "string"); putJsonArray("enum") { ids.forEach { add(it) } } }
                            putJsonObject("riskPct") { put("type", "integer") }
                            putJsonObject("reason") { put("type", "string") }
                        }
                    }
                }
            }
        }
    }
}
