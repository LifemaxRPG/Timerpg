package com.timerpg.street.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Watches the phone's free memory while the game runs, every [PERIOD_MS], and tells [listeners] (on the main thread)
 * when it crosses a level: 0 fine, 1 low (no writing ahead, idle models let go), 2 critical (the dialogue model is let
 * go even with the game in front). Android 14 and later no longer send a running app onTrimMemory's RUNNING_LOW or
 * RUNNING_CRITICAL, so the game has to look for itself: both crash reports of build 302 were the foreground game killed
 * by the low-memory killer with no warning in its breadcrumbs.
 *
 * The levels are set by what Android calls available ([ActivityManager.MemoryInfo.availMem], free plus what it can
 * drop) above its own threshold. The margins ([LOW_MB], [CRITICAL_MB]) are first guesses, not measured on the S25: the
 * breadcrumbs carry the free memory at every change and every 10 s in the town's frame line, so the next report says
 * where the phone really stood.
 */
object MemoryWatch {
    /** The level now (0 fine, 1 low, 2 critical), the free memory and Android's threshold when last read (MB). */
    @Volatile var level = 0; private set
    @Volatile var freeMb = -1L; private set
    @Volatile var thresholdMb = -1L; private set
    /** Told of each change of [level] (main thread). */
    val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    @Volatile private var thread: Thread? = null

    fun start(ctx: Context) {
        if (thread != null) return
        val am = ctx.applicationContext.getSystemService(ActivityManager::class.java) ?: return
        val main = Handler(Looper.getMainLooper())
        val mi = ActivityManager.MemoryInfo()
        thread = Thread({
            while (true) {
                try {
                    am.getMemoryInfo(mi)
                    val free = mi.availMem shr 20; val th = mi.threshold shr 20
                    freeMb = free; thresholdMb = th
                    // (a level is left only well clear of it, so it does not flicker at the edge)
                    val now = when {
                        mi.lowMemory || free < th + CRITICAL_MB -> 2
                        level == 2 && free < th + CRITICAL_MB + HYSTERESIS_MB -> 2
                        free < th + LOW_MB -> 1
                        level >= 1 && free < th + LOW_MB + HYSTERESIS_MB -> 1
                        else -> 0
                    }
                    if (now != level) {
                        level = now
                        com.timerpg.street.shot.Crumbs.add("memory ${NAMES[now]}: $free MB free (Android's threshold $th MB${if (mi.lowMemory) ", low" else ""})")
                        main.post { for (l in listeners) runCatching { l(now) } }
                    }
                    Thread.sleep(PERIOD_MS)
                } catch (e: InterruptedException) { break } catch (t: Throwable) {
                    try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                }
            }
        }, "memory-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private val NAMES = listOf("fine", "low", "critical")
    const val PERIOD_MS = 1500L
    /** Below Android's threshold plus this much, memory is low / critical (MB). */
    const val LOW_MB = 1200L
    const val CRITICAL_MB = 500L
    const val HYSTERESIS_MB = 300L
}
