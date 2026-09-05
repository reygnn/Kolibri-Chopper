package com.github.reygnn.kolibri_chopper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

/**
 * JVM unit tests for the pure launcher logic. No Android runtime, no Robolectric —
 * these run on the plain JVM because [LauncherLogic] touches no framework types.
 * The rows are a trivial [Ordered] fake, so the tests never build a ComponentName.
 */
class LauncherLogicTest {

    private data class Row(override val key: String, override val labelLower: String) : Ordered

    /** Rows whose key doubles as the (already ROOT-folded) label, for ordering tests. */
    private fun rows(vararg keys: String): List<Row> = keys.map { Row(it, it) }

    private fun List<Ordered>.keys(): List<String> = map { it.key }

    // ---- parseMode ----------------------------------------------------------

    @Test fun `empty and plain text are NORMAL`() {
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode(""))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("gmail"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("*"))
    }

    @Test fun `dash is hidden-edit`() {
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("-"))
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("-maps"))
    }

    @Test fun `hash is tag-filter`() {
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    @Test fun `single bang is fav-edit`() {
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!"))
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!maps"))
    }

    @Test fun `double bang is reorder and wins over single bang`() {
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!"))
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!maps"))
    }

    @Test fun `question mark is recents`() {
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?"))
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?maps"))
    }

    // ---- reorder ------------------------------------------------------------

    @Test fun `reorder moves a row down onto the target's slot`() {
        assertEquals(listOf("b", "c", "a", "d"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "a", "c"))
    }

    @Test fun `reorder moves a row up onto the target's slot`() {
        assertEquals(listOf("a", "d", "b", "c"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "d", "b"))
    }

    @Test fun `reorder handles adjacent moves both directions`() {
        assertEquals(listOf("b", "a", "c"), LauncherLogic.reorder(listOf("a", "b", "c"), "a", "b"))
        assertEquals(listOf("a", "c", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "b"))
    }

    @Test fun `reorder moves a row onto the very first slot`() {
        // Target at index 0 (an up-move inserting AT dest 0) — the boundary the other
        // cases never exercise; a future off-by-one here would land the row at index 1.
        assertEquals(listOf("c", "a", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "a"))
    }

    @Test fun `reorder returns null for a no-op or impossible move`() {
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "a", "a"))   // same row
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "x", "a"))   // picked absent
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "a", "x"))   // target absent
    }

    @Test fun `reorder does not mutate its input`() {
        val order = listOf("a", "b", "c")
        LauncherLogic.reorder(order, "a", "c")
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test fun `reorder on a single-element list is always a no-op`() {
        assertNull(LauncherLogic.reorder(listOf("only"), "only", "only"))  // same row
        assertNull(LauncherLogic.reorder(listOf("only"), "only", "x"))     // target absent
    }

    // ---- foldLabel ----------------------------------------------------------

    @Test fun `foldLabel lowercases with ROOT even under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            // Under tr-TR, "I".lowercase() gives the dotless "ı". foldLabel must pin
            // Locale.ROOT so a label keeps the same "i" the search needle folds to —
            // this is the LABEL side of the invariant (search covers the needle side).
            Locale.setDefault(Locale.of("tr", "TR"))
            assertEquals("instagram", LauncherLogic.foldLabel("Instagram"))
            assertEquals("i", LauncherLogic.foldLabel("I"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `search matches a foldLabel-built row under Turkish`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.of("tr", "TR"))
            // End-to-end: the row's key is built exactly as MainActivity builds it (via
            // foldLabel -> "instagram"), and search is given the raw "I". This guards
            // search's OWN needle fold — a search that stopped folding would test
            // "instagram".contains("I") and miss. (That the two sides fold the SAME way
            // is guaranteed by both using foldLabel; the ROOT-pinning itself is pinned
            // by the standalone foldLabel and search Turkish tests above/below.)
            val all = listOf(Row("p/insta", LauncherLogic.foldLabel("Instagram")))
            assertEquals(listOf("p/insta"), LauncherLogic.search(all, "I").keys())
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---- search -------------------------------------------------------------

    @Test fun `search empty needle returns the list unchanged`() {
        val all = rows("a", "b")
        assertSame(all, LauncherLogic.search(all, ""))
    }

    @Test fun `search over an empty list is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.search(emptyList<Row>(), "x").keys())
    }

    @Test fun `search matches a case-folded substring`() {
        val all = listOf(Row("p/insta", "instagram"), Row("p/maps", "maps"))
        assertEquals(listOf("p/insta"), LauncherLogic.search(all, "INSTA").keys())
        assertEquals(emptyList<String>(), LauncherLogic.search(all, "zzz").keys())
    }

    @Test fun `search folds the needle with ROOT even under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            // Under tr-TR, "I".lowercase() would give the dotless "ı" and miss the
            // row — search must fold with Locale.ROOT so "I" still becomes "i".
            Locale.setDefault(Locale.of("tr", "TR"))
            val all = listOf(Row("p/insta", "instagram"))
            assertEquals(listOf("p/insta"), LauncherLogic.search(all, "I").keys())
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---- favoritesInDisplayOrder --------------------------------------------

    @Test fun `favoritesInDisplayOrder keeps favorites in config order and drops the rest`() {
        val all = rows("a", "b", "c")
        // Favorites config order is c-then-a, independent of the all-list order.
        val result = LauncherLogic.favoritesInDisplayOrder(all, linkedSetOf("c", "a"))
        assertEquals(listOf("c", "a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder drops a favorite whose app is not present`() {
        val all = rows("a", "b")
        val result = LauncherLogic.favoritesInDisplayOrder(all, linkedSetOf("a", "gone"))
        assertEquals(listOf("a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder with no favorites is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.favoritesInDisplayOrder(rows("a", "b"), emptySet()).keys())
    }

    // ---- drawer -------------------------------------------------------------

    @Test fun `drawer excludes hidden apps`() {
        val result = LauncherLogic.drawer(rows("a", "b", "c"), hidden = setOf("b"), favorites = emptySet())
        assertEquals(listOf("a", "c"), result.keys())
    }

    @Test fun `drawer keeps a favorite even when it is also hidden`() {
        // favoriting overrides hiding: b is hidden AND favorite, so it stays.
        val result = LauncherLogic.drawer(rows("a", "b", "c"), hidden = setOf("b"), favorites = setOf("b"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    // ---- orderWithFavorites -------------------------------------------------

    @Test fun `orderWithFavorites returns input unchanged when there are no favorites`() {
        val apps = rows("a", "b", "c")
        assertSame(apps, LauncherLogic.orderWithFavorites(apps, emptySet()))
    }

    @Test fun `orderWithFavorites puts non-favorites first then favorites in rank order`() {
        val apps = rows("a", "b", "c", "d")
        // Favorites c (rank 0) and a (rank 1); non-favorites b, d keep their order.
        val result = LauncherLogic.orderWithFavorites(apps, linkedSetOf("c", "a"))
        assertEquals(listOf("b", "d", "c", "a"), result.keys())
    }

    // ---- pushRecent ---------------------------------------------------------

    @Test fun `pushRecent puts a new key at the front`() {
        assertEquals(listOf("b", "a"), LauncherLogic.pushRecent(listOf("a"), "b", 8))
    }

    @Test fun `pushRecent moves an existing key to the front without duplicating`() {
        assertEquals(listOf("c", "a", "b"), LauncherLogic.pushRecent(listOf("a", "b", "c"), "c", 8))
    }

    @Test fun `pushRecent caps the list at the limit, dropping the oldest`() {
        // Front-inserting "d" onto a full 3-slot list evicts the oldest ("a").
        assertEquals(listOf("d", "c", "b"), LauncherLogic.pushRecent(listOf("c", "b", "a"), "d", 3))
    }

    @Test fun `pushRecent re-promoting a key never grows past the limit`() {
        assertEquals(listOf("a", "c", "b"), LauncherLogic.pushRecent(listOf("c", "b", "a"), "a", 3))
    }

    @Test fun `pushRecent does not mutate its input`() {
        val current = listOf("a", "b")
        LauncherLogic.pushRecent(current, "c", 8)
        assertEquals(listOf("a", "b"), current)
    }

    @Test fun `pushRecent with a non-positive limit yields an empty list`() {
        // Not reachable in production (RECENTS_LIMIT is 8), but pin take(0)'s behaviour
        // so a bad limit degrades to "no recents" rather than throwing.
        assertEquals(emptyList<String>(), LauncherLogic.pushRecent(listOf("a", "b"), "c", 0))
    }

    // ---- recentsInDisplayOrder ----------------------------------------------

    @Test fun `recentsInDisplayOrder reverses so the newest sits last`() {
        val all = rows("a", "b", "c")
        // recentKeys is newest-first (c launched most recently); display is reversed
        // so the newest lands nearest the prompt (last row).
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("c", "b", "a"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder drops a recent whose app is gone`() {
        val all = rows("a", "b")
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("gone", "b", "a"))
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `recentsInDisplayOrder with no recents is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.recentsInDisplayOrder(rows("a", "b"), emptyList()).keys())
    }

    // ---- parseTags ----------------------------------------------------------

    @Test fun `parseTags folds, trims and drops empty pieces`() {
        assertEquals(listOf("work", "games"), LauncherLogic.parseTags("  Work , GAMES "))
    }

    @Test fun `parseTags de-duplicates keeping first-seen order`() {
        // "Work" and "work" fold to the same tag; the trailing empty piece is dropped.
        assertEquals(listOf("work", "fun"), LauncherLogic.parseTags("Work, fun, work, "))
    }

    @Test fun `parseTags on blank input is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.parseTags("   "))
        assertEquals(emptyList<String>(), LauncherLogic.parseTags(",, ,"))
    }

    // ---- allTags ------------------------------------------------------------

    @Test fun `allTags flattens, de-duplicates and sorts across apps`() {
        val tags = mapOf(
            "a" to listOf("work", "fun"),
            "b" to listOf("games", "work"),   // "work" shared with a
            "c" to emptyList(),
        )
        assertEquals(listOf("fun", "games", "work"), LauncherLogic.allTags(tags))
    }

    @Test fun `allTags on no tags is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.allTags(emptyMap()))
    }

    // ---- tagsInUse ----------------------------------------------------------

    @Test fun `tagsInUse lists only tags borne by an installed app, sorted`() {
        val tags = mapOf(
            "a" to listOf("work", "fun"),
            "b" to listOf("games"),
            "gone" to listOf("ghost"),   // app not in the installed list
        )
        // only a and b are installed; "ghost" is dropped, result sorted + distinct.
        assertEquals(listOf("fun", "games", "work"), LauncherLogic.tagsInUse(rows("a", "b"), tags))
    }

    @Test fun `tagsInUse is empty when no installed app is tagged`() {
        val tags = mapOf("gone" to listOf("ghost"))
        assertEquals(emptyList<String>(), LauncherLogic.tagsInUse(rows("a", "b"), tags))
    }

    // ---- tagged -------------------------------------------------------------

    private val taggedFixture = mapOf(
        "a" to listOf("work"),
        "b" to listOf("games", "fun"),
        "c" to emptyList(),                 // present but untagged
    )

    @Test fun `tagged with an empty needle lists every tagged app`() {
        // c has no tags, so it drops out; a and b keep their incoming order.
        val result = LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "")
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `tagged prefix-matches a tag`() {
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "ga").keys())
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "work").keys())
    }

    @Test fun `tagged folds the needle with ROOT`() {
        // "WORK" must still match the stored "work" tag.
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "WORK").keys())
    }

    @Test fun `tagged returns empty when nothing matches`() {
        assertEquals(emptyList<String>(), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "zzz").keys())
    }

    @Test fun `tagged drops a tag pointing at an uninstalled app`() {
        // "gone" is tagged but not in the app list, so it never appears.
        val tags = mapOf("gone" to listOf("work"), "a" to listOf("work"))
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
    }

    @Test fun `tagged prefix-match includes tags sharing the prefix`() {
        val tags = mapOf("a" to listOf("work"), "b" to listOf("workout"))
        // "work" prefixes both "work" and "workout" -> both; "worko" only "workout".
        // This is intentional: "#gam" should surface every app under a gam-ish tag.
        assertEquals(listOf("a", "b"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b"), tags, "worko").keys())
    }
}
