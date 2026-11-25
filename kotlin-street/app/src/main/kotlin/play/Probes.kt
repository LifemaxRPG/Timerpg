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

    /**
     * The "mem" line: the process's PSS and its Java, native, graphics and code parts, graphics' "Gfx dev" and "GL mtrack"
     * (gfxDev, glMtrack: the driver's mapped memory and its own count of textures and buffers); the phone's free memory
     * (Android's, and the kernel's MemAvailable: memAvail) and pressure; the town's GPU memory by part against its budget
     * ([TownBudget], the desktop's GpuBudgetTest's: gpuBudget, gpuOver); and the dialogue model's NPU headroom (npuRoom:
     * what the gate would count for the light model's copy, free plus what Android is expected to find, less its need).
     */
    fun memory(j: JsonLine, tMs: Long): StringBuilder {
        j.begin().str("k", "mem").long("t", tMs)
        runCatching {
            val d = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(d)
            fun mb(k: String) = (d.getMemoryStat("summary.$k")?.toLongOrNull() ?: -1024L) shr 10
            j.long("pss", d.totalPss.toLong() shr 10).long("java", mb("java-heap")).long("native", mb("native-heap"))
                .long("gfx", mb("graphics")).long("code", mb("code")).long("stack", mb("stack")).long("other", mb("private-other"))
                .long("swap", mb("total-swap"))
            graphicsParts(d)?.let { (dev, gl) -> if (dev >= 0) j.long("gfxDev", dev); if (gl >= 0) j.long("glMtrack", gl) }
        }
        val rt = Runtime.getRuntime()
        j.long("heapUsed", (rt.totalMemory() - rt.freeMemory()) shr 20).long("heapMax", rt.maxMemory() shr 20)
        val mw = com.timerpg.street.llm.MemoryWatch
        if (mw.freeMb >= 0) j.long("free", mw.freeMb).long("lmkAt", mw.thresholdMb).int("lvl", mw.level)
        val meminfo = runCatching { com.timerpg.street.llm.MemProbe.parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull()
        meminfo?.get("MemAvailable")?.let { j.long("memAvail", it) }
        runCatching { File("/proc/pressure/memory").readText() }.getOrNull()?.let { t ->
            val (some, full) = com.timerpg.street.llm.MemProbe.parsePsi(t)
            some?.let { j.num("psiSome", it, 2) }; full?.let { j.num("psiFull", it, 2) }
        }
        budget(tMs)?.let { rows ->
            j.raw("gpuBudget", rows.filter { it.mb != null }.joinToString(",", "{", "}") { "\"${it.part}\":[${it.mb},${it.budget}]" })
            j.str("gpuOver", rows.filter { it.over }.joinToString(", ") { "%s by %.1f MB".format(java.util.Locale.US, it.part, it.mb!! - it.budget) })
        }
        runCatching { npuRoom(mw.freeMb, meminfo?.get("SwapFree") ?: 0L) }.getOrNull()?.let { (room, need, reclaim) ->
            j.long("npuRoom", room).long("npuNeed", need).long("npuReclaim", reclaim)
        }
        // the battery's draw over 10 s, the model loaded and the NPU's power vote (BatteryDraw; docs/CONVERSATION.md)
        runCatching { BatteryDraw.sample(ctx) }.getOrNull()?.let { b ->
            b.avgMa?.let { j.int("mA10s", it) }; b.sysAvgMa?.let { j.int("mAavg", it) }
            j.str("llm", b.model).str("npuVote", b.npu)
        }
        return j.end()
    }

    /** Graphics' "Gfx dev" and "GL mtrack" PSS (MB) through Debug.MemoryInfo's own labels, or null where not readable. */
    private fun graphicsParts(d: android.os.Debug.MemoryInfo): Pair<Long, Long>? = runCatching {
        val c = android.os.Debug.MemoryInfo::class.java
        val n = c.getField("NUM_OTHER_STATS").getInt(null)
        val label = c.getMethod("getOtherLabel", Int::class.javaPrimitiveType)
        val pss = c.getMethod("getOtherPss", Int::class.javaPrimitiveType)
        var dev = -1L; var gl = -1L
        for (i in 0 until n) when (label.invoke(null, i) as? String) {
            "Gfx dev" -> dev = ((pss.invoke(d, i) as Int) / 1024).toLong()
            "GL mtrack" -> gl = ((pss.invoke(d, i) as Int) / 1024).toLong()
        }
        dev to gl
    }.getOrNull()

    /** The town's GPU parts against [TownBudget], worked out at most every [BUDGET_MS] (tens of thousands of entries). */
    private fun budget(tMs: Long): List<com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.Row>? {
        if (lastBudget != null && tMs - budgetAt < BUDGET_MS) return lastBudget
        budgetAt = tMs
        lastBudget = runCatching {
            val cats = com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categories(com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.estimate)
            // (nothing of the town's on the GPU yet: no line)
            if ((cats["total"] as? Map<*, *>)?.get("textures") == 0) null else com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.check(cats)
        }.getOrNull()
        return lastBudget
    }
    private var lastBudget: List<com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.Row>? = null
    private var budgetAt = 0L

    /**
     * The NPU headroom (MB) for the light dialogue model's copy, by the gate's own arithmetic (llm/NpuGate.kt, read only):
     * free ([freeMb], Android's) plus what the gate counts Android will find ([com.timerpg.street.llm.NpuGate.verdict]),
     * less the copy's need: its measured copy (else the file), its own memory and the gate's learned margin. With the
     * need and the reclaim counted; null when the light model is not on the phone.
     */
    private fun npuRoom(freeMb: Long, swapFreeMb: Long): Triple<Long, Long, Long>? {
        if (freeMb < 0) return null
        val app = ctx.applicationContext
        val light = com.timerpg.street.dialogue.DialogueEngine.shared(app).copyNeedsMb().second
        if (light <= 0) return null
        // (copyNeedsMb counts the whole file and the old flat margin: the measured copy and the learned margin instead)
        val fileMb = com.timerpg.street.dialogue.ModelFiles.LIVE_BYTES shr 20
        val headMapped = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("headMapped", true)
        val copyMb = app.getSharedPreferences("ai", Context.MODE_PRIVATE)
            .getLong("copy_live_d${com.timerpg.street.llm.NativeLlm.NPU}_m${com.timerpg.street.dialogue.ModelFiles.LIVE_BYTES}_h${if (headMapped) 1 else 0}", -1L)
            .let { if (it > 0) it shr 20 else fileMb }
        val need = light - fileMb + copyMb - com.timerpg.street.llm.Ram.MARGIN_MB + com.timerpg.street.llm.NpuGate.margin(app)
        val reclaim = com.timerpg.street.llm.NpuGate.verdict(app, freeMb, need, swapFreeMb).reclaimMb
        return Triple(freeMb + reclaim - need, need, reclaim)
    }

    private companion object { const val BUDGET_MS = 30_000L }

    private fun readLong(f: File): Long? = try { f.readText().trim().toLongOrNull() } catch (t: Throwable) { null }

    /** On Wi-Fi (unmetered) now. */
    fun wifi(): Boolean = wifiOrNull() == true

    /**
     * On Wi-Fi (unmetered) now, or null when the phone's network state cannot be read ([networkError] says why). Until
     * 0.1.638 the manifest lacked ACCESS_NETWORK_STATE: every read threw a SecurityException, which [wifi] took as "not
     * on Wi-Fi", so the timelines and conversations waited for a charger that the game is rarely played on.
     */
    fun wifiOrNull(): Boolean? = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        networkError = null
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    } catch (t: Throwable) { networkError = "${t.javaClass.simpleName}: ${t.message}"; null }

    /** Why the network state could not be read last time (null: it could). */
    var networkError: String? = null; private set

    /** Charging now (read afresh). */
    fun chargingNow(): Boolean = runCatching {
        val i = ctx.registerReceiver(null, batteryFilter) ?: return@runCatching false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }.getOrDefault(false)
}
