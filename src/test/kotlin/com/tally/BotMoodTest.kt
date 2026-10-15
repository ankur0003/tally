package com.tally

import com.tally.ai.BotMoodRules
import com.tally.ai.MoodInputs
import com.tally.util.IST
import java.time.ZonedDateTime
import kotlin.test.*

class BotMoodTest {
    // Thursday 15 Oct 2026, 11:30 IST
    private val midday = ZonedDateTime.of(2026, 10, 15, 11, 30, 0, 0, IST)
    private fun inputs(now: ZonedDateTime = midday) = MoodInputs(
        now = now, marketOpen = true, niftyChangePct = 0.3, vix = 13.0, vixChangePct = 1.0,
        aiLine = null, aiLineAt = null, openSymbol = null, openPnl = null,
        lossLimitHit = false, profitGoalHit = false, tradeableIdeas = 0, minConfidence = 80,
    )

    @Test
    fun `a VIX spike outranks a calm AI line`() {
        val m = BotMoodRules.mood(inputs().copy(vixChangePct = 7.0, aiLine = "All calm.", aiLineAt = midday.minusMinutes(2)))
        assertEquals("danger", m.tone)
        assertTrue(m.text.contains("shaking"))
    }

    @Test
    fun `fresh AI line is used and stale one is not`() {
        assertEquals("AI", BotMoodRules.mood(inputs().copy(aiLine = "Looks choppy, I'd wait.", aiLineAt = midday.minusMinutes(5))).source)
        assertEquals("rules", BotMoodRules.mood(inputs().copy(aiLine = "Old news.", aiLineAt = midday.minusMinutes(45))).source)
    }

    @Test
    fun `open trade and day limits come first`() {
        assertTrue(BotMoodRules.mood(inputs().copy(openSymbol = "NIFTY CE", openPnl = 500.0)).text.contains("up ₹500"))
        assertEquals("danger", BotMoodRules.mood(inputs().copy(lossLimitHit = true)).tone)
        assertEquals("good", BotMoodRules.mood(inputs().copy(profitGoalHit = true)).tone)
    }

    @Test
    fun `opening bell and closed market`() {
        assertTrue(BotMoodRules.mood(inputs(midday.withHour(9).withMinute(20))).text.contains("Opening-bell"))
        assertTrue(BotMoodRules.mood(inputs().copy(marketOpen = false, now = midday.withHour(16))).text.contains("closed"))
    }
}
