package com.github.reygnn.kolibri_chopper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * The 0.3 wallpaper backdrop: a STATIC, AMOLED-friendly block-art motif drawn
 * once behind the app list. No render loop — [onDraw] runs only on a motif change
 * or a resize — so it costs practically no battery. Pure Kotlin/[Canvas]: no XML,
 * no libraries, in keeping with the launcher's no-dependencies ethos.
 *
 * The motif is a monospace grid of cells; every '█' (FULL BLOCK) is a filled cell,
 * everything else (spaces) is empty. We render each filled cell as a filled
 * [Canvas.drawRect], NOT as drawText. Rendering the block glyph as text is
 * unreliable: Typeface.MONOSPACE has no '█' on many devices (Samsung included) and
 * the system substitutes a fallback glyph whose advance width differs from the
 * monospace space — so lines drift out of column alignment ("verrutschte" blocks).
 * A fixed cell grid of rects has no font, no fallback, no drift, and its cells abut
 * exactly, so strokes read as solid. (The IDEA.md plan said "one drawText per
 * line"; this achieves what that intended, more robustly.)
 *
 * The real black (#000000) lives here (the view's background), not on the app
 * list's root, so a fully lit motif still sits on true-off pixels. Cells are
 * painted in the launcher's foreground gray at a REDUCED alpha ([DIM_ALPHA]) so
 * the full-brightness labels in front stay the focus. Layout is "contain" (fit the
 * whole motif, no crop): the grid is scaled to the smaller axis ratio and centered,
 * letterboxing with black. The cell aspect matches a real monospace character cell
 * (measured once), so the art keeps the proportions it was drawn for. Set a motif
 * via [setMotif]; an empty list clears it.
 */
internal class AsciiWallpaperView(context: Context) : View(context) {

    // FILL, anti-alias OFF: rect edges land on cell boundaries that neighbours
    // share exactly, so AA would only add faint seams between abutting cells.
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
        color = (DIM_ALPHA shl 24) or (FG_RGB and 0x00FFFFFF)
    }

    // Current motif lines (empty = nothing to draw). Set only via setMotif.
    private var motif: List<String> = emptyList()

    // Cached layout, recomputed only in recompute() (motif change or resize) so
    // onDraw stays a tight fill loop with no measuring.
    private var originX = 0f       // left edge of the centered grid
    private var originY = 0f       // top edge of the centered grid
    private var cellW = 0f         // one grid cell's width
    private var cellH = 0f         // one grid cell's height

    init {
        // The real AMOLED black lives on the wallpaper view, not the app list.
        setBackgroundColor(Color.BLACK)
    }

    /** Set the motif to draw (a list of block-art lines, '█' = filled cell), or an
     *  empty list to clear it. Recomputes the layout and repaints. */
    fun setMotif(lines: List<String>) {
        motif = lines
        recompute()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recompute()
    }

    /**
     * Compute and cache the contain-scale layout: pick the largest cell size that
     * fits the whole [cols] × [rows] grid on BOTH axes, then center it. The cell's
     * width:height ratio is taken from a real monospace character cell (a one-shot
     * measurement) so the art keeps the proportions it was authored at — a square
     * cell would squash it horizontally.
     */
    private fun recompute() {
        val w = width
        val h = height
        val rows = motif.size
        val cols = motif.maxOfOrNull { it.length } ?: 0
        if (w == 0 || h == 0 || rows == 0 || cols == 0) return

        // Monospace cell aspect: advance width vs. line height at an arbitrary size
        // (both scale linearly, so only their ratio matters here).
        val probe = Paint().apply { typeface = Typeface.MONOSPACE; textSize = 100f }
        val fm = probe.fontMetrics
        val aspectW = probe.measureText("M")
        val aspectH = fm.descent - fm.ascent
        if (aspectW <= 0f || aspectH <= 0f) return

        // Contain to fit both axes, then shrink by CONTENT_SCALE so the motif keeps
        // a black margin instead of bleeding to the screen edges — otherwise a
        // full-width motif pins its outer strokes to the very edges.
        val scale = minOf(w / (cols * aspectW), h / (rows * aspectH)) * CONTENT_SCALE
        cellW = aspectW * scale
        cellH = aspectH * scale
        originX = (w - cols * cellW) / 2f
        originY = (h - rows * cellH) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (motif.isEmpty() || cellW <= 0f || cellH <= 0f) return
        // One filled rect per '█' cell. Edges are computed from the shared grid
        // lines (right of column c == left of column c+1, likewise for rows), so
        // abutting cells meet with no gap and strokes read as solid.
        for ((r, line) in motif.withIndex()) {
            val top = originY + r * cellH
            val bottom = top + cellH
            for (c in line.indices) {
                if (line[c] != FILLED) continue
                val left = originX + c * cellW
                canvas.drawRect(left, top, left + cellW, bottom, paint)
            }
        }
    }

    private companion object {
        // The motif character that marks a filled cell.
        const val FILLED = '█'

        // Fraction of the contain-fit size the motif actually occupies, leaving a
        // black margin so outer strokes don't touch the screen edges.
        const val CONTENT_SCALE = 0.8f

        // Motif tint (RGB of the launcher's foreground gray, #D4D4D4). Alpha is
        // applied separately via DIM_ALPHA.
        const val FG_RGB = 0xD4D4D4

        // Backdrop dim: the motif's alpha (0..255). Tuned by eye on the device — dark
        // enough to sit clearly behind the full-brightness labels in front of it.
        const val DIM_ALPHA = 0x30
    }
}
