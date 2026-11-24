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
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.domain.WeatherState
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import com.lifemax.rpg.feature.habits.presentation.scene3d.SkyState
import com.lifemax.rpg.feature.habits.presentation.scene3d.SkySurface
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Standalone headless Filament preview for the G016 pixel-art sky dome (`-Dmode=sky`).
 *
 * Renders 12 preview shots covering every required G016 condition + a 4x3 labeled contact sheet:
 *  - Dawn, noon, dusk (crescent moon over clock tower & western sunset), midnight (full & crescent moon)
 *  - Clear, overcast, rain, and morning mist
 *  - Camera turned 0 -> 45 -> 90 degrees at midnight proving stars & clouds stay fixed in world sky
 *  - Foreground rooftop + clock-tower silhouette (`street_lit.filamat`) proving far-plane depth occlusion
 *  - `sky_bench.txt` verifying 0 B/frame CPU allocation and render times
 *
 * Run via:
 *   gradle :desktop:preview -Dmode=sky
 */
fun main(args: Array<String>) {
    val outRoot = args.firstOrNull() ?: "build/preview"
    renderSky(outRoot)
}

private data class SkyShot(
    val id: String,
    val title: String,
    val hour: Float,
    val date: String,
    val weather: WeatherState,
    val season: SeasonState,
    val yawDeg: Float,
    val pitchDeg: Float,
    val timeSec: Float,
    val showSkyline: Boolean = true,
)

fun renderSky(outRoot: String) {
    val outDir = File(outRoot, "sky").also { it.mkdirs() }
    val w = System.getProperty("w")?.toIntOrNull() ?: 960
    val h = (w * 9 / 16).coerceAtLeast(360)
    val aspect = w.toFloat() / h.toFloat()
    val fovYDeg = 52f

    val assets = File(assetsDir(), "filament")
    val skyMatFile = File(assets, "sky.filamat")
    check(skyMatFile.exists()) {
        "Missing ${skyMatFile.absolutePath}. Compile it first with Filament v1.81.0 matc:\n" +
            "  matc -p mobile -a opengl -o ${skyMatFile.path} kotlin-street/filament/materials/sky.mat"
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

    val skyMat = loadMaterial(skyMatFile)
    val litMat = loadMaterial(litMatFile)

    val sky = SkySurface(engine, scene, skyMat)
    val skyline = PreviewSkyline(engine, scene, litMat)
    val skyState = SkyState()

    val sunEntity = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.DIRECTIONAL)
        .color(1f, 0.95f, 0.88f)
        .intensity(2.5f)
        .direction(0.2f, -0.5f, 0.8f)
        .castShadows(false)
        .build(engine, sunEntity)
    scene.addEntity(sunEntity)

    val sh = FloatArray(12).apply {
        this[0] = 0.14f; this[1] = 0.16f; this[2] = 0.22f
    }
    val ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1.0f).build(engine)
    scene.indirectLight = ibl

    val lookMatrix = FloatArray(16)
    val readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

    val weatherOvercast = WeatherState(cloudiness = 0.88f, rain = 0f, wet = 0f, mist = 0f)
    val weatherRain = WeatherState(cloudiness = 0.95f, rain = 0.88f, wet = 0.85f, mist = 0f)
    val weatherMist = WeatherState(cloudiness = 0.25f, rain = 0f, wet = 0f, mist = 0.86f)

    // Known lunar dates matching MoonPhase.kt (new moon epoch 2000-01-06):
    //   2000-01-10 -> waxing crescent (~0.125), 2000-01-14 -> first quarter (~0.25), 2000-01-21 -> full (~0.50)
    val dateCrescent = "2000-01-10"
    val dateQuarter = "2000-01-14"
    val dateFull = "2000-01-21"

    val shots = listOf(
        SkyShot("01_dawn_clear", "Dawn 06:15 Clear (East)", 6.25f, dateFull, WeatherState.CLEAR, Season.SUMMER, 96f, -10f, 12f),
        SkyShot("02_noon_clear", "Noon 12:00 Clear (South)", 12.0f, dateFull, WeatherState.CLEAR, Season.SUMMER, 180f, -34f, 18f),
        SkyShot("03_dusk_crescent_moon", "Dusk 19:45 Crescent Moon (East)", 19.75f, dateCrescent, WeatherState.CLEAR, Season.SUMMER, 112f, -12f, 24f),
        SkyShot("04_dusk_sunset_west", "Dusk 19:00 Sunset (West)", 19.0f, dateCrescent, WeatherState.CLEAR, Season.SUMMER, 282f, -10f, 24f),
        SkyShot("05_midnight_full_moon", "Midnight 00:30 Full Moon (South)", 0.5f, dateFull, WeatherState.CLEAR, Season.SUMMER, 188f, -34f, 30f),
        SkyShot("06_midnight_crescent_moon", "Midnight 00:30 Crescent (South)", 0.5f, dateCrescent, WeatherState.CLEAR, Season.SUMMER, 188f, -34f, 30f),
        SkyShot("07_day_overcast", "Day 13:00 Overcast", 13.0f, dateFull, weatherOvercast, Season.SUMMER, 180f, -14f, 40f),
        SkyShot("08_day_rain", "Day 14:00 Heavy Rain", 14.0f, dateFull, weatherRain, Season.SUMMER, 180f, -14f, 45f),
        SkyShot("09_dawn_mist", "Dawn 06:30 Morning Mist", 6.5f, dateFull, weatherMist, Season.SUMMER, 98f, -9f, 15f),
        SkyShot("10_turn_yaw0_north", "23:30 Turn Check: Yaw 0 (North)", 23.5f, dateQuarter, WeatherState.CLEAR, Season.SUMMER, 0f, -18f, 10f, showSkyline = false),
        SkyShot("11_turn_yaw45_ne", "23:30 Turn Check: Yaw 45 (NE)", 23.5f, dateQuarter, WeatherState.CLEAR, Season.SUMMER, 45f, -18f, 10f, showSkyline = false),
        SkyShot("12_turn_yaw90_east", "23:30 Turn Check: Yaw 90 (East)", 23.5f, dateQuarter, WeatherState.CLEAR, Season.SUMMER, 90f, -18f, 10f, showSkyline = false),
    )

    fun renderShot(shot: SkyShot): BufferedImage {
        skyState.update(shot.hour, shot.date, shot.weather, shot.season, shot.timeSec)
        sky.updateForCamera(skyState, shot.yawDeg, shot.pitchDeg, fovYDeg, aspect, artCellPx = 2f, windowY = LookMatrix.windowY)

        // Sync Filament camera & skyline look matrix with the exact same yaw/pitch basis
        val ya = shot.yawDeg * (PI / 180.0)
        val pa = shot.pitchDeg * (PI / 180.0)
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
        val rx = cos(ya).toFloat(); val rz = sin(ya).toFloat()
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy

        camera.setProjection(fovYDeg.toDouble(), aspect.toDouble(), 0.5, 5000.0, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 2.0, 0.0, fx.toDouble(), 2.0 + fy.toDouble(), fz.toDouble(), ux.toDouble(), uy.toDouble(), uz.toDouble())
        buildSkyDeviceLook(0f, 2f, 0f, rx, 0f, rz, ux, uy, uz, fx, fy, fz, fovYDeg, aspect, 0.5f, lookMatrix)
        skyline.setVisible(shot.showSkyline)
        skyline.setLook(lookMatrix)
        skyline.setAmbientTint(skyState.dark)

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
        check(done.get()) { "readPixels did not complete for ${shot.id}" }

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        readBuf.rewind()
        for (py in 0 until h) {
            for (px in 0 until w) {
                val r = readBuf.get().toInt() and 0xFF
                val g = readBuf.get().toInt() and 0xFF
                val b = readBuf.get().toInt() and 0xFF
                readBuf.get()
                // Headless Filament readPixels is bottom-up when windowY == false
                val dstY = if (LookMatrix.windowY) (h - 1 - py) else py
                img.setRGB(px, dstY, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return img
    }

    val rendered = ArrayList<Pair<SkyShot, BufferedImage>>()
    val timesMs = ArrayList<Double>()

    log("=== Filament Sky Dome Preview (G016) ===")
    log("Resolution: ${w}x${h}, fovY=${fovYDeg} deg, windowY=${LookMatrix.windowY}")

    for (shot in shots) {
        val t0 = System.nanoTime()
        val img = renderShot(shot)
        val dtMs = (System.nanoTime() - t0) / 1_000_000.0
        timesMs += dtMs
        val outFile = File(outDir, "${shot.id}.png")
        ImageIO.write(img, "PNG", outFile)
        rendered += shot to img
        log(
            "  %-28s  %.1f ms  sun=(az=%.1f el=%.1f vis=%.2f) moon=(az=%.1f el=%.1f vis=%.2f ph=%.2f) stars=%.2f"
                .format(
                    shot.id, dtMs,
                    skyState.sunAzimuthDeg, skyState.sunElevationDeg, skyState.sunVisibility,
                    skyState.moonAzimuthDeg, skyState.moonElevationDeg, skyState.moonVisibility, skyState.moonPhase,
                    skyState.starBrightness,
                ),
        )
    }

    // Build 4x3 labeled contact sheet
    val cols = 4
    val rows = 3
    val thumbW = 480
    val thumbH = 270
    val labelH = 26
    val pad = 8
    val sheetW = pad + cols * (thumbW + pad)
    val sheetH = pad + rows * (thumbH + labelH + pad)
    val sheet = BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_ARGB)
    val g2 = sheet.createGraphics()
    g2.color = Color(14, 16, 24)
    g2.fillRect(0, 0, sheetW, sheetH)
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g2.font = Font(Font.SANS_SERIF, Font.BOLD, 14)

    for ((idx, pair) in rendered.withIndex()) {
        val (shot, img) = pair
        val c = idx % cols
        val r = idx / cols
        val x = pad + c * (thumbW + pad)
        val y = pad + r * (thumbH + labelH + pad)
        g2.drawImage(img, x, y, thumbW, thumbH, null)
        g2.color = Color(24, 28, 40)
        g2.fillRect(x, y + thumbH, thumbW, labelH)
        g2.color = Color(230, 236, 248)
        g2.drawString(shot.title, x + 8, y + thumbH + 18)
    }
    g2.dispose()
    ImageIO.write(sheet, "PNG", File(outDir, "sky_contact_sheet.png"))

    // Benchmark 2,000 per-frame CPU updates for 0-byte allocation guarantee
    val benchState = SkyState()
    repeat(200) { i ->
        benchState.update(19.2f, dateCrescent, WeatherState.CLEAR, Season.SUMMER, i * 0.016f)
    }
    val tmx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
    val tid = Thread.currentThread().id
    val allocBefore = tmx?.getThreadAllocatedBytes(tid) ?: -1L
    val b0 = System.nanoTime()
    repeat(2000) { i ->
        val hr = (i % 240) * 0.1f
        benchState.update(hr, dateCrescent, WeatherState.CLEAR, Season.SUMMER, i * 0.016f)
    }
    val benchMs = (System.nanoTime() - b0) / 1_000_000.0
    val allocAfter = tmx?.getThreadAllocatedBytes(tid) ?: -1L
    val allocBytes = if (allocBefore >= 0 && allocAfter >= 0) allocAfter - allocBefore else -1L

    log("CPU SkyState.update x2000: %.3f ms total (%.4f ms/frame), allocated=%d B".format(benchMs, benchMs / 2000.0, allocBytes))
    log("Avg Filament render time: %.1f ms/frame".format(timesMs.average()))
    File(outDir, "sky_bench.txt").writeText(report.toString())

    // Clean up Filament resources
    sky.destroy()
    skyline.destroy()
    scene.removeEntity(sunEntity)
    engine.destroyEntity(sunEntity)
    EntityManager.get().destroy(sunEntity)
    engine.destroyIndirectLight(ibl)
    engine.destroyMaterial(skyMat)
    engine.destroyMaterial(litMat)
    engine.destroyColorGrading(grading)
    engine.destroyView(view)
    engine.destroyScene(scene)
    engine.destroyRenderer(renderer)
    engine.destroyCameraComponent(camEntity)
    EntityManager.get().destroy(camEntity)
    engine.destroySwapChain(swap)
    engine.destroy()
}

private fun buildSkyDeviceLook(
    ex: Float, ey: Float, ez: Float,
    rx: Float, ry: Float, rz: Float,
    ux: Float, uy: Float, uz: Float,
    fx: Float, fy: Float, fz: Float,
    fovYDeg: Float,
    aspect: Float,
    near: Float,
    out: FloatArray,
) {
    val fyScale = (1.0 / tan(fovYDeg * (PI / 360.0))).toFloat()
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
 * Simple foreground rooftop & clock-tower silhouette ring rendered with `street_lit.filamat` at radius ~65 m
 * around the camera so the sky preview proves reversed-Z far-plane depth occlusion and frames the Main Street
 * dusk crescent-moon encounter cleanly.
 */
private class PreviewSkyline(
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
    private var visible: Boolean = true

    init {
        val whiteBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(255.toByte()); put(255.toByte()); put(255.toByte()); put(255.toByte()); flip()
        }
        val zeroBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(0); put(0); put(0); put(0); flip()
        }
        albedoTex = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).build(engine)
        albedoTex.setImage(engine, 0, Texture.PixelBufferDescriptor(whiteBuf, Texture.Format.RGBA, Texture.Type.UBYTE, 1))
        maskTex = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).build(engine)
        maskTex.setImage(engine, 0, Texture.PixelBufferDescriptor(zeroBuf, Texture.Format.RGBA, Texture.Type.UBYTE, 1))

        // Build a 360-degree low town rooftop silhouette ring + a clock tower at azimuth 106 deg (ESE)
        val quads = ArrayList<FloatArray>() // each: [azCenterDeg, widthDeg, baseH, topH]
        for (i in 0 until 36) {
            val az = i * 10f
            val hRoof = 4.2f + ((i * 7) % 5) * 1.0f
            quads += floatArrayOf(az, 10.2f, -4f, hRoof)
        }
        // Clock tower base + belfry + spire near azimuth 104 deg (framing the rising dusk crescent at 112 deg!)
        quads += floatArrayOf(104f, 5.4f, -4f, 15.0f)
        quads += floatArrayOf(104f, 3.8f, 15.0f, 18.8f)
        quads += floatArrayOf(104f, 1.5f, 18.8f, 22.8f)

        val vCount = quads.size * 4
        val iCount = quads.size * 6
        val vBuf = ByteBuffer.allocateDirect(vCount * 36).order(ByteOrder.nativeOrder())
        val iBuf = ByteBuffer.allocateDirect(iCount * 2).order(ByteOrder.nativeOrder())

        val dist = 65f
        for ((qi, q) in quads.withIndex()) {
            val az0 = (q[0] - q[1] * 0.5f) * (PI / 180.0)
            val az1 = (q[0] + q[1] * 0.5f) * (PI / 180.0)
            val x0 = (sin(az0) * dist).toFloat(); val z0 = (-cos(az0) * dist).toFloat()
            val x1 = (sin(az1) * dist).toFloat(); val z1 = (-cos(az1) * dist).toFloat()
            val y0 = q[2]; val y1 = q[3]

            fun putVert(x: Float, y: Float, z: Float, u: Float, v: Float) {
                vBuf.putFloat(x); vBuf.putFloat(y); vBuf.putFloat(z)
                vBuf.putFloat(u); vBuf.putFloat(v)
                // Identity tangent quaternion
                vBuf.putFloat(0f); vBuf.putFloat(0f); vBuf.putFloat(0f); vBuf.putFloat(1f)
            }
            putVert(x0, y0, z0, 0f, 1f)
            putVert(x1, y0, z1, 1f, 1f)
            putVert(x1, y1, z1, 1f, 0f)
            putVert(x0, y1, z0, 0f, 0f)

            val base = (qi * 4).toShort()
            iBuf.putShort(base); iBuf.putShort((base + 1).toShort()); iBuf.putShort((base + 2).toShort())
            iBuf.putShort(base); iBuf.putShort((base + 2).toShort()); iBuf.putShort((base + 3).toShort())
        }
        vBuf.flip()
        iBuf.flip()

        vb = VertexBuffer.Builder()
            .vertexCount(vCount)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 36)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 36)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, 36)
            .build(engine)
        vb.setBufferAt(engine, 0, vBuf)

        ib = IndexBuffer.Builder()
            .indexCount(iCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, iBuf)

        val nearest = TextureSampler(
            TextureSampler.MinFilter.NEAREST,
            TextureSampler.MagFilter.NEAREST,
            TextureSampler.WrapMode.CLAMP_TO_EDGE,
        )
        mi = material.createInstance()
        mi.setParameter("albedo", albedoTex, nearest)
        mi.setParameter("mask", maskTex, nearest)
        mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
        mi.setParameter("tint", 0.08f, 0.09f, 0.14f, 1f)
        mi.setParameter("glow", 1f, 0.72f, 0.35f, 0f)
        mi.setParameter("glass", 1f, 1f, 1f, 1f)
        mi.setParameter("fade", 1f)
        mi.setParameter("cell", 2f)
        mi.setParameter("oneSided", 0f)
        mi.setParameter("seeA", 0f, 0f, 0f, 0f)
        mi.setParameter("seeB", 0f, 0f, 0f, 0f)

        entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 10f, 0f, 80f, 40f, 80f))
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(2)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, iCount)
            .material(0, mi)
            .build(engine, entity)

        scene.addEntity(entity)
    }

    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) scene.addEntity(entity) else scene.removeEntity(entity)
    }

    fun setLook(lookMat: FloatArray) {
        mi.setParameter("look", MaterialInstance.FloatElement.MAT4, lookMat, 0, 1)
    }

    fun setAmbientTint(dark: Float) {
        val r = 0.09f * (1f - dark * 0.65f)
        val g = 0.10f * (1f - dark * 0.62f)
        val b = 0.15f * (1f - dark * 0.55f)
        mi.setParameter("tint", r, g, b, 1f)
    }

    fun destroy() {
        if (visible) scene.removeEntity(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
        engine.destroyTexture(albedoTex)
        engine.destroyTexture(maskTex)
        engine.destroyMaterialInstance(mi)
    }
}
