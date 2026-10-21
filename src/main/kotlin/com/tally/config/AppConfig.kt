package com.tally.config

import io.ktor.server.config.*
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalTime
import java.util.UUID

enum class TradingMode { PAPER, LIVE }

enum class ExecutionMode {
    /** MIS intraday on the cash segment, long and short, squared off at EOD. */
    EQUITY_INTRADAY,

    /** CNC delivery, long-only. */
    EQUITY_SWING,

    /** Buy ATM CE on LONG, ATM PE on SHORT. Option buying only, so max loss is the premium paid. */
    INDEX_OPTIONS,
}

data class KiteConfig(
    val apiKey: String,
    val apiSecret: String,
    val baseUrl: String,
    val loginUrl: String,
    /** Only this Zerodha client ID may log in; blank allows any (local dev only). */
    val userId: String = "",
) {
    val configured get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}

data class AnthropicConfig(val apiKey: String, val model: String) {
    val configured get() = apiKey.isNotBlank()
}

data class RiskConfig(
    val killSwitch: Boolean,
    val maxDailyLoss: Double,
    val maxOrderValue: Double,
    val maxOpenPositions: Int,
    val maxOrdersPerDay: Int,
    val intradayEntryCutoff: LocalTime,
    val intradaySquareOff: LocalTime,
    val allowedExchanges: Set<String>,
)

data class AiConfig(
    val scheduler: Boolean,
    val briefMinutes: Long,
    val ideaMinutes: Long,
    val minConfidence: Int,
    val minRewardRisk: Double,
    val dailyBudgetUsd: Double,
    val autoTrade: Boolean,
    val allowShort: Boolean,
    val capital: Double,
    val maxLossPerTrade: Double,
    val maxDailyLoss: Double,
    /** F&O stocks scanned for stock-option ideas (NSE symbols). */
    val stocks: List<String>,
    val stockIdeaMinutes: Long,
    /** How many of the day's biggest movers from [stocks] are sent to Claude per scan. */
    val stockShortlist: Int,
    /**
     * PAPER learning mode: analysts call every setup with clear levels instead of only strong ones, and the AI
     * trader decides from its playbook and history without seeing the analysts' confidence. Always off in LIVE.
     */
    val learningMode: Boolean = false,
)

data class PaperConfig(
    val startingCash: Double,
    val slippageBps: Double,
    /** PAPER mode only: replace these account risk limits so a large simulated fund can trade at size. Null = keep tally.risk. */
    val maxOrderValue: Double? = null,
    val maxDailyLoss: Double? = null,
    val maxOrdersPerDay: Int? = null,
    val maxOpenPositions: Int? = null,
)

data class StrategyInstanceConfig(
    val id: String,
    val strategy: String,
    val exchange: String,
    val symbol: String,
    val interval: String,
    val execution: ExecutionMode,
    val optionsUnderlying: String?,
    val quantity: Int,
    val lots: Int,
    val stopLossPct: Double,
    val targetPct: Double,
    val autoTrade: Boolean,
    val enabled: Boolean,
)

data class AppConfig(
    val mode: TradingMode,
    val apiToken: String,
    val dataDir: Path,
    /** Public HTTPS hostname behind the reverse proxy (e.g. tally.example.com), or blank when only reached via the tunnel. */
    val publicHost: String,
    val kite: KiteConfig,
    val anthropic: AnthropicConfig,
    val risk: RiskConfig,
    val paper: PaperConfig,
    val ai: AiConfig,
    val pollSeconds: Long,
    val strategies: List<StrategyInstanceConfig>,
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val t = config.config("tally")
            fun ApplicationConfig.str(key: String, default: String = "") = propertyOrNull(key)?.getString() ?: default

            val risk = t.config("risk")
            val paper = t.config("paper")
            val strategies = t.config("strategies")
            val dataDir = t.str("dataDir").ifBlank { Paths.get(System.getProperty("user.home"), ".tally").toString() }

            val mode = TradingMode.valueOf(t.str("mode", "PAPER").uppercase())
            val paperCfg = PaperConfig(
                paper.str("startingCash").toDouble(), paper.str("slippageBps").toDouble(),
                paper.str("maxOrderValue").toDoubleOrNull(), paper.str("maxDailyLoss").toDoubleOrNull(),
                paper.str("maxOrdersPerDay").toIntOrNull(), paper.str("maxOpenPositions").toIntOrNull(),
            )
            val liveRisk = RiskConfig(
                killSwitch = risk.str("killSwitch", "false").toBoolean(),
                maxDailyLoss = risk.str("maxDailyLoss").toDouble(),
                maxOrderValue = risk.str("maxOrderValue").toDouble(),
                maxOpenPositions = risk.str("maxOpenPositions").toInt(),
                maxOrdersPerDay = risk.str("maxOrdersPerDay").toInt(),
                intradayEntryCutoff = LocalTime.parse(risk.str("intradayEntryCutoff")),
                intradaySquareOff = LocalTime.parse(risk.str("intradaySquareOff")),
                allowedExchanges = risk.property("allowedExchanges").getList().toSet(),
            )
            return AppConfig(
                mode = mode,
                apiToken = t.str("apiToken").ifBlank { UUID.randomUUID().toString() },
                dataDir = Paths.get(dataDir),
                publicHost = t.str("publicHost").trim().lowercase(),
                kite = t.config("kite").let {
                    KiteConfig(it.str("apiKey"), it.str("apiSecret"), it.str("baseUrl"), it.str("loginUrl"), it.str("userId").uppercase())
                },
                anthropic = t.config("anthropic").let { AnthropicConfig(it.str("apiKey"), it.str("model")) },
                risk = if (mode == TradingMode.LIVE) liveRisk else liveRisk.copy(
                    maxOrderValue = paperCfg.maxOrderValue ?: liveRisk.maxOrderValue,
                    maxDailyLoss = paperCfg.maxDailyLoss ?: liveRisk.maxDailyLoss,
                    maxOrdersPerDay = paperCfg.maxOrdersPerDay ?: liveRisk.maxOrdersPerDay,
                    maxOpenPositions = paperCfg.maxOpenPositions ?: liveRisk.maxOpenPositions,
                ),
                paper = paperCfg,
                ai = t.config("ai").let { a ->
                    AiConfig(
                        scheduler = a.str("scheduler", "true").toBoolean(),
                        briefMinutes = a.str("briefMinutes", "60").toLong(),
                        ideaMinutes = a.str("ideaMinutes", "30").toLong(),
                        minConfidence = a.str("minConfidence", "90").toInt(),
                        minRewardRisk = a.str("minRewardRisk", "1.5").toDouble(),
                        dailyBudgetUsd = a.str("dailyBudgetUsd", "3").toDouble(),
                        autoTrade = a.str("autoTrade", "false").toBoolean(),
                        allowShort = a.str("allowShort", "true").toBoolean(),
                        capital = a.str("capital", "1000").toDouble(),
                        maxLossPerTrade = a.str("maxLossPerTrade", "250").toDouble(),
                        maxDailyLoss = a.str("maxDailyLoss", "300").toDouble(),
                        stocks = a.propertyOrNull("stocks")?.getList()?.map { it.trim().uppercase() }.orEmpty(),
                        stockIdeaMinutes = a.str("stockIdeaMinutes", "30").toLong(),
                        stockShortlist = a.str("stockShortlist", "6").toInt(),
                        learningMode = mode == TradingMode.PAPER && a.str("learningMode", "false").toBoolean(),
                    )
                },
                pollSeconds = strategies.str("pollSeconds", "30").toLong(),
                strategies = strategies.configList("instances").map { s ->
                    StrategyInstanceConfig(
                        id = s.str("id"),
                        strategy = s.str("strategy"),
                        exchange = s.str("exchange"),
                        symbol = s.str("symbol"),
                        interval = s.str("interval"),
                        execution = ExecutionMode.valueOf(s.str("execution")),
                        optionsUnderlying = s.propertyOrNull("optionsUnderlying")?.getString(),
                        quantity = s.str("quantity", "0").toInt(),
                        lots = s.str("lots", "1").toInt(),
                        stopLossPct = s.str("stopLossPct", "0").toDouble(),
                        targetPct = s.str("targetPct", "0").toDouble(),
                        autoTrade = s.str("autoTrade", "false").toBoolean(),
                        enabled = s.str("enabled", "true").toBoolean(),
                    )
                },
            )
        }
    }
}
