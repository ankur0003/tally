package com.tally.ai

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/** One-line, casual "what the bot thinks right now", shown under the holdings. */
@Serializable
data class BotMood(
    val text: String,
    /** good | calm | caution | danger */
    val tone: String,
    /** "AI" when the line came from Claude's latest analysis, "rules" when derived from live numbers. */
    val source: String,
)

/** Everything the mood needs, gathered by the dashboard (all optional: Kite may be logged out). */
data class MoodInputs(
    val now: ZonedDateTime,
    val marketOpen: Boolean,
    val niftyChangePct: Double?,
    val vix: Double?,
    val vixChangePct: Double?,
    /** Claude's latest one-liner and when it was written. */
    val aiLine: String?,
    val aiLineAt: ZonedDateTime?,
    val openSymbol: String?,
    val openPnl: Double?,
    val lossLimitHit: Boolean,
    val profitGoalHit: Boolean,
    val tradeableIdeas: Int,
    val minConfidence: Int,
)

/**
 * Picks the most important thing to say, in priority order: market closed, day finished (goal / loss limit),
 * an open trade, danger signals, then Claude's own line if it is fresh, then milder observations.
 */
object BotMoodRules {
    private val AI_FRESH: Duration = Duration.ofMinutes(20)

    fun mood(m: MoodInputs): BotMood {
        val t = m.now.toLocalTime()
        val weekend = m.now.dayOfWeek == DayOfWeek.SATURDAY || m.now.dayOfWeek == DayOfWeek.SUNDAY
        fun rules(text: String, tone: String) = BotMood(text, tone, "rules")

        if (weekend) return rules("It's the weekend. Markets are closed, so no trading from me. Rest up.", "calm")
        if (!m.marketOpen) {
            return if (t.isBefore(LocalTime.of(9, 15))) rules("Markets open at 9:15. I'm reading the overnight news so you don't have to.", "calm")
            else rules("Market's closed for today. No more trades from me until tomorrow.", "calm")
        }
        if (m.profitGoalHit) return rules("Goal hit! Profit's locked in and I'm done trading for today.", "good")
        if (m.lossLimitHit) return rules("Rough day. The loss limit is hit, so I'm not trading again until tomorrow.", "danger")
        if (m.openSymbol != null && m.openPnl != null) {
            return if (m.openPnl >= 0) rules("We're up ₹%,.0f on %s. Holding, and the stop follows the price up.".format(m.openPnl, m.openSymbol), "good")
            else rules("Down ₹%,.0f on %s for now. The stop-loss is in place, so I'm staying calm.".format(-m.openPnl, m.openSymbol), "caution")
        }
        if (t.isBefore(LocalTime.of(9, 30))) return rules("Opening-bell chaos. Not a good time to trade, let the market settle first.", "caution")

        val vixJump = (m.vixChangePct ?: 0.0) >= 5
        val vixHigh = (m.vix ?: 0.0) >= 20
        if (vixJump || vixHigh) {
            val why = if (vixJump) "VIX is up %.1f%% today".format(m.vixChangePct) else "VIX is at %.1f".format(m.vix)
            return rules("Uh oh, markets are shaking: $why. Something's brewing, so I'd stay careful.", "danger")
        }
        if (m.aiLine != null && m.aiLineAt != null && Duration.between(m.aiLineAt, m.now) <= AI_FRESH) {
            return BotMood(m.aiLine, if (m.tradeableIdeas > 0) "good" else "calm", "AI")
        }
        if (t.isAfter(LocalTime.of(14, 45))) return rules("Too late for new trades today. The last hour is for squaring off, not starting.", "calm")
        if (m.tradeableIdeas > 0) {
            return rules("Ooh, I see ${m.tradeableIdeas} setup${if (m.tradeableIdeas > 1) "s" else ""} at ${m.minConfidence}%+ confidence. Check the ideas.", "good")
        }
        val move = m.niftyChangePct
        if (move != null && abs(move) >= 1.5) {
            return rules("Big day: NIFTY is %s%.1f%%. Moves this stretched are risky to chase.".format(if (move > 0) "+" else "−", abs(move)), "caution")
        }
        return rules("Quiet market. Nothing worth risking money on yet, so patience is the trade.", "calm")
    }
}
