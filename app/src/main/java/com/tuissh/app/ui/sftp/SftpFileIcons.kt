package com.tuissh.app.ui.sftp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tuissh.app.sftp.SftpClient
import com.tuissh.app.ui.theme.LocalMacOsColors

/**
 * Custom vector renderer for macOS 27 Finder folder and document icons.
 */
object SftpFileIcons {

    @Composable
    fun ItemIcon(
        entry: SftpClient.Entry,
        modifier: Modifier = Modifier.size(28.dp),
    ) {
        val tokens = LocalMacOsColors.current
        when {
            entry.isSymlink -> SymlinkIcon(modifier = modifier, isDark = tokens.isDark)
            entry.isDirectory -> FolderIcon(modifier = modifier, isDark = tokens.isDark)
            else -> DocumentIcon(
                filename = entry.name,
                modifier = modifier,
                isDark = tokens.isDark,
            )
        }
    }

    @Composable
    fun FolderIcon(
        modifier: Modifier = Modifier.size(28.dp),
        isDark: Boolean = LocalMacOsColors.current.isDark,
    ) {
        val topColor = if (isDark) Color(0xFF4CA5FF) else Color(0xFF64C8FF)
        val bottomColor = if (isDark) Color(0xFF167BFF) else Color(0xFF268DFF)
        val tabColor = if (isDark) Color(0xFF3892F3) else Color(0xFF4EB8FF)
        val borderColor = if (isDark) Color(0x66FFFFFF) else Color(0x330055CC)

        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height

            // 1. Back Tab (top left)
            val tabPath = Path().apply {
                moveTo(w * 0.08f, h * 0.32f)
                lineTo(w * 0.08f, h * 0.16f)
                quadraticTo(w * 0.08f, h * 0.10f, w * 0.15f, h * 0.10f)
                lineTo(w * 0.42f, h * 0.10f)
                quadraticTo(w * 0.48f, h * 0.10f, w * 0.52f, h * 0.18f)
                lineTo(w * 0.58f, h * 0.32f)
                close()
            }
            drawPath(path = tabPath, color = tabColor)

            // Back folder body line
            drawRoundRect(
                color = tabColor,
                topLeft = Offset(w * 0.08f, h * 0.22f),
                size = Size(w * 0.84f, h * 0.65f),
                cornerRadius = CornerRadius(w * 0.12f, h * 0.12f),
            )

            // 2. Front Folder Body with subtle perspective angle
            val frontPath = Path().apply {
                val cr = w * 0.12f
                moveTo(w * 0.06f, h * 0.32f)
                lineTo(w * 0.94f, h * 0.32f)
                lineTo(w * 0.92f, h * 0.88f - cr)
                quadraticTo(w * 0.92f, h * 0.88f, w * 0.92f - cr, h * 0.88f)
                lineTo(w * 0.08f + cr, h * 0.88f)
                quadraticTo(w * 0.08f, h * 0.88f, w * 0.08f, h * 0.88f - cr)
                close()
            }

            drawPath(
                path = frontPath,
                brush = Brush.verticalGradient(
                    colors = listOf(topColor, bottomColor),
                    startY = h * 0.32f,
                    endY = h * 0.88f,
                ),
            )

            // Specular border
            drawPath(
                path = frontPath,
                color = borderColor,
                style = Stroke(width = 1.2f),
            )
        }
    }

    @Composable
    fun DocumentIcon(
        filename: String,
        modifier: Modifier = Modifier.size(28.dp),
        isDark: Boolean = LocalMacOsColors.current.isDark,
    ) {
        val ext = filename.substringAfterLast('.', "").lowercase()
        val accentColor = when (ext) {
            "sh", "bash_history", "zsh", "py", "json", "yml", "yaml", "conf", "config", "pid", "toml" ->
                if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500) // Orange
            "txt", "md", "log", "rst", "csv" ->
                if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF) // Blue
            "zip", "tar", "gz", "7z", "rar", "pem", "pub", "key", "cert" ->
                if (isDark) Color(0xFFBF5AF2) else Color(0xFFAF52DE) // Purple
            "bin", "iso", "img", "exe", "apk" ->
                if (isDark) Color(0xFF30D158) else Color(0xFF28CD41) // Green
            "png", "jpg", "jpeg", "gif", "svg", "webp" ->
                if (isDark) Color(0xFFFF375F) else Color(0xFFFF2D55) // Pink
            else ->
                if (isDark) Color(0xFF8E8E93) else Color(0xFF8E8E93) // Grey
        }

        val paperBg = if (isDark) Color(0xFF252732) else Color(0xFFFCFCFE)
        val paperBorder = if (isDark) Color(0x3DFFFFFF) else Color(0x33000000)
        val flapColor = if (isDark) Color(0xFF363948) else Color(0xFFE2E5EE)
        val lineMuted = if (isDark) Color(0x40FFFFFF) else Color(0x26000000)

        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height

            val left = w * 0.14f
            val top = h * 0.08f
            val right = w * 0.86f
            val bottom = h * 0.92f
            val fold = w * 0.24f
            val cr = w * 0.08f

            // Document body with folded corner at top-right
            val body = Path().apply {
                moveTo(left + cr, top)
                lineTo(right - fold, top)
                lineTo(right, top + fold)
                lineTo(right, bottom - cr)
                quadraticTo(right, bottom, right - cr, bottom)
                lineTo(left + cr, bottom)
                quadraticTo(left, bottom, left, bottom - cr)
                lineTo(left, top + cr)
                quadraticTo(left, top, left + cr, top)
                close()
            }

            drawPath(path = body, color = paperBg, style = Fill)
            drawPath(path = body, color = paperBorder, style = Stroke(width = 1f))

            // Fold flap at top-right
            val flap = Path().apply {
                moveTo(right - fold, top)
                lineTo(right - fold, top + fold)
                lineTo(right, top + fold)
                close()
            }
            drawPath(path = flap, color = flapColor, style = Fill)
            drawPath(path = flap, color = paperBorder, style = Stroke(width = 1f))

            // Two subtle content lines
            val lineX = left + w * 0.12f
            val lineW = w * 0.45f
            drawLine(
                color = lineMuted,
                start = Offset(lineX, top + h * 0.36f),
                end = Offset(lineX + lineW, top + h * 0.36f),
                strokeWidth = 2.5f,
            )
            drawLine(
                color = lineMuted,
                start = Offset(lineX, top + h * 0.50f),
                end = Offset(lineX + lineW * 0.8f, top + h * 0.50f),
                strokeWidth = 2.5f,
            )

            // Accent indicator at bottom
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(lineX, bottom - h * 0.18f),
                size = Size(w * 0.38f, h * 0.07f),
                cornerRadius = CornerRadius(h * 0.035f, h * 0.035f),
            )
        }
    }

    @Composable
    fun SymlinkIcon(
        modifier: Modifier = Modifier.size(28.dp),
        isDark: Boolean = LocalMacOsColors.current.isDark,
    ) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            FolderIcon(modifier = modifier, isDark = isDark)
            Canvas(modifier = Modifier.size(12.dp)) {
                // Diagonal curved arrow indicating shortcut/symlink
                val arrowColor = Color.White
                val path = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.8f)
                    quadraticTo(
                        size.width * 0.4f, size.height * 0.3f,
                        size.width * 0.8f, size.height * 0.2f,
                    )
                }
                drawPath(path = path, color = arrowColor, style = Stroke(width = 2.5f))
            }
        }
    }
}

