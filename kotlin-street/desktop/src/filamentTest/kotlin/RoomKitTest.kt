import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** The interiors trial's pieces (docs/INTERIORS_3D.md): built from the shipped pictures, standing where their footprints say. */
class RoomKitTest {
    private val dir = listOf(File("app/src/main/assets/room3d/diner"), File("../app/src/main/assets/room3d/diner")).first { it.isDirectory }
    private val art: (String) -> RoomArt = { n -> ImageIO.read(File(dir, "$n.png")).let { RoomArt(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) } }

    @Test fun rowsRepeatAPlainRowAndKeepTheOthers() {
        val s = RoomArt(2, 4, IntArray(8) { 0xFF000000.toInt() or (it / 2) })
        val o = RoomKit.rowsOf(s, 7, 1)
        assertEquals(7, o.h)
        // rows 0 and 1 as drawn, row 1 three times more, then rows 2 and 3
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 3), (0 until 7).map { o.argb[it * 2] and 255 })
    }

    @Test fun everyBoxAndPartsPieceFitsItsFootprint() {
        for (p in RoomKit.PIECES) {
            if (p.kind != RoomKit.Kind.BOX && p.kind != RoomKit.Kind.PARTS) continue
            val m = RoomKit.meshOf(p.name, art)
            assertTrue("${p.name} has no faces", m.idx.isNotEmpty() && m.idx.size % 3 == 0)
            val t = RoomKit.T
            val b = m.bounds
            // a pendant hangs from its cord's top (the origin); everything else stands on the floor
            if (p.name == "pendant") assertTrue("pendant top ${b[4]}", abs(b[4]) < .1f)
            else assertTrue("${p.name} bottom ${b[1]}", abs(b[1]) < .1f)
            assertTrue("${p.name} height ${(b[4] - b[1]) / t} against ${p.h}", abs((b[4] - b[1]) / t - p.h) <= 1.5f)
            // in front of its origin nothing (it stands back into -z), across within its width (the stove's pan handle aside)
            assertTrue("${p.name} reaches ${b[5] / t} in front", b[5] <= .1f + (if (p.name == "lamp" || p.name == "pendant") p.d * t / 2f else 0f))
            assertTrue("${p.name} ${(b[3] - b[0]) / t} wide against ${p.w}", (b[3] - b[0]) / t <= p.w + (if (p.name == "stove") 15f else 1.5f))
            for (u in m.uv) assertTrue("${p.name} uv $u", u in -1e-4f..1.0001f)
        }
    }

    // ---- the pipeline's pieces (tools/cut_pieces.py: assets/room3d/pieces/pieces.json and its atlases) ---------------------

    private val piecesDir = listOf(File("app/src/main/assets/room3d/pieces"), File("../app/src/main/assets/room3d/pieces")).first { it.isDirectory }

    /** Every design with pictures in the atlases, as the builders get it: (spec, its picture). */
    private fun shippedDesigns(): List<Pair<RoomKit.PieceSpec, RoomArt>> {
        val all = JSONObject(File(piecesDir, "pieces.json").readText()).getJSONArray("designs")
        val atlases = HashMap<String, java.awt.image.BufferedImage>()
        val out = ArrayList<Pair<RoomKit.PieceSpec, RoomArt>>()
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            val at = d.optJSONArray("atlas") ?: continue
            if (at.length() == 0) continue
            val flat = LinkedHashMap<String, IntArray>()
            fun walk(k: String, v: Any?) {
                when (v) {
                    is JSONObject -> for (kk in v.keys()) walk(if (k.isEmpty()) kk else "$k.$kk", v.get(kk))
                    is JSONArray -> flat[k] = IntArray(v.length()) { v.getInt(it) }
                    is Boolean -> flat[k] = intArrayOf(if (v) 1 else 0)
                }
            }
            d.optJSONObject("spec")?.let { for (k in it.keys()) walk(k, it.get(k)) }
            val w = d.getInt("w"); val h = d.getInt("h")
            val a = at.getJSONObject(0)
            val im = atlases.getOrPut(a.getString("file")) { ImageIO.read(File(piecesDir, a.getString("file"))) }
            out += RoomKit.PieceSpec(d.getString("id"), d.getString("family"), d.getString("kind"), w, h, d.optJSONObject("split")?.optInt("row") ?: 0, flat) to
                RoomArt(w, h, im.getRGB(a.getInt("x"), a.getInt("y"), w, h, null, 0, w))
        }
        return out
    }

    @Test fun everyShippedBoxAndPartsDesignBuildsAndStandsOnTheFloor() {
        val designs = shippedDesigns().filter { it.first.kind == "box" || it.first.kind == "parts" }
        assertTrue("only ${designs.size} box/parts designs shipped", designs.size >= 120)
        val t = RoomKit.T
        for ((p, a) in designs) {
            val m = RoomKit.pieceMeshOf(p, a)
            assertTrue("${p.id} has no faces", m.idx.isNotEmpty() && m.idx.size % 3 == 0)
            val b = m.bounds
            assertTrue("${p.id} bottom ${b[1]}", abs(b[1]) < .1f)
            val hh = (b[4] - b[1]) / t; val ww = (b[3] - b[0]) / t; val dd = (b[5] - b[2]) / t
            assertTrue("${p.id} ${hh} tall", hh in 2f..120f)
            // nothing wider than its picture (a pan's handle aside), nothing in front of its front edge
            assertTrue("${p.id} ${ww} wide against ${p.w}", ww <= p.w + 1.5f)
            assertTrue("${p.id} reaches ${b[5] / t} in front", b[5] <= .1f)
            assertTrue("${p.id} ${dd} deep", dd in 1f..120f)
            for (u in m.uv) assertTrue("${p.id} uv $u", u in -1e-4f..1.0001f)
            for (v in m.pos) assertTrue("${p.id} has a bad vertex", v.isFinite())
        }
    }

    @Test fun theFamiliesRoomsNeedAreCovered() {
        val fam = shippedDesigns().map { it.first.family }.toSet()
        for (f in listOf("sofa", "armchair", "bed_up", "bed_side", "toilet", "tub", "desk", "bookcase", "table", "chair", "wardrobe", "dresser", "fridge", "stove", "counter", "sink", "shop_shelf", "register", "filing"))
            assertTrue("no $f in the atlases", f in fam)
    }
}
