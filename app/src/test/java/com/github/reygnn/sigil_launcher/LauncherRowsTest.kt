package com.github.reygnn.sigil_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `empty prompt falls back to the drawer when the set favorites are all uninstalled`() {
        // Favorites ARE configured, but none is currently launchable, so
        // favoritesInDisplayOrder is empty and the .ifEmpty branch still shows the drawer —
        // distinct from the "no favorites set" case above. This also exercises
        // orderWithFavorites with a non-empty favorites set none of whose keys are present.
        val ghostFavorites = linkedSetOf("com.x/X", "com.y/Y") // none present in `apps`
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "", apps, hidden, ghostFavorites, tags, recents, null,
        )
        assertEquals(listOf("com.a/A", "com.c/C"), appKeys(rows))
    }

    @Test fun `star shows the drawer keeping favorites and dropping hidden non-favorites`() {
        // B is hidden and not a favorite -> gone. Favorites sink to the bottom in rank order.
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.NORMAL, "*")))
    }

    @Test fun `a prefix before the star narrows the drawer to labels starting with it`() {
        // "a*" is the drawer kept to labels starting with "a": only Alpha. Charlie stays out.
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.NORMAL, "a*")))
        assertEquals(listOf("com.c/C"), appKeys(rowsFor(Mode.NORMAL, "c*")))
    }

    @Test fun `the star prefix is case-insensitive`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.NORMAL, "A*")))
    }

    @Test fun `the star prefix matches the START only, never a substring`() {
        // "lph" is inside "Alpha" but not its start, so unlike a plain search it matches nothing.
        assertTrue(appKeys(rowsFor(Mode.NORMAL, "lph*")).isEmpty())
    }

    @Test fun `the star prefix excludes a hidden non-favorite even when its name matches`() {
        // Bravo is hidden and not a favorite, so the drawer never carries it — "b*" is empty,
        // in deliberate contrast to the plain "brav" search below which reaches hidden apps.
        assertTrue(appKeys(rowsFor(Mode.NORMAL, "b*")).isEmpty())
    }

    @Test fun `the star prefix keeps a hidden FAVORITE, mirroring the bare drawer`() {
        // Favoriting overrides hiding for the drawer, so a hidden favorite survives "b*".
        val favsWithHiddenB = linkedSetOf("com.b/B")
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "b*", apps, hidden, favsWithHiddenB, tags, recents, null,
        )
        assertEquals(listOf("com.b/B"), appKeys(rows))
    }

    @Test fun `a star prefix matching several apps orders favorites last`() {
        // The shared fixture never gives >1 match for a prefix, so build one that does:
        // three "ap" apps (one a favorite) plus a non-matching banana that must drop out.
        val local = listOf(
            Fake("com.apple/A", "Apple"),
            Fake("com.applet/A", "Applet"),
            Fake("com.apricot/A", "Apricot"),
            Fake("com.banana/A", "Banana"),
        )
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "ap*", local, emptySet(), linkedSetOf("com.applet/A"),
            emptyMap(), emptyList(), null,
        )
        // Non-favorites in incoming order, the favorite sinks last (nearest the prompt).
        assertEquals(listOf("com.apple/A", "com.apricot/A", "com.applet/A"), appKeys(rows))
    }

    @Test fun `a double star matches nothing since no label starts with a literal star`() {
        assertTrue(appKeys(rowsFor(Mode.NORMAL, "**")).isEmpty())
    }

    @Test fun `a star prefix over an empty app list is empty`() {
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "a*", emptyList<Fake>(), emptySet(), emptySet(), emptyMap(), emptyList(), null,
        )
        assertTrue(appKeys(rows).isEmpty())
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

    @Test fun `tag filter surfaces a hidden app - tagging overrides hiding`() {
        // B is hidden (shared fixture) AND tagged here. TAG_FILTER runs over allApps, not
        // the drawer, so a tag is an explicit choice that overrides hiding — the same
        // contract as favoriting. The fixture's hidden app carries no tag, so this branch
        // was never exercised.
        val tagsWithHiddenApp = mapOf("com.b/B" to listOf("work"))
        val rows = LauncherLogic.rowsFor(
            Mode.TAG_FILTER, "#work", apps, hidden, favorites, tagsWithHiddenApp, recents, null,
        )
        assertEquals(listOf("com.b/B"), appKeys(rows))
    }

    /**
     * The reachability the canonicalTag design exists for, pinned end-to-end. canonicalTag
     * KEEPS a leading "!"/"-"/"~"/"?" on a tag (only "#" is stripped, because "#"+name would
     * flip the "##" sigil). Keeping them is only safe because DRILLING a tag prepends "#",
     * landing the sigil at position 1 where parseMode ignores it — so "#!work" is still a tag
     * filter, not FAV_EDIT. Nothing pinned that whole chain: a parseMode-ordering change, or
     * dropping the sigil from canonicalTag, would pass every other test yet make these tags
     * unreachable from the very "#" overview that offers them. (The tapAction/enterAction half
     * that BUILDS "#!work" from the tag row is pinned in LauncherActionsTest.)
     */
    @Test fun `a sigil-named tag is listed, stays a tag filter when drilled, and finds its app`() {
        for (name in listOf("!work", "-work", "~work", "?work", "!!work")) {
            val sigilTags = mapOf("com.a/A" to listOf(name))
            // 1. the bare "#" overview lists it (it is a tag borne by an installed app)...
            val overview = LauncherLogic.rowsFor(
                Mode.TAG_FILTER, "#", apps, hidden, favorites, sigilTags, recents, null,
            )
            assertEquals("\"$name\" missing from the # overview", listOf(name), tagNames(overview))
            // 2. ...drilling it prepends "#", which MUST stay a tag filter, not the sigil's mode.
            val drilled = "#$name"
            assertEquals("\"$drilled\" is no longer a tag filter", Mode.TAG_FILTER, LauncherLogic.parseMode(drilled))
            // 3. ...and that filter actually returns the app carrying the tag.
            val filtered = LauncherLogic.rowsFor(
                Mode.TAG_FILTER, drilled, apps, hidden, favorites, sigilTags, recents, null,
            )
            assertEquals("\"$drilled\" did not find its app", listOf("com.a/A"), appKeys(filtered))
        }
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

    // ---- tagEditTagFor -------------------------------------------------------
    //
    // The overview-vs-chosen-tag decision, pulled out of applyFilter so the raw-prompt ->
    // tag derivation is one tested mapping. It feeds rowsFor's tagEditTag parameter (above),
    // so a regression here — a substring(1) slip, a lost fold, a phantom empty tag — would
    // silently flip the bare-"##" overview into an all-apps edit list for tag "".

    @Test fun `tagEditTagFor is null outside TAG_EDIT`() {
        for (m in Mode.entries.filter { it != Mode.TAG_EDIT }) {
            assertNull("expected null for $m", LauncherLogic.tagEditTagFor(m, "##work"))
        }
    }

    @Test fun `tagEditTagFor returns the canonical tail of a chosen tag`() {
        assertEquals("work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##work"))
        assertEquals("work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##Work"))    // folded
        assertEquals("work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##  work"))  // trimmed
    }

    @Test fun `tagEditTagFor keeps the overview up for a tail that canonicalises to nothing`() {
        // A user CAN type each of these; none names a tag, so the overview stays up (null)
        // rather than the app selecting a phantom empty tag. "###"/"##," canonicalise away
        // exactly as canonicalTag pins.
        assertNull(LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##"))
        assertNull(LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "###"))
        assertNull(LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##,"))
    }

    @Test fun `tagEditTagFor keeps a sigil-named tag (the bulk-edit mirror of drilling it)`() {
        // "##!work" edits the tag "!work" in bulk — the mirror of drilling "#!work" to filter
        // it (see the reachability test below). canonicalTag keeps the leading "!".
        assertEquals("!work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##!work"))
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

    @Test fun `a bare edit-mode sigil lists every app (empty needle)`() {
        // "!" / "-" with nothing after the sigil is search(all, "") — the whole app list, so
        // anything can be toggled. Only the narrowed case was exercised above; this pins that
        // the empty-needle path reaches rowsFor unbroken (hidden apps included).
        assertEquals(apps.map { it.key }, appKeys(rowsFor(Mode.FAV_EDIT, "!")))
        assertEquals(apps.map { it.key }, appKeys(rowsFor(Mode.HIDDEN_EDIT, "-")))
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
