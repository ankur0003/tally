package com.tally.ai

import com.tally.config.AiConfig
import com.tally.market.GlobalMarkets
import com.tally.util.IST
import com.tally.util.MarketClock
import com.tally.util.TallyJson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZonedDateTime

/** Claude's post-mortem of one closed trade. */
@Serializable
data class TradeReview(
    /** GOOD_WIN, GOOD_LOSS (right process, unlucky), BAD_WIN (lucky) or BAD_LOSS (a mistake). */
    val verdict: String,
    val whatHappened: String,
    val mistakes: List<String>,
    val lesson: String,
    val at: String,
    val costUsd: Double,
)

/** Everything known about one AI trade: the setup and market when it opened, how it ended, and the review. */
@Serializable
data class JournalEntry(
    val ideaId: String,
    val day: String,
    val source: String,
    val underlying: String,
    val kind: String,
    val action: String,
    val confidence: Int,
    val symbol: String,
    val quantity: Int,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val openedAt: String,
    val ideaEntry: Double,
    val ideaStop: Double,
    val ideaTarget: Double,
    val rationale: String,
    val invalidation: String,
    /** The fund manager's reason for taking it (AI trader trades only). */
    val deskReason: String? = null,
    /** Playbook version in force when the trade was taken, to see whether lessons changed behaviour. */
    val playbookVersion: Int = 0,
    /** Underlying chart, India indices, global markets and a brief excerpt at entry. */
    val entryContext: JsonObject? = null,
    val exitPrice: Double? = null,
    val exitReason: String? = null,
    val closedAt: String? = null,
    val pnl: Double? = null,
    val peakPrice: Double? = null,
    val finalStop: Double? = null,
    /** Underlying chart and India indices at exit: what the market did during the trade. */
    val exitContext: JsonObject? = null,
    val review: TradeReview? = null,
) {
    fun oneLine() = "%s %s %s %s %d%% [%s] %s P&L %s%s".format(
        openedAt.take(16).replace('T', ' '), underlying, action, symbol, confidence, source,
        exitReason ?: "OPEN", pnl?.let { "₹%,.0f".format(it) } ?: "-",
        review?.let { " | ${it.verdict}: ${it.lesson}" }.orEmpty(),
    )
}

@Serializable
data class Playbook(
    val version: Int = 0,
    val updatedAt: String = "",
    /** Short, concrete trading rules learned from reviewed trades; fed to every idea and fund-manager call. */
    val rules: List<String> = emptyList(),
    val tradesReviewed: Int = 0,
    val daysReviewed: List<String> = emptyList(),
)

/** End-of-day debrief, kept so later days can see how earlier ones went. */
@Serializable
data class DayDigest(val day: String, val verdict: String, val summary: String, val lesson: String, val pnl: Double, val trades: Int)

@Serializable
data class JournalStatus(
    val playbook: Playbook,
    val today: List<JournalEntry>,
    val reviewedToday: Int,
    val winsToday: Int,
    val lossesToday: Int,
    val pnlToday: Double,
)

/**
 * The learning loop. Every AI trade is journaled with the full market picture at entry and exit; when it
 * closes, Claude reviews it against what actually happened and rewrites a short playbook of rules. The
 * playbook is injected into every idea and fund-manager prompt, so the next decision is made with the lessons
 * of the last ones. Claude's weights don't change: this is learning through memory, not fine-tuning.
 *
 * The playbook is shared by PAPER and LIVE (market lessons carry over); the journal is kept per mode.
 */
class TradeJournal(
    private val api: ClaudeApi,
    private val cfg: AiConfig,
    private val intel: MarketIntel,
    private val tracker: IdeaTracker,
    private val global: GlobalMarkets,
    private val clock: MarketClock,
    live: Boolean,
    dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(TradeJournal::class.java)
    private val file = dataDir.resolve(if (live) "journal_live.json" else "journal.json")
    private val playbookFile = dataDir.resolve("ai_playbook.json")
    private val daysFile = dataDir.resolve(if (live) "ai_days_live.json" else "ai_days.json")
    private val mutex = Mutex()
    /** Reviews run one at a time so each sees the playbook the previous one wrote. */
    private val reviewLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries: MutableList<JournalEntry> = load(file, kotlinx.serialization.builtins.ListSerializer(JournalEntry.serializer()))?.toMutableList() ?: mutableListOf()
    @Volatile var playbook: Playbook = load(playbookFile, Playbook.serializer()) ?: Playbook()
        private set
    private val days: MutableList<DayDigest> = load(daysFile, kotlinx.serialization.builtins.ListSerializer(DayDigest.serializer()))?.toMutableList() ?: mutableListOf()
    /** Fund-manager reasons waiting for their trade to open (a pick may wait for its entry level). */
    private val deskReasons = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun deskReason(ideaId: String, reason: String) { deskReasons[ideaId] = reason }

    /** Playbook block for prompts; empty until the first review. */
    fun playbookPrompt(): String {
        val p = playbook
        if (p.rules.isEmpty()) return ""
        return """
            Your trading playbook (v${p.version}, learned from ${p.tradesReviewed} reviewed trades of your own; follow it unless the data clearly says otherwise):
            <playbook>
            ${p.rules.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")}
            </playbook>
        """.trimIndent()
    }

    suspend fun recentLines(limit: Int = 12): List<String> = mutex.withLock { entries.takeLast(limit).map { it.oneLine() } }

    /** One line per recent debriefed day, oldest first: the memory that carries across days. */
    suspend fun recentDays(limit: Int = 10): List<String> = mutex.withLock {
        // Summaries contain '%' ("VIX rose 8%"), so only the P&L goes through format().
        days.takeLast(limit).map { "${it.day}: ${it.verdict}, ${it.trades} trades, P&L ₹${"%,.0f".format(it.pnl)}. ${it.summary} Lesson: ${it.lesson}" }
    }

    suspend fun status(): JournalStatus = mutex.withLock {
        val today = LocalDate.now(IST).toString()
        val t = entries.filter { it.day == today }
        val closed = t.filter { it.pnl != null }
        JournalStatus(playbook, t.asReversed(), t.count { it.review != null }, closed.count { it.pnl!! > 0 }, closed.count { it.pnl!! <= 0 }, closed.sumOf { it.pnl!! })
    }

    suspend fun day(day: String): List<JournalEntry> = mutex.withLock { entries.filter { it.day == day } }

    /** Called by [AiTrader] right after an entry fills. Captures the market picture in the background. */
    fun onOpened(t: AiTrade, idea: TradeIdea) {
        scope.launch {
            val ctx = runCatching { marketPicture(idea.quoteKey, withGlobal = true) }.onFailure { log.warn("Journal entry context failed: {}", it.message) }.getOrNull()
            val e = JournalEntry(
                ideaId = t.ideaId, day = clock.now().toLocalDate().toString(), source = t.source, underlying = t.index, kind = idea.kind,
                action = idea.action, confidence = idea.confidence, symbol = t.symbol, quantity = t.quantity, entryPrice = t.entryPrice,
                stopPrice = t.stopPrice, targetPrice = t.targetPrice, openedAt = t.openedAt, ideaEntry = idea.entry, ideaStop = idea.stopLoss,
                ideaTarget = idea.target, rationale = idea.rationale, invalidation = idea.invalidation, deskReason = deskReasons.remove(t.ideaId),
                playbookVersion = playbook.version, entryContext = ctx,
            )
            mutex.withLock { entries.removeAll { it.ideaId == t.ideaId && it.pnl == null }; entries += e; trimAndSave() }
        }
    }

    /** Called by [AiTrader] when a trade closes: records the exit, then has Claude review it and update the playbook. */
    fun onClosed(t: AiTrade) {
        scope.launch {
            val idea = tracker.get(t.ideaId)
            val ctx = idea?.let { runCatching { marketPicture(it.quoteKey, withGlobal = false) }.getOrNull() }
            val entry = mutex.withLock {
                val i = entries.indexOfLast { it.ideaId == t.ideaId && it.pnl == null }
                val base = if (i >= 0) entries[i] else JournalEntry(
                    ideaId = t.ideaId, day = clock.now().toLocalDate().toString(), source = t.source, underlying = t.index, kind = idea?.kind ?: "",
                    action = idea?.action ?: "", confidence = idea?.confidence ?: 0, symbol = t.symbol, quantity = t.quantity, entryPrice = t.entryPrice,
                    stopPrice = t.initialStop, targetPrice = t.targetPrice, openedAt = t.openedAt, ideaEntry = idea?.entry ?: 0.0,
                    ideaStop = idea?.stopLoss ?: 0.0, ideaTarget = idea?.target ?: 0.0, rationale = idea?.rationale.orEmpty(),
                    invalidation = idea?.invalidation.orEmpty(), playbookVersion = playbook.version,
                )
                val closed = base.copy(exitPrice = t.exitPrice, exitReason = t.exitReason, closedAt = t.closedAt, pnl = t.pnl,
                    peakPrice = t.peakPrice, finalStop = t.stopPrice, exitContext = ctx)
                if (i >= 0) entries[i] = closed else entries += closed
                trimAndSave()
                closed
            }
            runCatching { review(entry) }.onFailure { log.warn("Trade review failed for {}: {}", t.ideaId, it.message) }
        }
    }

    private suspend fun review(e: JournalEntry) = reviewLock.withLock {
        if (!api.configured || api.spendToday().usd >= cfg.dailyBudgetUsd) return@withLock
        val recent = mutex.withLock { entries.filter { it.review != null }.takeLast(15).map { it.oneLine() } }
        val pastDays = recentDays()
        val trade = TallyJson.encodeToJsonElement(JournalEntry.serializer(), e)
        val prompt = """
            ${playbookPrompt().ifBlank { "There is no playbook yet: write the first one." }}

            Your recent trading days:
            <days>
            ${pastDays.joinToString("\n").ifBlank { "none" }}
            </days>

            Your recent reviewed trades:
            <recent>
            ${recent.joinToString("\n").ifBlank { "none" }}
            </recent>

            The trade that just closed. entryContext is the market when you entered; exitContext is the market when it closed
            (its last36Bars5m show the path the underlying took during the trade):
            <trade>
            $trade
            </trade>

            Review this trade and update the playbook.
        """.trimIndent()
        val r = api.create(reviewBody(), prompt)
        if (r.stopReason == "refusal" || r.stopReason == "max_tokens") throw IllegalStateException("review stopped: ${r.stopReason}")
        val j = TallyJson.parseToJsonElement(r.text).jsonObject
        val rev = TradeReview(j.str("verdict"), j.str("whatHappened"), j["mistakes"]!!.jsonArray.map { it.jsonPrimitive.content },
            j.str("lesson"), clock.now().toString(), r.costUsd)
        mutex.withLock {
            val i = entries.indexOfLast { it.ideaId == e.ideaId }
            if (i >= 0) entries[i] = entries[i].copy(review = rev)
            savePlaybook(j["playbook"]!!.jsonArray.map { it.jsonPrimitive.content }, tradesReviewed = 1)
            trimAndSave()
        }
        log.info("Trade review {} {}: {} | {} (playbook v{})", e.underlying, e.symbol, rev.verdict, rev.lesson, playbook.version)
    }

    /**
     * Reviews a whole day from the idea track record (every idea Claude gave and how the market resolved it), plus
     * the journal and any [notes] on trades taken outside it, and rewrites the playbook. Used to learn from days
     * before the journal existed and as an end-of-day debrief.
     */
    suspend fun reviewDay(day: String, notes: String): Playbook = reviewLock.withLock {
        check(api.configured) { "ANTHROPIC_API_KEY is not set" }
        val ideas = tracker.recent(2000).filter { it.createdAt.startsWith(day) && !it.id.startsWith("test-") }.asReversed()
        check(ideas.isNotEmpty() || notes.isNotBlank()) { "No ideas recorded on $day" }
        val journaled = day(day)
        val trades = journaled.map { it.oneLine() }
        val pastDays = recentDays().filterNot { it.startsWith(day) }
        val ideaLines = ideas.joinToString("\n") {
            "${it.createdAt.substring(11, 16)} ${it.index} ${it.action} ${it.confidence}% entry ${it.entry} stop ${it.stopLoss} target ${it.target} " +
                "${it.horizonMinutes}m -> ${it.status}${it.resolvedPrice?.let { p -> " @ $p" }.orEmpty()} | ${it.rationale.take(220)}"
        }
        val prompt = """
            ${playbookPrompt().ifBlank { "There is no playbook yet: write the first one." }}

            Your earlier trading days:
            <days>
            ${pastDays.joinToString("\n").ifBlank { "none" }}
            </days>

            Debrief of $day. Every idea you gave that day, in time order, and how the market resolved it
            (TARGET = target traded before the stop, STOP = stop first, EXPIRED = neither within the horizon, NO_TRADE = you passed):
            <ideas>
            $ideaLines
            </ideas>

            Trades from the journal:
            <trades>
            ${trades.joinToString("\n").ifBlank { "none" }}
            </trades>

            Notes from the trader on what was actually traded:
            <notes>
            ${notes.ifBlank { "none" }}
            </notes>

            Find the patterns: which setups and times worked, which failed, what you kept getting wrong (direction,
            timing, chasing, chop, levels too tight or too wide), and what you should have done instead. Then update the playbook.
            Use the trade schema with the day as "the trade": verdict for the day overall, whatHappened as the day summary.
        """.trimIndent()
        val r = api.create(reviewBody(), prompt)
        if (r.stopReason == "refusal" || r.stopReason == "max_tokens") throw IllegalStateException("day review stopped: ${r.stopReason}")
        val j = TallyJson.parseToJsonElement(r.text).jsonObject
        mutex.withLock {
            savePlaybook(j["playbook"]!!.jsonArray.map { it.jsonPrimitive.content }, tradesReviewed = 0, day = day)
            days.removeAll { it.day == day }
            days += DayDigest(day, j.str("verdict"), j.str("whatHappened").take(600), j.str("lesson"),
                journaled.sumOf { it.pnl ?: 0.0 }, journaled.count { it.pnl != null })
            days.sortBy { it.day }
            while (days.size > 120) days.removeAt(0)
            runCatching { Files.writeString(daysFile, TallyJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(DayDigest.serializer()), days)) }
                .onFailure { log.error("Saving day digests failed", it) }
        }
        log.info("Day review {}: {} | {}", day, j.str("verdict"), j.str("lesson"))
        playbook
    }

    private suspend fun marketPicture(quoteKey: String, withGlobal: Boolean): JsonObject = buildJsonObject {
        put("at", clock.now().toString())
        put("underlying", intel.snapshot(quoteKey))
        runCatching { intel.indiaQuotes() }.getOrNull()?.let { put("india", it) }
        if (withGlobal) {
            put("global", TallyJson.encodeToJsonElement(global.snapshot()))
            intel.latestBrief?.let { put("briefExcerpt", it.markdown.take(1500)) }
        }
    }

    private fun savePlaybook(rules: List<String>, tradesReviewed: Int, day: String? = null) {
        val p = playbook
        playbook = Playbook(
            version = p.version + 1, updatedAt = clock.now().toString(),
            rules = rules.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_RULES),
            tradesReviewed = p.tradesReviewed + tradesReviewed,
            daysReviewed = if (day == null || day in p.daysReviewed) p.daysReviewed else p.daysReviewed + day,
        )
        runCatching { Files.writeString(playbookFile, TallyJson.encodeToString(Playbook.serializer(), playbook)) }
            .onFailure { log.error("Saving playbook failed", it) }
    }

    private fun reviewBody() = buildJsonObject {
        put("model", api.model)
        put("max_tokens", 12000)
        put("system", if (cfg.learningMode) REVIEW_SYSTEM + "\n\n" + REVIEW_LEARNING else REVIEW_SYSTEM)
        putJsonObject("output_config") {
            put("effort", "high")
            putJsonObject("format") { put("type", "json_schema"); put("schema", REVIEW_SCHEMA) }
        }
        put("fallbacks", "default")
    }

    private fun trimAndSave() {
        while (entries.size > 1000) entries.removeAt(0)
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, TallyJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(JournalEntry.serializer()), entries))
        }.onFailure { log.error("Saving journal failed", it) }
    }

    private fun <T> load(f: Path, s: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { if (Files.exists(f)) TallyJson.decodeFromString(s, Files.readString(f)) else null }.getOrNull()

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()

    companion object {
        const val MAX_RULES = 15

        private val REVIEW_SYSTEM = """
            You are the head trader reviewing your own intraday trades in Indian index and stock options (always
            BUYING at-the-money calls for LONG and puts for SHORT, exits by stop, trailing stop, target, horizon or 15:15).
            Your job is to get better trade by trade. Be honest and specific: judge the decision with what was knowable
            at entry, separately from the result. A loss on a sound setup is GOOD_LOSS; a win on a bad setup is BAD_WIN.

            Check: direction vs the 5-minute and daily trend, entry location (chasing an extended move, buying into
            resistance or selling into support), whether the day's ATR was already used, choppy range-bound action that
            decays premium, stop too tight for normal noise or too wide, timing (open volatility, lunch chop, late day),
            the news and global backdrop, and correlation with other open trades.

            Sizing is set by the system, not by lots: the fund manager picks riskPct (25-100) of the per-trade loss cap,
            and the lot count follows from the stop distance. A riskPct-25 trade on a large fund can be many lots and
            that is intended. Write sizing rules in riskPct terms, never in lots or confidence %; the fund manager
            does not see the analysts' confidence.

            Then rewrite the playbook: at most 15 short, concrete, testable rules ("Skip index option buys when the
            5m EMA9 and EMA21 are within 0.05% and RSI is 45-55: chop decays premium"), not vague advice ("be careful").
            Keep rules that still hold, sharpen or drop ones the evidence contradicts, merge duplicates, and put the most
            important first. Include rules about what TO trade, not only what to avoid. Do not let one trade overturn a
            rule that several trades support.
        """.trimIndent()

        /** Paper learning mode: the user wants entries allowed until the system cutoff (14:45). */
        private val REVIEW_LEARNING = """
            This is a paper learning account where new entries are allowed until 14:45. Do not write rules that ban
            entries before 14:45; express late-session risk as conditions (e.g. target within reach of the 5m ATR
            in the time left) instead.
        """.trimIndent()

        private val REVIEW_SCHEMA = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("verdict"); add("whatHappened"); add("mistakes"); add("lesson"); add("playbook") }
            putJsonObject("properties") {
                putJsonObject("verdict") { put("type", "string"); putJsonArray("enum") { add("GOOD_WIN"); add("GOOD_LOSS"); add("BAD_WIN"); add("BAD_LOSS") } }
                putJsonObject("whatHappened") { put("type", "string") }
                putJsonObject("mistakes") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("lesson") { put("type", "string") }
                putJsonObject("playbook") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            }
        }
    }
}
