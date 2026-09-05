package com.github.reygnn.kolibri_chopper

import java.util.Locale

/**
 * The launcher's pure decision logic, lifted out of [MainActivity] so it can be
 * unit-tested on the JVM without an Android runtime. Nothing here touches the
 * framework, disk or any shared state: every function is a total, deterministic
 * mapping from its arguments to a new list/value. The Activity keeps ownership of
 * the UI wiring and the mutable config; it only delegates the "what to show / how
 * to order / how to reorder" questions here.
 *
 * These are `internal`, not `private`, purely so the test source set can reach
 * them — they add nothing to the shipped APK that the inlined originals didn't.
 */

/** The command-line mode, chosen by the prompt's leading sigil. Top-level so the
 *  Activity, the adapter and the tests can all name it. */
internal enum class Mode { NORMAL, HIDDEN_EDIT, FAV_EDIT, FAV_REORDER, RECENTS, TAG_FILTER }

/** The two fields the ordering/search logic needs from a row: its identity [key]
 *  and its case-folded label. AppEntry implements this, and tests fake it with a
 *  plain data class — so the logic never has to construct a real ComponentName. */
internal interface Ordered {
    val key: String
    val labelLower: String
}

internal object LauncherLogic {

    /**
     * Map a trimmed prompt to its mode. "!!" MUST be tested before "!": the reorder
     * sigil is a strict prefix of the edit one, so the order here is load-bearing.
     */
    fun parseMode(trimmed: String): Mode = when {
        trimmed.startsWith("!!") -> Mode.FAV_REORDER
        trimmed.startsWith("-") -> Mode.HIDDEN_EDIT
        trimmed.startsWith("!") -> Mode.FAV_EDIT
        trimmed.startsWith("?") -> Mode.RECENTS
        trimmed.startsWith("#") -> Mode.TAG_FILTER
        else -> Mode.NORMAL
    }

    /**
     * Case-fold a display label to its search/sort key. Folds with Locale.ROOT (never
     * the device locale) so the I/i mapping stays invariant — the exact same reason
     * [search] folds its needle with ROOT. Both sides MUST use this: if a label were
     * folded with the device locale, a Turkish/Azeri phone would key "Instagram" under
     * a dotless ı and [search]'s ROOT-folded "i" needle would never match it. The one
     * place a label becomes an [Ordered.labelLower].
     */
    fun foldLabel(label: String): String = label.lowercase(Locale.ROOT)

    /**
     * Substring search over [all] by case-folded label. Folds [needle] with
     * Locale.ROOT (not the device locale) so the I/i mapping stays invariant — a
     * Turkish/Azeri device must not turn "Instagram" into an unmatchable dotless ı.
     * An empty needle returns [all] unchanged (the edit modes' "no filter" case).
     */
    fun <T : Ordered> search(all: List<T>, needle: String): List<T> {
        val n = foldLabel(needle)
        return if (n.isEmpty()) all else all.filter { it.labelLower.contains(n) }
    }

    /** The launchable favorites, in their stored ([favorites]) order. Entries not
     *  present in [all] (a favorite whose app is uninstalled) drop out silently. */
    fun <T : Ordered> favoritesInDisplayOrder(all: List<T>, favorites: Collection<String>): List<T> {
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        return all.filter { it.key in rank }.sortedBy { rank[it.key] }
    }

    /** The drawer set: everything not hidden, PLUS any favorite even when also
     *  hidden. The single place "favoriting overrides hiding" lives, so the
     *  empty-prompt view and the "*" drawer can never disagree. */
    fun <T : Ordered> drawer(all: List<T>, hidden: Set<String>, favorites: Set<String>): List<T> =
        all.filter { it.key !in hidden || it.key in favorites }

    /** Non-favorites first (in their incoming order), favorites last in [favorites]
     *  rank order — so with isStackFromBottom the config-first favorite sits nearest
     *  the prompt. No favorites configured: [apps] is returned untouched. */
    fun <T : Ordered> orderWithFavorites(apps: List<T>, favorites: Collection<String>): List<T> {
        if (favorites.isEmpty()) return apps
        val rank = favorites.withIndex().associate { (i, p) -> p to i }
        val (favs, rest) = apps.partition { it.key in rank }
        return rest + favs.sortedBy { rank[it.key] }
    }

    /**
     * Move [pickedKey] onto [targetKey]'s slot within [order], returning the new
     * order — or null when the move is a no-op or impossible (either key absent, or
     * both the same). Inserting AFTER the target when moving down / AT it when moving
     * up lands the picked key exactly where the target sat, shifting the rows between
     * by one. Pure: [order] is not mutated.
     */
    fun reorder(order: List<String>, pickedKey: String, targetKey: String): List<String>? {
        val from = order.indexOf(pickedKey)
        val to = order.indexOf(targetKey)
        if (from < 0 || to < 0 || from == to) return null
        val out = order.toMutableList()
        out.removeAt(from)
        val dest = out.indexOf(targetKey)
        out.add(if (from < to) dest + 1 else dest, pickedKey)
        return out
    }

    /**
     * Record a launch in the most-recent-first recents list: [key] goes to the front,
     * any earlier occurrence is dropped (a relaunch moves the app up rather than
     * duplicating it), and the result is capped at [limit]. Pure: [current] is not
     * mutated. The Activity holds this list in memory only — it is deliberately never
     * persisted, so it starts empty on every cold start.
     */
    fun pushRecent(current: List<String>, key: String, limit: Int): List<String> =
        (listOf(key) + current.filter { it != key }).take(limit)

    /**
     * The recently-launched apps as rows, ready for the list. [recentKeys] is
     * most-recent-first; the result is REVERSED so the most recent lands last — with
     * isStackFromBottom that's the row nearest the prompt (the Enter quick-launch
     * target), matching how favorites read. Keys whose app is no longer in [all] (an
     * uninstall since it was launched) drop out silently.
     */
    fun <T : Ordered> recentsInDisplayOrder(all: List<T>, recentKeys: List<String>): List<T> {
        val byKey = all.associateBy { it.key }
        return recentKeys.asReversed().mapNotNull { byKey[it] }
    }

    /**
     * Normalize a comma-separated tag input into canonical stored tags: each piece is
     * trimmed and [foldLabel]-folded (ROOT, so tag matching is case- and locale-
     * invariant like search), empties are dropped, and duplicates collapse keeping
     * first-seen order. The single place raw tag text becomes stored tags, so [tagged]
     * can assume its stored tags are already folded and never re-normalize per filter.
     */
    fun parseTags(raw: String): List<String> =
        raw.split(',').map { foldLabel(it.trim()) }.filter { it.isNotEmpty() }.distinct()

    /**
     * Every distinct tag across all apps, sorted — the suggestion pool for the tag
     * input's autocomplete (and later the "#" tag overview). Values in [tags] are
     * already folded (see [parseTags]/ConfigJson), so this only flattens, de-dupes
     * and sorts. Sorted so the suggestion list is stable and alphabetical.
     */
    fun allTags(tags: Map<String, List<String>>): List<String> =
        tags.values.flatten().distinct().sorted()

    /**
     * The "#" filter: apps carrying a tag that matches [needle]. [needle] is folded
     * (ROOT) and prefix-matched against each app's tags, so "#wo" finds a "work"-tagged
     * app; an empty needle (bare "#") returns every tagged app as an overview. [tags]
     * is keyed by app key and assumed already folded (see [parseTags]). Apps keep their
     * incoming order; a tag pointing at an app not in [all] (uninstalled) drops out
     * because we iterate [all]. Hidden apps are intentionally NOT excluded — a tag is
     * an explicit choice, the same way favoriting overrides hiding.
     */
    fun <T : Ordered> tagged(all: List<T>, tags: Map<String, List<String>>, needle: String): List<T> {
        val n = foldLabel(needle)
        return all.filter { entry ->
            val ts = tags[entry.key].orEmpty()
            if (n.isEmpty()) ts.isNotEmpty() else ts.any { it.startsWith(n) }
        }
    }
}
