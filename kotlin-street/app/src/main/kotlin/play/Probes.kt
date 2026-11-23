package com.timerpg.street.play

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * Heat, power and memory, read on the recorder's thread every few seconds (never the render thread): the thermal status
 * and ADPF's headroom forecast, the battery (level, current, voltage, temperature, charging), the CPU clusters' and the
 * GPU's clocks from sysfs where Android lets an app read them (quietly nothing where it does not), and the process's
 * memory by part with the phone's free memory and pressure.
 */
class Probes(private val ctx: Context) {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    /** The CPU policies' clock files (one per cluster), found once; the GPU's (Adreno's kgsl) if readable. */
    private val cpuFreq: List<File> by lazy {
        val d = File("/sys/devices/system/cpu/cpufreq")
        (d.listFiles { f -> f.name.startsWith("policy") } ?: emptyArray()).sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 0 }
            .map { File(it, "scaling_cur_freq") }.filter { runCatching { it.canRead() && it.readText().isNotBlank() }.getOrDefault(false) }
    }
    private val cpuPolicies: List<String> by lazy { cpuFreq.map { it.parentFile?.name?.removePrefix("policy") ?: "?" } }
    private val gpuFreq: File? by lazy {
        listOf("/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "/sys/kernel/gpu/gpu_clock")
            .map { File(it) }.firstOrNull { runCatching { it.canRead() && it.readText().isNotBlank() }.getOrDefault(false) }
    }
    var charging = false; private set
    var plugged = 0; private set

    /** The "pow" line: heat, battery, clocks. */
    fun power(j: JsonLine, tMs: Long): StringBuilder {
        j.begin().str("k", "pow").long("t", tMs)
        runCatching { j.int("th", pm.currentThermalStatus) }
        if (Build.VERSION.SDK_INT >= 30) runCatching { val h = pm.getThermalHeadroom(10); if (!h.isNaN()) j.num("head", h, 3) }
        runCatching {
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) j.int("bat", level)
            // (microamps on most phones, positive while charging on Samsung's; Integer.MIN_VALUE where not supported)
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua != Int.MIN_VALUE && ua != 0) j.int("mA", ua / 1000)
            val i = ctx.registerReceiver(null, batteryFilter)
            if (i != null) {
                val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
                if (mv > 0) j.int("mV", mv)
                if (t != Int.MIN_VALUE) j.num("degC", t / 10.0, 1)
                if (mv > 0 && ua != Int.MIN_VALUE && ua != 0) j.int("mW", (kotlin.math.abs(ua.toLong()) * mv / 1_000_000L).toInt())
            }
            j.bool("chg", charging)
            if (plugged != 0) j.int("plug", plugged)
        }
        if (cpuFreq.isNotEmpty()) {
            j.sb.append(",\"cpuMHz\":{")
            var first = true
            for (k in cpuFreq.indices) {
                val khz = readLong(cpuFreq[k]) ?: continue
                if (!first) j.sb.append(','); first = false
                j.sb.append('"').append(cpuPolicies[k]).append("\":").append(khz / 1000)
            }
            j.sb.append('}')
        }
        gpuFreq?.let { f -> readLong(f)?.let { hz -> j.int("gpuMHz", (if (hz > 100_000_000L) hz / 1_000_000 else if (hz > 100_000L) hz / 1000 else hz).toInt()) } }
        return j.end()
    }

    /** The "mem" line: the process's PSS and its Java, native, graphics and code parts; the phone's free memory and pressure. */
    fun memory(j: JsonLine, tMs: Long): StringBuilder {
        j.begin().str("k", "mem").long("t", tMs)
        runCatching {
            val d = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(d)
            fun mb(k: String) = (d.getMemoryStat("summary.$k")?.toLongOrNull() ?: -1024L) shr 10
            j.long("pss", d.totalPss.toLong() shr 10).long("java", mb("java-heap")).long("native", mb("native-heap"))
                .long("gfx", mb("graphics")).long("code", mb("code")).long("stack", mb("stack")).long("other", mb("private-other"))
                .long("swap", mb("total-swap"))
        }
        val rt = Runtime.getRuntime()
        j.long("heapUsed", (rt.totalMemory() - rt.freeMemory()) shr 20).long("heapMax", rt.maxMemory() shr 20)
        val mw = com.timerpg.street.llm.MemoryWatch
        if (mw.freeMb >= 0) j.long("free", mw.freeMb).long("lmkAt", mw.thresholdMb).int("lvl", mw.level)
        runCatching { File("/proc/pressure/memory").readText() }.getOrNull()?.let { t ->
            val (some, full) = com.timerpg.street.llm.MemProbe.parsePsi(t)
            some?.let { j.num("psiSome", it, 2) }; full?.let { j.num("psiFull", it, 2) }
        }
        return j.end()
    }

    private fun readLong(f: File): Long? = try { f.readText().trim().toLongOrNull() } catch (t: Throwable) { null }

    /** On Wi-Fi (unmetered) now. */
    fun wifi(): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }.getOrDefault(false)

    /** Charging now (read afresh). */
    fun chargingNow(): Boolean = runCatching {
        val i = ctx.registerReceiver(null, batteryFilter) ?: return@runCatching false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }.getOrDefault(false)
}
