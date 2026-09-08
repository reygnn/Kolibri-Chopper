package com.github.reygnn.sigil_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

/**
 * JVM unit tests for the pure launcher logic. No Android runtime, no Robolectric —
 * these run on the plain JVM because [LauncherLogic] touches no framework types.
 * The rows are a trivial [Ordered] fake, so the tests never build a ComponentName.
 */
class LauncherLogicTest {

    private data class Row(override val key: String, override val labelLower: String) : Ordered

    /** Rows whose key doubles as the (already ROOT-folded) label, for ordering tests. */
    private fun rows(vararg keys: String): List<Row> = keys.map { Row(it, it) }

    private fun List<Ordered>.keys(): List<String> = map { it.key }

    // ---- parseMode ----------------------------------------------------------

    @Test fun `empty and plain text are NORMAL`() {
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode(""))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("gmail"))
        assertEquals(Mode.NORMAL, LauncherLogic.parseMode("*"))
    }

    @Test fun `dash is hidden-edit`() {
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("-"))
        assertEquals(Mode.HIDDEN_EDIT, LauncherLogic.parseMode("-maps"))
    }

    @Test fun `hash is tag-filter`() {
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    @Test fun `single bang is fav-edit`() {
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!"))
        assertEquals(Mode.FAV_EDIT, LauncherLogic.parseMode("!maps"))
    }

    @Test fun `double bang is reorder and wins over single bang`() {
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!"))
        assertEquals(Mode.FAV_REORDER, LauncherLogic.parseMode("!!maps"))
    }

    @Test fun `question mark is recents`() {
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?"))
        assertEquals(Mode.RECENTS, LauncherLogic.parseMode("?maps"))
    }

    /** "##" must be tested BEFORE "#", the same trap "!!" vs "!" sets. If the order in
     *  parseMode ever flips, "##" silently becomes a tag filter for a tag named "#". */
    @Test fun `double hash is TAG_EDIT, single hash stays TAG_FILTER`() {
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##"))
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##work"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#"))
        assertEquals(Mode.TAG_FILTER, LauncherLogic.parseMode("#work"))
    }

    // ---- canonicalTag -------------------------------------------------------

    /** The finding this exists for: a tag beginning with "#" made the tag overview build
     *  "#" + "#work" = "##work", which parses as the BULK EDITOR for a truncated tag —
     *  so the tag was unreachable from the very list offering it. */
    @Test fun `canonicalTag strips the hash that would flip the sigil`() {
        assertEquals("work", LauncherLogic.canonicalTag("#work"))
        assertEquals("work", LauncherLogic.canonicalTag("##work"))
        assertEquals("work", LauncherLogic.canonicalTag("wo#rk"))
    }

    /** The second finding: a comma survives storage but the long-press dialog joins with
     *  ", " and re-splits on "," — so the tag was torn in two by an unrelated rename. */
    @Test fun `canonicalTag strips the comma that would tear a tag in two`() {
        assertEquals("foobar", LauncherLogic.canonicalTag("foo,bar"))
    }

    /** Other sigils are NOT stripped: "#" + "!work" = "#!work", and since only position 0
     *  picks the mode that still lands in the tag filter. Throwing them away would lose
     *  perfectly usable tag names for no gain. */
    @Test fun `canonicalTag keeps the harmless sigils`() {
        assertEquals("!work", LauncherLogic.canonicalTag("!work"))
        assertEquals("-work", LauncherLogic.canonicalTag("-work"))
        assertEquals("~work", LauncherLogic.canonicalTag("~work"))
        assertEquals("?work", LauncherLogic.canonicalTag("?work"))
        assertEquals("my work", LauncherLogic.canonicalTag("my work"))
    }

    @Test fun `canonicalTag folds, trims and drops control characters`() {
        assertEquals("work", LauncherLogic.canonicalTag("  WORK  "))
        assertEquals("work", LauncherLogic.canonicalTag("work\n"))
        assertEquals("work", LauncherLogic.canonicalTag("# work"))
    }

    @Test fun `canonicalTag returns empty when nothing survives`() {
        assertEquals("", LauncherLogic.canonicalTag("###"))
        assertEquals("", LauncherLogic.canonicalTag(",,,"))
        assertEquals("", LauncherLogic.canonicalTag("   "))
        assertEquals("", LauncherLogic.canonicalTag(""))
    }

    /** Same ROOT-folding reason as everywhere else. */
    @Test fun `canonicalTag folds with ROOT, not the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("i", LauncherLogic.canonicalTag("I"))
        } finally {
            Locale.setDefault(original)
        }
    }

    /** The dialog's separator must still work: canonicalTag only sees ONE tag at a time,
     *  because parseTags splits on "," first. */
    @Test fun `parseTags still splits on commas and now canonicalises each piece`() {
        assertEquals(listOf("work", "chat"), LauncherLogic.parseTags("#work, Chat"))
        assertEquals(listOf("work"), LauncherLogic.parseTags("work, #work, WORK"))
        assertEquals(emptyList<String>(), LauncherLogic.parseTags("#, ,,"))
    }

    /** "##" and the dialog must agree, or a tag is reachable one way and not the other —
     *  which is exactly how the two findings arose. */
    @Test fun `the bulk editor and the dialog canonicalise identically`() {
        for (raw in listOf("#work", "WORK", "  work  ", "wo#rk", "foo,bar")) {
            val viaDialog = LauncherLogic.parseTags(raw).firstOrNull().orEmpty()
            val viaBulk = LauncherLogic.toggleTag(null, raw).firstOrNull().orEmpty()
            if (raw == "foo,bar") continue  // the dialog SPLITS this one; see the test above
            assertEquals("disagreement on \"$raw\"", viaDialog, viaBulk)
        }
    }

    @Test fun `toggleTag ignores a tag that canonicalises to nothing`() {
        assertEquals(listOf("work"), LauncherLogic.toggleTag(listOf("work"), "###"))
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(null, "  "))
    }

    // ---- toggleTag ----------------------------------------------------------

    @Test fun `toggleTag adds a tag an app does not carry`() {
        assertEquals(listOf("work"), LauncherLogic.toggleTag(null, "work"))
        assertEquals(listOf("games", "work"), LauncherLogic.toggleTag(listOf("games"), "work"))
    }

    @Test fun `toggleTag removes a tag an app already carries`() {
        assertEquals(listOf("games"), LauncherLogic.toggleTag(listOf("games", "work"), "work"))
    }

    /** The caller must store this as a REMOVED key, never an empty list — ConfigJson
     *  drops empty tag lists on both sides, so keeping one would make the in-memory
     *  shape disagree with the file. */
    @Test fun `toggleTag can empty an app's tag list`() {
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("work"), "work"))
    }

    /** Stored tags are canonical, so an unfolded needle must not create a near-duplicate. */
    @Test fun `toggleTag folds the incoming tag`() {
        assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("work"), "WORK"))
        assertEquals(listOf("work"), LauncherLogic.toggleTag(null, "Work"))
    }

    /** Same ROOT-folding reason as everywhere else: a Turkish default locale must not
     *  turn a typed "##WORK" into a dotless-i tag that never matches the stored one. */
    @Test fun `toggleTag folds with ROOT, not the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(emptyList<String>(), LauncherLogic.toggleTag(listOf("i"), "I"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `toggleTag does not mutate the list it is given`() {
        val current = listOf("work")
        LauncherLogic.toggleTag(current, "games")
        assertEquals(listOf("work"), current)
    }

    // ---- parseCommand -------------------------------------------------------

    @Test fun `every command and the bare tilde alias parse`() {
        assertEquals(Command.RELOAD, LauncherLogic.parseCommand("~"))
        assertEquals(Command.RELOAD, LauncherLogic.parseCommand("~load"))
        assertEquals(Command.SAVE, LauncherLogic.parseCommand("~save"))
        assertEquals(Command.BACKUP, LauncherLogic.parseCommand("~backup"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~restore-saf"))
    }

    /** "~restore" is a strict PREFIX of "~restore-saf". Because the match is exact
     *  neither can shadow the other, whichever order the branches are written in —
     *  the trap parseMode has with "!!" vs "!" simply doesn't exist here. */
    @Test fun `restore and restore-saf do not shadow each other`() {
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~restore-saf"))
        assertNull(LauncherLogic.parseCommand("~restore-"))
        assertNull(LauncherLogic.parseCommand("~restore-safe"))
        assertNull(LauncherLogic.parseCommand("~restoresaf"))
    }

    @Test fun `commands are case-insensitive`() {
        assertEquals(Command.BACKUP, LauncherLogic.parseCommand("~BACKUP"))
        assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~ReStOrE"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.parseCommand("~Restore-SAF"))
    }

    /** Folding must be ROOT, not the device locale: a Turkish default would map the
     *  "I" of a typed "~RESTORE" to a dotless i and the command would stop resolving. */
    @Test fun `commands still parse under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(Command.RESTORE, LauncherLogic.parseCommand("~RESTORE"))
        } finally {
            Locale.setDefault(original)
        }
    }

    /** The exact-match rule: "~" must not swallow a search that merely starts with it,
     *  which is what keeps the sigil from ever needing an escape. */
    @Test fun `a prefix or a partial is not a command`() {
        assertNull(LauncherLogic.parseCommand("~saved"))
        assertNull(LauncherLogic.parseCommand("~ save"))
        assertNull(LauncherLogic.parseCommand("~sav"))
        assertNull(LauncherLogic.parseCommand("~backup now"))
        assertNull(LauncherLogic.parseCommand("save"))
        assertNull(LauncherLogic.parseCommand(""))
    }

    /** "~" IS a mode now: it renders the command overview while being typed. It used
     *  to stay NORMAL (so "~foo" could be searched for); the overview is worth the
     *  trade, and every other sigil already makes it. */
    @Test fun `tilde text is COMMAND mode`() {
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~"))
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~backup"))
        assertEquals(Mode.COMMAND, LauncherLogic.parseMode("~zzz"))
    }

    // ---- commandsMatching / resolveCommand -----------------------------------

    @Test fun `a bare tilde lists every command`() {
        assertEquals(LauncherLogic.COMMANDS.size, LauncherLogic.commandsMatching("~").size)
    }

    @Test fun `typing narrows the overview`() {
        assertEquals(listOf("~backup"), LauncherLogic.commandsMatching("~b").map { it.first })
        assertEquals(
            listOf("~restore", "~restore-saf"),
            LauncherLogic.commandsMatching("~r").map { it.first },
        )
        assertEquals(listOf("~restore-saf"), LauncherLogic.commandsMatching("~restore-").map { it.first })
        assertEquals(emptyList<String>(), LauncherLogic.commandsMatching("~zzz").map { it.first })
    }

    @Test fun `an unambiguous abbreviation resolves`() {
        assertEquals(Command.RELOAD, LauncherLogic.resolveCommand("~l"))
        assertEquals(Command.SAVE, LauncherLogic.resolveCommand("~s"))
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~b"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~restore-"))
    }

    /** The load-bearing rule: "~restore" both NAMES a command and PREFIXES
     *  "~restore-saf". Spelling one out in full must mean the one spelled, never the
     *  longer neighbour — so exact has to beat prefix. */
    @Test fun `an exact match beats a prefix`() {
        assertEquals(Command.RESTORE, LauncherLogic.resolveCommand("~restore"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~restore-saf"))
    }

    /** A bare "~" prefixes EVERY command, so only the exact-match alias keeps Enter on
     *  it doing what it always did — reload, not "ambiguous, do nothing". */
    @Test fun `a bare tilde still resolves to reload`() {
        assertEquals(Command.RELOAD, LauncherLogic.resolveCommand("~"))
    }

    @Test fun `an ambiguous abbreviation resolves to nothing`() {
        assertNull(LauncherLogic.resolveCommand("~r"))
        assertNull(LauncherLogic.resolveCommand("~re"))
        assertNull(LauncherLogic.resolveCommand("~restor"))
    }

    /** The list and the test must not drift: a command added to COMMANDS without a line
     *  here would otherwise look covered. */
    @Test fun `parseCommand covers every entry in COMMANDS`() {
        for ((spelling, command) in LauncherLogic.COMMANDS) {
            assertEquals(spelling, command, LauncherLogic.parseCommand(spelling))
        }
    }

    /**
     * parseCommand's case handling was pinned; these two fold separately and were not.
     * Drop either fold and "~B" shows an empty overview while "~RESTORE-" stops resolving,
     * with nothing failing.
     *
     * Deliberately NOT called a ROOT-folding test: no command spelling currently contains
     * an "i", so ROOT and a Turkish locale fold every one of them identically and no test
     * could tell the two apart. Claiming otherwise would be theatre. The next test guards
     * the day that stops being true.
     */
    @Test fun `commandsMatching and resolveCommand are case-insensitive too`() {
        assertEquals(listOf("~backup"), LauncherLogic.commandsMatching("~B").map { it.first })
        assertEquals(Command.BACKUP, LauncherLogic.resolveCommand("~B"))
        assertEquals(Command.RESTORE_SAF, LauncherLogic.resolveCommand("~RESTORE-"))
        assertEquals(Command.RELOAD, LauncherLogic.resolveCommand("~LOAD"))
    }

    /**
     * A forward guard, not a check of today's behaviour. The moment someone adds a command
     * whose spelling contains an "i" — "~import" is the obvious one — the difference
     * between ROOT and a device-locale fold becomes observable, and a Turkish phone would
     * stop resolving it. This fails then, at the commit that introduces the command,
     * instead of on a user's phone.
     */
    @Test fun `every command spelling survives a Turkish uppercase round trip`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            for ((spelling, _) in LauncherLogic.COMMANDS) {
                assertEquals(
                    "\"$spelling\" does not survive being typed in caps on a Turkish device",
                    spelling,
                    LauncherLogic.foldLabel(spelling.uppercase(Locale.getDefault())),
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `an abbreviation matching nothing resolves to nothing`() {
        assertNull(LauncherLogic.resolveCommand("~zzz"))
        assertNull(LauncherLogic.resolveCommand("~backupp"))
    }

    // ---- reorder ------------------------------------------------------------

    @Test fun `reorder moves a row down onto the target's slot`() {
        assertEquals(listOf("b", "c", "a", "d"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "a", "c"))
    }

    @Test fun `reorder moves a row up onto the target's slot`() {
        assertEquals(listOf("a", "d", "b", "c"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "d", "b"))
    }

    @Test fun `reorder handles adjacent moves both directions`() {
        assertEquals(listOf("b", "a", "c"), LauncherLogic.reorder(listOf("a", "b", "c"), "a", "b"))
        assertEquals(listOf("a", "c", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "b"))
    }

    @Test fun `reorder moves a row onto the very first slot`() {
        // Target at index 0 (an up-move inserting AT dest 0) — the boundary the other
        // cases never exercise; a future off-by-one here would land the row at index 1.
        assertEquals(listOf("c", "a", "b"), LauncherLogic.reorder(listOf("a", "b", "c"), "c", "a"))
    }

    @Test fun `reorder moves a row onto the very last slot`() {
        // The first-slot boundary is covered above; the last-slot boundary — a down-move
        // inserting AFTER the final element (dest + 1 at the end) — was not.
        assertEquals(listOf("b", "c", "d", "a"), LauncherLogic.reorder(listOf("a", "b", "c", "d"), "a", "d"))
    }

    @Test fun `reorder returns null for a no-op or impossible move`() {
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "a", "a"))   // same row
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "x", "a"))   // picked absent
        assertNull(LauncherLogic.reorder(listOf("a", "b"), "a", "x"))   // target absent
    }

    @Test fun `reorder does not mutate its input`() {
        val order = listOf("a", "b", "c")
        LauncherLogic.reorder(order, "a", "c")
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test fun `reorder on a single-element list is always a no-op`() {
        assertNull(LauncherLogic.reorder(listOf("only"), "only", "only"))  // same row
        assertNull(LauncherLogic.reorder(listOf("only"), "only", "x"))     // target absent
    }

    // ---- foldLabel ----------------------------------------------------------

    @Test fun `foldLabel lowercases with ROOT even under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            // Under tr-TR, "I".lowercase() gives the dotless "ı". foldLabel must pin
            // Locale.ROOT so a label keeps the same "i" the search needle folds to —
            // this is the LABEL side of the invariant (search covers the needle side).
            Locale.setDefault(Locale.of("tr", "TR"))
            assertEquals("instagram", LauncherLogic.foldLabel("Instagram"))
            assertEquals("i", LauncherLogic.foldLabel("I"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `search matches a foldLabel-built row under Turkish`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.of("tr", "TR"))
            // End-to-end: the row's key is built exactly as MainActivity builds it (via
            // foldLabel -> "instagram"), and search is given the raw "I". This guards
            // search's OWN needle fold — a search that stopped folding would test
            // "instagram".contains("I") and miss. (That the two sides fold the SAME way
            // is guaranteed by both using foldLabel; the ROOT-pinning itself is pinned
            // by the standalone foldLabel and search Turkish tests above/below.)
            val all = listOf(Row("p/insta", LauncherLogic.foldLabel("Instagram")))
            assertEquals(listOf("p/insta"), LauncherLogic.search(all, "I").keys())
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---- search -------------------------------------------------------------

    @Test fun `search empty needle returns the list unchanged`() {
        val all = rows("a", "b")
        assertSame(all, LauncherLogic.search(all, ""))
    }

    @Test fun `search over an empty list is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.search(emptyList<Row>(), "x").keys())
    }

    @Test fun `search matches a case-folded substring`() {
        val all = listOf(Row("p/insta", "instagram"), Row("p/maps", "maps"))
        assertEquals(listOf("p/insta"), LauncherLogic.search(all, "INSTA").keys())
        assertEquals(emptyList<String>(), LauncherLogic.search(all, "zzz").keys())
    }

    @Test fun `search folds the needle with ROOT even under a Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            // Under tr-TR, "I".lowercase() would give the dotless "ı" and miss the
            // row — search must fold with Locale.ROOT so "I" still becomes "i".
            Locale.setDefault(Locale.of("tr", "TR"))
            val all = listOf(Row("p/insta", "instagram"))
            assertEquals(listOf("p/insta"), LauncherLogic.search(all, "I").keys())
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---- favoritesInDisplayOrder --------------------------------------------

    @Test fun `favoritesInDisplayOrder keeps favorites in config order and drops the rest`() {
        val all = rows("a", "b", "c")
        // Favorites config order is c-then-a, independent of the all-list order.
        val result = LauncherLogic.favoritesInDisplayOrder(all, linkedSetOf("c", "a"))
        assertEquals(listOf("c", "a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder drops a favorite whose app is not present`() {
        val all = rows("a", "b")
        val result = LauncherLogic.favoritesInDisplayOrder(all, linkedSetOf("a", "gone"))
        assertEquals(listOf("a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder with no favorites is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.favoritesInDisplayOrder(rows("a", "b"), emptySet()).keys())
    }

    // ---- drawer -------------------------------------------------------------

    @Test fun `drawer excludes hidden apps`() {
        val result = LauncherLogic.drawer(rows("a", "b", "c"), hidden = setOf("b"), favorites = emptySet())
        assertEquals(listOf("a", "c"), result.keys())
    }

    @Test fun `drawer keeps a favorite even when it is also hidden`() {
        // favoriting overrides hiding: b is hidden AND favorite, so it stays.
        val result = LauncherLogic.drawer(rows("a", "b", "c"), hidden = setOf("b"), favorites = setOf("b"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    // ---- orderWithFavorites -------------------------------------------------

    @Test fun `orderWithFavorites returns input unchanged when there are no favorites`() {
        val apps = rows("a", "b", "c")
        assertSame(apps, LauncherLogic.orderWithFavorites(apps, emptySet()))
    }

    @Test fun `orderWithFavorites puts non-favorites first then favorites in rank order`() {
        val apps = rows("a", "b", "c", "d")
        // Favorites c (rank 0) and a (rank 1); non-favorites b, d keep their order.
        val result = LauncherLogic.orderWithFavorites(apps, linkedSetOf("c", "a"))
        assertEquals(listOf("b", "d", "c", "a"), result.keys())
    }

    // ---- pushRecent ---------------------------------------------------------

    @Test fun `pushRecent puts a new key at the front`() {
        assertEquals(listOf("b", "a"), LauncherLogic.pushRecent(listOf("a"), "b", 8))
    }

    @Test fun `pushRecent moves an existing key to the front without duplicating`() {
        assertEquals(listOf("c", "a", "b"), LauncherLogic.pushRecent(listOf("a", "b", "c"), "c", 8))
    }

    @Test fun `pushRecent caps the list at the limit, dropping the oldest`() {
        // Front-inserting "d" onto a full 3-slot list evicts the oldest ("a").
        assertEquals(listOf("d", "c", "b"), LauncherLogic.pushRecent(listOf("c", "b", "a"), "d", 3))
    }

    @Test fun `pushRecent re-promoting a key never grows past the limit`() {
        assertEquals(listOf("a", "c", "b"), LauncherLogic.pushRecent(listOf("c", "b", "a"), "a", 3))
    }

    @Test fun `pushRecent does not mutate its input`() {
        val current = listOf("a", "b")
        LauncherLogic.pushRecent(current, "c", 8)
        assertEquals(listOf("a", "b"), current)
    }

    @Test fun `pushRecent with a non-positive limit yields an empty list`() {
        // Not reachable in production (RECENTS_LIMIT is 8), but pin take(0)'s behaviour
        // so a bad limit degrades to "no recents" rather than throwing.
        assertEquals(emptyList<String>(), LauncherLogic.pushRecent(listOf("a", "b"), "c", 0))
    }

    // ---- recentsInDisplayOrder ----------------------------------------------

    @Test fun `recentsInDisplayOrder reverses so the newest sits last`() {
        val all = rows("a", "b", "c")
        // recentKeys is newest-first (c launched most recently); display is reversed
        // so the newest lands nearest the prompt (last row).
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("c", "b", "a"))
        assertEquals(listOf("a", "b", "c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder drops a recent whose app is gone`() {
        val all = rows("a", "b")
        val result = LauncherLogic.recentsInDisplayOrder(all, listOf("gone", "b", "a"))
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `recentsInDisplayOrder with no recents is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.recentsInDisplayOrder(rows("a", "b"), emptyList()).keys())
    }

    // ---- parseTags ----------------------------------------------------------

    @Test fun `parseTags folds, trims and drops empty pieces`() {
        assertEquals(listOf("work", "games"), LauncherLogic.parseTags("  Work , GAMES "))
    }

    @Test fun `parseTags de-duplicates keeping first-seen order`() {
        // "Work" and "work" fold to the same tag; the trailing empty piece is dropped.
        assertEquals(listOf("work", "fun"), LauncherLogic.parseTags("Work, fun, work, "))
    }

    @Test fun `parseTags on blank input is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.parseTags("   "))
        assertEquals(emptyList<String>(), LauncherLogic.parseTags(",, ,"))
    }

    // ---- allTags ------------------------------------------------------------

    @Test fun `allTags flattens, de-duplicates and sorts across apps`() {
        val tags = mapOf(
            "a" to listOf("work", "fun"),
            "b" to listOf("games", "work"),   // "work" shared with a
            "c" to emptyList(),
        )
        assertEquals(listOf("fun", "games", "work"), LauncherLogic.allTags(tags))
    }

    @Test fun `allTags on no tags is empty`() {
        assertEquals(emptyList<String>(), LauncherLogic.allTags(emptyMap()))
    }

    // ---- tagsInUse ----------------------------------------------------------

    @Test fun `tagsInUse lists only tags borne by an installed app, sorted`() {
        val tags = mapOf(
            "a" to listOf("work", "fun"),
            "b" to listOf("games"),
            "gone" to listOf("ghost"),   // app not in the installed list
        )
        // only a and b are installed; "ghost" is dropped, result sorted + distinct.
        assertEquals(listOf("fun", "games", "work"), LauncherLogic.tagsInUse(rows("a", "b"), tags))
    }

    @Test fun `tagsInUse is empty when no installed app is tagged`() {
        val tags = mapOf("gone" to listOf("ghost"))
        assertEquals(emptyList<String>(), LauncherLogic.tagsInUse(rows("a", "b"), tags))
    }

    // ---- tagged -------------------------------------------------------------

    private val taggedFixture = mapOf(
        "a" to listOf("work"),
        "b" to listOf("games", "fun"),
        "c" to emptyList(),                 // present but untagged
    )

    @Test fun `tagged with an empty needle lists every tagged app`() {
        // c has no tags, so it drops out; a and b keep their incoming order.
        val result = LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "")
        assertEquals(listOf("a", "b"), result.keys())
    }

    @Test fun `tagged prefix-matches a tag`() {
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "ga").keys())
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "work").keys())
    }

    @Test fun `tagged folds the needle with ROOT`() {
        // "WORK" must still match the stored "work" tag.
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "WORK").keys())
    }

    @Test fun `tagged returns empty when nothing matches`() {
        assertEquals(emptyList<String>(), LauncherLogic.tagged(rows("a", "b", "c"), taggedFixture, "zzz").keys())
    }

    @Test fun `tagged drops a tag pointing at an uninstalled app`() {
        // "gone" is tagged but not in the app list, so it never appears.
        val tags = mapOf("gone" to listOf("work"), "a" to listOf("work"))
        assertEquals(listOf("a"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
    }

    @Test fun `tagged prefix-match includes tags sharing the prefix`() {
        val tags = mapOf("a" to listOf("work"), "b" to listOf("workout"))
        // "work" prefixes both "work" and "workout" -> both; "worko" only "workout".
        // This is intentional: "#gam" should surface every app under a gam-ish tag.
        assertEquals(listOf("a", "b"), LauncherLogic.tagged(rows("a", "b"), tags, "work").keys())
        assertEquals(listOf("b"), LauncherLogic.tagged(rows("a", "b"), tags, "worko").keys())
    }
}
