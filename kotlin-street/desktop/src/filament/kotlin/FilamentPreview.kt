@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.lifemax.rpg.feature.habits.domain.WeatherOverride
import com.lifemax.rpg.feature.habits.presentation.HabitsUi
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.HabitsStage
import com.lifemax.rpg.feature.habits.presentation.scene.Stage3D
import com.lifemax.rpg.feature.habits.presentation.scene.StageActions
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentStreet
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Filament trial's desktop renders (-Dmode=filament, or filament-albedo for the sprites' own colours unlit): the same
 * frame of the crossroads drawn by the classic street and by Filament (headless, Mesa's software GL), Filament over the
 * stage's own sky. Writes filament/filament_<tag>.png and compare_<tag>.png (classic above, Filament below) for Main
 * Street at rest by day, at dusk and at night (-Dhour= for one hour only), and the walk round the corner at 0, 25, 50, 75
 * and 100% (-Dturn=0 to leave it out), and filament.txt with the frame times on this machine (software GL: relative only).
 * -Dsize=phone (default here) is the S25's 2340x1080.
 */
fun renderFilament(out: String, albedo: Boolean) {
    if (System.getProperty("size") == null) System.setProperty("size", "phone")
    // (-Deye=low|medium|high|<height>: that camera's renders in their own folder; -Dlook=classic: the classic's looks)
    val sub = when {
        com.lifemax.rpg.feature.habits.presentation.scene3d.CameraRig.classic -> "filament/look_classic"
        System.getProperty("eye") != null || System.getProperty("back") != null -> "filament/eye_" + com.lifemax.rpg.feature.habits.presentation.scene3d.CameraRig.label
        else -> "filament"
    }
    val dir = File(out, sub).also { it.mkdirs() }
    val (wd, hd, dens) = previewSize()
    val w = kotlin.math.round(wd * dens).toInt(); val h = kotlin.math.round(hd * dens).toInt()
    val report = StringBuilder()
    fun line(s: String) { report.appendLine(s); println(s) }
    WeatherOverride.forced = previewWeather()
    val cr = loadCrossroads() ?: error("street.json has no crossroads")
    StageTuning.crossroads = cr
    StageTuning.cornerIds = emptySet()
    GameState.keepers = (0 until 12).map { it to it * 7919 }
    GameState.keepersBy = mapOf(0 to GameState.keepers, 1 to (0 until 8).map { it to it * 104729 })
    Stage3D.listening = true

    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val swap: SwapChain = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
    val renderer = engine.createRenderer()
    renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) }
    val assets = File(assetsDir(), "filament")
    val tLoad = System.nanoTime()
    val street = FilamentStreet(engine, { n -> File(assets, "$n.filamat").readBytes() }, albedo = albedo, shadowMap = 4096)
    line("filament: engine + materials %.0f ms (llvmpipe, this machine)".format((System.nanoTime() - tLoad) / 1e6))
    val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    val times = ArrayList<Double>()

    fun drawFilament(): IntArray {
        val t0 = System.nanoTime()
        street.update(Stage3D.frame)
        if (street.buildMs > 0f) line("  built the street in %.0f ms".format(street.buildMs))
        val done = AtomicBoolean(false)
        for (frame in 0..1) {
            if (renderer.beginFrame(swap, 0L)) {
                renderer.render(street.view)
                if (frame == 1) renderer.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, w, Executor { it.run() }, Runnable { done.set(true) }))
                renderer.endFrame()
            }
            engine.flushAndWait()
        }
        var guard = 0
        while (!done.get() && guard++ < 200) { engine.flushAndWait(); Thread.sleep(5) }
        times += (System.nanoTime() - t0) / 1e6 / 2.0
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((h - 1 - y) * w + x) * 4
            val r = pixels.get(i).toInt() and 255; val g = pixels.get(i + 1).toInt() and 255; val b = pixels.get(i + 2).toInt() and 255; val a = pixels.get(i + 3).toInt() and 255
            px[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    // Filament's picture (premultiplied) over the stage's sky
    fun over(fil: IntArray, sky: IntArray): IntArray = IntArray(fil.size) {
        val f = fil[it]; val s = sky[it]; val a = f ushr 24
        fun ch(k: Int) = (((f shr k) and 255) + ((s shr k) and 255) * (255 - a) / 255).coerceAtMost(255)
        (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    var nanos = 0L
    fun shot(tag: String, scene: ImageComposeScene, @Suppress("UNUSED_PARAMETER") at: Long) {
        // (a still street redraws only when the player changes, and skyOnly is no snapshot state: without a redraw the
        // "sky" under Filament was the classic's whole street, showing through wherever Filament left the picture
        // open. So each picture is drawn by turning the player away for a frame and back for the next)
        fun redrawn(): IntArray {
            val facing = GameState.facing
            GameState.facing = (facing + 1) % 4
            nanos += 33_333_333L; Thread.sleep(40); scene.render(nanos)
            GameState.facing = facing
            nanos += 33_333_333L; Thread.sleep(40)
            return pixels(scene.render(nanos))
        }
        StageTuning.skyOnly = false
        val tc = System.nanoTime()
        val classic = redrawn()
        val classicMs = (System.nanoTime() - tc) / 1e6 / 2.0
        StageTuning.skyOnly = true
        val sky = redrawn()
        StageTuning.skyOnly = false
        val fil = over(drawFilament(), sky)
        savePng(File(dir, "filament_$tag.png"), fil, null, w, h)
        if (System.getProperty("sky") == "1") savePng(File(dir, "sky_$tag.png"), sky, null, w, h)
        savePng(File(dir, "compare_$tag.png"), classic + fil, null, w, h * 2)
        val st = stats(classic, fil)
        com.lifemax.rpg.feature.habits.presentation.scene3d.CameraRig.let { r ->
            if (!r.classic) line("  camera: eye %.0f back %.0f, %.0f deg across, horizon at y %.0f, yaw %.1f deg".format(r.eye, r.backNow, r.fovX(w.toFloat()), r.view.cy, Math.toDegrees(r.view.yaw.toDouble())))
        }
        Stage3D.frame.light?.let { k -> line("  light: dark %.2f across %.2f down %.2f out %.2f strength %.2f cool %.2f".format(Stage3D.frame.dark, k.across, k.down, k.out, k.strength, k.cool)) }
        line("%-14s classic (Skia, CPU) %.0f ms/frame   filament (llvmpipe) %.0f ms/frame   diff %s".format(tag, classicMs, times.last(), st))
    }

    fun newScene(hour: Float): ImageComposeScene = ImageComposeScene(width = w, height = h, density = Density(dens)) {
        HabitsStage(
            p = projection(hour, previewDay()), ui = HabitsUi(focus = cr.main.first().id), visible = true, top = 0f,
            bottom = 40f * dens, cover = 0f, inset = 0f, accent = Color(0xFF6FA8FF), actions = StageActions(), ambientAt = 100f,
        )
    }

    fun settle(scene: ImageComposeScene, n: Int) { repeat(n) { nanos += 33_333_333L; Thread.sleep(40); scene.render(nanos) } }

    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 19.3f, 22.5f)
    for (hour in if (System.getProperty("rest") == "0") emptyList() else hours) {
        val scene = newScene(hour)
        GameState.street = 0; GameState.x = 1500f; GameState.lane = 7f; GameState.dir = 1f
        GameState.moveX = 0f; GameState.moveLane = 0f
        settle(scene, 20)
        val mid = GameState.junctionX
        val name = "%.1f".format(hour).replace(".0", "")
        // the mouth of the side street, and further west along Main Street
        GameState.x = mid; settle(scene, 12)
        shot("${name}h_mouth", scene, nanos)
        GameState.x = mid - 700f; settle(scene, 12)
        shot("${name}h_west", scene, nanos)
        // the same frame again, for a warm time
        shot("${name}h_west_again", scene, nanos)
        scene.close()
    }
    if (System.getProperty("turn") != "0") {
        val hour = hours.first()
        val scene = newScene(hour)
        GameState.street = 0; GameState.x = 1500f; GameState.lane = 7f; GameState.dir = 1f; GameState.facing = 0
        settle(scene, 20)
        GameState.x = GameState.junctionX; GameState.vx = 0f
        settle(scene, 12)
        val targets = mutableListOf(0f, .25f, .5f, .75f, 1f)
        GameState.moveX = 0f; GameState.moveLane = -.7f
        CornerWalk.holdEnd = true
        var held = 0f
        var guard = 0
        while (targets.isNotEmpty() && guard++ < 900) {
            nanos += 16_666_667L
            scene.render(nanos); Thread.sleep(2)
            held += 1f / 60f
            if (held > .15f) GameState.moveLane = 0f
            if (!CornerWalk.active) continue
            while (targets.isNotEmpty() && CornerWalk.p >= targets[0]) {
                shot("turn_%03d".format((targets.removeAt(0) * 100).toInt()), scene, nanos)
            }
        }
        CornerWalk.holdEnd = false
        GameState.moveLane = 0f
        scene.close()
    }
    val warm = times.drop(1).sorted()
    if (warm.isNotEmpty()) line("filament frame (update + render + read back, llvmpipe on 4 cores): median %.0f ms, min %.0f, max %.0f over %d frames".format(warm[warm.size / 2], warm.first(), warm.last(), warm.size))
    File(dir, "filament.txt").writeText(report.toString())
    Stage3D.listening = false
    street.destroy()
    engine.destroyRenderer(renderer)
    engine.destroySwapChain(swap)
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.release(engine)
    engine.destroy()
    WeatherOverride.forced = null
}
