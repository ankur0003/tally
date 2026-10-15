package com.tally.broker

import com.tally.config.PaperConfig
import com.tally.config.TradingMode
import com.tally.kite.*
import com.tally.util.IST
import com.tally.util.TallyJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

@Serializable
data class PaperPosition(
    val exchange: String,
    val tradingsymbol: String,
    val product: Product,
    var quantity: Int = 0,
    var averagePrice: Double = 0.0,
    var realised: Double = 0.0,
    var buyQuantity: Int = 0,
    var sellQuantity: Int = 0,
) {
    val key get() = "$exchange:$tradingsymbol"

    /** Applies a fill using signed-quantity averaging; realises P&L on the closing portion. */
    fun fill(side: Side, qty: Int, price: Double) {
        val signed = if (side == Side.BUY) qty else -qty
        if (side == Side.BUY) buyQuantity += qty else sellQuantity += qty
        if (quantity == 0 || quantity.sign == signed.sign) {
            averagePrice = (averagePrice * abs(quantity) + price * qty) / (abs(quantity) + qty)
            quantity += signed
            return
        }
        val closing = min(abs(quantity), qty)
        realised += closing * (price - averagePrice) * quantity.sign
        quantity += signed
        when {
            quantity == 0 -> averagePrice = 0.0
            quantity.sign == signed.sign -> averagePrice = price // flipped through zero
        }
    }
}

@Serializable
data class PaperState(
    var day: String,
    var cash: Double,
    var seq: Long = 0,
    val orders: MutableList<Order> = mutableListOf(),
    val positions: MutableMap<String, PaperPosition> = mutableMapOf(),
    /** The order request behind each still-open paper order, keyed by order id. */
    val pending: MutableMap<String, OrderRequest> = mutableMapOf(),
)

/**
 * Simulated broker: fills against live Kite LTP with a slippage haircut. Good enough to validate
 * strategy plumbing and risk rules end-to-end before any real money moves. Does not model
 * brokerage, STT, exchange charges, or partial fills.
 */
class PaperBroker(
    private val cfg: PaperConfig,
    private val ltp: suspend (Collection<String>) -> Map<String, Double>,
    dataDir: Path,
) : Broker {
    override val mode = TradingMode.PAPER

    private val log = LoggerFactory.getLogger(PaperBroker::class.java)
    private val file = dataDir.resolve("paper_state.json")
    private val mutex = Mutex()
    private val state: PaperState = load()

    override suspend fun placeOrder(req: OrderRequest): String = mutex.withLock {
        rollDay()
        require(req.quantity > 0) { "quantity must be positive" }
        val id = "PAPER-${++state.seq}"
        val order = Order(
            orderId = id, status = "OPEN", tradingsymbol = req.tradingsymbol, exchange = req.exchange,
            transactionType = req.transactionType.name, orderType = req.orderType.wire, product = req.product.name,
            quantity = req.quantity, price = req.price ?: 0.0, triggerPrice = req.triggerPrice ?: 0.0,
            orderTimestamp = ZonedDateTime.now(IST).toString(), tag = req.tag, variety = req.variety,
        )
        state.orders += order
        state.pending[id] = req
        processPending()
        save()
        id
    }

    override suspend fun cancelOrder(orderId: String, variety: String) = mutex.withLock {
        val req = state.pending.remove(orderId) ?: throw IllegalArgumentException("No open paper order $orderId")
        replaceOrder(orderId) { it.copy(status = "CANCELLED", statusMessage = "Cancelled") }
        log.info("Paper order {} cancelled ({})", orderId, req.key)
        save()
    }

    override suspend fun orders(): List<Order> = mutex.withLock {
        rollDay()
        processPending()
        state.orders.toList()
    }

    override suspend fun positions(): List<Position> = mutex.withLock {
        rollDay()
        processPending()
        val open = state.positions.values.filter { it.quantity != 0 }.map { it.key }
        val prices = if (open.isEmpty()) emptyMap() else runCatching { ltp(open) }.getOrDefault(emptyMap())
        state.positions.values.map { p ->
            val last = prices[p.key] ?: p.averagePrice
            val unrealised = (last - p.averagePrice) * p.quantity
            Position(
                tradingsymbol = p.tradingsymbol, exchange = p.exchange, product = p.product.name,
                quantity = p.quantity, averagePrice = p.averagePrice, lastPrice = last,
                pnl = p.realised + unrealised, realised = p.realised, unrealised = unrealised,
                buyQuantity = p.buyQuantity, sellQuantity = p.sellQuantity,
            )
        }
    }

    suspend fun cash(): Double = mutex.withLock { state.cash }

    /** Must be called under [mutex]. Tries to fill every open order at the current LTP. */
    private suspend fun processPending() {
        if (state.pending.isEmpty()) return
        val prices = runCatching { ltp(state.pending.values.map { it.key }.distinct()) }.getOrElse {
            log.warn("Paper fills skipped, no LTP: {}", it.message)
            return
        }
        for ((id, req) in state.pending.toMap()) {
            val last = prices[req.key] ?: continue
            val fillPrice = fillPrice(req, last) ?: continue
            val pos = state.positions.getOrPut(req.key) { PaperPosition(req.exchange, req.tradingsymbol, req.product) }
            pos.fill(req.transactionType, req.quantity, fillPrice)
            state.cash += if (req.transactionType == Side.BUY) -fillPrice * req.quantity else fillPrice * req.quantity
            state.pending.remove(id)
            replaceOrder(id) {
                it.copy(status = "COMPLETE", filledQuantity = req.quantity, averagePrice = fillPrice)
            }
            log.info("Paper fill {} {} {} x{} @ {}", id, req.transactionType, req.key, req.quantity, "%.2f".format(fillPrice))
        }
        save()
    }

    /** Returns the fill price if the order is executable at [last], else null (stays open). */
    private fun fillPrice(req: OrderRequest, last: Double): Double? {
        val slip = last * cfg.slippageBps / 10_000
        val buy = req.transactionType == Side.BUY
        val market = if (buy) last + slip else last - slip
        return when (req.orderType) {
            OrderType.MARKET -> market
            OrderType.LIMIT -> {
                val limit = req.price ?: return market
                if (buy && last <= limit || !buy && last >= limit) (if (buy) min(market, limit) else maxOf(market, limit)) else null
            }
            OrderType.SL, OrderType.SL_M -> {
                val trigger = req.triggerPrice ?: return null
                if (buy && last >= trigger || !buy && last <= trigger) market else null
            }
        }
    }

    private fun replaceOrder(id: String, f: (Order) -> Order) {
        val i = state.orders.indexOfFirst { it.orderId == id }
        if (i >= 0) state.orders[i] = f(state.orders[i])
    }

    /** New trading day: drop yesterday's order book and flat positions, keep carried CNC/NRML holdings. */
    private fun rollDay() {
        val today = LocalDate.now(IST).toString()
        if (state.day == today) return
        state.day = today
        state.orders.clear()
        state.pending.clear()
        state.positions.values.removeIf { it.quantity == 0 }
        state.positions.values.forEach { it.realised = 0.0; it.buyQuantity = 0; it.sellQuantity = 0 }
        save()
    }

    private fun load(): PaperState = runCatching {
        if (Files.exists(file)) TallyJson.decodeFromString(PaperState.serializer(), Files.readString(file)) else null
    }.getOrNull() ?: PaperState(day = LocalDate.now(IST).toString(), cash = cfg.startingCash)

    private fun save() {
        Files.createDirectories(file.parent)
        Files.writeString(file, TallyJson.encodeToString(PaperState.serializer(), state))
    }
}
