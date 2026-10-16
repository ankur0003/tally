package com.tally.ai

import com.tally.market.InstrumentService
import com.tally.market.MarketDataService
import com.tally.market.OptionType
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min

/** What-if numbers for one trade and for repeating it, so the trader sees both the upside and the cost of being wrong. */
@Serializable
data class Forecast(
    /** Probability used for the expectation, 0-1. */
    val winProbability: Double,
    /** "track record" when enough scored ideas exist in this confidence bucket, otherwise "AI estimate (unproven)". */
    val probabilitySource: String,
    val valueAtTarget: Double,
    val valueAtStop: Double,
    /** Expected INR per trade after estimated charges. Negative means the setup loses money on average. */
    val expectedPerTrade: Double,
    /** Trades needed for capital to reach 1.5x / 2x if every trade earned the expectation; null when it never would. */
    val tradesTo1_5x: Int?,
    val tradesTo2x: Int?,
)

/** How an index idea would be executed with a bought ATM option, sized to the money limits. */
@Serializable
data class OptionPlan(
    val ideaId: String,
    val index: String,
    val exchange: String = "NFO",
    val symbol: String,
    val optionType: String,
    val strike: Double,
    val expiry: String,
    val lotSize: Int,
    val premium: Double,
    val stopPremium: Double,
    val targetPremium: Double,
    /** targetPremium / premium: how many times the money grows if the target is hit. */
    val targetMultiple: Double,
    /** Index points the underlying must move (in the idea's direction) for the option to reach the target. */
    val indexMoveForTarget: Double,
    /** Index points to Claude's own index target, for comparison. */
    val aiIndexMove: Double,
    val lots: Int,
    val quantity: Int,
    val investment: Double,
    val maxLoss: Double,
    val profitAtTarget: Double,
    /** Cost and risk of a single lot, so the dashboard can say what it takes when lots = 0. */
    val oneLotCost: Double,
    val oneLotRisk: Double,
    /** Best bid/ask spread as % of price at planning time. */
    val spreadPct: Double? = null,
    val executable: Boolean,
    val reason: String,
    val forecast: Forecast,
)

/**
 * Turns an index idea into an option buy: CE for LONG, PE for SHORT, at-the-money, nearest non-expiring expiry.
 * Index stop/target are mapped to premium levels with an ATM delta of ~0.5. That is an approximation: theta,
 * IV changes and gamma move premiums too, which is why the stop also has a hard % cap.
 */
class OptionPlanner(
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val settings: TradeSettingsStore,
    private val tracker: IdeaTracker,
    private val maxOrderValue: Double,
) {
    suspend fun plan(
        idea: TradeIdea, lossBudgetLeft: Double, targetMultipleOverride: Double? = null, maxLossOverride: Double? = null,
        capitalOverride: Double? = null,
    ): OptionPlan {
        require(idea.action == "LONG" || idea.action == "SHORT") { "Idea ${idea.id} is not directional" }
        val type = if (idea.action == "LONG") OptionType.CE else OptionType.PE
        val spot = market.ltp(idea.quoteKey)
        val opt = instruments.atmOption(idea.index, spot, type)
        val q = market.quote(listOf("NFO:${opt.tradingsymbol}"))["NFO:${opt.tradingsymbol}"]
        val premium = q?.lastPrice ?: 0.0
        check(premium > 0) { "No price for ${opt.tradingsymbol}" }
        val spread = q?.spreadPct

        val s = settings.current
        val multiple = targetMultipleOverride ?: if (s.targetMode == "MULTIPLE") s.targetMultiple else null
        val levels = levels(premium, abs(idea.entry - idea.stopLoss), abs(idea.target - idea.entry), s.maxStopPct, multiple)
        val sizing = size(premium, levels.stop, opt.lotSize, capitalOverride ?: s.capital, min(maxLossOverride ?: s.maxLossPerTrade, lossBudgetLeft), maxOrderValue)
            .let { z ->
                // Illiquid strikes: a market order would give away the edge in the spread alone.
                when {
                    spread == null -> z.copy(lots = 0, reason = "No bid/ask in ${opt.tradingsymbol}; too illiquid to trade")
                    spread > MAX_SPREAD_PCT -> z.copy(lots = 0, reason = "Bid/ask spread %.1f%% is too wide (max %.0f%%)".format(spread, MAX_SPREAD_PCT))
                    else -> z
                }
            }
        val qty = sizing.lots * opt.lotSize
        val stats = tracker.stats()
        val bucket = stats.firstOrNull { it.bucket == bucketOf(idea.confidence) }
        val (p, source) = if (bucket != null && bucket.resolved >= MIN_SCORED && bucket.hitRatePct != null) {
            bucket.hitRatePct / 100 to "track record (${bucket.resolved} scored ideas at ${bucket.bucket}%)"
        } else {
            idea.confidence / 100.0 to "AI estimate (unproven)"
        }
        val unitsForForecast = if (qty > 0) qty else opt.lotSize
        val gain = (levels.target - premium) * unitsForForecast
        val loss = (premium - levels.stop) * unitsForForecast
        val investment = premium * unitsForForecast

        return OptionPlan(
            ideaId = idea.id, index = idea.index, symbol = opt.tradingsymbol, optionType = type.name, strike = opt.strike,
            expiry = opt.expiry.toString(), lotSize = opt.lotSize, premium = premium, stopPremium = levels.stop,
            targetPremium = levels.target, targetMultiple = round2(levels.target / premium),
            indexMoveForTarget = round2((levels.target - premium) / DELTA), aiIndexMove = round2(abs(idea.target - idea.entry)),
            lots = sizing.lots, quantity = qty, investment = premium * qty, maxLoss = (premium - levels.stop) * qty,
            profitAtTarget = (levels.target - premium) * qty,
            oneLotCost = premium * opt.lotSize, oneLotRisk = (premium - levels.stop) * opt.lotSize,
            spreadPct = spread?.let(::round2), executable = sizing.lots > 0, reason = sizing.reason,
            forecast = forecast(p, source, investment, gain, loss, maxOf(s.capital, investment)),
        )
    }

    data class Levels(val stop: Double, val target: Double)
    data class Sizing(val lots: Int, val reason: String)

    companion object {
        const val DELTA = 0.5
        /** Rough brokerage + STT + exchange charges for one option round trip at Zerodha. */
        const val CHARGES_PER_TRADE = 60.0
        private const val MIN_SCORED = 10
        const val MAX_SPREAD_PCT = 3.0

        fun levels(premium: Double, indexRisk: Double, indexReward: Double, maxStopPct: Double, multiple: Double?): Levels {
            val byIndex = premium - DELTA * indexRisk
            val floorByPct = premium * (1 - maxStopPct / 100)
            val stop = tickDown(maxOf(byIndex, floorByPct, 0.05)).coerceAtMost(tickDown(premium - 0.05))
            val target = tickUp(if (multiple != null) premium * multiple else premium + DELTA * indexReward)
            return Levels(stop, target)
        }

        fun size(premium: Double, stop: Double, lotSize: Int, capital: Double, riskBudget: Double, maxOrderValue: Double): Sizing {
            val lotCost = premium * lotSize
            val lotRisk = (premium - stop) * lotSize
            val byCapital = floor(capital / lotCost).toInt()
            val byRisk = if (lotRisk > 0) floor(riskBudget / lotRisk).toInt() else 0
            val byOrderCap = floor(maxOrderValue / lotCost).toInt()
            val lots = minOf(byCapital, byRisk, byOrderCap).coerceAtLeast(0)
            val reason = when {
                lots > 0 -> "OK"
                byCapital < 1 -> "1 lot costs ₹%,.0f but capital is ₹%,.0f".format(lotCost, capital)
                byRisk < 1 -> "1 lot risks ₹%,.0f at the stop but max loss allowed is ₹%,.0f".format(lotRisk, riskBudget)
                else -> "1 lot (₹%,.0f) exceeds the account max order value ₹%,.0f".format(lotCost, maxOrderValue)
            }
            return Sizing(lots, reason)
        }

        fun forecast(p: Double, source: String, investment: Double, gain: Double, loss: Double, capital: Double): Forecast {
            val ev = p * gain - (1 - p) * loss - CHARGES_PER_TRADE
            val g = if (capital > 0) ev / capital else 0.0
            fun tradesTo(m: Double) = if (g > 0) ceil(ln(m) / ln(1 + g)).toInt() else null
            return Forecast(
                winProbability = round2(p), probabilitySource = source,
                valueAtTarget = round2(investment + gain), valueAtStop = round2(investment - loss),
                expectedPerTrade = round2(ev), tradesTo1_5x = tradesTo(1.5), tradesTo2x = tradesTo(2.0),
            )
        }

        fun bucketOf(confidence: Int) = when {
            confidence >= 90 -> "90+"
            confidence >= 80 -> "80-89"
            confidence >= 60 -> "60-79"
            else -> "<60"
        }

        fun tickDown(p: Double) = floor(p * 20 + 1e-9) / 20
        fun tickUp(p: Double) = ceil(p * 20 - 1e-9) / 20
        private fun round2(d: Double) = Math.round(d * 100) / 100.0
    }
}
