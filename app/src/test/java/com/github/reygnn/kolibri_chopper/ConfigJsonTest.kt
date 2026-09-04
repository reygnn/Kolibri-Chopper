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
    ) = ChopperConfig().apply {
        this.hidden += hidden
        this.favorites += favorites
        this.names.putAll(names)
    }

    @Test fun `round-trips a full config`() {
        val original = config(
            hidden = listOf("com.a/.A", "com.b/.B"),
            favorites = listOf("com.f2/.F", "com.f1/.F", "com.f3/.F"),
            names = mapOf("com.a/.A" to "Alpha", "com.b/.B" to "Beta"),
        )
        val parsed = ConfigJson.parse(ConfigJson.serialize(original))!!
        assertEquals(original.hidden, parsed.hidden)
        assertEquals(original.favorites.toList(), parsed.favorites.toList())  // order = rank
        assertEquals(original.names, parsed.names)
    }

    @Test fun `round-trips an empty config`() {
        val parsed = ConfigJson.parse(ConfigJson.serialize(ChopperConfig()))!!
        assertTrue(parsed.hidden.isEmpty())
        assertTrue(parsed.favorites.isEmpty())
        assertTrue(parsed.names.isEmpty())
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
