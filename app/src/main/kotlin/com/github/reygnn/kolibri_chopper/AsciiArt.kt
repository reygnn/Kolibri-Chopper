package com.github.reygnn.kolibri_chopper

/**
 * The hand-curated ASCII/block-art motifs for the 0.3 wallpaper backdrop
 * ([AsciiWallpaperView]). Each motif is embedded here as pure content — no
 * framework, no dependencies — and rendered as a grid of '█' (FULL BLOCK) cells.
 *
 * This is where the actual creative work lives, not the renderer. For the first
 * 0.3 test slice there is a single motif; more get added here as plain constants.
 */
internal object AsciiArt {

    /**
     * One 喜 (single "happiness"), 15 columns wide — the building block for the
     * 囍 pair below. Only leading blocks/spaces matter; [mirror] pads every line to
     * the block's width, so trailing spaces are never hand-counted. Interior blank
     * lines are part of the art (the gaps between strokes) and are preserved.
     */
    private val XI_HALF = """
        ███████████████
               █
            ███████

           █████████
           █       █
           █       █
           █████████

            ██   ██
        ███████████████

           █████████
           █       █
           █       █
           █████████
    """

    /**
     * 囍 ("double happiness") — two 喜 side by side, drawn in FULL BLOCK outline.
     * Rendered from block cells, not the real CJK glyph, so it needs no CJK font
     * and never breaks the grid. The outline (vs. a solid fill) keeps lit pixels
     * sparse — friendlier to the AMOLED backdrop it sits on. GAP is the columns of
     * black between the two halves: tune it here to space the pair.
     */
    val DOUBLE_HAPPINESS: List<String> = mirror(XI_HALF, gap = 1)

    /**
     * The selectable motifs, keyed by the name persisted in chopper.json
     * (ChopperConfig.wallpaper). Insertion order is the rotation order of the "~art"
     * command; "off" (the empty name) is appended by the rotation logic, not stored
     * here. Add a motif by adding an entry.
     */
    private val motifs: Map<String, List<String>> = linkedMapOf(
        "xi" to DOUBLE_HAPPINESS,
    )

    /** The motif names in rotation order (excludes "off"). */
    val names: List<String> = motifs.keys.toList()

    /**
     * Resolve a persisted wallpaper name to its lines. An unknown name or the empty
     * "off" name yields an empty list, which [AsciiWallpaperView] draws as nothing.
     */
    fun lines(name: String): List<String> = motifs[name] ?: emptyList()

    /**
     * Compose a symmetric two-up motif from a single [half] block: trim the literal
     * to its art lines, pad each to the half's width (so the two copies stay column-
     * aligned regardless of trailing spaces), and join each line to itself across
     * [gap] columns of blank. Blank lines are preserved as blank rows.
     */
    private fun mirror(half: String, gap: Int): List<String> {
        val lines = half.trimIndent().lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        val width = lines.maxOfOrNull { it.length } ?: 0
        val between = " ".repeat(gap)
        return lines.map { val padded = it.padEnd(width); padded + between + padded }
    }
}
