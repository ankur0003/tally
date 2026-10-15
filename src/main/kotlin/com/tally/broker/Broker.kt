package com.tally.broker

import com.tally.config.TradingMode
import com.tally.kite.KiteClient
import com.tally.kite.Order
import com.tally.kite.OrderRequest
import com.tally.kite.Position

interface Broker {
    val mode: TradingMode
    suspend fun placeOrder(req: OrderRequest): String
    suspend fun cancelOrder(orderId: String, variety: String = "regular")
    suspend fun orders(): List<Order>

    /** Net positions, including ones closed today (quantity 0) so realised P&L is visible. */
    suspend fun positions(): List<Position>
}

class LiveBroker(private val kite: KiteClient) : Broker {
    override val mode = TradingMode.LIVE
    override suspend fun placeOrder(req: OrderRequest) = kite.placeOrder(req)
    override suspend fun cancelOrder(orderId: String, variety: String) {
        kite.cancelOrder(orderId, variety)
    }
    override suspend fun orders() = kite.orders()
    override suspend fun positions() = kite.positions().net
}
