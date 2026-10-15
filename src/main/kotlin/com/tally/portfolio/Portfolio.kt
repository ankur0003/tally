package com.tally.portfolio

import com.tally.kite.Holding
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

@Serializable
data class HoldingView(
    val symbol: String,
    val exchange: String,
    val quantity: Int,
    val averagePrice: Double,
    val lastPrice: Double,
    val invested: Double,
    val currentValue: Double,
    val pnl: Double,
    val pnlPct: Double,
    val dayChangePct: Double,
    val weightPct: Double,
)

@Serializable
data class PortfolioSummary(
    val invested: Double,
    val currentValue: Double,
    val pnl: Double,
    val pnlPct: Double,
    val dayChange: Double,
    val holdings: List<HoldingView>,
    val warnings: List<String>,
)

@Serializable
data class ProjectionRequest(
    val years: Int = 10,
    /** Defaults to the current portfolio value. */
    val startValue: Double? = null,
    val monthlyContribution: Double = 0.0,
    /** Yearly % increase in the monthly contribution (SIP step-up). */
    val annualStepUpPct: Double = 0.0,
    val expectedReturnPct: Double = 12.0,
    val volatilityPct: Double = 18.0,
    val inflationPct: Double = 5.0,
    val targetValue: Double? = null,
    val simulations: Int = 5000,
    val seed: Long = 42,
)

@Serializable
data class YearRow(
    val year: Int,
    val contributed: Double,
    val conservative: Double,
    val base: Double,
    val optimistic: Double,
    val baseInTodaysMoney: Double,
    val p10: Double,
    val p50: Double,
    val p90: Double,
)

@Serializable
data class ProjectionResult(
    val request: ProjectionRequest,
    val startValue: Double,
    val totalContributed: Double,
    val years: List<YearRow>,
    val probabilityOfTargetPct: Double?,
    val requiredMonthlyForTarget: Double?,
    val assumptions: String,
)

object PortfolioAnalytics {
    fun summarise(holdings: List<Holding>): PortfolioSummary {
        val rows = holdings.filter { it.totalQuantity > 0 }
        val current = rows.sumOf { it.totalQuantity * it.lastPrice }
        val views = rows.map { h ->
            val invested = h.totalQuantity * h.averagePrice
            val value = h.totalQuantity * h.lastPrice
            HoldingView(
                symbol = h.tradingsymbol, exchange = h.exchange, quantity = h.totalQuantity,
                averagePrice = h.averagePrice, lastPrice = h.lastPrice, invested = invested, currentValue = value,
                pnl = value - invested, pnlPct = if (invested > 0) (value - invested) / invested * 100 else 0.0,
                dayChangePct = h.dayChangePercentage,
                weightPct = if (current > 0) value / current * 100 else 0.0,
            )
        }.sortedByDescending { it.currentValue }

        val invested = views.sumOf { it.invested }
        val warnings = buildList {
            views.filter { it.weightPct > 20 }.forEach { add("${it.symbol} is %.1f%% of the portfolio (>20%%)".format(it.weightPct)) }
            val top3 = views.take(3).sumOf { it.weightPct }
            if (views.size > 3 && top3 > 50) add("Top 3 holdings are %.1f%% of the portfolio".format(top3))
            if (views.isNotEmpty() && views.size < 8) add("Only ${views.size} holdings; consider broader diversification")
            views.filter { it.pnlPct < -25 }.forEach { add("${it.symbol} is down %.1f%% from cost; review the thesis".format(it.pnlPct)) }
        }
        return PortfolioSummary(
            invested = invested,
            currentValue = current,
            pnl = current - invested,
            pnlPct = if (invested > 0) (current - invested) / invested * 100 else 0.0,
            dayChange = rows.sumOf { it.totalQuantity * it.dayChange },
            holdings = views,
            warnings = warnings,
        )
    }

    fun project(req: ProjectionRequest, currentValue: Double): ProjectionResult {
        require(req.years in 1..50) { "years must be 1..50" }
        require(req.simulations in 100..50_000) { "simulations must be 100..50000" }
        val start = req.startValue ?: currentValue
        val months = req.years * 12

        val contributions = DoubleArray(months) { m ->
            req.monthlyContribution * (1 + req.annualStepUpPct / 100).pow(m / 12)
        }

        fun deterministic(annualPct: Double): DoubleArray {
            val r = (1 + annualPct / 100).pow(1.0 / 12) - 1
            val out = DoubleArray(req.years)
            var v = start
            for (m in 0 until months) {
                v = v * (1 + r) + contributions[m]
                if ((m + 1) % 12 == 0) out[m / 12] = v
            }
            return out
        }

        val cons = deterministic(req.expectedReturnPct - 4)
        val base = deterministic(req.expectedReturnPct)
        val opt = deterministic(req.expectedReturnPct + 4)

        // Monte Carlo with lognormal monthly returns whose mean matches expectedReturnPct annually.
        val sigmaM = req.volatilityPct / 100 / sqrt(12.0)
        val muM = ln(1 + req.expectedReturnPct / 100) / 12 - sigmaM * sigmaM / 2
        val rnd = Random(req.seed)
        val yearly = Array(req.years) { DoubleArray(req.simulations) }
        for (s in 0 until req.simulations) {
            var v = start
            for (m in 0 until months) {
                v = v * exp(muM + sigmaM * gaussian(rnd)) + contributions[m]
                if ((m + 1) % 12 == 0) yearly[m / 12][s] = v
            }
        }
        yearly.forEach { it.sort() }
        fun pct(arr: DoubleArray, p: Double) = arr[((arr.size - 1) * p).toInt()]

        var contributed = 0.0
        val rows = (0 until req.years).map { y ->
            contributed += (0 until 12).sumOf { contributions[y * 12 + it] }
            YearRow(
                year = y + 1, contributed = contributed,
                conservative = cons[y], base = base[y], optimistic = opt[y],
                baseInTodaysMoney = base[y] / (1 + req.inflationPct / 100).pow(y + 1),
                p10 = pct(yearly[y], 0.10), p50 = pct(yearly[y], 0.50), p90 = pct(yearly[y], 0.90),
            )
        }

        val target = req.targetValue
        val probability = target?.let { t -> yearly.last().count { it >= t } * 100.0 / req.simulations }
        val required = target?.let { requiredMonthly(it, start, req) }

        return ProjectionResult(
            request = req, startValue = start, totalContributed = contributed, years = rows,
            probabilityOfTargetPct = probability, requiredMonthlyForTarget = required,
            assumptions = "Conservative/base/optimistic use ${req.expectedReturnPct - 4}/${req.expectedReturnPct}/${req.expectedReturnPct + 4}% " +
                "annual returns. Monte Carlo: ${req.simulations} paths, ${req.volatilityPct}% annual volatility, lognormal. " +
                "Pre-tax, ignores LTCG/STCG and costs. Past returns do not guarantee future results.",
        )
    }

    /** Starting monthly contribution (with the same step-up) needed to hit [target] at the base return. */
    private fun requiredMonthly(target: Double, start: Double, req: ProjectionRequest): Double {
        val r = (1 + req.expectedReturnPct / 100).pow(1.0 / 12) - 1
        fun endValue(monthly: Double): Double {
            var v = start
            for (m in 0 until req.years * 12) v = v * (1 + r) + monthly * (1 + req.annualStepUpPct / 100).pow(m / 12)
            return v
        }
        if (endValue(0.0) >= target) return 0.0
        var lo = 0.0
        var hi = target
        repeat(100) {
            val mid = (lo + hi) / 2
            if (endValue(mid) >= target) hi = mid else lo = mid
        }
        return hi
    }

    private fun gaussian(rnd: Random): Double {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
