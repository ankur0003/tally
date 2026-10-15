package com.tally.ai

import com.tally.config.AiConfig
import com.tally.config.RiskConfig
import com.tally.config.TradingMode
import com.tally.kite.*
import com.tally.market.MarketDataService
import com.tally.risk.OrderService
import com.tally.util.IST
import com.tally.util.MarketClock
import com.tally.util.TallyJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min

@Serializable
data class AiTrade(
    val ideaId: String,
    val index: String,
    val exchange: String,
    val symbol: String,
    val optionType: String = "",
    val strike: Double = 0.0,
    val expiry: String = "",
    val side: Side = Side.BUY,
    val quantity: Int,
    val lots: Int = 0,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val entryOrderId: String,
    val stopOrderId: String?,
    val openedAt: String,
    val expiresAt: String,
    /** "auto" or "manual" (Take trade button). */
    val source: String = "auto",
    /** Highest premium seen while open; drives the trailing stop. */
    val peakPrice: Double = 0.0,
    /** Stop where the trade started, before any trailing. */
    val initialStop: Double = 0.0,
    val status: String = "OPEN",
    val exitPrice: Double? = null,
    val exitReason: String? = null,
    val closedAt: String? = null,
    val pnl: Double? = null,
) {
    val key get() = "$exchange:$symbol"
    val riskRupees get() = abs(entryPrice - stopPrice) * quantity
    val investment get() = entryPrice * quantity
}

/** A taken idea waiting for the index to come back to its entry level. */
@Serializable
data class PendingTrade(
    val ideaId: String,
    val index: String,
    val action: String,
    val quoteKey: String,
    val indexEntry: Double,
    val indexStop: Double,
    val armedAt: String,
    val expiresAt: String,
    val lots: Int? = null,
    val targetMultiple: Double? = null,
    val source: String,
)

@Serializable
data class AiTraderState(
    var day: String,
    var autoTrade: Boolean,
    var realizedPnl: Double = 0.0,
    /** Pre-multi-trade fields, migrated into [opens] / [pendings] on load. */
    var open: AiTrade? = null,
    var pending: PendingTrade? = null,
    val opens: MutableList<AiTrade> = mutableListOf(),
    val pendings: MutableList<PendingTrade> = mutableListOf(),
    val closed: MutableList<AiTrade> = mutableListOf(),
    val log: MutableList<String> = mutableListOf(),
)

@Serializable
data class OpenTradeView(val trade: AiTrade, val ltp: Double?, val pnl: Double?)

@Serializable
data class AiTraderStatus(
    val autoTrade: Boolean,
    val mode: String,
    val settings: TradeSettings,
    val realizedPnlToday: Double,
    val lossBudgetLeft: Double,
    /** Worst-case loss if every open trade hits its stop now (trailed stops above entry count as 0). */
    val openRisk: Double,
    val profitGoalReached: Boolean,
    val slotsLeft: Int,
    val pendings: List<PendingTrade>,
    val opens: List<OpenTradeView>,
    val openPnl: Double?,
    val closedToday: List<AiTrade>,
    val log: List<String>,
)

/**
 * Executes ideas by BUYING at-the-money options (CE for LONG, PE for SHORT). Options are never sold to open,
 * so the most a trade can lose is the premium paid, and the stop-loss caps it far below that:
 *  - lots are sized so hitting the stop costs <= maxLossPerTrade, and premium x qty <= capital
 *  - a broker-side SL order protects each position the moment it is filled, with a market exit as backup
 *  - exits on target premium, trailing stop, idea horizon, daily profit goal, or 15:15
 *  - up to [TradeSettings.maxOpenTrades] trades at once, one per underlying, and the stops of all open trades
 *    together can never lose more than what is left of the daily loss limit
 * Trades start from the Take-trade button or auto-trade (qualified ideas only).
 * All orders still pass through [OrderService] and therefore the account-wide risk manager.
 */
class AiTrader(
    private val cfg: AiConfig,
    private val riskCfg: RiskConfig,
    private val orders: OrderService,
    private val market: MarketDataService,
    private val tracker: IdeaTracker,
    private val planner: OptionPlanner,
    private val settings: TradeSettingsStore,
    private val clock: MarketClock,
    dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(AiTrader::class.java)
    // Separate state per mode: paper P&L must never count toward live loss limits or the profit goal.
    private val file = dataDir.resolve(if (orders.broker.mode == TradingMode.LIVE) "ai_trader_live.json" else "ai_trader.json")
    private val mutex = Mutex()
    private val state: AiTraderState = (runCatching {
        if (Files.exists(file)) TallyJson.decodeFromString(AiTraderState.serializer(), Files.readString(file)) else null
    }.getOrNull() ?: AiTraderState(LocalDate.now(IST).toString(), cfg.autoTrade)).also { s ->
        s.open?.let { s.opens += it; s.open = null }
        s.pending?.let { s.pendings += it; s.pending = null }
    }

    suspend fun status(): AiTraderStatus = mutex.withLock {
        rollDay()
        val prices = openPrices()
        val views = state.opens.map { t -> prices[t.key].let { ltp -> OpenTradeView(t, ltp, ltp?.let { (it - t.entryPrice) * t.quantity }) } }
        AiTraderStatus(
            autoTrade = state.autoTrade, mode = orders.broker.mode.name, settings = settings.current,
            realizedPnlToday = state.realizedPnl, lossBudgetLeft = lossBudgetLeft(), openRisk = openRisk(),
            profitGoalReached = profitGoalReached(), slotsLeft = slotsLeft(),
            pendings = state.pendings.toList(), opens = views,
            openPnl = if (views.isEmpty() || views.any { it.pnl == null }) null else views.sumOf { it.pnl!! },
            closedToday = state.closed.toList().asReversed(), log = state.log.takeLast(40).asReversed(),
        )
    }

    /** Live execution plans for the given ideas (premium, lots, money in, max loss, growth forecast). */
    suspend fun plans(ideas: List<TradeIdea>): Map<String, OptionPlan> {
        val budget = mutex.withLock { rollDay(); riskBudgetForNew() }
        return ideas.mapNotNull { idea ->
            runCatching { idea.id to planner.plan(idea, budget, null, if (isHighRisk(idea)) settings.current.highRiskMaxLoss else null) }
                .onFailure { log.debug("No plan for {}: {}", idea.id, it.message) }
                .getOrNull()
        }.toMap()
    }

    suspend fun setAutoTrade(on: Boolean) = mutex.withLock {
        state.autoTrade = on
        note("Auto-trade ${if (on) "ENABLED" else "disabled"} by user")
        save()
    }

    /** Below the confidence bar: only tradeable manually from the High-risk section, with its own smaller loss cap. */
    private fun isHighRisk(idea: TradeIdea) = idea.confidence < cfg.minConfidence && !idea.id.startsWith("test-")

    /**
     * Take-trade button. Enters now if the price is at the idea's entry, otherwise arms the trade and waits for it.
     * Returns a short message for the user; throws IllegalStateException when the trade can't be taken.
     */
    suspend fun take(ideaId: String, lots: Int?, targetMultiple: Double?, highRisk: Boolean = false): String = mutex.withLock {
        rollDay()
        val idea = tracker.get(ideaId) ?: throw IllegalArgumentException("Unknown idea $ideaId")
        require(lots == null || lots >= 1) { "lots must be at least 1" }
        require(targetMultiple == null || targetMultiple in 1.1..5.0) { "target multiple must be 1.1x - 5x" }
        val msg = arm(idea, lots, targetMultiple, if (highRisk) HIGH_RISK else "manual", clock.now())
        save()
        msg
    }

    /** Cancels one waiting trade, or all of them when [ideaId] is null. */
    suspend fun cancelPending(ideaId: String? = null) = mutex.withLock {
        state.pendings.filter { ideaId == null || it.ideaId == ideaId }.forEach {
            state.pendings.remove(it)
            note("Cancelled waiting trade ${it.index} ${it.action} (idea ${it.ideaId})")
        }
        save()
    }

    /** Called after each idea run. Arms the best qualified ideas, one per underlying, while slots are free. */
    suspend fun onIdeas(ideaIds: List<String>) = mutex.withLock {
        rollDay()
        if (!state.autoTrade) return
        val candidates = ideaIds.mapNotNull { tracker.get(it) }
            .filter { it.qualified && (it.action == "LONG" || cfg.allowShort) }
            .sortedByDescending { it.confidence }
        for (idea in candidates) {
            if (slotsLeft() < 1) break
            if (busyUnderlying(idea.index)) continue
            runCatching { arm(idea, null, null, "auto", clock.now()) }.onFailure { note("Auto-trade skipped ${idea.index}: ${it.message}") }
        }
        save()
    }

    /** Polled every few seconds: waiting entries, stop fills, targets, trailing, profit goal, expiry and EOD. */
    suspend fun manage() = mutex.withLock {
        rollDay()
        if (!clock.isOpen()) return
        val now = clock.now()
        state.pendings.toList().forEach { managePending(it, now) }
        if (state.opens.isEmpty()) { save(); return }

        val book = orders.broker.orders()
        val prices = openPrices()
        val goal = settings.current.dailyProfitGoal
        val unrealised = state.opens.sumOf { t -> prices[t.key]?.let { (it - t.entryPrice) * t.quantity } ?: 0.0 }
        if (goal > 0 && state.realizedPnl + unrealised >= goal) {
            state.opens.toList().forEach { exitNow(it, "Daily profit goal reached", now) }
            save(); return
        }
        state.opens.toList().forEach { manageOpen(it, book, prices[it.key], now) }
        save()
    }

    private suspend fun manageOpen(start: AiTrade, book: List<Order>, ltp: Double?, now: ZonedDateTime) {
        var t = start
        val stopOrder = t.stopOrderId?.let { id -> book.firstOrNull { it.orderId == id } }
        when (stopOrder?.status) {
            "COMPLETE" -> return close(t, stopOrder.averagePrice.takeIf { it > 0 } ?: t.stopPrice, "Stop-loss", now)
            "REJECTED", "CANCELLED" -> return exitNow(t, "Stop order ${stopOrder.status.lowercase()}; exiting to stay protected", now)
        }
        ltp ?: return
        if (ltp < t.targetPrice && ltp >= t.stopPrice) {
            trail(t, ltp)?.let { t = it; replaceOpen(it) }
            if (state.opens.none { it.entryOrderId == t.entryOrderId }) return // trailing hit a problem and exited
        }
        when {
            ltp >= t.targetPrice -> exitNow(t, "Target %.2fx".format(t.targetPrice / t.entryPrice), now)
            // Broker stop is a limit order; if price gapped through it, get out at market.
            ltp < t.stopPrice -> exitNow(t, "Stop-loss (market backup)", now)
            clock.isAfter(riskCfg.intradaySquareOff, now) -> exitNow(t, "EOD square-off", now)
            now.isAfter(ZonedDateTime.parse(t.expiresAt)) -> exitNow(t, "Idea horizon expired", now)
        }
    }

    /**
     * Ratchets the stop up once the trade is [TradeSettings.trailStartPct] in profit: first to break-even, then
     * [TradeSettings.trailPct] below the peak premium. Replaces the broker SL order; returns the updated trade
     * when anything changed, null otherwise. The stop never moves down.
     */
    private suspend fun trail(t: AiTrade, ltp: Double): AiTrade? {
        val s = settings.current
        val peak = maxOf(t.peakPrice, ltp, t.entryPrice)
        val withPeak = if (peak > t.peakPrice) t.copy(peakPrice = peak) else null
        if (s.trailStartPct <= 0 || peak < t.entryPrice * (1 + s.trailStartPct / 100)) return withPeak
        val wanted = OptionPlanner.tickDown(maxOf(t.entryPrice, peak * (1 - s.trailPct / 100)))
        // Only move in steps of at least 1% of entry, to avoid churning orders on every tick; never at/above the market.
        if (wanted < t.stopPrice + t.entryPrice * 0.01 || wanted >= ltp) return withPeak

        t.stopOrderId?.let { id -> runCatching { orders.broker.cancelOrder(id) } }
        val book = orders.broker.orders()
        book.firstOrNull { it.orderId == t.stopOrderId && it.status == "COMPLETE" }?.let {
            close(t, it.averagePrice, "Stop-loss", clock.now()); return null
        }
        val placed = orders.place(
            OrderRequest(t.exchange, t.symbol, Side.SELL, t.quantity, Product.MIS, OrderType.SL,
                price = OptionPlanner.tickDown(wanted * (1 - SL_LIMIT_CUSHION)).coerceAtLeast(0.05), triggerPrice = wanted, tag = "ai-trail"),
            "ai:${t.ideaId}:trail",
        )
        if (!placed.placed) {
            exitNow(t, "Could not move trailing stop (${placed.reason}); exiting to protect profit", clock.now())
            return null
        }
        note("TRAIL ${t.symbol}: stop %.2f -> %.2f (peak %.2f, locks ₹%,.0f)".format(t.stopPrice, wanted, peak, (wanted - t.entryPrice) * t.quantity))
        return t.copy(stopPrice = wanted, stopOrderId = placed.orderId, peakPrice = peak)
    }

    /** Exits one open trade (by idea id), or all of them when [ideaId] is null. */
    suspend fun closeNow(ideaId: String? = null, reason: String = "Closed by user") = mutex.withLock {
        state.opens.filter { ideaId == null || it.ideaId == ideaId }.forEach { exitNow(it, reason, clock.now()) }
        save()
    }

    // ---------------------------------------------------------------- entry

    private suspend fun arm(idea: TradeIdea, lots: Int?, targetMultiple: Double?, source: String, now: ZonedDateTime): String {
        if (slotsLeft() < 1) error("Already ${settings.current.maxOpenTrades} AI trades open or waiting (the max). Close one or raise the limit in Trade settings.")
        if (busyUnderlying(idea.index)) error("There is already a ${idea.index} trade open or waiting")
        if (idea.action != "LONG" && idea.action != "SHORT") error("This idea is NO TRADE")
        if (idea.status != "OPEN") error("This idea is already resolved (${idea.status})")
        if (!idea.levelsOk) error("This idea failed the rule checks: ${idea.note}")
        // Paper test ideas are exempt so the pipeline can be exercised; everything else must clear the bar.
        if (isHighRisk(idea) && source != HIGH_RISK) {
            error("Confidence ${idea.confidence}% is below the ${cfg.minConfidence}% minimum; use the High-risk section to take it")
        }
        if (!clock.isOpen(now)) error("Market is closed")
        if (clock.isAfter(LAST_ENTRY, now)) error("No new trades after $LAST_ENTRY")
        if (profitGoalReached()) error("Daily profit goal reached; trading is done for today")
        if (lossBudgetLeft() < 1) error("Daily loss limit reached; no new trades today")
        if (riskBudgetForNew() < 1) error("Open trades already use the rest of today's loss limit (₹%,.0f at risk)".format(openRisk()))
        val ideaEnd = ZonedDateTime.parse(idea.createdAt).plusMinutes(idea.horizonMinutes.toLong())
        val expires = minOf(ideaEnd, now.with(LAST_ENTRY))
        if (!now.isBefore(expires)) error("This idea's time window has passed")

        val pending = PendingTrade(
            ideaId = idea.id, index = idea.index, action = idea.action, quoteKey = idea.quoteKey,
            indexEntry = idea.entry, indexStop = idea.stopLoss, armedAt = now.toString(), expiresAt = expires.toString(),
            lots = lots, targetMultiple = targetMultiple, source = source,
        )
        val spot = market.ltp(idea.quoteKey)
        if (nearEntry(spot, idea.entry)) {
            val trade = enter(pending, idea, now)
            return trade?.let { "Bought ${it.symbol} x${it.quantity} @ %.2f".format(it.entryPrice) }
                ?: "Entry not placed: ${state.log.lastOrNull().orEmpty()}"
        }
        state.pendings += pending
        note("WAITING ${idea.index} ${idea.action}: buy when price is near %.1f (now %.1f), until %s".format(idea.entry, spot, expires.toLocalTime().withNano(0)))
        return "Waiting for ${idea.index} to reach %.1f (now %.1f)".format(idea.entry, spot)
    }

    private suspend fun managePending(p: PendingTrade, now: ZonedDateTime) {
        val spot = runCatching { market.ltp(p.quoteKey) }.getOrNull() ?: return
        val long = p.action == "LONG"
        when {
            long && spot <= p.indexStop || !long && spot >= p.indexStop -> { state.pendings.remove(p); note("Cancelled waiting ${p.index} ${p.action}: price hit the stop level before entry") }
            !now.isBefore(ZonedDateTime.parse(p.expiresAt)) -> { state.pendings.remove(p); note("Cancelled waiting ${p.index} ${p.action}: entry not reached in time") }
            nearEntry(spot, p.indexEntry) -> {
                state.pendings.remove(p)
                val idea = tracker.get(p.ideaId) ?: return note("Idea ${p.ideaId} vanished; entry skipped")
                enter(p, idea, now)
            }
        }
    }

    private suspend fun enter(p: PendingTrade, idea: TradeIdea, now: ZonedDateTime): AiTrade? {
        val maxLoss = if (p.source == HIGH_RISK) settings.current.highRiskMaxLoss else null
        val plan = runCatching { planner.plan(idea, riskBudgetForNew(), p.targetMultiple, maxLoss) }
            .getOrElse { note("No option plan for ${idea.index}: ${it.message}"); return null }
        if (!plan.executable) { note("Skipped ${plan.symbol}: ${plan.reason}"); return null }
        val lots = min(p.lots ?: plan.lots, plan.lots)
        val qty = lots * plan.lotSize

        val entry = orders.place(OrderRequest("NFO", plan.symbol, Side.BUY, qty, Product.MIS, tag = "ai-${idea.id}"), "ai:${idea.id}")
        if (!entry.placed) { note("Entry rejected for ${plan.symbol}: ${entry.reason}"); return null }

        val fill = orders.broker.orders().firstOrNull { it.orderId == entry.orderId }?.averagePrice?.takeIf { it > 0 } ?: plan.premium
        // Keep the planned ratios relative to the actual fill.
        val stop = OptionPlanner.tickDown(fill * plan.stopPremium / plan.premium)
        val target = OptionPlanner.tickUp(fill * plan.targetPremium / plan.premium)
        // Exchanges don't allow SL-M on options: use SL with a limit cushion below the trigger.
        val stopOrder = orders.place(
            OrderRequest("NFO", plan.symbol, Side.SELL, qty, Product.MIS, OrderType.SL,
                price = OptionPlanner.tickDown(stop * (1 - SL_LIMIT_CUSHION)).coerceAtLeast(0.05), triggerPrice = stop, tag = "ai-sl"),
            "ai:${idea.id}:stop",
        )
        val trade = AiTrade(
            ideaId = idea.id, index = idea.index, exchange = "NFO", symbol = plan.symbol, optionType = plan.optionType,
            strike = plan.strike, expiry = plan.expiry, side = Side.BUY, quantity = qty, lots = lots,
            entryPrice = fill, stopPrice = stop, targetPrice = target, entryOrderId = entry.orderId!!,
            stopOrderId = stopOrder.orderId, peakPrice = fill, initialStop = stop, openedAt = now.toString(), expiresAt = p.expiresAt.let {
                maxOf(ZonedDateTime.parse(it), ZonedDateTime.parse(idea.createdAt).plusMinutes(idea.horizonMinutes.toLong())).toString()
            }, source = p.source,
        )
        state.opens += trade
        if (p.source == "auto") tracker.markAutoTraded(idea.id)
        note("BUY ${plan.symbol} x$qty ($lots lot) @ %.2f = ₹%,.0f, stop %.2f (risk ₹%,.0f), target %.2f (%.2fx) [${p.source}, idea ${idea.id} ${idea.confidence}%%]"
            .format(fill, trade.investment, stop, trade.riskRupees, target, target / fill))
        if (!stopOrder.placed) exitNow(trade, "Could not place stop-loss (${stopOrder.reason}); exiting", now)
        return trade
    }

    // ---------------------------------------------------------------- exit

    private suspend fun exitNow(t: AiTrade, reason: String, now: ZonedDateTime) {
        t.stopOrderId?.let { id -> runCatching { orders.broker.cancelOrder(id) } }
        val book = orders.broker.orders()
        // The stop may have filled between our check and the cancel; if so the position is already flat.
        book.firstOrNull { it.orderId == t.stopOrderId && it.status == "COMPLETE" }?.let { return close(t, it.averagePrice, "Stop-loss", now) }

        // Never sell more than we hold: a SELL beyond the position would open a short option.
        val held = orders.broker.positions().firstOrNull { it.key == t.key }?.quantity ?: 0
        if (held <= 0) {
            val px = runCatching { market.ltp(t.key) }.getOrDefault(t.entryPrice)
            return close(t, px, "$reason (position already closed elsewhere)", now)
        }
        val exit = orders.place(OrderRequest(t.exchange, t.symbol, Side.SELL, min(held, t.quantity), Product.MIS, tag = "ai-exit"), "ai:${t.ideaId}:exit")
        if (!exit.placed) {
            note("EXIT FAILED for ${t.symbol}: ${exit.reason}. Check positions manually!")
            log.error("AI trade exit failed for {}: {}", t.symbol, exit.reason)
            return
        }
        val px = orders.broker.orders().firstOrNull { it.orderId == exit.orderId }?.averagePrice?.takeIf { it > 0 }
            ?: runCatching { market.ltp(t.key) }.getOrDefault(t.entryPrice)
        close(t, px, reason, now)
    }

    private fun close(t: AiTrade, exitPrice: Double, reason: String, now: ZonedDateTime) {
        val pnl = (exitPrice - t.entryPrice) * t.quantity
        val closed = t.copy(status = "CLOSED", exitPrice = exitPrice, exitReason = reason, closedAt = now.toString(), pnl = pnl)
        state.closed += closed
        state.realizedPnl += pnl
        state.opens.removeAll { it.entryOrderId == t.entryOrderId }
        note("SELL ${t.symbol} @ %.2f (%s) P&L ₹%,.2f (%.2fx), day ₹%,.2f".format(exitPrice, reason, pnl, exitPrice / t.entryPrice, state.realizedPnl))
        if (lossBudgetLeft() < 1) note("Daily loss limit reached; no new trades until tomorrow")
        if (profitGoalReached()) note("Daily profit goal reached; done for today")
    }

    private fun replaceOpen(t: AiTrade) {
        val i = state.opens.indexOfFirst { it.entryOrderId == t.entryOrderId }
        if (i >= 0) state.opens[i] = t
    }

    private suspend fun openPrices(): Map<String, Double> {
        val keys = state.opens.map { it.key }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return runCatching { market.ltp(keys).mapValues { it.value.lastPrice } }.getOrDefault(emptyMap())
    }

    private fun lossBudgetLeft() = (settings.current.maxDailyLoss + min(state.realizedPnl, 0.0)).coerceAtLeast(0.0)
    /** What the open trades lose if every stop is hit; a stop trailed above entry risks nothing. */
    private fun openRisk() = state.opens.sumOf { ((it.entryPrice - it.stopPrice) * it.quantity).coerceAtLeast(0.0) }
    /** Loss a new trade may risk: today's remaining loss limit minus what open trades could still lose. */
    private fun riskBudgetForNew() = (lossBudgetLeft() - openRisk()).coerceAtLeast(0.0)
    private fun slotsLeft() = (settings.current.maxOpenTrades - state.opens.size - state.pendings.size).coerceAtLeast(0)
    private fun busyUnderlying(index: String) = state.opens.any { it.index == index } || state.pendings.any { it.index == index }
    private fun profitGoalReached() = settings.current.dailyProfitGoal.let { it > 0 && state.realizedPnl >= it }
    private fun nearEntry(spot: Double, entry: Double) = abs(spot - entry) / entry <= ENTRY_TOLERANCE

    private fun rollDay() {
        val today = LocalDate.now(IST).toString()
        if (state.day == today) return
        state.day = today
        state.realizedPnl = 0.0
        state.pendings.clear()
        state.closed.clear()
        state.log.clear()
        save()
    }

    private fun note(msg: String) {
        log.info("[ai-trader] {}", msg)
        state.log += "${clock.now().toLocalTime().withNano(0)} $msg"
        while (state.log.size > 200) state.log.removeAt(0)
    }

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, TallyJson.encodeToString(AiTraderState.serializer(), state))
        }.onFailure { log.error("Saving AI trader state failed", it) }
    }

    companion object {
        private val LAST_ENTRY: LocalTime = LocalTime.of(14, 45)
        /** Trade source for manual trades taken from the High-risk section (never auto-traded). */
        const val HIGH_RISK = "high-risk"
        /** Index within 0.1% of the idea's entry counts as "at entry". */
        private const val ENTRY_TOLERANCE = 0.001
        /** SL limit price this far below the trigger so a fast-falling option still fills. */
        private const val SL_LIMIT_CUSHION = 0.10
    }
}
