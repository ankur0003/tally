package com.tally.util

import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

val TallyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

/**
 * NSE cash/F&O session clock. Exchange holidays are not modelled here; on a holiday Kite simply
 * rejects orders and returns no new candles, so the runner stays idle.
 */
class MarketClock(private val clock: Clock = Clock.system(IST)) {
    val open: LocalTime = LocalTime.of(9, 15)
    val close: LocalTime = LocalTime.of(15, 30)

    fun now(): ZonedDateTime = ZonedDateTime.now(clock).withZoneSameInstant(IST)

    fun isTradingDay(at: ZonedDateTime = now()) =
        at.dayOfWeek != DayOfWeek.SATURDAY && at.dayOfWeek != DayOfWeek.SUNDAY

    fun isOpen(at: ZonedDateTime = now()): Boolean {
        val t = at.toLocalTime()
        return isTradingDay(at) && !t.isBefore(open) && t.isBefore(close)
    }

    fun isAfter(time: LocalTime, at: ZonedDateTime = now()) = !at.toLocalTime().isBefore(time)
}
