package com.github.reygnn.sigil_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [LauncherLogic.enterAction] and [LauncherLogic.tapAction] — the two
 * decision surfaces lifted out of MainActivity's setOnEditorActionListener and
 * setOnItemClickListener. These were the app's last untested branching logic: the row
 * SELECTION (rowsFor) was covered by [LauncherRowsTest], but the "given the shown rows and
 * the mode, what does Enter / a tap DO?" mapping sat inlined behind the two listeners,
 * where a plain JVM test could not reach it.
 *
 * The fake is a trivial [Ordered], never a ComponentName — same trick as everywhere else.
 * Only [AppRow.entry] identity matters to these functions, so the fake carries a bare key.
 */
class LauncherActionsTest {

    private data class Fake(override val key: String, override val labelLower: String = key) : Ordered

    private fun app(key: String): Row<Fake> = AppRow(Fake(key))
    private val tag: Row<Fake> = TagRow("work")
    private val cmd: Row<Fake> = CommandRow("~backup", Command.BACKUP)

    // ---- enterAction ---------------------------------------------------------

    /** A resolved command wins over EVERYTHING, including a COMMAND-mode overview still being
     *  typed ("~b" both resolves to BACKUP and is COMMAND mode). Load-bearing: the command
     *  branch is first, so an exact/unambiguous command fires rather than no-ops. */
    @Test fun `a resolved command runs, even in COMMAND mode`() {
        assertEquals(
            EnterAction.Run(Command.BACKUP),
            LauncherLogic.enterAction(Mode.COMMAND, Command.BACKUP, promptBlank = false, lastRow = cmd),
        )
    }

    /** ...and it beats the empty-prompt "*" branch too — the bare "~" alias resolves to
     *  RELOAD, so Enter on it reloads rather than opening the drawer. */
    @Test fun `a resolved command beats the empty-prompt star`() {
        assertEquals(
            EnterAction.Run(Command.RELOAD),
            LauncherLogic.enterAction<Fake>(Mode.COMMAND, Command.RELOAD, promptBlank = true, lastRow = null),
        )
    }

    /** An ambiguous "~r" (no resolved command, still COMMAND mode) does nothing — clearing
     *  would throw away the typing and the overview already shows what's in the running. */
    @Test fun `COMMAND mode with no resolved command is a no-op`() {
        assertEquals(
            EnterAction.None,
            LauncherLogic.enterAction(Mode.COMMAND, command = null, promptBlank = false, lastRow = cmd),
        )
    }

    @Test fun `TAG_EDIT with a tag row nearest the prompt drills into that tag`() {
        assertEquals(
            EnterAction.SetPrompt("##work"),
            LauncherLogic.enterAction(Mode.TAG_EDIT, command = null, promptBlank = false, lastRow = tag),
        )
    }

    /** With the checkbox list up (an app row nearest), or an empty list, TAG_EDIT Enter is a
     *  plain "done" — it must NEVER launch, so it clears instead. */
    @Test fun `TAG_EDIT with an app row or nothing is a done gesture`() {
        assertEquals(
            EnterAction.SetPrompt(""),
            LauncherLogic.enterAction(Mode.TAG_EDIT, command = null, promptBlank = false, lastRow = app("com.a/A")),
        )
        assertEquals(
            EnterAction.SetPrompt(""),
            LauncherLogic.enterAction<Fake>(Mode.TAG_EDIT, command = null, promptBlank = false, lastRow = null),
        )
    }

    /** The "leeres Enter": an empty prompt opens the drawer via "*". This MUST beat the launch
     *  branch, or Enter-from-rest would launch the top favorite instead of showing everything. */
    @Test fun `an empty prompt opens the drawer via star, not the top favorite`() {
        assertEquals(
            EnterAction.SetPrompt("*"),
            LauncherLogic.enterAction(Mode.NORMAL, command = null, promptBlank = true, lastRow = app("com.fav/F")),
        )
    }

    @Test fun `a read mode launches the app nearest the prompt`() {
        for (mode in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER)) {
            assertEquals(
                "launch failed for $mode",
                EnterAction.LaunchApp(Fake("com.a/A")),
                LauncherLogic.enterAction(mode, command = null, promptBlank = false, lastRow = app("com.a/A")),
            )
        }
    }

    /** The bare-"#" overview shows tag rows; Enter drills the nearest one into its filter. */
    @Test fun `TAG_FILTER with a tag row drills into the single-hash filter`() {
        assertEquals(
            EnterAction.SetPrompt("#work"),
            LauncherLogic.enterAction(Mode.TAG_FILTER, command = null, promptBlank = false, lastRow = tag),
        )
    }

    @Test fun `a read mode over an empty list does nothing`() {
        assertEquals(
            EnterAction.None,
            LauncherLogic.enterAction<Fake>(Mode.RECENTS, command = null, promptBlank = false, lastRow = null),
        )
    }

    /** A non-empty edit-mode prompt (nothing above catches it) is a "done" gesture: clear. */
    @Test fun `a non-empty edit mode prompt clears back to normal`() {
        for (mode in listOf(Mode.HIDDEN_EDIT, Mode.FAV_EDIT, Mode.FAV_REORDER)) {
            assertEquals(
                "expected a clear for $mode",
                EnterAction.SetPrompt(""),
                LauncherLogic.enterAction(mode, command = null, promptBlank = false, lastRow = app("com.a/A")),
            )
        }
    }

    // ---- tapAction -----------------------------------------------------------

    @Test fun `tapping nothing (a stale position) does nothing`() {
        assertEquals(TapAction.None, LauncherLogic.tapAction<Fake>(Mode.NORMAL, null))
    }

    @Test fun `tapping a tag row drills into the right sigil for its mode`() {
        assertEquals(TapAction.SetPrompt("##work"), LauncherLogic.tapAction(Mode.TAG_EDIT, tag))
        // Every non-TAG_EDIT mode that can render a tag row drills into the single-hash filter.
        assertEquals(TapAction.SetPrompt("#work"), LauncherLogic.tapAction(Mode.TAG_FILTER, tag))
    }

    @Test fun `tapping a command row runs its command`() {
        assertEquals(TapAction.Run(Command.BACKUP), LauncherLogic.tapAction(Mode.COMMAND, cmd))
    }

    @Test fun `tapping an app row launches under the read modes`() {
        for (mode in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER)) {
            assertEquals(
                "launch failed for $mode",
                TapAction.Launch(Fake("com.a/A")),
                LauncherLogic.tapAction(mode, app("com.a/A")),
            )
        }
    }

    @Test fun `tapping an app row toggles or picks under the edit modes`() {
        val entry = Fake("com.a/A")
        assertEquals(TapAction.ToggleHidden(entry), LauncherLogic.tapAction(Mode.HIDDEN_EDIT, app("com.a/A")))
        assertEquals(TapAction.ToggleFavorite(entry), LauncherLogic.tapAction(Mode.FAV_EDIT, app("com.a/A")))
        assertEquals(TapAction.ReorderPick(entry), LauncherLogic.tapAction(Mode.FAV_REORDER, app("com.a/A")))
        assertEquals(TapAction.ToggleTag(entry), LauncherLogic.tapAction(Mode.TAG_EDIT, app("com.a/A")))
    }

    /** An app row can't be rendered in COMMAND mode (that overview holds only command rows),
     *  but the mapping stays total: it resolves to None rather than throwing. */
    @Test fun `an app row in COMMAND mode resolves to nothing`() {
        assertEquals(TapAction.None, LauncherLogic.tapAction(Mode.COMMAND, app("com.a/A")))
    }

    // ---- sigil-named tags drill to a tag filter, not that sigil's mode --------

    /**
     * A tag may legitimately start with "!"/"-"/"~"/"?" — canonicalTag keeps them; only "#" is
     * stripped. Drilling such a tag prepends "#", landing the sigil at position 1 where
     * parseMode ignores it, so the drill stays a TAG_FILTER. This pins the prompt the two
     * actions hand back for those names AND that it round-trips through parseMode — the
     * reachability canonicalTag keeps the characters for. (rowsFor's half — that the resulting
     * "#!work" actually lists the app — is pinned in LauncherRowsTest.)
     */
    @Test fun `drilling a sigil-named tag builds a prompt that is still a tag filter`() {
        for (name in listOf("!work", "-work", "~work", "?work", "!!fun")) {
            assertEquals(TapAction.SetPrompt("#$name"), LauncherLogic.tapAction(Mode.TAG_FILTER, TagRow(name)))
            // The Enter path (that tag row is nearest the prompt) drills identically.
            assertEquals(
                EnterAction.SetPrompt("#$name"),
                LauncherLogic.enterAction(Mode.TAG_FILTER, command = null, promptBlank = false, lastRow = TagRow(name)),
            )
            assertEquals("\"#$name\" is no longer a tag filter", Mode.TAG_FILTER, LauncherLogic.parseMode("#$name"))
        }
    }

    /** The bulk-edit mirror: "##!work" edits the tag "!work", and its doubled hash keeps it in
     *  TAG_EDIT — a "#"-stripped tag can never collide with the "##" sigil the other way. */
    @Test fun `bulk-editing a sigil-named tag builds a double-hash prompt that stays TAG_EDIT`() {
        assertEquals(TapAction.SetPrompt("##!work"), LauncherLogic.tapAction(Mode.TAG_EDIT, TagRow("!work")))
        assertEquals(Mode.TAG_EDIT, LauncherLogic.parseMode("##!work"))
    }
}
