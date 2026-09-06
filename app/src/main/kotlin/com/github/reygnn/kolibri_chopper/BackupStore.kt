package com.github.reygnn.kolibri_chopper

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import java.io.FileOutputStream
import java.io.IOException

/**
 * The shared-storage backup layer: publishing the config into Download/[subDir] and reading
 * it back. [ConfigStore] owns chopper.json in filesDir; this owns the off-device copy that
 * "~backup"/"~restore" use and that survives an uninstall.
 *
 * Lifted out of MainActivity to mirror the ConfigStore split — the Activity keeps the parts
 * that are inherently Activity-bound (the "~restore-saf" ACTION_OPEN_DOCUMENT dance in
 * pickRestoreFile/onActivityResult, and adoptRestored, which replaces the live cfg). Unlike
 * ConfigStore this earns little unit-test coverage: MediaStore/ContentResolver can't run on
 * a plain JVM, so this is a readability/cohesion move, not a testability one.
 *
 * Everything Android-specific is injected so the class names no framework singleton:
 *   [resolver] the app's ContentResolver
 *   [subDir]   the sub-folder of Downloads to write into (e.g. "KolibriChopper")
 *   [log]      android.util.Log in the app, a collector in a test — this class never
 *              throws, so the log IS its error channel.
 */
internal class BackupStore(
    private val resolver: ContentResolver,
    private val subDir: String,
    private val log: (String, Exception?) -> Unit,
) {

    // MediaStore stores RELATIVE_PATH WITH a trailing slash; the query below must match it
    // exactly or every backup would insert a fresh copy instead of replacing.
    private val relativePath get() = "${Environment.DIRECTORY_DOWNLOADS}/$subDir"

    /**
     * Publish [payload] into Download/[subDir] as [name] via MediaStore. Needs no storage
     * permission: writing into the Downloads collection is always allowed, and an app may
     * always rewrite what it wrote itself.
     *
     * Temp-write then publish, the same shape ConfigStore uses for chopper.json and for the
     * same reason. Opening the live backup with "wt" truncates it AT OPEN, so a write that
     * then failed — ENOSPC, or the OS reaping a backgrounded HOME app mid-write — used to
     * leave a torso where the backup had been. Unlike chopper.json in filesDir this file has
     * no .bak beside it: it is the only off-device copy, so it must never be destroyed before
     * its replacement is complete on disk.
     *
     * Sequence: write the payload into a PENDING temp row and fsync it -> delete the old row
     * -> rename the temp onto its name. A crash in the publish window leaves the COMPLETE new
     * payload under "<name>.tmp" rather than a truncated backup, and the next run clears that
     * temp away. IS_PENDING keeps the half-written temp invisible to file managers, and is
     * cleared as part of the same update that renames it — so a row can no longer be stranded
     * pending, invisible to the user and on the clock for MediaStore's pending-expiry sweep.
     *
     * Never throws — a failed backup is reported and forgotten, it must not take a HOME app
     * down with it. Returns whether the file was published.
     */
    fun write(name: String, payload: String): Boolean {
        val tmpName = "$name.tmp"
        var tmp: Uri? = null
        return try {
            // A temp left behind by a previous crash would otherwise collide, and MediaStore
            // resolves a taken DISPLAY_NAME by inventing "…(1)" rather than failing — which
            // is how you end up with a folder full of near-duplicates.
            findOwn(tmpName)?.let { resolver.delete(it, null, null) }
            tmp = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, tmpName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: throw IOException("MediaStore refused an entry for $tmpName")
            // fsync the CONTENTS before publishing, exactly as ConfigStore does: the rename
            // must not be able to reach disk ahead of the bytes it publishes.
            resolver.openFileDescriptor(tmp, "w")?.use { pfd ->
                val out = FileOutputStream(pfd.fileDescriptor)
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                Os.fsync(pfd.fileDescriptor)
            } ?: throw IOException("no descriptor for $tmp")
            // Publish. Deleting the old row first because a rename onto a taken name would
            // again produce "…(1)" instead of replacing it. This also sweeps away a row
            // stranded pending by an older crash, since a query returns our own pending rows.
            findOwn(name)?.let { resolver.delete(it, null, null) }
            resolver.update(
                tmp,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            true
        } catch (e: Exception) {
            log("backup to Downloads failed", e)
            // Drop the temp rather than leaving an invisible stub in the user's Downloads.
            // The previous backup is untouched at every point this can be reached.
            tmp?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    /**
     * The MediaStore row for OUR [name] in Download/[subDir], or null if we never wrote it —
     * or no longer own it: an uninstall orphans the row, and the reinstalled app can then
     * neither see nor overwrite it.
     *
     * Without a storage permission a query only ever returns the app's own rows, which is
     * exactly the scope wanted here.
     */
    fun findOwn(name: String): Uri? = try {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("$relativePath/", name),
            )
            // MATCH_INCLUDE, because MediaStore hides IS_PENDING rows from a plain query even
            // from the app that wrote them. Without this both the cleanup in write() and the
            // recovery in restore are dead code: a temp row stranded by a crash would be
            // invisible, so it could neither be swept away nor read back, and it would sit
            // there — a complete, unreachable backup — until MediaStore's own pending-expiry
            // sweep deleted it for good.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            query,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        log("looking up $name in Downloads failed", e)
        null
    }

    /** Read a document's whole text, or null if it cannot be read. Shared by both restore
     *  paths (the fixed-name one and the SAF picker's uri); never throws. */
    fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        log("restore: cannot read $uri", e)
        null
    }
}
