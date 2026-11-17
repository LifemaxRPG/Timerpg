@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.lifemax.rpg.feature.habits.domain.WeatherOverride
import com.lifemax.rpg.feature.habits.presentation.HabitsUi
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage
import com.lifemax.rpg.feature.habits.presentation.scene.StageActions
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File

/**
 * The seam test (-Dmode=seam): the walk round the corner rendered frame by frame at 60 fps in one scene, exactly as the
 * game steps it, with the clock of the street's life frozen, and measured where a cut would show:
 *  - identity pairs: the street's own view and the turn's first frame (S0, T0); the turn's last frame and the new
 *    street's own view (Tn, S1). They must be the same picture.
 *  - motion pairs: one step either side of each end (pre/S0, T0/T1, Tn-1/Tn, S1/S1+1), against ordinary walking (W95:
 *    the 95th percentile of consecutive-frame difference over 120 frames of walking along the main street).
 *  - the whole sequence (-Dseq=1): no frame that differs from its neighbour far more than the frames around it (a pop),
 *    and the walk's length in frames against its length at walking pace.
 * -Dat=west|mid|east (the mouth's middle -60/0/+60), -Ddir=fwd|back|abort, -Dsize=preview|phone, -Dhour, -Dwx, -Dseason.
 * Writes seam/start_*.png, end_*.png (diffs x16), seq/<dir>_NNN.png, sheet_<dir>.png and seam.txt.
 */
fun renderSeam(out: String) {
    val dir = File(out, "seam").also { it.mkdirs() }
    val way = System.getProperty("dir") ?: "fwd"
    val seq = System.getProperty("seq") == "1"
    val (w, h, dens) = previewSize()
    val hour = previewHour(17.6f)
    WeatherOverride.forced = previewWeather()
    val cr = loadCrossroads() ?: error("street.json has no crossroads")
    StageTuning.crossroads = cr
    StageTuning.cornerIds = emptySet()
    GameState.keepers = (0 until 12).map { it to it * 7919 }
    GameState.keepersBy = mapOf(0 to GameState.keepers, 1 to (0 until 8).map { it to it * 104729 })
    val day = previewDay()
    val report = StringBuilder()
    fun line(s: String) { report.appendLine(s); println(s) }
    line("seam test: dir=$way at=${System.getProperty("at") ?: "mid"} size=${System.getProperty("size") ?: "preview"} hour=$hour wx=${System.getProperty("wx") ?: "clear"} season=${System.getProperty("season") ?: "summer"} seq=$seq")
    val scene = ImageComposeScene(width = kotlin.math.round(w * dens).toInt(), height = kotlin.math.round(h * dens).toInt(), density = Density(dens)) {
        HabitsStage(
            p = projection(hour, day), ui = HabitsUi(focus = cr.main.first().id), visible = true, top = 0f,
            bottom = 40f * dens, cover = 0f, inset = 0f, accent = Color(0xFF6FA8FF), actions = StageActions(), ambientAt = 100f,
        )
    }
    var nanos = 0L
    fun frame(): IntArray {
        nanos += 16_666_667L
        // the game loop's step for this frame can land after the scene has drawn it; drawing once more at the same time
        // (no time passes, so nothing moves) shows exactly the state this frame produced, as a vsync-driven screen does
        scene.render(nanos)
        Thread.sleep(2)
        return pixels(scene.render(nanos))
    }
    fun keys(x: Float, lane: Float) { GameState.moveX = x; GameState.moveLane = lane }
    // settle: the baked sprites are made off the main thread
    GameState.street = 0; GameState.x = 1500f; GameState.lane = 7f; GameState.dir = 1f; keys(0f, 0f)
    repeat(30) { nanos += 33_333_333L; Thread.sleep(60); scene.render(nanos) }
    // W95: ordinary walking along the main street, well clear of the mouth
    var last = frame()
    val mid = GameState.junctionX
    GameState.x = mid - 900f; GameState.lane = 7f
    repeat(5) { last = frame() }
    keys(1f, 0f)
    val walkMads = ArrayList<Double>()
    repeat(120) { val f = frame(); walkMads += stats(last, f).mad; last = f }
    keys(0f, 0f)
    val w95 = walkMads.sorted()[(walkMads.size * .95).toInt().coerceAtMost(walkMads.size - 1)]
    line("W95 (consecutive-frame MAD while walking, 95th percentile) = %.3f   median %.3f".format(w95, walkMads.sorted()[walkMads.size / 2]))
    // the start: standing in the mouth (or where a walk round the corner leaves the player, walking back)
    val at = when (System.getProperty("at")) { "west" -> -60f; "east" -> 60f; else -> 0f }
    GameState.vx = 0f
    GameState.street = if (way == "back") 1 else 0
    GameState.x = if (way == "back") crossOf().arriveX else mid + at
    GameState.lane = 7f; GameState.dir = 1f; GameState.facing = 0
    // let everything that eases from frame to frame (the sun's glare) settle, every frame drawn
    repeat(40) { GameState.travel += 1e-4f; last = frame() }
    // hold the key: the walk starts after it has been held a moment
    keys(0f, -1f)
    val frames = ArrayList<IntArray>()
    val ps = ArrayList<Float>()
    var pre: IntArray? = null
    var s0: IntArray? = null
    var t0: IntArray? = null
    var guard = 0
    while (!CornerWalk.active && guard++ < 60) {
        val f = frame()
        if (!CornerWalk.active) { pre = s0; s0 = f } else t0 = f
    }
    if (t0 == null) { line("FAIL: the walk did not start"); writeReport(dir, report); scene.close(); return }
    frames += pre!!; ps += -2f
    frames += s0!!; ps += -1f
    frames += t0; ps += CornerWalk.p
    var held = .0f
    var abortBack = false
    CornerWalk.holdEnd = true
    var tPrev: IntArray = t0
    var tn1: IntArray? = null
    var tn: IntArray? = null
    var steps = 0
    var t1: IntArray? = null
    while (CornerWalk.active && steps < 900) {
        held += 1f / 60f
        // as the game is played: the key held a moment, then let go (the walk carries on by itself)
        if (way == "abort") keys(0f, if (abortBack) 1f else if (held < .15f) -1f else 0f)
        else keys(0f, if (held < .15f) -1f else 0f)
        val f = frame()
        steps++
        if (t1 == null) t1 = f
        frames += f; ps += CornerWalk.p
        if (way == "abort" && CornerWalk.p >= .5f) abortBack = true
        if (CornerWalk.p >= 1f) { tn1 = tPrev; tn = f; break }
        tPrev = f
    }
    // the hand-over: the next frame is the new street's own view
    CornerWalk.holdEnd = false
    keys(0f, 0f)
    val s1 = frame()
    frames += s1; ps += 2f
    val s2 = frame()
    frames += s2; ps += 3f
    val arrived = way != "abort"
    line("walk: ${steps} frames to ${if (arrived) "p=1" else "p=0 (walked back)"}; path %.1f units; at walking pace %.1f frames (+%.1f easing in)".format(
        CornerWalk.pathLength, CornerWalk.pathLength / GameState.SPEED * 60f, 60f / 16f))
    line("street now ${GameState.street}, x=%.1f lane=%.1f".format(GameState.x, GameState.lane))
    var fails = 0
    fun check(name: String, ok: Boolean, what: String) { if (!ok) fails++; line("${if (ok) "PASS" else "FAIL"}  $name  $what") }
    val sId = stats(s0, t0)
    check("identity S0/T0", sId.mad <= .10 && sId.p99 <= 1 && sId.share16 <= .0001 && sId.mad < 2.0, sId.toString())
    savePng(File(dir, "start_stage_$way.png"), s0, scene); savePng(File(dir, "start_turn_$way.png"), t0, scene); savePng(File(dir, "start_diff_$way.png"), diff(s0, t0), scene)
    if (tn != null) {
        val eId = stats(tn, s1)
        check("identity Tn/S1", eId.mad <= .10 && eId.p99 <= 1 && eId.share16 <= .0001 && eId.mad < 2.0, eId.toString())
        savePng(File(dir, "end_turn_$way.png"), tn, scene); savePng(File(dir, "end_stage_$way.png"), s1, scene); savePng(File(dir, "end_diff_$way.png"), diff(tn, s1), scene)
    } else check("identity Tn/S1", way == "abort", "(no end frame: walked back)")
    val lim = maxOf(1.0, 1.5 * w95)
    fun motion(name: String, a: IntArray?, b: IntArray?) { if (a != null && b != null) { val m = stats(a, b); check("motion $name", m.mad <= lim, m.toString() + "  (limit %.2f)".format(lim)) } }
    motion("pre/S0", pre, s0)
    motion("T0/T1", t0, t1)
    motion("Tn-1/Tn", tn1, tn)
    motion("S1/S1+1", s1, s2)
    // the whole sequence: consecutive differences, and pops against their neighbours
    val mads = (1 until frames.size).map { stats(frames[it - 1], frames[it]).mad }
    line("consecutive MAD: " + mads.joinToString(" ") { "%.2f".format(it) })
    var pops = 0
    for (i in mads.indices) {
        val near = (maxOf(0, i - 3)..minOf(mads.size - 1, i + 3)).filter { it != i }.map { mads[it] }.sorted()
        val med = if (near.isEmpty()) 0.0 else near[near.size / 2]
        if (mads[i] > 3 * med && mads[i] > .5) { pops++; line("POP at frame ${i + 1} (p=%.3f): MAD %.2f against a median %.2f".format(ps[i + 1], mads[i], med)) }
    }
    check("pops", pops == 0, "$pops")
    if (arrived) {
        val expect = CornerWalk.pathLength / GameState.SPEED * 60f + 60f / 16f
        check("duration", kotlin.math.abs(steps - expect) <= 2f + 1f, "%d frames, expected %.1f".format(steps, expect))
    }
    if (seq) {
        val sq = File(dir, "seq").also { it.mkdirs() }
        sq.listFiles()?.filter { it.name.startsWith(way) }?.forEach { it.delete() }
        for (i in frames.indices step 2) savePng(File(sq, "%s_%03d.png".format(way, i)), frames[i], scene)
        contactSheet(File(dir, "sheet_$way.png"), frames, scene)
    }
    line(if (fails == 0) "ALL PASS" else "$fails FAIL")
    writeReport(dir, report)
    scene.close()
    WeatherOverride.forced = null
}

private fun crossOf(): com.lifemax.rpg.feature.habits.domain.Crossroads {
    // the crossroads as the stage laid it out (the same layouts; see HabitsStage)
    val cs = StageTuning.crossroads!!
    fun lay(st: List<com.lifemax.rpg.feature.habits.domain.HabitStack>) = com.lifemax.rpg.feature.habits.domain.streetLayout(st.map { com.lifemax.rpg.feature.habits.domain.buildingLayout(it, true, ghost = false) })
    val main = lay(cs.main)
    val j = cs.main.indexOfFirst { it.id == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID }
    return com.lifemax.rpg.feature.habits.domain.Crossroads(main, j, lay(cs.cross), null)
}

private fun writeReport(dir: File, report: StringBuilder) {
    val tag = System.getProperty("tag")?.let { "_$it" } ?: ""
    File(dir, "seam$tag.txt").writeText(report.toString())
}

class Stats(val mad: Double, val p99: Int, val share16: Double, val max: Int) {
    override fun toString() = "MAD %.3f  p99 %d  >16: %.4f%%  max %d".format(mad, p99, share16 * 100, max)
}

/** Differences between two frames: mean absolute over the RGB channels, the 99th percentile, the share of pixels off by more than 16, the most. */
fun stats(a: IntArray, b: IntArray): Stats {
    val hist = LongArray(256)
    var sum = 0L
    var over = 0L
    var most = 0
    for (i in a.indices) {
        val x = a[i]; val y = b[i]
        val dr = kotlin.math.abs((x shr 16 and 255) - (y shr 16 and 255))
        val dg = kotlin.math.abs((x shr 8 and 255) - (y shr 8 and 255))
        val db = kotlin.math.abs((x and 255) - (y and 255))
        sum += dr + dg + db
        hist[dr]++; hist[dg]++; hist[db]++
        val m = maxOf(dr, dg, db)
        if (m > 16) over++
        if (m > most) most = m
    }
    val n = a.size * 3L
    var acc = 0L
    var p99 = 0
    for (v in 0..255) { acc += hist[v]; if (acc >= n * .99) { p99 = v; break } }
    return Stats(sum.toDouble() / n, p99, over.toDouble() / a.size, most)
}

fun pixels(img: Image): IntArray {
    val bm = Bitmap()
    bm.allocPixels(ImageInfo.makeN32Premul(img.width, img.height))
    img.readPixels(bm, 0, 0)
    val bytes = bm.readPixels()!!
    val out = IntArray(img.width * img.height)
    for (i in out.indices) {
        val b = bytes[i * 4].toInt() and 255; val g = bytes[i * 4 + 1].toInt() and 255; val r = bytes[i * 4 + 2].toInt() and 255
        out[i] = (r shl 16) or (g shl 8) or b
    }
    w0 = img.width; h0 = img.height
    return out
}

private var w0 = 0
private var h0 = 0

/** The difference of two frames, sixteen times as bright. */
fun diff(a: IntArray, b: IntArray): IntArray = IntArray(a.size) {
    val x = a[it]; val y = b[it]
    fun ch(s: Int) = (kotlin.math.abs((x shr s and 255) - (y shr s and 255)) * 16).coerceAtMost(255)
    (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun savePng(f: File, px: IntArray, @Suppress("UNUSED_PARAMETER") scene: Any?, w: Int = w0, h: Int = h0) {
    val bytes = ByteArray(px.size * 4)
    for (i in px.indices) {
        val c = px[i]
        bytes[i * 4] = (c and 255).toByte(); bytes[i * 4 + 1] = (c shr 8 and 255).toByte(); bytes[i * 4 + 2] = (c shr 16 and 255).toByte(); bytes[i * 4 + 3] = -1
    }
    val bm = Bitmap()
    bm.installPixels(ImageInfo.makeN32Premul(w, h), bytes, w * 4)
    f.parentFile.mkdirs()
    f.writeBytes(Image.makeFromBitmap(bm).encodeToData(EncodedImageFormat.PNG)!!.bytes)
}

/** Every few frames of the sequence, small, in a grid. */
fun contactSheet(f: File, frames: List<IntArray>, scene: Any?) {
    val pick = frames.indices.filter { it % maxOf(1, frames.size / 24) == 0 }.take(24)
    val tw = w0 / 4
    val th = h0 / 4
    val cols = 4
    val rows = (pick.size + cols - 1) / cols
    val out = IntArray(tw * cols * th * rows)
    for ((k, i) in pick.withIndex()) {
        val ox = (k % cols) * tw
        val oy = (k / cols) * th
        for (y in 0 until th) for (x in 0 until tw) out[(oy + y) * tw * cols + ox + x] = frames[i][(y * 4) * w0 + x * 4]
    }
    savePng(f, out, scene, tw * cols, th * rows)
}
