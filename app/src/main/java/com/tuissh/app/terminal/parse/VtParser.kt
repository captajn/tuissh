package com.tuissh.app.terminal.parse

/**
 * VT500-style parser state machine, following the DEC PDT (parser diagram) that
 * every terminal emulator implements.
 *
 * The original app used `VTParser` + `VTSequenceDispatcher` + `VTFunctionRegistry`
 * with 18 states. This collapses the rarely used DCS passthrough states into a
 * generic string-sink because Kitty graphics is handled at a higher layer.
 */
class VtParser(private val handler: Handler) {

    interface Handler {
        fun print(codepoint: Int)
        fun execute(control: Int)
        fun csi(
            prefix: Char,
            params: IntArray,
            paramCount: Int,
            privateMarker: Char,
            intermediates: String,
            final: Char,
            subParams: Array<IntArray>?,
        )

        fun esc(intermediates: String, final: Char)

        /** OSC with numeric command; terminated by BEL or ST. */
        fun osc(command: Int, payload: String)

        /** DCS/APC/PM/SOS payload. */
        fun dcs(kind: Char, payload: String)
    }

    private enum class State {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        CSI_IGNORE,
        OSC_STRING,
        DCS_ENTRY,
        DCS_PASSTHROUGH,
        DCS_IGNORE,
        SOS_PM_APC,
        UTF8_COLLECT,
    }

    private var state = State.GROUND

    // --- parameter accumulation -------------------------------------------
    private val params = IntArray(32)
    private var paramCount = 0
    private var currentParam = -1
    private var privateMarker = ' '
    private var prefix = ' '
    private val intermediates = StringBuilder()

    // sub-parameters (colon separated, used by SGR 38:2:r:g:b)
    private var currentSub: MutableList<Int>? = null
    private val subParams = ArrayList<IntArray>(8)

    // --- string accumulation ----------------------------------------------
    private val stringSink = StringBuilder()
    private var oscCommand = -1

    // --- UTF-8 decoder ----------------------------------------------------
    private var utf8Needed = 0
    private var utf8Codepoint = 0
    private var utf8Min = 0

    /** Feed raw bytes from the SSH channel. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val end = offset + length
        var i = offset
        while (i < end) {
            processByte(bytes[i].toInt() and 0xFF)
            i++
        }
    }

    fun feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        feed(bytes)
    }

    fun reset() {
        state = State.GROUND
        paramCount = 0
        currentParam = -1
        privateMarker = ' '
        prefix = ' '
        intermediates.setLength(0)
        subParams.clear()
        currentSub = null
        stringSink.setLength(0)
        oscCommand = -1
        utf8Needed = 0
        utf8Codepoint = 0
    }

    // ---------------------------------------------------------------------

    private fun processByte(b: Int) {
        // 0x18 CAN and 0x1A SUB abort any sequence.
        if (b == 0x18 || b == 0x1A) {
            abortSequence()
            return
        }

        // 0x1B ESC always restarts at ESCAPE regardless of state.
        if (b == 0x1B) {
            flushStringState()
            state = State.ESCAPE
            intermediates.setLength(0)
            privateMarker = ' '
            prefix = ' '
            resetParams()
            return
        }

        when (state) {
            State.GROUND -> ground(b)
            State.ESCAPE -> escape(b)
            State.ESCAPE_INTERMEDIATE -> escapeIntermediate(b)
            State.CSI_ENTRY -> csiEntry(b)
            State.CSI_PARAM -> csiParam(b)
            State.CSI_INTERMEDIATE -> csiIntermediate(b)
            State.CSI_IGNORE -> csiIgnore(b)
            State.OSC_STRING -> oscString(b)
            State.DCS_ENTRY -> dcsEntry(b)
            State.DCS_PASSTHROUGH -> dcsPassthrough(b)
            State.DCS_IGNORE -> dcsIgnore(b)
            State.SOS_PM_APC -> sosPmApc(b)
            State.UTF8_COLLECT -> utf8Collect(b)
        }
    }

    private fun abortSequence() {
        flushStringState()
        state = State.GROUND
        resetParams()
    }

    private fun flushStringState() {
        when (state) {
            State.OSC_STRING, State.DCS_PASSTHROUGH, State.SOS_PM_APC -> {
                if (stringSink.isNotEmpty() || oscCommand >= 0) {
                    emitString(terminated = false)
                }
            }
            else -> Unit
        }
        stringSink.setLength(0)
        oscCommand = -1
    }

    private fun emitString(terminated: Boolean) {
        val payload = stringSink.toString()
        when (state) {
            State.OSC_STRING -> if (oscCommand >= 0) handler.osc(oscCommand, payload)
            State.DCS_PASSTHROUGH -> handler.dcs('P', payload)
            State.SOS_PM_APC -> handler.dcs('_', payload)
            else -> Unit
        }
        stringSink.setLength(0)
    }

    // --- GROUND -----------------------------------------------------------

    private fun ground(b: Int) {
        when {
            b < 0x20 -> {
                if (b == 0x00 || b == 0x7F) return
                handler.execute(b)
            }
            b == 0x7F -> Unit
            b < 0x80 -> handler.print(b)
            b in 0xC2..0xDF -> startUtf8(b and 0x1F, 2, 0x80)
            b in 0xE0..0xEF -> startUtf8(b and 0x0F, 3, 0x800)
            b in 0xF0..0xF4 -> startUtf8(b and 0x07, 4, 0x10000)
            else -> Unit // stray continuation byte
        }
    }

    private fun startUtf8(masked: Int, total: Int, minimum: Int) {
        utf8Codepoint = masked
        utf8Needed = total - 1
        utf8Min = minimum
        state = State.UTF8_COLLECT
    }

    private fun utf8Collect(b: Int) {
        if (b and 0xC0 != 0x80) {
            // Truncated sequence: drop it and reprocess this byte in ground.
            state = State.GROUND
            utf8Needed = 0
            processByte(b)
            return
        }
        utf8Codepoint = (utf8Codepoint shl 6) or (b and 0x3F)
        utf8Needed--
        if (utf8Needed == 0) {
            state = State.GROUND
            val cp = utf8Codepoint
            if (cp >= utf8Min && cp <= 0x10FFFF && cp !in 0xD800..0xDFFF) {
                handler.print(cp)
            }
            utf8Codepoint = 0
        }
    }

    // --- ESCAPE -----------------------------------------------------------

    private fun escape(b: Int) {
        when {
            b == 0x18 || b == 0x1A -> abortSequence()
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            b in 0x20..0x2F -> {
                intermediates.append(b.toChar())
                state = State.ESCAPE_INTERMEDIATE
            }
            // '[' (0x5B), ']' (0x5D), 'P' (0x50), 'X' (0x58), '^' (0x5E) and
            // '_' (0x5F) all fall inside the 0x30..0x7E final-byte range, so the
            // introducer checks MUST come before the generic dispatch. Testing this
            // range first silently swallows every CSI/OSC/DCS sequence.
            b == '['.code -> {
                resetParams()
                privateMarker = ' '
                prefix = ' '
                state = State.CSI_ENTRY
            }
            b == ']'.code -> {
                oscCommand = -1
                stringSink.setLength(0)
                state = State.OSC_STRING
            }
            b == 'P'.code -> {
                stringSink.setLength(0)
                resetParams()
                state = State.DCS_ENTRY
            }
            b == 'X'.code || b == '^'.code || b == '_'.code -> {
                stringSink.setLength(0)
                state = State.SOS_PM_APC
            }
            b in 0x30..0x7E -> {
                handler.esc(intermediates.toString(), b.toChar())
                state = State.GROUND
                intermediates.setLength(0)
            }
            else -> state = State.GROUND
        }
    }

    private fun escapeIntermediate(b: Int) {
        when {
            b in 0x20..0x2F -> intermediates.append(b.toChar())
            b in 0x30..0x7E -> {
                handler.esc(intermediates.toString(), b.toChar())
                state = State.GROUND
                intermediates.setLength(0)
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> {
                state = State.GROUND
                intermediates.setLength(0)
            }
        }
    }

    // --- CSI --------------------------------------------------------------

    private fun resetParams() {
        paramCount = 0
        currentParam = -1
        subParams.clear()
        currentSub = null
        intermediates.setLength(0)
    }

    private fun finishParam() {
        val value = if (currentParam < 0) -1 else currentParam
        currentSub?.let { subParams.add(it.toIntArray()) }
        currentSub = null
        if (paramCount < params.size) params[paramCount++] = value
        currentParam = -1
    }

    private fun csiEntry(b: Int) {
        when {
            b in 0x3C..0x3F -> { // < = > ?
                privateMarker = b.toChar()
                state = State.CSI_PARAM
            }
            b in 0x30..0x39 -> {
                currentParam = b - 0x30
                state = State.CSI_PARAM
            }
            b == ';'.code -> {
                finishParam()
                state = State.CSI_PARAM
            }
            b == ':'.code -> {
                currentSub = mutableListOf()
                state = State.CSI_PARAM
            }
            b in 0x20..0x2F -> {
                intermediates.append(b.toChar())
                state = State.CSI_INTERMEDIATE
            }
            b in 0x40..0x7E -> {
                finishParam()
                dispatchCsi(b.toChar())
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> state = State.CSI_IGNORE
        }
        if (b in 0x30..0x3B && prefix == ' ') prefix = ' '
    }

    private fun csiParam(b: Int) {
        when {
            b in 0x30..0x39 -> {
                val d = b - 0x30
                if (currentSub != null) {
                    val list = currentSub!!
                    if (list.isEmpty()) list.add(d) else list[list.size - 1] = list[list.size - 1] * 10 + d
                } else {
                    currentParam = ((if (currentParam < 0) 0 else currentParam) * 10) + d
                }
            }
            b == ';'.code -> {
                currentSub?.let { subParams.add(it.toIntArray()) }
                currentSub = null
                finishParam()
            }
            b == ':'.code -> {
                if (currentSub == null) {
                    currentSub = mutableListOf(if (currentParam < 0) 0 else currentParam)
                    currentParam = -1
                } else {
                    currentSub!!.add(0)
                }
            }
            b in 0x20..0x2F -> {
                intermediates.append(b.toChar())
                state = State.CSI_INTERMEDIATE
            }
            b in 0x40..0x7E -> {
                if (currentSub != null) {
                    subParams.add(currentSub!!.toIntArray())
                    currentSub = null
                } else {
                    finishParam()
                }
                dispatchCsi(b.toChar())
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> state = State.CSI_IGNORE
        }
    }

    private fun csiIntermediate(b: Int) {
        when {
            b in 0x20..0x2F -> intermediates.append(b.toChar())
            b in 0x40..0x7E -> {
                finishParam()
                dispatchCsi(b.toChar())
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> state = State.CSI_IGNORE
        }
    }

    private fun csiIgnore(b: Int) {
        when {
            b in 0x40..0x7E -> state = State.GROUND
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> Unit
        }
    }

    private fun dispatchCsi(final: Char) {
        handler.csi(
            prefix = prefix,
            params = params,
            paramCount = paramCount,
            privateMarker = privateMarker,
            intermediates = intermediates.toString(),
            final = final,
            subParams = if (subParams.isEmpty()) null else subParams.toTypedArray(),
        )
        state = State.GROUND
        resetParams()
        privateMarker = ' '
    }

    // --- OSC --------------------------------------------------------------

    private fun oscString(b: Int) {
        when {
            b == 0x07 -> { // BEL terminator
                emitString(terminated = true)
                state = State.GROUND
                stringSink.setLength(0)
                oscCommand = -1
            }
            b == 0x9C -> { // ST as single byte (rare)
                emitString(terminated = true)
                state = State.GROUND
                stringSink.setLength(0)
                oscCommand = -1
            }
            b == 0x1B -> {
                // handled above: ESC restarts, flush first
                emitString(terminated = true)
                state = State.ESCAPE
                intermediates.setLength(0)
                resetParams()
            }
            else -> {
                if (oscCommand < 0) {
                    if (b == ';'.code) {
                        oscCommand = if (stringSink.isEmpty()) 0
                        else stringSink.toString().toIntOrNull() ?: -1
                        stringSink.setLength(0)
                    } else {
                        stringSink.append(b.toChar())
                        if (stringSink.length > 16) {
                            // Not a numeric command prefix; keep as raw.
                            oscCommand = -1
                            stringSink.setLength(0)
                            state = State.GROUND
                        }
                    }
                } else {
                    if (stringSink.length < MAX_STRING) stringSink.append(b.toChar())
                }
            }
        }
    }

    // --- DCS --------------------------------------------------------------

    private fun dcsEntry(b: Int) {
        when {
            b == 0x1B -> state = State.ESCAPE
            0x20 <= b && b <= 0x2F -> stringSink.append(b.toChar())
            b == 0x9C -> {
                state = State.GROUND
                stringSink.setLength(0)
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> state = State.DCS_PASSTHROUGH
        }
    }

    private fun dcsPassthrough(b: Int) {
        when {
            b == 0x9C -> {
                handler.dcs('P', stringSink.toString())
                stringSink.setLength(0)
                state = State.GROUND
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> if (stringSink.length < MAX_STRING) stringSink.append(b.toChar())
        }
    }

    private fun dcsIgnore(b: Int) {
        when {
            b == 0x9C -> state = State.GROUND
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> Unit
        }
    }

    private fun sosPmApc(b: Int) {
        when {
            b == 0x9C -> {
                handler.dcs('_', stringSink.toString())
                stringSink.setLength(0)
                state = State.GROUND
            }
            b < 0x20 -> if (b != 0x7F) handler.execute(b)
            else -> if (stringSink.length < MAX_STRING) stringSink.append(b.toChar())
        }
    }

    private companion object {
        const val MAX_STRING = 256 * 1024
    }
}
