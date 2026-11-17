package com.timerpg.street

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the game from inside it. [check] reads update.json (BuildConfig.UPDATE_URL: the "apk" branch of the game's
 * repository, written by tools/publish-apk.sh): {versionCode, versionName, apk, sha256, bytes, notes}. If it is newer than
 * this build, [install] downloads the APK (resuming, size and SHA-256 checked) and hands it to Android's installer, which
 * asks the player to confirm. Every build is signed with the same key (app/signing), so it installs as an update.
 */
class AppUpdater(private val ctx: Context) {
    class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val bytes: Long, val notes: String)

    var available by mutableStateOf<Release?>(null)
        private set
    var note by mutableStateOf("")
        private set
    /** Downloading: 0..1, or -1. */
    var progress by mutableFloatStateOf(-1f)
        private set

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

    /** Downloads [r] and asks Android to install it. */
    fun install(r: Release) {
        if (progress >= 0f) return
        progress = 0f; note = "Downloading the update…"
        Thread({
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            val f = File(dir, "timerpg-${r.versionCode}.apk")
            try {
                download(r, f)
                main.post { note = "Installing…" }
                installFile(f)
            } catch (e: Exception) {
                main.post { note = "Update failed: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                main.post { progress = -1f }
            }
        }, "update-download").start()
    }

    private fun download(r: Release, f: File) {
        val part = File(f.path + ".part")
        for (attempt in 1..4) {
            try {
                val have = if (part.isFile) part.length() else 0L
                val c = URL(r.apk).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 20_000; c.readTimeout = 30_000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code !in 200..299) throw java.io.IOException("server answered $code")
                val append = code == 206 && have > 0
                java.io.FileOutputStream(part, append).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(1 shl 16); var got = if (append) have else 0L; var last = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            val now = System.currentTimeMillis()
                            if (now - last > 200) { last = now; val p = got.toFloat() / r.bytes; main.post { progress = p } }
                        }
                    }
                }
                c.disconnect()
                if (part.length() != r.bytes) { part.delete(); throw java.io.IOException("got ${part.length()} bytes, expected ${r.bytes}") }
                val sha = MessageDigest.getInstance("SHA-256").let { md -> part.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest().joinToString("") { "%02x".format(it) } }
                if (!sha.equals(r.sha256, ignoreCase = true)) { part.delete(); throw java.io.IOException("the download is damaged (checksum)") }
                f.delete(); part.renameTo(f)
                return
            } catch (e: Exception) {
                if (attempt == 4) throw e
                Thread.sleep(1500L * attempt)
            }
        }
    }

    private fun installFile(f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val action = "${ctx.packageName}.UPDATE_STATUS"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            // Android's own confirmation (and, the first time, "allow installing from this app")
                            @Suppress("DEPRECATION")
                            val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            if (confirm != null) ctx.startActivity(confirm)
                        }
                        PackageInstaller.STATUS_SUCCESS -> { note = "Updated."; runCatching { ctx.unregisterReceiver(this) } }
                        else -> {
                            note = "The update was not installed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"}"
                            runCatching { ctx.unregisterReceiver(this) }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, IntentFilter(action))
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(action).setPackage(ctx.packageName), flags)
            s.commit(pending.intentSender)
        }
    }
}
