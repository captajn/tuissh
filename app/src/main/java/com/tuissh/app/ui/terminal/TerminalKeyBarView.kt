package com.tuissh.app.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.terminal.input.TerminalKeyBar
import com.tuissh.app.terminal.input.TerminalKeyBar.BarKey
import com.tuissh.app.ui.theme.LocalMacOsColors

/**
 * macOS 27 Magic Keyboard style auxiliary key bar for the terminal.
 */
@Composable
fun TerminalKeyBarView(
    appCursor: Boolean,
    onBytes: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    rows: List<List<BarKey>> = TerminalKeyBar.DefaultRows,
) {
    val tokens = LocalMacOsColors.current
    var modifiers by remember { mutableStateOf(TerminalKeyBar.ModifierState()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.keyBarTray)
            .border(
                BorderStroke(0.75.dp, tokens.specularBorderTop),
            )
            .padding(vertical = 3.dp),
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
                        Box(Modifier.width(8.dp).height(32.dp))
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
    val tokens = LocalMacOsColors.current
    val keyShape = RoundedCornerShape(7.dp)

    Box(
        modifier = Modifier
            .height(32.dp)
            .width(if (label.length > 2) 58.dp else 38.dp)
            .clip(keyShape)
            .background(if (latched) tokens.primaryAccent else tokens.keycapSurface)
            .border(
                BorderStroke(
                    width = 0.75.dp,
                    color = if (latched) tokens.primaryAccent else tokens.keycapBorder,
                ),
                shape = keyShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (latched) Color.White else tokens.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}
