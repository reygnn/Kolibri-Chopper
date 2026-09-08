package com.github.reygnn.sigil_launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.MultiAutoCompleteTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric tests for [RenameDialog] — the ONE part of the app that a plain JVM test
 * cannot reach, because its behaviour lives in real framework widgets: an [EditText] with
 * an [InputFilter], and an [AlertDialog] button wired to a commit lambda. Everything else
 * in the app is pure and covered in the JUnit4-only suites; this file is the reason
 * Robolectric is on the test classpath at all.
 *
 * It pins two things that were previously untested:
 *  1. The tags field folds to lowercase AS TYPED (the "show exactly what will be saved"
 *     nicety), via the same ROOT fold [LauncherLogic.foldLabel] uses everywhere — including
 *     the case a fold CHANGES LENGTH ("İ" -> "i̇"), which is the fragile edge for a filter.
 *  2. The dialog's contract: strings in, a trimmed name and canonical [LauncherLogic.parseTags]
 *     tags out through [onCommit] when OK is pressed.
 *
 * No androidx.test / ActivityScenario: a bare [Activity] from [Robolectric.buildActivity]
 * gives the window token the dialog needs, and the field is reached by walking the shown
 * dialog's view tree — keeping the whole androidx.test dependency (and its force-pin) out.
 */
@RunWith(RobolectricTestRunner::class)
class RenameDialogFilterTest {

    private val activity: Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private val fgColor = 0xFFD4D4D4.toInt()

    /** Build and show the dialog, capturing whatever [onCommit] hands back. */
    private class Harness(val dialog: AlertDialog) {
        var committed: Pair<String, List<String>>? = null
    }

    private fun show(
        initialName: String = "",
        initialTags: String = "",
        allTags: List<String> = emptyList(),
    ): Harness {
        lateinit var harness: Harness
        val dialog = RenameDialog.show(
            context = activity,
            title = "Gmail",
            initialName = initialName,
            initialTags = initialTags,
            allTags = allTags,
            fgColor = fgColor,
        ) { name, tags -> harness.committed = name to tags }
        harness = Harness(dialog)
        return harness
    }

    /** Depth-first collect of every view of type [T] under this view. */
    private inline fun <reified T : View> View.collect(): List<T> =
        collectMatching(this) { it is T }.map { it as T }

    // Non-inline worker: an inline function may not hold a local (recursive) function, so the
    // reified wrapper above delegates the walk down here.
    private fun collectMatching(root: View, keep: (View) -> Boolean): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (keep(v)) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** The tags field is the only [MultiAutoCompleteTextView] in the dialog. */
    private fun Harness.tagsField(): MultiAutoCompleteTextView =
        dialog.window!!.decorView.collect<MultiAutoCompleteTextView>().single()

    /** The name field is the [EditText] that is NOT the tags [MultiAutoCompleteTextView]. */
    private fun Harness.nameField(): EditText =
        dialog.window!!.decorView.collect<EditText>().single { it !is MultiAutoCompleteTextView }

    /** Click a dialog button and drain the main looper: AlertDialog dispatches the button
     *  through a Handler message, and Robolectric's main looper is paused, so [onCommit]
     *  runs only once the queued message is processed. */
    private fun Harness.click(which: Int) {
        dialog.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun Harness.clickOk() = click(AlertDialog.BUTTON_POSITIVE)

    // ---- the tag InputFilter -------------------------------------------------

    @Test fun `the tags field carries exactly one filter - the fold, replacing defaults`() {
        // RenameDialog SETS filters to a single-element array, so anything else here means a
        // framework default slipped back in (isSingleLine etc.) and the fold is not alone.
        assertEquals(1, show().tagsField().filters.size)
    }

    @Test fun `the filter folds an uppercase run to lowercase`() {
        val filter = show().tagsField().filters.single()
        // A changed run is rewritten; an already-folded one is accepted unchanged (null).
        assertEquals("work", filter.filter("WORK", 0, 4, null, 0, 0).toString())
        assertEquals("chat", filter.filter("Chat", 0, 4, null, 0, 0).toString())
        assertNull(filter.filter("work", 0, 4, null, 0, 0))
    }

    @Test fun `the filter folds with ROOT even under a Turkish default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            val filter = show().tagsField().filters.single()
            // Under tr, "I".lowercase() would give the dotless "ı"; the field must fold to a
            // plain "i" so what it shows matches what the ROOT-folded store/search will hold.
            assertEquals("i", filter.filter("I", 0, 1, null, 0, 0).toString())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    /**
     * The fragile edge: a ROOT fold is NOT length-preserving. "İ" (U+0130) folds to "i̇"
     * (U+0069 U+0307), two code units for one. The filter must return that longer
     * replacement, and — the actual worry behind testing this through a real widget — the
     * EditText must accept it via an ordinary edit without throwing.
     */
    @Test fun `a length-changing fold is returned and the real widget accepts it`() {
        val harness = show()
        val field = harness.tagsField()

        // The filter returns the 2-unit replacement for the 1-unit source.
        assertEquals("i̇", field.filters.single().filter("İ", 0, 1, null, 0, 0).toString())

        // And typing it through the live Editable (which runs the filter on replace) folds it
        // in place with no IndexOutOfBounds from the length change.
        field.setText("")
        field.text.append("İ")
        assertEquals("i̇", field.text.toString())
    }

    // ---- the commit contract -------------------------------------------------

    @Test fun `OK hands back a trimmed name and canonical, de-duplicated tags`() {
        val harness = show(initialName = "  My Gmail  ", initialTags = "#Work, Chat, work")
        harness.clickOk()

        val (name, tags) = harness.committed!!
        assertEquals("My Gmail", name)                 // trimmed
        assertEquals(listOf("work", "chat"), tags)     // folded, '#' stripped, "work" de-duped
    }

    @Test fun `OK on empty fields hands back an empty name and no tags`() {
        val harness = show(initialName = "", initialTags = "   ")
        harness.clickOk()

        val (name, tags) = harness.committed!!
        assertEquals("", name)
        assertTrue(tags.isEmpty())
    }

    @Test fun `Cancel commits nothing`() {
        val harness = show(initialName = "changed", initialTags = "work")
        harness.click(AlertDialog.BUTTON_NEGATIVE)
        assertNull(harness.committed)
    }
}
