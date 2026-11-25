package com.timerpg.street.shot

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Sends screenshots and crash reports to the game's GitHub repository with the REST API (one background thread, never
 * the game's): a screenshot and its game-state JSON to the `screenshots` branch, crash reports to `reports`; either
 * branch is made from the default branch's head the first time. Results are told in a toast. A screenshot sent with no
 * network waits and is tried once more when the phone is back online (or after a minute).
 */
object ShotUploader {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "claude-upload").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())

    // ---- capture

    /**
     * The whole window as the player sees it: Filament's layer and the Compose HUD alike (PixelCopy of the window, API
     * 26+; the town's SurfaceView, a layer of its own, is copied apart and the window laid over it). On API 24-25 the decor view is drawn into a bitmap instead, and the 3D layer may be missing there.
     * [done] runs on the main thread with the bitmap (null if it failed) and how it was taken.
     */
    fun capture(activity: Activity, done: (Bitmap?, String) -> Unit) {
        val v = activity.window.decorView
        val w = v.width; val h = v.height
        if (w <= 0 || h <= 0) { done(null, "no window"); return }
        val sv = com.timerpg.street.FilamentSurface.view
        if (Build.VERSION.SDK_INT >= 26 && sv != null && sv.isAttachedToWindow && sv.holder.surface?.isValid == true && sv.width > 0 && sv.height > 0) {
            // the town on a SurfaceView (its own layer, not in the window's pixels): the window (the HUD, with a clear hole
            // where the town is) copied over a copy of the town's surface
            val win = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val town = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            try {
                android.view.PixelCopy.request(activity.window, win, { r1 ->
                    if (r1 != android.view.PixelCopy.SUCCESS) { win.recycle(); town.recycle(); done(null, "PixelCopy of the window failed ($r1)"); return@request }
                    try {
                        android.view.PixelCopy.request(sv, town, { r2 ->
                            if (r2 != android.view.PixelCopy.SUCCESS) { town.recycle(); done(win, "PixelCopy of the window only (the town's surface failed: $r2)"); return@request }
                            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val c = Canvas(out)
                            val at = IntArray(2); sv.getLocationInWindow(at)
                            c.drawBitmap(town, at[0].toFloat(), at[1].toFloat(), null)
                            c.drawBitmap(win, 0f, 0f, null)
                            town.recycle(); win.recycle()
                            done(out, "PixelCopy of the town's SurfaceView under the window")
                        }, main)
                    } catch (t: Throwable) { town.recycle(); done(win, "PixelCopy of the window only (${t.javaClass.simpleName})") }
                }, main)
            } catch (t: Throwable) { win.recycle(); town.recycle(); done(null, "PixelCopy failed: ${t.javaClass.simpleName}") }
        } else if (Build.VERSION.SDK_INT >= 26) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                android.view.PixelCopy.request(activity.window, bmp, { r ->
                    if (r == android.view.PixelCopy.SUCCESS) done(bmp, "PixelCopy of the window") else { bmp.recycle(); done(null, "PixelCopy failed ($r)") }
                }, main)
            } catch (t: Throwable) { bmp.recycle(); done(null, "PixelCopy failed: ${t.javaClass.simpleName}") }
        } else {
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bmp))
                done(bmp, "decor view drawn (API ${Build.VERSION.SDK_INT}: the 3D layer may be missing)")
            } catch (t: Throwable) { done(null, "could not draw the window: ${t.javaClass.simpleName}") }
        }
    }

    // ---- encoding

    class Encoded(val bytes: ByteArray, val ext: String, val format: String, val scale: Float, val width: Int, val height: Int)

    /**
     * Under [ShotRequests.MAX_IMAGE_BYTES]: a full-resolution PNG if it fits, else lossless WebP (API 29+, where Android
     * writes WebP losslessly), else PNG scaled by 0.75 (and again, down to about a third) until it fits.
     */
    fun encode(src: Bitmap): Encoded {
        fun compress(b: Bitmap, f: Bitmap.CompressFormat, q: Int) = ByteArrayOutputStream(1 shl 20).also { b.compress(f, q, it) }.toByteArray()
        val png = compress(src, Bitmap.CompressFormat.PNG, 100)
        if (png.size <= ShotRequests.MAX_IMAGE_BYTES) return Encoded(png, "png", "png", 1f, src.width, src.height)
        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            val webp = if (Build.VERSION.SDK_INT >= 30) compress(src, Bitmap.CompressFormat.WEBP_LOSSLESS, 75)
                else compress(src, Bitmap.CompressFormat.WEBP, 100)   // (Q: quality 100 is lossless)
            if (webp.size <= ShotRequests.MAX_IMAGE_BYTES) return Encoded(webp, "webp", "webp-lossless", 1f, src.width, src.height)
        }
        var scale = 1f
        while (true) {
            scale *= .75f
            val w = (src.width * scale).toInt().coerceAtLeast(1); val h = (src.height * scale).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val bytes = compress(small, Bitmap.CompressFormat.PNG, 100)
            if (small !== src) small.recycle()
            if (bytes.size <= ShotRequests.MAX_IMAGE_BYTES || scale < .35f) return Encoded(bytes, "png", "png-scaled", scale, w, h)
        }
    }

    // ---- HTTP

    private class Resp(val code: Int, val body: String)

    private fun call(method: String, url: String, token: String, body: ByteArray? = null): Resp = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        for ((k, v) in ShotRequests.headers(token)) c.setRequestProperty(k, v)
        c.setRequestProperty("User-Agent", "timerpg-app")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
        c.disconnect()
        Resp(code, text)
    } catch (e: IOException) { Resp(-1, e.javaClass.simpleName) }

    private fun ok(code: Int) = code in 200..299

    /** Makes [branch] from the default branch's head if it is not there. Null when it is there now, else the error. */
    private fun ensureBranch(token: String, branch: String): Resp? {
        val ref = call("GET", ShotRequests.refUrl(branch), token)
        if (ok(ref.code)) return null
        if (ref.code != 404) return ref
        val repo = call("GET", ShotRequests.repoUrl(), token)
        if (!ok(repo.code)) return repo
        val def = runCatching { org.json.JSONObject(repo.body).getString("default_branch") }.getOrDefault("main")
        val head = call("GET", ShotRequests.refUrl(def), token)
        if (!ok(head.code)) return head
        val sha = runCatching { org.json.JSONObject(head.body).getJSONObject("object").getString("sha") }.getOrNull() ?: return Resp(0, "no head sha")
        val made = call("POST", ShotRequests.refsUrl(), token, ShotRequests.createRefBody(branch, sha).toByteArray())
        // (422: made meanwhile by another upload)
        return if (ok(made.code) || made.code == 422) null else made
    }

    /**
     * PUTs one file. A 404 or 422 may be the branch missing: it is made, and the file put again. [rename] gives another
     * path for a name already taken (422 with the branch there).
     */
    private fun put(token: String, branch: String, path: String, bytes: ByteArray, message: String, rename: ((Int) -> String)? = null): Pair<Resp, String> {
        var p = path
        var r = call("PUT", ShotRequests.contentsUrl(p), token, ShotRequests.putBody(message, bytes, branch))
        if (r.code == 404 || r.code == 422) {
            val err = ensureBranch(token, branch)
            if (err != null) return err to p
            r = call("PUT", ShotRequests.contentsUrl(p), token, ShotRequests.putBody(message, bytes, branch))
            var k = 1
            while (r.code == 422 && rename != null && k < 5) {
                p = rename(k++)
                r = call("PUT", ShotRequests.contentsUrl(p), token, ShotRequests.putBody(message, bytes, branch))
            }
        }
        return r to p
    }

    private fun toast(ctx: Context, text: String) = main.post { Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show() }

    private fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    // ---- screenshots

    /**
     * Sends [bmp] (taken at [takenAt]) with the [note] and [game] state: the image, then the JSON beside it. [how]: how
     * the window was captured. (The bitmap is left to the garbage collector: the sheet may still be drawing it.)
     */
    fun sendShot(ctx: Context, bmp: Bitmap, note: String?, takenAt: Long, how: String, game: Map<String, Any?>?, marks: List<ShotMark> = emptyList()) {
        val app = ctx.applicationContext
        val token = ShotSettings.token(app)
        if (token == null) { toast(app, "No token: add one in the AI panel"); return }
        toast(app, "Sending to Claude…")
        worker.execute {
            // with arrows drawn: the marked picture is the one sent as <name>.png, the unmarked one beside it as <name>-raw.png
            val enc = try { encode(if (marks.isEmpty()) bmp else ShotArt.render(bmp, marks)) } catch (t: Throwable) { null }
            val raw = if (marks.isEmpty()) null else try { encode(bmp) } catch (t: Throwable) { null }
            if (enc == null) { toast(app, "Could not encode the screenshot"); return@execute }
            val tz = TimeZone.getDefault()
            val sections = linkedMapOf<String, Any?>(
                "screen" to DeviceFacts.screen(app),
                "device" to DeviceFacts.device(app),
                "game" to game,
                // what fails quietly: breadcrumbs, reports waiting and the uploads' last error, the writer, the charger
                "diagnostics" to runCatching { Diagnostics.collect(app) }.getOrElse { mapOf("error" to it.toString()) },
            )
            val job = Job(enc, note, takenAt, tz, how, sections, raw, marks, bmp.width, bmp.height)
            upload(app, job, retried = false)
        }
    }

    private class Job(val enc: Encoded, val note: String?, val at: Long, val tz: TimeZone, val how: String, val sections: Map<String, Any?>,
                      val raw: Encoded? = null, val marks: List<ShotMark> = emptyList(), val srcW: Int = 0, val srcH: Int = 0)

    private fun upload(app: Context, job: Job, retried: Boolean) {
        val token = ShotSettings.token(app) ?: run { toast(app, "No token: add one in the AI panel"); return }
        if (!online(app)) { waitForNetwork(app, job, retried); return }
        val path0 = ShotRequests.shotPath(job.at, job.tz, job.note, job.enc.ext)
        val (r, path) = put(token, ShotRequests.SHOT_BRANCH, path0, job.enc.bytes, ShotRequests.shotMessage(job.note, path0)) { k ->
            ShotRequests.shotPath(job.at, job.tz, job.note, job.enc.ext, k)
        }
        if (r.code == -1) { waitForNetwork(app, job, retried); return }
        if (!ok(r.code)) { UploadLog.failed(app, path, r.code, r.body); toast(app, "Not sent: " + ShotRequests.explain(r.code, r.body)); return }
        var rawName: String? = null
        if (job.raw != null) {
            val rawPath = ShotMarks.rawPath(path).substringBeforeLast('.') + "." + job.raw.ext
            val (rr, rp) = put(token, ShotRequests.SHOT_BRANCH, rawPath, job.raw.bytes, "Unmarked screenshot for ${path.substringAfterLast('/')}")
            if (ok(rr.code)) rawName = rp.substringAfterLast('/') else toast(app, "Unmarked picture not sent: " + ShotRequests.explain(rr.code, rr.body))
        }
        val image = linkedMapOf<String, Any?>(
            "file" to path.substringAfterLast('/'), "format" to job.enc.format, "scale" to job.enc.scale,
            "width" to job.enc.width, "height" to job.enc.height, "bytes" to job.enc.bytes.size, "captured" to job.how,
        )
        val sections = LinkedHashMap(job.sections)
        ShotMarks.section(job.marks, job.srcW, job.srcH, path.substringAfterLast('/'), rawName ?: "(not sent)")?.let { sections["annotations"] = it }
        val json = ShotRequests.shotPayload(job.at, job.tz, job.note, DeviceFacts.app(), image, sections)
        val (j, _) = put(token, ShotRequests.SHOT_BRANCH, ShotRequests.siblingJson(path), json.toByteArray(Charsets.UTF_8), "Game state for ${path.substringAfterLast('/')}")
        toast(app, if (ok(j.code)) "Sent to Claude" else "Image sent, its game state not: " + ShotRequests.explain(j.code, j.body))
    }

    /** No network: tried once more when the phone is back online (or in a minute), then given up. */
    private fun waitForNetwork(app: Context, job: Job, retried: Boolean) {
        if (retried) { toast(app, "No network: the screenshot was not sent"); return }
        toast(app, "No network: will send when back online")
        var done = false
        val again = Runnable { synchronized(this) { if (done) return@Runnable; done = true }; worker.execute { upload(app, job, retried = true) } }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runCatching { cm?.unregisterNetworkCallback(this) }; main.postDelayed(again, 2000) }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        main.postDelayed({ runCatching { cm?.unregisterNetworkCallback(cb) }; again.run() }, 60_000)
    }

    // ---- the token test and crash reports

    /** GET of the repository with the saved token: [done] (on the main thread) says what it can do. */
    fun testAccess(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val token = ShotSettings.token(app) ?: run { done("No token: add one first"); return }
        worker.execute {
            val r = call("GET", ShotRequests.repoUrl(), token)
            val text = if (ok(r.code)) {
                val push = runCatching { org.json.JSONObject(r.body).optJSONObject("permissions")?.optBoolean("push", true) }.getOrNull()
                if (push == false) "Token can read ${ShotRequests.OWNER}/${ShotRequests.REPO} but not write to it"
                else "Token works: ${ShotRequests.OWNER}/${ShotRequests.REPO} is reachable"
            } else ShotRequests.explain(r.code, r.body)
            main.post { done(text) }
        }
    }

    /** Sends the crash reports waiting on the phone (each deleted once it is in the repository). */
    fun sendReports(ctx: Context, quiet: Boolean = true) {
        val app = ctx.applicationContext
        worker.execute {
            val token = ShotSettings.token(app) ?: return@execute
            var sent = 0
            for (f in Reports.pending(app).reversed()) {
                val path = Reports.remotePath(f)
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                val (r, _) = put(token, ShotRequests.REPORT_BRANCH, path, bytes, "Crash report from the phone: ${path.substringAfterLast('/')}") { k ->
                    path.removeSuffix(".json") + "-${k + 1}.json"
                }
                if (ok(r.code)) { f.delete(); sent++; UploadLog.ok(app, path, r.code) }
                else { UploadLog.failed(app, path, r.code, r.body); if (!quiet) toast(app, "Crash report not sent: " + ShotRequests.explain(r.code, r.body)); break }
            }
            if (sent > 0) toast(app, "Sent $sent crash report${if (sent > 1) "s" else ""} to Claude")
        }
    }

    /** Sends the overnight writer's summaries waiting on the phone (quietly; each deleted once it is in the repository). */
    fun sendNight(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            ShotSettings.load(app)
            val waiting = Reports.pendingNight(app).size + Reports.pendingNpu(app).size
            val token = ShotSettings.token(app) ?: run { if (waiting > 0) UploadLog.skipped(app, "night", "no token"); return@execute }
            if (!online(app)) { if (waiting > 0) UploadLog.skipped(app, "night", "offline"); return@execute }
            for (f in Reports.pendingNight(app).reversed()) {
                val path = Reports.remotePath(f)
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                val (r, _) = put(token, ShotRequests.REPORT_BRANCH, path, bytes, "Overnight writer: ${path.removePrefix("night/")}") { k ->
                    path.removeSuffix(".json") + "-${k + 1}.json"
                }
                if (ok(r.code)) { f.delete(); UploadLog.ok(app, path, r.code) } else { UploadLog.failed(app, path, r.code, r.body); break }
            }
            // the NPU fit test's reports (a test under way keeps its file: sent once it is done, or on the next start)
            for (f in Reports.pendingNpu(app).reversed()) {
                if (com.timerpg.street.llm.NpuFitTest.writing(f)) continue
                val path = Reports.remotePath(f)
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                val (r, _) = put(token, ShotRequests.REPORT_BRANCH, path, bytes, "NPU fit test: ${path.removePrefix("npu/")}") { k ->
                    path.removeSuffix(".json") + "-${k + 1}.json"
                }
                if (ok(r.code)) { f.delete(); UploadLog.ok(app, path, r.code) } else { UploadLog.failed(app, path, r.code, r.body); break }
            }
        }
    }

    /** Runs [block] on the upload thread (the playtest recorder's uploads: play.PlayUploads). */
    fun onWorker(block: () -> Unit) = worker.execute {
        // (a failure here used to vanish: it goes into the breadcrumbs, which the screenshot JSON and crash reports carry)
        runCatching(block).onFailure { Crumbs.add("upload thread: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Puts one file at [path] on the reports branch (call on the upload thread). True once it is in the repository. */
    fun putReport(ctx: Context, path: String, bytes: ByteArray, message: String): Boolean {
        val app = ctx.applicationContext
        val token = ShotSettings.token(app) ?: run { UploadLog.skipped(app, UploadLog.kindOf(path), "no token"); return false }
        if (!online(app)) { UploadLog.skipped(app, UploadLog.kindOf(path), "offline"); return false }
        val dot = path.indexOf('.', path.lastIndexOf('/'))
        val (r, _) = put(token, ShotRequests.REPORT_BRANCH, path, bytes, message) { k ->
            if (dot < 0) "$path-${k + 1}" else path.substring(0, dot) + "-${k + 1}" + path.substring(dot)
        }
        if (ok(r.code)) UploadLog.ok(app, path, r.code) else UploadLog.failed(app, path, r.code, r.body)
        return ok(r.code)
    }

    /** On each start, in the background: Android's record of how the last runs ended, then any reports sent. */
    fun onLaunch(ctx: Context, filamentDied: Boolean) {
        val app = ctx.applicationContext
        worker.execute {
            ShotSettings.load(app)
            runCatching { Reports.scanExits(app, filamentDied) }
            Reports.prune(app)
            if (ShotSettings.hasToken && ShotSettings.autoReports) sendReports(app)
            if (ShotSettings.hasToken) sendNight(app)
            // the playtest recorder's timelines, conversations and traces (on Wi-Fi or charging; traces on both)
            if (ShotSettings.hasToken) com.timerpg.street.play.PlayUploads.send(app)
            else if (Reports.dir(app).list()?.any { it.startsWith("play~") || it.startsWith("night~") || it.startsWith("crashes~") } == true)
                UploadLog.skipped(app, "all", "no token")
        }
        // the writer in its own process stops as the game opens and writes its report as it stops: after this start's
        // upload, which therefore never saw it (and its own process is cached, frozen by Android, moments after). Once
        // its lock is free, the reports waiting then go up from here.
        if (com.timerpg.street.night.WriterLock.heldElsewhere(app)) Thread({
            com.timerpg.street.night.WriterLock.awaitFree(app, 60_000L)
            try { Thread.sleep(1500) } catch (e: InterruptedException) { }
            Crumbs.add("uploads: the writer let go; sending its report")
            sendNight(app)
        }, "night-report-wait").apply { isDaemon = true; start() }
    }
}
