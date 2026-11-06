package com.tuissh.app.terminal.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.tuissh.app.terminal.ansi.Cell
import com.tuissh.app.terminal.ansi.TerminalRow
import com.tuissh.app.terminal.ansi.CursorShape
import com.tuissh.app.terminal.ansi.TerminalMode
import com.tuissh.app.terminal.parse.VtEmulator
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Canvas-based terminal renderer.
 *
 * Replaces `NeoTerminalView` / `NeoTerminalContentView` / `NeoTerminalParser`.
 *
 * Design notes
 * ------------
 * - Metrics are measured once per (font, size) pair with a fixed probe string.
 *   Everything else is arithmetic; re-measuring per frame would dominate the
 *   frame budget.
 * - Text is laid out one row at a time. Per-run batching by style is the next
 *   optimisation; correctness first.
 * - Damage handling is coarse: a remote chunk repaints the visible grid.
 */
@Composable
fun NeoTerminalView(
    emulator: VtEmulator,
    palette: TerminalPalette,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    cursorBlink: Boolean = true,
    onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    onSelectionChanged: (Selection?) -> Unit = {},
    /**
     * Reports how many lines the user has scrolled back, so the host screen can show a
     * "jump to latest" affordance. Null means the view is following live output.
     */
    onScrollChanged: (Int) -> Unit = {},
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)

    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var blinkOn by remember { mutableStateOf(true) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var revision by remember { mutableStateOf(0L) }

    // How many lines above the live screen the user has scrolled. 0 means "at the
    // bottom, following output". Kept in the view rather than the emulator so the
    // session never has to know the user is looking at history.
    var scrollOffset by remember { mutableStateOf(0) }

    val metrics = remember(fontSize, measurer) { measureCell(measurer, fontSize) }

    val cols = if (metrics.charWidth > 0f) floor(bounds.width / metrics.charWidth).toInt() else 0
    val rows = if (metrics.lineHeight > 0f) floor(bounds.height / metrics.lineHeight).toInt() else 0

    // Keep the emulator size in sync with the layout.
    //
    // The view is the source of truth for the grid, not the constructor default:
    // the emulator starts at whatever size the caller guessed, then the first
    // layout pass resizes it to the real cell count. Text must be drawn at the
    // bottom of the grid, matching `\r\n`-driven cursor movement.
    LaunchedEffect(cols, rows) {
        if (cols > 0 && rows > 0 && (emulator.cols != cols || emulator.rows != rows)) {
            emulator.resize(rows, cols)
            onResize(cols, rows)
        }
    }

    // Cursor blink.
    LaunchedEffect(cursorBlink, emulator.cursorShape) {
        if (!cursorBlink) {
            blinkOn = true
            return@LaunchedEffect
        }
        while (true) {
            delay(530)
            blinkOn = !blinkOn
            revision++
        }
    }

    // The emulator is mutated off the composition thread; a monotonically
    // increasing revision re-runs the Canvas lambda without copying the grid.
    LaunchedEffect(Unit) {
        emulator.damageListener = { revision++ }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { bounds = it }
                // Drag scrolls history; a plain tap starts a selection.
                //
                // Vertical drags are consumed here rather than being turned into arrow
                // keys, because a swipe is the only way to look at output that has
                // scrolled off, and the original app worked the same way.
                .pointerInput(metrics) {
                    var accumulated = 0f
                    detectDragGestures(
                        onDragStart = {
                            accumulated = 0f
                            selection = null
                            onSelectionChanged(null)
                        },
                        onDragEnd = {
                            // A gesture that ends near the bottom returns to live
                            // output. The threshold is one row: releasing within a line
                            // of the newest text means the user was not trying to read
                            // history, and leaving a one-line gap would look like a
                            // rendering glitch.
                            if (scrollOffset <= 1) scrollOffset = 0
                            onScrollChanged(scrollOffset)
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.y
                        val perLine = metrics.lineHeight.coerceAtLeast(1f)
                        val lines = (accumulated / perLine).toInt()
                        if (lines != 0) {
                            accumulated -= lines * perLine
                            // Dragging down (positive) moves toward newer lines.
                            scrollOffset = (scrollOffset - lines)
                                .coerceIn(0, emulator.scrollbackSize)
                            onScrollChanged(scrollOffset)
                        }
                    }
                }
                .pointerInput(metrics) {
                    detectTapGestures { offset ->
                        val pos = cellAt(offset, metrics)
                        selection = Selection(pos, pos)
                        onSelectionChanged(selection)
                    }
                },
        ) {
            // Touch the revision so Compose records the dependency.
            @Suppress("UNUSED_EXPRESSION")
            revision
            drawTerminal(
                emulator = emulator,
                palette = palette,
                metrics = metrics,
                measurer = measurer,
                selection = selection,
                showCursor = blinkOn && emulator.isModeSet(TerminalMode.SHOW_CURSOR),
                scrollOffset = scrollOffset,
            )
        }
    }
}

/** Two grid corners; inclusive. */
data class Selection(val start: CellPos, val end: CellPos)

data class CellPos(val col: Int, val row: Int)

internal data class CellMetrics(
    val charWidth: Float,
    val lineHeight: Float,
    val baseline: Float,
    val fontSize: TextUnit,
) {
    val valid: Boolean
        get() = charWidth.isFinite() && charWidth > 0f &&
            lineHeight.isFinite() && lineHeight > 0f
}

/**
 * Measure the monospace advance and line height.
 *
 * A string of `M` gives the advance; the layout height gives the row pitch. Both
 * come from the same style the renderer paints with, so they cannot drift.
 */
internal fun measureCell(measurer: TextMeasurer, fontSize: TextUnit): CellMetrics {
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        letterSpacing = 0.sp,
    )
    val probe = "MMMMMMMMMMMMMMMMMMMM"
    val result = measurer.measure(
        text = probe,
        style = style,
        constraints = Constraints(),
        maxLines = 1,
        softWrap = false,
    )
    val advance = if (result.size.width > 0) result.size.width / probe.length.toFloat() else 0f
    val height = if (result.size.height > 0) result.size.height.toFloat() else 0f
    return CellMetrics(
        charWidth = advance,
        lineHeight = height,
        baseline = if (result.lineCount > 0) result.firstBaseline else 0f,
        fontSize = fontSize,
    )
}

internal fun cellAt(position: Offset, metrics: CellMetrics): CellPos {
    if (!metrics.valid) return CellPos(0, 0)
    return CellPos(
        col = floor(position.x / metrics.charWidth).toInt().coerceAtLeast(0),
        row = floor(position.y / metrics.lineHeight).toInt().coerceAtLeast(0),
    )
}

/**
 * Paint the visible grid: background, cell backgrounds and rules, selection,
 * glyphs, cursor.
 */
internal fun DrawScope.drawTerminal(
    emulator: VtEmulator,
    palette: TerminalPalette,
    metrics: CellMetrics,
    measurer: TextMeasurer,
    selection: Selection?,
    showCursor: Boolean,
    /** Lines scrolled back from the live screen; 0 follows output. */
    scrollOffset: Int = 0,
) {
    if (!metrics.valid) return

    val cols = floor(size.width / metrics.charWidth).toInt()
    val rows = floor(size.height / metrics.lineHeight).toInt()
    if (cols <= 0 || rows <= 0) return

    drawRect(color = palette.background, size = size)

    val buffer = emulator.buffer
    val visibleRows = minOf(rows, emulator.rows)
    val visibleCols = minOf(cols, emulator.cols)

    // Rows are addressed through this helper so scrolling history is a single change
    // rather than a coordinate transform sprinkled through the draw code.
    //
    // `scrollOffset` is clamped to what actually exists: the scrollback grows while the
    // user is reading, and an unclamped offset would point past the newest line.
    val maxOffset = emulator.scrollbackSize
    val offset = scrollOffset.coerceIn(0, maxOffset)
    val rowAt: (Int) -> TerminalRow? = { r ->
        if (offset == 0) {
            buffer.rowOrNull(r)
        } else {
            // Blend scrollback and screen: the first `offset` visible rows come from
            // history, the rest from the live screen.
            val historyIndex = maxOffset - offset + r
            if (historyIndex < maxOffset) buffer.scrollbackRow(historyIndex) else buffer.rowOrNull(historyIndex - maxOffset)
        }
    }
    if (visibleRows <= 0 || visibleCols <= 0) return

    // --- cell backgrounds and rules --------------------------------------
    for (r in 0 until visibleRows) {
        val row = rowAt(r) ?: continue
        for (c in 0 until visibleCols) {
            val packedBg = row.bg[c]
            val packedFg = row.fg[c]
            val reversed = (Cell.flagsOf(packedFg) and Cell.FLAG_REVERSE) != 0
            val bgColor = palette.resolve(packedBg, palette.background)
            val effective = if (reversed) {
                palette.resolve(packedFg, palette.foreground)
            } else if (bgColor != palette.background) {
                bgColor
            } else {
                null
            }
            val x = c * metrics.charWidth
            val y = r * metrics.lineHeight
            if (effective != null) {
                drawRect(effective, Offset(x, y), Size(metrics.charWidth, metrics.lineHeight))
            }
            val flags = Cell.flagsOf(packedBg)
            if (flags and Cell.FLAG_UNDERLINE != 0) {
                drawLine(
                    color = palette.resolve(packedFg, palette.foreground),
                    start = Offset(x, y + metrics.lineHeight - 2f),
                    end = Offset(x + metrics.charWidth, y + metrics.lineHeight - 2f),
                    strokeWidth = 1f,
                )
            }
            if (flags and Cell.FLAG_STRIKE != 0) {
                drawLine(
                    color = palette.resolve(packedFg, palette.foreground),
                    start = Offset(x, y + metrics.lineHeight / 2f),
                    end = Offset(x + metrics.charWidth, y + metrics.lineHeight / 2f),
                    strokeWidth = 1f,
                )
            }
        }
    }

    // --- selection --------------------------------------------------------
    if (selection != null) {
        val s = normalizeSelection(selection, visibleRows, visibleCols)
        for (r in s[0]..s[2]) {
            val start = if (r == s[0]) s[1] else 0
            val end = if (r == s[2]) s[3] else visibleCols - 1
            if (start > end) continue
            drawRect(
                color = palette.selection.copy(alpha = 0.55f),
                topLeft = Offset(start * metrics.charWidth, r * metrics.lineHeight),
                size = Size((end - start + 1) * metrics.charWidth, metrics.lineHeight),
            )
        }
    }

    // --- glyphs -----------------------------------------------------------
    //
    // Each row is measured at its natural width and clipped to the grid, so a line
    // wider than the screen is truncated at the right edge rather than wrapped by
    // the text engine. The emulator already wrapped at its own `cols`; a second
    // wrap here would desynchronise the visible row count from the buffer.
    val style = TextStyle(
        color = palette.foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = metrics.fontSize,
        letterSpacing = 0.sp,
    )
    val gridWidth = (visibleCols * metrics.charWidth).toInt().coerceAtLeast(1)
    for (r in 0 until visibleRows) {
        val text = rowAt(r)?.trimmedText() ?: ""
        if (text.isEmpty()) continue
        val layout = measurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = gridWidth),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        // Nudge right by one pixel: glyphs have a left side bearing and column 0
        // otherwise clips against the canvas edge, losing the first character.
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(1f, r * metrics.lineHeight),
        )
    }

    // --- cursor -----------------------------------------------------------
    if (!showCursor) return
    // The cursor belongs to the live screen, not to the scrolled view. While the user
    // is reading history it must be hidden rather than drawn at a row that now shows
    // older output, which would put it in a meaningless place.
    if (offset > 0) return
    val curRow = emulator.cursorRow.coerceIn(0, visibleRows - 1)
    val curCol = emulator.cursorCol.coerceIn(0, visibleCols - 1)
    val topLeft = Offset(curCol * metrics.charWidth, curRow * metrics.lineHeight)
    val cellSize = Size(metrics.charWidth, metrics.lineHeight)
    when (emulator.cursorShape) {
        CursorShape.UNDERLINE, CursorShape.UNDERLINE_BLINK -> drawRect(
            color = palette.cursor,
            topLeft = Offset(topLeft.x, topLeft.y + metrics.lineHeight - 2f),
            size = Size(metrics.charWidth, 2f),
        )
        CursorShape.BAR, CursorShape.BAR_BLINK -> drawRect(
            color = palette.cursor,
            topLeft = topLeft,
            size = Size(2f, metrics.lineHeight),
        )
        else -> {
            drawRect(palette.cursor.copy(alpha = 0.40f), topLeft, cellSize)
            drawRect(palette.cursor, topLeft, cellSize, style = Stroke(width = 1.5f))
        }
    }
}

/** Order two selection corners and clamp them to the visible grid. */
internal fun normalizeSelection(selection: Selection, rows: Int, cols: Int): IntArray {
    val a = selection.start
    val b = selection.end
    val swap = a.row > b.row || (a.row == b.row && a.col > b.col)
    val start = if (swap) b else a
    val end = if (swap) a else b
    return intArrayOf(
        start.row.coerceIn(0, rows - 1),
        start.col.coerceIn(0, cols - 1),
        end.row.coerceIn(0, rows - 1),
        end.col.coerceIn(0, cols - 1),
    )
}

/** Grid size label used in the status strip, e.g. `120x34`. */
fun gridLabel(cols: Int, rows: Int): String = "${cols}x${rows}"

/** Round a pixel extent up to whole cells. */
internal fun cellsForPixels(pixels: Float, cell: Float): Int =
    if (cell <= 0f) 0 else ceil(pixels / cell).toInt()
