package com.tuissh.app.core

import android.util.Log

/**
 * Central logging facade.
 *
 * Every call writes to logcat and to a bounded in-memory ring buffer. The ring is
 * what the in-app debug screen and the exported log file read; logcat is what
 * `adb logcat` shows during bring-up.
 *
 * Local JVM tests run this same code with `android.util.Log` stubbed, so the Log
 * calls are wrapped: a missing stub must not fail a protocol test.
 */
object NeoLog {

    private const val MAX_RING = 2000

    private val ring = ArrayDeque<Entry>(MAX_RING)

    var enabled: Boolean = true
    var minLevel: Level = Level.DEBUG

    enum class Level(val prio: Int, val letter: Char) {
        VERBOSE(2, 'V'),
        DEBUG(3, 'D'),
        INFO(4, 'I'),
        WARN(5, 'W'),
        ERROR(6, 'E'),
    }

    data class Entry(
        val at: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    @Synchronized
    fun log(level: Level, tag: String, message: String) {
        if (!enabled || level.prio < minLevel.prio) return
        emit(level, tag, message)
        record(level, tag, message)
    }

    private fun emit(level: Level, tag: String, message: String) {
        runCatching {
            when (level) {
                Level.VERBOSE -> Log.v(tag, message)
                Level.DEBUG -> Log.d(tag, message)
                Level.INFO -> Log.i(tag, message)
                Level.WARN -> Log.w(tag, message)
                Level.ERROR -> Log.e(tag, message)
            }
        }
    }

    private fun record(level: Level, tag: String, message: String) {
        if (ring.size >= MAX_RING) ring.removeFirst()
        ring.addLast(Entry(System.currentTimeMillis(), level, tag, message))
    }

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled || Level.ERROR.prio < minLevel.prio) return
        if (t != null) {
            runCatching { Log.e(tag, msg, t) }
            synchronized(this) {
                record(Level.ERROR, tag, "$msg: ${t.message}")
            }
        } else {
            log(Level.ERROR, tag, msg)
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = ring.toList()

    @Synchronized
    fun clear() = ring.clear()

    /** Render the ring buffer as plain text for sharing / log export. */
    @Synchronized
    fun exportText(): String = buildString {
        for (e in ring) {
            append(e.level.letter).append('/').append(e.tag).append(": ").append(e.message).append('\n')
        }
    }
}

/** Terminal + SSH subsystem tags, mirroring the original debug categories. */
object LogTag {
    const val SSH = "NeoSSH"
    const val SFTP = "NeoSFTP"
    const val TERM = "NeoTerm"
    const val MOSH = "NeoMosh"
    const val DOCKER = "NeoDocker"
    const val METRICS = "NeoMetrics"
    const val AI = "NeoAI"
    const val DB = "NeoDB"
    const val CRYPTO = "NeoCrypto"
    const val UI = "NeoUI"
}
