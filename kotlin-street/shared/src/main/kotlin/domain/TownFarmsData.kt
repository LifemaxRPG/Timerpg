package com.lifemax.rpg.feature.habits.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/*
 * The farms round the town as street.json v2 exports them (`graph.farms`: tools/town-farms.ts, from src/town/farms.ts), with
 * no drawing in it: the country roads, the farmsteads (their yards, families and the things standing in them: farm models
 * by key, each its front's middle and the way it faces), and the fields (each its rect, crops in rotation, gate and life
 * over the years). [FieldNow] says what a field looks like on a day of a year by crops.json's Midwest calendar
 * ([CropBook]); [FarmSolids] what of the farms a walker meets (the fields' fences, gates open).
 */

class FarmRoad(val id: String, val kind: String, val ax: Float, val az: Float, val bx: Float, val bz: Float, val width: Float, val verge: Float)

/** A model of assets/farm ([model]) standing with its front's middle at ([x], [z]), its front facing ([fx], [fz]). */
class FarmThing(val model: String, val x: Float, val z: Float, val fx: Float, val fz: Float, val door: Boolean, val summer: Boolean)

class Farm(
    val id: String, val family: String, val founded: Int, val members: Int, val working: Boolean,
    /** The yard (world x0, z0, x1, z1) and its farmyard's dirt (the working ground between the buildings). */
    val rect: FloatArray, val ground: FloatArray?,
    val front: String, val produce: Boolean,
    val laneA: FloatArray, val laneB: FloatArray, val laneW: Float,
    val things: List<FarmThing>,
)

class FarmFieldData(
    val id: String, val farm: String, val use: String,
    /** World x0, z0, x1, z1. */
    val rect: FloatArray,
    /** The rows run along x (else along z). */
    val rowsX: Boolean,
    val gateX: Float, val gateZ: Float, val gateSide: Char,
    val rotation: List<String>, val phase: Int,
    val from: Int, val sold: Int?, val becomes: String?,
) {
    /** It is farmed in [year] (not yet built over). */
    fun farmedIn(year: Int): Boolean = year >= from && (sold == null || year < sold)
    /** The crop harvested in [year] (src/town/farms.ts cropIn). */
    fun cropIn(year: Int): String { val n = rotation.size; return rotation[(((year - 1950 + phase) % n) + n) % n] }
}

class TownFarmsData(val roads: List<FarmRoad>, val farms: List<Farm>, val fields: List<FarmFieldData>, val standFrom: Int, val standTo: Int, val extent: FloatArray) {
    val isEmpty: Boolean get() = farms.isEmpty() && roads.isEmpty()

    companion object {
        val NONE = TownFarmsData(emptyList(), emptyList(), emptyList(), 6, 9, FloatArray(4))

        /** The farms of a street.json root (its graph.farms), or [NONE]. */
        fun parse(root: JSONObject): TownFarmsData {
            val f = root.optJSONObject("graph")?.optJSONObject("farms") ?: return NONE
            fun r4(a: JSONArray?) = FloatArray(4) { (a?.optDouble(it, 0.0) ?: 0.0).toFloat() }
            fun p2(a: JSONArray?) = FloatArray(2) { (a?.optDouble(it, 0.0) ?: 0.0).toFloat() }
            fun <T> list(a: JSONArray?, m: (JSONObject) -> T): List<T> = if (a == null) emptyList() else (0 until a.length()).map { m(a.getJSONObject(it)) }
            val roads = list(f.optJSONArray("roads")) { o ->
                val pts = o.getJSONArray("pts"); val a = p2(pts.getJSONArray(0)); val b = p2(pts.getJSONArray(pts.length() - 1))
                FarmRoad(o.getString("id"), o.optString("kind"), a[0], a[1], b[0], b[1], o.optDouble("width", 96.0).toFloat(), o.optDouble("verge", 22.0).toFloat())
            }
            val farms = list(f.optJSONArray("farms")) { o ->
                val lane = o.optJSONObject("lane")
                Farm(o.getString("id"), o.optString("family"), o.optInt("founded"), o.optJSONObject("household")?.optInt("members") ?: 0, o.optBoolean("working", true),
                    r4(o.optJSONArray("rect")), o.optJSONArray("ground")?.let { r4(it) }, o.optString("front"), o.optBoolean("produce"),
                    p2(lane?.optJSONArray("a")), p2(lane?.optJSONArray("b")), (lane?.optDouble("width", 70.0) ?: 70.0).toFloat(),
                    list(o.optJSONArray("things")) { t ->
                        val fc = p2(t.optJSONArray("face"))
                        FarmThing(t.getString("model"), t.getDouble("x").toFloat(), t.getDouble("z").toFloat(), fc[0], fc[1], t.optBoolean("door"), t.optBoolean("summer"))
                    })
            }
            val fields = list(f.optJSONArray("fields")) { o ->
                val g = o.optJSONObject("gate")
                val rot = o.optJSONArray("rotation")
                FarmFieldData(o.getString("id"), o.optString("farm"), o.optString("use"), r4(o.optJSONArray("rect")), o.optBoolean("rowsX", true),
                    (g?.optDouble("x") ?: 0.0).toFloat(), (g?.optDouble("z") ?: 0.0).toFloat(), g?.optString("side")?.firstOrNull() ?: 'N',
                    if (rot == null) listOf(o.optString("crop", "corn")) else (0 until rot.length()).map { rot.getString(it) }, o.optInt("phase"),
                    o.optInt("from", 1950), if (o.has("sold")) o.getInt("sold") else null, o.optString("becomes").ifBlank { null })
            }
            val sm = f.optJSONArray("standMonths")
            return TownFarmsData(roads, farms, fields, sm?.optInt(0, 6) ?: 6, sm?.optInt(1, 9) ?: 9, r4(f.optJSONArray("extent")))
        }

        fun parse(streetJson: String): TownFarmsData = runCatching { parse(JSONObject(streetJson)) }.getOrDefault(NONE)
    }
}

/** One crop of crops.json: its frames in the atlas and its Midwest calendar (days of the year). */
class CropInfo(
    val name: String, val frameW: Int, val variants: List<IntArray>,
    val sow: Int, val ripe: Int, val harvest: Int, val perennial: Boolean,
)

/** crops.json (assets/farm, tools/make_farm_crops.py): every crop's frames and calendar, and the soil tiles (x, y, w, h). */
class CropBook(val crops: Map<String, CropInfo>, val soils: Map<String, IntArray>, val atlasW: Int, val atlasH: Int) {
    companion object {
        fun parse(json: String): CropBook {
            val o = JSONObject(json)
            val cs = o.getJSONObject("crops")
            val crops = HashMap<String, CropInfo>()
            for (k in cs.keys()) {
                val c = cs.getJSONObject(k)
                val vs = c.getJSONArray("variants")
                val cal = c.optJSONObject("calendar")
                crops[k] = CropInfo(k, c.optInt("frameW", 32), (0 until vs.length()).map { val v = vs.getJSONObject(it); intArrayOf(v.getInt("y"), v.getInt("h")) },
                    cal?.optInt("sow", 120) ?: 120, cal?.optInt("ripe", 220) ?: 220, cal?.optInt("harvest", 260) ?: 260, cal?.optBoolean("perennial") ?: false)
            }
            val ss = o.optJSONObject("soils")
            val soils = HashMap<String, IntArray>()
            if (ss != null) for (k in ss.keys()) { val s = ss.getJSONObject(k); soils[k] = intArrayOf(s.getInt("x"), s.getInt("y"), s.getInt("w"), s.getInt("h")) }
            val size = o.optJSONArray("atlasSize")
            return CropBook(crops, soils, size?.optInt(0) ?: 224, size?.optInt(1) ?: 884)
        }
    }
}

/** How a field looks on a day: bare and frozen, ploughed, a crop growing (its stage) or ripe, stubble, a hay meadow or cut. */
enum class FieldLook { WINTER, PLOUGHED, GROWING, RIPE, STUBBLE, MEADOW, MOWN }

/**
 * A field on day [day] of [year]: what lies on it ([look]), the crop standing ([crop], its growth [stage] 0 sprout, 1 young,
 * 2 growing, 3 budding, 4 ripe), the crop the stubble is of, whether bales lie on a cut meadow, how much snow lies ([snow]).
 */
class FieldNow(val look: FieldLook, val crop: String?, val stage: Int, val stubbleOf: String?, val bales: Boolean, val snow: Float) {
    /** A key that changes only when what is drawn changes (a field is rebuilt when it does). */
    val key: String get() = "$look/$crop/$stage/$stubbleOf/$bales/${if (snow > .35f) 1 else 0}"

    companion object {
        /** Field [f] on [day] (1..366) of [year], by [book]'s calendar; [snow] how much snow lies that day (Season). */
        fun of(f: FarmFieldData, book: CropBook, year: Int, day: Int, snow: Float): FieldNow {
            if (f.use == "hay") {
                val look = when {
                    day < 95 || day > 305 -> FieldLook.WINTER
                    day in 162..178 || day in 226..238 -> FieldLook.MOWN
                    else -> FieldLook.MEADOW
                }
                val bales = day in 166..190 || day in 230..252
                return FieldNow(if (bales && look == FieldLook.MEADOW) FieldLook.MOWN else look, "hay", if (day in 95..161) 2 else 3, null, bales, snow)
            }
            val crop = f.cropIn(year)
            val c = book.crops[crop] ?: return FieldNow(FieldLook.PLOUGHED, null, 0, null, false, snow)
            val next = f.cropIn(year + 1); val nc = book.crops[next]
            fun grow(info: CropInfo, sowAbs: Int, t: Int): Int {
                // (an autumn-sown crop sprouts, then lies dormant through the winter, and grows on from the spring)
                if (info.sow > info.ripe) {
                    val d = if (t < 0) t + 365 else t
                    if (d >= info.sow || d < 95) return if (d >= info.sow && d < info.sow + 14) 0 else 1
                    val fr = (d - 95f) / max(1, info.ripe - 95)
                    return when { fr < .45f -> 2; else -> 3 }
                }
                val fr = (t - sowAbs).toFloat() / max(1, info.ripe - sowAbs)
                return when { fr < .14f -> 0; fr < .34f -> 1; fr < .7f -> 2; else -> 3 }
            }
            if (c.perennial) {
                val st = when { day < c.sow -> 0; day < c.ripe -> min(3, 1 + (day - c.sow) * 3 / max(1, c.ripe - c.sow)); day <= c.harvest -> 4; day < 300 -> 2; else -> 0 }
                return FieldNow(if (st == 4) FieldLook.RIPE else FieldLook.GROWING, crop, st, null, false, snow)
            }
            // a crop sown in the autumn before (winter wheat) is sown "before" the year began
            val sowAbs = if (c.sow > c.ripe) c.sow - 365 else c.sow
            return when {
                day < sowAbs -> FieldNow(if (day < 80) FieldLook.WINTER else FieldLook.PLOUGHED, null, 0, null, false, snow)
                day < c.ripe -> FieldNow(FieldLook.GROWING, crop, grow(c, sowAbs, day), null, false, snow)
                day <= c.harvest -> FieldNow(FieldLook.RIPE, crop, 4, null, false, snow)
                // next year's winter wheat comes up in the autumn
                nc != null && nc.sow > nc.ripe && day >= nc.sow -> FieldNow(FieldLook.GROWING, next, grow(nc, nc.sow, day), null, false, snow)
                // grain leaves its stubble until the autumn ploughing (corn's stands the winter through)
                crop == "corn" -> FieldNow(FieldLook.STUBBLE, null, 0, crop, false, snow)
                crop == "wheat" && day < 290 -> FieldNow(FieldLook.STUBBLE, null, 0, crop, false, snow)
                day > 330 -> FieldNow(FieldLook.WINTER, null, 0, null, false, snow)
                else -> FieldNow(FieldLook.PLOUGHED, null, 0, null, false, snow)
            }
        }
    }
}

/**
 * What of the farms a walker meets (for [TownCollision.setExtra]): each field's fence as thin boxes round it with its gate
 * open ([GATE] wide, the leaf swung in against the fence, not solid). Six floats a box as [TownCollision.setExtra] takes.
 */
object FarmSolids {
    const val GATE = 130f
    /** The fence's posts' half thickness. */
    const val HALF = 3f

    /** The fences of every field farmed in [year]. */
    fun fences(data: TownFarmsData, year: Int): FloatArray {
        val out = ArrayList<Float>()
        for (f in data.fields) if (f.farmedIn(year)) for (s in fenceRuns(f)) {
            val (ax, az, bx, bz) = s
            val l = kotlin.math.hypot(bx - ax, bz - az)
            if (l < 1f) continue
            out += (ax + bx) / 2f; out += (az + bz) / 2f; out += (bx - ax) / l; out += (bz - az) / l; out += l / 2f; out += HALF
        }
        return out.toFloatArray()
    }

    /** A field's fence as straight runs (x0, z0, x1, z1), the gate's gap left out: four sides, the gate's side in two. */
    fun fenceRuns(f: FarmFieldData): List<FloatArray> {
        val (x0, z0, x1, z1) = f.rect.toList()
        val h = GATE / 2f
        val gx = f.gateX.coerceIn(x0 + h + 10f, x1 - h - 10f); val gz = f.gateZ.coerceIn(z0 + h + 10f, z1 - h - 10f)
        val out = ArrayList<FloatArray>()
        fun side(c: Char, ax: Float, az: Float, bx: Float, bz: Float) {
            if (c != f.gateSide) { out += floatArrayOf(ax, az, bx, bz); return }
            if (az == bz) { out += floatArrayOf(ax, az, gx - h, az); out += floatArrayOf(gx + h, az, bx, bz) }
            else { out += floatArrayOf(ax, az, ax, gz - h); out += floatArrayOf(ax, gz + h, bx, bz) }
        }
        side('N', x0, z0, x1, z0); side('S', x0, z1, x1, z1); side('W', x0, z0, x0, z1); side('E', x1, z0, x1, z1)
        return out
    }

    /** The gate's middle and the way out of the field through it (unit). */
    fun gate(f: FarmFieldData): FloatArray {
        val (x0, z0, x1, z1) = f.rect.toList()
        val h = GATE / 2f
        val gx = f.gateX.coerceIn(x0 + h + 10f, x1 - h - 10f); val gz = f.gateZ.coerceIn(z0 + h + 10f, z1 - h - 10f)
        return when (f.gateSide) { 'N' -> floatArrayOf(gx, z0, 0f, -1f); 'S' -> floatArrayOf(gx, z1, 0f, 1f); 'W' -> floatArrayOf(x0, gz, -1f, 0f); else -> floatArrayOf(x1, gz, 1f, 0f) }
    }
}
