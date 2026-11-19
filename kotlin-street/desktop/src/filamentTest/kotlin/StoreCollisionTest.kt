import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownPlace
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreCars
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSite
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSolids
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreState
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The stores in the open world's walk (docs/STORES.md, "In the town"), on the shipped gamma: each store's site as its
 * builder makes it, read into solid shapes ([StoreSolids]) and handed to the town's [TownCollision] as TownStore does,
 * the parked cars too. The main block is solid, the doors stand clear on the outside and can be walked to from the road,
 * through the car park between the parked cars at a busy hour; the frame stands on the plot the town reserved for it.
 */
class StoreCollisionTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val json = File(dir, "street.json").readText()
    private val root = org.json.JSONObject(json)
    private val town = Town.parse(root)!!
    private val yards = TownYards.parse(root)
    private val sites = StoreSite.parse(root)
    private fun collision() = TownCollision.build(town, yards, ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
        CinemaSiteShape.parse(root), StoreLots.parse(root).map { it.sprite }.toSet())

    private class Placed(val site: StoreSite, val place: TownPlace, val setback: Float, val row: String, val at: FloatArray)
    private fun placed(st: StoreSite): Placed {
        val (r, b) = town.placement(st.sprite)!!
        val place = TownPlace.of(r, b)
        return Placed(st, place, b.setback, r.id, StoreShapes.frameOf(place, b.setback))
    }

    /** A walker's flood from ([x], [z]) over cells of [step] within [box] (world x0, z0, x1, z1): whether it reaches ([tx], [tz]) and the share of free cells reached. */
    private fun flood(c: TownCollision, x: Float, z: Float, tx: Float, tz: Float, box: FloatArray, step: Float = 4f, r: Float = 5f): Pair<Boolean, Float> {
        val cols = ((box[2] - box[0]) / step).toInt() + 1; val rows = ((box[3] - box[1]) / step).toInt() + 1
        val seen = BooleanArray(cols * rows)
        val free = BooleanArray(cols * rows) { i -> !c.blocked(box[0] + (i % cols) * step, box[1] + (i / cols) * step, r) }
        fun cell(px: Float, pz: Float) = (((pz - box[1]) / step).toInt().coerceIn(0, rows - 1)) * cols + ((px - box[0]) / step).toInt().coerceIn(0, cols - 1)
        val q = IntArray(cols * rows); var h = 0; var t = 0
        val s = cell(x, z); seen[s] = true; q[t++] = s
        while (h < t) {
            val i = q[h++]; val cc = i % cols; val rr = i / cols
            for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nc = cc + dc; val nr = rr + dr
                if (nc !in 0 until cols || nr !in 0 until rows) continue
                val j = nr * cols + nc
                if (seen[j] || !free[j]) continue
                seen[j] = true; q[t++] = j
            }
        }
        // (the target: any reached cell within a cell's reach of it)
        val hit = (0 until cols * rows).any { seen[it] && hypot(box[0] + (it % cols) * step - tx, box[1] + (it / cols) * step - tz) < step * 1.5f }
        return hit to t.toFloat() / free.count { it }.coerceAtLeast(1)
    }

    @Test fun eachStoreIsSolidWithDoorsWalkedToThroughItsCarPark() {
        assertEquals(4, sites.size)
        val c = collision()
        val base = c.size
        // the plots are open until their stores are built
        for (st in sites) assertTrue((0 until c.size).none { c.owner(it) == st.sprite })
        for (st in sites) {
            val p = placed(st)
            val info = st.at(2025, 14.5f, 6)!!
            val sm = StoreKit.buildWithSite(info, cars = false)
            val rects = StoreSolids.of(listOf(sm.model), info.lot, info.deep)
            val boxes = StoreSolids.world(rects, p.at)
            c.setExtra(StoreShapes.KEY + st.sprite, boxes, SolidKind.BUILDING, st.sprite)
            // the busiest hour of the week: Saturday afternoon
            val cars = StoreCars.build(info, sm.bays)
            c.setExtra(StoreShapes.CARS + st.sprite, StoreSolids.world(StoreSolids.of(listOf(cars), info.lot, info.deep), p.at), SolidKind.PROP, st.sprite)
            val doors = StoreShapes.doorsAt(StoreShapes.entrances(sm, info), p.at, st.sprite, p.row, c)
            c.setExtraDoors(StoreShapes.KEY + st.sprite, doors)
            println("${st.sprite} ${info.kind} ${info.state} '${info.signName}': ${rects.size / 4} solid rects, ${doors.size} doors, ${sm.bays.size} bays")
            // the main block is solid through and through
            val bl = sm.block
            fun w(x: Float, z: Float) = floatArrayOf(p.at[0] + p.at[2] * x + p.at[4] * z, p.at[1] + p.at[3] * x + p.at[5] * z)
            for ((fx, fz) in listOf(.5f to .5f, .2f to .3f, .8f to .7f)) {
                val q = w(bl[0] + (bl[2] - bl[0]) * fx, bl[1] + (bl[3] - bl[1]) * fz)
                assertTrue("${st.sprite}: inside its block at ${q.toList()}", c.blocked(q[0], q[1], 1f))
            }
            if (info.state == StoreState.CLOSED) { assertTrue(doors.isEmpty()); continue }
            assertTrue(doors.isNotEmpty())
            // the plot's front middle on the pavement, and the whole plot with a margin, for the flood
            val pave = town.row(p.row).let { r -> r.pointAt(town.placement(st.sprite)!!.second.mid, 7f) }
            val corners = listOf(w(-info.lot / 2f - 60f, 120f), w(info.lot / 2f + 60f, 120f), w(-info.lot / 2f - 60f, -info.deep - 60f), w(info.lot / 2f + 60f, -info.deep - 60f))
            val box = floatArrayOf(corners.minOf { it[0] }, corners.minOf { it[1] }, corners.maxOf { it[0] }, corners.maxOf { it[1] })
            for (d in doors) {
                assertFalse("${st.sprite}: its door's spot is clear", c.blocked(d.x, d.z, 5f))
                assertTrue("${st.sprite}: the door is on the block's front", hypot(d.x - d.wallX, d.z - d.wallZ) < 120f)
                val (hit, share) = flood(c, pave.x, pave.z, d.x, d.z, box)
                println("  door at (%.0f, %.0f): reached from the road %s, %.0f%% of the plot's free ground reached".format(d.x, d.z, hit, share * 100f))
                assertTrue("${st.sprite}: its door walked to from the road", hit)
                assertTrue("${st.sprite}: most of the plot walkable from the road (${share})", share > .6f)
                // the walk's door finder: standing at the spot facing in
                assertTrue(TownDoors(town, yards, c, CinemaSiteShape.parse(root)).at(d.x, d.z, -d.outX, -d.outZ)?.sprite == st.sprite)
            }
        }
        assertTrue("the stores added shapes", c.extraSize > 100)
        assertTrue(c.size == base)
        // the cars are solid: their shapes are in the map and a walker cannot stand in the middle of one
        val carShapes = (0 until c.extraSize).count { c.kind(base + it) == SolidKind.PROP }
        assertTrue("parked cars in the map: $carShapes", carShapes > 20)
        // removing a store's shapes takes them out
        for (st in sites) { c.setExtra(StoreShapes.KEY + st.sprite, null); c.setExtra(StoreShapes.CARS + st.sprite, null); c.setExtraDoors(StoreShapes.KEY + st.sprite, null) }
        assertEquals(0, c.extraSize); assertEquals(0, c.extraDoors.size)
    }

    @Test fun theSuperstoresFrameStandsOnItsReservedPlot() {
        val st = sites.first { it.site?.site == "superstore" }
        val p = placed(st)
        val lm = town.landmarks.first { it.sprite == st.sprite }
        val rect = lm.rect
        val info = st.at(2025, 13f, 3)!!
        // the plot's four corners in the world: inside the landmark's reserved rectangle, as deep as it, centred along it
        // (the plot's width along the street is at the art's scale, the rectangle's at the position scale: it is wider)
        val cs = listOf(-info.lot / 2f to 0f, info.lot / 2f to 0f, -info.lot / 2f to -info.deep, info.lot / 2f to -info.deep)
            .map { (x, z) -> floatArrayOf(p.at[0] + p.at[2] * x + p.at[4] * z, p.at[1] + p.at[3] * x + p.at[5] * z) }
        for (q in cs) assertTrue("corner ${q.toList()} in ${rect.toList()}", q[0] >= rect[0] - 1f && q[0] <= rect[2] + 1f && q[1] >= rect[1] - 1f && q[1] <= rect[3] + 1f)
        val depthWorld = cs.maxOf { it[1] } - cs.minOf { it[1] }
        assertEquals(rect[3] - rect[1], depthWorld, 2f)
        val mid = (cs.minOf { it[0] } + cs.maxOf { it[0] }) / 2f
        assertEquals((rect[0] + rect[2]) / 2f, mid, 2f)
        assertTrue(abs(info.lot - st.site!!.width) < 1f)
    }
}
