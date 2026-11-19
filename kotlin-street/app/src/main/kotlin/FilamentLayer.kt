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
    /** This start found the "townBuilding" mark: the last run died while building the town. */
    var lastTownBuildDied = false
        private set

    /**
     * Marks the town's build under way (written at once, before a native crash could happen in it) or over: a mark still
     * there at the next launch means the build died, and the game starts on the Filament crossroads instead of the town.
     */
    fun townBuilding(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("townBuilding", on).commit() }

    /** The town failed (an exception in its build or its frame): the crossroads on Filament, not the classic street. */
    fun townFailed(ctx: Context, why: String) {
        com.timerpg.street.shot.Crumbs.add("town failed: $why")
        error = why
        townBuilding(ctx, false)
        setTown(ctx, CROSSROADS)
    }

    fun load(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("townBuilding", false)) {
            // the last run died while the town was being built (the ground, the buildings: [townBuilding]): Filament
            // itself drew, so it stays, on the old crossroads until the town is chosen again in the AI panel
            lastTownBuildDied = true
            p.edit().putBoolean("townBuilding", false).putBoolean("starting", false).putString("town", CROSSROADS).commit()
            error = "The town crashed while it was being built last time: on the crossroads (Filament) instead"
        }
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
        town = p.getString("town", TownSession.DEFAULT) ?: TownSession.DEFAULT
        setView(ctx, p.getString("view", OPEN) ?: OPEN)
        setGrab(ctx, p.getBoolean("grabWorld", false))
        setBikeFollow(ctx, p.getBoolean("bikeFollow", true))
        setResolution(ctx, p.getFloat("resolution", com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.RES_AUTO))
    }

    /** The open world's drawing resolution (the AI panel's "Resolution"): see TownQuality.resolution. */
    var resolution by mutableStateOf(com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.RES_AUTO)
        private set

    fun setResolution(ctx: Context, r: Float) {
        if (r != resolution) com.timerpg.street.shot.Crumbs.add("resolution: " + if (r == 0f) "auto" else "%.2f".format(java.util.Locale.US, r))
        resolution = r
        com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.resolution = r
        prefs(ctx).edit().putFloat("resolution", r).apply()
    }

    /** The open world's camera on the bike (the AI panel's "On the bike"): true (the default) swings round behind the rider. */
    var bikeFollow by mutableStateOf(true)
        private set

    fun setBikeFollow(ctx: Context, on: Boolean) {
        if (on != bikeFollow) com.timerpg.street.shot.Crumbs.add("bike camera: " + if (on) "follows" else "stays")
        bikeFollow = on
        com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.bikeFollow = on
        prefs(ctx).edit().putBoolean("bikeFollow", on).apply()
    }

    /** The open world's drag (the AI panel's "Drag"): false turns the camera the way the finger goes, true moves the world with it. */
    var grabWorld by mutableStateOf(false)
        private set

    fun setGrab(ctx: Context, on: Boolean) {
        grabWorld = on
        com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.grabWorld = on
        prefs(ctx).edit().putBoolean("grabWorld", on).apply()
    }

    /**
     * How the town is seen (the AI panel's "View"): the open world ([OPEN], the default: the player turns the camera round
     * themselves, [com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera]) or the old street view ([STREET]).
     */
    var view by mutableStateOf(OPEN)
        private set
    const val OPEN = "open"
    const val STREET = "street"

    fun setView(ctx: Context, name: String) {
        val v = if (name == STREET) STREET else OPEN
        if (v != view) com.timerpg.street.shot.Crumbs.add("view: $v")
        view = v
        com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.open = v == OPEN
        prefs(ctx).edit().putString("view", v).apply()
    }

    /**
     * Which place the game plays (the AI panel's switch): the walkable town ([TownSession.DEFAULT], gamma) or the old
     * eta crossroads ([CROSSROADS], kept for comparison). The town needs Filament: on the classic renderer it is the
     * crossroads whatever this says.
     */
    var town by mutableStateOf(TownSession.DEFAULT)
        private set
    const val CROSSROADS = "crossroads"

    /** The walkable town is being played (Filament on and the town chosen). */
    val townMode: Boolean get() = filament && town != CROSSROADS

    fun setTown(ctx: Context, name: String) {
        if (name != town) com.timerpg.street.shot.Crumbs.add("town: $name")
        town = name
        prefs(ctx).edit().putString("town", name).apply()
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

    /** The town's whole build, once over (breadcrumbs and the snapshot); the memory as last read ([measureMemory]). */
    @Volatile var townBuilt: String? = null
    @Volatile var memory: Map<String, Any?>? = null

    /**
     * Reads the process's memory (in the background: it takes a while) into [memory] and the breadcrumbs: graphics (the
     * GPU's textures and buffers as the driver reports them), native and Java heaps, the total; [why] says when.
     */
    fun measureMemory(why: String) {
        Thread({
            runCatching {
                val mi = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(mi)
                fun mb(k: String) = (mi.getMemoryStat(k)?.toIntOrNull() ?: -1024) / 1024
                val m = linkedMapOf<String, Any?>("when" to why, "graphicsMb" to mb("summary.graphics"), "nativeMb" to mb("summary.native-heap"),
                    "javaMb" to mb("summary.java-heap"), "codeMb" to mb("summary.code"), "totalPssMb" to mb("summary.total-pss"))
                memory = m
                com.timerpg.street.shot.Crumbs.add("memory ($why): " + m.entries.drop(1).joinToString { "${it.key} ${it.value}" })
            }
        }, "memory").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    @Synchronized fun add(cpuMs: Float, gpuMs: Float) {
        cpu[at] = cpuMs; gpu[at] = gpuMs; at = (at + 1) % cpu.size; if (n < cpu.size) n++
        if (winCpu.size < 4096) { winCpu += cpuMs; if (gpuMs >= 0f) winGpu += gpuMs }
    }

    /** Filament's frames since the last call (the breadcrumbs' 10 s line): CPU and GPU p50, p95 and the longest. */
    private val winCpu = ArrayList<Float>()
    private val winGpu = ArrayList<Float>()
    @Synchronized fun window(): String {
        fun q(a: List<Float>): String { val s = a.sorted(); return if (s.isEmpty()) "n/a" else "%.1f/%.1f/%.1f".format(java.util.Locale.US, s[s.size / 2], s[(s.size * 95 / 100).coerceAtMost(s.size - 1)], s.last()) }
        val line = "Filament ${winCpu.size} frames, CPU ${q(winCpu)} ms, GPU ${q(winGpu)} ms (p50/p95/max)"
        winCpu.clear(); winGpu.clear()
        return line
    }

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
fun FilamentLayer(modifier: Modifier = Modifier, town: Boolean = false) {
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { tv -> tv.tag = FilamentHost(ctx, tv, town) } },
        modifier = modifier,
        onRelease = { tv -> (tv.tag as? FilamentHost)?.destroy() },
    )
}

/** Runs Filament on [view]: one frame each time the stage draws one (in its draw, so street and sky are the same frame). */
private class FilamentHost(private val ctx: Context, view: TextureView, private val townMode: Boolean) {
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var street: FilamentStreet? = null
    /** The walkable town (town mode): made once its data is loaded ([TownSession]), then built a little each frame. */
    private var town: com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown? = null
    private var run: com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun? = null
    private val mats: (String) -> ByteArray = { n -> ctx.assets.open("filament/$n.filamat").use { it.readBytes() } }
    private var swap: SwapChain? = null
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var w = 0
    private var h = 0
    private var alive = true
    private var good = 0
    /** The stage's frame last drawn, and whether the surface changed since (then it is drawn again). */
    private var lastN = -1L
    private var dirty = true
    private val history = Array(4) { Renderer.FrameInfo() }
    private val onFrame: () -> Unit = { frame() }

    init {
        // (the stage puts off its own heavy art until the town is built: from now, as the town's data loads first)
        if (townMode) Stage3D.townBuilding = true
    }

    init {
        try {
            val t0 = System.nanoTime()
            StreetRenderer.starting(ctx, true)
            com.timerpg.street.shot.Crumbs.add("Filament start")
            Filament.init()
            // drawn to a window: GL's y-up clip space (the desktop's off-screen renders are the other way up)
            com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix.windowY = true
            // (arenas sized for the whole town's thousands of handles and its 1,000+ draws a frame: TownEngine)
            val e = Engine.Builder().featureLevel(Engine.FeatureLevel.FEATURE_LEVEL_3)
                .config(com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine.config()).build()
            engine = e
            renderer = e.createRenderer().also { r ->
                r.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) }
                // the display's fastest refresh (the S25's 120 Hz), as the classic asks for; the automatic resolution
                // (TownQuality.resolution) aims at a steady 60 a second: with frames 16.7 ms apart the GPU's budget
                r.frameRateOptions = Renderer.FrameRateOptions().apply { interval = 1 }
                runCatching {
                    @Suppress("DEPRECATION") val hz = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
                    r.displayInfo = Renderer.DisplayInfo().apply { refreshRate = hz }
                    if (hz > 90f) r.frameRateOptions = Renderer.FrameRateOptions().apply { interval = 2 }
                }
            }
            if (!townMode) street = FilamentStreet(e, mats)
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
        val s = street
        if (s == null && !townMode) return
        val sc = swap ?: return
        val f = Stage3D.frame
        if (!f.ready || w == 0) return
        // (a town frame for the crossroads' layer, or the other way round: the switch between them is under way)
        if (f.town != townMode) return
        if (f.n == lastN && !dirty) return
        try {
            val t0 = System.nanoTime()
            val view = if (s != null) {
                s.update(f)
                if (s.buildMs > 0f) FilamentStats.buildMs = s.buildMs
                s.view
            } else townFrame(e, f) ?: return
            val r0 = System.nanoTime()
            if (r.beginFrame(sc, System.nanoTime())) {
                r.render(view)
                r.endFrame()
            }
            com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.add(com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.RENDER, System.nanoTime() - r0)
            lastN = f.n; dirty = false
            val cpu = (System.nanoTime() - t0) / 1e6f
            // the GPU's time for an earlier frame, where the driver reports it
            val got = r.getFrameInfoHistory(history)
            val g = if (got > 0 && history[0].gpuFrameDuration > 0) history[0].gpuFrameDuration / 1e6f else -1f
            FilamentStats.add(cpu, g)
            // the frame-spike profiler: this frame's parts, the GPU's frames, the collections (the town only)
            if (townMode) {
                val gs = com.lifemax.rpg.feature.habits.presentation.scene.GameState
                val oc = com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
                FrameSpikes.frame(gs.inMotion || oc.moving, history, got)
            } else com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.reset()
            // it has drawn: the start did not die in native code (the town's: once its first view is built, behind the
            // loading screen; the rest of its build has a mark of its own, townBuilding)
            if ((!townMode || TownSession.ready) && ++good == StreetRenderer.GOOD_FRAMES) {
                StreetRenderer.starting(ctx, false); com.timerpg.street.shot.Crumbs.add("Filament drawing")
            }
        } catch (t: Throwable) {
            // a report with the whole stack (it waits on the phone until a token can send it), and the real reason shown
            if (townMode) com.timerpg.street.shot.Crumbs.add("town memory at the failure: " + com.lifemax.rpg.feature.habits.presentation.scene3d.TownMemory.summary())
            com.timerpg.street.shot.Reports.writeCaught(ctx, if (townMode) "town" else "filament", t)
            val why = com.timerpg.street.shot.Reports.explain(t)
            if (townMode) StreetRenderer.townFailed(ctx, "The town stopped: $why")
            else StreetRenderer.failed(ctx, "Filament stopped: $why")
            destroy()
        }
    }

    /**
     * The town's frame: made once [TownSession] has loaded the data (on the engine's thread, here), then built nearest the
     * player first in a budget a frame (more while the loading screen covers it), drawn through the true camera. Null while
     * there is nothing to draw yet.
     */
    /** The town's farms (TownFarms), with the town. */
    private var farms: com.lifemax.rpg.feature.habits.presentation.scene3d.TownFarms? = null
    /** Today's day of the year for the fields (a test's or screenshot's season if one is set). */
    private fun farmDay(): Int = com.lifemax.rpg.feature.habits.domain.Season.override?.dayOfYear ?: java.time.LocalDate.now().dayOfYear

    private fun townFrame(e: Engine, f: com.lifemax.rpg.feature.habits.presentation.scene.Frame3D): com.google.android.filament.View? {
        val data = TownSession.loaded ?: return null
        val walk = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.walk ?: return null
        var rn = run
        if (rn == null || town?.town !== data.town) {
            run = null; farms = null; town?.destroy(); town = null
            val t0 = System.nanoTime()
            val rt = Runtime.getRuntime()
            com.timerpg.street.shot.Crumbs.add("town scene: building ${data.town.seed}; Java heap limit ${rt.maxMemory() shr 20} MB, ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB used")
            StreetRenderer.townBuilding(ctx, true)
            buildMarked = true
            val ft = com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown(e, mats, data.town, data.yards, data.art, data.building, cinema = data.cinema,
                stores = data.stores, collision = data.collision, rail = data.rail, landmarks = data.landmarks)
            // the farms round the town, their fields as they are today
            farms = data.farmArt?.let { fa -> com.lifemax.rpg.feature.habits.presentation.scene3d.TownFarms.attach(ft, data.farms, fa, data.collision, day = farmDay()) }
            town = ft
            com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.applyResolution(ft.view)
            appliedResolution = com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.resolution
            rn = com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun(ft, com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets.sheets)
            run = rn
            FilamentStats.loadMs = (System.nanoTime() - t0) / 1e6f
        }
        // (the AI panel's resolution changed)
        val q = com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.resolution
        if (q != appliedResolution) { appliedResolution = q; town?.view?.let { com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.applyResolution(it) } }
        val ready = rn.build(walk.wx, walk.wz, if (TownSession.ready) TownSession.BUDGET_MS else TownSession.LOADING_BUDGET_MS)
        if (!TownSession.ready) {
            TownSession.progress = rn.progress(walk.wx, walk.wz)
            if (ready) {
                TownSession.viewReady(rn.firstViewMs); FilamentStats.measureMemory("town first view")
                com.timerpg.street.shot.Crumbs.add("town first view: " + com.lifemax.rpg.feature.habits.presentation.scene3d.TownMemory.summary())
                com.timerpg.street.shot.Crumbs.add("town first view, ${w}x$h: " + town!!.gpuEstimate(w, h).line())
            }
        }
        farms?.day = farmDay()
        // (a ride on the train asked for, under way or ending: RailTrip)
        RailTrip.frame(town!!)
        rn.draw(walk, w.toFloat(), h.toFloat(), f.hour, f.seconds, com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets.sheets)
        val ft = town!!
        FilamentStats.buildMs = ft.buildingsMs + ft.groundUploadMs
        // frames at the display's rate while there is building to do, even with the player standing still
        com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.renderBusy = rn.busy || !TownSession.ready
        if (buildMarked && rn.done) {
            // the whole town is built: its build did not die; how long it took, the slowest building, and memory
            buildMarked = false
            StreetRenderer.townBuilding(ctx, false)
            val (n, texels, shared) = ft.textureStats
            val built = "town built: ground %.0f ms (workers %.0f), buildings %.0f ms here (slowest %s %.1f ms, largest batch %.1f ms), %.0f ms on workers (slowest %s %.0f ms); textures %d, %.1fM texels, %d shared"
                .format(java.util.Locale.US, ft.groundMs, ft.groundPrepareMs, ft.buildingsMs, ft.slowest.first, ft.slowest.second,
                    ft.batches.maxOfOrNull { it.second } ?: 0f, ft.prepareMs, ft.slowestPrepare.first, ft.slowestPrepare.second, n, texels / 1e6, shared)
            FilamentStats.townBuilt = built
            TownSession.built = true
            com.timerpg.street.shot.Crumbs.add(built)
            com.timerpg.street.shot.Crumbs.add("town built, biggest textures: " + ft.biggestTextures(10))
            com.timerpg.street.shot.Crumbs.add("town built, ${w}x$h: " + ft.gpuEstimate(w, h).line())
            com.timerpg.street.shot.Crumbs.add("town built, memory: " + com.lifemax.rpg.feature.habits.presentation.scene3d.TownMemory.summary() + "; ground queue peak ${ft.queuedPeak shr 20} MB")
            FilamentStats.measureMemory("town built")
        }
        return ft.view
    }
    private var appliedResolution = -1f
    /** The town's build is marked under way ([StreetRenderer.townBuilding]) by this host. */
    private var buildMarked = false

    fun destroy() {
        if (!alive) return
        alive = false
        com.timerpg.street.shot.Crumbs.add("Filament stop")
        if (Stage3D.onFrame === onFrame) Stage3D.onFrame = null
        if (townMode) Stage3D.townBuilding = false
        // (the stage that filled it may be gone: a room; the next one fills it afresh)
        Stage3D.frame.clear()
        runCatching { ui.detach() }
        val e = engine ?: return
        street?.destroy(); street = null
        com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.projector = null
        run = null; farms = null; town?.destroy(); town = null
        // (the next host builds the town afresh: the loading screen shows again)
        if (townMode) TownSession.sceneGone()
        swap?.let { e.destroySwapChain(it) }; swap = null
        renderer?.let { e.destroyRenderer(it) }; renderer = null
        e.destroy(); engine = null
        // a clean stop before the first frames is no crash, nor one in the town's build
        if (good < StreetRenderer.GOOD_FRAMES && StreetRenderer.error == null) StreetRenderer.starting(ctx, false)
        if (buildMarked) { buildMarked = false; StreetRenderer.townBuilding(ctx, false) }
        com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.renderBusy = false
    }
}
