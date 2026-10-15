package com.tally.market

import com.tally.kite.Candle
import com.tally.kite.KiteClient
import com.tally.kite.Ltp
import com.tally.kite.Quote
import com.tally.util.IST
import java.time.LocalDateTime

class MarketDataService(
    private val kite: KiteClient,
    private val instruments: InstrumentService,
) {
    suspend fun quote(keys: Collection<String>): Map<String, Quote> = kite.quote(keys)

    suspend fun ltp(keys: Collection<String>): Map<String, Ltp> = kite.ltp(keys)

    suspend fun ltp(key: String): Double =
        ltp(listOf(key))[key]?.lastPrice ?: throw IllegalStateException("No LTP for $key")

    /**
     * Candles for the last [lookbackDays] calendar days. Kite caps a single request (60 days for minute
     * candles, 2000 for daily), so callers should keep lookback within those limits.
     */
    suspend fun candles(exchange: String, symbol: String, interval: String, lookbackDays: Long): List<Candle> {
        val token = instruments.require(exchange, symbol).instrumentToken
        val to = LocalDateTime.now(IST)
        return kite.historical(token, interval, to.minusDays(lookbackDays), to)
    }

    companion object {
        /** Sensible default history window per interval: enough bars for indicators, within Kite's limits. */
        fun defaultLookback(interval: String): Long = when (interval) {
            "minute", "3minute" -> 5
            "5minute", "10minute" -> 10
            "15minute", "30minute" -> 30
            "60minute" -> 60
            else -> 400
        }
    }
}
