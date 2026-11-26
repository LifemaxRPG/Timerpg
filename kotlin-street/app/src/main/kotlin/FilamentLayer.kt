package com.timerpg.street

import android.content.Context
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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
        setAstc(ctx, p.getBoolean("astc", true))
        setFps(ctx, p.getInt("fps", 60))
        setMsaa(ctx, p.getInt("msaa", 4))
        // (the encoded pictures kept from start to start: the next start reads them instead of encoding them again)
        com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.astcCache = java.io.File(ctx.cacheDir, "astc")
    }

    /**
     * The town's pictures (the AI panel's "Textures"): ASTC 4x4 (the default: a quarter of the atlases' GPU memory, their
     * blocks of one or two colours exact, the rest near) or exact RGBA8 as before (TownQuality.astc). The next town built
     * takes it (textures are made as the town is built).
     */
    var astc by mutableStateOf(true)
        private set

    fun setAstc(ctx: Context, on: Boolean) {
        if (on != astc) com.timerpg.street.shot.Crumbs.add("textures: " + if (on) "ASTC" else "exact RGBA8")
        astc = on
        com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.astc = on
        prefs(ctx).edit().putBoolean("astc", on).apply()
    }

    /**
     * The frame rate (Settings, "Frame rate", docs/HEAT.md): 60 a second (the default: about half the work, heat and
     * battery of 120 for long play) or 120. The display is asked for the matching refresh and frames are paced evenly;
     * the thermal governor may hold 120 at 60 while the phone is warm ([FramePace.heatCap]).
     */
    var fps by mutableStateOf(60)
        private set
    /** The activity asks the display for the new rate (set by it). */
    var onFps: (() -> Unit)? = null

    fun setFps(ctx: Context, hz: Int) {
        val v = if (hz >= 90) 120 else 60
        if (v != fps) com.timerpg.street.shot.Crumbs.add("frame rate: $v")
        fps = v
        com.lifemax.rpg.feature.habits.presentation.scene.FramePace.chosen = v
        prefs(ctx).edit().putInt("fps", v).apply()
        onFps?.invoke()
    }

    /** Multisample anti-aliasing (Settings, "Anti-aliasing"): 4 samples (the default) or off; see TownQuality.msaa. */
    var msaa by mutableStateOf(4)
        private set

    fun setMsaa(ctx: Context, n: Int) {
        val v = if (n > 1) 4 else 1
        if (v != msaa) com.timerpg.street.shot.Crumbs.add("anti-aliasing: " + if (v > 1) "MSAA ${v}x" else "off")
        msaa = v
        com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.msaa = v
        prefs(ctx).edit().putInt("msaa", v).apply()
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
     * Once the town is built (MEMORY_AUDIT.md, C1/C2): the Java heap collected twice and finalized, then trimmed (ART's
     * VMRuntime.trimHeap where the phone lets the game call it), and the native allocator's free pages purged (scudo's
     * M_PURGE_ALL, the dialogue engine's own helper, NativeLlm.purgeNativeHeap), so the build's peak (the heap held some
     * 311 MB at it, 170-200 used in play) is not kept for the whole session. On a thread of its own (tens of ms of
     * collection never stall a frame); [then] runs after it. The breadcrumbs say what it gave back.
     */
    fun trimAfterBuild(then: () -> Unit = {}) {
        Thread({
            runCatching {
                fun rssMb() = runCatching {
                    java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.shr(10)
                }.getOrNull() ?: -1L
                val rt = Runtime.getRuntime()
                val t0 = android.os.SystemClock.elapsedRealtime()
                val java0 = (rt.totalMemory() - rt.freeMemory()) shr 20; val held0 = rt.totalMemory() shr 20
                val native0 = android.os.Debug.getNativeHeapSize() shr 20; val rss0 = rssMb()
                System.gc(); System.runFinalization(); System.gc()
                val trimmed = runCatching {
                    val vm = Class.forName("dalvik.system.VMRuntime")
                    vm.getMethod("trimHeap").invoke(vm.getMethod("getRuntime").invoke(null)); true
                }.getOrDefault(false)
                val purged = com.timerpg.street.llm.NativeLlm.purgeNativeHeap()
                val java1 = (rt.totalMemory() - rt.freeMemory()) shr 20; val held1 = rt.totalMemory() shr 20
                val native1 = android.os.Debug.getNativeHeapSize() shr 20; val rss1 = rssMb()
                com.timerpg.street.shot.Crumbs.add("town built, memory trimmed in ${android.os.SystemClock.elapsedRealtime() - t0} ms: Java heap $java0 -> $java1 MB used " +
                    "($held0 -> $held1 held; trimHeap ${if (trimmed) "done" else "not allowed"}), native heap $native0 -> $native1 MB " +
                    "(purge ${when (purged) { 1 -> "done"; 0 -> "not supported"; else -> "no library" }}), RSS $rss0 -> $rss1 MB")
            }
            then()
        }, "trim").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Reads the process's memory (in the background: it takes a while) into [memory] and the breadcrumbs: graphics (the
     * GPU's textures and buffers as the driver reports them), native and Java heaps, the total; [why] says when.
     */
    fun measureMemory(why: String, gpu: Map<String, Any?>? = null) {
        Thread({
            runCatching {
                val mi = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(mi)
                fun mb(k: String) = (mi.getMemoryStat(k)?.toIntOrNull() ?: -1024) / 1024
                val m = linkedMapOf<String, Any?>("when" to why, "graphicsMb" to mb("summary.graphics"), "nativeMb" to mb("summary.native-heap"),
                    "javaMb" to mb("summary.java-heap"), "codeMb" to mb("summary.code"), "totalPssMb" to mb("summary.total-pss"))
                // the graphics figure's parts as the driver reports them, and Filament's own count by part beside it: what
                // the town's textures and buffers come to, and how much of "graphics" is the driver's beyond them
                driverGraphics(mi).takeIf { it.isNotEmpty() }?.let { m["graphicsParts"] = it }
                gpu?.let { g ->
                    m["gpuByPart"] = g
                    @Suppress("UNCHECKED_CAST") val counted = ((g["total"] as? Map<String, Any?>)?.get("mb") as? Number)?.toDouble()
                    if (counted != null) m["graphicsBeyondFilamentMb"] = Math.round(mb("summary.graphics") - counted)
                }
                memory = m
                com.timerpg.street.shot.Crumbs.add("memory ($why): " + m.entries.drop(1).filter { it.key != "gpuByPart" }.joinToString { "${it.key} ${it.value}" })
            }
        }, "memory").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Android's "graphics" in parts (MB): "Gfx dev" (the GPU driver's memory mapped into the game, /dev/kgsl-3d0 on the
     * Adreno), "GL mtrack" (the driver's own count of textures and buffers), "EGL mtrack" (the window's and the
     * TextureView's surfaces), read through Debug.MemoryInfo's own labels where the phone lets the game read them; and
     * the PSS of the driver's and dma-buf mappings in /proc/self/smaps. Whatever cannot be read is left out.
     */
    private fun driverGraphics(mi: android.os.Debug.MemoryInfo): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        runCatching {
            val n = android.os.Debug.MemoryInfo::class.java.getField("NUM_OTHER_STATS").getInt(null)
            val label = android.os.Debug.MemoryInfo::class.java.getMethod("getOtherLabel", Int::class.javaPrimitiveType)
            val pss = android.os.Debug.MemoryInfo::class.java.getMethod("getOtherPss", Int::class.javaPrimitiveType)
            for (i in 0 until n) {
                val l = label.invoke(null, i) as? String ?: continue
                if (l == "Gfx dev" || l == "GL mtrack" || l == "EGL mtrack" || l == "Other mtrack") out[l] = (pss.invoke(mi, i) as Int) / 1024
            }
        }
        runCatching {
            var kgsl = 0L; var dmabuf = 0L; var name = ""
            java.io.File("/proc/self/smaps").forEachLine { line ->
                if (line.isNotEmpty() && (line[0].isDigit() || line[0] in 'a'..'f') && line.contains('-')) name = line.substringAfterLast(' ').trim()
                else if (line.startsWith("Pss:")) {
                    val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                    if (name.contains("kgsl")) kgsl += kb else if (name.contains("dmabuf") || name.contains("dma_buf")) dmabuf += kb
                }
            }
            out["kgslMappedPssMb"] = kgsl / 1024; out["dmabufPssMb"] = dmabuf / 1024
        }
        return out
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

/**
 * Filament's layer over the stage, under the HUD.
 *
 * The town, which draws its own sky (an opaque picture: nothing of the stage shows through), is drawn into a
 * **SurfaceView** (docs/NOVEL_OPTIMIZATIONS.md idea 3): its buffer is a layer of its own that the display hardware
 * composes, instead of a texture the app's window samples with the GPU every frame (a full-screen read and write, ~1.2 GB/s
 * at 60 Hz, and a frame of latency). It stays in the default Z order, behind the window: the SurfaceView punches a hole in
 * the window where it stands (the stage's draw under it is cleared there), and the HUD drawn after it composes over it.
 * The crossroads street, drawn see-through over the stage's 2D sky, stays on a TextureView so Compose draws on both sides.
 * [FilamentSurface.useSurfaceView] false: the town on a TextureView as before (to compare).
 */
@Composable
fun FilamentLayer(modifier: Modifier = Modifier, town: Boolean = false) {
    AndroidView(
        factory = { ctx ->
            val surface = town && FilamentSurface.useSurfaceView && townOwnSky(ctx)
            val v: View = if (surface) SurfaceView(ctx) else TextureView(ctx)
            v.also { it.tag = FilamentHost(ctx, it, town) }
        },
        modifier = modifier,
        onRelease = { v -> (v.tag as? FilamentHost)?.destroy() },
    )
}

/** The town draws its own sky (FilamentTown's SkySurface, when its material ships): its surface is opaque. */
private fun townOwnSky(ctx: Context): Boolean = com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown.sky3d &&
    runCatching { ctx.assets.open("filament/sky.filamat").close(); true }.getOrDefault(false)

/** The town's SurfaceView while one is up (the screenshot copies it, [com.timerpg.street.shot.ShotUploader.capture]). */
object FilamentSurface {
    /** The town on a SurfaceView (true) or a TextureView as before (false: to compare power and pixels). */
    @Volatile @JvmField var useSurfaceView = true
    @Volatile var view: SurfaceView? = null; internal set
}

/** Runs Filament on [view]: one frame each time the stage draws one (in its draw, so street and sky are the same frame). */
private class FilamentHost(private val ctx: Context, private val view: View, private val townMode: Boolean) {
    /** The town draws its own sky (FilamentTown's SkySurface, when its material ships): the surface is opaque. */
    private val ownSky: Boolean = townMode && townOwnSky(ctx)
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
    /** When the last frame started (ns), for the automatic resolution's clock. */
    private var lastFrameNs = 0L
    private val onFrame: () -> Unit = { frame() }

    // ---- frame pacing (docs/HEAT.md): the display's refresh, the rate aimed at, the vsync each frame is drawn for
    /** The display's refresh (Hz) and the rate aimed at, as last given to Filament. */
    private var displayHz = 0f
    private var pacedFps = 0
    /** Refreshes a frame is held for (120 Hz at 60: 2) and one refresh (ns). */
    private var interval = 1
    private var periodNs = 16_666_667L
    /** The latest vsync the Choreographer told (ns, the monotonic clock Filament's beginFrame takes), and the one last drawn for. */
    @Volatile private var vsyncNs = 0L
    private var drawnVsync = 0L
    private val vsync = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            vsyncNs = frameTimeNanos
            if (alive) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var surface: Surface? = null
    private var paceCheck = 0

    /** Gives [r] the display's refresh and the rate aimed at, when either changed (each frame: cheap; the display may change mode). */
    private fun pace(r: Renderer) {
        @Suppress("DEPRECATION") val hz = runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate }.getOrDefault(60f)
        val fps = com.lifemax.rpg.feature.habits.presentation.scene.FramePace.target
        if (hz == displayHz && fps == pacedFps) return
        displayHz = hz; pacedFps = fps
        interval = com.lifemax.rpg.feature.habits.presentation.scene.FramePace.interval(hz, fps)
        periodNs = (1e9 / hz.coerceAtLeast(1f)).toLong()
        r.displayInfo = Renderer.DisplayInfo().apply { refreshRate = hz }
        // (Filament's own frame timing: frames [interval] refreshes apart; its dynamic resolution stays off, ours is held steps)
        r.frameRateOptions = Renderer.FrameRateOptions().apply { this.interval = this@FilamentHost.interval }
        // the automatic resolution's GPU budget: the frame's time at the rate aimed at
        com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.autoSteps.budgetMs = 1000f / fps
        surface?.let { setFrameRate(it) }
        com.timerpg.street.shot.Crumbs.add("frame pacing: display %.0f Hz, %d a second, a frame every %d refresh%s".format(java.util.Locale.US, hz, fps, interval, if (interval == 1) "" else "es"))
    }

    /** Tells the system the rate this surface is drawn at (Android 11+), so it can pick the display's mode to match. */
    private fun setFrameRate(s: Surface) {
        if (android.os.Build.VERSION.SDK_INT < 30 || pacedFps <= 0) return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) s.setFrameRate(pacedFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            else s.setFrameRate(pacedFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

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
                r.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, if (ownSky) 1.0 else 0.0) }
                // the display's refresh and the frame rate aimed at (docs/HEAT.md: 60 by default, 120 as a setting; the
                // activity asks the display for the matching mode): Filament's frame timing and its frames' spacing follow
                // them (pace())
                pace(r)
            }
            if (!townMode) street = FilamentStreet(e, mats)
            FilamentStats.loadMs = (System.nanoTime() - t0) / 1e6f
            com.timerpg.street.play.PlayLog.spanDone("filament.start", FilamentStats.loadMs.toDouble())
            com.timerpg.street.play.PlayLog.phase("filament.start")
            // (the town draws its own sky: an opaque surface, nothing composited under it; the crossroads draw over the
            // stage's 2D sky, see-through)
            ui.isOpaque = ownSky
            ui.renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swap?.let { e.destroySwapChain(it) }
                    swap = e.createSwapChain(surface, if (ownSky) ui.swapChainFlags else ui.swapChainFlags or SwapChainFlags.CONFIG_TRANSPARENT)
                    this@FilamentHost.surface = surface; setFrameRate(surface)
                    dirty = true
                }
                override fun onDetachedFromSurface() {
                    swap?.let { e.destroySwapChain(it); e.flushAndWait() }
                    swap = null; this@FilamentHost.surface = null
                }
                override fun onResized(width: Int, height: Int) { w = width; h = height; dirty = true }
            }
            when (view) {
                is SurfaceView -> { ui.attachTo(view); FilamentSurface.view = view }
                is TextureView -> ui.attachTo(view)
            }
            Stage3D.onFrame = onFrame
            // (the vsync each frame is drawn for, and the heat governor and performance hints: docs/HEAT.md)
            android.view.Choreographer.getInstance().postFrameCallback(vsync)
            ContextHolder.ctx = ctx.applicationContext
            Heat.start(ctx.applicationContext)
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
        // frame pacing (docs/HEAT.md): a frame every [interval] refreshes, evenly, at the rate aimed at (the display is
        // asked for that rate's mode, so this is 1 there; 2 on a display left at 120 Hz for 60 a second)
        if (++paceCheck % 30 == 1) pace(r)
        val v = vsyncNs
        if (!dirty && !com.lifemax.rpg.feature.habits.presentation.scene.FramePace.due(v, drawnVsync, periodNs, interval)) return
        drawnVsync = v
        try {
            val t0 = System.nanoTime()
            val view = if (s != null) {
                s.update(f)
                if (s.buildMs > 0f) FilamentStats.buildMs = s.buildMs
                s.view
            } else townFrame(e, f) ?: return
            val r0 = System.nanoTime()
            // (the vsync this frame is for: Filament times its frame from it)
            if (r.beginFrame(sc, if (v > 0L) v else System.nanoTime())) {
                r.render(view)
                r.endFrame()
            }
            com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.add(com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.RENDER, System.nanoTime() - r0)
            // ADPF: the frame's work against the frame's time (and the workers' since the last frame against theirs)
            Heat.frameDone(System.nanoTime() - t0, com.lifemax.rpg.feature.habits.presentation.scene.FramePace.target)
            lastN = f.n; dirty = false
            val cpu = (System.nanoTime() - t0) / 1e6f
            // the GPU's time for an earlier frame, where the driver reports it
            val got = r.getFrameInfoHistory(history)
            val g = if (got > 0 && history[0].gpuFrameDuration > 0) history[0].gpuFrameDuration / 1e6f else -1f
            FilamentStats.add(cpu, g)
            // (the automatic resolution: a held step, changed only after seconds over or well under the GPU's budget)
            if (townMode) {
                val tq = com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality
                val dt = if (lastFrameNs == 0L) 0f else ((t0 - lastFrameNs) / 1e9f).coerceAtMost(.25f)
                lastFrameNs = t0
                // (the thermal governor moved its step: docs/HEAT.md)
                if (Heat.resolutionChanged) { Heat.resolutionChanged = false; town?.view?.let { tq.applyResolution(it) } }
                if (tq.resolution == tq.RES_AUTO && tq.autoSteps.feed(g, dt)) {
                    town?.view?.let { tq.applyResolution(it) }
                    com.timerpg.street.shot.Crumbs.add("resolution: auto, %.2f".format(java.util.Locale.US, tq.autoSteps.scale))
                }
            }
            // the playtest recorder's second (play/PlayLog: fixed arrays, nothing allocated; before FrameSpikes clears FrameWork)
            run {
                val fw = com.lifemax.rpg.feature.habits.presentation.scene.FrameWork
                val ts = town?.stats
                val moving = com.lifemax.rpg.feature.habits.presentation.scene.GameState.inMotion || com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera.moving
                com.timerpg.street.play.PlayLog.playing = townMode && TownSession.ready
                com.timerpg.street.play.PlayLog.frame(t0, cpu, g, if (got > 0) history[0].frameId.toLong() else -1L, moving,
                    ts?.draws ?: -1, ts?.triangles ?: -1, ts?.visible ?: -1, fw.ns, fw.uploads, fw.bytes)
            }
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
                com.timerpg.street.play.PlayLog.phase("filament.drawing")
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
            // each of the town's worker jobs to the play log (TOWN.md, "The first view first"): {"k":"span","n":"town.job.<kind>",
            // "ms": how long it ran, "d": what, how long it waited in the queue, its priority}
            com.lifemax.rpg.feature.habits.presentation.scene3d.TownJobs.onJob = { kind, name, waitMs, runMs, priority ->
                com.timerpg.street.play.PlayLog.spanDone("town.job.$kind", runMs.toDouble(),
                    "%s waited %.0f ms, priority %.0f".format(java.util.Locale.US, name ?: "-", waitMs, priority))
            }
            val ft = com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown(e, mats, data.town, data.yards, data.art, data.building, cinema = data.cinema,
                stores = data.stores, collision = data.collision, rail = data.rail, landmarks = data.landmarks)
            // the farms round the town, their fields as they are today
            farms = data.farmArt?.let { fa -> com.lifemax.rpg.feature.habits.presentation.scene3d.TownFarms.attach(ft, data.farms, fa, data.collision, day = farmDay()) }
            // the town's cars (docs/VEHICLES.md), drawn from the game's VehicleWorld
            com.lifemax.rpg.feature.habits.presentation.scene3d.TownCars.attach(ft, { com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.cars }) { p ->
                runCatching { ctx.assets.open("vehicles/$p").use { it.readBytes() } }.getOrNull()
            }
            // the world round the town (docs/WORLD_GEN.md W4: the skeleton's far view to the horizon), planned and built on the town's workers
            data.world?.let { w ->
                // (the world streamer, docs/WORLD_STREAM.md: the far view planned again round the player out in the country)
                val far = com.lifemax.rpg.feature.habits.presentation.scene3d.WorldFar.attach(ft, { w.plan(data.town.year) }, w.origin,
                    replan = { c -> w.plan(data.town.year, c) }, farmArt = data.farmArt)
                // the generated towns streamed in round the player, and the roadside sites (planet step 2)
                TownSession.townsDir(ctx)?.takeIf { w.towns.ids.isNotEmpty() }?.let { dir ->
                    com.lifemax.rpg.feature.habits.presentation.scene3d.WorldTowns.attach(ft, far, w.origin, w, w.towns, com.lifemax.rpg.feature.habits.presentation.scene3d.TownsSource(
                        { p -> java.io.File(dir, p).takeIf { it.isFile }?.readBytes() },
                        { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() },
                        runCatching { ctx.assets.open("landmarks/landmarks.json").use { it.readBytes().decodeToString() } }.getOrNull(),
                        { p -> runCatching { ctx.assets.open("landmarks/$p").use { it.readBytes() } }.getOrNull() },
                        data.farmArt), data.town.year)
                }
                // (a re-planned terrain is the same ground: the cars' chunks stay, they read the new one from now on)
                far.onTerrainSwap = { t -> com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.terrain = t }
                // (the cars' ground beyond the town is this same terrain, handed over once it is made)
                com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.terrain = null
                far.whenTerrain { t ->
                    com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.terrain = t
                    com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.cars?.groundChanged()
                    com.timerpg.street.shot.Crumbs.add("cars: the terrain is the ground beyond the town now")
                }
            }
            town = ft
            com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.applyResolution(ft.view)
            appliedResolution = com.lifemax.rpg.feature.habits.presentation.scene3d.TownQuality.resolution
            rn = com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun(ft, com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets.sheets)
            run = rn
            FilamentStats.loadMs = (System.nanoTime() - t0) / 1e6f
        }
        // (the render origin stays the capital's own frame: the player, the camera and the cars are in its street units, so
        // the world's parts are placed from it too (docs/WORLD_STREAM.md, "Coordinates"). Re-centring out in the country
        // waits for the player, camera and physics to be re-based with it (step 3, travel between towns); moving the origin
        // alone would shift the world's parts against everything else.)
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
        // the sky: the stage's weather, season and date (the moon's phase); the stage stops drawing its own under it
        town!!.let { t -> t.weather = f.weather; f.season?.let { t.season = it }; t.day = f.day; Stage3D.townSky3d = t.drawsSky && ownSky }
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
            com.timerpg.street.play.PlayLog.spanDone("town.build", (ft.groundMs + ft.buildingsMs).toDouble(), built)
            com.timerpg.street.play.PlayLog.phase("town.built", data.town.seed)
            com.timerpg.street.shot.Crumbs.add(built)
            com.timerpg.street.shot.Crumbs.add("town built, biggest textures: " + ft.biggestTextures(10))
            com.timerpg.street.shot.Crumbs.add("town built, ${w}x$h: " + ft.gpuEstimate(w, h).line())
            com.timerpg.street.shot.Crumbs.add("town built, " + com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.line())
            com.timerpg.street.shot.Crumbs.add("town built, " + com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categoryLine(ft.gpuEstimate(w, h)))
            com.timerpg.street.shot.Crumbs.add("town built, memory: " + com.lifemax.rpg.feature.habits.presentation.scene3d.TownMemory.summary() + "; ground queue peak ${ft.queuedPeak shr 20} MB")
            // the GPU parts against their budgets (TownBudget, the desktop's GpuBudgetTest's), the play log's mem line after
            com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.estimate = ft.gpuEstimate(w, h)
            com.timerpg.street.shot.Crumbs.add("town built, GPU budget: " + com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget.let { b ->
                b.line(b.check(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categories(b.estimate))) })
            // the build's garbage let go for good: the Java heap collected and trimmed, the native allocator's free pages
            // purged (MEMORY_AUDIT.md, C1/C2), on a thread of its own, then the memory read
            FilamentStats.trimAfterBuild {
                FilamentStats.measureMemory("town built", com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.categories(ft.gpuEstimate(w, h)))
            }
        }
        return ft.view
    }
    private var appliedResolution = -1f
    /** The town's build is marked under way ([StreetRenderer.townBuilding]) by this host. */
    private var buildMarked = false

    fun destroy() {
        if (!alive) return
        alive = false
        runCatching { android.view.Choreographer.getInstance().removeFrameCallback(vsync) }
        com.timerpg.street.shot.Crumbs.add("Filament stop")
        if (Stage3D.onFrame === onFrame) Stage3D.onFrame = null
        if (townMode) { Stage3D.townBuilding = false; Stage3D.townSky3d = false }
        // (the stage that filled it may be gone: a room; the next one fills it afresh)
        Stage3D.frame.clear()
        runCatching { ui.detach() }
        if (FilamentSurface.view === view) FilamentSurface.view = null
        val e = engine ?: return
        street?.destroy(); street = null
        com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.projector = null
        run = null; farms = null; town?.destroy(); town = null
        // (the next host builds the town afresh: the loading screen shows again)
        if (townMode) TownSession.sceneGone()
        swap?.let { e.destroySwapChain(it) }; swap = null
        renderer?.let { e.destroyRenderer(it) }; renderer = null
        com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.release(e)
        e.destroy(); engine = null
        // a clean stop before the first frames is no crash, nor one in the town's build
        if (good < StreetRenderer.GOOD_FRAMES && StreetRenderer.error == null) StreetRenderer.starting(ctx, false)
        if (buildMarked) { buildMarked = false; StreetRenderer.townBuilding(ctx, false) }
        com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.renderBusy = false
    }
}
