package com.timerpg.street.shot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.TimeZone

/**
 * What the screenshot JSON carries beside the game state, for what fails quietly: the last breadcrumbs of this run and
 * the run before (and of the writer's process), the reports waiting on the phone by kind with the uploads' last error,
 * the writer's state and why, and the charger (wireless or cable). The pure parts are tested on the JVM.
 */
object Diagnostics {
    const val CRUMBS = 40

    /** The report files waiting in the reports folder, by kind ("recording": a file the recorder still writes, or one an
     *  ended process left open until the next start closes it). */
    fun pendingByKind(names: List<String>): Map<String, Int> {
        val m = linkedMapOf("crashes" to 0, "night" to 0, "npu" to 0, "play" to 0, "talk" to 0, "trace" to 0, "recording" to 0)
        for (n in names) {
            if (n.startsWith("open-")) { m["recording"] = m["recording"]!! + 1; continue }
            val kind = n.substringBefore('~', "")
            if (kind in m && kind != "recording") m[kind] = m[kind]!! + 1
        }
        return m
    }

    /** BatteryManager's EXTRA_PLUGGED in words: "wireless", "cable (AC)", "cable (USB)", "dock", "none". */
    fun plugName(plugged: Int): String = when {
        plugged == 0 -> "none"
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "cable (AC)"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "cable (USB)"
        plugged and 8 != 0 -> "dock"
        else -> "other ($plugged)"
    }

    /** The last [n] of [crumbs] as "HH:mm:ss.SSS what" (oldest first). */
    fun lastCrumbs(crumbs: List<Pair<Long, String>>, tz: TimeZone, n: Int = CRUMBS): List<String> {
        val f = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).apply { timeZone = tz }
        return crumbs.takeLast(n).map { (t, w) -> f.format(java.util.Date(t)) + " " + w }
    }

    private fun readCrumbs(f: File): List<Pair<Long, String>> = runCatching { Breadcrumbs.parse(f.readText()) }.getOrDefault(emptyList())

    /** The section for the screenshot JSON. */
    fun collect(ctx: Context): Map<String, Any?> {
        val app = ctx.applicationContext
        val tz = TimeZone.getDefault()
        val dir = Reports.dir(app)
        val m = linkedMapOf<String, Any?>()
        m["breadcrumbs"] = linkedMapOf(
            "thisRun" to lastCrumbs(Crumbs.ring.snapshot(), tz),
            "previousRun" to lastCrumbs(Crumbs.previous(), tz),
            // (the writer's process keeps its own: its current or last run, and the one before)
            "writer" to lastCrumbs(readCrumbs(File(dir, "crumbs-writer.txt")), tz),
            "writerPrevious" to lastCrumbs(readCrumbs(File(dir, "crumbs-writer-prev.txt")), tz),
        )
        m["reports"] = linkedMapOf(
            "pending" to pendingByKind(dir.list()?.toList().orEmpty()),
            "uploads" to runCatching { UploadLog.summary(app) }.getOrNull(),
        )
        // the playtest recorder: is it running, what is it writing; and whether its uploads may go now
        m["recorder"] = runCatching { com.timerpg.street.play.PlayLog.status() }.getOrElse { mapOf("error" to it.toString()) }
        m["uploadGate"] = runCatching {
            val p = com.timerpg.street.play.Probes(app)
            val wifi = p.wifiOrNull(); val charging = p.chargingNow()
            linkedMapOf("wifi" to (wifi ?: "unknown"), "networkError" to p.networkError, "charging" to charging,
                "playAllowed" to com.timerpg.street.play.PlayUploads.allowed(com.timerpg.street.play.PlayUploads.Kind.PLAY, wifi, charging),
                "traceAllowed" to com.timerpg.street.play.PlayUploads.allowed(com.timerpg.street.play.PlayUploads.Kind.TRACE, wifi, charging),
                "hasToken" to ShotSettings.hasToken)
        }.getOrElse { mapOf("error" to it.toString()) }
        m["writer"] = runCatching {
            val nw = com.timerpg.street.night.NightWriter
            linkedMapOf("status" to nw.status(app)?.map(System.currentTimeMillis()), "lastRun" to nw.lastSummary(app),
                "enabled" to nw.enabled(app), "unrestricted" to nw.unrestricted(app))
        }.getOrNull()
        m["power"] = runCatching {
            val b = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            linkedMapOf("plugged" to plugName(plugged), "charging" to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL),
                "battery" to com.timerpg.street.night.NightWriter.battery(app))
        }.getOrNull()
        return m
    }
}
