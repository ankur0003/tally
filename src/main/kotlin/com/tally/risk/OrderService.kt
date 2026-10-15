package com.tally.risk

import com.tally.broker.Broker
import com.tally.kite.*
import com.tally.market.MarketDataService
import com.tally.util.IST
import com.tally.util.TallyJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs

@Serializable
data class OrderOutcome(
    val placed: Boolean,
    val orderId: String?,
    val reason: String,
    val mode: String,
    val request: OrderRequest,
)

@Serializable
private data class AuditEntry(val at: String, val source: String, val outcome: OrderOutcome)

/**
 * The single path to the broker. Serialises order placement so concurrent strategies can't race past
 * the position/order-count limits, and writes an append-only audit log per day.
 */
class OrderService(
    val broker: Broker,
    private val risk: RiskManager,
    private val market: MarketDataService,
    dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(OrderService::class.java)
    private val auditDir = dataDir.resolve("audit")
    private val mutex = Mutex()

    suspend fun place(req: OrderRequest, source: String): OrderOutcome = mutex.withLock {
        val outcome = try {
            val refPrice = req.price?.takeIf { it > 0 } ?: market.ltp(req.key)
            val positions = broker.positions()
            val ordersToday = broker.orders().size
            val decision = risk.check(req, refPrice, positions, ordersToday)
            if (!decision.approved) {
                OrderOutcome(false, null, decision.reason, broker.mode.name, req)
            } else {
                val id = broker.placeOrder(req)
                log.info("[{}] {} {} {} x{} -> {}", broker.mode, source, req.transactionType, req.key, req.quantity, id)
                OrderOutcome(true, id, decision.reason, broker.mode.name, req)
            }
        } catch (e: Exception) {
            log.error("Order failed {} {}: {}", req.transactionType, req.key, e.message)
            OrderOutcome(false, null, "Error: ${e.message}", broker.mode.name, req)
        }
        audit(source, outcome)
        outcome
    }

    /** Market-exits every open position matching [filter]. Used for EOD square-off and the panic button. */
    suspend fun squareOff(source: String, filter: (Position) -> Boolean = { true }): List<OrderOutcome> =
        broker.positions().filter { it.quantity != 0 && filter(it) }.map { p ->
            place(
                OrderRequest(
                    exchange = p.exchange, tradingsymbol = p.tradingsymbol,
                    transactionType = if (p.quantity > 0) Side.SELL else Side.BUY,
                    quantity = abs(p.quantity), product = Product.valueOf(p.product),
                    orderType = OrderType.MARKET, tag = "squareoff",
                ),
                source,
            )
        }

    private fun audit(source: String, outcome: OrderOutcome) = runCatching {
        Files.createDirectories(auditDir)
        val line = TallyJson.encodeToString(AuditEntry.serializer(), AuditEntry(ZonedDateTime.now(IST).toString(), source, outcome))
        Files.writeString(
            auditDir.resolve("${LocalDate.now(IST)}.jsonl"), line + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }.onFailure { log.error("Audit write failed", it) }
}
