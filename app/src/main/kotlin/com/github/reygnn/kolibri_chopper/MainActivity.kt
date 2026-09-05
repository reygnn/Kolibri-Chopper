package com.github.reygnn.kolibri_chopper

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
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
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The entire Kolibri Chopper: a text-only, terminal-styled launcher in one
 * file. Enumerates the current user's launchable apps via a
 * [android.content.pm.PackageManager] MAIN/LAUNCHER query, lists them in
 * monospace light gray, filters as you type, launches on tap/Enter.
 *
 * Config lives in the app's own internal storage (chopper.json in filesDir) — no
 * storage permission on any Android version, and the app owns it end to end, so
 * it is edited entirely through the in-app modes below, never a text editor.
 * Prompt grammar (leading sigil = mode):
 *   ""      normal: favorites only (or the drawer if none are set yet)
 *   text    normal: substring search across ALL apps (hidden included)
 *   *       normal: the full app drawer (every non-hidden app)
 *   -[text] edit hidden:    tap a row to toggle its [x], persisted immediately
 *   ![text] edit favorites: tap a row to toggle its [x], persisted immediately
 *   !!      reorder favorites: tap a row to pick it up (marked »), tap another
 *           row to drop it there; tap the picked row again to cancel
 *   ?       recents: the last-launched apps (in memory only, empty after restart)
 *   ~       + Enter: reload chopper.json from disk (config is cached otherwise)
 * Long-press any row to set a custom name.
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

    // NB: not named `foreground` — that collides with View.foreground (a
    // Drawable) inside the apply{} blocks below and hides this Int.
    private val fgColor = 0xFFD4D4D4.toInt()     // pleasant light gray
    private val fgColorDim = 0xFF808080.toInt()  // dimmer gray, for the hint

    private var cfg = ChopperConfig()
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

    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()

    // The "?" mode: the component keys of the most recently launched apps, newest
    // first. Deliberately IN MEMORY ONLY — never written to chopper.json — so it
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

        // We drive edge-to-edge and the IME slide entirely ourselves (the inset
        // listener + WindowInsetsAnimation.Callback below). Telling the framework we
        // fit no system windows stops it from ALSO resizing/panning the window when
        // the keyboard appears — without this the system's own adjustResize fires a
        // hard relayout of the ListView (the "list blinks and redraws" flash) that
        // fights our smooth padding animation. adjustResize in the manifest is the
        // matching softInputMode so the IME inset is delivered as an animation.
        window.setDecorFitsSystemWindows(false)

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
            setOnItemClickListener { _, _, position, _ ->
                // getOrNull, not [position]: a background load can complete on the
                // main thread between the frame the user tapped and this click
                // message running, shrinking shownApps — a stale position would then
                // throw. A HOME app must never crash, so drop the tap instead.
                val entry = shownApps.getOrNull(position) ?: return@setOnItemClickListener
                when (mode) {
                    Mode.NORMAL, Mode.RECENTS -> launch(entry)
                    Mode.HIDDEN_EDIT -> toggle(cfg.hidden, entry.key)
                    Mode.FAV_EDIT    -> toggle(cfg.favorites, entry.key)
                    Mode.FAV_REORDER -> reorderTap(entry.key)
                }
            }
            // Long-press: set/clear a custom name for the app (any mode).
            setOnItemLongClickListener { _, _, position, _ ->
                val entry = shownApps.getOrNull(position)
                    ?: return@setOnItemLongClickListener false  // stale position: not consumed
                promptRename(entry)
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

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
                override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
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
                when {
                    // "~": WM-style reload — re-read chopper.json from disk. It's a
                    // one-shot command, not a view, so it lives on Enter, not on a
                    // live sigil. Clearing first ("" != "~") avoids re-triggering.
                    prompt.text?.toString()?.trim() == "~" -> {
                        prompt.setText("")
                        refreshApps(reloadConfig = true)
                    }
                    // In an edit mode Enter is a "done" gesture: clear the prompt
                    // back to normal instead of launching whatever sits at the top.
                    // lastOrNull, not firstOrNull: with isStackFromBottom the list
                    // fills upward from the command line, so the LAST row is the one
                    // sitting directly above the prompt — the natural Enter target.
                    // (Swap to firstOrNull if you'd rather Enter pick the
                    // alphabetically-first match instead.)
                    // NORMAL and RECENTS both launch the row nearest the command line;
                    // for "?" that's the most recently used app, so "?" + Enter is a
                    // one-key relaunch of the last app.
                    mode == Mode.NORMAL || mode == Mode.RECENTS ->
                        shownApps.lastOrNull()?.let { launch(it) }
                    else -> prompt.setText("")
                }
                true
            }
        }
        root.addView(prompt, LinearLayout.LayoutParams(MATCH, WRAP))

        // Edge-to-edge is mandatory on Android 16 — pad for the system bars and
        // lift the content above the IME ourselves (platform insets, no AndroidX).
        val pad = 12.dp()
        // Only the system bars pad the root, and ONLY they size the layout. The IME
        // is applied as a translationY lift, never as padding: padding would shrink
        // the weight-1 ListView, and a ListView re-runs a full layoutChildren() on
        // every size change — across the many frames of the keyboard slide that reads
        // as the favorites list blinking out and redrawing. Translating the whole
        // root up instead keeps the ListView at a constant size (no relayout) while
        // the prompt still rides up to sit just above the keyboard. Shared by the
        // static apply pass and the IME animation callback so the two never disagree.
        val applyInsets = { v: View, insets: WindowInsets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            // The IME inset is measured from the screen bottom and already spans the
            // nav-bar region we've padded for, so subtract it to avoid lifting twice.
            v.translationY = -maxOf(0, imeBottom - bars.bottom).toFloat()
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            applyInsets(v, insets)
            insets
        }
        // Without an animation callback the IME inset only lands in the pass above
        // once the keyboard has fully settled, so the content jumps to its raised
        // spot in a single frame. Driving the same lift from onProgress makes it
        // track the keyboard as it slides. DISPATCH_MODE_STOP holds the static pass
        // above back until the animation ends (so the two never fight mid-slide),
        // then it runs once with the final insets. Platform API (min 30); minSdk 36.
        root.setWindowInsetsAnimationCallback(
            object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP
            ) {
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>,
                ): WindowInsets {
                    applyInsets(root, insets)
                    return insets
                }
            }
        )

        adapter = AppListAdapter()
        listView.adapter = adapter

        setContentView(root)

        // Back on a HOME launcher must not finish the activity — we're the home
        // screen, so the platform's default callback (routed here on targetSdk 36
        // whether or not we opt in) would just finish us and bounce straight back
        // via HOME. Intercept it: a non-empty prompt (a search or an edit mode)
        // clears back to favorites; an empty prompt is a deliberate no-op. Lives
        // for the whole activity — there's no "done" state to unregister at.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            if (prompt.text?.isNotEmpty() == true) {
                prompt.setText("")   // TextWatcher -> applyFilter resets mode to NORMAL
                hideKeyboard()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
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
        val cached = if (loadConfigNow) ChopperConfig() else cfg.snapshot()
        submitIo {
            // Already superseded by a newer enumeration while queued: skip the work.
            if (generation != loadGeneration) return@submitIo
            val useCfg = if (loadConfigNow) loadConfig() else cached
            val loaded = loadApps(useCfg)
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread  // superseded
                if (loadConfigNow) {
                    // Only adopt the disk copy if no toggle/rename slipped in since
                    // the read; otherwise the live cfg is newer — keep it. (On cold
                    // start no mutation is possible yet, so this always adopts.)
                    if (configEpoch == cfgEpoch) {
                        cfg = useCfg
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
    private fun submitIo(task: Runnable) {
        if (ioExecutor.isShutdown) return
        try {
            ioExecutor.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w("Chopper", "IO task rejected (executor shutting down)", e)
        }
    }

    /** HOME pressed while already home: reset to a clean prompt. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // keep getIntent() in sync with the latest launch intent
        // setText("") fires the TextWatcher synchronously, so applyFilter has
        // already refreshed shownApps (and reset mode to NORMAL) by the time we
        // scroll to the last (bottom-most, nearest the prompt) row below.
        prompt.setText("")
        // Last valid index is size - 1; guard the empty case (no apps loaded yet or
        // everything hidden), where size - 1 would be an invalid -1.
        if (shownApps.isNotEmpty()) listView.setSelection(shownApps.size - 1)
        hideKeyboard()
    }

    // ---- config -------------------------------------------------------------

    /**
     * Read the config, preferring the primary chopper.json and falling back to the
     * .bak mirror written by [saveConfig] if the primary is missing or unreadable.
     * A missing file (fresh install) yields an empty config silently; a present but
     * unparseable primary is logged and .bak is tried before giving up to empty. A
     * HOME app must never throw on resume, so every error path degrades to a working
     * (if ruleless) launcher rather than crashing — and a single torn read of the
     * primary no longer discards the user's favorites/hidden/names.
     */
    private fun loadConfig(): ChopperConfig {
        parseConfig(File(filesDir, CONFIG_FILE))?.let { return it }
        parseConfig(File(filesDir, "$CONFIG_FILE.bak"))?.let { recovered ->
            Log.w("Chopper", "primary config unreadable — recovered from .bak")
            // Heal the primary now instead of waiting for the next toggle: rewrite the
            // recovered config over the bad primary so later resumes stop hitting this
            // path and we're never left running on a single copy. loadConfig() is only
            // ever called inside refreshApps()'s submitIo block, so we're already on
            // ioExecutor and this write is serialized with every save. rotateBackup =
            // false: .bak IS the good copy — rotating the torn primary into it would
            // destroy the very backup we just read from.
            writeConfigFile(ConfigJson.serialize(recovered), rotateBackup = false)
            return recovered
        }
        return ChopperConfig()
    }

    /**
     * Parse one config file. Returns null when the file is absent (a normal state,
     * not logged) or unparseable (logged, so real corruption is visible) — the caller
     * decides what to fall back to. Never throws.
     */
    private fun parseConfig(file: File): ChopperConfig? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            Log.w("Chopper", "config unreadable: ${file.path}", e)
            return null
        }
        // ConfigJson owns the JSON parsing (and is unit-tested); it returns null,
        // never throws, on malformed input — log here where the file path is known.
        return ConfigJson.parse(text)
            ?: run { Log.w("Chopper", "config unparseable: ${file.path}"); null }
    }

    /**
     * Persist the current config to disk. Serializes the live [cfg] on the calling
     * (main) thread — cfg is only ever mutated there, so the read is race-free and
     * the result is an immutable snapshot — then hands the write to [ioExecutor] so a
     * toggle/rename tap never blocks on disk. rotateBackup = true: a normal save
     * keeps a last-known-good .bak mirror (see [writeConfigFile]).
     */
    private fun saveConfig() {
        val payload = ConfigJson.serialize(cfg)
        submitIo { writeConfigFile(payload, rotateBackup = true) }
    }

    /**
     * Atomically publish [payload] as chopper.json, writing the WHOLE file each time.
     * MUST run on [ioExecutor] (the sole disk-writing thread) so concurrent writes
     * stay serialized on the temp file. Never throws — a failure degrades durability,
     * not correctness, and is logged: a HOME app must not crash on a bad save.
     *
     * Sequence: temp-write + fsync-contents -> rotate -> publish + fsync-dir.
     *   - fsync of the temp CONTENTS closes the window where the rename's metadata
     *     could reach disk ahead of the bytes (which would publish a truncated file).
     *   - the final fsync of the DIRECTORY makes the rename itself durable: rename is
     *     atomic for visibility but not durability, so without it a power-cut can roll
     *     the publish back to the previous file — a lost most-recent toggle, never
     *     corruption.
     *
     * [rotateBackup] moves the current primary into .bak (by rename, never an unsynced
     * in-place copy that could tear it) before publishing — but only after re-parsing
     * it, so a primary that silently went bad is never promoted over the good backup. A
     * normal save wants this. A HEAL after recovering from .bak must NOT rotate at all:
     * there the on-disk primary is the torn file we're replacing and .bak holds the
     * ONLY good copy — rotating would overwrite that good .bak with garbage. The heal
     * just replaces the bad primary and leaves .bak untouched, so both end up holding
     * the config.
     */
    private fun writeConfigFile(payload: String, rotateBackup: Boolean) {
        val tmp = File(filesDir, "$CONFIG_FILE.tmp")
        val dst = File(filesDir, CONFIG_FILE)
        val bak = File(filesDir, "$CONFIG_FILE.bak")
        try {
            // (1) Write to the temp file and force its bytes onto disk BEFORE anything
            //     is published, so a rename can never expose contents that aren't there.
            FileOutputStream(tmp).use { fos ->
                fos.write(payload.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }

            // (2) Rotate the current good primary into .bak by RENAME (atomic, can't
            //     tear) — unless we're healing, where the on-disk primary is bad and
            //     .bak is the good copy we must not clobber. We do NOT delete .bak
            //     first: renaming onto it replaces it atomically on POSIX, and leaving
            //     it keeps a good copy present at all times. On the first save dst
            //     doesn't exist yet, so .bak appears from save #2 onward.
            //
            //     But rename promotes the primary UNVALIDATED, so first re-parse it and
            //     skip the rotation if it no longer parses. Since a cold start serves
            //     cfg from memory, a primary that silently went bad afterwards (bit rot,
            //     or a torn in-place fallback at step 3 last time) would otherwise be
            //     rotated straight onto .bak — destroying the last known-good backup.
            //     This read is the only place that re-checks the on-disk primary; the
            //     preserved .bak refreshes on the next save once the primary is good
            //     again. One read+parse of a tiny file per save is a cheap insurance.
            if (rotateBackup && dst.exists()) {
                when {
                    parseConfig(dst) == null ->
                        Log.w("Chopper", "config primary invalid — keeping .bak, skipping rotate")
                    !dst.renameTo(bak) ->
                        Log.w("Chopper", "config .bak rotate failed (non-fatal)")
                }
            }

            // (3) Publish the new primary. After a rotation dst is gone, so this
            //     renames onto a FREE name — accepted by every filesystem, and it
            //     sidesteps the old "refuse rename onto existing target" problem. The
            //     in-place fallback only fires on an exotic FS that still refused; it
            //     fsyncs (unlike the old copyTo), and .bak still holds a good config,
            //     so even a torn in-place dst stays recoverable.
            if (!tmp.renameTo(dst)) {
                FileOutputStream(dst).use { fos ->
                    fos.write(payload.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                tmp.delete()
            }

            // (4) Make the renames themselves durable (see the method comment above).
            fsyncDir(filesDir)
        } catch (e: Exception) {
            Log.w("Chopper", "config save failed", e)
        }
    }

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
            Log.w("Chopper", "dir fsync failed (non-fatal): ${dir.path}", e)
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
     * ChopperConfig): copy it to a list, splice, and rebuild the set in the new
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
        val input = EditText(this).apply {
            setText(cfg.names[key] ?: entry.systemLabel)
            isSingleLine = true
            // Match the command prompt: no autocorrect/autocapitalize, so a
            // deliberate custom name isn't silently "corrected" on the way in.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelection(text.length)
        }
        // Dismiss any dialog already up (rapid long-presses) before opening a new
        // one, and keep the reference so onDestroy can tear it down. Clear the field
        // on dismiss so we never hold a stale, already-gone dialog.
        renameDialog?.dismiss()
        // Dark dialog theme so the rename popup stays in the black terminal look
        // instead of flashing the platform's default light Material dialog.
        renameDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(entry.label)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                // Empty OR identical to the app's own label = no override: drop any
                // custom name instead of persisting a redundant one, so `names` only
                // ever holds genuine overrides and re-typing the original clears it.
                if (name.isEmpty() || name == entry.systemLabel) cfg.names.remove(key)
                else cfg.names[key] = name
                saveConfig()
                rebuildLabelsFor(key)  // in-memory: only this component's row changes
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { renameDialog = null }
            .show()
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

    private fun loadApps(cfg: ChopperConfig): List<AppEntry>? {
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
            Log.w("Chopper", "app enumeration failed", e)
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
                Log.w("Chopper", "skipping app", e)
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
        shownApps = when (mode) {
            // Reorder lists exactly the current favorites, in their stored order —
            // any text after "!!" is ignored (filtering would scramble the positions
            // the reorder acts on). Nothing to show when none are set.
            Mode.FAV_REORDER -> LauncherLogic.favoritesInDisplayOrder(allApps, cfg.favorites)
            // Recents lists the last-launched apps (newest nearest the prompt). Like
            // reorder, any text after "?" is ignored — the list is short and fixed.
            Mode.RECENTS -> LauncherLogic.recentsInDisplayOrder(allApps, recentKeys)
            // Edit modes list EVERY app (so anything can be toggled), narrowed by
            // whatever follows the sigil. Membership shows as [x]/[ ] in getView.
            Mode.HIDDEN_EDIT, Mode.FAV_EDIT -> LauncherLogic.search(allApps, q.substring(1).trim())
            Mode.NORMAL -> when {
                q.isEmpty() -> favoritesView()
                // "*": the app drawer — everything except hidden, but a favorite is
                // always kept (favoriting overrides hiding), see LauncherLogic.drawer.
                q == "*" -> LauncherLogic.orderWithFavorites(
                    LauncherLogic.drawer(allApps, cfg.hidden, cfg.favorites), cfg.favorites
                )
                // Plain search spans ALL apps, so a hidden app is still reachable by
                // typing its (possibly custom) name — hidden only trims the default
                // views, it doesn't make an app unlaunchable.
                else -> LauncherLogic.search(allApps, q)
            }
        }
        // An empty "?" means nothing has been launched since this process started —
        // i.e. we just cold-started (or it's a fresh install). The recents cache is
        // in memory only and low-RAM devices kill the launcher process often, so warn
        // once per process that the list resets on restart, lest an empty "?" look
        // like the app forgot the user's recents. Guarded so the per-keystroke
        // TextWatcher can't repeat it.
        if (mode == Mode.RECENTS && recentKeys.isEmpty() && !recentsEmptyHintShown) {
            recentsEmptyHintShown = true
            Toast.makeText(this, getString(R.string.toast_recents_empty), Toast.LENGTH_LONG).show()
        }
        adapter.notifyDataSetChanged()
    }

    /** Empty prompt: just the favorites (in config order). Favorites are shown
     *  even if also hidden — favoriting overrides hiding, so a starred app is never
     *  trimmed from the two default views (this one and the "*" drawer); hiding it
     *  only affects it once the favorite is removed. Falls back to the full drawer
     *  when none are configured OR none of the configured ones are currently
     *  launchable, so a fresh install — or one where every favorite has since been
     *  uninstalled — never leaves a blank home screen with no way back to the apps. */
    private fun favoritesView(): List<AppEntry> =
        LauncherLogic.favoritesInDisplayOrder(allApps, cfg.favorites).ifEmpty {
            LauncherLogic.orderWithFavorites(
                LauncherLogic.drawer(allApps, cfg.hidden, cfg.favorites), cfg.favorites
            )
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
            Log.w("Chopper", "launch unavailable: ${entry.component}")
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w("Chopper", "launch denied: ${entry.component}", e)
            Toast.makeText(this, getString(R.string.toast_denied, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            // Dead system_server or similar — don't crash HOME.
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
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            val entry = shownApps[position]
            val key = entry.key
            // In an edit mode each row carries a monospace checkbox glyph; "[ ] "
            // and "[x] " are the same width, so labels stay column-aligned.
            tv.text = when (mode) {
                Mode.HIDDEN_EDIT -> (if (key in cfg.hidden) "[x] " else "[ ] ") + entry.label
                Mode.FAV_EDIT    -> (if (key in cfg.favorites) "[x] " else "[ ] ") + entry.label
                // "» " marks the picked-up row; "  " keeps the others column-aligned
                // (same two-cell width in the monospace face).
                Mode.FAV_REORDER -> (if (key == reorderPick) "» " else "  ") + entry.label
                Mode.NORMAL, Mode.RECENTS -> entry.label
            }
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
                Mode.NORMAL, Mode.RECENTS -> null
            }
            return tv
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val CONFIG_FILE = "chopper.json"
        const val RECENTS_LIMIT = 8  // how many apps "?" remembers, in memory only
    }
}
