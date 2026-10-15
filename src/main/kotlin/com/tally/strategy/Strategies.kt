package com.tally.strategy

import com.tally.kite.Candle
import com.tally.kite.KiteClient
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
enum class Signal { LONG, SHORT, HOLD }

/**
 * A strategy looks only at *closed* candles and emits an entry event on the last one. Exits are owned
 * by the runner (opposite signal, stop-loss, target, EOD), which keeps strategies small and testable.
 */
interface Strategy {
    val name: String
    val minBars: Int
    fun evaluate(candles: List<Candle>): Signal
}

/** Fast/slow EMA crossover, confirmed by RSI on the side of 50. */
class EmaCrossover(
    private val fast: Int = 9,
    private val slow: Int = 21,
    private val rsiPeriod: Int = 14,
) : Strategy {
    override val name = "ema_crossover"
    override val minBars = slow + 2

    override fun evaluate(candles: List<Candle>): Signal {
        if (candles.size < minBars) return Signal.HOLD
        val close = candles.map { it.close }
        val f = Indicators.ema(close, fast)
        val s = Indicators.ema(close, slow)
        val rsi = Indicators.rsi(close, rsiPeriod)
        val i = close.lastIndex
        val crossedUp = f[i - 1] <= s[i - 1] && f[i] > s[i]
        val crossedDown = f[i - 1] >= s[i - 1] && f[i] < s[i]
        return when {
            crossedUp && rsi[i] > 50 -> Signal.LONG
            crossedDown && rsi[i] < 50 -> Signal.SHORT
            else -> Signal.HOLD
        }
    }
}

/**
 * Opening-range breakout on intraday candles. The range is the high/low of the first [rangeMinutes] of
 * today's session; a close breaking out of it (that the previous close had not) is the entry.
 */
class OpeningRangeBreakout(
    private val rangeMinutes: Long = 15,
    private val lastEntry: LocalTime = LocalTime.of(14, 30),
) : Strategy {
    override val name = "opening_range_breakout"
    override val minBars = 2

    override fun evaluate(candles: List<Candle>): Signal {
        if (candles.size < minBars) return Signal.HOLD
        val last = candles.last()
        val lastTime = KiteClient.parseCandleTime(last.time)
        if (!lastTime.toLocalTime().isBefore(lastEntry)) return Signal.HOLD

        val sessionOpen = lastTime.toLocalDate().atTime(9, 15)
        val rangeEnd = sessionOpen.plusMinutes(rangeMinutes)
        val today = candles.filter {
            val t = KiteClient.parseCandleTime(it.time).toLocalDateTime()
            t.toLocalDate() == sessionOpen.toLocalDate()
        }
        val range = today.filter { KiteClient.parseCandleTime(it.time).toLocalDateTime().isBefore(rangeEnd) }
        val after = today.filter { !KiteClient.parseCandleTime(it.time).toLocalDateTime().isBefore(rangeEnd) }
        if (range.isEmpty() || after.isEmpty()) return Signal.HOLD

        val hi = range.maxOf { it.high }
        val lo = range.minOf { it.low }
        val prevClose = if (after.size >= 2) after[after.size - 2].close else range.last().close
        return when {
            last.close > hi && prevClose <= hi -> Signal.LONG
            last.close < lo && prevClose >= lo -> Signal.SHORT
            else -> Signal.HOLD
        }
    }
}

object StrategyRegistry {
    fun create(name: String): Strategy = when (name) {
        "ema_crossover" -> EmaCrossover()
        "opening_range_breakout" -> OpeningRangeBreakout()
        else -> throw IllegalArgumentException("Unknown strategy '$name'. Available: $names")
    }

    val names = listOf("ema_crossover", "opening_range_breakout")
}
