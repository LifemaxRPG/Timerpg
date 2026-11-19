import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.LinearToneMapper
import com.google.android.filament.Material
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLights
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaModel
import com.lifemax.rpg.feature.habits.presentation.scene3d.Paint
import com.lifemax.rpg.feature.habits.presentation.scene3d.PixImg
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.TEXEL
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreReuse
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreState
/**
 * The stores' desktop renders (-Dmode=filament-stores), headless through Filament like -Dmode=filament-cinema: each
 * variant (the general store open in 1925 and 1955, closed and boarded, reused as an antiques shop, a chapel and a cafe;
 * the market of 1956 and 1966; the supermarket of 1975 and 1986; the superstore of 1998 and 2016, and closed) on its
 * plot by the road, from the street, the storefront close up, three-quarter, behind and above, at 13:00, dusk (19.3) and
 * night (22.5). -DstoresFrom=street.json renders the town's own graph.stores[] instead. -Dstores=general,market,... (variant names or kinds) for some only; -Dhour= one hour.
 * Writes into build/preview/stores/ and stores.txt (each variant's pictures in texels and bytes, its triangles, parts and lights).
 */
fun renderStores(out: String) {
    val dir = File(out, "stores").also { it.mkdirs() }
    val only = System.getProperty("stores")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 19.3f, 22.5f)
    val report = StringBuilder()
    val variants = listOf(
        "general_1925" to StoreInfo.sample(StoreKind.GENERAL, 1925, seed = 4),
        "general_1955" to StoreInfo.sample(StoreKind.GENERAL, 1955, seed = 5),
        "general_brick_1940" to StoreInfo.sample(StoreKind.GENERAL, 1940, seed = 2),
        "general_closed_1995" to StoreInfo.sample(StoreKind.GENERAL, 1995, seed = 4, state = StoreState.CLOSED).copy(closed = 1988),
        "general_antiques_2010" to StoreInfo.sample(StoreKind.GENERAL, 2010, seed = 4, state = StoreState.REUSED, reuse = StoreReuse.ANTIQUES),
        "general_church_2000" to StoreInfo.sample(StoreKind.GENERAL, 2000, seed = 7, state = StoreState.REUSED, reuse = StoreReuse.CHURCH),
        "general_cafe_2022" to StoreInfo.sample(StoreKind.GENERAL, 2022, seed = 9, state = StoreState.REUSED, reuse = StoreReuse.CAFE),
        "market_1956" to StoreInfo.sample(StoreKind.MARKET, 1956, seed = 3),
        "market_1966" to StoreInfo.sample(StoreKind.MARKET, 1966, seed = 6),
        "market_closed_1984" to StoreInfo.sample(StoreKind.MARKET, 1984, seed = 3, state = StoreState.CLOSED),
        "market_thrift_1995" to StoreInfo.sample(StoreKind.MARKET, 1995, seed = 3, state = StoreState.REUSED, reuse = StoreReuse.THRIFT),
        "supermarket_1975" to StoreInfo.sample(StoreKind.SUPERMARKET, 1975, seed = 3),
        "supermarket_1986" to StoreInfo.sample(StoreKind.SUPERMARKET, 1986, seed = 8),
        "supermarket_gym_2020" to StoreInfo.sample(StoreKind.SUPERMARKET, 2020, seed = 5, state = StoreState.REUSED, reuse = StoreReuse.GYM),
        "superstore_1998" to StoreInfo.sample(StoreKind.SUPERSTORE, 1998, seed = 3),
        "superstore_2016" to StoreInfo.sample(StoreKind.SUPERSTORE, 2016, seed = 4),
        "superstore_closed_2038" to StoreInfo.sample(StoreKind.SUPERSTORE, 2038, seed = 4, state = StoreState.CLOSED),
    ).let { samples ->
        // -DstoresFrom=street.json: the town's own stores (graph.stores[], as the generator exported them) instead
        val from = System.getProperty("storesFrom")?.let { File(it) }
        if (from == null) samples else {
            val root = org.json.JSONObject(from.readText())
            StoreInfo.fromGraph(root.optJSONObject("graph") ?: root).map { "${from.parentFile?.name ?: "town"}_${it.kind.name.lowercase()}_${it.year}" to it }
        }
    }.filter { (n, i) -> only == null || n in only || i.kind.name.lowercase() in only }
    if (variants.isEmpty()) return
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = File(assetsDir(), "filament")
    fun mat(n: String): Material { val b = File(assets, "$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
    val lit = mat("street_lit")
    val proxy = mat("shadow_proxy")
    for ((name, info) in variants) {
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val built = StoreKit.buildWithSite(info)
        val ms = (System.nanoTime() - t0) / 1e6
        val m = built.model
        val heap = (rt.totalMemory() - rt.freeMemory() - before) / 1024
        report.appendLine("$name (${info.kind}, ${info.year}, ${info.state}${info.reuse?.let { " as $it" } ?: ""}, \"${info.signName}\"): ${m.parts.size} parts, ${m.triangles} triangles, " +
            "${m.lights.size} lights, pictures ${m.texels} texels = ${m.bytes / 1024} KiB (${m.parts.withIndex().joinToString(" ") { (i, p) -> "${built.names.getOrElse(i) { p.role.name.lowercase() }}:${p.mesh.texW}x${p.mesh.texH}${if (p.mesh.mask != null) "+mask" else ""}" }}), " +
            "built in ${"%.0f".format(ms)} ms, heap +$heap KiB; plot ${info.lot.toInt()} x ${info.deep.toInt()}, footprint x ${m.footprint[0].toInt()}..${m.footprint[2].toInt()} z ${m.footprint[1].toInt()}..${m.footprint[3].toInt()}, ${m.height.toInt()} tall")
        val w = 1600; val h = 900
        val stage = StoreStage(engine, lit, proxy, w, h)
        val L = info.lot; val D = info.deep
        stage.ground(-L * 1.6f - 600f, L * 1.6f + 600f)
        field(stage, -L * 1.6f - 600f, L * 1.6f + 600f, D + 1200f)
        val layer = CinemaLayer(stage.props)
        val at = floatArrayOf(0f, 0f, 1f, 0f, 0f, -1f, 0f)
        layer.add(m, at)
        val lamps = CinemaLights(engine, stage.scene).also { it.add(m.lights, at) }
        stage.after = { dark -> layer.update(dark, 0.0); lamps.update(dark) }
        // the main block in the world: model z -> world -z
        val bx0 = built.block[0]; val bx1 = built.block[2]; val fz = -built.block[1]; val bz = -built.block[3]
        val bcx = (bx0 + bx1) / 2f; val bw = bx1 - bx0
        val top = m.height
        val s = (maxOf(bw, bz - fz) / 400f).coerceAtLeast(1f)
        val shots = listOf(
            "street" to Triple(floatArrayOf(bcx * .3f, 110f, -620f - fz * .25f), floatArrayOf(bcx, top * .45f, fz), 58f),
            "front" to Triple(floatArrayOf(bcx + bw * .18f, 70f + 20f * s, fz - 240f * s), floatArrayOf(bcx, 60f, fz), 55f),
            "quarter" to Triple(floatArrayOf(bcx - bw * 1.1f - 200f, 160f + bw * .35f, fz - bw * .7f - 150f), floatArrayOf(bcx, top * .3f, (fz + bz) / 2f), 52f),
            "behind" to Triple(floatArrayOf(bcx + bw * .9f + 150f, 200f + bw * .3f, D + bw * .6f + 160f), floatArrayOf(bcx, 40f, (fz + bz) / 2f + (bz - fz) * .2f), 54f),
            "above" to Triple(floatArrayOf(-L * .45f, maxOf(L, D) * 1.05f + 300f, -D * .35f - 200f), floatArrayOf(0f, 0f, D * .5f), 52f),
        )
        for (hour in hours) {
            val tag = "%.1f".format(hour).replace(".0", "").replace(".", "_")
            for ((sn, sh) in shots) stage.shoot(File(dir, "${name}_${sn}_$tag.png"), hour, sh.first, sh.second, sh.third)
        }
        lamps.destroy()
        stage.destroy()
    }
    engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
    engine.destroy()
    File(dir, "stores.txt").writeText(report.toString())
    print(report)
}

/** Fields round the plot (the town is not here): pale grass behind the road and across it. */
private fun field(stage: StoreStage, x0: Float, x1: Float, far: Float) {
    val tile = PixImg(32, 32).also { im ->
        val rnd = Paint.Rnd(8)
        for (y in 0 until 32) for (x in 0 until 32) { val t = rnd.next(); im[x, y] = if (t < .2f) Paint.c(0x3E6E3A) else if (t < .3f) Paint.c(0x5E8E4A) else Paint.c(0x4A7E42) }
    }
    val k = 32f * TEXEL
    for ((z0, z1) in listOf(0f to far, -900f to -150f)) {
        val pos = floatArrayOf(x0, -.05f, z1, x1, -.05f, z1, x1, -.05f, z0, x0, -.05f, z0)
        val uv = floatArrayOf(x0 / k, z1 / k, x1 / k, z1 / k, x1 / k, z0 / k, x0 / k, z0 / k)
        val n = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        val t = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
        val mesh = PropMesh(pos, uv, n, t, intArrayOf(0, 1, 2, 0, 2, 3), 32, 32, tile.px, null, floatArrayOf(x0, 0f, z0, x1, 0f, z1))
        stage.props.addMesh(mesh, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false, tiled = true)
    }
}

/** The props preview's stage (a sun and sky light as the street's, one camera, a swap chain to read back), for the store. */
private class StoreStage(val engine: Engine, lit: Material, proxy: Material, val w: Int, val h: Int) {
    val scene = engine.createScene()
    val view = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    val sun = EntityManager.get().create()
    val props = PropLayer(engine, scene, lit, proxy)
    val swap = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
    val renderer = engine.createRenderer().also { it.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) } }
    val grading: ColorGrading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)
    var ibl: IndirectLight? = null
    val look = FloatArray(16)

    init {
        view.scene = scene; view.camera = camera
        view.dithering = View.Dithering.NONE; view.antiAliasing = View.AntiAliasing.NONE; view.sampleCount = 1
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = true; view.shadowType = View.ShadowType.PCF
        view.viewport = Viewport(0, 0, w, h)
        camera.setExposure(1f)
        val shadow = LightManager.ShadowOptions().apply {
            mapSize = 4096; shadowCascades = 3; cascadeSplitPositions = floatArrayOf(.15f, .35f, .65f); stable = true; lispsm = false
            constantBias = .05f; normalBias = .4f; polygonOffsetConstant = .5f; polygonOffsetSlope = .2f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 1f, 1f).intensity(1f).direction(0f, -1f, 0f).castShadows(true).shadowOptions(shadow).build(engine, sun)
        scene.addEntity(sun)
    }

    /** Called after the props' own update each shot (the store's lights). */
    var after: (Float) -> Unit = {}

    /** The pavement, kerb and road along z (wall at z 0, road from -38), from [x0] to [x1], in pixel slabs. */
    fun ground(x0: Float, x1: Float) {
        val tw = 58; val th = 117 // 48 x 96 street units
        val im = PixImg(tw, th)
        val day = skyColors(13f)
        fun c(col: Color) = (0xFF shl 24) or ((col.red * 255).toInt() shl 16) or ((col.green * 255).toInt() shl 8) or (col.blue * 255).toInt()
        val pave = c(day.pavement); val paveD = c(lerp(day.pavement, Color.Black, .1f)); val joint = c(lerp(day.pavement, Color.Black, .2f))
        val kerb = c(day.kerb); val kerbL = c(lerp(day.kerb, Color.White, .3f))
        val road = c(day.road); val roadD = c(lerp(day.road, Color.Black, .2f)); val roadL = c(lerp(day.road, Color.White, .1f))
        val rnd = Paint.Rnd(5)
        for (y in 0 until th) for (x in 0 until tw) {
            val z = y * TEXEL
            im[x, y] = when {
                z < Crossroads.PAVE -> if (x % 29 == 0 || y % 29 == 0) joint else if ((x / 29 + y / 29) % 2 == 0) pave else paveD
                z < Crossroads.PAVE + 1.6f -> kerbL
                z < Crossroads.PAVE + Crossroads.KERB -> kerb
                else -> if (z > 70f && z < 72f && x < 27) c(lerp(day.kerb, day.road, .3f)) else if (rnd.next() < .06f) roadD else if (rnd.next() < .03f) roadL else road
            }
        }
        for (y in 0..1) for (x in 0 until tw) im[x, y] = c(lerp(day.pavement, Color.Black, .25f))
        val z0 = -150f
        val pos = floatArrayOf(x0, 0f, 0f, x1, 0f, 0f, x1, 0f, z0, x0, 0f, z0)
        val uv = floatArrayOf(x0 / 48f, 0f, x1 / 48f, 0f, x1 / 48f, -z0 / 96f, x0 / 48f, -z0 / 96f)
        val n = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        val t = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
        val mesh = PropMesh(pos, uv, n, t, intArrayOf(0, 1, 2, 0, 2, 3), tw, th, im.px, null, floatArrayOf(x0, 0f, z0, x1, 0f, 0f))
        props.addMesh(mesh, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false, tiled = true)
    }

    fun light(hour: Float) {
        val key = SceneLight.key(hour)
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        // as the street lights its view: the key across it, down, out of the fronts towards the viewer (the street's
        // own view, so both camera views of the lineup share one light)
        val cdx = 0f; val cdz = 1f
        val dx = key.across * cdz - key.out * cdx
        val dy = -key.down
        val dz = -key.across * cdx - key.out * cdz
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        lm.setDirection(li, dx / dl, dy / dl, dz / dl)
        val warm = (1f - key.down).coerceIn(0f, 1f)
        val sc = if (key.cool > .5f) Color(0xFF9DB4FF) else lerp(Color(0xFFFFF6E8), Color(0xFFFFB070), warm * warm)
        lm.setColor(li, lin(sc.red), lin(sc.green), lin(sc.blue))
        lm.setIntensity(li, .45f * key.strength * PI.toFloat())
        lm.setShadowCaster(li, key.cool < .5f && key.strength > .05f)
        val sky = skyColors(hour)
        val tone = sky.sky.lastOrNull() ?: Color.White
        val night = sqrt(sky.dark.coerceIn(0f, 1f))
        var level = lerp(Color(.8f, .8f, .8f), Color(.16f, .19f, .30f), night)
        val low = smooth((.75f - key.down) / .35f)
        val back = (low * (1f - key.cool) * (key.out * 2.5f).coerceIn(0f, 1f) * (key.strength * 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        level = lerp(level, Color(level.red * .42f, level.green * .36f, level.blue * .44f), back)
        val dusk = 1f - abs(sky.dark - .5f) * 2f
        val t = lerp(level, Color(tone.red * level.red * 1.1f, tone.green * level.green * 1.1f, tone.blue * level.blue * 1.1f), .25f + .3f * dusk)
        val sh = floatArrayOf(t.red, t.green, t.blue, t.red * -.06f, t.green * -.06f, t.blue * -.06f, -.18f * t.red, -.18f * t.green, -.18f * t.blue, 0f, 0f, 0f)
        ibl?.let { engine.destroyIndirectLight(it) }
        ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine).also { scene.indirectLight = it }
    }

    /** Renders from [eye] towards [at] at [hour] into [file] ([fov]: degrees across), over the hour's sky. */
    fun shoot(file: File, hour: Float, eye: FloatArray, at: FloatArray, fov: Float = 40f) {
        light(hour)
        val f = norm(floatArrayOf(at[0] - eye[0], at[1] - eye[1], at[2] - eye[2]))
        // the street's own handedness: looking at the fronts (+z) from the road, +x is to the right
        val r = norm(floatArrayOf(f[2], 0f, -f[0]))
        val u = cross(f, r).let { if (it[1] < 0f) floatArrayOf(-it[0], -it[1], -it[2]) else it }
        val cx = w / 2f; val cy = h / 2f
        val fl = cx / tan(Math.toRadians(fov / 2.0)).toFloat()
        val m = FloatArray(12)
        for (i in 0..2) { m[i] = fl * r[i] + cx * f[i]; m[4 + i] = -fl * u[i] + cy * f[i]; m[8 + i] = f[i] }
        m[3] = -(m[0] * eye[0] + m[1] * eye[1] + m[2] * eye[2]); m[7] = -(m[4] * eye[0] + m[5] * eye[1] + m[6] * eye[2]); m[11] = -(f[0] * eye[0] + f[1] * eye[1] + f[2] * eye[2])
        LookMatrix.pinhole(m, w.toFloat(), h.toFloat(), 2f, look)
        val near = 4.0; val k = near / fl
        // (the street's look is mirrored from Filament's right-handed camera: the camera's projection is mirrored too, so
        // Filament's light grid, which follows its camera, puts the store's lights where the picture shows them)
        val far = near + 14000.0
        val l = -cx * k; val rr = (w - cx) * k; val bb = -(h - cy) * k; val tt = cy * k
        val proj = DoubleArray(16)
        proj[0] = -2 * near / (rr - l); proj[5] = 2 * near / (tt - bb); proj[8] = -(rr + l) / (rr - l); proj[9] = (tt + bb) / (tt - bb)
        proj[10] = -(far + near) / (far - near); proj[11] = -1.0; proj[14] = -2 * far * near / (far - near)
        camera.setCustomProjection(proj, near, far)
        camera.lookAt(eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(), at[0].toDouble(), at[1].toDouble(), at[2].toDouble(), 0.0, 1.0, 0.0)
        props.update(look, skyColors(hour).dark)
        after(skyColors(hour).dark)
        val px = draw()
        // over the hour's sky, top to bottom
        val sky = skyColors(hour).sky
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            val t = y / (h - 1f) * (sky.size - 1)
            val i = t.toInt().coerceAtMost(sky.size - 2).coerceAtLeast(0)
            val sc = if (sky.size < 2) sky.firstOrNull() ?: Color.Gray else lerp(sky[i], sky[i + 1], t - i)
            val sr = (sc.red * 255).toInt(); val sg = (sc.green * 255).toInt(); val sb = (sc.blue * 255).toInt()
            for (x in 0 until w) {
                val p = px[y * w + x]; val a = p ushr 24
                fun ch(s: Int, b: Int) = (((p shr s) and 255) + b * (255 - a) / 255).coerceAtMost(255)
                img.setRGB(x, y, (ch(16, sr) shl 16) or (ch(8, sg) shl 8) or ch(0, sb))
            }
        }
        javax.imageio.ImageIO.write(img, "png", file)
    }

    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

    private fun draw(): IntArray {
        val done = AtomicBoolean(false)
        for (frame in 0..1) {
            if (renderer.beginFrame(swap, 0L)) {
                renderer.render(view)
                if (frame == 1) renderer.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, w, Executor { it.run() }, Runnable { done.set(true) }))
                renderer.endFrame()
            }
            engine.flushAndWait()
        }
        var guard = 0
        while (!done.get() && guard++ < 200) { engine.flushAndWait(); Thread.sleep(5) }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((h - 1 - y) * w + x) * 4
            val r = pixels.get(i).toInt() and 255; val g = pixels.get(i + 1).toInt() and 255; val b = pixels.get(i + 2).toInt() and 255; val a = pixels.get(i + 3).toInt() and 255
            px[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    fun destroy() {
        props.destroy()
        ibl?.let { engine.destroyIndirectLight(it) }
        scene.remove(sun); engine.destroyEntity(sun); EntityManager.get().destroy(sun)
        engine.destroyColorGrading(grading)
        engine.destroyRenderer(renderer); engine.destroySwapChain(swap)
        val ce = camera.entity
        engine.destroyCameraComponent(ce); EntityManager.get().destroy(ce)
        engine.destroyView(view); engine.destroyScene(scene)
    }

    private fun lin(c: Float): Float = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }
    private fun norm(a: FloatArray): FloatArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
