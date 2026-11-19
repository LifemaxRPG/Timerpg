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
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaBill
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaFilm
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaModel
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaPosters
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

/**
 * The movie theater's desktop renders (-Dmode=filament-cinema), headless through Filament like -Dmode=filament-props:
 *  - posters_<decade>.png: a sheet of eight made-up films' posters in each decade's style, enlarged (no Filament needed);
 *  - <era>_<view>_<hour>.png: each era's theater (palace 1952, modern 1972, twin 1988, multiplex 2015) on a pavement
 *    by the road, from the street (the game's medium camera), three-quarter, the marquee close up, the side, behind and
 *    above, at 13:00, dusk (19.3) and night (22.5); <era>_chase_<k>.png: the marquee at night a chase step apart.
 * -Dcinema=palace,modern,twin,multiplex,posters for some only; -Dhour= one hour. -DcinemaBills=DIR: the town's own bills
 * instead (files gamma-<year>.json, each a street.json v2 `graph` with its `cinema` block and the cinema landmark's
 * footprint): each era's theater at the plot's size with that year's bill, and town_posters_<year>.png for every file.
 * Writes into build/preview/cinema/ and cinema.txt (each era's pictures in texels and bytes, its triangles and batches).
 */
fun renderCinema(out: String) {
    val dir = File(out, "cinema").also { it.mkdirs() }
    val only = System.getProperty("cinema")?.split(",")?.map { it.trim() }?.toSet()
    fun wants(k: String) = only == null || k in only
    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 19.3f, 22.5f)
    val report = StringBuilder()

    val bills = System.getProperty("cinemaBills")?.let { d -> File(d).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } }
        ?.mapNotNull { f -> CinemaInfo.fromGraph(org.json.JSONObject(f.readText())) } ?: emptyList()
    val sheets: List<Pair<String, List<CinemaFilm>>> = if (bills.isNotEmpty()) bills.map { "town_posters_${it.year}" to (it.nowShowing + it.comingSoon) }
        else listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020).map { d -> CinemaBill.sample("LYRIC", d + 4, seed = 3, now = 4, soon = 4).let { "posters_$d" to (it.nowShowing + it.comingSoon) } }
    if (wants("posters")) for ((sheetName, films) in sheets) {
        val decade = sheetName.takeLast(4)
        val s = 5
        val cw = CinemaPosters.W * s; val ch = CinemaPosters.H * s
        val rows = (films.size + 3) / 4
        val sheet = java.awt.image.BufferedImage(4 * (cw + 24) + 24, rows * (ch + 60) + 70, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.color = java.awt.Color(0x22, 0x22, 0x2A); g.fillRect(0, 0, sheet.width, sheet.height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = java.awt.Color.WHITE; g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 26)
        g.drawString(if (bills.isNotEmpty()) "gamma's cinema, $decade: now showing and coming soon (made-up films, ${CinemaPosters.W}x${CinemaPosters.H} texels, shown x$s)" else "${decade}s posters (made-up films, ${CinemaPosters.W}x${CinemaPosters.H} texels each, shown x$s)", 24, 40)
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 15)
        for ((i, f) in films.withIndex()) {
            val im = CinemaPosters.paint(f)
            val label = if (f.motif.isNotEmpty()) "${f.style}/${f.motif}" else f.genre
            val ox = 24 + (i % 4) * (cw + 24); val oy = 64 + (i / 4) * (ch + 60)
            for (y in 0 until im.h) for (x in 0 until im.w) { g.color = java.awt.Color(im[x, y], true).let { if (it.alpha == 0) java.awt.Color(0x22, 0x22, 0x2A) else it }; g.fillRect(ox + x * s, oy + y * s, s, s) }
            g.color = java.awt.Color(0xDD, 0xDD, 0xE6)
            g.drawString("${f.title} (${label}, ${f.rating.ifEmpty { "-" }})", ox, oy + ch + 20)
            g.drawString("\"${f.tagline}\"", ox, oy + ch + 40)
        }
        g.dispose()
        javax.imageio.ImageIO.write(sheet, "png", File(dir, "$sheetName.png"))
        report.appendLine("$sheetName: " + films.joinToString { "${it.title} [${it.genre}${if (it.motif.isNotEmpty()) "/" + it.motif else ""}]" })
    }

    val samples = listOf(Triple("palace", 1952, "LYRIC"), Triple("modern", 1972, "MERIDIAN"), Triple("twin", 1988, "STARLITE"), Triple("multiplex", 2015, "CRESTVIEW"))
    val eras = (if (bills.isNotEmpty()) samples.mapNotNull { (e, y, _) -> bills.filter { it.era.name.lowercase() == e }.minByOrNull { abs(it.year - y) }?.let { Triple(e, it.year, "") } } else samples).filter { wants(it.first) }
    if (eras.isNotEmpty()) {
        Filament.init()
        val engine = Engine.create(Engine.Backend.OPENGL)
        val assets = File(assetsDir(), "filament")
        fun mat(n: String): Material { val b = File(assets, "$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
        val lit = mat("street_lit")
        val proxy = mat("shadow_proxy")
        for ((era, year, name) in eras) {
            val info = bills.firstOrNull { it.year == year && name.isEmpty() } ?: CinemaKit.sample(year, name, seed = year)
            val rt = Runtime.getRuntime()
            System.gc()
            val before = rt.totalMemory() - rt.freeMemory()
            val t0 = System.nanoTime()
            var model: CinemaModel? = CinemaKit.build(info, seed = 3)
            val ms = (System.nanoTime() - t0) / 1e6
            val m = model!!
            val heap = (rt.totalMemory() - rt.freeMemory() - before) / 1024
            report.appendLine("$era (${info.era}, $year, \"${info.name}\"): ${m.parts.size} parts, ${m.triangles} triangles, pictures ${m.texels} texels = ${m.bytes / 1024} KiB " +
                "(${m.parts.joinToString(" ") { "${it.role.name.lowercase()}${if (it.tiled) "/tile" else ""}:${it.mesh.texW}x${it.mesh.texH}${if (it.mesh.mask != null) "+mask" else ""}" }}), " +
                "built in ${"%.0f".format(ms)} ms, heap +$heap KiB; footprint x ${m.footprint[0].toInt()}..${m.footprint[2].toInt()} z ${m.footprint[1].toInt()}..${m.footprint[3].toInt()}, ${m.height.toInt()} tall")
            report.appendLine("  now showing: " + info.nowShowing.joinToString { it.title } + "; coming: " + info.comingSoon.joinToString { it.title })
            val w = 1600; val h = 900
            val stage = CinemaStage(engine, lit, proxy, w, h)
            stage.ground(-900f, 900f)
            yard(stage, -1200f, 1200f)
            val cinema = CinemaLayer(stage.props)
            val setback = 8f
            cinema.add(m, floatArrayOf(0f, setback, 1f, 0f, 0f, -1f, 0f))
            model = null
            var seconds = 0.0
            stage.after = { dark -> cinema.update(dark, seconds) }
            val fz = setback
            val wide = max(1f, m.footprint[2] - m.footprint[0]) / 520f
            val shots = listOf(
                Triple("street", floatArrayOf(0f, 80f, -420f), floatArrayOf(0f, 80f, 0f)) to 64f,
                Triple("quarter", floatArrayOf(-470f * wide, 190f, -430f * wide), floatArrayOf(0f, 95f, fz + 120f)) to 50f,
                Triple("marquee", floatArrayOf(-90f, 52f, -170f), floatArrayOf(0f, 100f, fz)) to 55f,
                Triple("side", floatArrayOf(-700f, 110f, fz + 150f), floatArrayOf(0f, 80f, fz + 190f)) to 50f,
                Triple("behind", floatArrayOf(430f * wide, 320f * wide, fz + m.depth + 650f * wide), floatArrayOf(0f, 60f, fz + m.depth * .6f)) to 50f,
                Triple("above", floatArrayOf(-560f * wide, 860f * wide, -360f * wide), floatArrayOf(0f, 0f, fz + m.depth / 2f)) to 50f,
            )
            for (hour in hours) {
                val tag = "%.1f".format(hour).replace(".0", "").replace(".", "_")
                for ((s, fov) in shots) stage.shoot(File(dir, "${era}_${s.first}_$tag.png"), hour, s.second, s.third, fov)
            }
            for (k in 0..2) { seconds = k / CinemaLayer.CHASE_RATE + .01; stage.shoot(File(dir, "${era}_chase_$k.png"), 22.5f, floatArrayOf(-60f, 70f, -150f), floatArrayOf(0f, 100f, fz), 50f) }
            stage.destroy()
        }
        engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
        engine.destroy()
    }
    File(dir, "cinema.txt").writeText(report.toString())
    print(report)
}

/** The theater's lot: a concrete apron and service yard behind the building line (z > 0), in pixel slabs. */
private fun yard(stage: CinemaStage, x0: Float, x1: Float) {
    val tile = PixImg(48, 48).also { im ->
        val rnd = Paint.Rnd(4)
        for (y in 0 until 48) for (x in 0 until 48) im[x, y] = if (x == 0 || y == 0) Paint.CONCRETE.d else if (rnd.next() < .1f) Paint.CONCRETE.l else Paint.CONCRETE.b
    }
    val k = 48f * TEXEL
    // the lot behind the building line and the far side of the street (the camera's side), past the road
    for ((z0, z1) in listOf(0f to 1500f, -800f to -150f)) {
        val pos = floatArrayOf(x0, .02f, z1, x1, .02f, z1, x1, .02f, z0, x0, .02f, z0)
        val uv = floatArrayOf(x0 / k, z1 / k, x1 / k, z1 / k, x1 / k, z0 / k, x0 / k, z0 / k)
        val n = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
        val t = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
        val mesh = PropMesh(pos, uv, n, t, intArrayOf(0, 1, 2, 0, 2, 3), 48, 48, tile.px, null, floatArrayOf(x0, 0f, z0, x1, 0f, z1))
        stage.props.addMesh(mesh, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false, tiled = true)
    }
}

/** The props preview's stage (a sun and sky light as the street's, one camera, a swap chain to read back), for the theater. */
private class CinemaStage(val engine: Engine, lit: Material, proxy: Material, val w: Int, val h: Int) {
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

    /** Called after the props' own update each shot (the theater's lights). */
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
        camera.setProjection(Camera.Projection.PERSPECTIVE, -cx * k, (w - cx) * k, -(h - cy) * k, cy * k, near, near + 3000.0)
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
