package com.tally.backtest

import com.tally.kite.Candle
import com.tally.kite.KiteClient
import com.tally.strategy.Signal
import com.tally.strategy.Strategy
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class BacktestParams(
    val longOnly: Boolean = false,
    /** Flatten at the last bar of each day (intraday intervals). */
    val intraday: Boolean = true,
    val stopLossPct: Double = 0.0,
    val targetPct: Double = 0.0,
    /** Round-trip-ish cost charged per side, in basis points (brokerage, STT, exchange fees, slippage). */
    val costBpsPerSide: Double = 5.0,
)

@Serializable
data class BacktestTrade(
    val side: String,
    val entryTime: String,
    val entryPrice: Double,
    val exitTime: String,
    val exitPrice: Double,
    val returnPct: Double,
    val exitReason: String,
)

@Serializable
data class BacktestResult(
    val bars: Int,
    val trades: Int,
    val winRatePct: Double,
    val totalReturnPct: Double,
    val avgWinPct: Double,
    val avgLossPct: Double,
    /** Null when there were no losing trades. */
    val profitFactor: Double?,
    val maxDrawdownPct: Double,
    val tradeList: List<BacktestTrade>,
)

/**
 * Bar-by-bar simulation on the underlying. Signals are evaluated on a bar's close and filled at the
 * next bar's open, so there is no look-ahead. Stops/targets are checked against each bar's high/low
 * (stop wins when both are touched in the same bar - the conservative assumption).
 *
 * Note: for INDEX_OPTIONS instances this measures the *direction* edge on the index, not option P&L;
 * premium decay and IV changes are not modelled.
 */
object Backtester {
    fun run(strategy: Strategy, candles: List<Candle>, p: BacktestParams): BacktestResult {
        val trades = mutableListOf<BacktestTrade>()
        var pos = 0 // +1 long, -1 short
        var entry = 0.0
        var entryTime = ""
        val cost = p.costBpsPerSide / 10_000

        fun close(price: Double, time: String, reason: String) {
            val gross = (price - entry) / entry * pos
            trades += BacktestTrade(
                if (pos > 0) "LONG" else "SHORT", entryTime, entry, time, price, (gross - 2 * cost) * 100, reason,
            )
            pos = 0
        }

        val days = candles.map { KiteClient.parseCandleTime(it.time).toLocalDate() }
        var pending: Signal? = null

        for (i in candles.indices) {
            val bar = candles[i]
            // 1. Fill the signal from the previous bar's close at this bar's open.
            pending?.let { sig ->
                val dir = if (sig == Signal.LONG) 1 else -1
                if (pos == -dir) close(bar.open, bar.time, "Opposite signal")
                if (pos == 0 && !(p.longOnly && dir < 0)) {
                    pos = dir; entry = bar.open; entryTime = bar.time
                }
                pending = null
            }
            // 2. Stops and targets intrabar.
            if (pos != 0) {
                val stop = if (p.stopLossPct > 0) entry * (1 - pos * p.stopLossPct / 100) else null
                val target = if (p.targetPct > 0) entry * (1 + pos * p.targetPct / 100) else null
                val stopHit = stop != null && (if (pos > 0) bar.low <= stop else bar.high >= stop)
                val targetHit = target != null && (if (pos > 0) bar.high >= target else bar.low <= target)
                when {
                    stopHit -> close(stop!!, bar.time, "Stop-loss")
                    targetHit -> close(target!!, bar.time, "Target")
                }
            }
            // 3. EOD flatten.
            val lastOfDay = i == candles.lastIndex || days[i + 1] != days[i]
            if (p.intraday && lastOfDay) {
                if (pos != 0) close(bar.close, bar.time, "EOD")
                continue // no carrying a signal into the next session's open
            }
            // 4. Evaluate on the close of this bar.
            if (i + 1 >= strategy.minBars) {
                val sig = strategy.evaluate(candles.subList(0, i + 1))
                if (sig != Signal.HOLD) pending = sig
            }
        }
        if (pos != 0) close(candles.last().close, candles.last().time, "End of data")

        return summarise(candles.size, trades)
    }

    private fun summarise(bars: Int, trades: List<BacktestTrade>): BacktestResult {
        var equity = 1.0
        var peak = 1.0
        var maxDd = 0.0
        for (t in trades) {
            equity *= 1 + t.returnPct / 100
            peak = max(peak, equity)
            maxDd = max(maxDd, (peak - equity) / peak)
        }
        val wins = trades.filter { it.returnPct > 0 }
        val losses = trades.filter { it.returnPct <= 0 }
        val grossWin = wins.sumOf { it.returnPct }
        val grossLoss = -losses.sumOf { it.returnPct }
        return BacktestResult(
            bars = bars,
            trades = trades.size,
            winRatePct = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
            totalReturnPct = (equity - 1) * 100,
            avgWinPct = if (wins.isEmpty()) 0.0 else grossWin / wins.size,
            avgLossPct = if (losses.isEmpty()) 0.0 else -grossLoss / losses.size,
            profitFactor = if (grossLoss == 0.0) null else grossWin / grossLoss,
            maxDrawdownPct = maxDd * 100,
            tradeList = trades,
        )
    }
}
