package com.timerpg.street

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the game from inside it. [check] reads update.json (BuildConfig.UPDATE_URL: the "apk" branch of the game's
 * repository, written by tools/publish-apk.sh): {versionCode, versionName, apk, sha256, bytes, notes}. If it is newer than
 * this build, [install] opens the APK's link in the browser and the player installs the download. Every build is signed with
 * the same key (app/signing), so it installs as an update.
 */
class AppUpdater(private val ctx: Context) {
    class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val bytes: Long, val notes: String)

    var available by mutableStateOf<Release?>(null)
        private set
    var note by mutableStateOf("")
        private set
    /** Kept for the panel: the game no longer downloads the APK itself. */
    val progress: Float get() = -1f

    private val main = Handler(Looper.getMainLooper())
    val current: String get() = BuildConfig.VERSION_NAME

    /** Looks for a newer build (off the main thread). [quiet]: say nothing when there is none. */
    fun check(quiet: Boolean = false) {
        if (!quiet) note = "Checking for an update…"
        Thread({
            val r = try {
                val c = URL(BuildConfig.UPDATE_URL + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                c.setRequestProperty("Cache-Control", "no-cache")
                val text = c.inputStream.use { it.readBytes().decodeToString() }
                c.disconnect()
                val j = JSONObject(text)
                Release(j.getInt("versionCode"), j.getString("versionName"), j.getString("apk"), j.getString("sha256"), j.getLong("bytes"), j.optString("notes"))
            } catch (e: Exception) {
                main.post { if (!quiet) note = "Could not check for an update (${e.message ?: e.javaClass.simpleName})." }
                return@Thread
            }
            main.post {
                if (r.versionCode > BuildConfig.VERSION_CODE) { available = r; note = "Update available: ${r.versionName}" }
                else { available = null; if (!quiet) note = "You have the latest build ($current)." }
            }
        }, "update-check").start()
    }

    /**
     * Opens [r]'s APK in the browser: it downloads it, and the player taps it to install over this build (same signing
     * key). The game itself never downloads or installs apps: Play Protect blocks sideloaded apps that do ("dropper").
     */
    fun install(r: Release) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.apk)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            note = "Downloading in your browser: open the file when it finishes to update."
        } catch (e: Exception) {
            note = "No browser to download the update with: ${r.apk}"
        }
    }
}
