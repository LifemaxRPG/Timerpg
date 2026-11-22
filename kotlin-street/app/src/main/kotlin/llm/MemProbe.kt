package com.timerpg.street.llm

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import java.io.File

/**
 * The phone's memory by every measure an app can read, for the NPU fit test and the device gate ([NpuGate]).
 *
 * Why several: Android's "available" ([ActivityManager.MemoryInfo.availMem]) is free plus page cache, the figure its
 * low-memory flag uses. It leaves out what Android hands a foreground app on demand: the private memory of cached
 * background apps (the low-memory killer ends them first) and what can be pushed out to compressed swap (zram). The NPU's
 * copy of a model is not file-backed (llama.cpp's Hexagon code copies and retiles the weights into DMA buffers shared
 * with the DSP), so it is real memory, but the room for it can be more than availMem says. Only a real load tells how
 * much more: [NpuFitTest] measures it, and [NpuGate] counts it from then on.
 */
object MemProbe {
    /** One reading (MB; -1 where not readable). */
    data class Snap(
        val atMs: Long,
        val availMb: Long,
        val thresholdMb: Long,
        val lowMemory: Boolean,
        val totalMb: Long,
        /** /proc/meminfo, MB by field (MemFree, MemAvailable, Cached, SwapFree, AnonPages, ...). */
        val meminfo: Map<String, Long>,
        /** This process: /proc/self/status (VmRSS, RssAnon, RssFile, RssShmem, VmSwap) and its PSS by part. */
        val self: Map<String, Long>,
        /** /proc/pressure/memory's "some avg10" and "full avg10" (null: not readable here). */
        val psiSome10: Double? = null,
        val psiFull10: Double? = null,
        val watchLevel: Int = MemoryWatch.level,
        val thermal: Int = -1,
        val headroom: Float = Float.NaN,
    ) {
        fun mi(k: String) = meminfo[k] ?: -1L
        fun map(): Map<String, Any?> = linkedMapOf(
            "availMb" to availMb, "androidThresholdMb" to thresholdMb, "lowMemory" to lowMemory,
            "memFreeMb" to mi("MemFree"), "memAvailableMb" to mi("MemAvailable"), "cachedMb" to mi("Cached"),
            "swapFreeMb" to mi("SwapFree"), "swapTotalMb" to mi("SwapTotal"), "anonMb" to mi("AnonPages"),
            "shmemMb" to mi("Shmem"), "fileActiveMb" to mi("Active(file)"), "fileInactiveMb" to mi("Inactive(file)"),
            "unevictableMb" to mi("Unevictable"), "kernelReclaimableMb" to mi("KReclaimable"),
            "game" to self, "pressureSome10" to psiSome10, "pressureFull10" to psiFull10,
            "memoryWatchLevel" to watchLevel, "thermalStatus" to thermal,
            "thermalHeadroom10s" to if (headroom.isNaN()) null else Math.round(headroom * 100) / 100.0,
        )
    }

    /** /proc/meminfo's lines ("MemFree:  123456 kB") as MB by field. */
    fun parseMeminfo(text: String): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (line in text.lineSequence()) {
            val c = line.indexOf(':'); if (c <= 0) continue
            val parts = line.substring(c + 1).trim().split(Regex("\\s+"))
            val v = parts.getOrNull(0)?.toLongOrNull() ?: continue
            m[line.substring(0, c).trim()] = if (parts.getOrNull(1) == "kB") v shr 10 else v shr 20
        }
        return m
    }

    /** /proc/pressure/memory's avg10 of its "some" and "full" lines. */
    fun parsePsi(text: String): Pair<Double?, Double?> {
        fun avg(kind: String) = text.lineSequence().firstOrNull { it.startsWith(kind) }
            ?.split(' ')?.firstOrNull { it.startsWith("avg10=") }?.removePrefix("avg10=")?.toDoubleOrNull()
        return avg("some") to avg("full")
    }

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    fun snap(ctx: Context, withPss: Boolean = false): Snap {
        val mi = ActivityManager.MemoryInfo()
        runCatching { ctx.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi) }
        val self = LinkedHashMap<String, Long>()
        read("/proc/self/status")?.let { t ->
            for (k in listOf("VmRSS", "RssAnon", "RssFile", "RssShmem", "VmSwap"))
                t.lineSequence().firstOrNull { it.startsWith("$k:") }?.let { l -> parseMeminfo(l)[k]?.let { self["${k}Mb"] = it } }
        }
        if (withPss) runCatching {
            val d = Debug.MemoryInfo(); Debug.getMemoryInfo(d)
            fun stat(k: String) = (d.getMemoryStat("summary.$k")?.toLongOrNull() ?: 0L) shr 10
            self["pssMb"] = d.totalPss.toLong() shr 10
            self["javaMb"] = stat("java-heap"); self["nativeMb"] = stat("native-heap"); self["graphicsMb"] = stat("graphics")
            self["codeMb"] = stat("code"); self["otherMb"] = stat("private-other")
        }
        val (some, full) = read("/proc/pressure/memory")?.let { parsePsi(it) } ?: (null to null)
        var thermal = -1; var head = Float.NaN
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            thermal = pm.currentThermalStatus; head = pm.getThermalHeadroom(10)
        }
        return Snap(System.currentTimeMillis(), mi.availMem shr 20, mi.threshold shr 20, mi.lowMemory, mi.totalMem shr 20,
            read("/proc/meminfo")?.let { parseMeminfo(it) } ?: emptyMap(), self, some, full, MemoryWatch.level, thermal, head)
    }

    /** The onTrimMemory levels Android sent this process, with when (for the report; Android 14+ sends few in front). */
    object Trims {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        @Volatile private var on = false
        fun watch(ctx: Context) {
            if (on) return
            on = true
            ctx.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) { seen += System.currentTimeMillis() to level; while (seen.size > 40) seen.removeAt(0) }
                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() { seen += System.currentTimeMillis() to 80 }
            })
        }
    }
}
