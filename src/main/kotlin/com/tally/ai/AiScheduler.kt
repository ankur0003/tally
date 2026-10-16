package com.tally.ai

import com.tally.config.AiConfig
import com.tally.util.MarketClock
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Background loops:
 *  - every 15s: manage the open AI trade (stop / target / expiry / EOD)
 *  - every 60s: score open ideas; run the news brief and idea generation when due and within budget
 *  - after each idea run: the AI trader's fund-manager decision, when it is switched on
 * Briefs run 08:30-15:30 IST on weekdays; ideas only while the market is open, 09:25-14:45.
 */
class AiScheduler(
    private val cfg: AiConfig,
    private val api: ClaudeApi,
    private val intel: MarketIntel,
    private val tracker: IdeaTracker,
    private val trader: AiTrader,
    private val desk: AiTraderDesk,
    private val clock: MarketClock,
    private val kiteReady: () -> Boolean,
) {
    private val log = LoggerFactory.getLogger(AiScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var lastError: String? = null
        private set
    @Volatile private var lastIdeasAt: ZonedDateTime? = null
    @Volatile private var lastStockIdeasAt: ZonedDateTime? = null

    fun start() {
        scope.launch {
            while (isActive) {
                guard("trade manager") { if (kiteReady()) trader.manage() }
                delay(15_000)
            }
        }
        scope.launch {
            delay(20_000) // let the app finish starting
            while (isActive) {
                guard("idea scoring") { if (kiteReady()) tracker.evaluate() }
                if (cfg.scheduler && api.configured) {
                    guard("brief") { if (briefDue()) intel.refreshBrief(kiteReady()) }
                    guard("ideas") { if (ideasDue()) runIdeas() }
                    guard("stock ideas") { if (stockIdeasDue()) runStockIdeas() }
                }
                delay(60_000)
            }
        }
    }

    fun shutdown() = scope.cancel()

    fun overBudget() = api.spendToday().usd >= cfg.dailyBudgetUsd

    suspend fun runIdeas(): IdeaRun {
        lastIdeasAt = clock.now()
        val run = intel.generateIdeas()
        trader.onIdeas(run.ideaIds)
        guard("AI trader") { desk.decide() }
        return run
    }

    suspend fun runStockIdeas(): IdeaRun {
        lastStockIdeasAt = clock.now()
        val run = intel.generateStockIdeas()
        trader.onIdeas(run.ideaIds)
        guard("AI trader") { desk.decide() }
        return run
    }

    private fun stockIdeasDue(): Boolean {
        if (cfg.stocks.isEmpty()) return false
        val now = clock.now()
        val t = now.toLocalTime()
        if (!kiteReady() || !clock.isOpen(now) || t.isBefore(LocalTime.of(9, 30)) || t.isAfter(LocalTime.of(14, 30))) return false
        if (overBudget()) return false
        val last = lastStockIdeasAt ?: intel.latestStockRun?.let { ZonedDateTime.parse(it.at) } ?: return true
        return Duration.between(last, now).toMinutes() >= cfg.stockIdeaMinutes
    }

    private fun briefDue(): Boolean {
        val now = clock.now()
        val t = now.toLocalTime()
        if (!clock.isTradingDay(now) || t.isBefore(LocalTime.of(8, 30)) || !t.isBefore(LocalTime.of(15, 30))) return false
        if (overBudget()) return false
        val last = intel.latestBrief?.let { ZonedDateTime.parse(it.at) } ?: return true
        return Duration.between(last, now).toMinutes() >= cfg.briefMinutes
    }

    private fun ideasDue(): Boolean {
        val now = clock.now()
        val t = now.toLocalTime()
        if (!kiteReady() || !clock.isOpen(now) || t.isBefore(LocalTime.of(9, 25)) || t.isAfter(LocalTime.of(14, 45))) return false
        if (overBudget()) return false
        val last = lastIdeasAt ?: intel.latestRun?.let { ZonedDateTime.parse(it.at) } ?: return true
        return Duration.between(last, now).toMinutes() >= cfg.ideaMinutes
    }

    private suspend fun guard(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "$name: ${e.message}"
            log.warn("AI {} failed: {}", name, e.message)
        }
    }
}
