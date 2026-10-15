package com.tally

import com.tally.ai.*
import com.tally.backtest.BacktestParams
import com.tally.backtest.Backtester
import com.tally.config.TradingMode
import com.tally.kite.OrderRequest
import com.tally.market.MarketDataService
import com.tally.portfolio.PortfolioAnalytics
import com.tally.portfolio.ProjectionRequest
import com.tally.strategy.Indicators
import com.tally.strategy.StrategyRegistry
import com.tally.strategy.StrategyRunner
import com.tally.util.TallyJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.util.UUID

@Serializable
data class KillSwitchRequest(val on: Boolean, val reason: String = "Manual")

@Serializable
data class StrategyUpdate(val enabled: Boolean? = null, val autoTrade: Boolean? = null, val confirmLive: Boolean = false)

@Serializable
data class BacktestRequest(
    val strategy: String,
    val exchange: String = "NSE",
    val symbol: String,
    val interval: String = "5minute",
    val days: Long? = null,
    val params: BacktestParams = BacktestParams(),
)

@Serializable
data class PortfolioReviewRequest(val goals: String? = null, val projection: ProjectionRequest? = null)

@Serializable
data class AnalyzeRequest(val exchange: String = "NSE", val symbol: String, val question: String? = null)

@Serializable
data class AutoTradeRequest(val on: Boolean, val confirmLive: Boolean = false)

@Serializable
data class TakeTradeRequest(
    val ideaId: String,
    val lots: Int? = null,
    val targetMultiple: Double? = null,
    val confirmLive: Boolean = false,
    /** Taken from the High-risk section: confidence bar ignored, sized with the high-risk max loss. */
    val highRisk: Boolean = false,
)

@Serializable
data class TradeRef(val ideaId: String? = null)

@Serializable
data class TestIdeaRequest(val index: String = "NIFTY", val action: String = "LONG")

@Serializable
data class TakeTradeResponse(val message: String, val trader: AiTraderStatus)

@Serializable
data class ChartPoint(val t: String, val c: Double)

@Serializable
data class IndexTile(
    val key: String, val name: String, val ltp: Double, val prevClose: Double,
    val change: Double, val changePct: Double, val high: Double, val low: Double,
)

@Serializable
data class OptionRow(
    val strike: Double,
    val ceSymbol: String?, val ceLtp: Double?, val ceOi: Double?,
    val peSymbol: String?, val peLtp: Double?, val peOi: Double?,
)

@Serializable
data class OptionChainResponse(val underlying: String, val spot: Double, val expiry: String, val lotSize: Int, val rows: List<OptionRow>)

private const val LOGIN_STATE_COOKIE = "tally_login_state"

private val INDICES = mapOf("NIFTY" to "NSE:NIFTY 50", "BANKNIFTY" to "NSE:NIFTY BANK", "FINNIFTY" to "NSE:NIFTY FIN SERVICE")

fun Application.configureRouting(s: Services) {
    routing {
        get("/health") { call.respondText("ok") }

        // Dashboard SPA (src/main/resources/web). It asks for the API token once and keeps it in the browser.
        staticResources("/", "web")

        // ---- Kite browser login. Redirect URL in the Kite console: https://<your-domain>/auth/callback
        // (or http://127.0.0.1:8090/auth/callback when using the SSH tunnel). These two routes may be public.
        get("/auth/login") {
            require(s.config.kite.configured) { "Set KITE_API_KEY and KITE_API_SECRET first" }
            val state = UUID.randomUUID().toString()
            call.response.cookies.append(
                Cookie(
                    LOGIN_STATE_COOKIE, state, maxAge = 600, path = "/auth", httpOnly = true,
                    secure = call.request.headers["X-Forwarded-Proto"] == "https",
                    extensions = mapOf("SameSite" to "Lax"),
                ),
            )
            call.respondRedirect(s.kite.loginUrl(state))
        }
        get("/auth/callback") {
            val q = call.request.queryParameters
            val token = q["request_token"]
            val expectedState = call.request.cookies[LOGIN_STATE_COOKIE]
            if (expectedState.isNullOrBlank() || q["state"] != expectedState) {
                call.respondText("Login state mismatch. Start again from /auth/login.", status = HttpStatusCode.BadRequest)
                return@get
            }
            call.response.cookies.append(Cookie(LOGIN_STATE_COOKIE, "", maxAge = 0, path = "/auth"))
            if (q["status"] != "success" || token.isNullOrBlank()) {
                call.respondText("Kite login failed or was cancelled.", status = HttpStatusCode.BadRequest)
                return@get
            }
            val session = s.kite.createSession(token)
            call.respondText(
                "<h3>Logged in to Kite as ${session.userName ?: session.userId}.</h3><p>You can close this tab.</p>",
                ContentType.Text.Html,
            )
        }

        route("/api") {
            get("/status") {
                val loggedIn = s.kite.isLoggedIn
                val riskStatus = if (loggedIn) runCatching {
                    s.risk.status(s.broker.positions(), s.broker.orders().size)
                }.getOrNull() else null
                call.respond(buildJsonObject {
                    put("mode", s.config.mode.name)
                    put("kiteConfigured", s.config.kite.configured)
                    put("kiteLoggedIn", loggedIn)
                    put("claudeConfigured", s.config.anthropic.configured)
                    put("marketOpen", s.clock.isOpen())
                    put("runnerRunning", s.runner.running)
                    put("risk", riskStatus?.let { TallyJson.encodeToJsonElement(com.tally.risk.RiskStatus.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
                })
            }
            // Invalidates the Kite access token. Refused while anything is open: once logged out tally can no longer
            // manage targets, trailing stops or the 15:15 exit. ?force=true overrides (broker stop orders stay live).
            post("/auth/logout") {
                val force = call.request.queryParameters["force"] == "true"
                if (!force && s.kite.isLoggedIn) {
                    val trader = s.aiTrader.status()
                    val openPositions = runCatching { s.broker.positions().filter { it.quantity != 0 } }.getOrDefault(emptyList())
                    val blockers = buildList {
                        if (trader.opens.isNotEmpty()) add("${trader.opens.size} AI trade(s) open")
                        if (trader.pendings.isNotEmpty()) add("${trader.pendings.size} AI trade(s) waiting for entry")
                        if (openPositions.isNotEmpty()) add("open positions: ${openPositions.joinToString { it.tradingsymbol }}")
                    }
                    check(blockers.isEmpty()) { "Not logging out: ${blockers.joinToString("; ")}. Close them first so nothing is left unmanaged." }
                }
                s.kite.logout()
                call.respond(HttpStatusCode.NoContent)
            }

            // ---- Account & portfolio
            get("/account/profile") { call.respond(s.kite.profile()) }
            get("/account/margins") { call.respond(s.kite.margins()) }
            get("/portfolio") { call.respond(PortfolioAnalytics.summarise(s.kite.holdings())) }
            post("/portfolio/projection") {
                val req = call.receive<ProjectionRequest>()
                val current = if (req.startValue == null) PortfolioAnalytics.summarise(s.kite.holdings()).currentValue else 0.0
                call.respond(PortfolioAnalytics.project(req, current))
            }

            // ---- Orders & positions (through the active broker: paper or live)
            get("/positions") { call.respond(s.broker.positions()) }
            get("/orders") { call.respond(s.broker.orders()) }
            post("/orders") {
                val outcome = s.orders.place(call.receive<OrderRequest>(), "api")
                call.respond(if (outcome.placed) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity, outcome)
            }
            delete("/orders/{id}") {
                s.broker.cancelOrder(call.parameters["id"]!!, call.request.queryParameters["variety"] ?: "regular")
                call.respond(HttpStatusCode.NoContent)
            }
            post("/squareoff") {
                // Panic button: block new entries, then flatten everything.
                s.risk.setKillSwitch(true, "Manual square-off")
                call.respond(s.orders.squareOff("api:squareoff"))
            }

            // ---- Market data
            get("/market/quote") {
                val keys = call.request.queryParameters.getAll("i").orEmpty()
                require(keys.isNotEmpty()) { "Pass one or more ?i=EXCHANGE:SYMBOL" }
                call.respond(s.market.quote(keys))
            }
            get("/market/indices") { call.respond(s.market.quote(INDICES.values)) }
            get("/market/candles") {
                val q = call.request.queryParameters
                val interval = q["interval"] ?: "day"
                val days = q["days"]?.toLong() ?: MarketDataService.defaultLookback(interval)
                call.respond(s.market.candles(q["exchange"] ?: "NSE", q["symbol"] ?: error("symbol required"), interval, days))
            }
            get("/options/chain") {
                val underlying = (call.request.queryParameters["underlying"] ?: "NIFTY").uppercase()
                val indexKey = INDICES[underlying] ?: throw IllegalArgumentException("Supported: ${INDICES.keys}")
                val expiry = call.request.queryParameters["expiry"]?.let { LocalDate.parse(it) }
                val width = call.request.queryParameters["width"]?.toInt() ?: 8
                val spot = s.market.ltp(indexKey)
                val chain = s.instruments.optionChain(underlying, spot, expiry, width)
                require(chain.isNotEmpty()) { "No options found for $underlying" }
                val quotes = s.market.quote(chain.map { "NFO:${it.tradingsymbol}" })
                val rows = chain.groupBy { it.strike }.map { (strike, legs) ->
                    val ce = legs.firstOrNull { it.instrumentType == "CE" }
                    val pe = legs.firstOrNull { it.instrumentType == "PE" }
                    val cq = ce?.let { quotes["NFO:${it.tradingsymbol}"] }
                    val pq = pe?.let { quotes["NFO:${it.tradingsymbol}"] }
                    OptionRow(strike, ce?.tradingsymbol, cq?.lastPrice, cq?.oi, pe?.tradingsymbol, pq?.lastPrice, pq?.oi)
                }.sortedBy { it.strike }
                call.respond(OptionChainResponse(underlying, spot, chain.first().expiry.toString(), chain.first().lotSize, rows))
            }

            // ---- Risk
            get("/risk") { call.respond(s.risk.status(s.broker.positions(), s.broker.orders().size)) }
            post("/risk/kill-switch") {
                val req = call.receive<KillSwitchRequest>()
                s.risk.setKillSwitch(req.on, req.reason)
                call.respond(s.risk.status(s.broker.positions(), s.broker.orders().size))
            }

            // ---- Strategies
            get("/strategies") { call.respond(s.runner.status()) }
            get("/strategies/signals") { call.respond(s.runner.events()) }
            post("/strategies/runner/start") { s.runner.start(); call.respond(HttpStatusCode.NoContent) }
            post("/strategies/runner/stop") { s.runner.stop(); call.respond(HttpStatusCode.NoContent) }
            post("/strategies/{id}") {
                val req = call.receive<StrategyUpdate>()
                if (req.autoTrade == true && s.config.mode == TradingMode.LIVE && !req.confirmLive) {
                    throw IllegalArgumentException("Enabling autoTrade in LIVE mode places real orders; resend with \"confirmLive\": true")
                }
                val updated = s.runner.configure(call.parameters["id"]!!, req.enabled, req.autoTrade)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("No strategy instance ${call.parameters["id"]}"))
                call.respond(updated)
            }
            post("/backtest") {
                val req = call.receive<BacktestRequest>()
                val strategy = StrategyRegistry.create(req.strategy)
                val candles = s.market.candles(req.exchange, req.symbol, req.interval, req.days ?: MarketDataService.defaultLookback(req.interval) * 3)
                val closed = StrategyRunner.closedCandles(candles, req.interval, s.clock.now())
                call.respond(Backtester.run(strategy, closed, req.params.copy(intraday = req.params.intraday && req.interval != "day")))
            }

            // ---- Claude research assistant (advisory only, never places orders)
            post("/ai/portfolio-review") {
                val req = call.receive<PortfolioReviewRequest>()
                val summary = PortfolioAnalytics.summarise(s.kite.holdings())
                val projection = req.projection?.let { PortfolioAnalytics.project(it, summary.currentValue) }
                call.respond(
                    s.advisor.reviewPortfolio(
                        TallyJson.encodeToString(com.tally.portfolio.PortfolioSummary.serializer(), summary),
                        projection?.let { TallyJson.encodeToString(com.tally.portfolio.ProjectionResult.serializer(), it.copy(years = it.years.filter { y -> y.year % 5 == 0 || y.year == it.years.size })) },
                        req.goals,
                    ),
                )
            }
            post("/ai/analyze") {
                val req = call.receive<AnalyzeRequest>()
                call.respond(s.advisor.analyzeSymbol("${req.exchange}:${req.symbol}", marketSnapshot(s, req.exchange, req.symbol).toString(), req.question))
            }

            // ---- Dashboard + AI market intelligence
            get("/dashboard") { call.respond(dashboard(s)) }
            get("/global") { call.respond(s.global.snapshot()) }
            get("/index-chart") {
                val index = (call.request.queryParameters["index"] ?: "NIFTY").uppercase()
                val key = INDICES[index] ?: if (index == "INDIAVIX") INDIA_VIX else throw IllegalArgumentException("Unknown index $index")
                val (exchange, symbol) = key.split(":", limit = 2)
                val candles = s.market.candles(exchange, symbol, "5minute", 5)
                val lastDay = candles.lastOrNull()?.time?.take(10)
                call.respond(candles.filter { it.time.startsWith(lastDay ?: "") }.map { ChartPoint(it.time.substring(11, 16), it.close) })
            }
            get("/ai/brief") {
                val brief = s.intel.latestBrief ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("No brief yet"))
                call.respond(brief)
            }
            post("/ai/brief/refresh") { call.respond(s.intel.refreshBrief(s.kite.isLoggedIn)) }
            get("/ai/ideas") {
                call.respond(buildJsonObject {
                    put("run", s.intel.latestRun?.let { TallyJson.encodeToJsonElement(IdeaRun.serializer(), it) } ?: JsonNull)
                    put("ideas", TallyJson.encodeToJsonElement(ListSerializer(TradeIdea.serializer()), s.ideas.recent(100)))
                    put("stats", TallyJson.encodeToJsonElement(ListSerializer(BucketStats.serializer()), s.ideas.stats()))
                })
            }
            post("/ai/ideas/refresh") { call.respond(s.aiScheduler.runIdeas()) }
            post("/ai/stock-ideas/refresh") { call.respond(s.aiScheduler.runStockIdeas()) }
            get("/ai/trader") { call.respond(s.aiTrader.status()) }
            post("/ai/autotrade") {
                val req = call.receive<AutoTradeRequest>()
                if (req.on && s.config.mode == TradingMode.LIVE && !req.confirmLive) {
                    throw IllegalArgumentException("Auto-trade in LIVE mode places real orders; resend with \"confirmLive\": true")
                }
                s.aiTrader.setAutoTrade(req.on)
                call.respond(s.aiTrader.status())
            }
            // Body {"ideaId": "..."} closes one trade; no body / no id closes all AI trades.
            post("/ai/trade/close") {
                val ref = runCatching { call.receive<TradeRef>() }.getOrDefault(TradeRef())
                s.aiTrader.closeNow(ref.ideaId); call.respond(s.aiTrader.status())
            }
            post("/ai/take") {
                val req = call.receive<TakeTradeRequest>()
                if (s.config.mode == TradingMode.LIVE && !req.confirmLive) {
                    throw IllegalArgumentException("LIVE mode places a real order; resend with \"confirmLive\": true")
                }
                val msg = s.aiTrader.take(req.ideaId, req.lots, req.targetMultiple, req.highRisk)
                call.respond(TakeTradeResponse(msg, s.aiTrader.status()))
            }
            // PAPER only: a synthetic idea at the current price so the take-trade / exit pipeline can be exercised
            // when Claude has no directional idea. Stop 0.25% away, target 0.5%, 60 minutes.
            post("/ai/test-idea") {
                check(s.config.mode == TradingMode.PAPER) { "Test ideas are only available in PAPER mode" }
                val req = call.receive<TestIdeaRequest>()
                val index = req.index.uppercase()
                val stock = index !in AI_INDICES && index in s.config.ai.stocks
                val key = AI_INDICES[index] ?: if (stock) "NSE:$index" else throw IllegalArgumentException("Use ${AI_INDICES.keys} or a watchlist stock: ${s.config.ai.stocks}")
                val action = req.action.uppercase().also { require(it == "LONG" || it == "SHORT") { "action must be LONG or SHORT" } }
                val spot = s.market.ltp(key)
                val dir = if (action == "LONG") 1 else -1
                val idea = TradeIdea(
                    id = "test-" + UUID.randomUUID().toString().take(6), createdAt = s.clock.now().toString(),
                    index = index, quoteKey = key, kind = if (stock) "STOCK" else "INDEX", action = action, confidence = 50,
                    entry = spot, stopLoss = Math.round(spot * (1 - dir * 0.0025) * 10) / 10.0,
                    target = Math.round(spot * (1 + dir * 0.005) * 10) / 10.0, horizonMinutes = 60,
                    rationale = "Paper test idea created from the dashboard (not an AI suggestion).",
                    invalidation = "Test only.", refPrice = spot, qualified = false, levelsOk = true, note = "PAPER test idea",
                )
                s.ideas.add(listOf(idea))
                call.respond(idea)
            }
            post("/ai/pending/cancel") {
                val ref = runCatching { call.receive<TradeRef>() }.getOrDefault(TradeRef())
                s.aiTrader.cancelPending(ref.ideaId); call.respond(s.aiTrader.status())
            }
            get("/ai/settings") { call.respond(s.tradeSettings.current) }
            put("/ai/settings") { call.respond(s.tradeSettings.update(call.receive<TradeSettings>())) }
        }
    }
}

/** Compact market context for Claude: quote, last 60 daily bars, and a few indicators. */
private suspend fun marketSnapshot(s: Services, exchange: String, symbol: String): JsonElement {
    val key = "$exchange:$symbol"
    val quote = s.market.quote(listOf(key))[key]
    val daily = s.market.candles(exchange, symbol, "day", 365)
    val close = daily.map { it.close }
    fun DoubleArray.lastOrNaN() = if (isEmpty()) Double.NaN else last()
    return buildJsonObject {
        put("symbol", key)
        quote?.let { put("quote", TallyJson.encodeToJsonElement(com.tally.kite.Quote.serializer(), it)) }
        put("ema20", Indicators.ema(close, 20).lastOrNaN().takeIf { !it.isNaN() })
        put("ema50", Indicators.ema(close, 50).lastOrNaN().takeIf { !it.isNaN() })
        put("ema200", Indicators.ema(close, 200).lastOrNaN().takeIf { !it.isNaN() })
        put("rsi14", Indicators.rsi(close, 14).lastOrNaN().takeIf { !it.isNaN() })
        put("atr14", Indicators.atr(daily.map { it.high }, daily.map { it.low }, close, 14).lastOrNaN().takeIf { !it.isNaN() })
        if (daily.isNotEmpty()) {
            put("high52w", daily.maxOf { it.high })
            put("low52w", daily.minOf { it.low })
        }
        putJsonArray("last60Daily") {
            daily.takeLast(60).forEach { c ->
                add(kotlinx.serialization.json.JsonPrimitive("${c.time.take(10)} O${c.open} H${c.high} L${c.low} C${c.close} V${c.volume}"))
            }
        }
    }
}

private val TILE_NAMES = linkedMapOf(
    "NSE:NIFTY 50" to "NIFTY 50", "NSE:NIFTY BANK" to "BANK NIFTY",
    "NSE:NIFTY FIN SERVICE" to "FIN NIFTY", INDIA_VIX to "INDIA VIX",
)

/**
 * Everything the dashboard polls, in one call. Each section fails independently so a Kite hiccup or a missing
 * login still renders the rest of the page.
 */
/**
 * Today's directional ideas that are still open and inside their time window. [highRisk] = false gives the ones
 * at/above the confidence bar (normal sections); true gives the ones below it (High-risk section, manual only).
 */
private fun liveIdeas(s: Services, ideas: List<TradeIdea>, highRisk: Boolean = false): List<TradeIdea> {
    val now = s.clock.now()
    return ideas.filter {
        val created = java.time.ZonedDateTime.parse(it.createdAt)
        val above = it.confidence >= s.config.ai.minConfidence || it.id.startsWith("test-")
        it.status == "OPEN" && it.levelsOk && (it.action == "LONG" || it.action == "SHORT") && above != highRisk &&
            created.toLocalDate() == now.toLocalDate() && now.isBefore(created.plusMinutes(it.horizonMinutes.toLong()))
    }.distinctBy { it.index }.take(if (highRisk) 6 else 10)
}

private suspend fun dashboard(s: Services): JsonObject {
    val loggedIn = s.kite.isLoggedIn
    suspend fun <T> safe(block: suspend () -> T): T? = runCatching { block() }.getOrNull()
    val ideas = s.ideas.recent(60)

    val tiles = if (!loggedIn) null else safe {
        s.market.quote(TILE_NAMES.keys).mapNotNull { (k, q) ->
            val prev = q.ohlc.close
            // Kite's net_change is often 0 in /quote; derive the day change from the previous close instead.
            val change = if (prev > 0) q.lastPrice - prev else 0.0
            IndexTile(k, TILE_NAMES[k] ?: k, q.lastPrice, prev, change, if (prev > 0) change / prev * 100 else 0.0, q.ohlc.high, q.ohlc.low)
        }.sortedBy { TILE_NAMES.keys.indexOf(it.key) }
    }
    val portfolio = if (!loggedIn) null else safe { PortfolioAnalytics.summarise(s.kite.holdings()) }
    val positions = if (!loggedIn) null else safe { s.broker.positions() }
    val ordersToday = if (!loggedIn) null else safe { s.broker.orders() }
    val cash = if (!loggedIn) null else safe {
        (s.kite.margins() as? JsonObject)?.get("equity")?.let { it as? JsonObject }?.get("net")
    }
    val spend = s.claude.spendToday()
    val trader = s.aiTrader.status()
    val now = s.clock.now()
    val aiRun = listOfNotNull(s.intel.latestRun, s.intel.latestStockRun).filter { it.botSays.isNotBlank() }.maxByOrNull { it.at }
    val tile = { key: String -> tiles?.firstOrNull { it.key == key } }
    val mood = BotMoodRules.mood(MoodInputs(
        now = now, marketOpen = s.clock.isOpen(now),
        niftyChangePct = tile("NSE:NIFTY 50")?.changePct, vix = tile(INDIA_VIX)?.ltp, vixChangePct = tile(INDIA_VIX)?.changePct,
        aiLine = aiRun?.botSays, aiLineAt = aiRun?.let { java.time.ZonedDateTime.parse(it.at) },
        openSymbol = when (trader.opens.size) { 0 -> null; 1 -> trader.opens[0].trade.symbol; else -> "${trader.opens.size} trades" },
        openPnl = trader.openPnl,
        lossLimitHit = trader.lossBudgetLeft < 1, profitGoalHit = trader.profitGoalReached,
        tradeableIdeas = liveIdeas(s, ideas).count { !it.id.startsWith("test-") }, minConfidence = s.config.ai.minConfidence,
    ))

    return buildJsonObject {
        put("status", buildJsonObject {
            put("mode", s.config.mode.name)
            put("kiteConfigured", s.config.kite.configured)
            put("kiteLoggedIn", loggedIn)
            put("claudeConfigured", s.config.anthropic.configured)
            put("marketOpen", s.clock.isOpen())
            put("now", s.clock.now().toString())
        })
        put("indices", tiles?.let { TallyJson.encodeToJsonElement(ListSerializer(IndexTile.serializer()), it) } ?: JsonNull)
        put("portfolio", portfolio?.let { TallyJson.encodeToJsonElement(com.tally.portfolio.PortfolioSummary.serializer(), it) } ?: JsonNull)
        put("positions", positions?.let { TallyJson.encodeToJsonElement(ListSerializer(com.tally.kite.Position.serializer()), it.filter { p -> p.quantity != 0 || p.pnl != 0.0 }) } ?: JsonNull)
        put("ordersToday", ordersToday?.size?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("equityNet", cash ?: JsonNull)
        put("risk", if (positions != null && ordersToday != null) TallyJson.encodeToJsonElement(com.tally.risk.RiskStatus.serializer(), s.risk.status(positions, ordersToday.size)) else JsonNull)
        put("ai", buildJsonObject {
            put("spendUsd", spend.usd)
            put("calls", spend.calls)
            put("budgetUsd", s.config.ai.dailyBudgetUsd)
            put("overBudget", s.aiScheduler.overBudget())
            put("minConfidence", s.config.ai.minConfidence)
            put("schedulerEnabled", s.config.ai.scheduler)
            put("lastError", s.aiScheduler.lastError)
            put("briefAt", s.intel.latestBrief?.at)
            put("run", s.intel.latestRun?.let { TallyJson.encodeToJsonElement(IdeaRun.serializer(), it) } ?: JsonNull)
            put("stockRun", s.intel.latestStockRun?.let { TallyJson.encodeToJsonElement(IdeaRun.serializer(), it) } ?: JsonNull)
            put("highRiskIds", TallyJson.encodeToJsonElement(ListSerializer(String.serializer()), liveIdeas(s, ideas, highRisk = true).map { it.id }))
            put("stockWatchlist", TallyJson.encodeToJsonElement(ListSerializer(String.serializer()), s.config.ai.stocks))
            put("ideas", TallyJson.encodeToJsonElement(ListSerializer(TradeIdea.serializer()), ideas))
            put("stats", TallyJson.encodeToJsonElement(ListSerializer(BucketStats.serializer()), s.ideas.stats()))
            put("trader", TallyJson.encodeToJsonElement(AiTraderStatus.serializer(), trader))
            put("mood", TallyJson.encodeToJsonElement(BotMood.serializer(), mood))
            put("plans", if (loggedIn) TallyJson.encodeToJsonElement(MapSerializer(String.serializer(), OptionPlan.serializer()), s.aiTrader.plans(liveIdeas(s, ideas) + liveIdeas(s, ideas, highRisk = true))) else JsonNull)
        })
    }
}
