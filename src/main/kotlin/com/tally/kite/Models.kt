package com.tally.kite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class Side { BUY, SELL;
    val opposite get() = if (this == BUY) SELL else BUY
}

@Serializable
enum class Product { MIS, CNC, NRML }

@Serializable
enum class OrderType {
    MARKET, LIMIT, SL,
    @SerialName("SL-M") SL_M;

    val wire get() = if (this == SL_M) "SL-M" else name
}

@Serializable
data class OrderRequest(
    val exchange: String,
    val tradingsymbol: String,
    val transactionType: Side,
    val quantity: Int,
    val product: Product,
    val orderType: OrderType = OrderType.MARKET,
    val price: Double? = null,
    val triggerPrice: Double? = null,
    val tag: String? = null,
    val variety: String = "regular",
) {
    val key get() = "$exchange:$tradingsymbol"
}

@Serializable
data class Order(
    val orderId: String,
    val status: String,
    val tradingsymbol: String,
    val exchange: String,
    val transactionType: String,
    val orderType: String,
    val product: String,
    val quantity: Int,
    val filledQuantity: Int = 0,
    val price: Double = 0.0,
    val triggerPrice: Double = 0.0,
    val averagePrice: Double = 0.0,
    val orderTimestamp: String? = null,
    val statusMessage: String? = null,
    val tag: String? = null,
    val variety: String? = null,
)

@Serializable
data class OrderResult(val orderId: String)

@Serializable
data class Holding(
    val tradingsymbol: String,
    val exchange: String,
    val instrumentToken: Long = 0,
    val isin: String? = null,
    val quantity: Int = 0,
    val t1Quantity: Int = 0,
    val averagePrice: Double = 0.0,
    val lastPrice: Double = 0.0,
    val closePrice: Double = 0.0,
    val pnl: Double = 0.0,
    val dayChange: Double = 0.0,
    val dayChangePercentage: Double = 0.0,
) {
    val totalQuantity get() = quantity + t1Quantity
}

@Serializable
data class Position(
    val tradingsymbol: String,
    val exchange: String,
    val instrumentToken: Long = 0,
    val product: String,
    val quantity: Int,
    val averagePrice: Double = 0.0,
    val lastPrice: Double = 0.0,
    val pnl: Double = 0.0,
    val realised: Double = 0.0,
    val unrealised: Double = 0.0,
    val buyQuantity: Int = 0,
    val sellQuantity: Int = 0,
    val multiplier: Double = 1.0,
) {
    val key get() = "$exchange:$tradingsymbol"
}

@Serializable
data class Positions(val net: List<Position> = emptyList(), val day: List<Position> = emptyList())

@Serializable
data class Ohlc(val open: Double = 0.0, val high: Double = 0.0, val low: Double = 0.0, val close: Double = 0.0)

@Serializable
data class Quote(
    val instrumentToken: Long = 0,
    val lastPrice: Double = 0.0,
    val volume: Long = 0,
    val netChange: Double = 0.0,
    val oi: Double = 0.0,
    val ohlc: Ohlc = Ohlc(),
    val depth: Depth? = null,
) {
    /** Best bid/ask spread as % of the mid price, or null when the book is empty. */
    val spreadPct: Double?
        get() {
            val bid = depth?.buy?.firstOrNull()?.price?.takeIf { it > 0 } ?: return null
            val ask = depth.sell.firstOrNull()?.price?.takeIf { it > 0 } ?: return null
            return (ask - bid) / ((ask + bid) / 2) * 100
        }
}

@Serializable
data class DepthLevel(val price: Double = 0.0, val quantity: Long = 0, val orders: Int = 0)

@Serializable
data class Depth(val buy: List<DepthLevel> = emptyList(), val sell: List<DepthLevel> = emptyList())

@Serializable
data class Ltp(val instrumentToken: Long = 0, val lastPrice: Double = 0.0)

@Serializable
data class Profile(
    val userId: String,
    val userName: String? = null,
    val email: String? = null,
    val broker: String? = null,
    val exchanges: List<String> = emptyList(),
    val products: List<String> = emptyList(),
)

@Serializable
data class SessionResponse(
    val userId: String,
    val userName: String? = null,
    val accessToken: String,
)

@Serializable
data class Candle(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val oi: Long? = null,
)

@Serializable
data class HistoricalData(val candles: List<List<JsonElement>>)

data class Instrument(
    val instrumentToken: Long,
    val tradingsymbol: String,
    val name: String,
    val expiry: java.time.LocalDate?,
    val strike: Double,
    val tickSize: Double,
    val lotSize: Int,
    val instrumentType: String,
    val segment: String,
    val exchange: String,
)

class KiteException(
    message: String,
    val errorType: String?,
    val httpStatus: Int,
) : RuntimeException(message) {
    val isTokenError get() = errorType == "TokenException"
}
