package com.github.reygnn.sigil_launcher

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
internal enum class Mode {
    NORMAL, HIDDEN_EDIT, FAV_EDIT, FAV_REORDER, RECENTS, TAG_FILTER, TAG_EDIT, COMMAND
}

/** A one-shot "~" command, typed out in full and fired with Enter. Unlike a [Mode]
 *  it renders nothing: it acts once and the prompt is cleared. Top-level for the
 *  same reason [Mode] is — the Activity and the tests both name it. */
internal enum class Command { RELOAD, SAVE, BACKUP, RESTORE, RESTORE_SAF }

/** The two fields the ordering/search logic needs from a row: its identity [key]
 *  and its case-folded label. AppEntry implements this, and tests fake it with a
 *  plain data class — so the logic never has to construct a real ComponentName. */
internal interface Ordered {
    val key: String
    val labelLower: String
}

/**
 * A rendered list row. Lifted out of [MainActivity] (where it was a private nested
 * sealed interface) so [LauncherLogic.rowsFor] can build it purely and the tests can
 * assert on it without an Android runtime.
 *
 * Generic over [Ordered] for the SAME reason the rest of this file is: the app rows in
 * production carry an AppEntry (which wraps a framework ComponentName, unusable on a
 * plain JVM), while a test fakes them with a trivial [Ordered]. [AppRow] therefore holds
 * a `T`, not a concrete AppEntry; [TagRow]/[CommandRow] carry no app and are `Row<Nothing>`
 * so they slot into a `Row<T>` list for any T (the interface is `out T`).
 */
internal sealed interface Row<out T : Ordered>
internal data class AppRow<out T : Ordered>(val entry: T) : Row<T>
internal data class TagRow(val name: String) : Row<Nothing>
internal data class CommandRow(val name: String, val command: Command) : Row<Nothing>

/**
 * What an Enter on the command line should DO, decided purely from the prompt state. Lifted
 * out of MainActivity's setOnEditorActionListener so the branch order — which is load-bearing,
 * not cosmetic — is one testable mapping instead of an inlined `when`. The Activity performs
 * the side effects ([EnterAction.Run] clears the prompt first, [EnterAction.SetPrompt] drives
 * the TextWatcher, [EnterAction.LaunchApp] starts the app); this only decides which.
 */
internal sealed interface EnterAction<out T : Ordered> {
    /** Run a one-shot "~" command; the Activity clears the prompt before running it. */
    data class Run(val command: Command) : EnterAction<Nothing>
    /** Launch the app nearest the command line (an [AppRow] under a read mode). */
    data class LaunchApp<out T : Ordered>(val entry: T) : EnterAction<T>
    /** Rewrite the prompt — drill into a tag ("#tag"/"##tag"), open the drawer ("*"), or
     *  clear back to NORMAL (""). Drives the TextWatcher exactly as typing would. */
    data class SetPrompt(val text: String) : EnterAction<Nothing>
    /** Do nothing at all — an ambiguous "~" abbreviation, or an empty list to act on. */
    data object None : EnterAction<Nothing>
}

/**
 * What a TAP on a list row should DO, decided purely from the mode and the tapped row. Lifted
 * out of MainActivity's setOnItemClickListener for the same reason [EnterAction] is: the
 * per-mode dispatch is real branching logic that belongs in a tested pure function, not inlined
 * behind a click listener. The Activity maps each action to its side effect (toggle a set,
 * pick up a reorder, launch, rewrite the prompt); this only decides which.
 */
internal sealed interface TapAction<out T : Ordered> {
    data class Launch<out T : Ordered>(val entry: T) : TapAction<T>
    data class ToggleHidden<out T : Ordered>(val entry: T) : TapAction<T>
    data class ToggleFavorite<out T : Ordered>(val entry: T) : TapAction<T>
    data class ReorderPick<out T : Ordered>(val entry: T) : TapAction<T>
    data class ToggleTag<out T : Ordered>(val entry: T) : TapAction<T>
    /** Run the command a [CommandRow] carries; the Activity clears the prompt first. */
    data class Run(val command: Command) : TapAction<Nothing>
    /** Drill a tag row into its filter ("#tag" for the "#" overview, "##tag" for "##"). */
    data class SetPrompt(val text: String) : TapAction<Nothing>
    /** A stale position (the list shrank under the tap), or a row a mode ignores. */
    data object None : TapAction<Nothing>
}

internal object LauncherLogic {

    /**
     * Map a trimmed prompt to its mode. Two doubled sigils MUST be tested before their
     * single form — "!!" before "!", "##" before "#" — because each is a strict prefix
     * of the other. The order of these branches is load-bearing, not cosmetic.
     */
    fun parseMode(trimmed: String): Mode = when {
        trimmed.startsWith("!!") -> Mode.FAV_REORDER
        trimmed.startsWith("##") -> Mode.TAG_EDIT
        trimmed.startsWith("-") -> Mode.HIDDEN_EDIT
        trimmed.startsWith("!") -> Mode.FAV_EDIT
        trimmed.startsWith("?") -> Mode.RECENTS
        trimmed.startsWith("#") -> Mode.TAG_FILTER
        // "~" is a live sigil like the rest: it lists the commands still matching what
        // has been typed. The cost is that a label containing "~" is no longer
        // searchable — the same trade every other sigil already makes.
        trimmed.startsWith("~") -> Mode.COMMAND
        else -> Mode.NORMAL
    }

    /**
     * Every "~" command with its canonical spelling. One list, so the overview rows,
     * the abbreviation resolver and the exact parser can never drift apart — adding a
     * command here is the whole change.
     *
     * Ordered harmless-first: ~load and ~save only touch our own sigil.json, ~backup
     * only writes a copy out to Downloads, and the two that REPLACE the live config —
     * ~restore and ~restore-saf — come last. The overview renders them in this order, so
     * the destructive pair sits furthest from the prompt, never nearest it.
     */
    val COMMANDS: List<Pair<String, Command>> = listOf(
        "~load" to Command.RELOAD,
        "~save" to Command.SAVE,
        "~backup" to Command.BACKUP,
        "~restore" to Command.RESTORE,
        "~restore-saf" to Command.RESTORE_SAF,
    )

    /**
     * The command spelled EXACTLY by [trimmed] (case-folded), or null.
     *
     * Bare "~" stays an alias for "~load": it is the spelling that shipped first and
     * lives in muscle memory, and it keeps Enter on a bare "~" doing what it always
     * did even though "~" now also opens the overview.
     */
    fun parseCommand(trimmed: String): Command? {
        val q = foldLabel(trimmed)
        if (q == "~") return Command.RELOAD
        return COMMANDS.firstOrNull { it.first == q }?.second
    }

    /** The commands an overview should list for [trimmed]: every one whose name starts
     *  with it. A bare "~" therefore lists them all, which is the point of the mode. */
    fun commandsMatching(trimmed: String): List<Pair<String, Command>> {
        val q = foldLabel(trimmed)
        return COMMANDS.filter { it.first.startsWith(q) }
    }

    /**
     * The command Enter should run: an exact match, or an unambiguous abbreviation —
     * "~b" is only ever "~backup", so typing it out is busywork.
     *
     * EXACT beats prefix, and that is load-bearing: "~restore" both names a command and
     * prefixes "~restore-saf", and spelling one out in full must mean the one spelled.
     * An abbreviation that still fits more than one command ("~r", "~re") resolves to
     * null rather than picking — the overview is already showing which are in the
     * running, and guessing between two commands that both replace the config is not a
     * guess worth making.
     */
    fun resolveCommand(trimmed: String): Command? {
        parseCommand(trimmed)?.let { return it }
        return commandsMatching(trimmed).singleOrNull()?.second
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

    /**
     * The "*" drawer, optionally narrowed to labels that START WITH [prefix]. This is what
     * a NORMAL prompt ending in "*" shows: a bare "*" (empty prefix) is the whole [drawer],
     * and "<prefix>*" keeps only the drawer entries whose label begins with <prefix>.
     *
     * The star is a TRAILING wildcard — the prefix is typed IN FRONT of it (the empty-Enter
     * shortcut leaves the "*" at the end with the cursor before it), so "a*" reads as the glob
     * "apps starting with a". [prefix] is ROOT-folded like every other match here, and a
     * startsWith test is what makes this "Anfangsbuchstaben" rather than the substring [search].
     *
     * Deliberately scoped to the drawer, NOT allApps: bare "*" already means "the drawer", so
     * narrowing it keeps hidden non-favorites out — reach a hidden app by name with a plain
     * (star-less) search instead. Favorites are ordered last (nearest the prompt under
     * isStackFromBottom) exactly as the bare drawer is.
     */
    fun <T : Ordered> drawerStartingWith(
        all: List<T>,
        hidden: Set<String>,
        favorites: Set<String>,
        prefix: String,
    ): List<T> {
        val p = foldLabel(prefix)
        val base = drawer(all, hidden, favorites)
        val narrowed = if (p.isEmpty()) base else base.filter { it.labelLower.startsWith(p) }
        return orderWithFavorites(narrowed, favorites)
    }

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
        raw.split(',').map(::canonicalTag).filter { it.isNotEmpty() }.distinct()

    /**
     * Canonicalise ONE tag: ROOT-folded, stripped of the characters the rest of the app
     * gives a meaning to, and trimmed. Returns "" when nothing survives — callers drop
     * empties. THE single definition of what a tag may look like; every path that can put
     * a tag into the config goes through here ([parseTags] for the dialog, [toggleTag] for
     * "##", ConfigJson for a file off disk), so a tag cannot exist in one shape in memory
     * and another on disk.
     *
     * '#' is removed because the tag overview builds its prompt by PREPENDING a sigil
     * ("#" + name). A tag beginning with '#' would produce "##…", which parses as the bulk
     * editor for a different, truncated tag — the tag would become unreachable from the
     * very list that offered it. Other sigils are harmless: "!work" yields "#!work", and
     * since only position 0 selects the mode that still lands in the tag filter.
     *
     * ',' is removed because the long-press dialog joins tags with ", " for display and
     * re-splits on ',' when confirmed. A tag containing a comma stores fine but is torn in
     * two the next time that dialog is opened — even to do something unrelated, like a
     * rename. ([parseTags] splits on ',' BEFORE calling this, so the separator still works
     * exactly as before; only a comma that survived into a single tag is dropped.)
     *
     * Control characters go too — a paste can carry a newline, which nothing renders.
     *
     * Ignoring rather than rejecting is deliberate: this is a launcher prompt, not a form.
     * Typing "#work" should hand you the tag you obviously meant, not an error.
     */
    fun canonicalTag(raw: String): String =
        foldLabel(raw).filter { it != '#' && it != ',' && !it.isISOControl() }.trim()

    /**
     * Add or remove [tag] on an app whose current tags are [current], returning the new
     * list. Runs [tag] through [canonicalTag] so a typed "##Work" hits the stored "work" —
     * stored tag values are canonical, and comparing against a raw needle would silently
     * create a second, near-identical tag.
     *
     * Returns a possibly EMPTY list. The caller must store that as a removed key, never as
     * an empty list: serialize and parse both drop empty tag lists, so keeping one would
     * make the in-memory shape disagree with the file it was just written from.
     *
     * Order is append-at-the-end, matching how the long-press dialog's [parseTags] records
     * them — first-entered stays first.
     */
    fun toggleTag(current: List<String>?, tag: String): List<String> {
        val folded = canonicalTag(tag)
        val list = current.orEmpty()
        // Nothing survived canonicalisation ("##" then only "#" and commas): there is no
        // tag to toggle, so leave the app exactly as it was rather than inventing one.
        if (folded.isEmpty()) return list
        // minus removes only the FIRST occurrence, which is why every write path
        // de-duplicates: a list holding the same tag twice could not be fully un-ticked.
        return if (folded in list) list - folded else list + folded
    }

    /**
     * What a rename dialog's typed [name] means for the stored name override of an app whose
     * own label the system reports as [systemLabel]: the string to STORE, or null to CLEAR any
     * override (so the row falls back to [systemLabel]). [name] is assumed already trimmed —
     * RenameDialog trims it before this ever runs.
     *
     * An empty name, or one EQUAL to the system label, is not an override at all: storing it
     * would persist a redundant entry that re-typing the original could then never clear. So
     * both collapse to null, keeping `names` holding only genuine overrides.
     *
     * The comparison is deliberately EXACT — case- and whitespace-sensitive. A lowercase
     * "gmail" over a system "Gmail", or a trimmed "Gmail" over a system that reports " Gmail "
     * with spaces, changes what the row DISPLAYS, so it is a real override the user asked for,
     * not a redundant one. Folding or trimming the system side here would silently throw those
     * deliberate choices away. Lifted out of MainActivity so this decision — and those edges —
     * are pinned by a JVM test instead of living inline behind the dialog's commit lambda.
     */
    fun resolveNameOverride(name: String, systemLabel: String): String? =
        if (name.isEmpty() || name == systemLabel) null else name

    /**
     * Every distinct tag ever defined, sorted — the suggestion pool for the tag
     * input's autocomplete. Includes tags whose apps are currently uninstalled, so a
     * known tag can still be reused. Values in [tags] are already folded (see
     * [parseTags]/ConfigJson), so this only flattens, de-dupes and sorts.
     */
    fun allTags(tags: Map<String, List<String>>): List<String> =
        tags.values.flatten().distinct().sorted()

    /**
     * Tags carried by at least one currently-installed app, sorted — the bare-"#"
     * overview list. Unlike [allTags], this iterates [all] and so drops "ghost" tags
     * whose only apps have been uninstalled, guaranteeing every listed tag drills into
     * a non-empty result. Tag values are already folded (see [parseTags]).
     */
    fun <T : Ordered> tagsInUse(all: List<T>, tags: Map<String, List<String>>): List<String> =
        all.flatMap { tags[it.key].orEmpty() }.distinct().sorted()

    /**
     * The "#" filter: apps carrying a tag that PREFIX-matches [needle]. [needle] is
     * folded (ROOT), so "#gam" pulls in every app under a tag starting with "gam"
     * (e.g. "games") — typing a partial shows the apps of all matching tags, which is
     * the practical behaviour for a launcher. An empty needle returns every tagged
     * app. [tags] is keyed by app key and assumed already folded (see [parseTags]).
     * Apps keep their incoming order; a tag pointing at an app not in [all]
     * (uninstalled) drops out because we iterate [all]. Hidden apps are intentionally
     * NOT excluded — a tag is an explicit choice, the same way favoriting overrides
     * hiding.
     */
    fun <T : Ordered> tagged(all: List<T>, tags: Map<String, List<String>>, needle: String): List<T> {
        val n = foldLabel(needle)
        return all.filter { entry ->
            val ts = tags[entry.key].orEmpty()
            if (n.isEmpty()) ts.isNotEmpty() else ts.any { it.startsWith(n) }
        }
    }

    /**
     * The canonical tag behind a "##" prompt, or null when the bare-"##" overview is showing
     * and no tag is chosen yet. Lifted out of MainActivity.applyFilter for the same reason the
     * rest of this file is: it is the SINGLE place that decides "overview vs. a chosen tag" from
     * the raw prompt, and that decision drives [rowsFor]'s bare-"##" branch, the "##tag" edit
     * list and the adapter's [x]/[ ] membership glyph — so it must be one tested mapping, not
     * inlined behind the TextWatcher.
     *
     * Only meaningful in [Mode.TAG_EDIT]; every other mode yields null. [trimmed] is the
     * already-trimmed prompt, and TAG_EDIT guarantees it starts with "##", so substring(2) is
     * safe. What follows is run through [canonicalTag] — the SAME rule the long-press dialog and
     * the file loader apply — so "##Work", "##work" and "##  work" are one tag. A prompt whose
     * tail canonicalises to nothing ("##", "###", "##,") yields null: there is no tag yet, so the
     * overview stays up rather than the app selecting a phantom empty tag.
     */
    fun tagEditTagFor(mode: Mode, trimmed: String): String? =
        if (mode == Mode.TAG_EDIT) canonicalTag(trimmed.substring(2)).takeIf { it.isNotEmpty() }
        else null

    /**
     * The whole "given the prompt, what rows does the list show?" decision, lifted out of
     * MainActivity.applyFilter so it is a single pure, exhaustively-testable mapping. The
     * Activity keeps only the SIDE EFFECTS around it — bumping shownGeneration, resetting
     * reorderPick, the empty-recents toast, notifyDataSetChanged — and the two derived
     * inputs it also stores as fields ([mode] and [tagEditTag]) it computes once and passes
     * in, so there is one source of truth for each.
     *
     * [trimmed] is the already-trimmed prompt. [tagEditTag] is the canonical tag behind a
     * "##tag" (null while the bare-"##" overview is up) — the Activity computes it because
     * the adapter needs the same value; passing it avoids canonicalising twice and drifting.
     *
     * The branch order mirrors the old applyFilter exactly: the three non-app / bypass modes
     * first (bare-"##" tag list, the "~" command overview, the bare-"#" tag list), then the
     * per-mode app selection. Every substring index is safe because the sigil that selects a
     * mode guarantees the prompt starts with it (see [parseMode]).
     */
    fun <T : Ordered> rowsFor(
        mode: Mode,
        trimmed: String,
        allApps: List<T>,
        hidden: Set<String>,
        favorites: Set<String>,
        tags: Map<String, List<String>>,
        recentKeys: List<String>,
        tagEditTag: String?,
    ): List<Row<T>> {
        // Bare "##": allTags, NOT tagsInUse — bulk editing wants a tag whose apps are all
        // uninstalled too, since that is exactly the one you came to re-assign.
        if (mode == Mode.TAG_EDIT && tagEditTag == null) {
            return allTags(tags).map { TagRow(it) }
        }
        // "~" overview: the commands still matching what has been typed. Not an app list.
        if (mode == Mode.COMMAND) {
            return commandsMatching(trimmed).map { (name, cmd) -> CommandRow(name, cmd) }
        }
        // Bare "#": the in-use tags to drill into (drops ghost tags whose apps are all gone).
        if (mode == Mode.TAG_FILTER && trimmed.substring(1).isBlank()) {
            return tagsInUse(allApps, tags).map { TagRow(it) }
        }
        val apps: List<T> = when (mode) {
            // Reorder / recents ignore any text after the sigil: the list is short and
            // fixed, and filtering would scramble the positions the reorder acts on.
            Mode.FAV_REORDER -> favoritesInDisplayOrder(allApps, favorites)
            Mode.RECENTS -> recentsInDisplayOrder(allApps, recentKeys)
            Mode.TAG_FILTER -> tagged(allApps, tags, trimmed.substring(1).trim())
            // Edit modes list EVERY app (so anything can be toggled), narrowed by what
            // follows the sigil; membership shows as [x]/[ ] in the adapter.
            Mode.HIDDEN_EDIT, Mode.FAV_EDIT -> search(allApps, trimmed.substring(1).trim())
            // "##tag": every app so anything can be tagged. Deliberately NOT reordered to
            // put tagged first — rows would jump under the finger as you tick them.
            Mode.TAG_EDIT -> allApps
            // Handled above; named only to keep the when exhaustive.
            Mode.COMMAND -> emptyList()
            Mode.NORMAL -> when {
                // Empty prompt: favorites, or the drawer when none are set (or none of the
                // set ones are currently launchable) so a fresh install is never blank.
                trimmed.isEmpty() -> favoritesInDisplayOrder(allApps, favorites).ifEmpty {
                    orderWithFavorites(drawer(allApps, hidden, favorites), favorites)
                }
                // "*" is a trailing wildcard: a bare "*" is the whole drawer (everything
                // except hidden, but a favorite is always kept), and "<prefix>*" narrows
                // that drawer to labels STARTING WITH <prefix>. The empty-Enter shortcut
                // drops the user into a bare "*" with the cursor before it, so typing a
                // letter grows "a*", "ab*", … and the list follows by prefix.
                trimmed.endsWith("*") -> drawerStartingWith(allApps, hidden, favorites, trimmed.dropLast(1))
                // Plain search spans ALL apps, so a hidden app stays reachable by name.
                else -> search(allApps, trimmed)
            }
        }
        return apps.map { AppRow(it) }
    }

    /**
     * The one- or two-cell monospace marker a row carries in each edit mode, lifted out of
     * the adapter's bindAppRow so the glyph selection is pure and testable. "[ ] "/"[x] "
     * are the same width, and "» "/"  " are the same width, so labels stay column-aligned.
     * The read modes (and COMMAND, which renders no app rows) carry no marker.
     */
    fun rowPrefix(
        mode: Mode,
        isHidden: Boolean,
        isFavorite: Boolean,
        isPicked: Boolean,
        isTagged: Boolean,
    ): String = when (mode) {
        Mode.HIDDEN_EDIT -> if (isHidden) "[x] " else "[ ] "
        Mode.FAV_EDIT -> if (isFavorite) "[x] " else "[ ] "
        Mode.FAV_REORDER -> if (isPicked) "\u00BB " else "  "
        Mode.TAG_EDIT -> if (isTagged) "[x] " else "[ ] "
        Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND -> ""
    }

    /**
     * What Enter on the command line does, given the resolved [command], the [mode], whether
     * the raw prompt [promptBlank] is empty, and the [lastRow] (the row nearest the prompt
     * under isStackFromBottom — the natural Enter target). The branch ORDER is load-bearing:
     *
     *  1. A resolved [command] wins over everything, including a COMMAND-mode overview still
     *     being typed ("~b" both matches and is in COMMAND mode) — an exact/unambiguous
     *     command must fire rather than no-op.
     *  2. COMMAND mode with no resolved command ("~r", still ambiguous) does NOTHING: the
     *     overview already shows what's in the running, so clearing would throw away the typing.
     *  3. TAG_EDIT is two views behind one sigil, so it never launches: with the overview up
     *     Enter drills into the nearest tag, otherwise it's a plain "done" (clear).
     *  4. An empty prompt opens the drawer via "*" — this MUST sit before the launch branch, or
     *     a "leeres Enter" would launch the top favorite instead of showing everything.
     *  5. The read modes act on [lastRow]: launch an app, or drill a bare-"#" tag row.
     *  6. Anything else (a non-empty edit-mode prompt) is a "done" gesture: clear to NORMAL.
     */
    fun <T : Ordered> enterAction(
        mode: Mode,
        command: Command?,
        promptBlank: Boolean,
        lastRow: Row<T>?,
    ): EnterAction<T> = when {
        command != null -> EnterAction.Run(command)
        mode == Mode.COMMAND -> EnterAction.None
        mode == Mode.TAG_EDIT -> when (lastRow) {
            is TagRow -> EnterAction.SetPrompt("##${lastRow.name}")
            else -> EnterAction.SetPrompt("")
        }
        promptBlank -> EnterAction.SetPrompt("*")
        mode == Mode.NORMAL || mode == Mode.RECENTS || mode == Mode.TAG_FILTER -> when (lastRow) {
            is AppRow -> EnterAction.LaunchApp(lastRow.entry)
            is TagRow -> EnterAction.SetPrompt("#${lastRow.name}")
            // A CommandRow only exists in COMMAND mode (handled above) and null means an
            // empty list — neither is an Enter target here.
            else -> EnterAction.None
        }
        else -> EnterAction.SetPrompt("")
    }

    /**
     * What a tap on [row] does in the given [mode]. A [TagRow] drills into its filter (which
     * sigil is decided by the mode it was rendered in), a [CommandRow] runs its command, and an
     * [AppRow] fans out per mode — launch under the read modes, toggle/pick under the edit
     * modes. A null [row] is a stale position (the list shrank under the tap) and does nothing.
     */
    fun <T : Ordered> tapAction(mode: Mode, row: Row<T>?): TapAction<T> = when (row) {
        null -> TapAction.None
        is TagRow -> TapAction.SetPrompt(if (mode == Mode.TAG_EDIT) "##${row.name}" else "#${row.name}")
        is CommandRow -> TapAction.Run(row.command)
        is AppRow -> when (mode) {
            Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER -> TapAction.Launch(row.entry)
            Mode.HIDDEN_EDIT -> TapAction.ToggleHidden(row.entry)
            Mode.FAV_EDIT -> TapAction.ToggleFavorite(row.entry)
            Mode.FAV_REORDER -> TapAction.ReorderPick(row.entry)
            Mode.TAG_EDIT -> TapAction.ToggleTag(row.entry)
            // A CommandRow never reaches here (handled above); an AppRow can't be rendered in
            // COMMAND mode, so this only keeps the when exhaustive.
            Mode.COMMAND -> TapAction.None
        }
    }
}
