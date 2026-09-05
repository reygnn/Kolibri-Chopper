package com.github.reygnn.kolibri_chopper

import org.junit.Assert.assertEquals
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
}
