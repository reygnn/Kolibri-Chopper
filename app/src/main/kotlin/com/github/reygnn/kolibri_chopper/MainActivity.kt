package com.github.reygnn.kolibri_chopper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * The entire Kolibri Chopper: a text-only, terminal-styled launcher in one
 * file. Queries the launchable apps from [android.content.pm.PackageManager],
 * lists them in monospace green, filters as you type, launches on tap or Enter.
 *
 * No AndroidX, no Compose, no ViewBinding, no persistence, no crash reporting —
 * everything a launcher does not strictly need has been chopped off.
 */
class MainActivity : Activity() {

    private data class AppEntry(val label: String, val component: ComponentName)

    // NB: not named `foreground` — that collides with View.foreground (a
    // Drawable) inside the apply{} blocks below and hides this Int.
    private val fgColor = 0xFFD4D4D4.toInt()     // pleasant light gray
    private val fgColorDim = 0xFF808080.toInt()  // dimmer gray, for the hint

    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()

    private lateinit var listView: ListView
    private lateinit var prompt: EditText
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            fitsSystemWindows = false
        }

        // Results grow upward, sitting right above the command line.
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            isStackFromBottom = true
            setOnItemClickListener { _, _, position, _ -> launch(shownApps[position]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // The prompt: a bare monospace command line at the bottom.
        prompt = EditText(this).apply {
            hint = "> search"
            setHintTextColor(fgColorDim)
            setTextColor(fgColor)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, _, _ ->
                shownApps.firstOrNull()?.let { launch(it) }
                true
            }
        }
        root.addView(prompt, LinearLayout.LayoutParams(MATCH, WRAP))

        // Edge-to-edge is mandatory on Android 16 — pad for the system bars and
        // lift the prompt above the IME ourselves (platform insets, no AndroidX).
        val pad = 12.dp()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        adapter = AppListAdapter()
        listView.adapter = adapter

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        applyFilter(prompt.text?.toString().orEmpty())
    }

    /** HOME pressed while already home: reset to a clean prompt. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        prompt.setText("")
        listView.setSelection(shownApps.size)
        hideKeyboard()
    }

    private fun loadApps() {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = pm.queryIntentActivities(query, 0)
            .map { ri ->
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun applyFilter(raw: String) {
        val needle = raw.trim().lowercase(Locale.getDefault())
        shownApps = if (needle.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase(Locale.getDefault()).contains(needle) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun launch(entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // App vanished between listing and launch (uninstall race).
            Log.w("Chopper", "launch failed: ${entry.component}", e)
            Toast.makeText(this, "not found: ${entry.label}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w("Chopper", "launch denied: ${entry.component}", e)
            Toast.makeText(this, "denied: ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount(): Int = shownApps.size
        override fun getItem(position: Int): Any = shownApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(fgColor)
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            tv.text = shownApps[position].label
            return tv
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
