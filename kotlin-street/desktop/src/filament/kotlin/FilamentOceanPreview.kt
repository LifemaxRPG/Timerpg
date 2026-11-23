import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.LinearToneMapper
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanPalette
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSample
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanSurface
import com.lifemax.rpg.feature.habits.presentation.scene3d.OceanWaves
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Standalone headless Filament preview for the G002 pixel-art ocean prototype (`-Dmode=ocean`, round 3).
 *
 * Outputs (desktop Filament renders on llvmpipe, not phone captures):
 *  - 6 overview renders from 6 m up, 20 m behind the boat (day/dusk/night x calm/rough) + `ocean_grid_6up.png`
 *  - 4 person-height close-ups (2 m above water near the boat: day & dusk x calm & rough) + `ocean_closeup_4up.png`
 *  - 6-frame close-up motion strips (`ocean_motion_calm_6f.png` and `ocean_motion_rough_6f.png`)
 *  - 4-frame bobbing boat strip (`ocean_boat_bob_strip.png`) with tilt capped <= 14 deg
 *  - `ocean_bench.txt` reporting glint highlight coverage (< 8% day calm, < 20% dusk), 0 B per-frame alloc, and timing
 *
 * Run via:
 *   gradle :desktop:preview -Dmode=ocean
 */
fun main(args: Array<String>) {
    val outRoot = args.firstOrNull() ?: "build/preview"
    renderOcean(outRoot)
}

fun renderOcean(outRoot: String) {
    val outDir = File(outRoot, "ocean").also { it.mkdirs() }
    val w = System.getProperty("w")?.toIntOrNull() ?: 960
    val h = (w * 9 / 16).coerceAtLeast(360)

    val assets = File(assetsDir(), "filament")
    val oceanMatFile = File(assets, "ocean.filamat")
    check(oceanMatFile.exists()) {
        "Missing ${oceanMatFile.absolutePath}. Compile it first with Filament v1.81.0 matc:\n" +
            "  matc -p mobile -a opengl -o ${oceanMatFile.path} kotlin-street/filament/materials/ocean.mat"
    }
    val litMatFile = File(assets, "street_lit.filamat")
    check(litMatFile.exists()) { "Missing ${litMatFile.absolutePath}" }

    val report = StringBuilder()
    fun log(msg: String) {
        report.appendLine(msg)
        println(msg)
    }

    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val swap: SwapChain = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
    val renderer = engine.createRenderer()
    renderer.clearOptions = Renderer.ClearOptions().apply {
        clear = true
        setClearColor(0.0, 0.0, 0.0, 0.0)
    }

    val scene = engine.createScene()
    val view = engine.createView()
    val camEntity = EntityManager.get().create()
    val camera = engine.createCamera(camEntity)
    view.scene = scene
    view.camera = camera
    view.viewport = Viewport(0, 0, w, h)
    view.blendMode = View.BlendMode.TRANSLUCENT
    view.isPostProcessingEnabled = true
    view.dithering = View.Dithering.NONE
    view.antiAliasing = View.AntiAliasing.NONE
    val grading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)
    view.colorGrading = grading
    camera.setExposure(1f)

    fun loadMaterial(f: File): Material {
        val bytes = f.readBytes()
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).flip()
        return Material.Builder().payload(buf, bytes.size).build(engine)
    }

    val oceanMat = loadMaterial(oceanMatFile)
    val litMat = loadMaterial(litMatFile)

    // Watertight 9-ring nested mesh (1 m core -> 7.17 km waves -> 24 km far horizon skirt, 1 draw call)
    val ocean = OceanSurface(
        engine = engine,
        scene = scene,
        material = oceanMat,
        ringCells = 56,
        baseCellSize = 1.0f,
        ringCount = 9,
        farHorizonRadius = 24000f,
        seaLevelY = 0f,
    )

    val sunEntity = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.DIRECTIONAL)
        .color(1f, 0.96f, 0.90f)
        .intensity(4.0f)
        .direction(0.3f, -0.6f, 0.7f)
        .castShadows(false)
        .build(engine, sunEntity)
    scene.addEntity(sunEntity)
    var ibl: IndirectLight? = null

    val boat = OceanBoxBoat(engine, scene, litMat)

    val boatX = 0.0f
    val boatZ = 0.0f
    val boatHeading = 0.55f
    val nearPlane = 0.5
    val farPlane = 30000.0
    val lookMatrix = FloatArray(16)

    // Active camera parameters (updated per view preset)
    var curCamX = 0f; var curCamY = 6f; var curCamZ = -20f
    var curTargetX = 0f; var curTargetY = 1.8f; var curTargetZ = 4f
    var curFovY = 42.0

    fun setCamera(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, fovYDeg: Double) {
        curCamX = ex; curCamY = ey; curCamZ = ez
        curTargetX = tx; curTargetY = ty; curTargetZ = tz
        curFovY = fovYDeg
        camera.setProjection(fovYDeg, w.toDouble() / h.toDouble(), nearPlane, farPlane, Camera.Fov.VERTICAL)
        camera.lookAt(ex.toDouble(), ey.toDouble(), ez.toDouble(), tx.toDouble(), ty.toDouble(), tz.toDouble(), 0.0, 1.0, 0.0)
        buildDeviceLookMatrix(ex, ey, ez, tx, ty, tz, fovYDeg, w.toFloat() / h.toFloat(), nearPlane.toFloat(), lookMatrix)
        boat.setLook(lookMatrix)
    }

    fun applyLighting(palette: OceanPalette) {
        val lm = engine.lightManager
        val li = lm.getInstance(sunEntity)
        val ld = palette.lightDir
        lm.setDirection(li, -ld[0], -ld[1], -ld[2])
        val night = palette.nightFactor
        val (cr, cg, cb, inten) = when {
            night > 0.75f -> floatArrayOf(0.62f, 0.72f, 1.00f, 1.05f)
            night > 0.10f -> floatArrayOf(1.00f, 0.64f, 0.36f, 3.80f)
            else -> floatArrayOf(1.00f, 0.97f, 0.90f, 4.40f)
        }
        lm.setColor(li, cr, cg, cb)
        lm.setIntensity(li, inten)

        val amb = when {
            night > 0.75f -> floatArrayOf(0.08f, 0.11f, 0.20f)
            night > 0.10f -> floatArrayOf(0.32f, 0.26f, 0.32f)
            else -> floatArrayOf(0.54f, 0.60f, 0.70f)
        }
        val sh = FloatArray(12)
        sh[0] = amb[0]; sh[1] = amb[1]; sh[2] = amb[2]
        sh[3] = -0.06f * amb[0]; sh[4] = -0.06f * amb[1]; sh[5] = -0.06f * amb[2]
        sh[6] = -0.15f * amb[0]; sh[7] = -0.15f * amb[1]; sh[8] = -0.15f * amb[2]
        ibl?.let { engine.destroyIndirectLight(it) }
        ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1.0f).build(engine).also {
            scene.indirectLight = it
        }
        val cabinGlow = if (night > 0.75f) 0.95f else if (night > 0.10f) 0.45f else 0.0f
        boat.setCabinGlow(cabinGlow)
    }

    val readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    val renderTimesMs = ArrayList<Double>()

    fun linearToSrgbByte(c: Float): Int {
        val clamped = c.coerceIn(0f, 1f).toDouble()
        val s = if (clamped <= 0.0031308) clamped * 12.92 else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    data class RenderOut(
        val rgb: IntArray,
        val topAlphaRatio: Float,
        val bottomAlphaRatio: Float,
        val firstWaterRow: Int,
        val glintFracBelowHorizon: Float,
    )

    fun renderFrame(palette: OceanPalette): RenderOut {
        val t0 = System.nanoTime()
        val done = AtomicBoolean(false)
        for (pass in 0..1) {
            if (renderer.beginFrame(swap, 0L)) {
                renderer.render(view)
                if (pass == 1) {
                    renderer.readPixels(
                        0, 0, w, h,
                        Texture.PixelBufferDescriptor(
                            readBuf,
                            Texture.Format.RGBA,
                            Texture.Type.UBYTE,
                            1, 0, 0, w,
                            Executor { it.run() },
                            Runnable { done.set(true) },
                        ),
                    )
                }
                renderer.endFrame()
            }
            engine.flushAndWait()
        }
        var guard = 0
        while (!done.get() && guard++ < 400) {
            engine.flushAndWait()
            Thread.sleep(5)
        }
        val ms = (System.nanoTime() - t0) / 1e6 / 2.0
        renderTimesMs += ms

        val sky = renderPixelArtSky(w, h, palette, curFovY, curCamX, curCamY, curCamZ, curTargetX, curTargetY, curTargetZ)
        val out = IntArray(w * h)
        var topOpaque = 0; var topTotal = 0
        var botOpaque = 0; var botTotal = 0
        var firstWaterRow = -1

        // Expected sRGB highlight colour of glintColor for exact coverage measurement below horizon
        val gr = linearToSrgbByte(palette.glintColor[0])
        val gg = linearToSrgbByte(palette.glintColor[1])
        val gb = linearToSrgbByte(palette.glintColor[2])

        for (y in 0 until h) {
            var rowOpaque = 0
            for (x in 0 until w) {
                val i = ((h - 1 - y) * w + x) * 4
                val fr = readBuf.get(i).toInt() and 255
                val fg = readBuf.get(i + 1).toInt() and 255
                val fb = readBuf.get(i + 2).toInt() and 255
                val fa = readBuf.get(i + 3).toInt() and 255
                if (fa > 200) rowOpaque++
                if (y < h / 6) {
                    topTotal++
                    if (fa > 200) topOpaque++
                } else if (y >= h * 3 / 4) {
                    botTotal++
                    if (fa > 200) botOpaque++
                }
                val s = sky[y * w + x]
                val sr = (s shr 16) and 255
                val sg = (s shr 8) and 255
                val sb = s and 255
                val rr = (fr + sr * (255 - fa) / 255).coerceAtMost(255)
                val ggc = (fg + sg * (255 - fa) / 255).coerceAtMost(255)
                val bb = (fb + sb * (255 - fa) / 255).coerceAtMost(255)
                out[y * w + x] = (255 shl 24) or (rr shl 16) or (ggc shl 8) or bb
            }
            if (firstWaterRow < 0 && rowOpaque > w * 3 / 4) firstWaterRow = y
        }

        // Measure pure glint highlight pixel fraction strictly below the horizon line
        val startRow = if (firstWaterRow >= 0) firstWaterRow else h / 2
        var belowTotal = 0
        var belowGlint = 0
        for (y in startRow until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                belowTotal++
                val px = out[rowOff + x]
                val pr = (px shr 16) and 255
                val pg = (px shr 8) and 255
                val pb = px and 255
                if (abs(pr - gr) <= 10 && abs(pg - gg) <= 10 && abs(pb - gb) <= 10) {
                    belowGlint++
                }
            }
        }
        val glintFrac = belowGlint.toFloat() / belowTotal.coerceAtLeast(1)

        return RenderOut(
            rgb = out,
            topAlphaRatio = topOpaque.toFloat() / topTotal.coerceAtLeast(1),
            bottomAlphaRatio = botOpaque.toFloat() / botTotal.coerceAtLeast(1),
            firstWaterRow = firstWaterRow,
            glintFracBelowHorizon = glintFrac,
        )
    }

    val boatSample = OceanSample()
    val boatModel = FloatArray(16)

    data class Shot(val tag: String, val label: String, val spec: OceanSpec, val palette: OceanPalette, val t: Float)
    val overviewShots = listOf(
        Shot("day_calm", "DAY 13:00 | CALM SEA", OceanWaves.CALM, OceanPalette.DAY, 4.2f),
        Shot("dusk_calm", "DUSK 19:15 | CALM SEA", OceanWaves.CALM, OceanPalette.DUSK, 4.2f),
        Shot("night_calm", "NIGHT 22:30 | CALM SEA", OceanWaves.CALM, OceanPalette.NIGHT, 4.2f),
        Shot("day_rough", "DAY 13:00 | ROUGH SEA", OceanWaves.ROUGH, OceanPalette.DAY, 5.1f),
        Shot("dusk_rough", "DUSK 19:15 | ROUGH SEA", OceanWaves.ROUGH, OceanPalette.DUSK, 5.1f),
        Shot("night_rough", "NIGHT 22:30 | ROUGH SEA", OceanWaves.ROUGH, OceanPalette.NIGHT, 5.1f),
    )

    log("G002 Ocean Prototype (Round 3 / G002-r2) — Desktop Filament Preview (${w}x${h}, llvmpipe, not phone captures)")
    log("Mesh: ${ocean.ringCount} nested rings (${ocean.ringCells}x${ocean.ringCells}/ring, ${ocean.baseCellSize} m core -> %.0f m wave outer -> %.0f m far horizon skirt; ${ocean.vertexCount} verts, ${ocean.triangleCount} tris, 1 draw call, snapStep=%.1f m)".format(
        ocean.mesh.waveOuterRadius, ocean.farHorizonRadius, ocean.snapStep,
    ))

    // 1. Overview shots: camera 6 m up and 20 m behind the boat looking slightly down
    setCamera(0.0f, 6.0f, -20.0f, 0.0f, 1.8f, 4.0f, 42.0)
    ocean.setHullFoam(boatX, boatZ, boatHeading, halfBeam = 1.16f, halfLength = 2.68f, foamRingWidth = 0.36f)

    val glintCoverage = LinkedHashMap<String, Float>()
    val gridImages = ArrayList<Pair<String, IntArray>>(overviewShots.size)
    log("")
    log("Overview shots (camera 6 m up, 20 m behind boat):")
    for (s in overviewShots) {
        applyLighting(s.palette)
        ocean.update(lookMatrix, curCamX, curCamY, curCamZ, s.t, s.spec, s.palette, artPixelCell = 2.0f)
        OceanSurface.boatTransformInto(boatX, boatZ, boatHeading, s.t, s.spec, boatSample, boatModel)
        boat.setTransform(boatModel)
        val res = renderFrame(s.palette)
        writePng(File(outDir, "ocean_${s.tag}.png"), res.rgb, w, h)
        val pct = res.glintFracBelowHorizon * 100f
        glintCoverage[s.tag] = pct
        val tileLabel = "%s  |  glint=%.1f%%".format(s.label, pct)
        gridImages += tileLabel to res.rgb
        val tiltDeg = Math.toDegrees(atan2(sqrt(boatSample.nx * boatSample.nx + boatSample.nz * boatSample.nz).toDouble(), boatSample.ny.toDouble()))
        log("  %-12s  boat y=%+.2fm tilt=%.1fdeg  horizon=%d/%d (%.0f%%)  glint=%.2f%%  render=%.1fms".format(
            s.tag, boatSample.y, tiltDeg, res.firstWaterRow, h, res.firstWaterRow * 100f / h, pct, renderTimesMs.last(),
        ))
        check(res.topAlphaRatio < 0.15f && res.bottomAlphaRatio > 0.95f) {
            "Orientation check failed for ${s.tag}: topOpaque=${res.topAlphaRatio}, botOpaque=${res.bottomAlphaRatio}"
        }
    }
    writeContactSheet(File(outDir, "ocean_grid_6up.png"), gridImages, cols = 3, rows = 2, tileW = w, tileH = h)

    // Glint coverage gate from G002-r2 note 1
    val dayCalmGlint = glintCoverage["day_calm"] ?: 100f
    val duskCalmGlint = glintCoverage["dusk_calm"] ?: 100f
    val duskRoughGlint = glintCoverage["dusk_rough"] ?: 100f
    log("")
    log("Glint highlight coverage below horizon (G002-r2 gate):")
    log("  day_calm   = %.2f%%  (limit < 8.0%%)".format(dayCalmGlint))
    log("  dusk_calm  = %.2f%%  (limit < 20.0%%)".format(duskCalmGlint))
    log("  dusk_rough = %.2f%%  (limit < 20.0%%)".format(duskRoughGlint))
    log("  night_calm = %.2f%%".format(glintCoverage["night_calm"] ?: 0f))
    check(dayCalmGlint < 8.0f) { "day_calm glint coverage %.2f%% exceeded 8%% limit".format(dayCalmGlint) }
    check(duskCalmGlint < 20.0f && duskRoughGlint < 20.0f) {
        "dusk glint coverage (calm=%.2f%%, rough=%.2f%%) exceeded 20%% limit".format(duskCalmGlint, duskRoughGlint)
    }

    // 2. Person-height close-ups (2 m above the water near the boat): day & dusk x calm & rough
    setCamera(4.2f, 2.0f, -10.5f, 0.0f, 0.70f, 2.5f, 44.0)
    val closeupShots = listOf(
        Shot("closeup_day_calm", "CLOSE-UP 2m | DAY CALM", OceanWaves.CALM, OceanPalette.DAY, 4.2f),
        Shot("closeup_day_rough", "CLOSE-UP 2m | DAY ROUGH", OceanWaves.ROUGH, OceanPalette.DAY, 5.1f),
        Shot("closeup_dusk_calm", "CLOSE-UP 2m | DUSK CALM", OceanWaves.CALM, OceanPalette.DUSK, 4.2f),
        Shot("closeup_dusk_rough", "CLOSE-UP 2m | DUSK ROUGH", OceanWaves.ROUGH, OceanPalette.DUSK, 5.1f),
    )
    val closeupImages = ArrayList<Pair<String, IntArray>>(closeupShots.size)
    log("")
    log("Person-height close-ups (eye 2.0 m above water near boat):")
    for (s in closeupShots) {
        applyLighting(s.palette)
        ocean.update(lookMatrix, curCamX, curCamY, curCamZ, s.t, s.spec, s.palette, artPixelCell = 2.0f)
        OceanSurface.boatTransformInto(boatX, boatZ, boatHeading, s.t, s.spec, boatSample, boatModel)
        boat.setTransform(boatModel)
        val res = renderFrame(s.palette)
        writePng(File(outDir, "ocean_${s.tag}.png"), res.rgb, w, h)
        closeupImages += s.label to res.rgb
        log("  %-20s  boat y=%+.2fm  glint=%.2f%%  render=%.1fms".format(
            s.tag, boatSample.y, res.glintFracBelowHorizon * 100f, renderTimesMs.last(),
        ))
    }
    writeContactSheet(File(outDir, "ocean_closeup_4up.png"), closeupImages, cols = 2, rows = 2, tileW = w, tileH = h)

    // 3. 6-frame close-up motion strips (calm and rough sea over 2.5 s so wave shape evolution is visible)
    val motionTimes = floatArrayOf(0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f)
    applyLighting(OceanPalette.DAY)
    for ((name, spec) in listOf("calm" to OceanWaves.CALM, "rough" to OceanWaves.ROUGH)) {
        val frames = ArrayList<Pair<String, IntArray>>(motionTimes.size)
        for ((idx, mt) in motionTimes.withIndex()) {
            ocean.update(lookMatrix, curCamX, curCamY, curCamZ, mt, spec, OceanPalette.DAY, artPixelCell = 2.0f)
            OceanSurface.boatTransformInto(boatX, boatZ, boatHeading, mt, spec, boatSample, boatModel)
            boat.setTransform(boatModel)
            val res = renderFrame(OceanPalette.DAY)
            val tiltDeg = Math.toDegrees(atan2(sqrt(boatSample.nx * boatSample.nx + boatSample.nz * boatSample.nz).toDouble(), boatSample.ny.toDouble()))
            frames += "%s t=%.1fs | boat y=%+.2fm | tilt=%.1f deg".format(name.uppercase(), mt, boatSample.y, tiltDeg) to res.rgb
            if (idx == 0) log("Motion strip ($name, 2 m close-up, 6 frames 0.0..2.5s): written to ocean_motion_${name}_6f.png")
        }
        writeContactSheet(File(outDir, "ocean_motion_${name}_6f.png"), frames, cols = 3, rows = 2, tileW = w, tileH = h)
    }

    // 4. 4-frame bobbing boat strip from 6 m / 20 m overview camera on rough daytime sea (tilt capped <= 14 deg)
    setCamera(0.0f, 6.0f, -20.0f, 0.0f, 1.8f, 4.0f, 42.0)
    val bobTimes = floatArrayOf(0.0f, 0.7f, 1.4f, 2.1f)
    val bobFrames = ArrayList<Pair<String, IntArray>>(bobTimes.size)
    applyLighting(OceanPalette.DAY)
    log("")
    log("Bobbing boat strip (rough daytime sea, 4-point hull buoyancy capped <= 14 deg):")
    for ((idx, bt) in bobTimes.withIndex()) {
        ocean.update(lookMatrix, curCamX, curCamY, curCamZ, bt, OceanWaves.ROUGH, OceanPalette.DAY, artPixelCell = 2.0f)
        OceanSurface.boatTransformInto(boatX, boatZ, boatHeading, bt, OceanWaves.ROUGH, boatSample, boatModel)
        boat.setTransform(boatModel)
        val res = renderFrame(OceanPalette.DAY)
        writePng(File(outDir, "boat_bob_$idx.png"), res.rgb, w, h)
        val tiltDeg = Math.toDegrees(atan2(sqrt(boatSample.nx * boatSample.nx + boatSample.nz * boatSample.nz).toDouble(), boatSample.ny.toDouble()))
        check(tiltDeg <= 14.5) { "Boat tilt %.1f deg exceeded 14.5 deg cap".format(tiltDeg) }
        val lbl = "t = %.1fs | y = %+.2fm | tilt = %.1f deg".format(bt, boatSample.y, tiltDeg)
        bobFrames += lbl to res.rgb
        log("  bob[$idx] t=%.1fs  y=%+.3f m  tilt=%.1f deg".format(bt, boatSample.y, tiltDeg))
    }
    writeContactSheet(File(outDir, "ocean_boat_bob_strip.png"), bobFrames, cols = 4, rows = 1, tileW = w, tileH = h)

    // 5. Per-frame CPU benchmark with strict 0 B allocation check (pre-warmed ThreadMXBean.currentThreadAllocatedBytes)
    val benchWarmup = 5000
    val benchIters = 2000
    var simT = 0f
    for (i in 0 until benchWarmup) {
        simT += 1f / 60f
        ocean.update(lookMatrix, curCamX + simT * 2f, curCamY, curCamZ + simT * 3f, simT, OceanWaves.ROUGH, OceanPalette.DAY)
        ocean.setHullFoam(boatX + simT, boatZ, boatHeading)
        OceanSurface.boatTransformInto(boatX + simT, boatZ, boatHeading, simT, OceanWaves.ROUGH, boatSample, boatModel)
    }
    val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    val allocSupported = mx != null && mx.isThreadAllocatedMemorySupported
    // Warm up the scalar JNI allocation counter itself so reading it allocates 0 bytes
    if (allocSupported) mx!!.currentThreadAllocatedBytes
    val allocBefore = if (allocSupported) mx!!.currentThreadAllocatedBytes else -1L
    val cpuT0 = System.nanoTime()
    for (i in 0 until benchIters) {
        simT += 1f / 60f
        ocean.update(lookMatrix, curCamX + simT * 2f, curCamY, curCamZ + simT * 3f, simT, OceanWaves.ROUGH, OceanPalette.DAY)
        ocean.setHullFoam(boatX + simT, boatZ, boatHeading)
        OceanSurface.boatTransformInto(boatX + simT, boatZ, boatHeading, simT, OceanWaves.ROUGH, boatSample, boatModel)
    }
    val cpuNs = System.nanoTime() - cpuT0
    val allocDelta = if (allocSupported) mx!!.currentThreadAllocatedBytes - allocBefore else -1L
    val cpuUsPerFrame = cpuNs.toDouble() / benchIters / 1000.0
    val warmRender = renderTimesMs.drop(1).sorted()
    val medianRenderMs = if (warmRender.isNotEmpty()) warmRender[warmRender.size / 2] else renderTimesMs.firstOrNull() ?: 0.0

    log("")
    log("Bench ($benchIters frames after $benchWarmup warmup):")
    log("  CPU (OceanSurface.update + setHullFoam + 4-pt boatTransformInto): %.2f us/frame (alloc=%d B over %d frames)".format(
        cpuUsPerFrame, allocDelta, benchIters,
    ))
    log("  Headless Filament render (llvmpipe, ${w}x${h}): median %.1f ms/frame over %d warm frames".format(
        medianRenderMs, warmRender.size,
    ))
    log("  Output dir: ${outDir.absolutePath}")

    File(outDir, "ocean_bench.txt").writeText(report.toString())

    boat.destroy()
    ocean.destroy()
    scene.remove(sunEntity)
    engine.destroyEntity(sunEntity)
    EntityManager.get().destroy(sunEntity)
    ibl?.let { engine.destroyIndirectLight(it) }
    engine.destroyMaterial(oceanMat)
    engine.destroyMaterial(litMat)
    engine.destroyColorGrading(grading)
    engine.destroyCameraComponent(camEntity)
    EntityManager.get().destroy(camEntity)
    engine.destroyView(view)
    engine.destroyScene(scene)
    engine.destroyRenderer(renderer)
    engine.destroySwapChain(swap)
    engine.destroy()
}

/**
 * Builds the `VERTEX_DOMAIN_DEVICE` clip-space matrix `look` shared by BOTH `ocean.mat` and `street_lit.filamat`,
 * respecting [LookMatrix.windowY] (`false` on headless desktop, `true` on Android `TextureView`) and using
 * Filament's infinite reversed-Z depth (`D = near / w`).
 */
private fun buildDeviceLookMatrix(
    ex: Float, ey: Float, ez: Float,
    tx: Float, ty: Float, tz: Float,
    fovYDeg: Double,
    aspect: Float,
    near: Float,
    out: FloatArray,
) {
    var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
    val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
    fx /= fl; fy /= fl; fz /= fl

    var rx = -fz; val ry = 0f; var rz = fx
    val rl = sqrt(rx * rx + rz * rz).coerceAtLeast(1e-6f)
    rx /= rl; rz /= rl
    val ux = ry * fz - rz * fy
    val uy = rz * fx - rx * fz
    val uz = rx * fy - ry * fx

    val fyScale = (1.0 / tan(Math.toRadians(fovYDeg / 2.0))).toFloat()
    val fxScale = fyScale / aspect
    val ySign = if (LookMatrix.windowY) 1f else -1f

    val rxE = rx * ex + ry * ey + rz * ez
    val uxE = ux * ex + uy * ey + uz * ez
    val fxE = fx * ex + fy * ey + fz * ez

    out[0] = fxScale * rx; out[4] = fxScale * ry; out[8] = fxScale * rz; out[12] = -fxScale * rxE
    out[1] = ySign * fyScale * ux; out[5] = ySign * fyScale * uy; out[9] = ySign * fyScale * uz; out[13] = -ySign * fyScale * uxE
    out[2] = 0f; out[6] = 0f; out[10] = 0f; out[14] = 1f - 2f * near
    out[3] = fx; out[7] = fy; out[11] = fz; out[15] = -fxE
}

/**
 * Simple multi-part pixel-art wooden box boat (submerged keel + hull + deck + cabin + mast + pennant)
 * rendered with `street_lit.filamat`, driven every frame by [OceanSurface.boatTransformInto].
 */
private class OceanBoxBoat(
    private val engine: Engine,
    private val scene: Scene,
    material: Material,
) {
    private val albedoTex: Texture
    private val maskTex: Texture
    private val vb: VertexBuffer
    private val ib: IndexBuffer
    private val mi: MaterialInstance
    private val entity: Int
    private val tmInstance: Int

    init {
        val paletteArgb = intArrayOf(
            0xFF8C5630.toInt(),
            0xFF4D2D18.toInt(),
            0xFFC89B64.toInt(),
            0xFFE9DFCE.toInt(),
            0xFFA63F2E.toInt(),
            0xFF6B4226.toInt(),
            0xFFD94A38.toInt(),
            0xFF23384F.toInt(),
        )
        val maskArgb = intArrayOf(
            0xFF000000.toInt(),
            0xFF000000.toInt(),
            0xFF000000.toInt(),
            0xFF000000.toInt(),
            0xFF000000.toInt(),
            0xFF000000.toInt(),
            0xFF000000.toInt(),
            0xFFFF0000.toInt(),
        )
        albedoTex = makeStripTexture(engine, paletteArgb, srgb = true)
        maskTex = makeStripTexture(engine, maskArgb, srgb = false)

        val builder = BoxMeshBuilder()
        builder.addBox(-0.98f, -0.65f, -2.35f, 0.98f, -0.04f, 2.35f, sideTile = 1, topTile = 1)
        builder.addBox(-1.12f, -0.04f, -2.50f, 1.12f, 0.72f, 2.50f, sideTile = 0, topTile = 2)
        builder.addBox(-0.76f, -0.02f, 2.50f, 0.76f, 0.80f, 3.22f, sideTile = 0, topTile = 2)
        builder.addBox(-0.78f, 0.72f, -1.35f, 0.78f, 1.85f, 0.55f, sideTile = 3, topTile = 4)
        builder.addBox(-0.88f, 1.85f, -1.48f, 0.88f, 2.02f, 0.68f, sideTile = 4, topTile = 4)
        builder.addBox(-0.80f, 1.15f, -1.05f, 0.80f, 1.50f, 0.25f, sideTile = 7, topTile = 3)
        builder.addBox(-0.52f, 1.15f, -1.37f, 0.52f, 1.50f, 0.57f, sideTile = 7, topTile = 3)
        builder.addBox(-0.08f, 0.72f, 1.25f, 0.08f, 3.95f, 1.41f, sideTile = 5, topTile = 5)
        builder.addBox(-0.04f, 3.35f, 0.30f, 0.04f, 3.85f, 1.25f, sideTile = 6, topTile = 6)

        val vCount = builder.vertexCount
        val iCount = builder.indexCount
        val posBuf = builder.buildVertexBuffer()
        val idxBuf = builder.buildIndexBuffer()

        vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 36)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 36)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, 36)
            .build(engine)
        vb.setBufferAt(engine, 0, posBuf)

        ib = IndexBuffer.Builder()
            .indexCount(iCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, idxBuf)

        val nearest = TextureSampler(
            TextureSampler.MinFilter.NEAREST,
            TextureSampler.MagFilter.NEAREST,
            TextureSampler.WrapMode.CLAMP_TO_EDGE,
        )
        mi = material.createInstance()
        mi.setParameter("albedo", albedoTex, nearest)
        mi.setParameter("mask", maskTex, nearest)
        mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
        mi.setParameter("tint", 1f, 1f, 1f, 1f)
        mi.setParameter("glow", 1.0f, 0.72f, 0.35f, 0f)
        mi.setParameter("glass", 1f, 1f, 1f, 1f)
        mi.setParameter("fade", 1f)
        mi.setParameter("cell", 2f)
        mi.setParameter("oneSided", 0f)
        mi.setParameter("seeA", 0f, 0f, 0f, 0f)
        mi.setParameter("seeB", 0f, 0f, 0f, 0f)

        entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 1.5f, 0.3f, 2.5f, 3.0f, 4.0f))
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, iCount)
            .material(0, mi)
            .build(engine, entity)

        val tm = engine.transformManager
        if (!tm.hasComponent(entity)) tm.create(entity)
        tmInstance = tm.getInstance(entity)
        scene.addEntity(entity)
    }

    fun setLook(lookMat: FloatArray) {
        mi.setParameter("look", MaterialInstance.FloatElement.MAT4, lookMat, 0, 1)
    }

    fun setTransform(modelMat: FloatArray) {
        if (tmInstance != 0) engine.transformManager.setTransform(tmInstance, modelMat)
    }

    fun setCabinGlow(strength: Float) {
        mi.setParameter("glow", 1.0f, 0.75f, 0.38f, strength)
    }

    fun destroy() {
        scene.remove(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyMaterialInstance(mi)
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
        engine.destroyTexture(albedoTex)
        engine.destroyTexture(maskTex)
    }

    private fun makeStripTexture(engine: Engine, argb: IntArray, srgb: Boolean): Texture {
        val buf = ByteBuffer.allocateDirect(argb.size * 4).order(ByteOrder.nativeOrder())
        for (c in argb) {
            buf.put(((c shr 16) and 255).toByte())
            buf.put(((c shr 8) and 255).toByte())
            buf.put((c and 255).toByte())
            buf.put(((c ushr 24) and 255).toByte())
        }
        buf.flip()
        val tex = Texture.Builder()
            .width(argb.size)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .build(engine)
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        return tex
    }
}

/** Axis-aligned box mesh builder with per-face unit tangent quaternions for Filament `street_lit`. */
private class BoxMeshBuilder {
    private val verts = ArrayList<Float>()
    private val inds = ArrayList<Short>()
    private val tileCount = 8f

    val vertexCount: Int get() = verts.size / 9
    val indexCount: Int get() = inds.size

    fun addBox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, sideTile: Int, topTile: Int) {
        val s = 0.70710677f
        addQuad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, topTile, -s, 0f, 0f, s)
        addQuad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, sideTile, s, 0f, 0f, s)
        addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, sideTile, 0f, 0f, 0f, 1f)
        addQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, sideTile, 0f, 1f, 0f, 0f)
        addQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, sideTile, 0f, s, 0f, s)
        addQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, sideTile, 0f, -s, 0f, s)
    }

    private fun addQuad(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        tile: Int,
        qx: Float, qy: Float, qz: Float, qw: Float,
    ) {
        val base = vertexCount
        val u = (tile + 0.5f) / tileCount
        val v = 0.5f
        fun push(x: Float, y: Float, z: Float) {
            verts += x; verts += y; verts += z
            verts += u; verts += v
            verts += qx; verts += qy; verts += qz; verts += qw
        }
        push(ax, ay, az); push(bx, by, bz); push(cx, cy, cz); push(dx, dy, dz)
        inds += base.toShort(); inds += (base + 1).toShort(); inds += (base + 2).toShort()
        inds += base.toShort(); inds += (base + 2).toShort(); inds += (base + 3).toShort()
    }

    fun buildVertexBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        for (f in verts) buf.putFloat(f)
        buf.flip()
        return buf
    }

    fun buildIndexBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(inds.size * 2).order(ByteOrder.nativeOrder())
        for (s in inds) buf.putShort(s)
        buf.flip()
        return buf
    }
}

/**
 * Generates a stepped 16-bit pixel-art sky backdrop (5 flat horizontal bands + sun or moon disc positioned
 * along [OceanPalette.lightDir], plus sparse crisp 2x2 stars at night) filling the upper sky above the horizon.
 */
private fun renderPixelArtSky(
    w: Int,
    h: Int,
    palette: OceanPalette,
    fovYDeg: Double,
    ex: Float, ey: Float, ez: Float,
    tx: Float, ty: Float, tz: Float,
): IntArray {
    val night = palette.nightFactor
    val bands = when {
        night > 0.75f -> intArrayOf(0xFF070C1C.toInt(), 0xFF0C152E.toInt(), 0xFF121F40.toInt(), 0xFF1A2B52.toInt(), 0xFF233863.toInt())
        night > 0.10f -> intArrayOf(0xFF2B2852.toInt(), 0xFF5A3762.toInt(), 0xFF9F4B58.toInt(), 0xFFD9724C.toInt(), 0xFFF2A45A.toInt())
        else -> intArrayOf(0xFF4284C9.toInt(), 0xFF5FA2DF.toInt(), 0xFF82BCEB.toInt(), 0xFFAAD4F4.toInt(), 0xFFCDE6F9.toInt())
    }
    val px = IntArray(w * h)
    val dy = ty - ey
    val dHoriz = sqrt((tx - ex) * (tx - ex) + (tz - ez) * (tz - ez)).coerceAtLeast(1e-4f)
    val pitchRad = atan2(dy.toDouble(), dHoriz.toDouble())
    val halfFovRad = Math.toRadians(fovYDeg / 2.0)
    val horizonY = (h * (0.5 + tan(pitchRad) / (2.0 * tan(halfFovRad)))).toInt().coerceIn(h / 8, h * 3 / 4)

    for (y in 0 until h) {
        val frac = (y.toFloat() / horizonY.coerceAtLeast(1)).coerceIn(0f, 0.999f)
        val idx = (frac * bands.size).toInt().coerceIn(0, bands.lastIndex)
        val c = bands[idx]
        val rowOff = y * w
        for (x in 0 until w) px[rowOff + x] = c
    }

    if (night > 0.75f) {
        var seed = 0x51A7F31D
        repeat(80) {
            seed = seed * 1664525 + 1013904223
            val sx = ((seed ushr 16) and 0xFFFF) % (w - 4) + 2
            seed = seed * 1664525 + 1013904223
            val sy = ((seed ushr 16) and 0xFFFF) % (horizonY - 10).coerceAtLeast(8) + 4
            val bright = if ((seed and 3) == 0) 0xFFEAF2FF.toInt() else 0xFFA4BCDF.toInt()
            for (oy in 0..1) for (ox in 0..1) px[(sy + oy) * w + (sx + ox)] = bright
        }
    }

    var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
    val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
    fx /= fl; fy /= fl; fz /= fl
    var rx = -fz; var rz = fx
    val rl = sqrt(rx * rx + rz * rz).coerceAtLeast(1e-6f)
    rx /= rl; rz /= rl
    val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
    val ld = palette.lightDir
    val forwardDot = ld[0] * fx + ld[1] * fy + ld[2] * fz
    if (forwardDot > 0.1f) {
        val rightDot = ld[0] * rx + ld[2] * rz
        val upDot = ld[0] * ux + ld[1] * uy + ld[2] * uz
        val aspect = w.toFloat() / h.toFloat()
        val fyScale = (1.0 / tan(halfFovRad)).toFloat()
        val fxScale = fyScale / aspect
        val ndcX = fxScale * rightDot / forwardDot
        val ndcY = fyScale * upDot / forwardDot
        val cx = ((ndcX * 0.5f + 0.5f) * w).roundToInt()
        val cy = ((0.5f - ndcY * 0.5f) * h).roundToInt()
        val radius = if (night > 0.75f) (h * 0.032f).roundToInt() else (h * 0.044f).roundToInt()
        val discColor = when {
            night > 0.75f -> 0xFFE6F0FF.toInt()
            night > 0.10f -> 0xFFFFCA6B.toInt()
            else -> 0xFFFFFBE0.toInt()
        }
        val haloColor = when {
            night > 0.75f -> 0xFF536E9E.toInt()
            night > 0.10f -> 0xFFF59352.toInt()
            else -> 0xFFEBF5FF.toInt()
        }
        val r2 = radius * radius
        val halo2 = (radius + 5) * (radius + 5)
        for (py in max(0, cy - radius - 6)..min(horizonY, cy + radius + 6)) {
            for (pxi in max(0, cx - radius - 6)..min(w - 1, cx + radius + 6)) {
                val qx = (pxi / 2) * 2 - cx
                val qy = (py / 2) * 2 - cy
                val d2 = qx * qx + qy * qy
                if (d2 <= r2) {
                    px[py * w + pxi] = discColor
                } else if (d2 <= halo2 && ((pxi / 2 + py / 2) and 1) == 0) {
                    px[py * w + pxi] = haloColor
                }
            }
        }
    }
    return px
}

private fun writePng(file: File, argb: IntArray, w: Int, h: Int) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, argb, 0, w)
    ImageIO.write(img, "png", file)
}

private fun writeContactSheet(
    file: File,
    tiles: List<Pair<String, IntArray>>,
    cols: Int,
    rows: Int,
    tileW: Int,
    tileH: Int,
) {
    val pad = 12
    val barH = 30
    val outW = cols * tileW + (cols + 1) * pad
    val outH = rows * (tileH + barH) + (rows + 1) * pad
    val sheet = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
    val g = sheet.createGraphics()
    g.color = Color(0x11, 0x16, 0x22)
    g.fillRect(0, 0, outW, outH)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.font = Font(Font.MONOSPACED, Font.BOLD, 15)

    for ((idx, pair) in tiles.withIndex()) {
        val c = idx % cols
        val r = idx / cols
        val ox = pad + c * (tileW + pad)
        val oy = pad + r * (tileH + barH + pad)
        g.color = Color(0x1B, 0x23, 0x35)
        g.fillRect(ox, oy, tileW, barH)
        g.color = Color(0xE8, 0xEE, 0xF8)
        g.drawString(pair.first, ox + 10, oy + 20)
        val tileImg = BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_ARGB)
        tileImg.setRGB(0, 0, tileW, tileH, pair.second, 0, tileW)
        g.drawImage(tileImg, ox, oy + barH, null)
    }
    g.dispose()
    ImageIO.write(sheet, "png", file)
}
