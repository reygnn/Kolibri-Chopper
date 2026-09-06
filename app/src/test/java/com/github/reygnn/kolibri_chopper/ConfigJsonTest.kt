package com.github.reygnn.kolibri_chopper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM round-trip tests for the chopper.json (de)serialization. These run on the
 * plain JVM because the REAL org.json is on the test classpath (see the json
 * testImplementation) — the app's android.jar org.json is only a throwing stub.
 */
class ConfigJsonTest {

    private fun config(
        hidden: List<String> = emptyList(),
        favorites: List<String> = emptyList(),
        names: Map<String, String> = emptyMap(),
        tags: Map<String, List<String>> = emptyMap(),
    ) = ChopperConfig().apply {
        this.hidden += hidden
        this.favorites += favorites
        this.names.putAll(names)
        for ((k, v) in tags) this.tags[k] = v.toMutableList()
    }

    @Test fun `round-trips a full config`() {
        val original = config(
            hidden = listOf("com.a/.A", "com.b/.B"),
            favorites = listOf("com.f2/.F", "com.f1/.F", "com.f3/.F"),
            names = mapOf("com.a/.A" to "Alpha", "com.b/.B" to "Beta"),
            tags = mapOf("com.a/.A" to listOf("work", "fun"), "com.b/.B" to listOf("games")),
        )
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(original.hidden, parsed.hidden)
        assertEquals(original.favorites.toList(), parsed.favorites.toList())  // order = rank
        assertEquals(original.names, parsed.names)
        assertEquals(original.tags, parsed.tags)
    }

    @Test fun `round-trips an empty config`() {
        val parsed = ConfigJson.parse(ConfigJson.serialize(ChopperConfig()))!!
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.names.isEmpty())
        assertTrue(parsed.tags.isEmpty())
    }

    @Test fun `round-trips tags, preserving per-key order`() {
        val original = config(tags = mapOf("com.a/.A" to listOf("z", "a", "m")))
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(listOf("z", "a", "m"), parsed.tags["com.a/.A"])
    }

    @Test fun `an empty tag list is not materialized on parse`() {
        // serialize never writes [], but a hand-edited file might; parse must drop it
        // so the in-memory shape stays "key present only when it has tags".
        val parsed = ConfigJson.parse("""{"tags":{"com.a/.A":[]}}""")!!
        assertTrue(parsed.tags.isEmpty())
    }

    @Test fun `round-trips tags with unicode and special characters`() {
        // Tags are stored ROOT-folded, so use already-lowercase values here to keep
        // round-trip identity; the point is that unicode/punctuation survive org.json.
        val original = config(tags = mapOf("com.a/.A" to listOf("café", "c/c++", "a b")))
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(original.tags, parsed.tags)
    }

    @Test fun `parse folds tag case so a hand-edited tag still matches the filter`() {
        // A hand-edited file with an unfolded "Work" must load as the canonical "work",
        // or the ROOT-folded "#work" filter would silently miss it.
        val parsed = ConfigJson.parse("""{"tags":{"com.a/.A":["Work","GAMES"]}}""")!!
        assertEquals(listOf("work", "games"), parsed.tags["com.a/.A"])
    }

    @Test fun `preserves favorite order (the rank)`() {
        val original = config(favorites = listOf("z", "a", "m"))
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(listOf("z", "a", "m"), parsed.favorites.toList())
    }

    @Test fun `malformed input returns null`() {
        assertNull(ConfigJson.parse("not json"))
        assertNull(ConfigJson.parse(""))
        assertNull(ConfigJson.parse("[1,2,3]"))   // a JSON array, not the expected object
    }

    @Test fun `missing keys yield empty sections, not null`() {
        val parsed = ConfigJson.parse("{}")!!
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.names.isEmpty())
    }

    @Test fun `a partial file loads what it can`() {
        val parsed = ConfigJson.parse("""{"favorites":["x","y"]}""")!!
        assertEquals(listOf("x", "y"), parsed.favorites.toList())
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.names.isEmpty())
    }

    @Test fun `round-trips names with quotes, backslashes and unicode`() {
        // A custom name must survive serialization intact — otherwise a stray quote
        // or backslash in a rename could corrupt the whole chopper.json.
        val tricky = mapOf(
            "com.a/.A" to "Wörk \"Gmail\"",
            "com.b/.B" to "back\\slash\tand\nnewline",
            "com.c/.C" to "絵文字 😀",
        )
        val parsed = ConfigJson.parse(ConfigJson.serialize(config(names = tricky)))!!
        assertEquals(tricky, parsed.names)
    }

    @Test fun `truncated JSON returns null - the torn-write recovery trigger`() {
        // A half-written primary (power-loss mid-save) is exactly what loadConfig()
        // must reject so it falls back to .bak. Pin that these parse to null.
        assertNull(ConfigJson.parse("{\"favorites\":[\"x\""))  // unterminated
        assertNull(ConfigJson.parse("{"))                      // bare opening brace
        assertNull(ConfigJson.parse("   "))                    // whitespace only
    }

    @Test fun `duplicate favorites in the file are de-duplicated, first position wins`() {
        // A hand-edited or corrupted file could repeat a key; the favorites Set must
        // collapse it to one entry without disturbing the surrounding rank order.
        val parsed = ConfigJson.parse("""{"favorites":["x","y","x"]}""")!!
        assertEquals(listOf("x", "y"), parsed.favorites.toList())
    }
    // ---- parseForeign -------------------------------------------------------

    /** The regression this exists for: a restore fed unrelated JSON used to "succeed"
     *  and adopt an empty config, silently wiping favorites/hidden/names/tags. */
    @Test
    fun `foreign JSON without a single known section is rejected`() {
        assertNull(ConfigJson.parseForeign("{}"))
        assertNull(ConfigJson.parseForeign("""{"name":"thing","version":"1.0.0"}"""))
        assertNull(ConfigJson.parseForeign("""{"favourites":["a/b"]}"""))  // British spelling
        assertNull(ConfigJson.parseForeign("[]"))
        assertNull(ConfigJson.parseForeign("not json at all"))
        assertNull(ConfigJson.parseForeign(""))
    }

    /** A section of the WRONG type doesn't count as one either — "hidden" as a string
     *  is some other app's file that happens to share the word. */
    @Test
    fun `known keys of the wrong type do not qualify`() {
        assertNull(ConfigJson.parseForeign("""{"hidden":true,"names":"nope"}"""))
    }

    @Test
    fun `a single known section is enough to accept`() {
        val onlyFavorites = ConfigJson.parseForeign("""{"favorites":["pkg/Act"]}""")
        assertNotNull(onlyFavorites)
        assertEquals(listOf("pkg/Act"), onlyFavorites!!.favorites.toList())
    }

    /** An all-empty but well-formed config is a legitimate thing to restore — the user
     *  clearing everything and keeping that as a backup must still work. */
    @Test
    fun `a well-formed but empty config is accepted`() {
        val empty = ConfigJson.parseForeign("""{"hidden":[],"favorites":[],"names":{},"tags":{}}""")
        assertNotNull(empty)
        assertTrue(empty!!.favorites.isEmpty())
    }

    /** parseForeign must agree with parse on a real file — same round-trip, just pickier
     *  about what it lets in. */
    @Test
    fun `a real serialized config round-trips through parseForeign`() {
        val cfg = ChopperConfig(
            hidden = linkedSetOf("h/1"),
            favorites = linkedSetOf("f/1", "f/2"),
            names = linkedMapOf("f/1" to "Custom"),
            tags = linkedMapOf("f/2" to mutableListOf("work")),
        )
        val back = ConfigJson.parseForeign(ConfigJson.serialize(cfg))
        assertNotNull(back)
        assertEquals(listOf("f/1", "f/2"), back!!.favorites.toList())
        assertEquals(listOf("h/1"), back.hidden.toList())
        assertEquals("Custom", back.names["f/1"])
        assertEquals(listOf("work"), back.tags["f/2"])
    }

    // ---- tag canonicalisation on load ---------------------------------------

    /** A config written before the rule existed — or hand-edited, or restored from
     *  another device — is migrated on load rather than kept in a shape the app can no
     *  longer reach. */
    @Test
    fun `loading canonicalises tags out of a foreign or older file`() {
        val cfg = ConfigJson.parse("""{"tags":{"pkg/A":["#work","Chat","foo,bar","  spaced  "]}}""")

        assertEquals(listOf("work", "chat", "foobar", "spaced"), cfg!!.tags["pkg/A"])
    }

    /** Duplicates must collapse: toggleTag's minus removes only the first occurrence, so
     *  a doubled tag could never be fully un-ticked in the "##" editor. */
    @Test
    fun `loading collapses tags that canonicalise to the same thing`() {
        val cfg = ConfigJson.parse("""{"tags":{"pkg/A":["Work","work","#work"]}}""")

        assertEquals(listOf("work"), cfg!!.tags["pkg/A"])
    }

    /** An app whose tags all canonicalise to nothing loses its KEY, not merely its
     *  values — an empty list would disagree with what serialize writes next. */
    @Test
    fun `an app whose tags all vanish loses its key`() {
        val cfg = ConfigJson.parse("""{"tags":{"pkg/A":["###",",",""],"pkg/B":["work"]}}""")

        assertTrue("pkg/A" !in cfg!!.tags)
        assertEquals(listOf("work"), cfg.tags["pkg/B"])
    }

}
