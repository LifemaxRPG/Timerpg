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
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.domain.PropRules
import com.lifemax.rpg.feature.habits.domain.PropSpot
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder
import com.lifemax.rpg.feature.habits.presentation.scene3d.Paint
import com.lifemax.rpg.feature.habits.presentation.scene3d.PixImg
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.TEXEL
import com.lifemax.rpg.feature.habits.presentation.scene3d.WorldProp
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
 * The street props' desktop renders (-Dmode=filament-props), headless through Filament like -Dmode=filament:
 *  - lineup_<group>_<view>_<hour>.png: every prop and look standing on a pavement at its kerb by a wall, at 13:00, dusk
 *    (19.3) and night (22.5), from the front and a three-quarter view, close enough to see each texel;
 *  - street_<row>_<hour>.png: rows of the town gamma (the test fixture) with their props placed by [PropRules], the
 *    buildings as plain boxes (their doors marked), seen across the road from the game's medium camera.
 * -Dhour= one hour only; -Dprops=lineup or street for one half. Writes into build/preview/props/.
 */
fun renderProps(out: String) {
    val dir = File(out, "props").also { it.mkdirs() }
    val only = System.getProperty("props")
    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 19.3f, 22.5f)
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = File(assetsDir(), "filament")
    fun mat(n: String): Material { val b = File(assets, "$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
    val lit = mat("street_lit")
    val proxy = mat("shadow_proxy")
    val report = StringBuilder()

    if (only == null || only == "lineup") {
        val w = 1600; val h = 900
        val stage = Stage(engine, lit, proxy, w, h)
        // the wall behind and the pavement
        stage.ground(-600f, 600f)
        stage.wall(-600f, 600f)
        // the lineup: groups of props side by side at the kerb
        val items = PropKit.lineup()
        val groups = items.chunked(8)
        val all = ArrayList<WorldProp>()
        val centres = ArrayList<Pair<Float, Float>>()
        var x0 = -560f
        for (g in groups) {
            var x = x0
            val start = x
            for ((k, v) in g) {
                val wd = if (k == PropKind.STREET_SIGN) 34f else k.w
                x += wd / 2f
                val spot = PropSpot(k, v, x, PropRules.kerbOut(k), text = PropKit.sampleText(k), seed = v * 3 + 1)
                all += WorldProp(spot, x, -spot.out, 1f, 0f, 0f, -1f)
                x += wd / 2f + 9f
            }
            centres += (start + x - 9f) / 2f to (x - 9f - start)
            x0 = x + 60f
        }
        stage.props.add(all)
        report.appendLine("lineup: ${items.size} props and looks, ${stage.props.draws} batches")
        for (hour in hours) {
            for ((gi, c) in centres.withIndex()) {
                val (cx, span) = c
                val tag = "%.1f".format(hour).replace(".0", "").replace(".", "_")
                // front: level-ish, from the road; three-quarter: from the road's side and a little higher
                val dist = (span / 2f + 12f) / tan(Math.toRadians(20.0)).toFloat()
                val target = floatArrayOf(cx, 16f, -24f)
                stage.shoot(File(dir, "lineup_${gi}_front_$tag.png"), hour, floatArrayOf(cx, 24f, -24f - dist), target)
                val a = Math.toRadians(38.0)
                stage.shoot(File(dir, "lineup_${gi}_quarter_$tag.png"), hour, floatArrayOf(cx - (dist * sin(a)).toFloat(), 46f, -24f - (dist * cos(a)).toFloat()), target)
            }
        }
        stage.destroy()
        // one sheet a view and hour: the groups stacked
        for (hour in hours) for (view in listOf("front", "quarter")) {
            val tag = "%.1f".format(hour).replace(".0", "").replace(".", "_")
            val files = groups.indices.map { File(dir, "lineup_${it}_${view}_$tag.png") }.filter { it.exists() }
            val imgs = files.map { javax.imageio.ImageIO.read(it) }
            if (imgs.isEmpty()) continue
            val sheet = java.awt.image.BufferedImage(w / 2, h / 2 * imgs.size, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g2 = sheet.createGraphics()
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            for ((i, im) in imgs.withIndex()) g2.drawImage(im, 0, i * h / 2, w / 2, h / 2, null)
            g2.dispose()
            javax.imageio.ImageIO.write(sheet, "png", File(dir, "sheet_${view}_$tag.png"))
        }
    }

    if (only == null || only == "street") {
        val town = Town.parse(File("src/test/resources/town-gamma.json").takeIf { it.exists() }?.readText() ?: File("desktop/src/test/resources/town-gamma.json").readText())!!
        val w = 2340; val h = 1080
        for (rowId in (System.getProperty("rows")?.split(",") ?: listOf("3R", "19R", "8L"))) {
            val row = town.row(rowId)
            val spots = PropRules.forRow(town, row)
            val stage = Stage(engine, lit, proxy, w, h)
            // the row laid straight along +x from its start, the buildings at +z, the road at -z
            stage.ground(-200f, row.length + 200f)
            stage.lots(-200f, row.length + 200f)
            for (e in listOf(0f, row.length)) stage.mouth(e)
            for (b in row.buildings) stage.building(b.x0, b.x1, b.setback, b.depth, b.kind, town.landmarks.firstOrNull { it.sprite == b.sprite }?.kind)
            stage.lamps(row.furniture.map { it.x })
            stage.props.add(spots.map { WorldProp(it, it.x, -it.out, 1f, 0f, 0f, -1f) })
            report.appendLine("street $rowId (${town.road(row.road).name}, ${row.length.toInt()} long): ${spots.size} props: " +
                spots.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.key.name.lowercase()} ${it.value}" })
            // a few stretches along it: where its buildings are, and a corner
            val stops = (row.buildings.map { it.mid } + listOf(150f)).sorted().fold(ArrayList<Float>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > 900f) acc += x; acc }.take(3)
            for (hour in if (System.getProperty("hour") != null) hours else listOf(13f, 22.5f)) {
                val tag = "%.1f".format(hour).replace(".0", "").replace(".", "_")
                for ((i, x) in stops.withIndex()) {
                    // the game's medium camera: eye 80 up, standing back across the road
                    stage.shoot(File(dir, "street_${rowId}_${i}_$tag.png"), hour, floatArrayOf(x, 80f, -420f), floatArrayOf(x, 80f, 0f), fov = 64f)
                    stage.shoot(File(dir, "street_${rowId}_${i}_near_$tag.png"), hour, floatArrayOf(x - 60f, 40f, -170f), floatArrayOf(x + 20f, 18f, -20f), fov = 50f)
                }
            }
            stage.destroy()
        }
    }
    File(dir, "props.txt").writeText(report.toString())
    print(report)
    engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
    engine.destroy()
}

/** A small Filament stage for the props: a sun and sky light as the street's, one camera, a swap chain to read back. */
private class Stage(val engine: Engine, lit: Material, proxy: Material, val w: Int, val h: Int) {
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

    /** A plain brick wall along z 0 (a building's front) from [x0] to [x1], in 96-unit pieces. */
    fun wall(x0: Float, x1: Float) {
        val m = MeshBuilder()
        val brick = Paint.c(0xB45A3C); val brickD = Paint.c(0x8E4434); val mortar = Paint.c(0xCF9A80)
        m.box(-48f, 0f, -20f, 48f, 78f, 0f) { s, w, h ->
            if (s != MeshBuilder.Side.FRONT) null else PixImg(w, h).also { im ->
                for (y in 0 until h) for (x in 0 until w) im[x, y] = if (y % 4 == 3 || (x + (y / 4) * 4) % 9 == 0) mortar else if ((x / 9 + y / 4) % 3 == 0) brickD else brick
                for (y in h - 6 until h) for (x in 0 until w) im[x, y] = Paint.CONCRETE.d
            }
        }
        val mesh = m.build()
        val at = ArrayList<FloatArray>()
        var x = x0 + 48f
        while (x < x1) { at += floatArrayOf(x, 0f, 1f, 0f, 0f, -1f, 0f); x += 96f }
        // (a backdrop only: the street's sun stands behind the fronts, and a wall this tall would shade the whole pavement)
        props.addMesh(mesh, at, casts = false)
    }

    /** A building of a row as a plain box: its front [setback] behind the building line, its door marked. */
    fun building(x0: Float, x1: Float, setback: Float, depth: Float, kind: PlacementKind, landmark: String?) {
        val hgt = when (kind) { PlacementKind.SHOP -> 70f; PlacementKind.HOME -> 46f; PlacementKind.LANDMARK -> 96f }
        val ramp = when (kind) { PlacementKind.SHOP -> Paint.TERRACOTTA; PlacementKind.HOME -> Paint.BEIGE; PlacementKind.LANDMARK -> Paint.CONCRETE }
        val m = MeshBuilder()
        val wd = x1 - x0
        m.box(-wd / 2f, 0f, -depth, wd / 2f, hgt, 0f) { s, w, h ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> Paint.top(w, h, Paint.GREY)
                MeshBuilder.Side.FRONT -> Paint.panel(w, h, ramp, 3).also { im ->
                    // its windows and the door at its middle
                    for (y in 8 until h - 16 step 18) for (x in 6 until w - 10 step 16) { im.rect(x, y, x + 8, y + 10, Paint.OUT); im.rect(x + 1, y + 1, x + 7, y + 9, Paint.c(0x34435C)) }
                    val dx = w / 2
                    im.rect(dx - 7, h - 24, dx + 6, h - 1, Paint.OUT); im.rect(dx - 6, h - 23, dx + 5, h - 1, Paint.c(0x5E4030))
                    landmark?.let { im.text(it.uppercase(), 4, 2, Paint.OUT) }
                }
                else -> Paint.panel(w, h, ramp, 4 + s.ordinal)
            }
        }
        props.addMesh(m.build(), listOf(floatArrayOf((x0 + x1) / 2f, setback, 1f, 0f, 0f, -1f, 0f)), casts = true)
        // its path to the pavement
        if (setback > 2f) {
            val p = MeshBuilder()
            p.box(-8f, 0f, 0f, 8f, .08f, setback) { s, ww, hh -> if (s == MeshBuilder.Side.TOP) PixImg(ww, hh).also { it.fill(Paint.CONCRETE.l); it.border(Paint.CONCRETE.d) } else null }
            props.addMesh(p.build(), listOf(floatArrayOf((x0 + x1) / 2f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
        }
    }

    /** The lots behind the building line: grass in pixel tufts (the town's yards are not drawn here). */
    fun lots(x0: Float, x1: Float) {
        val m = MeshBuilder()
        val tile = PixImg(48, 48).also { im ->
            val rnd = Paint.Rnd(9)
            for (y in 0 until 48) for (x in 0 until 48) { val t = rnd.next(); im[x, y] = if (t < .15f) Paint.LEAF.d else if (t < .25f) Paint.LEAF.l else Paint.LEAF.b }
        }
        m.box(-20f, 0f, -20f, 20f, .02f, 20f) { s, _, _ -> if (s == MeshBuilder.Side.TOP) tile else null }
        val at = ArrayList<FloatArray>()
        var x = x0
        while (x < x1) { var z = 20f; while (z < 420f) { at += floatArrayOf(x + 20f, z, 1f, 0f, 0f, 1f, 0f); z += 40f }; x += 40f }
        props.addMesh(m.build(), at, casts = false)
    }

    /** A crossing road's carriageway at row x [x] (its middle). */
    fun mouth(x: Float) {
        val m = MeshBuilder()
        val half = Crossroads.MOUTH / 2f - PropRules.CORNER
        m.box(-half, 0f, -150f, half, .06f, 40f) { s, w, h -> if (s == MeshBuilder.Side.TOP) PixImg(w, h).also { it.fill(Paint.c(0x5A5E6E)) } else null }
        props.addMesh(m.build(), listOf(floatArrayOf(x, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
    }

    /** The row's lamps (as plain posts: the real ones are cards drawn by the street). */
    fun lamps(xs: List<Float>) {
        if (xs.isEmpty()) return
        val m = MeshBuilder()
        m.upright(0f, 0f, 0f, 62f, 1.1f, 1.1f, 6, null, Paint.swatch(Paint.IRON.b))
        m.solidBox(-1.5f, 60f, -1.5f, 1.5f, 66f, 1.5f, Paint.swatch(Paint.c(0xF7D650)))
        props.addMesh(m.build(), xs.map { floatArrayOf(it, -3f, 1f, 0f, 0f, -1f, 0f) }, casts = true)
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
