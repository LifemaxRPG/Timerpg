import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder
import com.lifemax.rpg.feature.habits.presentation.scene3d.Paint
import com.lifemax.rpg.feature.habits.presentation.scene3d.PixImg
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomKit
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Material
import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rooms of every kind furnished from the pack's pieces (docs/INTERIORS_3D.md, the pipeline), -Dmode=rooms: living room,
 * bedroom, kitchen, bathroom, shop, office and classroom, laid out from `pieces.json` (tools/cut_pieces.py), rendered from the
 * high game camera, a corner and eye level, by day and by night into build/preview/rooms/. Also a contact sheet of the pieces
 * beside their sprites and a person at the same scale (pieces_sheet.png). Desktop renders: nothing here has run on the phone.
 * -Drooms=kitchen,bath (the property the older rooms mode also reads) for some rooms only, -Dhour=13 or 22 for one time of day.
 */
fun renderRooms(out: String) {
    val dir = File(out, "rooms").also { it.mkdirs() }
    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = assetsDir()
    fun mat(n: String): Material { val b = File(assets, "filament/$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
    val lit = mat("street_lit"); val proxy = mat("shadow_proxy")
    val lib = PieceLib(File(assets, "room3d/pieces"), File(assets, "room3d/diner"))
    val sheet = ImageIO.read(File(assets, "characters/char_01.png"))
    val sheet2 = File(assets, "characters/char_07.png").takeIf { it.exists() }?.let { ImageIO.read(it) } ?: sheet
    fun person(s: BufferedImage, view: Int): PixImg { val x = view * 6 * 32; return RoomKit.pix(32, 64, s.getRGB(x, 64, 32, 64, null, 0, 32)) }
    val people = listOf(person(sheet, 3), person(sheet2, 3))
    val hours = System.getProperty("hour")?.toFloatOrNull()?.let { listOf(it) } ?: listOf(13f, 22f)
    val only = System.getProperty("rooms")?.split(',')?.toSet()
    val report = StringBuilder()
    val stats = lib.coverage()
    report.appendLine(stats)

    for (spec in roomSpecs()) {
        if (only != null && spec.name !in only) continue
        val stage = RoomStage(engine, lit, proxy, 1400, 900)
        val room = Room(stage, lib, spec, people)
        report.appendLine("${spec.name}: ${room.pieces} pieces (${room.kinds}), ${stage.layers.sumOf { it.draws }} batches, ${stage.layers.sumOf { it.triangles }} triangles")
        val W = spec.w * RoomKit.T; val D = spec.d * RoomKit.T
        val views = listOf(
            "high" to (Triple(floatArrayOf(W * .5f, 300f + D * .35f, D + 250f + D * .2f), floatArrayOf(W * .5f, 0f, D * .5f), 52f)),
            "corner" to (Triple(floatArrayOf(W + 170f, 220f, D + 160f), floatArrayOf(W * .5f, 10f, D * .5f), 54f)),
            "eye" to (Triple(floatArrayOf(W * .5f, 40f, D * .96f), floatArrayOf(W * .5f, 24f, D * .2f), 70f)),
        )
        for (hour in hours) for ((v, t) in views) {
            val tag = if (hour < 18f) "day" else "night"
            room.shoot(File(dir, "${spec.name}_${v}_$tag.png"), hour, t.first, t.second, t.third)
        }
        stage.destroy()
    }

    if (only == null || "sheet" in only) pieceSheet(engine, lit, proxy, lib, people[0], File(dir, "pieces_sheet.png"), report)
    File(dir, "rooms.txt").writeText(report.toString())
    print(report)
    engine.destroyMaterial(lit); engine.destroyMaterial(proxy)
    engine.destroy()
}

// ---- pieces.json ---------------------------------------------------------------------------------------------------

/** pieces.json and its atlases: a design's [RoomKit.PieceSpec] and its pictures. */
private class PieceLib(val dir: File, val diner: File) {
    class Design(val spec: RoomKit.PieceSpec, val kind: String, val atlas: List<Triple<String, Int, Int>>, val derived: String?, val variants: Int)
    val designs = LinkedHashMap<String, Design>()
    private val atlases = HashMap<String, BufferedImage>()
    private val cache = HashMap<String, PixImg>()
    val all: JSONArray

    init {
        val root = JSONObject(File(dir, "pieces.json").readText())
        all = root.getJSONArray("designs")
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            val flat = LinkedHashMap<String, IntArray>()
            fun walk(k: String, v: Any?) {
                when (v) {
                    is JSONObject -> for (kk in v.keys()) walk(if (k.isEmpty()) kk else "$k.$kk", v.get(kk))
                    is JSONArray -> flat[k] = IntArray(v.length()) { v.getInt(it) }
                    is Boolean -> flat[k] = intArrayOf(if (v) 1 else 0)
                }
            }
            d.optJSONObject("spec")?.let { for (k in it.keys()) walk(k, it.get(k)) }
            val split = d.optJSONObject("split")?.optInt("row") ?: 0
            val at = d.optJSONArray("atlas")
            val atlas = if (at == null) emptyList() else (0 until at.length()).map { val a = at.getJSONObject(it); Triple(a.getString("file"), a.getInt("x"), a.getInt("y")) }
            val id = d.getString("id")
            designs[id] = Design(RoomKit.PieceSpec(id, d.getString("family"), d.getString("kind"), d.getInt("w"), d.getInt("h"), split, flat), d.getString("kind"), atlas,
                d.optString("derived").ifEmpty { null }, d.optInt("variants"))
        }
    }

    /** The designs of [family] that have pictures in the atlases. */
    fun family(f: String) = designs.values.filter { it.spec.family == f && it.atlas.isNotEmpty() }

    /** Design [id]'s picture, [variant] (a colour variant, wrapping). */
    fun art(id: String, variant: Int = 0): PixImg {
        val d = designs[id] ?: error("no design $id")
        if (d.atlas.isEmpty()) error("design $id (${d.spec.family}) has no picture in the atlases")
        val a = d.atlas[variant % d.atlas.size]
        return cache.getOrPut("$id/${variant % d.atlas.size}") {
            val im = atlases.getOrPut(a.first) { ImageIO.read(File(dir, a.first)) }
            val w = d.spec.w; val h = d.spec.h
            RoomKit.pix(w, h, im.getRGB(a.second, a.third, w, h, null, 0, w))
        }
    }

    fun mesh(id: String, variant: Int = 0, spec: RoomKit.PieceSpec = designs[id]!!.spec): PropMesh = RoomKit.pieceMesh(spec, art(id, variant))

    fun dinerArt(n: String): PixImg = cache.getOrPut("diner/$n") { val im = ImageIO.read(File(diner, "$n.png")); RoomKit.pix(im.width, im.height, im.getRGB(0, 0, im.width, im.height, null, 0, im.width)) }

    fun coverage(): String {
        val sb = StringBuilder("designs in pieces.json: ${designs.size}; with pictures in the atlases: ${designs.values.count { it.atlas.isNotEmpty() }}\n")
        val byKind = designs.values.groupBy { it.kind }.mapValues { it.value.size }
        sb.appendLine("  by kind: $byKind")
        val fam = designs.values.filter { it.atlas.isNotEmpty() }.groupBy { it.kind + "/" + it.spec.family }.mapValues { it.value.size }.toList().sortedByDescending { it.second }
        sb.appendLine("  built (pictures shipped): ${fam.joinToString { "${it.first} ${it.second}" }}")
        return sb.toString()
    }
}

// ---- room layouts ----------------------------------------------------------------------------------------------------

private enum class Side { BACK, FRONT, LEFT, RIGHT }

/** A piece in a room: against a wall ([side], [at] along it) or free at ([x], [z]) facing ([fx], [fz]); [lift] texels up. */
private class Put(val id: String, val variant: Int = 0, val side: Side? = null, val at: Int = 0, val x: Int = 0, val z: Int = 0, val fx: Float = 0f, val fz: Float = 1f,
                  val lift: Int = 0, val gap: Int = 0, val h: Int? = null)

private class Opening(val side: Side, val at: Int, val kind: String)
private class RoomSpec(val name: String, val w: Int, val d: Int, val things: List<Put>, val openings: List<Opening>, val people: List<Triple<Int, Int, Int>>,
                       val lamps: List<Triple<Int, Int, Int>>, val plants: List<Triple<String, Int, Int>> = emptyList(), val rugs: List<Triple<String, Int, Int>> = emptyList())

private fun roomSpecs(): List<RoomSpec> {
    fun w(id: String, side: Side, at: Int, v: Int = 0, lift: Int = 0, gap: Int = 0) = Put(id, v, side, at, lift = lift, gap = gap)
    fun f(id: String, x: Int, z: Int, fx: Float = 0f, fz: Float = 1f, v: Int = 0, lift: Int = 0, h: Int? = null) = Put(id, v, null, 0, x, z, fx, fz, lift, h = h)
    val B = Side.BACK; val L = Side.LEFT; val R = Side.RIGHT; val F = Side.FRONT
    return listOf(
        // living room: sofa and armchairs facing a TV, a low table on a rug, bookcases, a dresser, plants
        RoomSpec("living", 288, 256, listOf(
            w("generic@106,1422", B, 144, lift = 38), w("living_70", B, 144),
            w("classroom_60", L, 70), w("classroom_74", L, 150),
            w("living_53", R, 170, v = 0), w("living_19", R, 90),
            f("living_sofa_blue", 144, 232, 0f, -1f),
            f("living_sofa_white_armchair", 48, 176, 1f, 0f), f("living_sofa_brown_armchair", 240, 168, -1f, 0f),
            f("generic@170,208", 144, 142, 0f, 1f, h = 12),
            w("generic@10,436", B, 60, lift = 44), w("generic@68,438", L, 210, lift = 40),
            f("living_ottoman_blue", 214, 214, 0f, 1f),
        ), listOf(Opening(B, 50, "window"), Opening(B, 232, "window"), Opening(R, 210, "window"), Opening(F, 40, "door")),
            listOf(Triple(0, 220, 180)), listOf(Triple(144, 150, 10)),
            plants = listOf(Triple("living_13", 30, 40), Triple("living_14", 262, 30), Triple("living_16", 262, 230)),
            rugs = listOf(Triple("generic@0,1476", 144, 150))),
        // bedroom: a double bed under the window between nightstands, a kid's bed, a wardrobe, a dresser
        RoomSpec("bedroom", 320, 240, listOf(
            w("bedroom_217", B, 190), w("living_65", B, 138), w("living_69", B, 242),
            w("bedroom_1", B, 60, v = 2), w("bedroom_537", L, 160), w("bedroom_393", R, 150), w("bedroom_539", R, 60),
            w("generic@68,438", B, 100, lift = 44), w("living_5", F, 80),
        ), listOf(Opening(L, 60, "window"), Opening(F, 200, "door"), Opening(R, 100, "window")),
            listOf(Triple(1, 250, 190)), listOf(Triple(190, 140, 12), Triple(60, 160, 12)),
            plants = listOf(Triple("living_16", 300, 220), Triple("living_17", 22, 220)),
            rugs = listOf(Triple("generic@262,422", 190, 120))),
        // kitchen: fridge, counters, sink and stove along the back wall, a table with chairs, a window and a picture
        RoomSpec("kitchen", 288, 224, listOf(
            w("kitchen_158", B, 26), w("kitchen_121", B, 86), w("kitchen_141", B, 140), w("kitchen_148", B, 186), w("kitchen_124", B, 220), w("kitchen_125", B, 252),
            w("kitchen_122", L, 100), w("kitchen_121", L, 160),
            w("generic@10,436", L, 190, lift = 40),
            f("kitchen_309", 144, 160, 0f, 1f), f("kitchen_280", 98, 150, 1f, 0f), f("kitchen_284", 194, 150, -1f, 0f, v = 0),
            f("kitchen_280", 144, 112, 0f, 1f, v = 1),
        ), listOf(Opening(B, 140, "window"), Opening(R, 100, "window"), Opening(F, 230, "door")), listOf(Triple(1, 230, 160)), listOf(Triple(144, 120, 12)),
            plants = listOf(Triple("living_18", 270, 30)), rugs = listOf(Triple("generic@262,422", 144, 150))),
        // bathroom: toilet, vanity with a basin, a bath, a shower, a mirror
        RoomSpec("bath", 224, 224, listOf(
            w("bath_21", B, 40), w("bath_5", B, 112), w("bath_151", B, 186, v = 0), w("bath_152", R, 150),
            w("generic@354,2318", L, 140), w("bath_2", L, 70),
        ), listOf(Opening(B, 112, "window"), Opening(F, 150, "door")), listOf(Triple(1, 160, 150)), listOf(Triple(112, 110, 12)),
            plants = listOf(Triple("living_15", 30, 190))),
        // shop: shelves and cold cabinets along the walls, rows of gondolas, a checkout with a register
        RoomSpec("shop", 352, 288, listOf(
            w("grocery_51", B, 40), w("grocery_52", B, 94), w("grocery_53", B, 148), w("grocery_54", B, 202), w("grocery_56", B, 256), w("grocery_113", B, 310),
            w("grocery_98", L, 70), w("grocery_102", L, 110), w("grocery_105", L, 150),
            w("grocery_101", R, 80), w("grocery_98", R, 120, v = 1), w("grocery_102", R, 160, v = 1),
            f("grocery_98", 130, 150, 0f, 1f, v = 2), f("grocery_105", 130, 120, 0f, -1f, v = 1),
            f("grocery_102", 210, 150, 0f, 1f, v = 1), f("grocery_101", 210, 120, 0f, -1f),
            w("grocery_178", F, 110), w("grocery_159", F, 190), w("grocery_177", F, 150),
        ), listOf(Opening(F, 50, "window"), Opening(F, 260, "door"), Opening(L, 220, "window")), listOf(Triple(0, 290, 200)), listOf(Triple(110, 130, 12), Triple(240, 130, 12)),
            plants = listOf(Triple("living_17", 330, 260))),
        // office: desks with chairs, filing cabinets, bookcases
        RoomSpec("office", 288, 224, listOf(
            w("classroom_26", B, 90), w("classroom_25", B, 200),
            w("conference_50", L, 60), w("conference_51", L, 92), w("conference_52", L, 124), w("classroom_60", R, 70), w("classroom_74", R, 140),
            f("classroom_25", 90, 170, 0f, -1f), f("conference_37", 90, 138, 0f, 1f), f("conference_37", 200, 118, 0f, 1f),
            f("classroom_10", 220, 190, 0f, -1f), w("classroom_31", L, 190, lift = 36),
        ), listOf(Opening(B, 150, "window"), Opening(F, 150, "door")), listOf(Triple(0, 240, 150)), listOf(Triple(90, 110, 12), Triple(200, 110, 12)),
            plants = listOf(Triple("living_13", 262, 40), Triple("living_16", 30, 200)), rugs = listOf(Triple("generic@0,1476", 150, 150))),
        // classroom: rows of desks facing the board, the teacher's desk, bookcases, a world map
        RoomSpec("classroom", 352, 288, listOf(
            w("classroom_36", B, 176, lift = 30), w("classroom_26", B, 80), w("classroom_31", L, 150, lift = 36),
            w("classroom_60", R, 70), w("classroom_74", R, 140), w("classroom_57", R, 210),
            f("classroom_5", 90, 130, 0f, -1f), f("classroom_7", 150, 130, 0f, -1f, v = 0), f("classroom_9", 210, 130, 0f, -1f), f("classroom_11", 270, 130, 0f, -1f),
            f("classroom_5", 90, 190, 0f, -1f), f("classroom_9", 150, 190, 0f, -1f), f("classroom_7", 210, 190, 0f, -1f), f("classroom_11", 270, 190, 0f, -1f),
            f("classroom_5", 90, 250, 0f, -1f), f("classroom_11", 150, 250, 0f, -1f), f("classroom_9", 210, 250, 0f, -1f), f("classroom_7", 270, 250, 0f, -1f),
        ), listOf(Opening(L, 60, "window"), Opening(L, 230, "window"), Opening(F, 280, "door")), listOf(Triple(1, 40, 210)), listOf(Triple(110, 150, 12), Triple(240, 150, 12)),
            plants = listOf(Triple("living_13", 330, 30))),
    )
}

/** A room built from a [RoomSpec]: floor, four walls (those facing the camera cut down), pieces, plants, lights. */
private class Room(val stage: RoomStage, val lib: PieceLib, val spec: RoomSpec, val people: List<PixImg>) {
    private val T = RoomKit.T
    private val things = stage.layer()
    private val cards = ArrayList<Triple<PixImg, FloatArray, Float>>()
    private var cardLayer: RoomStage.Layer? = null
    private class Wall(val full: RoomStage.Layer, val cut: RoomStage.Layer, val cutWhen: (Float, Float) -> Boolean)
    private val walls = ArrayList<Wall>()
    private val ceiling = stage.layer()
    private val roof = stage.layer()
    var pieces = 0; private set
    private val counts = HashMap<String, Int>()
    val kinds: String get() = counts.entries.joinToString { "${it.key} ${it.value}" }
    private class Lamp(val x: Float, val y: Float, val z: Float, val spot: Boolean, val r: Float, val g: Float, val b: Float, val lumens: Float)
    private val lamps = ArrayList<Lamp>()
    private val W = spec.w; private val D = spec.d
    private val wt = RoomKit.WALL_T

    init {
        shell()
        for (r in spec.rugs) {
            val a = lib.art(r.first)
            things.addMesh(RoomKit.lift(RoomKit.flat(a), .6f), listOf(floatArrayOf(r.second * T, r.third * T, 1f, 0f, 0f, 1f, 0f)), casts = false)
        }
        for (p in spec.things) put(p)
        for (pl in spec.plants) { cards += Triple(lib.art(pl.first), floatArrayOf(pl.second * T, pl.third * T), 0f); count("card") }
        for (pe in spec.people) { cards += Triple(people[pe.first % people.size], floatArrayOf(pe.second * T, pe.third * T), 0f) }
        val ceil = RoomKit.WALL_H
        for ((x, z, _) in spec.lamps) {
            things.addMesh(RoomKit.lift(RoomKit.mesh("pendant") { lib.dinerArt(it) }, ceil * T), listOf(floatArrayOf(x * T, z * T, 0f, 1f, 1f, 0f, 0f)), casts = true)
            lamps += Lamp(x * T, (ceil - 19.5f) * T, z * T, true, 1f, .78f, .52f, 32000f)
        }
        stage.nightLights(lamps.map { floatArrayOf(it.x, it.y, it.z, if (it.spot) 1f else 0f, it.r, it.g, it.b, it.lumens) })
    }

    private fun count(k: String) { pieces++; counts[k] = (counts[k] ?: 0) + 1 }

    /** Places a piece: its mesh stands back from its front edge, so against a wall its front sits its own depth out from it. */
    private fun put(p: Put) {
        val d = lib.designs[p.id] ?: error("no design ${p.id}")
        val sp = if (p.h != null) RoomKit.PieceSpec(d.spec.id, d.spec.family, d.spec.kind, d.spec.w, d.spec.h, d.spec.splitRow, d.spec.s + ("h" to intArrayOf(p.h))) else d.spec
        if (d.kind == "card") { cards += Triple(lib.art(p.id, p.variant), floatArrayOf(p.x * T, p.z * T), p.lift * T); count("card"); return }
        if (d.kind == "flat") { things.addMesh(RoomKit.lift(RoomKit.flat(lib.art(p.id, p.variant)), (p.lift + .5f) * T), listOf(floatArrayOf(p.x * T, p.z * T, 1f, 0f, 0f, 1f, 0f)), casts = false); count("flat"); return }
        val mesh = lib.mesh(p.id, p.variant, sp)
        val depth = -mesh.bounds[2]
        var x = p.x * T; var z = p.z * T; var fx = p.fx; var fz = p.fz
        if (p.side != null) when (p.side) {
            Side.BACK -> { x = p.at * T; z = depth; fx = 0f; fz = 1f }
            Side.FRONT -> { x = p.at * T; z = D * T - depth; fx = 0f; fz = -1f }
            Side.LEFT -> { x = depth; z = p.at * T; fx = 1f; fz = 0f }
            Side.RIGHT -> { x = W * T - depth; z = p.at * T; fx = -1f; fz = 0f }
        }
        val m = if (p.lift != 0) RoomKit.lift(mesh, p.lift * T) else mesh
        things.addMesh(m, listOf(floatArrayOf(x, z, -fz, fx, fx, fz, 0f)), casts = true)
        count(d.kind)
    }

    private fun shell() {
        val ground = MeshBuilder().also { m ->
            val g = PixImg(1, 1).also { it.fill(Paint.CONCRETE.b) }
            m.quad(MeshBuilder.v(-900f, -.4f, -900f), MeshBuilder.v(1500f, -.4f, -900f), MeshBuilder.v(1500f, -.4f, 1500f), MeshBuilder.v(-900f, -.4f, 1500f), g)
        }.build()
        things.addMesh(ground, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
        things.addMesh(RoomKit.floor(lib.dinerArt("floor"), W, D), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
        val win = lib.dinerArt("window")
        val door = RoomKit.crop(lib.dinerArt("door"), 10, 8, 54, 64)
        fun decals(side: Side, off: Int): Pair<List<RoomKit.Decal>, List<RoomKit.Opening>> {
            val ds = ArrayList<RoomKit.Decal>(); val os = ArrayList<RoomKit.Opening>()
            for (o in spec.openings) if (o.side == side) {
                if (o.kind == "window") { val (d, op) = RoomKit.window(win, o.at - 28 + off, 34); ds += d; os += op } else ds += RoomKit.Decal(door, o.at - 22 + off, 0)
            }
            return ds to os
        }
        // (the street's world is mirrored: a wall whose inside faces (ox, oz) runs along (-oz, ox); see FilamentRoomPreview)
        decals(Side.FRONT, wt).let { (d, o) -> addWall(W + 2 * wt, d, o, floatArrayOf(-wt * T, D * T, 1f, 0f, 0f, -1f)) { _, ez -> ez > D * T } }
        decals(Side.BACK, wt).let { (d, o) -> addWall(W + 2 * wt, d, o, floatArrayOf((W + wt) * T, 0f, -1f, 0f, 0f, 1f)) { _, ez -> ez < 0f } }
        decals(Side.LEFT, 0).let { (d, o) -> addWall(D, d, o, floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f)) { ex, _ -> ex < 0f } }
        decals(Side.RIGHT, 0).let { (d, o) -> addWall(D, d, o, floatArrayOf(W * T, D * T, 0f, -1f, -1f, 0f)) { ex, _ -> ex > W * T } }
        val ceil = MeshBuilder().also { m ->
            val g = PixImg(1, 1).also { it.fill(-1) }
            val y = RoomKit.WALL_H * T
            m.quad(MeshBuilder.v(-20f, y, -20f), MeshBuilder.v(W * T + 20f, y, -20f), MeshBuilder.v(W * T + 20f, y, D * T + 20f), MeshBuilder.v(-20f, y, D * T + 20f), g)
        }.build()
        ceiling.addMesh(ceil, listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true)
        roof.addMesh(RoomKit.ceiling(lib.dinerArt("wall"), W, D, RoomKit.WALL_H), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
    }

    private fun addWall(len: Int, decals: List<RoomKit.Decal>, openings: List<RoomKit.Opening>, at: FloatArray, cutWhen: (Float, Float) -> Boolean) {
        val wallArt = lib.dinerArt("wall")
        val reveal = RoomKit.rowMode(wallArt, 20, 32, 64)
        val face = RoomKit.wallFace(wallArt, len, decals, openings)
        val full = RoomKit.wall(wallArt, face, RoomKit.outsideOf(face), openings, reveal)
        val low = RoomKit.wallFace(wallArt, len, decals, openings, RoomKit.CUT_H)
        val cut = RoomKit.wall(wallArt, low, RoomKit.outsideOf(low), openings, reveal)
        val pose = listOf(floatArrayOf(at[0], at[1], at[2], at[3], at[4], at[5], 0f))
        walls += Wall(stage.layer().also { it.addMesh(full, pose, casts = true) }, stage.layer().also { it.addMesh(cut, pose, casts = false) }, cutWhen)
    }

    fun shoot(file: File, hour: Float, eye: FloatArray, at: FloatArray, fov: Float) {
        for (w in walls) { val cut = w.cutWhen(eye[0], eye[2]); w.full.shown = !cut; w.cut.shown = cut }
        ceiling.shown = false
        roof.shown = eye[1] < RoomKit.WALL_H * T && eye[0] in 0f..W * T && eye[2] in 0f..D * T
        cardLayer?.let { stage.drop(it) }
        val cl = stage.layer()
        for ((img, p, lift) in cards) {
            var fx = eye[0] - p[0]; var fz = eye[2] - p[1]
            val l = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-3f); fx /= l; fz /= l
            val el = kotlin.math.atan2(eye[1] - lift - 20f, l)
            val mesh = RoomKit.card(img, (el - .52f).coerceIn(0f, 1.05f))
            cl.addMesh(if (lift != 0f) RoomKit.lift(mesh, lift) else mesh, listOf(floatArrayOf(p[0], p[1], -fz, fx, fx, fz, 0f)), casts = true)
        }
        cardLayer = cl
        stage.shoot(file, hour, eye, at, fov)
    }
}

// ---- the contact sheet: each piece beside its sprite and a person at the same scale -------------------------------------

/** One design of each family built and shot from the 3/4 view beside its sprite at 4 screen pixels a texel, a person card by it. */
private fun pieceSheet(engine: Engine, lit: Material, proxy: Material, lib: PieceLib, person: PixImg, file: File, report: StringBuilder) {
    val picks = listOf(
        "living_sofa_blue", "living_sofa_white_armchair", "bedroom_217", "bedroom_140", "bedroom_1", "bedroom_63", "bath_21", "bath_5", "bath_151", "classroom_26", "classroom_5", "classroom_14",
        "classroom_60", "kitchen_309", "generic@170,208", "kitchen_280", "generic@290,1666", "conference_37", "living_37", "bedroom_537", "bedroom_393", "living_65", "kitchen_158", "kitchen_148",
        "kitchen_121", "kitchen_141", "grocery_98", "grocery_113", "grocery_178", "grocery_159", "conference_50", "bath_61", "kitchen_406", "generic@128,162",
    ).filter { lib.designs[it]?.atlas?.isNotEmpty() == true }
    val ps = RoomStage(engine, lit, proxy, 440, 400)
    val shots = ArrayList<BufferedImage>()
    for (id in picks) {
        val d = lib.designs[id]!!
        val mesh = lib.mesh(id)
        val layer = ps.layer()
        layer.addMesh(mesh, listOf(floatArrayOf(0f, 0f, -1f, 0f, 0f, 1f, 0f)), casts = true)
        val fl = ps.layer()
        fl.addMesh(plainFloor(500f, 0xFF9C9C9C.toInt()), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = false)
        val b = mesh.bounds
        val c = floatArrayOf((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f)
        // a person beside it (32 x 64 picture, the feet at its frame's bottom), facing the camera, at the same texel size
        val px = if (d.spec.w > 84) 2f else 3f; val dist = 900f
        val pw = (b[3] - b[0]) / 2f + 20f * RoomKit.T
        val pc = ps.layer()
        val a = Math.toRadians(42.0)
        val eyeDir = floatArrayOf(-120f, (dist * sin(a)).toFloat(), (dist * cos(a)).toFloat())
        val el = kotlin.math.atan2(eyeDir[1], dist.toFloat())
        val pm = RoomKit.card(person, (el - .52f).coerceIn(0f, 1.05f))
        pc.addMesh(pm, listOf(floatArrayOf(b[3] + 18f * RoomKit.T, c[2], -1f, 0f, 0f, 1f, 0f)), casts = true)
        val flx = px * dist / RoomKit.T
        val fov = Math.toDegrees(2.0 * atan(220.0 / flx)).toFloat()
        val f = File(file.parentFile, "tmp_piece.png")
        ps.shoot(f, 13f, floatArrayOf(c[0] - 120f + 17f * RoomKit.T, c[1] + eyeDir[1], c[2] + eyeDir[2]), floatArrayOf(c[0] + 17f * RoomKit.T, c[1], c[2]), fov, sunSide = true)
        val s = lib.art(id)
        val sc = px.toInt()
        val pair = BufferedImage(440 + s.w * sc + 40, 400, BufferedImage.TYPE_INT_RGB)
        val g = pair.createGraphics()
        g.color = java.awt.Color(0x6E7B6E); g.fillRect(0, 0, pair.width, pair.height)
        val sp = BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, s.w, s.h, s.px, 0, s.w) }
        g.drawImage(sp, 20, (400 - s.h * sc) / 2, s.w * sc, s.h * sc, null)
        g.drawImage(ImageIO.read(f), s.w * sc + 40, 0, null)
        g.color = java.awt.Color.WHITE; g.drawString("$id (${d.spec.family}/${d.kind}): sprite and 3D, ${px.toInt()} px a texel, person for scale", 6, 14)
        g.dispose()
        shots += pair
        ps.clear()
    }
    ps.destroy()
    File(file.parentFile, "tmp_piece.png").delete()
    val colW = shots.maxOf { it.width }
    val cols = 3
    val sheetImg = BufferedImage(colW * cols, 400 * ((shots.size + cols - 1) / cols), BufferedImage.TYPE_INT_RGB)
    sheetImg.createGraphics().also { g -> g.color = java.awt.Color(0x6E7B6E); g.fillRect(0, 0, sheetImg.width, sheetImg.height); for ((i, im) in shots.withIndex()) g.drawImage(im, (i % cols) * colW, (i / cols) * 400, null); g.dispose() }
    ImageIO.write(sheetImg, "png", file)
    report.appendLine("contact sheet: ${shots.size} pieces")
}
