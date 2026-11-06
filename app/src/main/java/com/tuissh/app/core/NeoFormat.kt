package com.tuissh.app.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Byte / rate / duration formatting.
 *
 * Ported from `NeoSwiftLib/NeoUtilLib.swift`. The original exposes a
 * `StringEncoding` class for binary-vs-decimal unit display; on Android the same
 * choice is modelled as [UnitBase].
 */
enum class UnitBase { BINARY, DECIMAL }

object NeoFormat {

    private val BINARY_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    private val DECIMAL_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

    /** Human readable byte count. */
    fun bytes(value: Long, base: UnitBase = UnitBase.BINARY, decimals: Int = 1): String {
        if (value < 0) return "—"
        val divisor = if (base == UnitBase.BINARY) 1024.0 else 1000.0
        if (value < divisor) return "$value B"
        val units = if (base == UnitBase.BINARY) BINARY_UNITS else DECIMAL_UNITS
        var v = value.toDouble()
        var i = 0
        while (v >= divisor && i < units.size - 1) {
            v /= divisor
            i++
        }
        return String.format(Locale.US, "%.${decimals}f %s", v, units[i])
    }

    /**
     * Byte count for a *rate* (per second).
     */
    fun rate(bytesPerSecond: Double, base: UnitBase = UnitBase.BINARY): String {
        if (bytesPerSecond.isNaN() || bytesPerSecond < 0) return "—"
        val divisor = if (base == UnitBase.BINARY) 1024.0 else 1000.0
        val units = if (base == UnitBase.BINARY) BINARY_UNITS else DECIMAL_UNITS
        var v = bytesPerSecond
        var i = 0
        while (v >= divisor && i < units.size - 1) {
            v /= divisor
            i++
        }
        val pattern = if (v >= 100) "%.0f" else if (v >= 10) "%.1f" else "%.2f"
        return String.format(Locale.US, "$pattern %s/s", v, units[i])
    }

    /**
     * Parse a size token as printed by `df`, `du`, `free` or `ls -h`.
     * Accepts values like `1.5G`, `512M`, `0`, `1,024K`.
     */
    fun parseSizeToBytes(token: String): Long? {
        val cleaned = token.trim().replace(",", "")
        if (cleaned.isEmpty() || cleaned == "-") return null
        val number = cleaned.takeWhile { it.isDigit() || it == '.' }
        if (number.isEmpty()) return null
        val suffix = cleaned.drop(number.length).trim().uppercase(Locale.US)
        val unit = when (suffix.firstOrNull()) {
            null -> 1L
            'B' -> 1L
            'K' -> 1024L
            'M' -> 1024L * 1024
            'G' -> 1024L * 1024 * 1024
            'T' -> 1024L * 1024 * 1024 * 1024
            'P' -> 1024L * 1024 * 1024 * 1024 * 1024
            else -> return null
        }
        val value = number.toDoubleOrNull() ?: return null
        return (value * unit).roundToLong()
    }

    /** Parse a percentage token such as `12.4%` or `12,4 %`. */
    fun parsePercent(token: String): Double? =
        token.trim().replace(",", ".").removeSuffix("%").trim().toDoubleOrNull()

    /** Compact duration: `4d 3h 12m`, `12m 4s`, `9s`. */
    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** `12.3t` style number shortening used by process tables. */
    fun compactNumber(value: Double): String {
        if (value.isNaN()) return "—"
        val a = abs(value)
        val sign = if (value < 0) "-" else ""
        return when {
            a >= 1e12 -> sign + String.format(Locale.US, "%.1ft", a / 1e12)
            a >= 1e9 -> sign + String.format(Locale.US, "%.1fb", a / 1e9)
            a >= 1e6 -> sign + String.format(Locale.US, "%.1fm", a / 1e6)
            a >= 1e3 -> sign + String.format(Locale.US, "%.1fk", a / 1e3)
            else -> sign + String.format(Locale.US, "%.0f", a)
        }
    }

    /** Base64 size hint needed by Kitty graphics / image preview. */
    fun base64Length(rawBytes: Long): Long = ((rawBytes + 2) / 3) * 4

    /** Shannon-entropy style byte-class estimate used by the file preview. */
    fun looksBinary(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        var control = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return true
            if (v < 0x09 || (v > 0x0D && v < 0x20)) control++
        }
        return control.toDouble() / sample.size > 0.30
    }

    /** Order-of-magnitude helper kept for parity with the original util lib. */
    fun magnitude(value: Double): Int =
        if (value <= 0.0) 0 else ln(value) .div(ln(10.0)).toInt()

    /** Thousand-separated integer. */
    fun group(value: Long): String = String.format(Locale.US, "%,d", value)
}
