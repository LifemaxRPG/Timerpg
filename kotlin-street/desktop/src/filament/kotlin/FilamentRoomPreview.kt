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
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder
import com.lifemax.rpg.feature.habits.presentation.scene3d.Paint
import com.lifemax.rpg.feature.habits.presentation.scene3d.PixImg
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomKit
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The 3D interiors trial (docs/INTERIORS_3D.md), -Dmode=room: a small-town diner built from LimeZu's Modern Interiors
 * pictures by [RoomKit], rendered headless through Filament into build/preview/room/:
 *  - diner_<view>_<day|night>.png: the room from eye level, the game's high camera, the opposite side, a close-up of a
 *    booth, a corner and straight down, at 13:00 (the sun through the windows) and at 22:00 (its own lamps); the walls
 *    between the camera and the room are cut down Sims-style;
 *  - pieces_<name>.png and pieces_sheet.png: each box or parts piece beside its original sprite at the same pixel size.
 * -Dhour=13 or 22 for one of the two. Desktop renders: nothing here has run on the phone.
 */
fun renderRoom(out: String) {
    val dir = File(out, "room").also { it.mkdirs() }
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = assetsDir()
    fun mat(n: String): Material { val b = File(assets, "filament/$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
    val lit = mat("street_lit"); val proxy = mat("shadow_proxy")
    val cache = HashMap<String, PixImg>()
    fun load(f: File): PixImg { val im = ImageIO.read(f); return RoomKit.pix(im.width, im.height, im.getRGB(0, 0, im.width, im.height, null, 0, im.width)) }
    val art: (String) -> PixImg = { n -> cache.getOrPut(n) { load(File(assets, "room3d/diner/$n.png")) } }
    val sheet = ImageIO.read(File(assets, "characters/char_01.png"))
    val sheet2 = File(assets, "characters/char_07.png").takeIf { it.exists() }?.let { ImageIO.read(it) } ?: sheet
    fun person(s: BufferedImage, view: Int): PixImg { val x = view * 6 * 32; return RoomKit.pix(32, 64, s.getRGB(x, 64, 32, 64, null, 0, 32)) }
    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 22f)
    val report = StringBuilder()

    val stage = RoomStage(engine, lit, proxy, 1600, 1000)
    val diner = Diner(stage, art, listOf(person(sheet, 3), person(sheet2, 3)))
    report.appendLine("diner: ${diner.pieces} pieces (${diner.kinds}), ${stage.layers.sumOf { it.draws }} batches, ${stage.layers.sumOf { it.triangles }} triangles")
    val W = Diner.W * RoomKit.T; val D = Diner.D * RoomKit.T
    // eye, target (street units), field of view across
    val views = listOf(
        Triple("eye", floatArrayOf(W * .78f, 34f, D * .93f), floatArrayOf(W * .32f, 22f, D * .45f)) to 62f,
        Triple("high", floatArrayOf(W * .5f, 300f, D + 260f), floatArrayOf(W * .5f, 0f, D * .5f)) to 52f,
        Triple("opposite", floatArrayOf(W * .2f, 250f, -230f), floatArrayOf(W * .55f, 0f, D * .62f)) to 54f,
        Triple("booth", floatArrayOf(W * .30f, 44f, D * .70f), floatArrayOf(W * .10f, 18f, D * .93f)) to 50f,
        Triple("corner", floatArrayOf(W + 170f, 210f, D + 150f), floatArrayOf(W * .55f, 0f, D * .55f)) to 50f,
        Triple("counter", floatArrayOf(W * .52f, 52f, D * .78f), floatArrayOf(W * .48f, 22f, D * .42f)) to 50f,
        Triple("top", floatArrayOf(W * .5f, 520f, D * .5f + 60f), floatArrayOf(W * .5f, 0f, D * .5f)) to 58f,
    )
    for (hour in hours) for ((v, fov) in views) {
        val tag = if (hour < 18f) "day" else "night"
        diner.shoot(File(dir, "diner_${v.first}_$tag.png"), hour, v.second, v.third, fov)
    }
    stage.destroy()

    // each piece beside its sprite, at the same size on screen (4 screen pixels a texel)
    val names = listOf("counter", "display", "fridge", "stove", "kitchenCounter", "jukebox", "sideTable", "booth", "clothTable", "roundTable", "stool", "chair", "lamp")
    val shots = ArrayList<BufferedImage>()
    val ps = RoomStage(engine, lit, proxy, 360, 400)
    for (n in names) {
        val mesh = RoomKit.mesh(n, art)
        val layer = ps.layer()
        // (facing +z: in the street's mirrored world its along is -x, see Diner.shell)
        layer.addMesh(mesh, listOf(floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f)), casts = true)
        val floorL = ps.layer()
        floorL.addMesh(plainFloor(400f, 0xFF9C9C9C.toInt()), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
        val b = mesh.bounds
        val c = floatArrayOf((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f)
        val px = 4f; val dist = 900f
        val fl = px * dist / RoomKit.T
        val fov = Math.toDegrees(2.0 * atan(180.0 / fl)).toFloat()
        val a = Math.toRadians(42.0)
        val f = File(dir, "pieces_$n.png")
        ps.shoot(f, 13f, floatArrayOf(c[0] - 120f, c[1] + (dist * sin(a)).toFloat(), c[2] + (dist * cos(a)).toFloat()), c, fov, sunSide = true)
        val s = art(n)
        val pair = BufferedImage(360 + s.width() * 4 + 40, 400, BufferedImage.TYPE_INT_RGB)
        val g = pair.createGraphics()
        g.color = java.awt.Color(0x6E7B6E); g.fillRect(0, 0, pair.width, pair.height)
        val sp = BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, s.w, s.h, s.px, 0, s.w) }
        g.drawImage(sp, 20, (400 - s.h * 4) / 2, s.w * 4, s.h * 4, null)
        g.drawImage(ImageIO.read(f), s.w * 4 + 40, 0, null)
        g.color = java.awt.Color.WHITE; g.drawString("$n: sprite (left) and 3D (right), 4 px a texel", 6, 14)
        g.dispose()
        ImageIO.write(pair, "png", f)
        shots += pair
        ps.clear()
    }
    ps.destroy()
    // one sheet of them, two columns
    val colW = shots.maxOf { it.width }
    val sheetImg = BufferedImage(colW * 2, 400 * ((shots.size + 1) / 2), BufferedImage.TYPE_INT_RGB)
    sheetImg.createGraphics().also { g -> g.color = java.awt.Color(0x6E7B6E); g.fillRect(0, 0, sheetImg.width, sheetImg.height); for ((i, im) in shots.withIndex()) g.drawImage(im, (i % 2) * colW, (i / 2) * 400, null); g.dispose() }
    ImageIO.write(sheetImg, "png", File(dir, "pieces_sheet.png"))

    File(dir, "room.txt").writeText(report.toString())
    print(report)
    engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
    engine.destroy()
}

internal fun PixImg.width() = w

/** A plain floor square [size] across, centred on the origin, in [c] with a faint 32-texel grid. */
internal fun plainFloor(size: Float, c: Int): PropMesh {
    val m = MeshBuilder()
    val n = (size / RoomKit.T).toInt()
    val im = PixImg(n, n).also { it.fill(c); for (i in 0 until n step 32) { it.hline(0, n - 1, i, Paint.c(0x8A8A8A)); it.vline(i, 0, n - 1, Paint.c(0x8A8A8A)) } }
    val h = size / 2f
    m.quad(MeshBuilder.v(-h, 0f, -h), MeshBuilder.v(h, 0f, -h), MeshBuilder.v(h, 0f, h), MeshBuilder.v(-h, 0f, h), im)
    return m.build()
}

/**
 * The trial diner (texels; x across, z from the kitchen's back wall to the street front): the kitchen behind a partition
 * with a pass at the back, the counter with its stools and the ice cream display, two booths under the street windows,
 * a round table by the door, the machine against the right wall, a lamp on a side table, plants, plates, pictures.
 */
private class Diner(val stage: RoomStage, val art: (String) -> PixImg, val people: List<PixImg>) {
    companion object {
        const val W = 384
        const val D = 320
        /** The partition's dining face (z) and the kitchen behind it. */
        const val P = 96
    }

    private val T = RoomKit.T
    private val things = stage.layer()
    private val cards = ArrayList<Triple<PixImg, FloatArray, Float>>() // picture, (x, z) world, lift
    private var cardLayer: RoomStage.Layer? = null
    /** Each wall: its full layer, its cut-down layer, and when the camera ([ex], [ez] world) should see it cut. */
    private class Wall(val full: RoomStage.Layer, val cut: RoomStage.Layer, val cutWhen: (Float, Float) -> Boolean)
    private val walls = ArrayList<Wall>()
    private val ceiling = stage.layer()
    private val roof = stage.layer()
    var pieces = 0; private set
    private val counts = HashMap<RoomKit.Kind, Int>()
    val kinds: String get() = counts.entries.joinToString { "${it.key.name.lowercase()} ${it.value}" }

    /** The night lights: (x, y, z) world, spot (down) or point, its colour and strength. */
    private class Lamp(val x: Float, val y: Float, val z: Float, val spot: Boolean, val r: Float, val g: Float, val b: Float, val lumens: Float)
    private val lamps = ArrayList<Lamp>()

    init {
        shell()
        furnish()
        stage.nightLights(lamps.map { floatArrayOf(it.x, it.y, it.z, if (it.spot) 1f else 0f, it.r, it.g, it.b, it.lumens) })
    }

    private fun count(k: RoomKit.Kind) { pieces++; counts[k] = (counts[k] ?: 0) + 1 }

    /** [name] at ([x], [z]) texels (its front's middle), facing ([fx], [fz]), raised [lift] texels. */
    private fun put(name: String, x: Int, z: Int, fx: Float = 0f, fz: Float = 1f, lift: Int = 0) {
        val mesh = RoomKit.mesh(name, art).let { if (lift != 0) RoomKit.lift(it, lift * T) else it }
        things.addMesh(mesh, listOf(floatArrayOf(x * T, z * T, -fz, fx, fx, fz, 0f)), casts = true)
        count(RoomKit.PIECES.first { it.name == name }.kind)
    }

    private fun card(img: PixImg, x: Int, z: Int, lift: Int = 0, kind: RoomKit.Kind? = RoomKit.Kind.CARD) {
        cards += Triple(img, floatArrayOf(x * T, z * T), lift * T)
        kind?.let { count(it) }
    }

    private fun flat(name: String, x: Int, z: Int, lift: Int) {
        things.addMesh(RoomKit.lift(RoomKit.flat(art(name)), lift * T), listOf(floatArrayOf(x * T, z * T, 1f, 0f, 0f, 1f, 0f)), casts = false)
        count(RoomKit.Kind.FLAT)
    }

    private fun shell() {
        // outside: a pavement all round, a hair under the floor
        val ground = MeshBuilder().also { m ->
            val g = PixImg(1, 1).also { it.fill(Paint.CONCRETE.b) }
            m.quad(MeshBuilder.v(-900f, -.4f, -900f), MeshBuilder.v(1300f, -.4f, -900f), MeshBuilder.v(1300f, -.4f, 1300f), MeshBuilder.v(-900f, -.4f, 1300f), g)
        }.build()
        things.addMesh(ground, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
        things.addMesh(RoomKit.floor(art("floor"), W, D), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)

        val win = art("window")
        val wt = RoomKit.WALL_T
        // (the street's world is drawn mirrored against the usual right hand: looking along +z, +x is on the right; so a
        // wall whose inside faces (ox, oz) runs along (-oz, ox) for its pictures to read the right way round)
        // the street front (z = D): two windows over the booths, the door; its start at world x -wt
        fun frontX(x: Int, w: Int) = x + wt
        val (w1, o1) = RoomKit.window(win, frontX(60 - 28, 56), 34)
        val (w2, o2) = RoomKit.window(win, frontX(150 - 28, 56), 34)
        val door = RoomKit.crop(art("door"), 10, 8, 54, 64)
        val dDec = RoomKit.Decal(door, frontX(300 - 22, 44), 0)
        addWall(W + 2 * wt, listOf(w1, w2, dDec), listOf(o1, o2), floatArrayOf(-wt * T, D * T, 1f, 0f, 0f, -1f)) { _, ez -> ez > D * T }
        // the kitchen's back wall (z = 0)
        addWall(W + 2 * wt, listOf(RoomKit.Decal(art("picture2").let { RoomKit.crop(it, 6, 14, 26, 32) }, W + wt - 250, 44)), emptyList(), floatArrayOf((W + wt) * T, 0f, -1f, 0f, 0f, 1f)) { _, ez -> ez < 0f }
        // the left wall (x = 0), from the back (z = 0) on: two pictures over the side table, a window by the booths
        val (w3, o3) = RoomKit.window(win, 210 - 28, 34)
        val pic1 = RoomKit.crop(art("picture1"), 8, 14, 26, 38)
        val pic2 = RoomKit.crop(art("picture2"), 6, 14, 26, 32)
        addWall(D, listOf(w3, RoomKit.Decal(pic1, 158, 40), RoomKit.Decal(pic2, 184, 42)), listOf(o3), floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f)) { ex, _ -> ex < 0f }
        // the right wall (x = W), from the front (z = D) back: a window over the round table
        val (w4, o4) = RoomKit.window(win, D - 230 - 28, 34)
        addWall(D, listOf(w4), listOf(o4), floatArrayOf(W * T, D * T, 0f, -1f, -1f, 0f)) { ex, _ -> ex > W * T }
        // the partition (its dining face at z = P): the pass over the counter, the kitchen door at the right, a picture
        // (its start at world x W: world x is W less the distance along it)
        val pass = RoomKit.Opening(W - 270, W - 110, 30, 62)
        val kdoor = RoomKit.Opening(W - 352, W - 318, 0, 60)
        addWall(W, listOf(RoomKit.Decal(pic1, W - 58, 40)), listOf(pass, kdoor), floatArrayOf(W * T, P * T, -1f, 0f, 0f, 1f)) { _, ez -> ez < P * T }
        // the pass's shelf: a board on the sill, both sides
        val shelf = MeshBuilder().also { m -> m.panelBox(110 * T, 30 * T, (P - wt - 6) * T, 270 * T, 32 * T, (P + 6) * T, Paint.WOOD, 3) }.build()
        things.addMesh(shelf, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true)
        // the ceiling: it only throws the shadow (the sun comes in by the windows); never drawn, so the camera sees in
        val ceil = MeshBuilder().also { m ->
            val g = PixImg(1, 1).also { it.fill(-1) }
            val y = RoomKit.WALL_H * T
            m.quad(MeshBuilder.v(-20f, y, -20f), MeshBuilder.v(W * T + 20f, y, -20f), MeshBuilder.v(W * T + 20f, y, D * T + 20f), MeshBuilder.v(-20f, y, D * T + 20f), g)
        }.build()
        ceiling.addMesh(ceil, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true)
        roof.addMesh(RoomKit.ceiling(art("wall"), W, D, RoomKit.WALL_H), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
    }

    /** A wall [len] long at [at] (world x, z of its start, along x, z, out x, z: its inside faces out), cut down [cutWhen]. */
    private fun addWall(len: Int, decals: List<RoomKit.Decal>, openings: List<RoomKit.Opening>, at: FloatArray, cutWhen: (Float, Float) -> Boolean) {
        val wallArt = art("wall")
        val reveal = RoomKit.rowMode(wallArt, 20, 32, 64)
        val face = RoomKit.wallFace(wallArt, len, decals, openings)
        val full = RoomKit.wall(wallArt, face, RoomKit.outsideOf(face), openings, reveal)
        val low = RoomKit.wallFace(wallArt, len, decals, openings, RoomKit.CUT_H)
        val cut = RoomKit.wall(wallArt, low, RoomKit.outsideOf(low), openings, reveal)
        val pose = listOf(floatArrayOf(at[0], at[1], at[2], at[3], at[4], at[5], 0f))
        val f = stage.layer().also { it.addMesh(full, pose, casts = true) }
        val c = stage.layer().also { it.addMesh(cut, pose, casts = false) }
        walls += Wall(f, c, cutWhen)
    }

    private fun furnish() {
        // the kitchen, against the back wall
        put("fridge", 24, 26); put("fridge", 58, 26)
        put("stove", 98, 30); put("stove", 128, 30)
        put("kitchenCounter", 176, 24); put("kitchenCounter", 234, 24)
        card(art("coffee"), 230, 12, lift = RoomKit.COUNTER_TOP)
        card(people[1], 140, 60, kind = null)
        // the counter and its stools, the display at its end
        for (x in listOf(120, 174, 228)) put("counter", x, 150)
        put("display", 270, 158)
        for (x in listOf(106, 136, 166, 196, 226)) put("stool", x, 172)
        card(art("plantSmall"), 98, 140, lift = RoomKit.COUNTER_TOP)
        flat("plateFood2", 170, 138, RoomKit.COUNTER_TOP)
        // two booths under the windows: one with its back to the wall, a table, one facing the wall
        for (x in listOf(60, 150)) {
            put("booth", x, D - 28, 0f, -1f)
            put("clothTable", x, D - 30)
            put("booth", x, D - 30 - 34 - 2)
        }
        flat("plateFood", 50, D - 46, RoomKit.TABLE_TOP); flat("plateFood2", 72, D - 54, RoomKit.TABLE_TOP)
        flat("plateFood", 160, D - 50, RoomKit.TABLE_TOP)
        // the round table by the door with two chairs
        put("roundTable", 300, 236)
        put("chair", 268, 230, 1f, 0f); put("chair", 332, 212, -1f, 0f)
        flat("plateFood2", 300, 224, RoomKit.TABLE_TOP)
        // the machine against the right wall
        put("jukebox", W - 20, 120, -1f, 0f)
        // the side table and its lamp against the left wall, a big plant in the corner, a palm by the door
        put("sideTable", 16, 190, 1f, 0f)
        put("lamp", 16 - 8 + 10, 190, 1f, 0f, lift = 22)
        card(art("plantBig"), 24, 118)
        card(art("palm"), 352, D - 20)
        card(art("plantSmall"), W - 16, 160)
        // people for scale: one at the counter, one by the door
        card(people[0], 250, 205, kind = null)
        // the pendants: over the counter, the booths and the round table, hung from the ceiling
        val ceil = RoomKit.WALL_H
        for ((x, z) in listOf(147 to 140, 201 to 140, 60 to D - 47, 150 to D - 47, 300 to 222)) {
            put("pendant", x, z, lift = ceil)
            lamps += Lamp(x * T, (ceil - 19.5f) * T, z * T, true, 1f, .78f, .52f, 32000f)
        }
        // the table lamp, the machine's glow, the kitchen's tube
        lamps += Lamp(20 * T, 34 * T, 180 * T, false, 1f, .7f, .45f, 40000f)
        lamps += Lamp((W - 34) * T, 40 * T, 104 * T, false, .55f, .7f, 1f, 15000f)
        lamps += Lamp(160 * T, (ceil - 4) * T, 44 * T, true, .9f, .95f, 1f, 40000f)
    }

    /** Renders the room from [eye] towards [at] at [hour]: the walls before the camera cut down, the cards turned to it. */
    fun shoot(file: File, hour: Float, eye: FloatArray, at: FloatArray, fov: Float) {
        for (w in walls) {
            val cut = w.cutWhen(eye[0], eye[2])
            w.full.shown = !cut; w.cut.shown = cut
        }
        ceiling.shown = false
        // the ceiling is seen only from inside the room under it (from above it is left off, Sims-style)
        roof.shown = eye[1] < RoomKit.WALL_H * T && eye[0] in 0f..W * T && eye[2] in 0f..D * T
        // the cards face the camera (as the town's people do): a fresh layer for this view
        cardLayer?.let { stage.drop(it) }
        val cl = stage.layer()
        for ((img, p, lift) in cards) {
            var fx = eye[0] - p[0]; var fz = eye[2] - p[1]
            val l = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-3f); fx /= l; fz /= l
            // upright under the game's usual 3/4 view; leaning back as the camera looks more steeply down (up to 60 degrees)
            val el = kotlin.math.atan2(eye[1] - lift - 20f, l)
            val mesh = RoomKit.card(img, (el - .52f).coerceIn(0f, 1.05f))
            cl.addMesh(if (lift != 0f) RoomKit.lift(mesh, lift) else mesh, listOf(floatArrayOf(p[0], p[1], -fz, fx, fx, fz, 0f)), casts = true)
        }
        cardLayer = cl
        stage.shoot(file, hour, eye, at, fov)
    }
}

/** A Filament stage for the room: a sun, a sky light, the night's lamps, one camera, a swap chain to read back. */
internal class RoomStage(val engine: Engine, val lit: Material, val proxy: Material, val w: Int, val h: Int) {
    val scene = engine.createScene()
    val view = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    val sun = EntityManager.get().create()
    val swap = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
    val renderer = engine.createRenderer().also { it.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) } }
    val grading: ColorGrading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)
    var ibl: IndirectLight? = null
    val look = FloatArray(16)

    /** A layer and whether it is drawn and casts. */
    class Layer(val props: PropLayer) { var shown = true; var shadows = true; val draws: Int get() = props.draws; val triangles: Int get() = props.triangles
        fun addMesh(m: PropMesh, at: List<FloatArray>, casts: Boolean) { props.addMesh(m, at, casts) } }
    val layers = ArrayList<Layer>()
    private val lights = ArrayList<Pair<Int, Float>>()

    init {
        view.scene = scene; view.camera = camera
        view.dithering = View.Dithering.NONE; view.antiAliasing = View.AntiAliasing.NONE; view.sampleCount = 1
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = true; view.shadowType = View.ShadowType.PCF
        view.viewport = Viewport(0, 0, w, h)
        // (the lamps' froxels: Filament's default range is 5 to 100 of its units (metres), a few feet of our street units)
        view.setDynamicLightingOptions(10f, 3000f)
        camera.setExposure(1f)
        val shadow = LightManager.ShadowOptions().apply {
            mapSize = 4096; shadowCascades = 3; cascadeSplitPositions = floatArrayOf(.15f, .35f, .65f); stable = true; lispsm = false
            constantBias = .05f; normalBias = .4f; polygonOffsetConstant = .5f; polygonOffsetSlope = .2f
            // (the camera's far is a million, as the look's depth is infinite: the cascades span the room's distances instead)
            shadowFar = 1600f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 1f, 1f).intensity(1f).direction(0f, -1f, 0f).castShadows(true).shadowOptions(shadow).build(engine, sun)
        scene.addEntity(sun)
    }

    fun layer(): Layer = Layer(PropLayer(engine, scene, lit, proxy, anisotropy = 1f)).also { layers += it }
    fun drop(l: Layer) { l.props.destroy(); layers.remove(l) }
    fun clear() { for (l in layers) l.props.destroy(); layers.clear() }

    /** The lamps lit at night: each (x, y, z, spot 1 / point 0, r, g, b, lumens). */
    fun nightLights(list: List<FloatArray>) {
        for (l in list) {
            val e = EntityManager.get().create()
            val spot = l[3] > .5f
            val b = LightManager.Builder(if (spot) LightManager.Type.SPOT else LightManager.Type.POINT)
                .position(l[0], l[1], l[2]).color(l[4], l[5], l[6]).intensity(0f).falloff(if (spot) 220f else 150f)
            if (spot) b.direction(0f, -1f, 0f).spotLightCone(.5f, 1.25f).castShadows(true)
                .shadowOptions(LightManager.ShadowOptions().apply { mapSize = 1024; constantBias = .1f; normalBias = .6f })
            b.build(engine, e)
            scene.addEntity(e)
            lights += e to l[7]
        }
    }

    private fun light(hour: Float, sunSide: Boolean) {
        val night = hour >= 19f || hour < 6f
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        // the afternoon sun from the street's side and the right, low enough to come in by the windows
        val d = if (sunSide) floatArrayOf(-.45f, -.75f, -.5f) else floatArrayOf(-.42f, -.52f, -.74f)
        val dl = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        lm.setDirection(li, d[0] / dl, d[1] / dl, d[2] / dl)
        lm.setColor(li, 1f, .93f, .82f)
        lm.setIntensity(li, if (night) 0f else 2.6f)
        lm.setShadowCaster(li, !night)
        for ((e, lumens) in lights) { val i = lm.getInstance(e); lm.setIntensity(i, if (night) lumens else 0f) }
        // indoors the sky light is weaker than on the street; at night a dim blue from the windows
        val sh = if (night) floatArrayOf(.07f, .08f, .13f, 0f, 0f, 0f, -.01f, -.01f, -.02f, 0f, 0f, 0f)
        else floatArrayOf(.40f, .39f, .38f, -.02f, -.02f, -.02f, -.07f, -.07f, -.07f, 0f, 0f, 0f)
        ibl?.let { engine.destroyIndirectLight(it) }
        ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine).also { scene.indirectLight = it }
    }

    /** Renders from [eye] towards [at] at [hour] into [file] ([fov]: degrees across), over a plain sky. */
    fun shoot(file: File, hour: Float, eye: FloatArray, at: FloatArray, fov: Float = 40f, sunSide: Boolean = false) {
        light(hour, sunSide)
        val f = norm(floatArrayOf(at[0] - eye[0], at[1] - eye[1], at[2] - eye[2]))
        // the street's handedness (FilamentPropsPreview): right = (f.z, 0, -f.x); straight down needs a forward hint
        var r = floatArrayOf(f[2], 0f, -f[0])
        if (r[0] * r[0] + r[2] * r[2] < 1e-6f) r = floatArrayOf(-1f, 0f, 0f)
        r = norm(r)
        val u = cross(f, r).let { if (it[1] < 0f) floatArrayOf(-it[0], -it[1], -it[2]) else it }
        val cx = w / 2f; val cy = h / 2f
        val fl = cx / tan(Math.toRadians(fov / 2.0)).toFloat()
        val m = FloatArray(12)
        for (i in 0..2) { m[i] = fl * r[i] + cx * f[i]; m[4 + i] = -fl * u[i] + cy * f[i]; m[8 + i] = f[i] }
        m[3] = -(m[0] * eye[0] + m[1] * eye[1] + m[2] * eye[2]); m[7] = -(m[4] * eye[0] + m[5] * eye[1] + m[6] * eye[2]); m[11] = -(f[0] * eye[0] + f[1] * eye[1] + f[2] * eye[2])
        LookMatrix.pinhole(m, w.toFloat(), h.toFloat(), 2f, look)
        // (the same near as the look's depth, 2: Filament finds each pixel's distance, and so its lamps, from that depth)
        val near = 2.0; val k = near / fl
        // Filament's own camera (its lamps' froxels, the sun's shadow cascades) must see what the look draws: the street's
        // world is mirrored against Filament's right hand, so its projection's x is turned round
        val l = -cx * k; val rr = (w - cx) * k; val b = -(h - cy) * k; val t = cy * k; val far = 1.0e6
        val p = DoubleArray(16)
        p[0] = -2 * near / (rr - l); p[8] = -(rr + l) / (rr - l)
        p[5] = 2 * near / (t - b); p[9] = (t + b) / (t - b)
        p[10] = -(far + near) / (far - near); p[14] = -2 * far * near / (far - near); p[11] = -1.0
        camera.setCustomProjection(p, near, far)
        camera.lookAt(eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(), at[0].toDouble(), at[1].toDouble(), at[2].toDouble(), u[0].toDouble(), u[1].toDouble(), u[2].toDouble())
        val dark = if (hour >= 19f || hour < 6f) 1f else 0f
        for (l in layers) l.props.update(look, dark, shown = l.shown, shadows = l.shadows)
        val px = draw()
        val night = dark > .5f
        val top = if (night) intArrayOf(14, 18, 40) else intArrayOf(120, 170, 225)
        val bot = if (night) intArrayOf(40, 44, 70) else intArrayOf(200, 225, 240)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            val t = y / (h - 1f)
            val sr = (top[0] + (bot[0] - top[0]) * t).toInt(); val sg = (top[1] + (bot[1] - top[1]) * t).toInt(); val sb = (top[2] + (bot[2] - top[2]) * t).toInt()
            for (x in 0 until w) {
                val p = px[y * w + x]; val a = p ushr 24
                fun ch(s: Int, b: Int) = (((p shr s) and 255) + b * (255 - a) / 255).coerceAtMost(255)
                img.setRGB(x, y, (ch(16, sr) shl 16) or (ch(8, sg) shl 8) or ch(0, sb))
            }
        }
        ImageIO.write(img, "png", file)
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
        clear()
        val em = EntityManager.get()
        for ((e, _) in lights) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
        ibl?.let { engine.destroyIndirectLight(it) }
        scene.remove(sun); engine.destroyEntity(sun); em.destroy(sun)
        engine.destroyColorGrading(grading)
        engine.destroyRenderer(renderer); engine.destroySwapChain(swap)
        val ce = camera.entity
        engine.destroyCameraComponent(ce); em.destroy(ce)
        engine.destroyView(view); engine.destroyScene(scene)
    }

    private fun norm(a: FloatArray): FloatArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
