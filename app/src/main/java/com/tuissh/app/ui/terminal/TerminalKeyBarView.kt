package com.tuissh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.terminal.input.TerminalKeyBar
import com.tuissh.app.terminal.input.TerminalKeyBar.BarKey

private val BarBackground = Color(0xFF101820)
private val KeyIdle = Color(0xFF1E2A36)
private val KeyText = Color(0xFFE8EEF4)
private val KeyAccent = Color(0xFF3DDC97)

/**
 * Fixed key bar for the terminal.
 *
 * A phone keyboard cannot produce Ctrl-C, Esc or an arrow key, so the terminal has to
 * supply them. Modifiers are sticky: tap Ctrl, then C, and exactly one Ctrl-C is sent.
 * That is the only interaction that works one-handed.
 *
 * Rows scroll horizontally instead of wrapping: a wrapped bar changes height whenever
 * the layout changes, which makes the terminal grid jump under the user's finger.
 *
 * The bar does not know about the SSH session; it encodes bytes and hands them over.
 */
@Composable
fun TerminalKeyBarView(
    appCursor: Boolean,
    onBytes: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    rows: List<List<BarKey>> = TerminalKeyBar.DefaultRows,
) {
    var modifiers by remember { mutableStateOf(TerminalKeyBar.ModifierState()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BarBackground),
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (key in row) {
                    if (key is BarKey.Spacer) {
                        Box(Modifier.width(8.dp).height(34.dp))
                        continue
                    }
                    val latched = when (key) {
                        is BarKey.Ctrl -> modifiers.ctrl
                        is BarKey.Alt -> modifiers.alt
                        is BarKey.Shift -> modifiers.shift
                        else -> false
                    }
                    KeyButton(
                        label = key.label,
                        latched = latched,
                        onClick = {
                            val (bytes, next) = TerminalKeyBar.press(key, modifiers, appCursor)
                            modifiers = next
                            if (bytes.isNotEmpty()) onBytes(bytes)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, latched: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .width(if (label.length > 2) 62.dp else 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (latched) KeyAccent else KeyIdle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (latched) BarBackground else KeyText,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}
