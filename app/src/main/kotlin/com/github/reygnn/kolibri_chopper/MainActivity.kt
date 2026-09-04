package com.github.reygnn.kolibri_chopper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
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
 * file. Enumerates the launchable apps via [android.content.pm.LauncherApps] (so
 * work-profile and cloned/private-space apps are covered too, each with its user),
 * lists them in monospace light gray, filters as you type, launches on tap/Enter.
 *
 * No AndroidX, no Compose, no ViewBinding, no persistence, no crash reporting —
 * everything a launcher does not strictly need has been chopped off.
 */
class MainActivity : Activity() {

    // labelLower is the case-folded label, precomputed once so the sort and the
    // per-keystroke filter never re-lowercase (the label never changes).
    private data class AppEntry(
        val label: String,
        val labelLower: String,
        val component: ComponentName,
        val user: UserHandle,
    )

    // NB: not named `foreground` — that collides with View.foreground (a
    // Drawable) inside the apply{} blocks below and hides this Int.
    private val fgColor = 0xFFD4D4D4.toInt()     // pleasant light gray
    private val fgColorDim = 0xFF808080.toInt()  // dimmer gray, for the hint

    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()

    // Bumped on every refresh so a slow load can tell it has been superseded by a
    // newer one (see refreshApps). Main-thread only — no synchronization needed.
    private var loadGeneration = 0

    private lateinit var listView: ListView
    private lateinit var prompt: EditText
    private lateinit var adapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            fitsSystemWindows = false
        }

        // Results grow upward, sitting right above the command line.
        listView = ListView(this).apply {
            divider = null  // setDivider(null) already zeroes the divider height
            isVerticalScrollBarEnabled = false
            isStackFromBottom = true
            setOnItemClickListener { _, _, position, _ -> launch(shownApps[position]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // The prompt: a bare monospace command line at the bottom.
        prompt = EditText(this).apply {
            hint = getString(R.string.hint_search)
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
        refreshApps()
    }

    /**
     * Load the launchable apps OFF the main thread — enumerating the profiles plus
     * loadLabel() per app touch disk/IPC and would jank the home screen on every
     * return. Swap the result in and re-apply the current filter on the UI thread.
     * Reloaded on each resume so install/uninstall changes surface. A generation
     * counter makes the most-recently-STARTED load win: if an older load finishes
     * after a newer one has started, its now-stale result is dropped.
     */
    private fun refreshApps() {
        val generation = ++loadGeneration
        Thread {
            val loaded = loadApps()
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread  // superseded
                allApps = loaded
                applyFilter(prompt.text?.toString().orEmpty())
            }
        }.start()
    }

    /** HOME pressed while already home: reset to a clean prompt. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // keep getIntent() in sync with the latest launch intent
        // setText("") fires the TextWatcher synchronously, so applyFilter has
        // already refreshed shownApps by the time setSelection reads its size.
        prompt.setText("")
        listView.setSelection(shownApps.size)
        hideKeyboard()
    }

    private fun loadApps(): List<AppEntry> {
        val launcherApps = getSystemService(LauncherApps::class.java)
        val pm = packageManager
        val self = packageName
        val personal = Process.myUserHandle()
        // This runs on a background thread, and a HOME app that throws on resume
        // loops forever — so nothing here may let an exception escape. LauncherApps
        // and getUserBadgedLabel surface plain RuntimeExceptions: SecurityException
        // when a profile turns inaccessible mid-scan (Home-role / work-profile
        // TOCTOU), or DeadSystemRuntimeException if system_server restarts. Catch
        // broadly and drop just the offending profile; give up to an empty list
        // only if even the top-level profile enumeration fails.
        val profiles = try {
            launcherApps.profiles
        } catch (e: RuntimeException) {
            Log.w("Chopper", "profile enumeration failed", e)
            return emptyList()
        }
        return profiles.flatMap { user ->
            try {
                launcherApps.getActivityList(null, user)
                    .filter { it.componentName.packageName != self }  // don't list ourselves
                    .map { info ->
                        val raw = info.label.toString()
                        // Badge non-personal entries ("Work Gmail") so a work copy
                        // isn't an identical text row. Only managed/work profiles get
                        // a localized text badge; clone/private-space may not, so
                        // those can still collide (rare, harmless). labelLower keys
                        // off the DISPLAYED text, so filtering matches what's on
                        // screen while the bare app name still matches both copies.
                        val display = if (user == personal) raw
                            else pm.getUserBadgedLabel(raw, user).toString()
                        AppEntry(
                            label = display,
                            labelLower = display.lowercase(Locale.getDefault()),
                            component = info.componentName,
                            user = info.user,
                        )
                    }
            } catch (e: RuntimeException) {
                Log.w("Chopper", "skipping profile $user", e)
                emptyList()
            }
        }.sortedBy { it.labelLower }
    }

    private fun applyFilter(raw: String) {
        val needle = raw.trim().lowercase(Locale.getDefault())
        shownApps = if (needle.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.labelLower.contains(needle) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun launch(entry: AppEntry) {
        // startMainActivity honours the entry's UserHandle — the only correct way
        // to launch a work-profile / cloned-app activity from here.
        val launcherApps = getSystemService(LauncherApps::class.java)
        try {
            // isActivityEnabled == false covers both the uninstall race (component
            // gone since the list loaded) and a profile that turned inaccessible.
            // Without it startMainActivity would throw SecurityException on the
            // former (misreported as "denied") and silently no-op on the latter.
            if (!launcherApps.isActivityEnabled(entry.component, entry.user)) {
                Log.w("Chopper", "launch unavailable: ${entry.component}")
                Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
                return
            }
            launcherApps.startMainActivity(entry.component, entry.user, null, null)
        } catch (e: SecurityException) {
            // Access revoked in the gap between the check and the launch.
            Log.w("Chopper", "launch denied: ${entry.component}", e)
            Toast.makeText(this, getString(R.string.toast_denied, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            // Dead system_server / profile removed at launch — don't crash HOME.
            Log.w("Chopper", "launch failed: ${entry.component}", e)
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
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
                ellipsize = TextUtils.TruncateAt.END
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
