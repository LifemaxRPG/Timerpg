package com.timerpg.street.play

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.hud.HudSection
import com.lifemax.rpg.feature.habits.hud.HudStyle
import com.lifemax.rpg.feature.habits.hud.HudText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The battery's current draw, averaged over the last [WINDOW_MS], with whether the dialogue model is loaded and the NPU's
 * power vote: what the S25 measurement of the vote per burst needs (docs/CONVERSATION.md, "The NPU's power vote": idle
 * current with the light model loaded, burst for the whole process vs auto vs never loaded). Sampled by the probes
 * (every few seconds) and by the Debug tab while it is open (every second). BatteryManager's CURRENT_NOW in microamps (as
 * [Probes] reads it; Samsung's is positive while charging, negative while it discharges), CURRENT_AVERAGE where the
 * phone gives one.
 */
object BatteryDraw {
    const val WINDOW_MS = 10_000L

    class Reading(
        /** The current now, its average over the window (mA; null: the phone does not say), samples in the window. */
        val nowMa: Int?, val avgMa: Int?, val samples: Int,
        /** The battery driver's own average (CURRENT_AVERAGE, mA), null where not supported. */
        val sysAvgMa: Int?,
        /** The model loaded ("live on NPU", "bank on CPU"; "none"), and the NPU's vote (NativeLlm.npuPower). */
        val model: String, val npu: String,
    )

    private val times = LongArray(64)
    private val values = IntArray(64)
    private var head = 0
    private var count = 0

    /** Reads the current now, adds it to the window and says the lot. Off the main thread (the vote is a native call). */
    fun sample(ctx: Context): Reading {
        val bm = runCatching { ctx.getSystemService(BatteryManager::class.java) }.getOrNull()
        val ua = bm?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull() }?.takeIf { it != Int.MIN_VALUE && it != 0 }
        val avg = bm?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }.getOrNull() }?.takeIf { it != Int.MIN_VALUE && it != 0 }
        val now = SystemClock.elapsedRealtime()
        val (mean, n) = synchronized(this) {
            if (ua != null) {
                times[head] = now; values[head] = ua
                head = (head + 1) % times.size
                if (count < times.size) count++
            }
            var sum = 0L; var k = 0
            for (i in 0 until count) {
                val j = (head - 1 - i + times.size) % times.size
                if (now - times[j] > WINDOW_MS) break
                sum += values[j]; k++
            }
            (if (k > 0) (sum / k / 1000).toInt() else null) to k
        }
        return Reading(ua?.div(1000), mean, n, avg?.div(1000), model(ctx), com.timerpg.street.llm.NativeLlm.npuPower())
    }

    private fun model(ctx: Context): String = runCatching {
        val s = com.timerpg.street.dialogue.DialogueEngine.shared(ctx.applicationContext).status.value
        val role = s.role ?: return@runCatching "none"
        val dev = when (s.runsOn) { com.timerpg.street.llm.NativeLlm.NPU -> "NPU"; com.timerpg.street.llm.NativeLlm.GPU -> "GPU"; else -> "CPU" }
        "${role.name.lowercase()} on $dev"
    }.getOrDefault("?")

    /** One line for the Debug tab. */
    fun line(r: Reading): String = buildString {
        append("Battery: ").append(r.nowMa?.let { "$it mA now" } ?: "current not readable")
        r.avgMa?.let { append(", $it mA over ${WINDOW_MS / 1000} s (${r.samples} samples)") }
        r.sysAvgMa?.let { append(", driver's average $it mA") }
        append(". Model: ").append(r.model).append(". NPU vote: ").append(r.npu)
    }
}

/** The Debug tab's power rows: [BatteryDraw] every second while the tab is open. */
@Composable
fun BatteryDebugRows() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("Battery: reading...") }
    LaunchedEffect(Unit) {
        while (true) {
            text = withContext(Dispatchers.Default) { runCatching { BatteryDraw.line(BatteryDraw.sample(ctx)) }.getOrDefault("Battery: not readable") }
            delay(1000)
        }
    }
    HudSection("Power")
    HudText(text, 12.sp, HudStyle.DIM, maxLines = 3)
}
