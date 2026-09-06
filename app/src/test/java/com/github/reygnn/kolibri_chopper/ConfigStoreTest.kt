package com.github.reygnn.kolibri_chopper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for [ConfigStore] — the .bak rotation state machine.
 *
 * This is the highest-consequence logic in the app and the reason ConfigStore exists as a
 * separate class: getting the rotation wrong silently destroys the last known-good backup.
 * It has already happened once here (commit 7e4745d, "fix: .bak-Rotation validiert den
 * Primary" — rotation used to promote an unvalidated, possibly torn primary over a good
 * .bak). Nothing pinned that fix until these tests.
 *
 * Real files in a real temp directory, no mocking of the filesystem: the whole point is
 * the interaction between rename, parse and the two files. Only the directory fsync is
 * stubbed, because android.system.Os is the one call that cannot run on a plain JVM —
 * file CONTENTS are still synced for real via java.io.FileDescriptor.sync().
 */
class ConfigStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: ConfigStore
    private val logs = mutableListOf<String>()
    private var dirSyncs = 0

    private val primary get() = File(dir, "chopper.json")
    private val backup get() = File(dir, "chopper.json.bak")
    private val temp get() = File(dir, "chopper.json.tmp")

    @Before
    fun setUp() {
        dir = tmp.newFolder("files")
        store = ConfigStore(
            dir = dir,
            log = { msg, _ -> logs += msg },
            syncDir = { dirSyncs++ },
        )
    }

    private fun cfg(vararg favorites: String) =
        ChopperConfig(favorites = LinkedHashSet(favorites.toList()))

    private fun json(vararg favorites: String) = ConfigJson.serialize(cfg(*favorites))

    private fun loggedAbout(fragment: String) = logs.any { fragment in it }

    // ---- load ----------------------------------------------------------------

    @Test
    fun `a fresh install loads an empty config without logging`() {
        val loaded = store.load()

        assertTrue(loaded.favorites.isEmpty())
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `a good primary is loaded and bak mirror is not consulted`() {
        primary.writeText(json("a/1"))
        backup.writeText(json("SHOULD-NOT-BE-READ"))

        assertEquals(listOf("a/1"), store.load().favorites.toList())
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `a torn primary falls back to bak mirror`() {
        primary.writeText("{ this is not json")
        backup.writeText(json("from/bak"))

        assertEquals(listOf("from/bak"), store.load().favorites.toList())
        assertTrue(loggedAbout("recovered from .bak"))
    }

    /** The heal: after recovering, the bad primary is rewritten so later loads stop
     *  hitting the fallback and we are never left running on a single copy. */
    @Test
    fun `recovering from bak mirror heals the primary in place`() {
        primary.writeText("{ torn")
        backup.writeText(json("from/bak"))

        store.load()

        assertEquals(listOf("from/bak"), ConfigJson.parse(primary.readText())!!.favorites.toList())
    }

    /**
     * The load-bearing one, and the shape of the bug 7e4745d fixed: healing must NOT
     * rotate. At that moment .bak is the ONLY good copy and the primary is the torn file
     * being replaced — rotating would overwrite the good backup with garbage.
     */
    @Test
    fun `healing does not rotate the torn primary onto the good bak mirror`() {
        primary.writeText("{ torn")
        backup.writeText(json("from/bak"))

        store.load()

        assertEquals(listOf("from/bak"), ConfigJson.parse(backup.readText())!!.favorites.toList())
    }

    @Test
    fun `both files unreadable degrades to an empty config rather than throwing`() {
        primary.writeText("{ torn")
        backup.writeText("also not json")

        assertTrue(store.load().favorites.isEmpty())
    }

    @Test
    fun `a directory where the config should be is treated as absent, not fatal`() {
        File(dir, "chopper.json").mkdirs()

        assertTrue(store.load().favorites.isEmpty())
    }

    // ---- write and rotation --------------------------------------------------

    @Test
    fun `the first save publishes a primary and no bak mirror yet`() {
        assertTrue(store.write(json("a/1"), rotateBackup = true))

        assertEquals(listOf("a/1"), ConfigJson.parse(primary.readText())!!.favorites.toList())
        assertFalse("there is nothing to rotate on the first save", backup.exists())
    }

    @Test
    fun `the second save rotates the previous primary into bak mirror`() {
        store.write(json("first"), rotateBackup = true)
        store.write(json("second"), rotateBackup = true)

        assertEquals(listOf("second"), ConfigJson.parse(primary.readText())!!.favorites.toList())
        assertEquals(listOf("first"), ConfigJson.parse(backup.readText())!!.favorites.toList())
    }

    /**
     * The regression guard for 7e4745d. A primary that went bad AFTER the last good save
     * (bit rot, a torn in-place fallback) must not be promoted over the good .bak — the
     * rotation re-parses it first and skips when it no longer parses.
     */
    @Test
    fun `a primary that no longer parses is not rotated over a good bak mirror`() {
        store.write(json("good"), rotateBackup = true)
        store.write(json("newer"), rotateBackup = true)   // .bak = "good"
        primary.writeText("{ went bad after the save")

        store.write(json("newest"), rotateBackup = true)

        assertEquals(listOf("good"), ConfigJson.parse(backup.readText())!!.favorites.toList())
        assertTrue(loggedAbout("skipping rotate"))
    }

    @Test
    fun `a skipped rotation still publishes the new primary`() {
        store.write(json("good"), rotateBackup = true)
        primary.writeText("{ went bad")

        store.write(json("newest"), rotateBackup = true)

        assertEquals(listOf("newest"), ConfigJson.parse(primary.readText())!!.favorites.toList())
    }

    @Test
    fun `rotateBackup false replaces the primary and leaves bak mirror untouched`() {
        store.write(json("first"), rotateBackup = true)
        store.write(json("second"), rotateBackup = true)   // .bak = "first"

        store.write(json("healed"), rotateBackup = false)

        assertEquals(listOf("healed"), ConfigJson.parse(primary.readText())!!.favorites.toList())
        assertEquals(listOf("first"), ConfigJson.parse(backup.readText())!!.favorites.toList())
    }

    @Test
    fun `the temp file never survives a successful publish`() {
        store.write(json("a/1"), rotateBackup = true)

        assertFalse(temp.exists())
    }

    @Test
    fun `a successful publish fsyncs the directory`() {
        store.write(json("a/1"), rotateBackup = true)

        assertEquals(1, dirSyncs)
    }

    @Test
    fun `a failed write is reported rather than thrown`() {
        val broken = ConfigStore(
            dir = File(dir, "does/not/exist"),
            log = { msg, _ -> logs += msg },
            syncDir = { dirSyncs++ },
        )

        assertFalse(broken.write(json("a/1"), rotateBackup = true))
        assertTrue(loggedAbout("config save failed"))
    }

    // ---- the two together ----------------------------------------------------

    @Test
    fun `a written config round-trips back through load`() {
        val original = ChopperConfig(
            hidden = linkedSetOf("h/1"),
            favorites = linkedSetOf("f/1", "f/2"),
            names = linkedMapOf("f/1" to "Custom"),
            tags = linkedMapOf("f/2" to mutableListOf("work")),
        )

        store.write(ConfigJson.serialize(original), rotateBackup = true)
        val loaded = store.load()

        assertEquals(listOf("f/1", "f/2"), loaded.favorites.toList())
        assertEquals(listOf("h/1"), loaded.hidden.toList())
        assertEquals("Custom", loaded.names["f/1"])
        assertEquals(listOf("work"), loaded.tags["f/2"])
    }

    /** Losing the primary entirely (not merely torn) must still recover, and the recovery
     *  must not eat the backup it recovered from. */
    @Test
    fun `a deleted primary is rebuilt from bak mirror without losing bak mirror`() {
        store.write(json("first"), rotateBackup = true)
        store.write(json("second"), rotateBackup = true)   // .bak = "first"
        assertTrue(primary.delete())

        val loaded = store.load()

        assertEquals(listOf("first"), loaded.favorites.toList())
        assertTrue(backup.exists())
        assertEquals(listOf("first"), ConfigJson.parse(primary.readText())!!.favorites.toList())
    }
}
