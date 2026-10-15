package com.tally

import com.tally.backtest.BacktestParams
import com.tally.backtest.Backtester
import com.tally.broker.PaperPosition
import com.tally.config.RiskConfig
import com.tally.kite.*
import com.tally.portfolio.PortfolioAnalytics
import com.tally.portfolio.ProjectionRequest
import com.tally.risk.RiskManager
import com.tally.strategy.EmaCrossover
import com.tally.strategy.Indicators
import com.tally.strategy.Signal
import com.tally.strategy.StrategyRunner
import com.tally.util.IST
import com.tally.util.MarketClock
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.*

class CoreLogicTest {
    private val tuesday1030 = ZonedDateTime.of(2026, 10, 13, 10, 30, 0, 0, IST)

    private fun clockAt(t: ZonedDateTime) = MarketClock(Clock.fixed(t.toInstant(), IST))

    private val riskCfg = RiskConfig(
        killSwitch = false, maxDailyLoss = 5000.0, maxOrderValue = 100_000.0, maxOpenPositions = 2,
        maxOrdersPerDay = 10, intradayEntryCutoff = LocalTime.of(15, 0), intradaySquareOff = LocalTime.of(15, 15),
        allowedExchanges = setOf("NSE", "NFO"),
    )

    private fun pos(symbol: String, qty: Int, pnl: Double = 0.0) =
        Position(tradingsymbol = symbol, exchange = "NSE", product = "MIS", quantity = qty, pnl = pnl)

    private fun buy(symbol: String, qty: Int, product: Product = Product.MIS) =
        OrderRequest("NSE", symbol, Side.BUY, qty, product)

    @Test
    fun `ema seeds with sma then smooths`() {
        val ema = Indicators.ema(listOf(1.0, 2.0, 3.0, 4.0), 3)
        assertTrue(ema[1].isNaN())
        assertEquals(2.0, ema[2], 1e-9)
        assertEquals(3.0, ema[3], 1e-9) // 4*0.5 + 2*0.5
    }

    @Test
    fun `rsi is 100 on monotonic rise`() {
        val rsi = Indicators.rsi((1..30).map { it.toDouble() }, 14)
        assertEquals(100.0, rsi.last(), 1e-9)
    }

    @Test
    fun `paper position averages, realises and flips`() {
        val p = PaperPosition("NSE", "INFY", Product.MIS)
        p.fill(Side.BUY, 10, 100.0)
        p.fill(Side.BUY, 10, 110.0)
        assertEquals(105.0, p.averagePrice, 1e-9)
        p.fill(Side.SELL, 5, 120.0)
        assertEquals(75.0, p.realised, 1e-9)
        assertEquals(15, p.quantity)
        p.fill(Side.SELL, 20, 100.0) // closes 15 at -5 each, flips to -5 @ 100
        assertEquals(0.0, p.realised, 1e-9)
        assertEquals(-5, p.quantity)
        assertEquals(100.0, p.averagePrice, 1e-9)
    }

    @Test
    fun `risk blocks entries when market closed`() {
        val risk = RiskManager(riskCfg, clockAt(tuesday1030.withHour(16)))
        assertFalse(risk.check(buy("INFY", 1), 1500.0, emptyList(), 0, tuesday1030.withHour(16)).approved)
    }

    @Test
    fun `risk enforces value, positions and loss limits but always allows exits`() {
        val clock = clockAt(tuesday1030)
        val risk = RiskManager(riskCfg, clock)
        assertTrue(risk.check(buy("INFY", 10), 1500.0, emptyList(), 0, tuesday1030).approved)
        assertFalse(risk.check(buy("INFY", 100), 1500.0, emptyList(), 0, tuesday1030).approved, "order value cap")

        val two = listOf(pos("A", 1), pos("B", 1))
        assertFalse(risk.check(buy("C", 1), 100.0, two, 0, tuesday1030).approved, "max positions")

        val losing = listOf(pos("A", 10, pnl = -6000.0))
        assertFalse(risk.check(buy("C", 1), 100.0, losing, 0, tuesday1030).approved)
        assertTrue(risk.killSwitch, "daily loss trips kill switch")

        val exit = OrderRequest("NSE", "A", Side.SELL, 10, Product.MIS)
        val decision = risk.check(exit, 100.0, losing, 99, tuesday1030)
        assertTrue(decision.approved && decision.isExit, "exits pass even with kill switch on")
    }

    @Test
    fun `risk blocks intraday entries after cutoff`() {
        val late = tuesday1030.withHour(15).withMinute(5)
        val risk = RiskManager(riskCfg, clockAt(late))
        assertFalse(risk.check(buy("INFY", 1), 100.0, emptyList(), 0, late).approved)
        assertTrue(risk.check(buy("INFY", 1, Product.CNC), 100.0, emptyList(), 0, late).approved)
    }

    private fun candle(t: ZonedDateTime, close: Double) = Candle(
        time = t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")),
        open = close, high = close + 1, low = close - 1, close = close, volume = 1000,
    )

    @Test
    fun `forming candle is dropped`() {
        val start = tuesday1030.withMinute(25)
        val candles = listOf(candle(start.minusMinutes(5), 100.0), candle(start, 101.0))
        assertEquals(1, StrategyRunner.closedCandles(candles, "5minute", tuesday1030.withMinute(27)).size)
        assertEquals(2, StrategyRunner.closedCandles(candles, "5minute", tuesday1030.withMinute(31)).size)
    }

    @Test
    fun `ema crossover fires on cross and backtest trades it`() {
        val start = tuesday1030.withHour(9).withMinute(15)
        val prices = List(30) { 100.0 - it * 0.5 } + List(30) { 85.0 + it * 1.0 }
        val candles = prices.mapIndexed { i, p -> candle(start.plusMinutes(5L * i), p) }
        val strategy = EmaCrossover()
        val signals = (strategy.minBars..candles.size).map { strategy.evaluate(candles.subList(0, it)) }
        assertTrue(Signal.LONG in signals)

        val result = Backtester.run(strategy, candles, BacktestParams(intraday = true))
        assertTrue(result.trades >= 1)
        assertEquals("LONG", result.tradeList.first().side)
    }

    @Test
    fun `projection with no volatility matches compounding`() {
        val r = PortfolioAnalytics.project(
            ProjectionRequest(years = 2, startValue = 100_000.0, expectedReturnPct = 12.0, volatilityPct = 0.0, simulations = 100),
            currentValue = 0.0,
        )
        assertEquals(125_440.0, r.years.last().base, 1.0)
        assertEquals(r.years.last().base, r.years.last().p50, 1.0)
    }

    @Test
    fun `required sip reaches target`() {
        val r = PortfolioAnalytics.project(
            ProjectionRequest(years = 10, startValue = 0.0, expectedReturnPct = 12.0, volatilityPct = 0.0, targetValue = 1_000_000.0, simulations = 100),
            currentValue = 0.0,
        )
        val sip = r.requiredMonthlyForTarget!!
        assertTrue(sip in 4400.0..4600.0, "≈4,500/month at 12% for 10y, got $sip")
    }
}
