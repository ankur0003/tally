package com.tally.kite

import com.tally.util.IST
import com.tally.util.TallyJson
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

@Serializable
data class KiteSession(
    val userId: String,
    val userName: String?,
    val accessToken: String,
    val loginEpochSeconds: Long,
)

/**
 * Persists the daily Kite access token so a restart does not force a re-login.
 * Kite invalidates tokens at 06:00 IST every day, so a session from before the last 06:00 is stale.
 */
class KiteSessionStore(dataDir: Path) {
    private val file = dataDir.resolve("kite_session.json")

    @Volatile
    private var cached: KiteSession? = load()

    fun current(now: ZonedDateTime = ZonedDateTime.now(IST)): KiteSession? =
        cached?.takeIf { isValid(it, now) }

    fun save(session: KiteSession) {
        Files.createDirectories(file.parent)
        Files.writeString(file, TallyJson.encodeToString(KiteSession.serializer(), session))
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
        cached = session
    }

    fun clear() {
        Files.deleteIfExists(file)
        cached = null
    }

    private fun load(): KiteSession? = runCatching {
        if (Files.exists(file)) TallyJson.decodeFromString(KiteSession.serializer(), Files.readString(file)) else null
    }.getOrNull()

    private fun isValid(session: KiteSession, now: ZonedDateTime): Boolean {
        val todayCutoff = now.with(LocalTime.of(6, 0))
        val lastCutoff = if (now.isBefore(todayCutoff)) todayCutoff.minusDays(1) else todayCutoff
        return Instant.ofEpochSecond(session.loginEpochSeconds).isAfter(lastCutoff.toInstant())
    }
}
