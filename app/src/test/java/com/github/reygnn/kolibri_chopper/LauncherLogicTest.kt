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

    @Test fun `hash is hidden-edit`() {
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("#"))
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("#maps"))
    }

    @Test fun `single bang is fav-edit`() {
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!"))
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!maps"))
    }

    @Test fun `double bang is reorder and wins over single bang`() {
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!"))
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!maps"))
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

    // ---- search -------------------------------------------------------------

    @Test fun `search empty needle returns the list unchanged`() {
        val all = rows("a", "b")
        assertSame(all, LauncherLogic.search(all, ""))
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
}
