package com.github.reygnn.sigil_launcher

import java.io.File
import java.io.FileOutputStream

/**
 * The sigil.json file layer: reading the config with its .bak fallback, and publishing
 * it back atomically with a validated .bak rotation. [ConfigJson] owns the JSON; this owns
 * the FILES.
 *
 * Lifted out of MainActivity so the rotation state machine can be unit-tested on the JVM.
 * That machine — re-parse the on-disk primary, skip the rotation when it no longer parses,
 * heal-without-rotating versus normal-save-with-rotating, rename-or-fall-back-to-in-place —
 * is the highest-consequence logic in the app: getting it wrong silently destroys the last
 * known-good backup, which has happened here once already. It was also the only part with
 * no test, precisely because it sat behind an Activity.
 *
 * Everything it needs from Android is injected, so a test can drive it against a temp
 * directory:
 *   [dir]     where sigil.json, its .bak and its .tmp live (filesDir in the app)
 *   [log]     android.util.Log in the app, a collector in tests — this class never
 *             throws, so the log IS its error channel and a test can assert on it
 *   [syncDir] fsync of the directory, the one genuinely Android-only call
 *             (android.system.Os). File CONTENTS are synced with plain
 *             java.io.FileDescriptor.sync(), which works on any JVM and is therefore
 *             exercised for real by the tests.
 *   [rename]  the atomic rename primitive, defaulting to [File.renameTo]. A seam only
 *             so a test can force it to fail on a chosen file: the two "rename refused"
 *             paths below (the .bak rotation and the temp->primary publish with its
 *             in-place fallback) never fire on a normal filesystem, so a real temp dir
 *             cannot reach them. Production is unchanged — it uses [File.renameTo].
 */
internal class ConfigStore(
    private val dir: File,
    private val log: (String, Exception?) -> Unit,
    private val syncDir: (File) -> Unit,
    private val rename: (File, File) -> Boolean = File::renameTo,
) {

    private val primary get() = File(dir, CONFIG_FILE)
    private val backup get() = File(dir, "$CONFIG_FILE.bak")
    private val temp get() = File(dir, "$CONFIG_FILE.tmp")

    /**
     * Read the config, preferring the primary sigil.json and falling back to the .bak
     * mirror written by [write] if the primary is missing or unreadable. A missing file
     * (fresh install) yields an empty config silently; a present but unparseable primary
     * is logged and .bak is tried before giving up to empty. A HOME app must never throw
     * on resume, so every error path degrades to a working (if ruleless) launcher rather
     * than crashing — and a single torn read of the primary no longer discards the user's
     * favorites/hidden/names/tags.
     */
    fun load(): SigilConfig {
        parse(primary)?.let { return it }
        parse(backup)?.let { recovered ->
            log("primary config unreadable — recovered from .bak", null)
            // Heal the primary now instead of waiting for the next toggle: rewrite the
            // recovered config over the bad primary so later resumes stop hitting this
            // path and we're never left running on a single copy. rotateBackup = false:
            // .bak IS the good copy — rotating the torn primary into it would destroy the
            // very backup we just read from.
            write(ConfigJson.serialize(recovered), rotateBackup = false)
            return recovered
        }
        return SigilConfig()
    }

    /**
     * Parse one config file. Returns null when the file is absent (a normal state, not
     * logged) or unparseable (logged, so real corruption is visible) — the caller decides
     * what to fall back to. Never throws.
     */
    private fun parse(file: File): SigilConfig? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            log("config unreadable: ${file.path}", e)
            return null
        }
        // ConfigJson owns the JSON parsing (and is unit-tested); it returns null, never
        // throws, on malformed input — log here where the file path is known.
        return ConfigJson.parse(text)
            ?: run { log("config unparseable: ${file.path}", null); null }
    }

    /**
     * Atomically publish [payload] as sigil.json, writing the WHOLE file each time.
     * MUST be called from the caller's single disk-writing thread so concurrent writes
     * stay serialized on the temp file. Never throws — a failure degrades durability, not
     * correctness, and is logged: a HOME app must not crash on a bad save. Returns whether
     * the publish succeeded.
     *
     * Sequence: temp-write + fsync-contents -> rotate -> publish + fsync-dir.
     *   - fsync of the temp CONTENTS closes the window where the rename's metadata could
     *     reach disk ahead of the bytes (which would publish a truncated file).
     *   - the final fsync of the DIRECTORY makes the rename itself durable: rename is
     *     atomic for visibility but not durability, so without it a power-cut can roll the
     *     publish back to the previous file — a lost most-recent toggle, never corruption.
     *
     * [rotateBackup] moves the current primary into .bak (by rename, never an unsynced
     * copy) before the new one is published, so a good copy is on disk at all times; a
     * normal save wants this. A HEAL after recovering from .bak must NOT rotate at all:
     * there the on-disk primary is the torn file we're replacing and .bak holds the ONLY
     * good copy — rotating would overwrite that good .bak with garbage.
     */
    fun write(payload: String, rotateBackup: Boolean): Boolean {
        val tmp = temp
        val dst = primary
        val bak = backup
        return try {
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
                    parse(dst) == null ->
                        log("config primary invalid — keeping .bak, skipping rotate", null)
                    !rename(dst, bak) ->
                        log("config .bak rotate failed (non-fatal)", null)
                }
            }

            // (3) Publish the new primary. After a rotation dst is gone, so this
            //     renames onto a FREE name — accepted by every filesystem, and it
            //     sidesteps the old "refuse rename onto existing target" problem. The
            //     in-place fallback only fires on an exotic FS that still refused; it
            //     fsyncs (unlike the old copyTo), and .bak still holds a good config,
            //     so even a torn in-place dst stays recoverable.
            if (!rename(tmp, dst)) {
                FileOutputStream(dst).use { fos ->
                    fos.write(payload.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
                tmp.delete()
            }

            // (4) Make the renames themselves durable (see the doc above).
            syncDir(dir)
            true
        } catch (e: Exception) {
            log("config save failed", e)
            false
        }
    }

    companion object {
        const val CONFIG_FILE = "sigil.json"
    }
}
