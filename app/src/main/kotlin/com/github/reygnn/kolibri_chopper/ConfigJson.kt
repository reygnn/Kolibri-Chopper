package com.github.reygnn.kolibri_chopper

import org.json.JSONArray
import org.json.JSONObject

/**
 * The chopper.json (de)serialization — the pure String ⇄ [ChopperConfig] core of
 * the persistence layer, lifted out of MainActivity so the round-trip is unit-
 * testable on the JVM. It touches only org.json (real impl on the test classpath),
 * never a File, a filesDir or android.util.Log: the caller owns the disk I/O and
 * the logging. [parse] returns null (never throws) on malformed input, leaving the
 * caller to log and fall back.
 */
internal object ConfigJson {

    /** Serialize a config to the on-disk JSON shape (pretty-printed, 2-space). */
    fun serialize(config: ChopperConfig): String {
        val names = JSONObject()
        for ((k, v) in config.names) names.put(k, v)
        val tags = JSONObject()
        for ((k, v) in config.tags) tags.put(k, JSONArray(v))
        return JSONObject().apply {
            put("hidden", JSONArray(config.hidden.toList()))
            put("favorites", JSONArray(config.favorites.toList()))
            put("names", names)
            put("tags", tags)
        }.toString(2)
    }

    /**
     * Parse the JSON text back into a config. A missing key yields an empty section
     * (a partially-written or older file still loads what it can); malformed JSON
     * returns null so the caller can fall back to the .bak. favorites/hidden keep
     * their array order (favorites' order is the rank); names is an unordered map.
     *
     * This leniency is safe ONLY because the input is our own file. For a document
     * from outside the app use [parseForeign], which refuses unrelated JSON instead
     * of quietly turning it into an empty config.
     */
    fun parse(text: String): ChopperConfig? = try {
        parseObject(JSONObject(text))
    } catch (e: Exception) {
        null
    }

    /**
     * Parse a config that came from OUTSIDE the app — the document a "~restore" pick
     * hands over — returning null unless it actually looks like a chopper config.
     *
     * [parse]'s leniency is right for OUR file in filesDir, where a missing section
     * means an older or half-written copy and salvaging the rest beats failing. For a
     * file the user picked out of shared storage it is dangerous: every JSON object on
     * the device parses "successfully" into a config with NOTHING in it — an empty
     * "{}", a settings export, some app's package.json — and adopting that silently
     * wipes every favorite, hidden entry, custom name and tag with no error to show
     * for it. So demand that at least one known section is present AND of the expected
     * type before treating the document as a config at all. A real chopper.json always
     * carries all four (serialize writes them unconditionally), so requiring one is a
     * generous floor that still rejects unrelated JSON.
     *
     * Note this accepts a config whose sections are present but EMPTY — restoring a
     * genuinely empty config is a legitimate thing to want.
     */
    fun parseForeign(text: String): ChopperConfig? = try {
        val j = JSONObject(text)
        val looksLikeConfig = j.optJSONArray("hidden") != null ||
            j.optJSONArray("favorites") != null ||
            j.optJSONObject("names") != null ||
            j.optJSONObject("tags") != null
        if (looksLikeConfig) parseObject(j) else null
    } catch (e: Exception) {
        null
    }

    /** The shared body of [parse] and [parseForeign], once the caller has decided the
     *  document is worth reading. May throw; both callers translate that to null. */
    private fun parseObject(j: JSONObject): ChopperConfig {
        val loaded = ChopperConfig()
        j.optJSONArray("hidden")?.let {
            for (i in 0 until it.length()) loaded.hidden += it.getString(i)
        }
        j.optJSONArray("favorites")?.let {
            for (i in 0 until it.length()) loaded.favorites += it.getString(i)
        }
        j.optJSONObject("names")?.let { o ->
            for (k in o.keys()) loaded.names[k] = o.getString(k)
        }
        j.optJSONObject("tags")?.let { o ->
            for (k in o.keys()) {
                val arr = o.optJSONArray(k) ?: continue
                val list = ArrayList<String>(arr.length())
                // Canonicalise on the way in too, not just when the app writes them: a
                // hand-edited or restored chopper.json with an unfolded "Work", a stray
                // "#work" or a comma inside a tag would otherwise be unreachable from the
                // filters, or would break the "#"/"##" sigil dispatch. Doing it here makes
                // "stored tags are canonical" hold for EVERY loaded file, not only
                // app-written ones, and quietly migrates a config written before the rule
                // existed. Empties are dropped, and duplicates with them — two tags that
                // canonicalise to the same thing must collapse, or toggleTag's minus
                // (first occurrence only) could never fully un-tick the app.
                for (i in 0 until arr.length()) {
                    val tag = LauncherLogic.canonicalTag(arr.getString(i))
                    if (tag.isNotEmpty() && tag !in list) list += tag
                }
                // Drop an empty list rather than materializing a tagless key — keeps the
                // in-memory shape identical to what serialize() would write next time.
                if (list.isNotEmpty()) loaded.tags[k] = list
            }
        }
        return loaded
    }
}
