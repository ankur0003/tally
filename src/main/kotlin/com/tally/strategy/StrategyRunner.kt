package com.tally.strategy

import com.tally.config.ExecutionMode
import com.tally.config.RiskConfig
import com.tally.config.StrategyInstanceConfig
import com.tally.kite.*
import com.tally.market.InstrumentService
import com.tally.market.MarketDataService
import com.tally.market.OptionType
import com.tally.risk.OrderService
import com.tally.util.MarketClock
import com.tally.util.TallyJson
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Serializable
data class OpenTrade(
    val exchange: String,
    val tradingsymbol: String,
    val side: Side,
    val quantity: Int,
    val product: Product,
    val entryPrice: Double,
    val entryOrderId: String,
    val openedAt: String,
) {
    val key get() = "$exchange:$tradingsymbol"
}

@Serializable
data class SignalEvent(
    val at: String,
    val instanceId: String,
    val signal: Signal,
    val price: Double,
    val action: String,
)

@Serializable
data class InstanceStatus(
    val id: String,
    val strategy: String,
    val symbol: String,
    val interval: String,
    val execution: ExecutionMode,
    val enabled: Boolean,
    val autoTrade: Boolean,
    val lastCandle: String?,
    val lastSignal: Signal?,
    val openTrade: OpenTrade?,
    val lastError: String?,
)

private class Instance(val cfg: StrategyInstanceConfig) {
    val strategy = StrategyRegistry.create(cfg.strategy)
    @Volatile var enabled = cfg.enabled
    @Volatile var autoTrade = cfg.autoTrade
    @Volatile var lastCandle: String? = null
    @Volatile var lastSignal: Signal? = null
    @Volatile var lastError: String? = null
}

class StrategyRunner(
    configs: List<StrategyInstanceConfig>,
    private val pollSeconds: Long,
    private val riskCfg: RiskConfig,
    private val market: MarketDataService,
    private val instruments: InstrumentService,
    private val orders: OrderService,
    private val clock: MarketClock,
    private val isReady: () -> Boolean,
    dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(StrategyRunner::class.java)
    private val instances = configs.associate { it.id to Instance(it) }
    private val stateFile = dataDir.resolve("runner_trades.json")
    private val trades = ConcurrentHashMap<String, OpenTrade>(loadTrades())
    private val events = ConcurrentLinkedDeque<SignalEvent>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val running get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch {
            log.info("Strategy runner started ({} instances, every {}s)", instances.size, pollSeconds)
            while (isActive) {
                if (isReady() && clock.isOpen()) {
                    instances.values.filter { it.enabled }.forEach { inst ->
                        try {
                            tick(inst)
                            inst.lastError = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            inst.lastError = e.message
                            log.warn("[{}] tick failed: {}", inst.cfg.id, e.message)
                        }
                    }
                }
                delay(pollSeconds * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        log.info("Strategy runner stopped")
    }

    fun shutdown() = scope.cancel()

    fun status(): List<InstanceStatus> = instances.values.map {
        InstanceStatus(
            it.cfg.id, it.cfg.strategy, it.cfg.symbol, it.cfg.interval, it.cfg.execution, it.enabled,
            it.autoTrade, it.lastCandle, it.lastSignal, trades[it.cfg.id], it.lastError,
        )
    }

    fun events(): List<SignalEvent> = events.toList().asReversed()

    fun configure(id: String, enabled: Boolean?, autoTrade: Boolean?): InstanceStatus? {
        val inst = instances[id] ?: return null
        enabled?.let { inst.enabled = it }
        autoTrade?.let { inst.autoTrade = it }
        log.info("[{}] enabled={} autoTrade={}", id, inst.enabled, inst.autoTrade)
        return status().first { it.id == id }
    }

    private suspend fun tick(inst: Instance) {
        val cfg = inst.cfg
        val now = clock.now()

        trades[cfg.id]?.let { trade ->
            val intraday = cfg.execution != ExecutionMode.EQUITY_SWING
            if (intraday && clock.isAfter(riskCfg.intradaySquareOff, now)) {
                exit(inst, trade, "EOD square-off")
                return
            }
            checkStops(inst, trade)
        }
        if (cfg.execution != ExecutionMode.EQUITY_SWING && clock.isAfter(riskCfg.intradayEntryCutoff, now)) return

        val candles = market.candles(cfg.exchange, cfg.symbol, cfg.interval, MarketDataService.defaultLookback(cfg.interval))
        val closed = closedCandles(candles, cfg.interval, now)
        val last = closed.lastOrNull() ?: return
        if (last.time == inst.lastCandle) return // already evaluated this bar
        inst.lastCandle = last.time

        val signal = inst.strategy.evaluate(closed)
        inst.lastSignal = signal
        if (signal == Signal.HOLD) return

        val open = trades[cfg.id]
        val action = when {
            open != null && isSameDirection(cfg, open, signal) -> "hold existing"
            !inst.autoTrade -> "signal only (autoTrade off)"
            else -> {
                if (open != null) exit(inst, open, "Opposite signal $signal")
                if (trades[cfg.id] == null) enter(inst, signal, last.close) else "exit failed, not re-entering"
            }
        }
        record(cfg.id, signal, last.close, action)
    }

    private suspend fun checkStops(inst: Instance, trade: OpenTrade) {
        val cfg = inst.cfg
        if (cfg.stopLossPct <= 0 && cfg.targetPct <= 0) return
        val ltp = market.ltp(trade.key)
        val movePct = (ltp - trade.entryPrice) / trade.entryPrice * 100 * if (trade.side == Side.BUY) 1 else -1
        when {
            cfg.stopLossPct > 0 && movePct <= -cfg.stopLossPct -> exit(inst, trade, "Stop-loss %.1f%%".format(movePct))
            cfg.targetPct > 0 && movePct >= cfg.targetPct -> exit(inst, trade, "Target %.1f%%".format(movePct))
        }
    }

    private suspend fun enter(inst: Instance, signal: Signal, underlyingPrice: Double): String {
        val cfg = inst.cfg
        val req = when (cfg.execution) {
            ExecutionMode.INDEX_OPTIONS -> {
                val underlying = cfg.optionsUnderlying ?: error("optionsUnderlying required for INDEX_OPTIONS")
                val spot = runCatching { market.ltp("${cfg.exchange}:${cfg.symbol}") }.getOrDefault(underlyingPrice)
                val opt = instruments.atmOption(underlying, spot, if (signal == Signal.LONG) OptionType.CE else OptionType.PE)
                OrderRequest("NFO", opt.tradingsymbol, Side.BUY, cfg.lots * opt.lotSize, Product.MIS, tag = cfg.id)
            }
            ExecutionMode.EQUITY_INTRADAY -> OrderRequest(
                cfg.exchange, cfg.symbol, if (signal == Signal.LONG) Side.BUY else Side.SELL, cfg.quantity, Product.MIS, tag = cfg.id,
            )
            ExecutionMode.EQUITY_SWING -> {
                if (signal != Signal.LONG) return "short signal ignored for swing (long-only)"
                OrderRequest(cfg.exchange, cfg.symbol, Side.BUY, cfg.quantity, Product.CNC, tag = cfg.id)
            }
        }
        val outcome = orders.place(req, "strategy:${cfg.id}")
        if (!outcome.placed) return "entry rejected: ${outcome.reason}"

        val fill = filledPrice(outcome.orderId!!) ?: market.ltp(req.key)
        trades[cfg.id] = OpenTrade(
            req.exchange, req.tradingsymbol, req.transactionType, req.quantity, req.product, fill,
            outcome.orderId, clock.now().toString(),
        )
        saveTrades()
        return "entered ${req.transactionType} ${req.key} x${req.quantity} @ %.2f".format(fill)
    }

    private suspend fun exit(inst: Instance, trade: OpenTrade, reason: String) {
        val outcome = orders.place(
            OrderRequest(trade.exchange, trade.tradingsymbol, trade.side.opposite, trade.quantity, trade.product, tag = inst.cfg.id),
            "strategy:${inst.cfg.id}",
        )
        val price = runCatching { market.ltp(trade.key) }.getOrDefault(0.0)
        if (outcome.placed) {
            trades.remove(inst.cfg.id)
            saveTrades()
            record(inst.cfg.id, Signal.HOLD, price, "exited ${trade.key}: $reason")
        } else {
            record(inst.cfg.id, Signal.HOLD, price, "EXIT FAILED ${trade.key} ($reason): ${outcome.reason}")
        }
    }

    private suspend fun filledPrice(orderId: String): Double? =
        orders.broker.orders().firstOrNull { it.orderId == orderId }?.averagePrice?.takeIf { it > 0 }

    private fun isSameDirection(cfg: StrategyInstanceConfig, trade: OpenTrade, signal: Signal): Boolean = when (cfg.execution) {
        ExecutionMode.INDEX_OPTIONS -> trade.tradingsymbol.endsWith(if (signal == Signal.LONG) "CE" else "PE")
        else -> (trade.side == Side.BUY) == (signal == Signal.LONG)
    }

    private fun record(id: String, signal: Signal, price: Double, action: String) {
        log.info("[{}] {} @ {} -> {}", id, signal, "%.2f".format(price), action)
        events.addLast(SignalEvent(clock.now().toString(), id, signal, price, action))
        while (events.size > 500) events.pollFirst()
    }

    private fun loadTrades(): Map<String, OpenTrade> = runCatching {
        if (Files.exists(stateFile)) TallyJson.decodeFromString(tradeMapSerializer, Files.readString(stateFile)) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun saveTrades() {
        Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, TallyJson.encodeToString(tradeMapSerializer, trades.toMap()))
    }

    companion object {
        private val tradeMapSerializer = MapSerializer(String.serializer(), OpenTrade.serializer())

        /** Drops the still-forming bar: Kite returns the current candle while it is being built. */
        fun closedCandles(candles: List<Candle>, interval: String, now: ZonedDateTime): List<Candle> {
            val last = candles.lastOrNull() ?: return candles
            val start = KiteClient.parseCandleTime(last.time)
            val end = when (interval) {
                "day" -> start.toLocalDate().atTime(15, 30).atZone(start.zone)
                "minute" -> start.plusMinutes(1)
                "60minute" -> start.plusMinutes(60)
                else -> start.plusMinutes(interval.removeSuffix("minute").toLongOrNull() ?: 1)
            }
            return if (now.isBefore(end)) candles.dropLast(1) else candles
        }
    }
}
