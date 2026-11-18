package com.timerpg.street

import android.content.Context
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.android.UiHelper
import com.lifemax.rpg.feature.habits.presentation.scene.Stage3D
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentStreet

/**
 * The renderer trial's switch (the AI panel): the classic street, or Filament drawing the street over the stage's sky.
 * Kept in the app's preferences; the classic is the default, and the game falls back to it if Filament fails to start.
 *
 * A native abort in Filament (a failed precondition, a driver crash) kills the process before any Kotlin code can turn
 * it off, so a start is marked in the preferences before Filament starts and cleared once it has drawn [GOOD_FRAMES]
 * frames: a mark still there at the next launch means that start died, and the game starts on the classic instead.
 */
object StreetRenderer {
    var filament by mutableStateOf(false)
        private set
    /** Why Filament is not drawing, if it failed (shown in the panel). */
    var error by mutableStateOf<String?>(null)
    /** The app is in the background (between onPause and onResume): Filament draws nothing. */
    @Volatile var paused = false
    const val GOOD_FRAMES = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("renderer", Context.MODE_PRIVATE)

    /** This start found the "starting" mark: the last run's Filament start died (told in that exit's crash report). */
    var lastStartDied = false
        private set

    fun load(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("starting", false)) {
            lastStartDied = true
            // the last start never drew its first frames: it died in native code
            p.edit().putBoolean("starting", false).commit()
            error = "Filament crashed while starting last time: back on the classic street"
            set(ctx, false)
            return
        }
        // Filament is the game's renderer (the classic is only a fallback)
        set(ctx, p.getBoolean("filament", true))
        setCamera(ctx, p.getString("camera", "medium")!!)
    }

    /** The camera's eye height preset (low, medium, high), as the owner picked it. */
    var camera by mutableStateOf("medium")
        private set

    fun setCamera(ctx: Context, name: String) {
        camera = name
        com.lifemax.rpg.feature.habits.presentation.scene3d.CameraRig.pick(name)
        prefs(ctx).edit().putString("camera", name).apply()
    }

    fun set(ctx: Context, on: Boolean) {
        if (on != filament) com.timerpg.street.shot.Crumbs.add("renderer: " + if (on) "Filament" else "classic")
        filament = on
        // the stage hands its frames over and draws only its sky under Filament
        Stage3D.listening = on
        StageTuning.skyOnly = on
        prefs(ctx).edit().putBoolean("filament", on).apply()
    }

    /** Marks a start (written at once, so it is on disk before a native crash could happen) or clears the mark. */
    fun starting(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("starting", on).commit() }

    fun failed(ctx: Context, why: String) { com.timerpg.street.shot.Crumbs.add("Filament failed: $why"); error = why; starting(ctx, false); set(ctx, false) }
}

/** The frame times of the renderer in use, for the AI panel's frame line. */
object FilamentStats {
    private val cpu = FloatArray(240)
    private val gpu = FloatArray(240)
    private var n = 0
    private var at = 0
    var loadMs = 0f
    var buildMs = 0f

    @Synchronized fun add(cpuMs: Float, gpuMs: Float) { cpu[at] = cpuMs; gpu[at] = gpuMs; at = (at + 1) % cpu.size; if (n < cpu.size) n++ }

    @Synchronized fun summary(): String {
        if (n < 10) return "Filament: measuring…"
        fun p(a: FloatArray, q: Int): Float { val s = a.copyOf(n).filter { it >= 0f }.sorted(); return if (s.isEmpty()) -1f else s[(s.size * q / 100).coerceAtMost(s.size - 1)] }
        val g50 = p(gpu, 50); val g95 = p(gpu, 95)
        return "Filament: CPU %.1f ms (p95 %.1f) · GPU %s · start %.0f ms, street %.0f ms".format(
            java.util.Locale.US, p(cpu, 50), p(cpu, 95),
            if (g50 < 0f) "n/a" else "%.1f ms (p95 %.1f)".format(java.util.Locale.US, g50, g95), loadMs, buildMs)
    }
}

/** Filament's layer over the stage (which then draws only its sky), under the HUD: a TextureView, so Compose draws on both sides. */
@Composable
fun FilamentLayer(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { tv -> tv.tag = FilamentHost(ctx, tv) } },
        modifier = modifier,
        onRelease = { tv -> (tv.tag as? FilamentHost)?.destroy() },
    )
}

/** Runs Filament on [view]: one frame each time the stage draws one (in its draw, so street and sky are the same frame). */
private class FilamentHost(private val ctx: Context, view: TextureView) {
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var street: FilamentStreet? = null
    private var swap: SwapChain? = null
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var w = 0
    private var h = 0
    private var alive = true
    private var good = 0
    /** The stage's frame last drawn, and whether the surface changed since (then it is drawn again). */
    private var lastN = -1L
    private var dirty = true
    private val history = arrayOf(Renderer.FrameInfo())
    private val onFrame: () -> Unit = { frame() }

    init {
        try {
            val t0 = System.nanoTime()
            StreetRenderer.starting(ctx, true)
            com.timerpg.street.shot.Crumbs.add("Filament start")
            Filament.init()
            // drawn to a window: GL's y-up clip space (the desktop's off-screen renders are the other way up)
            com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix.windowY = true
            val e = Engine.Builder().featureLevel(Engine.FeatureLevel.FEATURE_LEVEL_3).build()
            engine = e
            renderer = e.createRenderer().also { r ->
                r.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) }
                // the display's fastest refresh (the S25's 120 Hz), as the classic asks for
                r.frameRateOptions = Renderer.FrameRateOptions().apply { interval = 1 }
            }
            street = FilamentStreet(e, { n -> ctx.assets.open("filament/$n.filamat").use { it.readBytes() } })
            FilamentStats.loadMs = (System.nanoTime() - t0) / 1e6f
            ui.isOpaque = false
            ui.renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swap?.let { e.destroySwapChain(it) }
                    swap = e.createSwapChain(surface, ui.swapChainFlags or SwapChainFlags.CONFIG_TRANSPARENT)
                    dirty = true
                }
                override fun onDetachedFromSurface() {
                    swap?.let { e.destroySwapChain(it); e.flushAndWait() }
                    swap = null
                }
                override fun onResized(width: Int, height: Int) { w = width; h = height; dirty = true }
            }
            ui.attachTo(view)
            Stage3D.onFrame = onFrame
        } catch (t: Throwable) {
            StreetRenderer.failed(ctx, "Filament did not start: ${t.message ?: t.javaClass.simpleName}")
            destroy()
        }
    }

    /** Draws the stage's frame just filled: nothing in the background, nothing when the stage drew nothing new. */
    private fun frame() {
        if (!alive || StreetRenderer.paused) return
        val e = engine ?: return
        val r = renderer ?: return
        val s = street ?: return
        val sc = swap ?: return
        val f = Stage3D.frame
        if (!f.ready || w == 0) return
        if (f.n == lastN && !dirty) return
        try {
            val t0 = System.nanoTime()
            s.update(f)
            if (s.buildMs > 0f) FilamentStats.buildMs = s.buildMs
            if (r.beginFrame(sc, System.nanoTime())) {
                r.render(s.view)
                r.endFrame()
            }
            lastN = f.n; dirty = false
            val cpu = (System.nanoTime() - t0) / 1e6f
            // the GPU's time for an earlier frame, where the driver reports it
            val got = r.getFrameInfoHistory(history)
            val g = if (got > 0 && history[0].gpuFrameDuration > 0) history[0].gpuFrameDuration / 1e6f else -1f
            FilamentStats.add(cpu, g)
            // it has drawn: the start did not die in native code
            if (++good == StreetRenderer.GOOD_FRAMES) { StreetRenderer.starting(ctx, false); com.timerpg.street.shot.Crumbs.add("Filament drawing") }
        } catch (t: Throwable) {
            StreetRenderer.failed(ctx, "Filament stopped: ${t.message ?: t.javaClass.simpleName}")
            destroy()
        }
    }

    fun destroy() {
        if (!alive) return
        alive = false
        com.timerpg.street.shot.Crumbs.add("Filament stop")
        if (Stage3D.onFrame === onFrame) Stage3D.onFrame = null
        // (the stage that filled it may be gone: a room; the next one fills it afresh)
        Stage3D.frame.clear()
        runCatching { ui.detach() }
        val e = engine ?: return
        street?.destroy(); street = null
        swap?.let { e.destroySwapChain(it) }; swap = null
        renderer?.let { e.destroyRenderer(it) }; renderer = null
        e.destroy(); engine = null
        // a clean stop before the first frames is no crash
        if (good < StreetRenderer.GOOD_FRAMES && StreetRenderer.error == null) StreetRenderer.starting(ctx, false)
    }
}
