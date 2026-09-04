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
        return JSONObject().apply {
            put("hidden", JSONArray(config.hidden.toList()))
            put("favorites", JSONArray(config.favorites.toList()))
            put("names", names)
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
        loaded
    } catch (e: Exception) {
        null
    }
}
