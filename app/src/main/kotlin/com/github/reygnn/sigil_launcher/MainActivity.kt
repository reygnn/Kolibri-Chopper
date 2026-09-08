package com.github.reygnn.sigil_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The entire Sigil Launcher: a text-only, terminal-styled launcher in one
 * file. Enumerates the current user's launchable apps via a
 * [android.content.pm.PackageManager] MAIN/LAUNCHER query, lists them in
 * monospace light gray, filters as you type, launches on tap/Enter.
 *
 * Config lives in the app's own internal storage (sigil.json in filesDir) — no
 * storage permission on any Android version, and the app owns it end to end, so
 * it is edited entirely through the in-app modes below, never a text editor.
 * Prompt grammar (leading sigil = mode):
 *   ""      normal: favorites only (or the drawer if none are set yet)
 *   text    normal: substring search across ALL apps (hidden included)
 *   *       normal: the full app drawer (every non-hidden app)
 *   #       tags: lists the tags in use; tap one to drill into its apps
 *   #text   tag filter: apps under every tag prefix-matching [text], tap/Enter
 *           launches. Tags are assigned via the long-press dialog, or in bulk with "##"
 *   ##      edit tags: lists the tags in use; tap one to get EVERY app with an
 *           [x]/[ ] for that tag, tap a row to toggle it. The long-press dialog stays
 *           the way to invent a tag and to set several on one app; this is the way to
 *           put one tag on many apps
 *   -[text] edit hidden:    tap a row to toggle its [x], persisted immediately
 *   ![text] edit favorites: tap a row to toggle its [x], persisted immediately
 *   !!      reorder favorites: tap a row to pick it up (marked »), tap another
 *           row to drop it there; tap the picked row again to cancel
 *   ?       recents: the last-launched apps (in memory only, empty after restart)
 *   ~[text] commands: lists the "~" commands still matching [text] (a bare "~"
 *           lists every one); tap a row to run it
 * The "~" commands. Tap one in that overview, or type enough of one to be
 * unambiguous ("~b" can only be "~backup") and press Enter. Each acts once and
 * clears the prompt:
 *   ~ / ~load  reload sigil.json from disk (the config is cached otherwise)
 *   ~save      flush the in-memory config to disk (saves are automatic; this is
 *              the explicit "write it now" for peace of mind)
 *   ~backup    export the config to Download/Sigil/sigil.json, replacing
 *              the previous one — there is always exactly one backup
 *   ~restore   adopt that backup again. No picker: one file, known name. The config
 *              being replaced is written next to it as sigil-pre-restore.json, so
 *              a restore never destroys the state it overwrote without a trace.
 *   ~restore-saf
 *              same, but PICK the file via the system document picker. The escape
 *              hatch for a config carried over from another phone, which plain
 *              ~restore cannot see (see [restoreConfig]).
 * Long-press any row to set a custom name and its tags.
 */
class MainActivity : Activity() {

    // labelLower is the case-folded label, precomputed once so the sort and the
    // per-keystroke filter never re-lowercase (the label never changes).
    private data class AppEntry(
        val label: String,
        override val labelLower: String,
        val component: ComponentName,
        // The app's own label as the system reports it — WITHOUT any custom name.
        // Kept so a rename can be applied (or cleared, falling back to this)
        // in-memory, re-baking only the affected component's rows instead of
        // re-enumerating every app just to rebuild one label.
        val systemLabel: String,
    ) : Ordered {
        // The flattened "package/class" component string — the identity key for
        // hidden/favorites/names throughout. A package can expose several launcher
        // activities (e.g. "Google" and "Voice Search"), so keying on the package
        // alone would make one row's toggle/rename bleed onto its siblings; the
        // full component keeps each launchable entry independent. Body val, so it
        // stays out of equals/hashCode/copy — the identity is still the component.
        override val key: String = component.flattenToString()
    }

    // The row types (Row/AppRow/TagRow/CommandRow) now live top-level in LauncherLogic.kt,
    // beside the pure rowsFor that builds them, so both can be unit-tested off-device. Here
    // they are parameterised with AppEntry: `shown` is a List<Row<AppEntry>>.

    // NB: not named `foreground` — that collides with View.foreground (a
    // Drawable) inside the apply{} blocks below and hides this Int.
    private val fgColor = 0xFFD4D4D4.toInt()     // pleasant light gray
    private val fgColorDim = 0xFF808080.toInt()  // dimmer gray, for the hint

    private var cfg = SigilConfig()
    // The config is read from disk once, then cfg IS the cache — the app is its
    // only writer, so every HOME press re-reading it would be wasted I/O. The "~"
    // command forces a fresh read (see refreshApps / the Enter handler).
    private var configLoaded = false
    private var mode = Mode.NORMAL

    // In FAV_REORDER: the component key of the favorite currently "picked up",
    // or null when nothing is held. First tap picks a row up (marked » in
    // getView), the next tap on another row drops it there; tapping the held row
    // again cancels. Reset whenever the mode is left (see applyFilter), so a stale
    // key from an old reorder session can never move the wrong row later.
    private var reorderPick: String? = null

    // In TAG_EDIT: the tag whose membership the checkboxes show, i.e. whatever follows
    // "##", folded. Null while the bare-"##" overview is up (nothing chosen yet), so the
    // row binder and the tap handler have one place to ask "which tag is this about?"
    // instead of re-parsing the prompt. Recomputed by applyFilter on every keystroke.
    private var tagEditTag: String? = null

    // How many times [shown] has been replaced, and what that counter read when the
    // finger last went down. AbsListView POSTS its click and long-press, and it only drops
    // a pending one while its own mDataChanged flag is up — once a layout pass has run the
    // flag is clear again and the gesture lands on whatever row has since moved into that
    // slot. Comparing the two makes a gesture act only on the list it was aimed at.
    private var shownGeneration = 0
    // The generation the list had when THIS gesture's finger went down. Read LIVE by the
    // long-press guard (CheckForLongPress fires before ACTION_UP, finger still down), then
    // reset to NO_TOUCH the moment the gesture ends so a later accessibility click is not
    // measured against a dead gesture.
    private var touchDownGeneration = TapGuard.NO_TOUCH

    // The down-generation handed over at ACTION_UP for the click the framework may still
    // POST. A SEPARATE field from touchDownGeneration precisely because that one is cleared
    // at UP: the click is delivered LATER, so it needs the down-generation preserved to
    // compare against the CURRENT shownGeneration at delivery — that live compare is what
    // catches a list replacement landing between ACTION_UP and the posted click. NO_TOUCH
    // means "no touch-originated click pending", so an accessibility/hardware click passes.
    private var clickDownGeneration = TapGuard.NO_TOUCH

    private var allApps: List<AppEntry> = emptyList()
    // What the ListView currently shows: app rows in every mode, or tag-name rows in
    // the bare-"#" overview. Reassigned only by applyFilter (from LauncherLogic.rowsFor).
    private var shown: List<Row<AppEntry>> = emptyList()

    // The "?" mode: the component keys of the most recently launched apps, newest
    // first. Deliberately IN MEMORY ONLY — never written to sigil.json — so it
    // starts empty on every cold start. Updated only by launch() on a successful
    // start; capped at RECENTS_LIMIT via LauncherLogic.pushRecent.
    private var recentKeys: List<String> = emptyList()

    // One-shot guard for the "recents are empty because we cold-started" toast. The
    // launcher process is killed under memory pressure (aggressively on low-RAM
    // devices), which silently empties recentKeys; the hint stops that reading as
    // lost data. Shown at most once per process — see applyFilter.
    private var recentsEmptyHintShown = false

    // Three separate counters keep three separate concerns from stepping on each
    // other. All are written only on the main thread (single writer, so ++ stays
    // safe); @Volatile so the IO loader can read the latest value and bail early.
    //
    //   loadGeneration  — bumped by refreshApps ONLY. "Newest enumeration wins":
    //                     an older in-flight load bails when a newer refresh starts.
    //   labelGeneration — bumped by a RENAME only. A rename changes what loadApps
    //                     produces (labels), so a load whose name-snapshot predates
    //                     the rename must not commit its now-stale allApps.
    //   configEpoch     — bumped by ANY config mutation (toggle OR rename). Guards
    //                     the "~" reload's wholesale `cfg = useCfg`: if the config
    //                     changed since the disk read, the in-memory copy already
    //                     holds the newer truth and must not be clobbered.
    //
    // The point of the split: a plain onResume reload only reads app labels, so a
    // membership toggle (configEpoch only) no longer throws its enumeration away —
    // newly installed/removed apps still surface. Only a rename (labelGeneration)
    // invalidates that enumeration, because only a rename makes its labels wrong.
    @Volatile private var loadGeneration = 0
    @Volatile private var labelGeneration = 0
    @Volatile private var configEpoch = 0

    // A single background thread owns ALL disk I/O — both the app reload and the
    // config write run here. Off the main thread (a tap or resume never blocks on
    // disk) and serialized against each other, so two rapid saves can't race on the
    // temp file and reloads can't pile up into an unbounded number of threads.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var listView: ListView
    private lateinit var prompt: EditText
    private lateinit var adapter: AppListAdapter

    // The long-press rename dialog, tracked only so it can be dismissed if the
    // activity is torn down while it is open — an undismissed dialog leaks its
    // window (and would crash on the resulting bad token). configChanges keeps
    // rotation from recreating us, so process death is the realistic trigger.
    private var renameDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Under enforced edge-to-edge (Android 16) the system does NOT push our content
        // up for the keyboard on its own — adjustResize is a no-op and the IME just
        // covers the prompt. So we own the insets and pad for the IME ourselves (below).
        window.setDecorFitsSystemWindows(false)

        // Keyboard-first launcher: this is a type-to-filter command line, so the IME is
        // part of the resting state, not something the user should have to summon. Ask the
        // window to bring it up whenever it receives focus — this covers the cold start and
        // every return to the foreground. Warm re-entry is additionally nudged from
        // onResume via showKeyboard(), which under decorFitsSystemWindows(false) is the
        // reliable path. Back still hides the IME on demand (see the back callback): that
        // is a deliberate user override of the default, not a change to it.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

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
            // Record which list the finger went down on. Returns false without fail: this
            // must observe the gesture, never consume it — the ListView still has to do
            // its own scrolling and click detection.
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownGeneration = shownGeneration
                        clickDownGeneration = TapGuard.NO_TOUCH  // new gesture, nothing to deliver yet
                    }
                    MotionEvent.ACTION_UP -> {
                        // Hand this gesture's down-generation to the click field and clear the
                        // live one. The click compares it against shownGeneration when it is
                        // actually delivered, so a replacement AFTER this UP is still caught.
                        clickDownGeneration = touchDownGeneration
                        touchDownGeneration = TapGuard.NO_TOUCH
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchDownGeneration = TapGuard.NO_TOUCH
                        clickDownGeneration = TapGuard.NO_TOUCH
                    }
                }
                false
            }
            setOnItemClickListener { _, _, position, _ ->
                if (!clickStillValid()) return@setOnItemClickListener
                // getOrNull, not [position]: a background load can complete on the
                // main thread between the frame the user tapped and this click
                // message running, shrinking shown — a stale position would then
                // throw. A HOME app must never crash, so drop the tap instead.
                when (val row = shown.getOrNull(position)) {
                    // A tag row (bare "#") drills into that tag's apps by rewriting the
                    // prompt — the TextWatcher then re-filters through applyFilter.
                    // The same row type serves both overviews; which sigil it drills
                    // into is decided by the mode it was rendered in.
                    is TagRow ->
                        prompt.setText(if (mode == Mode.TAG_EDIT) "##${row.name}" else "#${row.name}")
                    // Clear FIRST, exactly as the Enter path does: setText fires the
                    // TextWatcher synchronously, so the mode is back to NORMAL before
                    // the command runs and the re-render can't land on a stale overview.
                    is CommandRow -> {
                        prompt.setText("")
                        runCommand(row.command)
                    }
                    is AppRow -> when (mode) {
                        Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER -> launch(row.entry)
                        Mode.HIDDEN_EDIT -> toggle(cfg.hidden, row.entry.key)
                        Mode.FAV_EDIT    -> toggle(cfg.favorites, row.entry.key)
                        Mode.FAV_REORDER -> reorderTap(row.entry.key)
                        Mode.TAG_EDIT -> toggleTagOn(row.entry.key)
                        // The "~" overview holds CommandRows, never AppRows — its taps
                        // are handled by the CommandRow branch above. Named only to keep
                        // this when exhaustive.
                        Mode.COMMAND -> {}
                    }
                    null -> {}  // stale position
                }
            }
            // Long-press: set/clear a custom name (and tags) for an app. Tag rows have
            // no long-press action.
            setOnItemLongClickListener { _, _, position, _ ->
                // Deliberately NOT the click's helper: a long-press fires while the
                // finger is still down, so it must compare live rather than read a verdict
                // that only exists after ACTION_UP. Same exposure though — AbsListView
                // gates it on the mDataChanged flag an intervening layout clears — and a
                // stale one opens the rename dialog for whichever app moved in underneath.
                // Not consumed when stale: let the framework do nothing.
                val row = shown.getOrNull(position) as? AppRow
                    ?: return@setOnItemLongClickListener false  // tag row or stale: not consumed
                // Row type FIRST, guard second: a long-press on a tag row does nothing, and
                // consuming the marker there would leave the click that follows it
                // unguarded.
                if (!longPressStillValid()) return@setOnItemLongClickListener false
                promptRename(row.entry)
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // A clear button for the prompt, sitting at the end of the command line. Shown
        // only while there IS something to erase (see the TextWatcher), it wipes the line
        // back to favorites without touching the keyboard — so the user can retype right
        // away, unlike Back, which also drops the IME.
        val clearButton = TextView(this).apply {
            text = "×"  // × — a clear glyph that sits right in the monospace line
            typeface = Typeface.MONOSPACE
            textSize = 24f
            setTextColor(fgColorDim)
            gravity = Gravity.CENTER
            minWidth = 48.dp()  // a comfortable tap target next to the narrow glyph
            contentDescription = getString(R.string.clear_prompt)
            isFocusable = false  // keep focus (and the IME) on the prompt when tapped
            visibility = View.GONE
            setOnClickListener { prompt.setText("") }  // TextWatcher -> applyFilter resets to NORMAL
        }

        // The prompt: a bare monospace command line at the bottom.
        prompt = EditText(this).apply {
            hint = getString(R.string.hint_search)
            setHintTextColor(fgColorDim)
            setTextColor(fgColor)
            typeface = Typeface.MONOSPACE
            textSize = 20f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    // Track the button off the actual text, so it appears/vanishes whether
                    // the change came from typing or from a setText("") elsewhere.
                    clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    applyFilter(text)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                // Act once per Enter. A soft-keyboard action arrives once with a
                // null event; a hardware/Bluetooth Enter invokes this on BOTH the
                // key-down and the key-up, so without this guard "~", a launch and
                // the edit-mode "done" gesture would each fire twice. Handle the GO
                // action or the key-DOWN edge only; ignore (don't consume) the rest.
                val enterDown = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId != EditorInfo.IME_ACTION_GO && !enterDown) {
                    return@setOnEditorActionListener false
                }
                // A "~" command is one-shot, not a view, so it lives on Enter rather
                // than on a live sigil — nothing happens while it is being typed.
                val command = LauncherLogic.resolveCommand(
                    prompt.text?.toString()?.trim().orEmpty()
                )
                when {
                    // Clear the prompt BEFORE running it ("" parses to no command),
                    // so a second Enter can't fire the same command again.
                    command != null -> {
                        prompt.setText("")
                        runCommand(command)
                    }
                    // An abbreviation that still fits more than one command ("~r"):
                    // do nothing at all. Clearing would throw away what was typed, and
                    // the overview is right there showing what is still in the running —
                    // one more keystroke settles it.
                    mode == Mode.COMMAND -> {}
                    // "##" is two views behind one sigil, so it cannot join the read
                    // modes below: with the overview up Enter drills into the nearest tag,
                    // with the checkbox list up it is a plain "done". It must never reach
                    // the launch branch — Enter in an edit mode has never launched.
                    mode == Mode.TAG_EDIT -> when (val last = shown.lastOrNull()) {
                        is TagRow -> prompt.setText("##${last.name}")
                        else -> prompt.setText("")
                    }
                    // A "leeres Enter" — Enter on an empty prompt — opens the full drawer
                    // via "*" instead of launching the favorite nearest the command line.
                    // An empty prompt is always NORMAL (every other mode needs a sigil) and
                    // shows favorites, so its previous Enter target was the top favorite;
                    // routing it to "*" makes Enter-from-rest mean "show everything" rather
                    // than "launch this one". setText drives the TextWatcher exactly as
                    // typing "*" does, so the drawer and the visible command line stay one
                    // source of truth. It sits before the launch branch so it wins for the
                    // empty prompt; the command branch above already no-ops on "".
                    //
                    // The star is a trailing wildcard: setText parks the cursor at index 0
                    // (before the "*"), so a letter typed next grows "a*", "ab*", … and the
                    // drawer narrows by prefix (see LauncherLogic.drawerStartingWith).
                    prompt.text.isNullOrBlank() -> prompt.setText("*")
                    // In an edit mode Enter is a "done" gesture: clear the prompt
                    // back to normal instead of launching whatever sits at the top.
                    // lastOrNull, not firstOrNull: with isStackFromBottom the list
                    // fills upward from the command line, so the LAST row is the one
                    // sitting directly above the prompt — the natural Enter target.
                    // (Swap to firstOrNull if you'd rather Enter pick the
                    // alphabetically-first match instead.)
                    // The read modes act on the row nearest the command line: launch it
                    // if it's an app, or — in the bare-"#" tag overview — drill into the
                    // nearest tag. (An empty NORMAL prompt is peeled off just above; edit
                    // modes fall through to the prompt-clearing "done" gesture below.)
                    mode == Mode.NORMAL || mode == Mode.RECENTS || mode == Mode.TAG_FILTER ->
                        when (val last = shown.lastOrNull()) {
                            is AppRow -> launch(last.entry)
                            is TagRow -> prompt.setText("#${last.name}")
                            // Unreachable: COMMAND mode returns at the branch above, so
                            // no command row ever reaches this one.
                            is CommandRow -> {}
                            null -> {}
                        }
                    else -> prompt.setText("")
                }
                true
            }
        }
        // The prompt takes the row's width (weight 1); the clear button rides at its end,
        // as tall as the row so its glyph lines up with the command line.
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clearButton, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        root.addView(promptRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Pad the root for the system bars AND the IME together. getInsets(systemBars |
        // ime) returns the max per edge, so the bottom padding is the keyboard height
        // when it's up and the nav-bar height otherwise: the weight-1 ListView shrinks
        // from the bottom — staying FULLY on-screen and scrollable (translating it
        // instead pushed the top of a long list off the top edge) — and the prompt
        // rides up just above the keyboard.
        //
        // Deliberately NO WindowInsetsAnimation callback: applying the inset once, when
        // it settles, keeps the ListView from re-laying-out on every animation frame —
        // that per-frame relayout is what made the list blink/redraw before. The prompt
        // moves in a single step rather than tracking the slide; a fair trade for a
        // launcher's command line, and it never clips or flickers.
        val pad = 12.dp()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
            insets
        }

        adapter = AppListAdapter()
        listView.adapter = adapter

        setContentView(root)

        // Make the prompt the initial input target so the always-visible IME (set above)
        // has somewhere to type on the very first frame. The prompt is the only text field,
        // and the clear button / list rows don't take focus in touch mode, so this just
        // makes the natural target explicit rather than relying on default focus order.
        prompt.requestFocus()

        // Back on a HOME launcher must not finish the activity — we're the home
        // screen, so the platform's default callback (routed here on targetSdk 36
        // whether or not we opt in) would just finish us and bounce straight back
        // via HOME. Intercept it: a non-empty prompt (a search or an edit mode)
        // clears back to favorites; an empty prompt is a deliberate no-op. Lives
        // for the whole activity — there's no "done" state to unregister at.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            // Drop the keyboard FIRST, and unconditionally: focusing the prompt raises the
            // IME without putting any text into it, so on an empty prompt the old
            // isNotEmpty-guarded version skipped hideKeyboard() entirely. Since this
            // callback swallows every back press, that left no way to dismiss the keyboard
            // with back at all.
            hideKeyboard()
            if (prompt.text?.isNotEmpty() == true) {
                prompt.setText("")   // TextWatcher -> applyFilter resets mode to NORMAL
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        // Re-raise the IME on every return to the foreground (HOME re-entry, coming back
        // from a launched app or a dialog). The window flag set in onCreate covers the
        // cold start; this is the reliable warm-path show under decorFitsSystemWindows(false).
        // onResume never fires from a Back-dismiss (Back is swallowed and keeps us in the
        // launcher), so this never fights a user who deliberately hid the keyboard.
        showKeyboard()
    }

    /**
     * Reload the launchable apps OFF the main thread — enumerating the apps
     * plus loadLabel() per app touch disk/IPC and would jank the home screen on
     * every return. Runs on each resume so install/uninstall changes surface.
     *
     * The config is decoupled: read from disk only on the first load (cold start)
     * or when [reloadConfig] is set (the "~" command), and cached in [cfg]
     * otherwise — the app is its sole writer, so the in-memory copy is always
     * authoritative and re-reading every resume is pointless I/O. The reference is
     * captured on the main thread so the background load can't race a reassign.
     *
     * A generation counter makes the most-recently-REQUESTED refresh win. Loads run
     * on the single-threaded ioExecutor, so they no longer overlap; a refresh queued
     * behind a running one bails out at the top (before any disk/IPC work) once a yet
     * newer refresh supersedes it, and any result that still slips through is dropped
     * at the UI hand-off.
     */
    private fun refreshApps(reloadConfig: Boolean = false) {
        val generation = ++loadGeneration
        // Capture the label/config epochs at REQUEST time, alongside the name
        // snapshot the load will read from. If either advances before the hand-off,
        // an in-memory rename/toggle has already applied the newer truth and this
        // load's result would clobber it.
        val labelGen = labelGeneration
        val cfgEpoch = configEpoch
        val loadConfigNow = reloadConfig || !configLoaded
        // Snapshot on the MAIN thread: the loader must not read the live cfg, or
        // loadApps() enumerating cfg.names could race a rename/toggle mutating it.
        // Skipped when we're about to reload from disk (that path ignores it).
        val cached = if (loadConfigNow) SigilConfig() else cfg.snapshot()
        submitIo {
            // Already superseded by a newer enumeration while queued: skip the work.
            if (generation != loadGeneration) return@submitIo
            val useCfg = if (loadConfigNow) store.load() else cached
            val loaded = loadApps(useCfg)
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread  // superseded
                if (loadConfigNow) {
                    // Only adopt the disk copy if no toggle/rename slipped in since
                    // the read; otherwise the live cfg is newer — keep it. (On cold
                    // start no mutation is possible yet, so this always adopts.)
                    if (configEpoch == cfgEpoch) {
                        cfg = useCfg
                        // The rename dialog read the OLD cfg when it opened and writes
                        // back on OK; leaving it up over a wholesale replace lets it
                        // persist its stale fields into the config that just arrived.
                        renameDialog?.dismiss()
                        configLoaded = true
                    }
                }
                // Commit the enumeration unless a rename made its labels stale. A
                // membership toggle does NOT touch labels, so it never blocks this —
                // that is what lets a toggle-during-resume still pick up installs.
                // loaded is null only when the enumeration itself failed (e.g. a
                // system_server restart): keep the last-known-good list instead of
                // wiping the home screen — a transient failure no longer blanks it.
                if (labelGeneration == labelGen) {
                    loaded?.let { allApps = it }
                }
                applyFilter(prompt.text?.toString().orEmpty())
            }
        }
    }

    override fun onDestroy() {
        // Tear down an open rename dialog first — an undismissed dialog leaks its
        // window once the activity's context is gone. setOnDismissListener nulls the
        // field; guarding here is belt-and-suspenders.
        renameDialog?.dismiss()
        renameDialog = null
        // A HOME activity is rarely destroyed, but shut the IO thread down cleanly
        // if it is. shutdown() (not shutdownNow) lets an in-flight save finish.
        ioExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Submit disk work to [ioExecutor] without ever letting a post-shutdown
     * submission crash the app. Every caller today runs on the main thread — the
     * same thread that calls shutdown() in onDestroy — so the isShutdown check is
     * race-free as written; the catch is belt-and-suspenders for any future
     * refactor that adds an off-main caller and could lose that guarantee.
     * Dropping a task once the executor is down is fine: the activity is going
     * away, so an unwritten save or a skipped reload no longer matters.
     */
    private fun submitIo(task: Runnable): Boolean {
        if (ioExecutor.isShutdown) return false
        return try {
            ioExecutor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            Log.w("Sigil", "IO task rejected (executor shutting down)", e)
            false
        }
    }

    /** HOME pressed while already home: reset to a clean prompt. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // keep getIntent() in sync with the latest launch intent
        // HOME while already home is a "reset to a clean prompt" gesture, so a rename
        // dialog left open has no business surviving it: the list underneath jumps back to
        // favorites while a modal for some other row stays on top, and confirming it would
        // then apply a rename the user has visually left behind.
        renameDialog?.dismiss()
        // setText("") fires the TextWatcher synchronously, so applyFilter has
        // already refreshed the shown rows (and reset mode to NORMAL) by the time we
        // scroll to the last (bottom-most, nearest the prompt) row below.
        prompt.setText("")
        // Last valid index is size - 1; guard the empty case (no apps loaded yet or
        // everything hidden), where size - 1 would be an invalid -1.
        if (shown.isNotEmpty()) listView.setSelection(shown.size - 1)
        hideKeyboard()
    }

    // ---- config -------------------------------------------------------------

    /**
     * The sigil.json file layer. Everything Android-specific it needs is injected here:
     * filesDir, android.util.Log and the directory fsync. Loading, rotating and publishing
     * itself lives in [ConfigStore], where it is unit-tested away from the Activity.
     */
    private val store by lazy {
        ConfigStore(
            dir = filesDir,
            log = { msg, e -> if (e != null) Log.w("Sigil", msg, e) else Log.w("Sigil", msg) },
            syncDir = ::fsyncDir,
        )
    }

    /**
     * The shared-storage backup layer (Download/[BACKUP_DIR]). The MediaStore mechanics live
     * in [BackupStore]; the Activity keeps only the orchestration that needs it — exportConfig,
     * restoreConfig and the "~restore-saf" picker.
     */
    private val backupStore by lazy {
        BackupStore(
            resolver = contentResolver,
            subDir = BACKUP_DIR,
            log = { msg, e -> if (e != null) Log.w("Sigil", msg, e) else Log.w("Sigil", msg) },
        )
    }

    /**
     * Persist the current config to disk. Serializes the live [cfg] on the calling
     * (main) thread — cfg is only ever mutated there, so the read is race-free and
     * the result is an immutable snapshot — then hands the write to [ioExecutor] so a
     * toggle/rename tap never blocks on disk. rotateBackup = true: a normal save
     * keeps a last-known-good .bak mirror (see [ConfigStore.write]).
     */
    private fun saveConfig(onDone: ((Boolean) -> Unit)? = null) {
        val payload = ConfigJson.serialize(cfg)
        // [onDone] exists for "~save", which has to tell the user something true. Every
        // other caller saves as a side effect of a tap that already shows its result on
        // screen, and passes nothing.
        val accepted = submitIo {
            val ok = store.write(payload, rotateBackup = true)
            onDone?.let { cb -> runOnUiThread { cb(ok) } }
        }
        // Refused outright (the IO thread is shutting down): nothing will ever run, so
        // report the failure here rather than leaving the caller waiting on a callback
        // that cannot arrive.
        if (!accepted) onDone?.invoke(false)
    }

    /**
     * Run a one-shot "~" command. RELOAD stays silent — its effect is the list
     * redrawing, which is its own feedback. The other three do their work off-screen
     * (or in another app entirely), so each one reports back with a toast; a save you
     * cannot see happen is a save you do not trust.
     */
    private fun runCommand(command: Command) {
        when (command) {
            Command.RELOAD -> refreshApps(reloadConfig = true)
            // Toast from the IO callback, on the real result — NOT straight after
            // submitting. saveConfig only queues the write; announcing success before the
            // write is even attempted is exactly the "save you cannot see happen" this
            // command exists to rule out.
            Command.SAVE -> saveConfig { ok ->
                toast(getString(if (ok) R.string.toast_saved else R.string.toast_save_failed))
            }
            Command.BACKUP -> exportConfig(BACKUP_NAME)
            Command.RESTORE -> restoreConfig()
            Command.RESTORE_SAF -> pickRestoreFile()
        }
    }

    /**
     * Export the CURRENT config to Download/[BACKUP_DIR] under a FIXED [name],
     * replacing whatever was there before.
     *
     * There is exactly one backup and exactly one undo point — no timestamps, no
     * accumulating pile to sift through later. That is also what lets [restoreConfig]
     * work without a file picker: the file it has to read is the one it knows the name
     * of.
     *
     * Different from the .bak mirror [writeConfigFile] keeps: that one rotates away
     * after two saves and lives in filesDir, so it dies with the app. This one is an
     * ordinary file in shared storage that can be copied off the device.
     *
     * Serialized on the main thread (like [saveConfig]: cfg is only ever mutated here,
     * so the read is race-free), written on [ioExecutor].
     */
    private fun exportConfig(name: String) {
        val payload = ConfigJson.serialize(cfg)
        submitIo {
            val ok = backupStore.write(name, payload)
            runOnUiThread {
                // Name the file that was actually written: exportConfig serves both
                // "~backup" and the undo point a restore leaves behind, and a toast
                // naming the wrong one of the two is worse than none.
                if (ok) toast(getString(R.string.toast_backup_ok, name), Toast.LENGTH_LONG)
                else toast(getString(R.string.toast_backup_failed))
            }
        }
    }

    /**
     * "~restore": read the one backup back in. No picker — there is a single file under
     * a known name, so there is nothing to choose.
     *
     * The read runs on [ioExecutor]; only the adoption returns to the main thread, where
     * cfg lives.
     */
    private fun restoreConfig() {
        submitIo {
            val uri = backupStore.findOwn(BACKUP_NAME)
                // A backup interrupted between deleting the old row and renaming the new
                // one leaves the complete payload under the temp name. Picking it up here
                // is what makes that window RECOVERABLE rather than merely survivable —
                // without it the user is told "no backup" while a good one sits on disk.
                // A temp torn mid-write is no risk: parseForeign refuses anything that is
                // not a readable config, so a half-written one is rejected, not adopted.
                ?: backupStore.findOwn("$BACKUP_NAME.tmp")?.also {
                    Log.w("Sigil", "restore: no published backup — using an interrupted one")
                }
            val text = uri?.let { backupStore.readText(it) }
            runOnUiThread {
                // Nothing there at all is its own message: "~backup was never run" is a
                // different problem from "the backup is broken", and saying so saves the
                // user hunting for a file that does not exist.
                if (text == null) toast(getString(R.string.toast_restore_none), Toast.LENGTH_LONG)
                else adoptRestored(text)
            }
        }
    }

    /**
     * Ask the system picker for a config, then adopt it — the "~restore-saf" command.
     *
     * Plain "~restore" reads the app's OWN MediaStore row, which is invisible after a
     * reinstall and on a different phone; ACTION_OPEN_DOCUMENT has no such blind spot,
     * because the user granting the pick IS the permission. So this is the way a config
     * moves between devices, at the cost of one dialog.
     */
    private fun pickRestoreFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // "*/*" plus a hint list, not a strict "application/json": a .json arrives
            // typed as text/plain or octet-stream depending on who wrote or copied it,
            // and a strict filter would grey out the very file being looked for.
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            )
            // Open ON the backup folder rather than wherever the picker last was — the
            // backups live in a SUB-folder of Downloads, so a picker landing in Downloads
            // root shows everything EXCEPT them. Only a hint; DocumentsUI ignores it when
            // it cannot honour it (folder not created yet, no backup ever written).
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIR",
                ),
            )
        try {
            startActivityForResult(intent, REQ_RESTORE)
        } catch (e: ActivityNotFoundException) {
            Log.w("Sigil", "no document picker on this device", e)
            toast(getString(R.string.toast_restore_no_picker))
        }
    }

    /** The "~restore-saf" picker's answer. Reading stays off the main thread; only the
     *  adoption comes back to it, exactly as in [restoreConfig]. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        submitIo {
            val text = backupStore.readText(uri)
            runOnUiThread {
                // Unlike [restoreConfig] a null here is not "no backup yet" — the user
                // pointed at a file and it could not be read. Same message as a file that
                // does not parse: either way nothing was changed.
                if (text == null) toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                else adoptRestored(text)
            }
        }
    }

    /**
     * Adopt config text that [restoreConfig] or the "~restore-saf" picker read, back on
     * the main thread. The caller has already established there WAS something to read.
     *
     * Order matters and is the whole safety story: PARSE first, so a malformed backup
     * aborts with the live config untouched. Only with a valid config in hand is the
     * outgoing one written out as the undo point, and only then is the new one adopted.
     */
    private fun adoptRestored(text: String) {
        // parseForeign, NOT parse: the lenient parser would turn any JSON object at all
        // into an empty config and wipe everything without so much as an error.
        val restored = ConfigJson.parseForeign(text)
        if (restored == null) {
            toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
            return
        }
        // What a restore actually adopted, so a "my favorites are gone" report can be
        // settled from the log instead of guessed at.
        Log.i(
            "Sigil",
            "restore: ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        // The undo point. Reads the still-live cfg synchronously, so it must run before
        // the reassign below.
        exportConfig(PRE_RESTORE_NAME)
        // Wholesale replace, exactly like the "~" reload does — and bump configEpoch for
        // the same reason it does: an enumeration already in flight captured the OLD
        // cfg, and must not be allowed to hand it back over this one.
        cfg = restored
        // Same reason as the reload path: a dialog opened against the pre-restore cfg
        // would write its stale name and tags over what the restore just brought in.
        renameDialog?.dismiss()
        configLoaded = true
        configEpoch++
        saveConfig()
        toast(getString(R.string.toast_restore_ok))
        // Re-render against the adopted config. No reloadConfig: cfg IS the newest truth
        // here, and re-reading the file we just queued a write for could race it.
        refreshApps()
    }

    /** Toasts are this launcher's only chrome — one helper so they stay uniform. */
    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

    /**
     * fsync a *directory* so a preceding rename() is durable, not merely visible.
     * The rename publishes atomically, but the directory entry it rewrites isn't on
     * disk until the directory itself is synced — so a power-loss just after a rename
     * can silently roll the publish back to the previous file. Platform syscalls only
     * (no AndroidX): open the dir read-only, fsync the fd, close it. Best-effort — a
     * failure here only weakens durability, it never corrupts, so it is logged, not
     * thrown, exactly like the rest of the save path.
     */
    private fun fsyncDir(dir: File) {
        var fd: FileDescriptor? = null
        try {
            fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            Os.fsync(fd)
        } catch (e: ErrnoException) {
            Log.w("Sigil", "dir fsync failed (non-fatal): ${dir.path}", e)
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd)
                } catch (e: ErrnoException) {
                    // Nothing actionable at close time; the publish already happened.
                }
            }
        }
    }

    /**
     * "##tag": put the chosen tag on [key] or take it off, persisted immediately like the
     * other edit modes' checkboxes.
     *
     * Deliberately not routed through [toggle]: that one flips membership in a flat
     * collection, while a tag lives in a per-app LIST inside a map, and an app left with
     * no tags must lose its KEY rather than keep an empty list (ConfigJson drops empty
     * lists on both sides, so an empty one would vanish on the next round trip and the
     * in-memory shape would stop matching the file). The epoch/save/notify tail is
     * identical, and identical for the same reasons — see [toggle].
     */
    /**
     * May the posted click still act on [shown]?
     *
     * False only when the list was replaced between the finger going down and this click
     * being DELIVERED — then the row at that position is not the one that was aimed at, and
     * acting on it would toggle, launch, re-tag or rename an app the user never touched.
     *
     * The comparison is made live against the CURRENT [shownGeneration] here, not against a
     * verdict frozen at ACTION_UP: [clickDownGeneration] preserves the down-generation past
     * UP for exactly this reason. That closes the window a UP-time snapshot left open — a
     * replacement landing between ACTION_UP and this posted delivery (a background app-load
     * can, which is also why the branches below re-check via getOrNull). Nothing runs
     * between reading the generation here and reading [shown] in the same callback, so the
     * two stay consistent.
     *
     * Consumes the down-generation (resets it to NO_TOUCH), so it guards exactly the one
     * click that touch leads to and a following accessibility click passes. A NO_TOUCH
     * value passes anyway: accessibility services and hardware keys invoke the click action
     * directly, with no MotionEvent at all, and vetoing those would make the launcher
     * unusable with a screen reader.
     *
     * This reads [clickDownGeneration]; the long-press reads the LIVE [touchDownGeneration]
     * via [longPressStillValid]. The pure arithmetic in [TapGuard.stillValid] is shared,
     * but the FIELD each passes must not be — feeding a long-press the click's field (or
     * vice versa) is the mistake that made the long-press guard a no-op once already.
     */
    private fun clickStillValid(): Boolean {
        val downGeneration = clickDownGeneration
        clickDownGeneration = TapGuard.NO_TOUCH
        return TapGuard.stillValid(downGeneration, shownGeneration)
    }

    /**
     * The same question for a LONG-PRESS, which needs a different answer — and getting that
     * wrong is what made the previous version of this guard a no-op.
     *
     * AbsListView posts CheckForLongPress on ACTION_DOWN and fires it about half a second
     * later WHILE THE FINGER IS STILL DOWN. It therefore arrives strictly BEFORE this
     * gesture's own ACTION_UP — so there is no verdict for it to read, and reading one
     * would only ever return a leftover from some earlier gesture. Instead the comparison
     * is made live: touchDownGeneration still holds THIS gesture's value, because the
     * gesture has not ended yet.
     *
     * NO_TOUCH means no touch is in progress — an accessibility service invoking the
     * long-click action directly — and passes, for the same reason [clickStillValid] lets
     * an unattributed click through.
     */
    private fun longPressStillValid(): Boolean =
        TapGuard.stillValid(touchDownGeneration, shownGeneration)

    private fun toggleTagOn(key: String) {
        val tag = tagEditTag ?: return
        val next = LauncherLogic.toggleTag(cfg.tags[key], tag)
        if (next.isEmpty()) cfg.tags.remove(key) else cfg.tags[key] = next.toMutableList()
        ++configEpoch
        saveConfig()
        adapter.notifyDataSetChanged()
    }

    private fun toggle(coll: MutableCollection<String>, key: String) {
        if (!coll.remove(key)) coll.add(key)  // add appends a favorite at the end
        // Bump ONLY the config epoch, not loadGeneration. A "~" reload replaces cfg
        // wholesale at its UI hand-off; without this a toggle made while that reload
        // is in flight would be read back from the pre-toggle disk copy and clobbered
        // (memory diverging from disk, the next save persisting the loss). Advancing
        // the epoch makes the reload keep the live, toggled cfg instead. It does NOT
        // touch loadGeneration/labelGeneration, so a concurrent onResume enumeration
        // still commits — a toggle no longer discards freshly discovered installs.
        ++configEpoch
        saveConfig()
        adapter.notifyDataSetChanged()        // membership glyphs only; set unchanged
    }

    /**
     * One tap in FAV_REORDER. Nothing held yet -> pick this row up. The held row
     * tapped again -> cancel. Any other row -> drop the held favorite there (see
     * [moveFavorite]). A pick/cancel only flips the » marker, so notifyDataSetChanged
     * is enough; a drop reorders the set, so it re-renders via applyFilter.
     */
    private fun reorderTap(key: String) {
        when (reorderPick) {
            null -> { reorderPick = key; adapter.notifyDataSetChanged() }
            key  -> { reorderPick = null; adapter.notifyDataSetChanged() }
            else -> moveFavorite(reorderPick!!, key)
        }
    }

    /**
     * Move the picked favorite so it takes [targetKey]'s current slot, then persist.
     * cfg.favorites is a LinkedHashSet (insertion order == display rank, see
     * SigilConfig): copy it to a list, splice, and rebuild the set in the new
     * order. Inserting AFTER the target when moving down / AT it when moving up lands
     * the picked row exactly where the target sat, shifting the rows between by one.
     *
     * Bumps configEpoch (not loadGeneration) for the same reason toggle() does — a
     * concurrent "~" reload must keep this live, reordered cfg rather than the
     * pre-reorder disk copy. If either key has since vanished (a toggle/reload dropped
     * a favorite mid-session) the move is abandoned cleanly, only clearing the marker.
     */
    private fun moveFavorite(pickedKey: String, targetKey: String) {
        reorderPick = null
        // A no-op or impossible move (either key vanished mid-session, or same row)
        // returns null: just drop the » marker, don't touch the order.
        val newOrder = LauncherLogic.reorder(cfg.favorites.toList(), pickedKey, targetKey)
        if (newOrder == null) {
            applyFilter(prompt.text?.toString().orEmpty())
            return
        }
        cfg.favorites.clear()
        cfg.favorites.addAll(newOrder)
        ++configEpoch
        saveConfig()
        applyFilter(prompt.text?.toString().orEmpty())  // re-render in the new order
    }

    private fun promptRename(entry: AppEntry) {
        val key = entry.key
        // Dismiss any dialog already up (rapid long-presses) before opening a new one, and
        // keep the reference so onDestroy can tear it down. The view construction lives in
        // RenameDialog; the Activity keeps only the config decisions the commit implies.
        renameDialog?.dismiss()
        renameDialog = RenameDialog.show(
            context = this,
            title = entry.label,
            initialName = cfg.names[key] ?: entry.systemLabel,
            initialTags = cfg.tags[key]?.joinToString(", ").orEmpty(),
            allTags = LauncherLogic.allTags(cfg.tags),
            fgColor = fgColor,
        ) { name, tags ->
            // Empty OR identical to the app's own label = no override: drop any custom name
            // instead of persisting a redundant one, so `names` only ever holds genuine
            // overrides and re-typing the original clears it.
            if (name.isEmpty() || name == entry.systemLabel) cfg.names.remove(key)
            else cfg.names[key] = name
            // Tags arrive already canonical from RenameDialog; drop the key entirely when
            // none remain so `tags` never holds an empty list (matching how names drops a
            // blank override).
            if (tags.isEmpty()) cfg.tags.remove(key) else cfg.tags[key] = tags.toMutableList()
            saveConfig()
            rebuildLabelsFor(key)  // in-memory: relabel + re-sort this component's row
        }
        // Clear the field on dismiss so we never hold a stale, already-gone dialog.
        renameDialog?.setOnDismissListener { renameDialog = null }
    }

    /**
     * Re-bake the display label for the row of one component in-memory, instead of
     * re-enumerating every app (loadLabel() per app) just to rebuild a single
     * label. Runs entirely on the main thread — cfg is read here anyway, and the
     * per-component relabel is cheap string work, not the disk/IPC that loadApps does.
     *
     * A background load could still be in flight holding a pre-rename name snapshot.
     * Bump labelGeneration so its hand-off drops the now-stale-labelled allApps, and
     * configEpoch so a concurrent "~" reload keeps this new name instead of the disk
     * copy it read. (loadGeneration is left alone — a rename is not a new
     * enumeration.) Note this deliberately does NOT pick up installs/uninstalls since
     * the last load; the next onResume re-enumeration does.
     *
     * allApps must stay sorted by labelLower (the drawer and search rely on it), so
     * re-sort after the relabel — a rename can move an app's alphabetical position.
     */
    private fun rebuildLabelsFor(key: String) {
        ++labelGeneration
        ++configEpoch
        allApps = allApps.map { e ->
            if (e.key != key) e
            else {
                val base = cfg.names[key] ?: e.systemLabel
                e.copy(label = base, labelLower = LauncherLogic.foldLabel(base))
            }
        }.sortedBy { it.labelLower }
        applyFilter(prompt.text?.toString().orEmpty())
    }

    // ---- apps ---------------------------------------------------------------

    private fun loadApps(cfg: SigilConfig): List<AppEntry>? {
        val pm = packageManager
        val self = packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // This runs on a background thread, and a HOME app that throws on resume
        // loops forever — so nothing here may let an exception escape. The query
        // and loadLabel() surface plain RuntimeExceptions (e.g. a
        // DeadSystemRuntimeException if system_server restarts): catch broadly and
        // return null (NOT an empty list) so the caller keeps the last-known-good
        // list rather than blanking the home screen on a transient hiccup. A query
        // that SUCCEEDS returns its result even when empty — the rare genuinely
        // app-less device (only this launcher installed) still commits its emptiness.
        val resolved = try {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } catch (e: RuntimeException) {
            Log.w("Sigil", "app enumeration failed", e)
            return null  // failure sentinel: keep the current list, don't wipe it
        }
        return resolved.mapNotNull { ri ->
            try {
                val ai = ri.activityInfo
                if (ai.packageName == self) return@mapNotNull null  // don't list ourselves
                // Identity is the full launcher component, not the package — a
                // package can expose several launcher activities, each its own row.
                val component = ComponentName(ai.packageName, ai.name)
                val key = component.flattenToString()
                // The app's own label, before any override — kept on the entry
                // (systemLabel) so a later rename can rebuild in-memory.
                val systemLabel = ri.loadLabel(pm).toString()
                // A custom name overrides the app's own label; otherwise the label
                // as the system reports it. labelLower keys off the displayed text,
                // so filtering matches what's on screen (a custom name included).
                // LauncherLogic.foldLabel folds with Locale.ROOT — the same fold
                // search's needle uses — so the I/i mapping stays locale-invariant.
                val base = cfg.names[key] ?: systemLabel
                AppEntry(
                    label = base,
                    labelLower = LauncherLogic.foldLabel(base),
                    component = component,
                    systemLabel = systemLabel,
                )
            } catch (e: RuntimeException) {
                Log.w("Sigil", "skipping app", e)
                null
            }
        }.sortedBy { it.labelLower }
    }

    private fun applyFilter(raw: String) {
        val q = raw.trim()
        mode = LauncherLogic.parseMode(q)
        // A pickup belongs to a single reorder session: drop it the moment we're no
        // longer in FAV_REORDER, so nothing stale survives into another mode.
        if (mode != Mode.FAV_REORDER) reorderPick = null
        // Whatever follows "##" IS the tag; blank means the overview is showing and no tag
        // is chosen yet. Canonicalised here once — the SAME rule the dialog and the file
        // loader apply — so "##Work", "##work" and "##  work" are one tag, and nothing
        // downstream has to remember to normalise.
        tagEditTag = if (mode == Mode.TAG_EDIT) {
            LauncherLogic.canonicalTag(q.substring(2)).takeIf { it.isNotEmpty() }
        } else {
            null
        }
        // The entire "given the prompt, what rows does the list show?" decision now lives
        // as one pure, exhaustively-tested function in LauncherLogic (see rowsFor and
        // LauncherRowsTest). The Activity keeps only the SIDE EFFECTS around it: the
        // reorderPick reset and tagEditTag above, the shownGeneration bump and the empty-
        // recents toast below. tagEditTag is passed in rather than recomputed so the value
        // the adapter reads and the value rowsFor uses can never drift.
        shown = LauncherLogic.rowsFor(
            mode = mode,
            trimmed = q,
            allApps = allApps,
            hidden = cfg.hidden,
            favorites = cfg.favorites,
            tags = cfg.tags,
            recentKeys = recentKeys,
            tagEditTag = tagEditTag,
        )
        // Any replacement of shown invalidates a tap already in flight (see the click
        // listener). Bumped here, in the ONE place shown is assigned.
        shownGeneration++
        // An empty "?" means nothing has been launched since this process started —
        // i.e. we just cold-started (or it's a fresh install). The recents cache is
        // in memory only and low-RAM devices kill the launcher process often, so warn
        // once per process that the list resets on restart, lest an empty "?" look
        // like the app forgot the user's recents. Guarded so the per-keystroke
        // TextWatcher can't repeat it.
        // shown, not recentKeys: a recent whose app has since been uninstalled is filtered
        // out of the rendered list but still sits in recentKeys, so testing the cache would
        // leave the user staring at an unexplained empty "?" — the one case the hint exists
        // for.
        if (mode == Mode.RECENTS && shown.isEmpty() && !recentsEmptyHintShown) {
            recentsEmptyHintShown = true
            Toast.makeText(this, getString(R.string.toast_recents_empty), Toast.LENGTH_LONG).show()
        }
        adapter.notifyDataSetChanged()
    }

    private fun launch(entry: AppEntry) {
        // A launcher starts the app in its own task, not nested in this one.
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = entry.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            // Only a launch that actually started counts as "recent" — the catch
            // branches below leave the list untouched so a dead component never
            // lingers at the top of "?".
            recentKeys = LauncherLogic.pushRecent(recentKeys, entry.key, RECENTS_LIMIT)
        } catch (e: ActivityNotFoundException) {
            // Component gone since the list loaded (uninstall race).
            Log.w("Sigil", "launch unavailable: ${entry.component}")
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w("Sigil", "launch denied: ${entry.component}", e)
            Toast.makeText(this, getString(R.string.toast_denied, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            // Dead system_server or similar — don't crash HOME.
            Log.w("Sigil", "launch failed: ${entry.component}", e)
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showKeyboard() {
        // requestFocus makes the prompt the input target; the window insets controller is
        // the modern IME show that cooperates with the app-owned insets above. The legacy
        // InputMethodManager.showSoftInput wants the view already laid out and the window
        // focused, so it is unreliable straight from onResume — the controller is not.
        prompt.requestFocus()
        prompt.windowInsetsController?.show(WindowInsets.Type.ime())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Tag rows and app rows are the same monospace TextView, so a recycled view
            // is reused across both freely.
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(fgColor)
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            when (val row = shown[position]) {
                // Tag overview (bare "#"): plain tag name; a screen reader reads it fine,
                // so clear any stale edit-mode description from the recycled view.
                is TagRow -> {
                    tv.text = row.name
                    tv.contentDescription = null
                }
                // "~" overview: the command's own spelling is the label, and it reads
                // aloud as-is, so clear any stale edit-mode description off the recycled view.
                is CommandRow -> {
                    tv.text = row.name
                    tv.contentDescription = null
                }
                is AppRow -> bindAppRow(tv, row.entry)
            }
            return tv
        }

        private fun bindAppRow(tv: TextView, entry: AppEntry) {
            val key = entry.key
            // The edit-mode marker glyph is chosen by the pure LauncherLogic.rowPrefix
            // (unit-tested in LauncherRowsTest); the read modes return "" so the label
            // stands alone. "[ ] "/"[x] " and "» "/"  " are equal-width, so labels stay
            // column-aligned.
            tv.text = LauncherLogic.rowPrefix(
                mode,
                isHidden = key in cfg.hidden,
                isFavorite = key in cfg.favorites,
                isPicked = key == reorderPick,
                isTagged = tagEditTag in cfg.tags[key].orEmpty(),
            ) + entry.label
            // Accessibility: the "[x]"/"[ ]" glyph reads as literal punctuation to a
            // screen reader, so in the edit modes give the row a spoken description of
            // its state and what a tap does. NORMAL rows read their label fine, so the
            // description is cleared (null falls back to the text) — also stops a stale
            // edit-mode description clinging to a recycled convertView.
            tv.contentDescription = when (mode) {
                Mode.HIDDEN_EDIT -> getString(
                    if (key in cfg.hidden) R.string.a11y_hidden_on else R.string.a11y_hidden_off,
                    entry.label,
                )
                Mode.FAV_EDIT -> getString(
                    if (key in cfg.favorites) R.string.a11y_fav_on else R.string.a11y_fav_off,
                    entry.label,
                )
                Mode.FAV_REORDER -> getString(
                    when {
                        reorderPick == null -> R.string.a11y_reorder_pick   // nothing held
                        key == reorderPick  -> R.string.a11y_reorder_picked // this row held
                        else                -> R.string.a11y_reorder_drop   // a drop target
                    },
                    entry.label,
                )
                Mode.TAG_EDIT -> getString(
                    if (tagEditTag in cfg.tags[key].orEmpty()) R.string.a11y_tag_on
                    else R.string.a11y_tag_off,
                    entry.label,
                    tagEditTag.orEmpty(),
                )
                Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND -> null
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RECENTS_LIMIT = 8  // how many apps "?" remembers, in memory only
        /** Sub-folder of Downloads that "~backup" writes into. */
        const val BACKUP_DIR = "Sigil"
        // Fixed names, not timestamped ones: there is exactly ONE backup and ONE undo
        // point, each overwritten in place. That is what lets "~restore" skip a file
        // picker — it already knows the name of the only file it could mean.
        const val BACKUP_NAME = "sigil.json"
        const val PRE_RESTORE_NAME = "sigil-pre-restore.json"
        const val REQ_RESTORE = 1
    }
}
