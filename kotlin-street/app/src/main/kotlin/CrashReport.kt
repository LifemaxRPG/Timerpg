package com.timerpg.street

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Why the game last stopped, to show and copy on the next start: Android's own record (API 30+: killed for memory, a
 * crash in Kotlin or in native code, with its trace) and, for a Kotlin crash, the stack written by [install]'s handler.
 */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    /** Writes any uncaught exception (with the memory picture) to a file before the game goes down. */
    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                file(ctx).writeText("Crash in thread ${t.name} (build ${BuildConfig.VERSION_NAME})\n${memory(ctx)}\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun memory(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        "RAM free ${mi.availMem shr 20} MB of ${mi.totalMem shr 20} MB, the game ${android.os.Debug.getPss() shr 10} MB"
    }.getOrDefault("")

    /** The report of the last time the game stopped abnormally (and clears it), or null if it ended normally. */
    fun takeLast(ctx: Context): String? {
        val sb = StringBuilder()
        val f = file(ctx)
        if (f.isFile) { sb.append(f.readText().take(6000)).append('\n'); f.delete() }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
            val seen = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
            if (last != null && last.timestamp != seen.getLong("t", 0L)) {
                seen.edit().putLong("t", last.timestamp).apply()
                val why = when (last.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed by Android for memory"
                    ApplicationExitInfo.REASON_CRASH -> "crash (Kotlin/Java)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native code)"
                    ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                    ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
                    ApplicationExitInfo.REASON_OTHER -> "killed (other)"
                    else -> null   // exited normally, or by the user
                }
                if (why != null) {
                    sb.append("Last exit: $why. ${last.description ?: ""}\n")
                    sb.append("Memory then: ${last.pss shr 10} MB in use, ${last.rss shr 10} MB resident\n")
                    if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || last.reason == ApplicationExitInfo.REASON_ANR)
                        last.traceInputStream?.use { sb.append(it.readBytes().decodeToString().take(8000)) }
                }
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }
}
