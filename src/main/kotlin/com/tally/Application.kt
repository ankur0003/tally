package com.tally

import com.tally.ai.*
import com.tally.broker.Broker
import com.tally.broker.LiveBroker
import com.tally.broker.PaperBroker
import com.tally.config.AppConfig
import com.tally.config.TradingMode
import com.tally.kite.KiteClient
import com.tally.kite.KiteSessionStore
import com.tally.market.GlobalMarkets
import com.tally.market.InstrumentService
import com.tally.market.MarketDataService
import com.tally.risk.OrderService
import com.tally.risk.RiskManager
import com.tally.strategy.StrategyRunner
import com.tally.util.MarketClock
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.server.application.*
import java.nio.file.Files

class Services(val config: AppConfig) {
    val clock = MarketClock()
    val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }
    val sessions = KiteSessionStore(config.dataDir)
    val kite = KiteClient(config.kite, sessions, http)
    val instruments = InstrumentService(kite, config.dataDir)
    val market = MarketDataService(kite, instruments)
    val broker: Broker = when (config.mode) {
        TradingMode.LIVE -> LiveBroker(kite)
        TradingMode.PAPER -> PaperBroker(
            config.paper,
            ltp = { keys -> market.ltp(keys).mapValues { it.value.lastPrice } },
            dataDir = config.dataDir,
        )
    }
    val risk = RiskManager(config.risk, clock)
    val orders = OrderService(broker, risk, market, config.dataDir)
    val runner = StrategyRunner(
        configs = config.strategies,
        pollSeconds = config.pollSeconds,
        riskCfg = config.risk,
        market = market,
        instruments = instruments,
        orders = orders,
        clock = clock,
        isReady = { kite.isLoggedIn },
        dataDir = config.dataDir,
    )
    val claude = ClaudeApi(config.anthropic, http, config.dataDir)
    val advisor = ClaudeAdvisor(claude)
    val global = GlobalMarkets(http)
    val ideas = IdeaTracker(market, clock, config.dataDir)
    val intel = MarketIntel(claude, config.ai, market, global, ideas, instruments, clock, config.dataDir)
    val tradeSettings = TradeSettingsStore(config.ai, config.dataDir)
    val planner = OptionPlanner(instruments, market, tradeSettings, ideas, config.risk.maxOrderValue)
    val aiTrader = AiTrader(config.ai, config.risk, orders, market, ideas, planner, tradeSettings, clock, config.dataDir)
    val aiDesk = AiTraderDesk(claude, config.ai, intel, ideas, aiTrader, tradeSettings, global, clock, config.mode == TradingMode.LIVE, config.dataDir)
    val aiScheduler = AiScheduler(config.ai, claude, intel, ideas, aiTrader, aiDesk, clock, kiteReady = { kite.isLoggedIn })
}

fun Application.module() {
    val config = AppConfig.from(environment.config)
    Files.createDirectories(config.dataDir)
    val services = Services(config)

    configureHttp(config)
    configureRouting(services)

    log.info("Tally running in {} mode, data dir {}", config.mode, config.dataDir)
    if (config.mode == TradingMode.LIVE) log.warn("LIVE MODE: orders will be sent to Zerodha with real money")
    if (environment.config.propertyOrNull("tally.apiToken")?.getString().isNullOrBlank()) {
        log.info("Generated API token for this run (set TALLY_API_TOKEN to fix it): {}", config.apiToken)
    }
    if (!config.kite.configured) log.warn("KITE_API_KEY / KITE_API_SECRET not set; market data and trading are unavailable")
    if (!config.anthropic.configured) log.warn("ANTHROPIC_API_KEY not set; /api/ai endpoints are unavailable")

    services.runner.start()
    services.aiScheduler.start()
    monitor.subscribe(ApplicationStopping) {
        services.runner.shutdown()
        services.aiScheduler.shutdown()
        services.http.close()
    }
}
