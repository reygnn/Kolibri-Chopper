package com.github.reygnn.sigil_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [SigilConfig.snapshot]. Plain JUnit4, no Robolectric: the class
 * holds no framework types, only collections.
 *
 * These exist for ONE invariant, and it is a load-bearing one. snapshot() hands a copy to
 * the background loader while the main thread keeps mutating the live config. If any
 * container were shared rather than copied, a background read could race a main-thread
 * write on a collection that is not thread-safe — a ConcurrentModificationException, or a
 * half-mutated read, in a HOME app that must not crash. The tag map is the subtle case:
 * copying the outer map alone still leaves the inner MutableLists aliased, which is
 * exactly the "simplification" a future reader is most likely to reach for.
 */
class SigilConfigTest {

    private fun populated() = SigilConfig(
        hidden = linkedSetOf("h/1", "h/2"),
        favorites = linkedSetOf("f/1", "f/2", "f/3"),
        names = linkedMapOf("f/1" to "Custom"),
        tags = linkedMapOf(
            "f/1" to mutableListOf("work", "chat"),
            "f/2" to mutableListOf("media"),
        ),
    )

    // ---- the deep-copy invariant ---------------------------------------------

    /** The one a shallow LinkedHashMap(tags) copy would break: outer map copied, inner
     *  lists still shared with the live config. */
    @Test
    fun `mutating a tag list on the original does not reach the snapshot`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        cfg.tags["f/1"]!! += "added-after-snapshot"
        cfg.tags["f/2"]!!.clear()

        assertEquals(listOf("work", "chat"), snap.tags["f/1"])
        assertEquals(listOf("media"), snap.tags["f/2"])
    }

    /** ...and the other direction, since the loader holds the snapshot while the main
     *  thread holds the original. */
    @Test
    fun `mutating a tag list on the snapshot does not reach the original`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        snap.tags["f/1"]!! += "only-in-snapshot"

        assertEquals(listOf("work", "chat"), cfg.tags["f/1"])
    }

    /** Every inner list must be a distinct object, not just equal — identity is what
     *  decides whether two threads can collide on it. */
    @Test
    fun `each tag list is a distinct object`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        for (key in cfg.tags.keys) {
            assertNotSame("tag list for $key is shared", cfg.tags[key], snap.tags[key])
        }
    }

    @Test
    fun `the four containers are distinct objects`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        assertNotSame(cfg.hidden, snap.hidden)
        assertNotSame(cfg.favorites, snap.favorites)
        assertNotSame(cfg.names, snap.names)
        assertNotSame(cfg.tags, snap.tags)
    }

    @Test
    fun `adding to the original's hidden, favorites or names does not reach the snapshot`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        cfg.hidden += "h/3"
        cfg.favorites += "f/4"
        cfg.names["f/2"] = "Late"
        cfg.tags["f/3"] = mutableListOf("new-key")

        assertEquals(setOf("h/1", "h/2"), snap.hidden)
        assertEquals(setOf("f/1", "f/2", "f/3"), snap.favorites)
        assertEquals(mapOf("f/1" to "Custom"), snap.names)
        assertTrue("f/3" !in snap.tags)
    }

    // ---- what the copy must preserve -----------------------------------------

    /** Favorites order IS the rank the rows are laid out by, so a snapshot that copied
     *  the members but lost the order would reorder the user's home screen. */
    @Test
    fun `favorites keep their order across a snapshot`() {
        val cfg = SigilConfig(favorites = linkedSetOf("z/1", "a/1", "m/1"))

        assertEquals(listOf("z/1", "a/1", "m/1"), cfg.snapshot().favorites.toList())
    }

    @Test
    fun `a snapshot carries every value across`() {
        val cfg = populated()
        val snap = cfg.snapshot()

        assertEquals(cfg.hidden, snap.hidden)
        assertEquals(cfg.favorites, snap.favorites)
        assertEquals(cfg.names, snap.names)
        assertEquals(cfg.tags, snap.tags)
    }

    @Test
    fun `an empty config snapshots to an empty config`() {
        val snap = SigilConfig().snapshot()

        assertTrue(snap.hidden.isEmpty())
        assertTrue(snap.favorites.isEmpty())
        assertTrue(snap.names.isEmpty())
        assertTrue(snap.tags.isEmpty())
    }
}
