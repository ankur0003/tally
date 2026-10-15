package com.tally.market

import com.tally.kite.Instrument
import com.tally.kite.KiteClient
import com.tally.util.IST
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.math.abs

enum class OptionType { CE, PE }

/**
 * Daily-cached Kite instrument master. Lot sizes, expiries and strikes all come from here; never hardcode
 * them, NSE revises lot sizes and expiry schedules regularly.
 */
class InstrumentService(private val kite: KiteClient, dataDir: Path) {
    private val log = LoggerFactory.getLogger(InstrumentService::class.java)
    private val dir = dataDir.resolve("instruments")
    private val mutex = Mutex()
    private val byExchange = mutableMapOf<String, Pair<LocalDate, List<Instrument>>>()

    suspend fun instruments(exchange: String): List<Instrument> = mutex.withLock {
        val today = LocalDate.now(IST)
        byExchange[exchange]?.takeIf { it.first == today }?.let { return it.second }

        val file = dir.resolve("$exchange-$today.csv")
        val csv = if (Files.exists(file)) {
            Files.readString(file)
        } else {
            log.info("Downloading {} instrument dump", exchange)
            kite.instrumentsCsv(exchange).also {
                Files.createDirectories(dir)
                Files.writeString(file, it)
                pruneOld(exchange, today)
            }
        }
        val parsed = parseCsv(csv)
        byExchange[exchange] = today to parsed
        parsed
    }

    suspend fun find(exchange: String, tradingsymbol: String): Instrument? =
        instruments(exchange).firstOrNull { it.tradingsymbol == tradingsymbol }

    suspend fun require(exchange: String, tradingsymbol: String): Instrument =
        find(exchange, tradingsymbol) ?: throw IllegalArgumentException("Unknown instrument $exchange:$tradingsymbol")

    /** All option contracts for an underlying (e.g. "NIFTY", "BANKNIFTY") expiring on/after today. */
    suspend fun options(underlying: String): List<Instrument> {
        val today = LocalDate.now(IST)
        return instruments("NFO").filter {
            it.name == underlying && it.segment == "NFO-OPT" && it.expiry != null && !it.expiry.isBefore(today)
        }
    }

    suspend fun expiries(underlying: String): List<LocalDate> =
        options(underlying).mapNotNull { it.expiry }.distinct().sorted()

    /** Strikes around ATM for one expiry (nearest if null), both CE and PE, sorted by strike. */
    suspend fun optionChain(underlying: String, spot: Double, expiry: LocalDate? = null, width: Int = 10): List<Instrument> {
        val opts = options(underlying)
        val exp = expiry ?: opts.mapNotNull { it.expiry }.minOrNull() ?: return emptyList()
        val forExpiry = opts.filter { it.expiry == exp }
        val strikes = forExpiry.map { it.strike }.distinct().sorted()
        val atmIdx = strikes.indices.minByOrNull { abs(strikes[it] - spot) } ?: return emptyList()
        val window = strikes.subList((atmIdx - width).coerceAtLeast(0), (atmIdx + width + 1).coerceAtMost(strikes.size)).toSet()
        return forExpiry.filter { it.strike in window }.sortedWith(compareBy({ it.strike }, { it.instrumentType }))
    }

    /**
     * ATM option on the nearest expiry. On expiry day itself we roll to the next expiry: gamma and
     * theta on a same-day contract are too violent for a polling bot.
     */
    suspend fun atmOption(underlying: String, spot: Double, type: OptionType): Instrument {
        val today = LocalDate.now(IST)
        val expiries = expiries(underlying)
        val expiry = expiries.firstOrNull { it.isAfter(today) } ?: expiries.firstOrNull()
            ?: throw IllegalStateException("No live $underlying options found")
        return optionChain(underlying, spot, expiry, width = 0).firstOrNull { it.instrumentType == type.name }
            ?: throw IllegalStateException("No ATM $type for $underlying @ $spot")
    }

    private fun pruneOld(exchange: String, today: LocalDate) = runCatching {
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().startsWith("$exchange-") && !it.fileName.toString().contains(today.toString()) }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        fun parseCsv(csv: String): List<Instrument> {
            val lines = csv.lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return emptyList()
            val header = splitCsv(lines.next())
            val idx = header.withIndex().associate { it.value to it.index }
            fun List<String>.col(name: String) = idx[name]?.let { getOrNull(it) }.orEmpty()

            val out = ArrayList<Instrument>(100_000)
            for (line in lines) {
                val f = splitCsv(line)
                out += Instrument(
                    instrumentToken = f.col("instrument_token").toLongOrNull() ?: continue,
                    tradingsymbol = f.col("tradingsymbol"),
                    name = f.col("name"),
                    expiry = f.col("expiry").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                    strike = f.col("strike").toDoubleOrNull() ?: 0.0,
                    tickSize = f.col("tick_size").toDoubleOrNull() ?: 0.05,
                    lotSize = f.col("lot_size").toIntOrNull() ?: 1,
                    instrumentType = f.col("instrument_type"),
                    segment = f.col("segment"),
                    exchange = f.col("exchange"),
                )
            }
            return out
        }

        private fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            for (c in line) {
                when {
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
            }
            out += sb.toString()
            return out
        }
    }
}
