package com.github.reygnn.kolibri_chopper

/**
 * The whole editable state, mirroring chopper.json 1:1. It is the single
 * source of truth for hidden/favorite membership: the filter and the adapter
 * read it live, so a toggle only mutates this + saves + notifies — the loaded
 * AppEntry list never has to be rebuilt. The flattened component string
 * ("package/class", see AppEntry.key) is the key throughout, so apps that
 * expose more than one launcher activity stay individually addressable.
 *
 * `internal` (not private-nested in MainActivity) so [ConfigJson] and its unit
 * tests can name it; it holds no framework types, so it stays JVM-testable.
 */
internal class ChopperConfig(
    val hidden: MutableSet<String> = linkedSetOf(),
    // A LinkedHashSet, not a List: favorites need BOTH insertion order (for the
    // rank used when laying rows out) AND O(1) membership (getView tests it per
    // visible row, applyFilter/orderWithFavorites/toggle test it too). A plain
    // List gave order but O(n) contains; the linked set gives both, and it also
    // makes a favorite inherently unique — toggling can never create a duplicate.
    val favorites: MutableSet<String> = linkedSetOf(),
    val names: MutableMap<String, String> = linkedMapOf(),
    // Per-app tags for the "#" filter. Values are canonical (ROOT-folded, deduped,
    // see LauncherLogic.parseTags), ordered first-entered. Stored in chopper.json like
    // names; a key with no tags is dropped rather than persisting an empty list.
    val tags: MutableMap<String, MutableList<String>> = linkedMapOf(),
    // The active 0.3 wallpaper motif's name (AsciiArt.names), or "" for off. A var,
    // not a collection: the "~art" command reassigns it in place. Serialized like the
    // rest; a missing key parses back to "" (off), so older configs stay compatible.
    var wallpaper: String = "",
) {
    /**
     * A copy for handing to the background loader. loadApps() only READS these
     * collections; taking the copy on the main thread (where every mutation
     * also happens) means the loader owns an isolated snapshot and can't race
     * a concurrent rename/toggle — LinkedHashMap et al. aren't thread-safe, so
     * a read during a structural write could otherwise throw. Values are
     * immutable Strings, so copying the containers is enough.
     */
    fun snapshot() = ChopperConfig(
        LinkedHashSet(hidden),
        LinkedHashSet(favorites),
        LinkedHashMap(names),
        // Deep-copy the tag lists too — a shallow map copy would still share the inner
        // MutableLists with the live cfg, reopening the very cross-thread mutation the
        // snapshot exists to prevent.
        LinkedHashMap<String, MutableList<String>>().also { c ->
            for ((k, v) in tags) c[k] = ArrayList(v)
        },
        wallpaper,
    )
}
