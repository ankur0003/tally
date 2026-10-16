package com.tally.risk

import com.tally.config.RiskConfig
import com.tally.kite.OrderRequest
import com.tally.kite.Position
import com.tally.kite.Product
import com.tally.kite.Side
import com.tally.util.MarketClock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime
import kotlin.math.abs

@Serializable
data class RiskDecision(val approved: Boolean, val reason: String, val isExit: Boolean)

@Serializable
data class RiskStatus(
    val killSwitch: Boolean,
    val killReason: String?,
    val dayPnl: Double,
    val maxDailyLoss: Double,
    val openPositions: Int,
    val maxOpenPositions: Int,
    val ordersToday: Int,
    val maxOrdersPerDay: Int,
    val marketOpen: Boolean,
)

/**
 * Pre-trade gate. Every order passes through [check]. Risk-reducing exits are always allowed while the
 * market is open, even with the kill switch on, so the bot can always get flat.
 */
class RiskManager(
    private val cfg: RiskConfig,
    private val clock: MarketClock,
) {
    private val log = LoggerFactory.getLogger(RiskManager::class.java)

    @Volatile var killSwitch: Boolean = cfg.killSwitch
        private set
    @Volatile var killReason: String? = if (cfg.killSwitch) "Enabled in config" else null
        private set

    fun setKillSwitch(on: Boolean, reason: String) {
        if (on != killSwitch) log.warn("Kill switch {} - {}", if (on) "ENGAGED" else "released", reason)
        killSwitch = on
        killReason = if (on) reason else null
    }

    fun dayPnl(positions: List<Position>) = positions.sumOf { it.pnl }

    fun status(positions: List<Position>, ordersToday: Int) = RiskStatus(
        killSwitch = killSwitch,
        killReason = killReason,
        dayPnl = dayPnl(positions),
        maxDailyLoss = cfg.maxDailyLoss,
        openPositions = positions.count { it.quantity != 0 },
        maxOpenPositions = cfg.maxOpenPositions,
        ordersToday = ordersToday,
        maxOrdersPerDay = cfg.maxOrdersPerDay,
        marketOpen = clock.isOpen(),
    )

    fun check(
        req: OrderRequest,
        referencePrice: Double,
        positions: List<Position>,
        ordersToday: Int,
        now: ZonedDateTime = clock.now(),
    ): RiskDecision {
        val existing = positions.firstOrNull { it.key == req.key && it.quantity != 0 }
        val isExit = existing != null &&
            (existing.quantity > 0 && req.transactionType == Side.SELL || existing.quantity < 0 && req.transactionType == Side.BUY) &&
            req.quantity <= abs(existing.quantity)

        fun reject(reason: String) = RiskDecision(false, reason, isExit).also { log.warn("RISK REJECT {} {}: {}", req.transactionType, req.key, reason) }

        if (req.quantity <= 0) return reject("Quantity must be positive")
        if (req.exchange !in cfg.allowedExchanges) return reject("Exchange ${req.exchange} not allowed")
        if (!clock.isOpen(now)) return reject("Market closed")
        if (isExit) return RiskDecision(true, "Exit order", true)

        if (killSwitch) return reject("Kill switch engaged: $killReason")

        val pnl = dayPnl(positions)
        // Blocks entries only while the day's P&L is past the limit; nothing latches, so the next day starts clean.
        if (pnl <= -cfg.maxDailyLoss) return reject("Daily loss limit reached (P&L %.0f)".format(pnl))
        val intraday = req.product == Product.MIS || req.exchange == "NFO"
        if (intraday && clock.isAfter(cfg.intradayEntryCutoff, now)) {
            return reject("No new intraday entries after ${cfg.intradayEntryCutoff}")
        }
        val value = referencePrice * req.quantity
        if (value > cfg.maxOrderValue) {
            return reject("Order value %.0f exceeds max %.0f".format(value, cfg.maxOrderValue))
        }
        if (ordersToday >= cfg.maxOrdersPerDay) return reject("Max orders per day (${cfg.maxOrdersPerDay}) reached")
        val open = positions.count { it.quantity != 0 }
        if (existing == null && open >= cfg.maxOpenPositions) {
            return reject("Max open positions (${cfg.maxOpenPositions}) reached")
        }
        return RiskDecision(true, "OK", false)
    }
}
