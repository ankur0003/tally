package com.tally

import com.tally.ai.OptionPlanner
import com.tally.ai.TradeSettings
import kotlin.test.*

class OptionPlannerTest {

    @Test
    fun `index levels map to premium with half delta and the stop is capped`() {
        // NIFTY idea: 40 pts risk, 80 pts reward on a 100 rupee ATM option.
        val l = OptionPlanner.levels(premium = 100.0, indexRisk = 40.0, indexReward = 80.0, maxStopPct = 35.0, multiple = null)
        assertEquals(80.0, l.stop)
        assertEquals(140.0, l.target)

        // 100 pts risk would mean a 50% premium drop; the 35% cap exits earlier.
        val capped = OptionPlanner.levels(100.0, 100.0, 200.0, 35.0, null)
        assertEquals(65.0, capped.stop)
    }

    @Test
    fun `a fixed multiple overrides the AI target`() {
        val l = OptionPlanner.levels(premium = 120.0, indexRisk = 30.0, indexReward = 60.0, maxStopPct = 35.0, multiple = 1.5)
        assertEquals(180.0, l.target)
    }

    @Test
    fun `lots fit capital, max loss and the account order cap`() {
        // 1 lot of 65 @ 100 = 6,500 in, risk (100-80)*65 = 1,300.
        assertEquals(2, OptionPlanner.size(100.0, 80.0, 65, capital = 15_000.0, riskBudget = 3_000.0, maxOrderValue = 50_000.0).lots)
        assertEquals(1, OptionPlanner.size(100.0, 80.0, 65, capital = 15_000.0, riskBudget = 1_500.0, maxOrderValue = 50_000.0).lots)

        val poor = OptionPlanner.size(100.0, 80.0, 65, capital = 1_000.0, riskBudget = 250.0, maxOrderValue = 12_000.0)
        assertEquals(0, poor.lots)
        assertTrue(poor.reason.contains("capital"), poor.reason)

        val riskTooLow = OptionPlanner.size(100.0, 80.0, 65, capital = 20_000.0, riskBudget = 250.0, maxOrderValue = 50_000.0)
        assertEquals(0, riskTooLow.lots)
        assertTrue(riskTooLow.reason.contains("max loss"), riskTooLow.reason)
    }

    @Test
    fun `forecast refuses to promise growth from a losing expectation`() {
        val coinFlip = OptionPlanner.forecast(p = 0.4, source = "x", investment = 6_500.0, gain = 2_600.0, loss = 1_300.0, capital = 10_000.0)
        // 0.4*2600 - 0.6*1300 - 60 = 200 per trade
        assertEquals(200.0, coinFlip.expectedPerTrade)
        assertEquals(9_100.0, coinFlip.valueAtTarget)
        assertNotNull(coinFlip.tradesTo2x)

        val losing = OptionPlanner.forecast(p = 0.3, source = "x", investment = 6_500.0, gain = 2_600.0, loss = 1_300.0, capital = 10_000.0)
        assertTrue(losing.expectedPerTrade < 0)
        assertNull(losing.tradesTo1_5x)
        assertNull(losing.tradesTo2x)
    }

    @Test
    fun `quote spread comes from the top of the order book`() {
        val q = com.tally.kite.Quote(lastPrice = 100.0, depth = com.tally.kite.Depth(
            buy = listOf(com.tally.kite.DepthLevel(99.0, 500)), sell = listOf(com.tally.kite.DepthLevel(101.0, 500))))
        assertEquals(2.0, q.spreadPct!!, 1e-9)
        assertNull(com.tally.kite.Quote(lastPrice = 100.0).spreadPct)
    }

    @Test
    fun `settings validation rejects unsafe values`() {
        val ok = TradeSettings(capital = 10_000.0, maxLossPerTrade = 1_000.0, maxDailyLoss = 2_000.0)
        assertEquals(ok, ok.validated())
        assertFailsWith<IllegalArgumentException> { ok.copy(maxDailyLoss = 500.0).validated() }
        assertFailsWith<IllegalArgumentException> { ok.copy(targetMultiple = 10.0).validated() }
        assertFailsWith<IllegalArgumentException> { ok.copy(targetMode = "YOLO").validated() }
        assertFailsWith<IllegalArgumentException> { ok.copy(trailPct = 1.0).validated() }
        assertEquals(0.0, ok.copy(trailStartPct = 0.0).validated().trailStartPct)
        assertFailsWith<IllegalArgumentException> { ok.copy(highRiskMaxLoss = 5_000.0).validated() }
    }
}
