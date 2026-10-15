package com.tally.strategy

import kotlin.math.abs
import kotlin.math.max

/** Pure indicator functions. Outputs align with inputs; entries before warm-up are NaN. */
object Indicators {
    fun ema(values: List<Double>, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size < period) return out
        val k = 2.0 / (period + 1)
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's RSI. */
    fun rsi(values: List<Double>, period: Int = 14): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period
        loss /= period
        out[period] = rsiOf(gain, loss)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            gain = (gain * (period - 1) + max(d, 0.0)) / period
            loss = (loss * (period - 1) + max(-d, 0.0)) / period
            out[i] = rsiOf(gain, loss)
        }
        return out
    }

    /** Wilder's ATR. */
    fun atr(high: List<Double>, low: List<Double>, close: List<Double>, period: Int = 14): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= period) return out
        val tr = DoubleArray(n) { i ->
            if (i == 0) high[0] - low[0]
            else maxOf(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
        }
        var prev = tr.slice(1..period).average()
        out[period] = prev
        for (i in period + 1 until n) {
            prev = (prev * (period - 1) + tr[i]) / period
            out[i] = prev
        }
        return out
    }

    private fun rsiOf(gain: Double, loss: Double) = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
}
