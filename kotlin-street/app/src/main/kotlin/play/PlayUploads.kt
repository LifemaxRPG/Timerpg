package com.timerpg.street.play

import android.content.Context
import java.io.File

/**
 * Sends the recorder's finished files to the `reports` branch, as the crash and night reports go (the same folder, the
 * same token, the same upload thread: [com.timerpg.street.shot.ShotUploader.onWorker]). File names hold the repository
 * path with '~' for '/': `play~<date>~<session>.jsonl.gz`, `talk~<date>~<session>.jsonl`,
 * `trace~<date>~<time>-<why>.perfetto-trace.gz`. A file still being written starts with "open-" and is never sent.
 *
 * When: the timeline and the conversations on Wi-Fi or while charging; the traces (big) only on Wi-Fi and charging.
 */
object PlayUploads {
    /** At most this many of each kind wait on the phone (the oldest dropped). */
    const val KEEP = 60

    enum class Kind(val prefix: String) { PLAY("play~"), TALK("talk~"), TRACE("trace~") }

    /** Whether [kind] may be sent now. */
    fun allowed(kind: Kind, wifi: Boolean, charging: Boolean): Boolean = when (kind) {
        Kind.PLAY, Kind.TALK -> wifi || charging
        Kind.TRACE -> wifi && charging
    }

    fun kindOf(name: String): Kind? = Kind.values().firstOrNull { name.startsWith(it.prefix) }

    fun pending(dir: File, kind: Kind): List<File> = (dir.listFiles { f -> f.name.startsWith(kind.prefix) } ?: emptyArray()).sortedBy { it.name }

    fun prune(dir: File) {
        for (k in Kind.values()) pending(dir, k).let { l -> if (l.size > KEEP) l.take(l.size - KEEP).forEach { it.delete() } }
        Traces.prune(dir)
    }

    /** The timeline and conversation files that may go now and have not yet ([com.timerpg.street.night.NightSession]
     *  gives their upload a moment before it closes the game's process). */
    fun waiting(ctx: Context): Int {
        val dir = com.timerpg.street.shot.Reports.dir(ctx)
        val p = Probes(ctx.applicationContext)
        if (!allowed(Kind.PLAY, p.wifi(), p.chargingNow())) return 0
        return pending(dir, Kind.PLAY).size + pending(dir, Kind.TALK).size
    }

    /** Sends what may go now (each file deleted once it is in the repository; stops at the first failure, which
     *  [com.timerpg.street.shot.UploadLog] keeps). */
    fun send(ctx: Context) {
        val app = ctx.applicationContext
        val dir = com.timerpg.street.shot.Reports.dir(app)
        com.timerpg.street.shot.ShotUploader.onWorker {
            com.timerpg.street.shot.ShotSettings.load(app)
            if (!com.timerpg.street.shot.ShotSettings.hasToken) return@onWorker
            prune(dir)
            val probes = Probes(app)
            val wifi = probes.wifi(); val charging = probes.chargingNow()
            for (kind in Kind.values()) {
                if (!allowed(kind, wifi, charging)) {
                    if (pending(dir, kind).isNotEmpty()) com.timerpg.street.shot.UploadLog.skipped(app, kind.prefix.removeSuffix("~"),
                        "not allowed now (Wi-Fi ${if (wifi) "yes" else "no"}, charging ${if (charging) "yes" else "no"}): " +
                            if (kind == Kind.TRACE) "traces need both" else "needs Wi-Fi or charging")
                    continue
                }
                for (f in pending(dir, kind)) {
                    val path = com.timerpg.street.shot.Reports.remotePath(f)
                    val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                    val what = when (kind) { Kind.PLAY -> "Playtest timeline"; Kind.TALK -> "Conversations"; Kind.TRACE -> "System trace" }
                    if (com.timerpg.street.shot.ShotUploader.putReport(app, path, bytes, "$what from the phone: ${path.substringAfter('/')}")) f.delete()
                    else return@onWorker
                }
            }
        }
    }
}
