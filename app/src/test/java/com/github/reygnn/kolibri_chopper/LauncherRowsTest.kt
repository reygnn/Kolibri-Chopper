package com.github.reygnn.kolibri_chopper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [LauncherLogic.rowsFor] and [LauncherLogic.rowPrefix] — the two pure
 * decision surfaces lifted out of MainActivity. These were the app's largest UNTESTED
 * branches: the primitives ([LauncherLogic.search], .tagged, .drawer, …) were covered in
 * [LauncherLogicTest], but their COMPOSITION per mode — which primitive runs for which
 * sigil, the substring arithmetic, the bare-"#"/"##"/"~" special cases — was not, because
 * it sat inside the Activity's applyFilter.
 *
 * The fake is called [Fake], NOT `Row`: [LauncherLogicTest] already has a private nested
 * `Row` Ordered fake, and rowsFor now returns the top-level [Row] type, so reusing the
 * name here would shadow it. Same trick as everywhere else — a trivial [Ordered], never a
 * ComponentName.
 */
class LauncherRowsTest {

    private data class Fake(override val key: String, val label: String) : Ordered {
        override val labelLower: String = LauncherLogic.foldLabel(label)
    }

    private val apps = listOf(
        Fake("com.a/A", "Alpha"),
        Fake("com.b/B", "Bravo"),
        Fake("com.c/C", "Charlie"),
    )
    private val favorites = linkedSetOf("com.c/C", "com.a/A") // rank: C=0, A=1
    private val hidden = setOf("com.b/B")
    private val tags = mapOf("com.a/A" to listOf("games"), "com.c/C" to listOf("work"))
    private val recents = listOf("com.b/B", "com.a/A") // most-recent-first

    private fun rowsFor(mode: Mode, trimmed: String, tagEditTag: String? = null) =
        LauncherLogic.rowsFor(mode, trimmed, apps, hidden, favorites, tags, recents, tagEditTag)

    private fun appKeys(rows: List<Row<Fake>>) =
        rows.filterIsInstance<AppRow<Fake>>().map { it.entry.key }

    private fun tagNames(rows: List<Row<Fake>>) =
        rows.filterIsInstance<TagRow>().map { it.name }

    // ---- NORMAL --------------------------------------------------------------

    @Test fun `empty prompt shows the favorites in stored rank order`() {
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.NORMAL, "")))
    }

    @Test fun `empty prompt falls back to the drawer when no favorites are set`() {
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "", apps, hidden, favorites = emptySet(), tags, recents, null,
        )
        // Drawer drops the hidden non-favorite B; A and C remain.
        assertEquals(listOf("com.a/A", "com.c/C"), appKeys(rows))
    }

    @Test fun `star shows the drawer keeping favorites and dropping hidden non-favorites`() {
        // B is hidden and not a favorite -> gone. Favorites sink to the bottom in rank order.
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.NORMAL, "*")))
    }

    @Test fun `plain search spans all apps including hidden ones`() {
        assertEquals(listOf("com.b/B"), appKeys(rowsFor(Mode.NORMAL, "brav")))
    }

    // ---- COMMAND -------------------------------------------------------------

    @Test fun `bare tilde lists every command harmless-first as CommandRows`() {
        val rows = rowsFor(Mode.COMMAND, "~")
        assertTrue(rows.all { it is CommandRow })
        assertEquals(LauncherLogic.COMMANDS.map { it.first }, rows.filterIsInstance<CommandRow>().map { it.name })
        assertEquals("~load", (rows.first() as CommandRow).name)
    }

    @Test fun `tilde prefix narrows the command overview`() {
        val rows = rowsFor(Mode.COMMAND, "~b")
        assertEquals(1, rows.size)
        assertEquals(Command.BACKUP, (rows.single() as CommandRow).command)
    }

    // ---- TAG_FILTER ("#") ----------------------------------------------------

    @Test fun `bare hash lists the in-use tags sorted, as TagRows`() {
        assertEquals(listOf("games", "work"), tagNames(rowsFor(Mode.TAG_FILTER, "#")))
    }

    @Test fun `bare hash drops ghost tags whose apps are all uninstalled`() {
        val ghost = tags + ("com.z/Z" to listOf("archived"))
        val rows = LauncherLogic.rowsFor(Mode.TAG_FILTER, "#", apps, hidden, favorites, ghost, recents, null)
        assertEquals(listOf("games", "work"), tagNames(rows)) // "archived" has no live app
    }

    @Test fun `hash with text prefix-matches tags and returns their apps`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.TAG_FILTER, "#gam")))
    }

    // ---- TAG_EDIT ("##") -----------------------------------------------------

    @Test fun `bare double-hash lists ALL tags including ghosts, as TagRows`() {
        val ghost = tags + ("com.z/Z" to listOf("archived"))
        val rows = LauncherLogic.rowsFor(Mode.TAG_EDIT, "##", apps, hidden, favorites, ghost, recents, null)
        assertEquals(listOf("archived", "games", "work"), tagNames(rows))
    }

    @Test fun `double-hash with a chosen tag lists every app unreordered`() {
        val rows = rowsFor(Mode.TAG_EDIT, "##work", tagEditTag = "work")
        assertEquals(apps.map { it.key }, appKeys(rows))
    }

    // ---- RECENTS / FAV_REORDER / edit modes ----------------------------------

    @Test fun `recents are reversed so the most recent sits nearest the prompt`() {
        // recents = [B, A] most-recent-first; reversed for isStackFromBottom -> [A, B].
        assertEquals(listOf("com.a/A", "com.b/B"), appKeys(rowsFor(Mode.RECENTS, "?")))
    }

    @Test fun `reorder lists exactly the favorites in stored order, ignoring trailing text`() {
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.FAV_REORDER, "!!ignored")))
    }

    @Test fun `edit modes list every app narrowed by the text after the sigil`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.FAV_EDIT, "!al")))
        assertEquals(listOf("com.b/B"), appKeys(rowsFor(Mode.HIDDEN_EDIT, "-brav")))
    }

    // ---- rowPrefix -----------------------------------------------------------

    @Test fun `rowPrefix picks the right marker per edit mode`() {
        assertEquals("[x] ", LauncherLogic.rowPrefix(Mode.HIDDEN_EDIT, isHidden = true, false, false, false))
        assertEquals("[ ] ", LauncherLogic.rowPrefix(Mode.HIDDEN_EDIT, isHidden = false, false, false, false))
        assertEquals("[x] ", LauncherLogic.rowPrefix(Mode.FAV_EDIT, false, isFavorite = true, false, false))
        assertEquals("\u00BB ", LauncherLogic.rowPrefix(Mode.FAV_REORDER, false, false, isPicked = true, false))
        assertEquals("  ", LauncherLogic.rowPrefix(Mode.FAV_REORDER, false, false, isPicked = false, false))
        assertEquals("[x] ", LauncherLogic.rowPrefix(Mode.TAG_EDIT, false, false, false, isTagged = true))
    }

    @Test fun `rowPrefix is empty in the read modes that carry no marker`() {
        for (m in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND)) {
            assertEquals("", LauncherLogic.rowPrefix(m, true, true, true, true))
        }
    }

    @Test fun `edit-mode markers are column-aligned (equal width)`() {
        assertEquals("[ ] ".length, "[x] ".length)
        assertEquals("  ".length, "\u00BB ".length)
    }
}
