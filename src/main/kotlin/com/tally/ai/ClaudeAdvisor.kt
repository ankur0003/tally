package com.tally.ai

import kotlinx.serialization.Serializable

@Serializable
data class AdvisorReply(val model: String, val text: String, val stopReason: String?, val inputTokens: Long, val outputTokens: Long)

/**
 * On-demand research (portfolio review, single-symbol analysis). Strictly advisory: nothing here can reach
 * the order path.
 */
class ClaudeAdvisor(private val api: ClaudeApi) {

    suspend fun reviewPortfolio(portfolioJson: String, projectionJson: String?, goals: String?): AdvisorReply = ask(
        effort = "high",
        prompt = buildString {
            appendLine("Review my equity portfolio held at Zerodha. Holdings and summary (INR):")
            appendLine("<portfolio>\n$portfolioJson\n</portfolio>")
            projectionJson?.let { appendLine("Projection I ran:\n<projection>\n$it\n</projection>") }
            goals?.takeIf { it.isNotBlank() }?.let { appendLine("My goals and constraints: $it") }
            appendLine()
            appendLine("Cover: concentration and sector exposure (infer sectors from the symbols), positions that look")
            appendLine("weak or over-extended, whether the projection assumptions are realistic for Indian equities,")
            appendLine("and a short prioritised action list for growing the portfolio within sensible risk.")
        },
    )

    suspend fun analyzeSymbol(symbol: String, marketJson: String, question: String?): AdvisorReply = ask(
        effort = "medium",
        prompt = buildString {
            appendLine("Analyse $symbol from this market data (Kite quote plus recent daily candles and indicators):")
            appendLine("<market_data>\n$marketJson\n</market_data>")
            appendLine(question?.takeIf { it.isNotBlank() } ?: "Give the trend, key support/resistance levels, momentum, and what would invalidate a long or short view for intraday and swing horizons.")
        },
    )

    private suspend fun ask(prompt: String, effort: String): AdvisorReply {
        val r = api.create(ClaudeApi.baseBody(api.model, SYSTEM_PROMPT, 16000, effort), prompt)
        val text = if (r.stopReason == "refusal") "Claude declined to answer this request." else r.text
        return AdvisorReply(r.model, text, r.stopReason, r.inputTokens, r.outputTokens)
    }

    companion object {
        val SYSTEM_PROMPT = """
            You are an equity and derivatives analyst working for one individual retail investor in India who trades
            NSE/BSE cash and NFO index options (NIFTY, BANKNIFTY) through Zerodha. They do intraday, swing and long-term investing.

            Be concrete and quantitative: cite the numbers you were given, give price levels, percentages and position
            sizes rather than generalities. Use Indian market context (SEBI rules, F&O lot sizes and expiries, STT and
            taxes, NSE sessions) where it matters. If the data provided is not enough to support a conclusion, say what is missing
            instead of guessing. Call out risk plainly, especially leverage, concentration and option decay.

            Your output is research for the investor's own decision; you do not place trades. Format answers as short
            Markdown sections with a brief bottom line first.
        """.trimIndent()
    }
}
