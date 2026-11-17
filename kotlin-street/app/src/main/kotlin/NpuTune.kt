package com.timerpg.street

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import org.json.JSONArray

/**
 * Tunes llama.cpp's Hexagon NPU backend on this phone. Its settings (how many NPU sessions the model is spread over, the
 * power mode, the matrix unit, host buffers) are read once, when the backend starts, so each setting is tried in its own
 * start of the game: set before the native library loads ([apply]), the model loaded on the NPU, the speed test run
 * twice (the second is the warm number), the result kept, and the game restarted with the next. A setting that crashes
 * the game (the start finds its step still marked running) is recorded as failed and skipped. At the end the fastest
 * setting whose reply passed the check is kept for every later start.
 */
object NpuTune {
    class Config(val name: String, val env: Map<String, String>)

    val CONFIGS = listOf(
        Config("2 sessions (default)", mapOf("GGML_HEXAGON_DEVICES" to "2")),
        Config("3 sessions", mapOf("GGML_HEXAGON_DEVICES" to "3")),
        Config("4 sessions", mapOf("GGML_HEXAGON_DEVICES" to "4")),
        Config("2 sessions, sustained power", mapOf("GGML_HEXAGON_DEVICES" to "2", "GGML_HEXAGON_POWER_MODE" to "sustained_high_performance")),
        Config("2 sessions, no host buffers", mapOf("GGML_HEXAGON_DEVICES" to "2", "GGML_HEXAGON_HOSTBUF" to "0")),
        Config("2 sessions, vector units only (no HMX)", mapOf("GGML_HEXAGON_DEVICES" to "2", "GGML_HEXAGON_NHMX" to "0")),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("npu", Context.MODE_PRIVATE)

    /** The step being tried (-1: not tuning). */
    fun step(ctx: Context): Int = prefs(ctx).getInt("step", -1)
    fun active(ctx: Context) = step(ctx) >= 0
    fun results(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("results", "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    /** The setting in use (kept from the last tuning), or null for the default. */
    fun chosen(ctx: Context): String? = prefs(ctx).getString("chosen", null)

    /**
     * At start, before the native library loads: the setting to run with (this step's while tuning, else the chosen one)
     * into the environment. A step found still running crashed last time: recorded and skipped.
     */
    fun apply(ctx: Context) {
        val p = prefs(ctx)
        var step = p.getInt("step", -1)
        if (step >= 0 && p.getInt("running", -1) == step) {
            add(ctx, "${CONFIGS[step].name}: the game stopped with it (crashed or killed)", null)
            step++
            p.edit().putInt("step", if (step < CONFIGS.size) step else -1).apply()
            if (step >= CONFIGS.size) finish(ctx)
        }
        step = p.getInt("step", -1)
        val env = if (step >= 0) CONFIGS[step].env
        else p.getString("chosenEnv", null)?.let { parse(it) } ?: emptyMap()
        if (step >= 0) p.edit().putInt("running", step).commit()
        for ((k, v) in env) runCatching { android.system.Os.setenv(k, v, true) }
    }

    /** Starts tuning: the dialogue model on the NPU, the first setting, a restart. */
    fun begin(activity: Activity) {
        prefs(activity).edit().putInt("step", 0).putInt("running", -1).putString("results", "[]").putString("scores", "[]").commit()
        activity.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putInt("device", 2).putBoolean("repack", false).commit()
        restart(activity)
    }

    /** Stops tuning, keeping what was measured so far. */
    fun cancel(activity: Activity) {
        prefs(activity).edit().putInt("step", -1).putInt("running", -1).commit()
        finish(activity)
        restart(activity)
    }

    /** This step's result ([ms]: the warm line's time, null if it failed); then the next step (a restart). */
    fun record(activity: Activity, text: String, ms: Long?) {
        val p = prefs(activity)
        val step = p.getInt("step", -1)
        if (step < 0) return
        add(activity, "${CONFIGS[step].name}: $text", ms)
        val next = step + 1
        p.edit().putInt("running", -1).putInt("step", if (next < CONFIGS.size) next else -1).commit()
        if (next >= CONFIGS.size) finish(activity)
        restart(activity)
    }

    private fun add(ctx: Context, line: String, ms: Long?) {
        val p = prefs(ctx)
        val r = JSONArray(p.getString("results", "[]")).put(line)
        val sc = JSONArray(p.getString("scores", "[]")).put(ms ?: -1L)
        p.edit().putString("results", r.toString()).putString("scores", sc.toString()).commit()
    }

    /** The fastest setting that worked becomes the one used from now on. */
    private fun finish(ctx: Context) {
        val p = prefs(ctx)
        val sc = JSONArray(p.getString("scores", "[]"))
        var best = -1; var bestMs = Long.MAX_VALUE
        for (i in 0 until minOf(sc.length(), CONFIGS.size)) { val ms = sc.getLong(i); if (ms in 1 until bestMs) { bestMs = ms; best = i } }
        if (best >= 0) p.edit().putString("chosen", CONFIGS[best].name).putString("chosenEnv", CONFIGS[best].env.entries.joinToString(";") { "${it.key}=${it.value}" }).commit()
    }

    /** True once after a restart of ours (and clears it). */
    fun takeRestarting(ctx: Context): Boolean {
        val p = prefs(ctx)
        val r = p.getBoolean("restarting", false)
        if (r) p.edit().putBoolean("restarting", false).commit()
        return r
    }

    private fun parse(s: String) = s.split(';').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    /** A fresh process (the backend's settings are read once per process). */
    fun restart(activity: Activity) {
        // not a crash: the next start's crash report skips this exit
        prefs(activity).edit().putBoolean("restarting", true).commit()
        activity.startActivity(Intent(activity, RestartActivity::class.java).putExtra("pid", Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Runs in its own process: ends the game's process and starts the game again (a clean start for the NPU backend). */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra("pid", 0)
        if (pid > 0) Process.killProcess(pid)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Runtime.getRuntime().exit(0)
    }
}
