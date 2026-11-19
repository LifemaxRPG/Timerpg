package com.timerpg.street.night

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timerpg.street.llm.MemoryWatch
import com.timerpg.street.llm.Ram
import java.util.Locale

/**
 * Writer mode: a screen of its own (and its own launcher icon, "Timerpg Writer") in the writer's process, with none of
 * the game in it (no town, no renderer, no voices, no speech or memory model), that writes the bank of lines with the
 * dialogue model ([NightSession] in [NightService], a foreground service, so it goes on with the screen off). Opening it
 * closes the game's process when that sits in the background (its memory goes to the model). It starts writing at once;
 * Pause holds the writing with the model kept loaded, Stop ends the session (its report is written). The screen can stay
 * on, dimmed after a while untouched; writing on battery is the owner's choice (else only plugged in). It stops by itself
 * when the game is opened.
 */
class WriterActivity : ComponentActivity() {
    private var lastTouch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        com.timerpg.street.AppInit.basics(applicationContext)
        com.timerpg.street.shot.Crumbs.add("writer mode: opened")
        super.onCreate(savedInstanceState)
        MemoryWatch.start(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        // writing starts at once (a fresh open, not a screen rotation); a session already running here is taken over
        if (savedInstanceState == null) start()
        lastTouch = android.os.SystemClock.elapsedRealtime()
        setContent { Screen() }
    }

    private fun start() {
        if (!NightWriter.startService(this, writer = true)) com.timerpg.street.shot.Crumbs.add("writer mode: the service could not start")
    }

    /** The screen kept on while it writes (the owner's choice), dimmed once untouched for [DIM_AFTER_MS]. */
    private fun screen(running: Boolean, keepOn: Boolean) {
        val w = window
        if (running && keepOn) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dim = running && keepOn && android.os.SystemClock.elapsedRealtime() - lastTouch > DIM_AFTER_MS
        val want = if (dim) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (w.attributes.screenBrightness != want) w.attributes = w.attributes.apply { screenBrightness = want }
    }

    @Composable
    private fun Screen() {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
        val ctx = this
        val session = remember(tick) { NightWriter.session }
        val v = remember(tick) { session?.view?.value ?: NightWriter.lastView }
        var keepOn by remember { mutableStateOf(NightWriter.writerScreenOn(ctx)) }
        var onBattery by remember { mutableStateOf(NightWriter.writerOnBattery(ctx)) }
        val running = session != null && v?.running == true
        remember(tick, keepOn, running) { screen(running, keepOn); 0 }
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0B0E16))
                .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch = android.os.SystemClock.elapsedRealtime() } } }
                .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            T("Timerpg Writer", 20.sp)
            T(v?.phase ?: "Not writing", 15.sp, color = if (running) Color(0xFF9FD3FF) else DIM)
            v?.ended?.let { T(it, 13.sp, color = DIM) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !running -> Chip("Start", true) { start() }
                    session?.paused == true -> Chip("Resume", true) { session.setPaused(false) }
                    else -> Chip("Pause", false) { session?.setPaused(true) }
                }
                if (running) Chip("Stop", false) { session?.stop("stopped in Writer mode") }
                Chip("Open the game", false) {
                    // (the writer stops as the game opens: it saves its lines and lets the model go first)
                    session?.stop(NightWriter.GAME_OPENED)
                    startActivity(Intent().setClassName(packageName, "com.timerpg.street.GameActivity")
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Keep screen on (dimmed): " + if (keepOn) "on" else "off", keepOn) { keepOn = !keepOn; NightWriter.setWriterScreenOn(ctx, keepOn); lastTouch = android.os.SystemClock.elapsedRealtime() }
                Chip("Write on battery too: " + if (onBattery) "yes" else "only plugged in", onBattery) { onBattery = !onBattery; NightWriter.setWriterOnBattery(ctx, onBattery) }
            }
            if (!remember(tick / 10) { NightWriter.unrestricted(ctx) })
                Chip("Let it run in the background (battery: Unrestricted)", false) { NightWriter.askUnrestricted(ctx) }

            Spacer(Modifier.height(6.dp))
            T("Model", 15.sp)
            val m = v?.memory ?: NightReport.Memory()
            T(when {
                v == null -> "Not loaded"
                v.model.isNotEmpty() -> v.model + if (v.model.startsWith("light")) " (the main model's NPU copy does not fit; its lines are rewritten by the main model later)" else ""
                else -> v.modelDetail.ifEmpty { "Not loaded" }
            }, 13.sp)
            val free = MemoryWatch.freeMb.takeIf { it >= 0 } ?: remember(tick / 5) { Ram.freeMb(ctx) }
            T("Free memory now $free MB (Android's threshold ${MemoryWatch.thresholdMb} MB)" +
                (if (m.freeBeforeLoadMb >= 0) " · before the load ${m.freeBeforeLoadMb} MB" else "") +
                (if (m.freeAfterLoadMb >= 0) " · after ${m.freeAfterLoadMb} MB" else "") +
                (if (m.freedByGameMb >= 0) " · closing the game gave ${m.freedByGameMb} MB" else ""), 12.sp, color = DIM)
            if (m.mainNeedMb > 0 || m.lightNeedMb > 0)
                T("The NPU copy needs ${m.mainNeedMb} MB free (main model), ${m.lightNeedMb} MB (light model), with room for Android", 12.sp, color = DIM)

            if (v != null) {
                Spacer(Modifier.height(6.dp))
                T("Written", 15.sp)
                val c = v.count
                T("This session: ${c.lines} lines + ${c.variants} variants (${c.rejected} turned down by the check) · ${f1(v.linesPerHour)} lines/hour", 13.sp)
                T("Bank: ${v.bankReady} of ${v.bankPrompts} lines ready, ${v.bankLines} with variants" +
                    (if (v.bankLight > 0) " (${v.bankLight} by the light model)" else ""), 12.sp, color = DIM)
                T("Prefill ${f1(c.prefillPerSec)} tok/s · generation ${f1(c.genPerSec)} tok/s · writing ${WriterLoop.duration(v.writingMs)} of ${WriterLoop.duration(v.elapsedMs)}", 12.sp, color = DIM)
                v.progress?.let { T("Now: $it", 12.sp, color = DIM) }
                v.working?.let { T("Writing: $it", 12.sp, color = DIM) }
                T(when {
                    v.left == 0 -> "The bank is complete"
                    v.left < 0 -> "Lines left: counting…"
                    else -> "Lines left: ${v.left}" + (v.etaMs?.let { " · about ${WriterLoop.duration(it)} at this pace" } ?: " · time to finish: once a few lines are written")
                }, 13.sp)

                v.queue?.let { q ->
                    Spacer(Modifier.height(6.dp))
                    T("Queue", 15.sp)
                    T("By year: " + q.byYear.joinToString(" · ") { "${it.first} ${it.second}" }, 12.sp, color = DIM)
                    T("By person: " + q.byPerson.joinToString(" · ") { "${it.first} ${it.second}" }, 12.sp, color = DIM)
                    T("By topic: " + q.byTopic.joinToString(" · ") { "${it.first} ${it.second}" }, 12.sp, color = DIM)
                }
                if (v.recent.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    T("Last lines", 15.sp)
                    for (w in v.recent.asReversed()) T("${w.who} (${w.topic}${if (w.how != "line") ", ${w.how}" else ""}): " +
                        (w.text?.let { "“$it”" } ?: "(steered by your own words)"), 12.sp, color = DIM)
                }

                Spacer(Modifier.height(6.dp))
                T("Phone", 15.sp)
                T("Heat: ${thermalName(v.thermal)}" + (if (!v.headroom.isNaN()) " · headroom ${String.format(Locale.US, "%.2f", v.headroom)} (1.0 = throttling)" else "") +
                    " · hottest this session: ${thermalName(v.maxThermal)}", 12.sp, color = DIM)
                T("Battery ${v.battery}% · " + when { v.charging -> "charging"; v.plugged -> "plugged in, not charging"; else -> "on battery" }, 12.sp, color = DIM)
            }
            Spacer(Modifier.height(6.dp))
            T("Heat is handled by pacing: it writes straight away when cool, in short bursts with longer rests when warm, and pauses when hot. " +
                "Opening the game stops Writer mode (it saves its lines first).", 11.sp, color = DIM)
            NightWriter.lastSummary(ctx)?.let { T("Last run: $it", 11.sp, color = DIM) }
        }
    }

    companion object {
        private val DIM = Color(0xFFB8C2D6)
        private const val DIM_AFTER_MS = 20_000L
        private const val DIM_BRIGHTNESS = 0.02f

        /** Opens Writer mode from the game: the game's screen closes (its process is closed by the writer for the room). */
        fun open(activity: Activity) {
            activity.startActivity(Intent(activity, WriterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity.finish()
        }

        fun thermalName(t: Int) = when (t) {
            0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"; else -> "not known"
        }

        private fun f1(v: Number) = String.format(Locale.US, "%.1f", v.toDouble())
    }
}

@Composable
private fun T(s: String, size: TextUnit, color: Color = Color.White) =
    androidx.compose.foundation.text.BasicText(s, style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.SemiBold))

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier.background(if (on) Color(0xCC3A6FD8) else Color(0x99202840), RoundedCornerShape(10.dp)).pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { click() } }
        }.padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, 13.sp) }
}
