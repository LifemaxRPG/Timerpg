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
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder
import com.lifemax.rpg.feature.habits.presentation.scene3d.Paint
import com.lifemax.rpg.feature.habits.presentation.scene3d.PixImg
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.Yard
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardData
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardItem
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardPics
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The yards' desktop renders (-Dmode=filament-yards), headless through Filament like -Dmode=filament-props:
 *  - lineup_<group>_<view>_<hour>.png: every yard kind (and a few looks of each) on a lawn, at 13:00, dusk (19.3) and
 *    night (22.5), from the front and a three-quarter view; sheet_<view>_<hour>.png stacks the groups;
 *  - yard_<name>_<view>_<hour>.png: three real yards of the town gamma (build-town/gamma/street.json: heights with a
 *    pool, woodside, gardens) and their neighbours, the houses as plain boxes, from the street, from behind and above.
 * -Dhour= one hour only; -Dyards=lineup or real for one half; -Dstreet=<path> another street.json; -DyardLots= other yards. Writes into
 * build/preview/yards/.
 */
fun renderYards(out: String) {
    val dir = File(out, "yards").also { it.mkdirs() }
    val only = System.getProperty("yards")
    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 19.3f, 22.5f)
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = File(assetsDir(), "filament")
    fun mat(n: String): Material { val b = File(assets, "$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
    val lit = mat("street_lit")
    val proxy = mat("shadow_proxy")
    val report = StringBuilder()
    fun tag(hour: Float) = "%.1f".format(hour).replace(".0", "").replace(".", "_")

    if (only == null || only == "lineup") {
        val w = 1600; val h = 900
        // the groups: ground and surfaces, edges, then the things by size
        val all = YardKit.lineup()
        val cat = { it: YardItem -> when { it.kind in YardKit.GROUND || it.kind in YardKit.SURFACES -> 0; it.kind in YardKit.EDGES -> 1; it.x1 - it.x0 <= 40f && it.z1 - it.z0 <= 40f -> 2; else -> 3 } }
        val groups = ArrayList<List<YardItem>>()
        for (c in 0..3) {
            var cur = ArrayList<YardItem>(); var span = 0f
            for (it in all.filter { cat(it) == c }) {
                val wd = max(it.x1 - it.x0, if (it.kind in YardKit.EDGES) 0f else it.z1 - it.z0)
                if (cur.isNotEmpty() && span + wd > (if (c == 2) 260f else 420f)) { groups += cur; cur = ArrayList(); span = 0f }
                cur += it; span += wd + 12f
            }
            if (cur.isNotEmpty()) groups += cur
        }
        val stage = YardStage(engine, lit, proxy, w, h)
        val placed = ArrayList<YardItem>()
        val centres = ArrayList<Triple<Float, Float, Float>>() // centre x, span, depth
        // the groups side by side along x, far apart (each shot sees only its own group)
        var gx = 0f
        for (g in groups) {
            var x = gx
            val deep = g.maxOf { it.z1 - it.z0 }
            for (it in g) {
                val iw = it.x1 - it.x0; val id = it.z1 - it.z0
                // facing the camera at +z
                placed += YardItem(it.kind, x, -id / 2f, x + iw, id / 2f, 0f, 1f, it.variant, it.layer, it.on, it.zone)
                x += iw + 12f
            }
            centres += Triple((gx + x - 12f) / 2f, x - 12f - gx, deep)
            gx = x + 1400f
        }
        stage.yards.add(placed)
        stage.lawn(-1500f, gx + 1500f, -3000f, 3000f)
        report.appendLine("lineup: ${placed.size} items of ${placed.map { it.kind }.distinct().size} kinds in ${groups.size} groups, ${stage.yards.draws} draws")
        for (hour in hours) for ((gi, c) in centres.withIndex()) {
            val (cx, span, deep) = c
            val dist = (span / 2f + 16f) / tan(Math.toRadians(20.0)).toFloat() * .85f
            val target = floatArrayOf(cx, 10f, 0f)
            stage.shoot(File(dir, "lineup_${gi}_front_${tag(hour)}.png"), hour, floatArrayOf(cx, 14f + dist * .3f, dist), target)
            val a = Math.toRadians(35.0)
            stage.shoot(File(dir, "lineup_${gi}_quarter_${tag(hour)}.png"), hour, floatArrayOf(cx - (dist * .8f * sin(a)).toFloat(), 14f + dist * .36f, (dist * .8f * cos(a)).toFloat()), target)
        }
        stage.destroy()
        for (hour in hours) for (view in listOf("front", "quarter")) {
            val files = groups.indices.map { File(dir, "lineup_${it}_${view}_${tag(hour)}.png") }.filter { it.exists() }
            val imgs = files.map { javax.imageio.ImageIO.read(it) }
            if (imgs.isEmpty()) continue
            val cols = 2; val rows = (imgs.size + 1) / 2
            val sheet = java.awt.image.BufferedImage(w / 2 * cols, h / 2 * rows, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g2 = sheet.createGraphics()
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for ((i, im) in imgs.withIndex()) g2.drawImage(im, (i % cols) * w / 2, (i / cols) * h / 2, w / 2, h / 2, null)
            g2.dispose()
            javax.imageio.ImageIO.write(sheet, "png", File(dir, "sheet_${view}_${tag(hour)}.png"))
        }
    }

    if (only == null || only == "real") {
        val path = System.getProperty("street")?.takeIf { it.endsWith(".json") } ?: listOf("build-town/gamma/street.json", "../build-town/gamma/street.json").first { File(it).exists() }
        val json = File(path).readText()
        val yards = YardData.yards(json)
        val lanes = YardData.laneItems(json)
        val w = 2340; val h = 1080
        // (-DyardLots=name=lot;name=lot: other yards, e.g. the capital's blocks of flats)
        val picks = System.getProperty("yardLots")?.split(";")?.map { it.substringBefore("=") to it.substringAfter("=") }
            ?: listOf("heights_pool" to "medium:75,91", "woodside" to "small:147,72", "gardens" to "medium:261,116")
        for ((name, lot) in picks) {
            val y = yards.firstOrNull { it.lot == lot } ?: continue
            val p = y.parcel
            val cx = (p[0] + p[2]) / 2f; val cz = (p[1] + p[3]) / 2f
            fun near(r: FloatArray, m: Float) = r[2] > p[0] - m && r[0] < p[2] + m && r[3] > p[1] - m && r[1] < p[3] + m
            val around = yards.filter { near(it.parcel, 700f) }
            val stage = YardStage(engine, lit, proxy, w, h)
            stage.yards.add(around.flatMap { it.items } + lanes.filter { near(floatArrayOf(it.x0, it.z0, it.x1, it.z1), 700f) })
            // the street: the way from the house to its front yard
            val hs = y.house
            val hx = (hs[0] + hs[2]) / 2f; val hz = (hs[1] + hs[3]) / 2f
            val fr = y.front ?: y.parcel
            var sx = (fr[0] + fr[2]) / 2f - hx; var sz = (fr[1] + fr[3]) / 2f - hz
            if (abs(sx) > abs(sz)) { sx = if (sx > 0) 1f else -1f; sz = 0f } else { sz = if (sz > 0) 1f else -1f; sx = 0f }
            stage.lawn(cx - 1200f, cx + 1200f, cz - 1200f, cz + 1200f, verge = true)
            // the pavement and road beyond the parcel's front edge
            val edge = if (sx > 0) p[2] else if (sx < 0) p[0] else if (sz > 0) p[3] else p[1]
            stage.street(sx, sz, edge, if (sx != 0f) cz else cx, 1400f)
            for (o in around) stage.house(o.house, o.kind)
            report.appendLine("yard $name ($lot, ${y.neighbourhood}, ${y.style}, ${y.kind}): ${y.items.size} items, ${y.items.map { it.kind }.distinct().size} kinds; " +
                "with ${around.size - 1} neighbours: ${stage.yards.items} items, ${stage.yards.draws} draws")
            val ps = max(p[2] - p[0], p[3] - p[1])
            // looking at the house from across the road, from a corner of the parcel, from behind and from above
            val ax = -sz; val az = sx // along the street
            // the game's camera stands over the far side of the road, eye 80 up
            val toEdge = abs(if (sx != 0f) edge - hx else edge - hz)
            val streetEye = floatArrayOf(hx + sx * (toEdge + 150f), 80f, hz + sz * (toEdge + 150f))
            val views = listOf(
                "street" to (streetEye to floatArrayOf(hx, 30f, hz)),
                "corner" to (floatArrayOf(hx + sx * (ps * .5f + 120f) + ax * ps * .55f, 110f, hz + sz * (ps * .5f + 120f) + az * ps * .55f) to floatArrayOf(cx, 10f, cz)),
                "behind" to (floatArrayOf(hx - sx * (ps * .5f + 260f) - ax * 120f, 120f, hz - sz * (ps * .5f + 260f) - az * 120f) to floatArrayOf(cx, 10f, cz)),
                "above" to (floatArrayOf(cx + sx * 220f + ax * 40f, ps * 1.25f, cz + sz * 220f + az * 40f) to floatArrayOf(cx - sx * 20f, 0f, cz - sz * 20f)),
            )
            for (hour in if (System.getProperty("hour") != null) hours else listOf(13f, 19.3f, 22.5f)) for ((vn, v) in views) {
                if (hour != 13f && vn != "street" && vn != "corner") continue
                stage.shoot(File(dir, "yard_${name}_${vn}_${tag(hour)}.png"), hour, v.first, v.second, fov = if (vn == "street") 58f else 62f)
            }
            stage.destroy()
        }
    }
    File(dir, "yards.txt").writeText(report.toString())
    print(report)
    engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.release(engine)
    engine.destroy()
}

/** A flat textured quad x0..x1, z0..z1 at [y] wearing [img] repeated ([TEXEL]s a texel), for the stage's ground. */
private fun groundMesh(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, img: PixImg): PropMesh {
    val tw = img.w * 184f / 224f; val th = img.h * 184f / 224f
    val pos = floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1)
    val uv = floatArrayOf(x0 / tw, z0 / th, x1 / tw, z0 / th, x1 / tw, z1 / th, x0 / tw, z1 / th)
    val n = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
    val t = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
    return PropMesh(pos, uv, n, t, intArrayOf(0, 1, 2, 0, 2, 3), img.w, img.h, img.px, null, floatArrayOf(min(x0, x1), y, min(z0, z1), max(x0, x1), y, max(z0, z1)))
}

private val ID = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)

private fun YardStage.lawn(x0: Float, x1: Float, z0: Float, z1: Float, verge: Boolean = false) {
    // the town's open ground under everything (a little darker than the lawns)
    val img = if (verge) YardPics.pic("verge", 64, 64) { im -> val g = YardPics.grass(0); for (y in 0 until 64) for (x in 0 until 64) { val c = g[x, y]; im[x, y] = (0xFF shl 24) or ((((c shr 16) and 255) * 82 / 100) shl 16) or ((((c shr 8) and 255) * 86 / 100) shl 8) or ((c and 255) * 84 / 100) } } else YardPics.grass(0)
    props.addMesh(groundMesh(x0, min(z0, z1), x1, max(z0, z1), 0f, img), listOf(ID), casts = false, tiled = true)
}

/** The pavement (38 wide) and a road beyond the parcels' front edge at [edge], the way ([sx], [sz]) points to it. */
private fun YardStage.street(sx: Float, sz: Float, edge: Float, mid: Float, half: Float) {
    val pave = YardPics.surface("slabs")
    val road = YardPics.surface("asphalt")
    if (sx != 0f) {
        // fronts 178 apart: pavement 38, the carriageway 102, pavement 38
        val a = edge; val b = edge + sx * 38f; val c = edge + sx * 140f; val e = edge + sx * 178f
        props.addMesh(groundMesh(min(a, b), mid - half, max(a, b), mid + half, .2f, pave), listOf(ID), casts = false, tiled = true)
        props.addMesh(groundMesh(min(b, c), mid - half, max(b, c), mid + half, .08f, road), listOf(ID), casts = false, tiled = true)
        props.addMesh(groundMesh(min(c, e), mid - half, max(c, e), mid + half, .2f, pave), listOf(ID), casts = false, tiled = true)
    } else {
        val a = edge; val b = edge + sz * 38f; val c = edge + sz * 140f; val e = edge + sz * 178f
        props.addMesh(groundMesh(mid - half, min(a, b), mid + half, max(a, b), .2f, pave), listOf(ID), casts = false, tiled = true)
        props.addMesh(groundMesh(mid - half, min(b, c), mid + half, max(b, c), .08f, road), listOf(ID), casts = false, tiled = true)
        props.addMesh(groundMesh(mid - half, min(c, e), mid + half, max(c, e), .2f, pave), listOf(ID), casts = false, tiled = true)
    }
}

/** A house as a plain box on its footprint (the real models stand here in the game), its roof a darker slab. */
private fun YardStage.house(r: FloatArray, kind: String) {
    val hgt = when (kind) { "apartment" -> 90f; "villa", "house" -> 58f; else -> 48f }
    val m = MeshBuilder()
    val wd = r[2] - r[0]; val dp = r[3] - r[1]
    m.box(-wd / 2f, 0f, -dp / 2f, wd / 2f, hgt, dp / 2f) { s, ww, hh ->
        when (s) {
            MeshBuilder.Side.BOTTOM -> null
            MeshBuilder.Side.TOP -> Paint.top(ww, hh, Paint.GREY)
            else -> Paint.panel(ww, hh, Paint.BEIGE, 3 + s.ordinal).also { im ->
                for (y in 8 until hh - 16 step 22) for (x in 6 until ww - 10 step 18) { im.rect(x, y, x + 8, y + 10, Paint.OUT); im.rect(x + 1, y + 1, x + 7, y + 9, Paint.c(0x34435C)) }
            }
        }
    }
    props.addMesh(m.build(), listOf(floatArrayOf((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, 1f, 0f, 0f, 1f, 0f)), casts = true)
}

/** A small Filament stage for the yards (as the props preview's): a sun and sky light as the street's, one camera, a swap chain to read back. */
private class YardStage(val engine: Engine, lit: Material, proxy: Material, val w: Int, val h: Int) {
    val scene = engine.createScene()
    val view = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    val sun = EntityManager.get().create()
    val props = PropLayer(engine, scene, lit, proxy)
    val yards = YardLayer(engine, scene, lit, proxy)
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

    fun light(hour: Float, cdx: Float = 0f, cdz: Float = 1f) {
        val key = SceneLight.key(hour)
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        // as the street lights its view: the key across it, down, out of the fronts towards the viewer (the street's
        // own view, so both camera views of the lineup share one light)
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
    var seconds = 0.0

    fun shoot(file: File, hour: Float, eye: FloatArray, at: FloatArray, fov: Float = 40f) {
        val f = norm(floatArrayOf(at[0] - eye[0], at[1] - eye[1], at[2] - eye[2]))
        val fl0 = sqrt(f[0] * f[0] + f[2] * f[2]).coerceAtLeast(1e-4f)
        light(hour, f[0] / fl0, f[2] / fl0)
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
        camera.setProjection(Camera.Projection.PERSPECTIVE, -cx * k, (w - cx) * k, -(h - cy) * k, cy * k, near, near + 6000.0)
        camera.lookAt(eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(), at[0].toDouble(), at[1].toDouble(), at[2].toDouble(), 0.0, 1.0, 0.0)
        props.update(look, skyColors(hour).dark)
        yards.update(look, skyColors(hour).dark, seconds)
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
        yards.destroy()
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
