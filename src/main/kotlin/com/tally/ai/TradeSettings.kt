package com.tally.ai

import com.tally.config.AiConfig
import com.tally.util.TallyJson
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** Money limits for AI option trades. Editable from the dashboard; config values are only the first-run defaults. */
@Serializable
data class TradeSettings(
    /** INR available per trade: premium x quantity must fit in this. */
    val capital: Double,
    /** INR lost if the stop-loss is hit; lots are sized so this is never exceeded. */
    val maxLossPerTrade: Double,
    /** INR of AI-trade losses after which no new trades are taken today. */
    val maxDailyLoss: Double,
    /** INR profit for the day after which the open trade is closed and no new trades are taken. 0 = off. */
    val dailyProfitGoal: Double = 0.0,
    /** AI = exit where Claude's index target maps to on the option; MULTIPLE = exit when premium reaches [targetMultiple] x entry. */
    val targetMode: String = "AI",
    val targetMultiple: Double = 1.5,
    /** Largest premium drop (%) tolerated before the stop-loss exits, whatever the index stop says. */
    val maxStopPct: Double = 35.0,
    /** Trailing stop starts once the premium is up this much (%): stop moves to break-even, then trails. 0 = off. */
    val trailStartPct: Double = 25.0,
    /** Once trailing, the stop sits this % below the highest premium seen. */
    val trailPct: Double = 10.0,
    /** INR max loss for a manual High-risk trade (ideas below the confidence bar). Sized separately and smaller. */
    val highRiskMaxLoss: Double = 1000.0,
    /** AI trades open or waiting at the same time (one per underlying). */
    val maxOpenTrades: Int = 3,
) {
    fun validated(): TradeSettings {
        require(capital in 0.0..10_000_000.0) { "capital must be 0 - 1,00,00,000" }
        require(maxLossPerTrade > 0) { "max loss per trade must be positive" }
        require(maxDailyLoss >= maxLossPerTrade) { "max daily loss must be at least the max loss per trade" }
        require(dailyProfitGoal >= 0) { "daily profit goal cannot be negative (0 turns it off)" }
        require(targetMode in setOf("AI", "MULTIPLE")) { "targetMode must be AI or MULTIPLE" }
        require(targetMultiple in 1.1..5.0) { "target multiple must be between 1.1x and 5x" }
        require(maxStopPct in 5.0..80.0) { "max stop must be 5-80% of the premium" }
        require(trailStartPct == 0.0 || trailStartPct in 5.0..200.0) { "trailing start must be 0 (off) or 5-200%" }
        require(trailPct in 2.0..50.0) { "trailing distance must be 2-50%" }
        require(maxOpenTrades in 1..5) { "max open trades must be 1-5" }
        require(highRiskMaxLoss > 0 && highRiskMaxLoss <= maxDailyLoss) { "high-risk max loss must be positive and at most the max daily loss" }
        return this
    }
}

class TradeSettingsStore(defaults: AiConfig, dataDir: Path) {
    private val log = LoggerFactory.getLogger(TradeSettingsStore::class.java)
    private val file = dataDir.resolve("ai_settings.json")

    @Volatile var current: TradeSettings = runCatching {
        if (Files.exists(file)) TallyJson.decodeFromString(TradeSettings.serializer(), Files.readString(file)).validated() else null
    }.onFailure { log.warn("Ignoring invalid {}: {}", file, it.message) }.getOrNull()
        ?: TradeSettings(defaults.capital, defaults.maxLossPerTrade, defaults.maxDailyLoss)
        private set

    fun update(s: TradeSettings): TradeSettings {
        val v = s.validated()
        Files.createDirectories(file.parent)
        Files.writeString(file, TallyJson.encodeToString(TradeSettings.serializer(), v))
        current = v
        log.info("AI trade settings updated: {}", v)
        return v
    }
}
