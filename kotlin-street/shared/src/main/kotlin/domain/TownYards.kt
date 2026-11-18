package com.lifemax.rpg.feature.habits.domain

import org.json.JSONArray
import org.json.JSONObject

/*
 * The town's yards and back lanes as street.json v2 carries them (graph.yards, graph.lanes: tools/town-yards.ts), and
 * where each building of a row stands in the world ([TownPlace]). No drawing here: the Filament town (scene3d/FilamentTown)
 * draws from these, and a yard kit plugs its builders in through scene3d's YardBuilder.
 */

/** One thing in a yard: [kind] (lawn, driveway, shed, hedge...), its world rectangle, the way it faces, its layer and zone. */
class TownYardItem(
    val kind: String,
    /** World rectangle: x0, z0, x1, z1. */
    val rect: FloatArray,
    /** The way its front faces (unit, world x and z). */
    val faceX: Float,
    val faceZ: Float,
    val variant: Int,
    /** ground (lawn, meadow, gravel) under surface (paths, drives, patios) under object and edge (fences, hedges, gates). */
    val layer: String,
    /** front, back, side or street (a kerb cut on the pavement). */
    val zone: String,
    /** The surface an object stands on (its kind), if any. */
    val on: String?,
)

/** A household's summary, as the yard was laid out for it. */
class TownHousehold(val residents: Int, val children: Int, val elders: Int, val gardener: Boolean, val wealth: Int, val pool: Boolean, val drive: String)

/** One lot's yard: its parcel (the lot widened into the free ground beside it), its house's footprint and its front and back. */
class TownYard(
    val lot: String,
    val sprite: String,
    val row: String,
    val neighbourhood: String,
    val style: String,
    val kind: String,
    val parcel: FloatArray,
    val house: FloatArray,
    val front: FloatArray?,
    val back: FloatArray?,
    val household: TownHousehold?,
    val items: List<TownYardItem>,
)

/** A back lane reaching a street, or a verge (the ground between groups no yard took). */
class TownLane(val id: String, val kind: String, val rect: FloatArray, val axis: Char, val reaches: Int, val items: List<TownYardItem>)

class TownYards(val yards: List<TownYard>, val lanes: List<TownLane>) {
    private val bySprite = yards.associateBy { it.sprite }

    /** The yard of the building drawn as [sprite], if it has one. */
    fun of(sprite: String): TownYard? = bySprite[sprite]

    companion object {
        /** The yards in a street.json (empty for a file without them). */
        fun parse(streetJson: String): TownYards = parse(JSONObject(streetJson))

        fun parse(root: JSONObject): TownYards {
            val g = root.optJSONObject("graph") ?: return TownYards(emptyList(), emptyList())
            val yards = g.optJSONArray("yards")?.objects()?.map { y ->
                val h = y.optJSONObject("household")
                TownYard(
                    y.getString("lot"), y.optString("sprite"), y.optString("row"), y.optString("neighbourhood"), y.optString("style"),
                    y.optString("kind"), y.getJSONArray("parcel").floats(), y.getJSONArray("house").floats(),
                    y.optJSONArray("front")?.floats(), y.optJSONArray("back")?.floats(),
                    h?.let { TownHousehold(it.optInt("residents"), it.optInt("children"), it.optInt("elders"), it.optBoolean("gardener"), it.optInt("wealth"), it.optBoolean("pool"), it.optString("drive")) },
                    items(y.optJSONArray("items")),
                )
            } ?: emptyList()
            val lanes = g.optJSONArray("lanes")?.objects()?.map { l ->
                TownLane(l.getString("id"), l.optString("kind"), l.getJSONArray("rect").floats(), l.optString("axis", "x").firstOrNull() ?: 'x', l.optInt("reaches"), items(l.optJSONArray("items")))
            } ?: emptyList()
            return TownYards(yards, lanes)
        }

        private fun items(a: JSONArray?): List<TownYardItem> = a?.objects()?.map { i ->
            val f = i.optJSONArray("face")
            TownYardItem(
                i.getString("kind"), i.getJSONArray("rect").floats(), f?.optDouble(0, 0.0)?.toFloat() ?: 0f, f?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                i.optInt("variant"), i.optString("layer"), i.optString("zone"), i.optString("on").ifEmpty { null },
            )
        } ?: emptyList()

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.floats(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }
    }
}

/**
 * Where a building of a row stands in the world: as [com.lifemax.rpg.feature.habits.presentation.scene.TownWalk]'s
 * footprints and the yards' `house` stand it, a rigid box square to the row at its front's middle. Its own frame: the
 * origin on the building line at that middle ([ox], [oz]), local z into the lot ([nx], [nz]: the row's normal there), local
 * x the other way from the art's left-to-right as seen from the street (so the frame is a proper rotation of the world:
 * x east, y up, z south). A building is built in that frame facing local -z with its picture running along local -x.
 */
class TownPlace(val ox: Float, val oz: Float, val nx: Float, val nz: Float) {
    /** The art's left-to-right as seen from the street (world): local -x. */
    val rx: Float get() = -nz
    val rz: Float get() = nx

    /** Local ([lx], [lz]) to world (x, z). */
    fun world(lx: Float, lz: Float): TownPoint = TownPoint(ox + nz * lx + nx * lz, oz - nx * lx + nz * lz)

    /** The world-from-local matrix (4x4, column-major, Filament's order), lifted [y] off the ground. */
    fun matrix(out: FloatArray = FloatArray(16), y: Float = 0f): FloatArray {
        out.fill(0f)
        out[0] = nz; out[2] = -nx
        out[5] = 1f
        out[8] = nx; out[10] = nz
        out[12] = ox; out[13] = y; out[14] = oz; out[15] = 1f
        return out
    }

    companion object {
        fun of(row: Row, b: Placement): TownPlace {
            val m = row.pointAt(b.mid, 0f)
            val n = row.normalAt(b.mid)
            return TownPlace(m.x, m.z, n.x, n.z)
        }
    }
}
