package com.tuissh.app.terminal.input

import com.tuissh.app.terminal.session.TerminalInputEncoder
import com.tuissh.app.terminal.session.TerminalInputEncoder.Key
import com.tuissh.app.terminal.session.TerminalInputEncoder.Modifiers

/**
 * The extra keyboard a terminal needs and a phone does not have.
 *
 * Replaces `TerminalFixedKeySettings`, `TerminalKeyTriggerSettings`,
 * `KeyboardSymbolGroupStore` and the `NeoTerminalKeyboardLayoutHostView` from the
 * original app. A soft keyboard cannot send Ctrl-C, Esc or an arrow key, so the app
 * supplies its own key bar.
 *
 * The bar is data-driven: [DefaultRows] is the shipped layout, and a user layout is
 * just another list of the same [BarKey] values. That keeps persistence trivial and
 * avoids a parallel enum for custom keys.
 */
object TerminalKeyBar {

    /** One key on the bar. */
    sealed interface BarKey {
        val label: String

        /** Modifier sent with this key while the sticky modifier is latched. */
        val consumesModifier: Boolean get() = true

        data class Ctrl(override val label: String = "Ctrl") : BarKey
        data class Alt(override val label: String = "Alt") : BarKey
        data class Shift(override val label: String = "Shift") : BarKey

        data class Special(
            val key: Key,
            override val label: String,
        ) : BarKey

        /** Send literal text, e.g. `|`, `~`, `-`. */
        data class Literal(
            val text: String,
            override val label: String = text,
        ) : BarKey

        /** Send a control byte, e.g. Ctrl-C = 0x03. */
        data class ControlByte(
            val value: Int,
            override val label: String,
        ) : BarKey

        data object Spacer : BarKey {
            override val label: String get() = ""
            override val consumesModifier: Boolean get() = false
        }
    }

    /**
     * Default rows, ordered by how often a phone terminal needs them.
     *
     * Row 1 is modifiers plus the keys people reach for constantly (Esc, Tab,
     * arrows). Row 2 is symbols a soft keyboard hides behind planes.
     */
    val DefaultRows: List<List<BarKey>> = listOf(
        listOf(
            BarKey.Ctrl(),
            BarKey.Alt(),
            BarKey.Special(Key.ESCAPE, "Esc"),
            BarKey.Special(Key.TAB, "Tab"),
            BarKey.Special(Key.UP, "↑"),
            BarKey.Special(Key.DOWN, "↓"),
            BarKey.Special(Key.LEFT, "←"),
            BarKey.Special(Key.RIGHT, "→"),
            BarKey.Special(Key.HOME, "Home"),
            BarKey.Special(Key.END, "End"),
        ),
        listOf(
            BarKey.Special(Key.PAGE_UP, "PgUp"),
            BarKey.Special(Key.PAGE_DOWN, "PgDn"),
            BarKey.Special(Key.INSERT, "Ins"),
            BarKey.Special(Key.DELETE, "Del"),
            BarKey.Literal("|"),
            BarKey.Literal("~"),
            BarKey.Literal("/"),
            BarKey.Literal("-"),
            BarKey.Literal("_"),
            BarKey.ControlByte(0x03, "^C"),
        ),
        listOf(
            BarKey.ControlByte(0x04, "^D"),
            BarKey.ControlByte(0x1A, "^Z"),
            BarKey.ControlByte(0x0C, "^L"),
            BarKey.ControlByte(0x01, "^A"),
            BarKey.ControlByte(0x05, "^E"),
            BarKey.ControlByte(0x0B, "^K"),
            BarKey.ControlByte(0x15, "^U"),
            BarKey.ControlByte(0x17, "^W"),
            BarKey.ControlByte(0x12, "^R"),
            BarKey.Literal("$"),
        ),
    )

    /** Sticky modifier state. */
    data class ModifierState(
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) {
        val any: Boolean get() = ctrl || alt || shift

        fun toggleCtrl() = copy(ctrl = !ctrl)
        fun toggleAlt() = copy(alt = !alt)
        fun toggleShift() = copy(shift = !shift)
        fun cleared() = ModifierState()

        fun toModifiers(): Modifiers = Modifiers(ctrl = ctrl, alt = alt, shift = shift)
    }

    /**
     * Encode a bar key press.
     *
     * Returns the bytes to send and the modifier state to keep. A latched modifier is
     * consumed by the next key, matching every desktop terminal.
     */
    fun press(
        barKey: BarKey,
        modifiers: ModifierState,
        appCursor: Boolean,
    ): Pair<ByteArray, ModifierState> {
        return when (barKey) {
            is BarKey.Ctrl -> ByteArray(0) to modifiers.toggleCtrl()
            is BarKey.Alt -> ByteArray(0) to modifiers.toggleAlt()
            is BarKey.Shift -> ByteArray(0) to modifiers.toggleShift()
            BarKey.Spacer -> ByteArray(0) to modifiers

            is BarKey.ControlByte -> {
                // A latched Alt turns a control byte into ESC-prefixed input.
                val bytes = if (modifiers.alt) {
                    byteArrayOf(0x1B, barKey.value.toByte())
                } else {
                    byteArrayOf(barKey.value.toByte())
                }
                bytes to modifiers.cleared()
            }

            is BarKey.Literal -> {
                val out = if (modifiers.alt) "\u001B" + barKey.text else barKey.text
                out.toByteArray(Charsets.UTF_8) to modifiers.cleared()
            }

            is BarKey.Special -> {
                var effective = modifiers
                // A latched Ctrl on an arrow key is meaningless; drop it rather than
                // sending a modified sequence the remote may not understand.
                if (effective.ctrl && barKey.key in ARROW_KEYS) {
                    effective = effective.copy(ctrl = false)
                }
                TerminalInputEncoder.encodeKey(
                    key = barKey.key,
                    mods = effective.toModifiers(),
                    appCursor = appCursor,
                ) to modifiers.cleared()
            }
        }
    }

    private val ARROW_KEYS = setOf(Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT)

    /** Serialise a custom layout for `AppSetting` storage. */
    fun encodeLayout(rows: List<List<BarKey>>): String = buildString {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) append('\n')
            append(
                row.joinToString("\t") { key ->
                    when (key) {
                        is BarKey.Ctrl -> "ctrl"
                        is BarKey.Alt -> "alt"
                        is BarKey.Shift -> "shift"
                        BarKey.Spacer -> "spacer"
                        is BarKey.ControlByte -> "ctrl:${key.value}"
                        is BarKey.Literal -> "lit:${key.text}"
                        is BarKey.Special -> "key:${key.key.name}"
                    }
                },
            )
        }
    }

    /** Parse a stored layout; unknown tokens are dropped rather than failing. */
    fun decodeLayout(serialised: String): List<List<BarKey>> {
        if (serialised.isBlank()) return DefaultRows
        return serialised.lines().map { line ->
            line.split('\t').mapNotNull { token ->
                when {
                    token == "ctrl" -> BarKey.Ctrl()
                    token == "alt" -> BarKey.Alt()
                    token == "shift" -> BarKey.Shift()
                    token == "spacer" -> BarKey.Spacer
                    token.startsWith("ctrl:") ->
                        token.removePrefix("ctrl:").toIntOrNull()?.let {
                            BarKey.ControlByte(it, "^" + ('A' + it - 1).toString().removePrefix("["))
                        }
                    token.startsWith("lit:") -> BarKey.Literal(token.removePrefix("lit:"))
                    token.startsWith("key:") -> runCatching {
                        val key = Key.valueOf(token.removePrefix("key:"))
                        BarKey.Special(key, key.name.lowercase().replaceFirstChar { it.uppercase() })
                    }.getOrNull()
                    else -> null
                }
            }.ifEmpty { listOf(BarKey.Spacer) }
        }.ifEmpty { DefaultRows }
    }

    /** Human label for a control byte, used when rebuilding a decoded layout. */
    fun controlLabel(value: Int): String = when (value) {
        0x00 -> "^@"
        0x1B -> "^["
        0x1C -> "^\\"
        0x1D -> "^]"
        0x1E -> "^6"
        0x1F -> "^-"
        0x7F -> "^?"
        else -> "^" + ('A'.code + value - 1).toChar()
    }
}
