package com.github.reygnn.kolibri_chopper

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView

/**
 * The long-press "set a custom name and tags" dialog, lifted out of [MainActivity] so the
 * Activity keeps only the config decision (what an empty or unchanged field means) and the
 * dialog lifecycle. This is a pure VIEW-construction extraction: unlike the LauncherLogic
 * split it earns no unit test — every line touches a framework widget — so its whole point
 * is readability, moving ~90 lines of EditText/dropdown wiring out of the Activity.
 *
 * The contract is deliberately narrow: strings in, a canonical (name, tags) pair out via
 * [onCommit]. The dialog trims the name and runs the tags through [LauncherLogic.parseTags]
 * (so the caller receives already-canonical tags), but does NOT decide whether an empty
 * name clears an override or whether an empty tag list drops the key — that reads the app's
 * system label and mutates the config, so it stays with the caller.
 */
internal object RenameDialog {

    /**
     * Build, show and return the rename dialog. The caller owns the returned [AlertDialog]:
     * it should track it (to dismiss on teardown) and attach its own dismiss listener.
     *
     * [initialName] is what the name field starts with (the custom name, or the app's own
     * label as the placeholder default). [initialTags] is the stored tags pre-joined as the
     * comma-separated string the field edits. [allTags] feeds the autocomplete pool.
     */
    fun show(
        context: Context,
        title: String,
        initialName: String,
        initialTags: String,
        allTags: List<String>,
        fgColor: Int,
        onCommit: (name: String, tags: List<String>) -> Unit,
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        // No autocorrect/autocapitalize on either field: a deliberate custom name or tag
        // must not be silently "corrected" on the way in.
        val noSuggest = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val nameInput = EditText(context).apply {
            setText(initialName)
            hint = context.getString(R.string.hint_rename_name)
            isSingleLine = true
            inputType = noSuggest
            setSelection(text.length)
        }
        val tagsInput = MultiAutoCompleteTextView(context).apply {
            // Show the stored tags back as a plain comma-separated list to edit.
            setText(initialTags)
            hint = context.getString(R.string.hint_rename_tags)
            isSingleLine = true
            inputType = noSuggest
            // Force tags lowercase as typed, via the same ROOT fold used to store and match
            // them — so the field shows exactly what gets saved, regardless of whether the
            // keyboard's shift/auto-capitalize is on. (inputType requests no caps, but that
            // hint isn't honored by every keyboard; the filter is the guarantee.) Only the
            // tag field is folded — a custom name keeps its case.
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                val sub = source.subSequence(start, end).toString()
                val folded = LauncherLogic.foldLabel(sub)
                if (folded == sub) null else folded  // null = accept unchanged
            })
            // Autocomplete the comma-separated token being typed from the already-defined
            // tags: type "g" and "games" is offered. A brand-new tag can still be typed
            // freely — the suggestions are additive. CommaTokenizer scopes the completion to
            // the current token so the others are left intact.
            setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            threshold = 1
            // Own dark, monospace dropdown so it fits the terminal look instead of the
            // default light Material popup. getView is fully overridden, so the unused
            // resource id passed to ArrayAdapter is never inflated.
            setAdapter(object : ArrayAdapter<String>(
                context, android.R.layout.simple_list_item_1, allTags
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView) ?: TextView(context).apply {
                        typeface = Typeface.MONOSPACE
                        setTextColor(fgColor)
                        textSize = 18f
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                    tv.text = getItem(position)
                    return tv
                }
            })
            setDropDownBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), 0)
            addView(nameInput, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(tagsInput, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // Dark dialog theme so the rename popup stays in the black terminal look instead of
        // flashing the platform's default light Material dialog.
        return AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                // Tags are canonicalised here; the caller only decides set-vs-drop.
                val tags = LauncherLogic.parseTags(tagsInput.text?.toString().orEmpty())
                onCommit(name, tags)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
