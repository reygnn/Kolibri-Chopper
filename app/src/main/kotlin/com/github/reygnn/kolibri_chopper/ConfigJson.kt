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
            put("wallpaper", config.wallpaper)
        }.toString(2)
    }

    /**
     * Parse the JSON text back into a config. A missing key yields an empty section
     * (a partially-written or older file still loads what it can); malformed JSON
     * returns null so the caller can fall back to the .bak. favorites/hidden keep
     * their array order (favorites' order is the rank); names is an unordered map.
     */
    fun parse(text: String): ChopperConfig? = try {
        val j = JSONObject(text)
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
                // Fold on the way in too, not just when parseTags writes them: a
                // hand-edited chopper.json with an unfolded "Work" would otherwise never
                // match the ROOT-folded "#work" filter. Folding here makes "stored tags
                // are canonical" hold for EVERY loaded file, not only app-written ones.
                for (i in 0 until arr.length()) list += LauncherLogic.foldLabel(arr.getString(i))
                // Drop an empty list rather than materializing a tagless key — keeps the
                // in-memory shape identical to what serialize() would write next time.
                if (list.isNotEmpty()) loaded.tags[k] = list
            }
        }
        // Missing key (older config, or off) → "". optString already yields "" when
        // absent; kept explicit for symmetry with the sections above.
        loaded.wallpaper = j.optString("wallpaper", "")
        loaded
    } catch (e: Exception) {
        null
    }
}
