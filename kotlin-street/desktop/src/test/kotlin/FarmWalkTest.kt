import com.lifemax.rpg.feature.habits.domain.CropBook
import com.lifemax.rpg.feature.habits.domain.FarmFieldData
import com.lifemax.rpg.feature.habits.domain.FarmSolids
import com.lifemax.rpg.feature.habits.domain.FieldLook
import com.lifemax.rpg.feature.habits.domain.FieldNow
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownFarmsData
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The farms round the shipped gamma town (street.json's graph.farms, assets/farm) for the open world's walk, and the fields
 * through the year: every field's fence is solid and its gate open (a walker goes in through it and is inside the field,
 * walking its crop), the farm models stand solid where drawn, the farmhouses' and barns' doors stand clear, the country
 * roads and lanes are walkable; and what a field shows on a day follows crops.json's Midwest calendar.
 */
class FarmWalkTest {
    companion object {
        private val dir = File("../app/src/main/assets/town/gamma")
        private val farmDir = File("../app/src/main/assets/farm")
        private val root by lazy { JSONObject(File(dir, "street.json").readText()) }
        val town: Town by lazy { Town.parse(root)!! }
        val farms: TownFarmsData by lazy { TownFarmsData.parse(root) }
        val shapes: Map<String, ModelShape> by lazy { ModelShape.parseAll(File(farmDir, "buildings/models.json").readText()) }
        val book: CropBook by lazy { CropBook.parse(File(farmDir, "crops.json").readText()) }
        val map: TownCollision by lazy {
            TownCollision.build(town, TownYards.parse(root), ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
                com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet()).also { m ->
                m.setExtra("farms:fences", FarmSolids.fences(farms, town.year), SolidKind.FENCE, "farms")
                m.setExtra("farms:buildings", FarmSolids.models(farms, shapes), SolidKind.BUILDING, "farms")
            }
        }
        const val R = OpenWalk.RADIUS
    }

    @Test fun theExportHasFarms() {
        assertTrue(farms.farms.size >= 12)
        assertTrue(farms.fields.count { it.farmedIn(town.year) } >= farms.farms.size)
        assertTrue(farms.roads.size >= 6)
        // every model a farm stands is one assets/farm builds
        for (f in farms.farms) for (t in f.things) assertTrue("${t.model} has no model", t.model in shapes)
        // the world's edge (the collision's) reaches past every farm
        for (f in farms.farms) assertTrue(f.rect[0] > map.minX && f.rect[1] > map.minZ && f.rect[2] < map.maxX && f.rect[3] < map.maxZ)
        println("farms: ${farms.farms.size} farms, ${farms.fields.size} fields (${farms.fields.count { it.farmedIn(town.year) }} farmed in ${town.year}), " +
            "${farms.roads.size} roads; collision ${map.size} + ${map.extraSize} shapes")
    }

    @Test fun fencesAreSolidAndGatesOpen() {
        var through = 0
        val out = FloatArray(2)
        for (f in farms.fields.filter { it.farmedIn(town.year) }) {
            // the middle of every fence run is solid
            for (run in FarmSolids.fenceRuns(f)) {
                val mx = (run[0] + run[2]) / 2f; val mz = (run[1] + run[3]) / 2f
                if (hypot(run[2] - run[0], run[3] - run[1]) > 20f) assertTrue("${f.id} fence at $mx,$mz", map.blocked(mx, mz, R))
            }
            // in through the gate: from outside it, straight in, to well inside the field
            val g = FarmSolids.gate(f)
            var x = g[0] + g[2] * 40f; var z = g[1] + g[3] * 40f
            if (map.blocked(x, z, R)) continue
            repeat(120) { map.move(x, z, -g[2] * 2f, -g[3] * 2f, R, out); x = out[0]; z = out[1] }
            val inside = x > f.rect[0] + 30f && x < f.rect[2] - 30f && z > f.rect[1] + 30f && z < f.rect[3] - 30f
            assertTrue("${f.id}: not in through its gate (at $x,$z)", inside)
            // and the crop is walked through: the middle of the field is clear
            assertFalse("${f.id} middle", map.blocked((f.rect[0] + f.rect[2]) / 2f, (f.rect[1] + f.rect[3]) / 2f, R))
            through++
        }
        println("walked in through $through gates")
        assertTrue(through >= farms.fields.count { it.farmedIn(town.year) } * 3 / 4)
    }

    @Test fun buildingsSolidDoorsClearRoadsWalkable() {
        for (f in farms.farms) {
            for (t in f.things.filter { it.model.startsWith("fm_barn") || it.model.startsWith("fm_house") }) {
                // a step behind the front's middle is inside the building
                assertTrue("${f.id} ${t.model}", map.blocked(t.x - t.fx * 120f, t.z - t.fz * 120f, R))
            }
            val lm = floatArrayOf((f.laneA[0] + f.laneB[0]) / 2f, (f.laneA[1] + f.laneB[1]) / 2f)
            assertFalse("${f.id} lane", map.blocked(lm[0], lm[1], R))
        }
        for ((id, d) in FarmSolids.doors(farms, shapes)) {
            var ok = false
            for (k in 0 until 40) if (!map.blocked(d[0] + d[2] * k * 4f, d[1] + d[3] * k * 4f, R)) { ok = true; break }
            assertTrue("door $id", ok)
        }
        for (r in farms.roads) for (k in 1..9) {
            val x = r.ax + (r.bx - r.ax) * k / 10f; val z = r.az + (r.bz - r.az) * k / 10f
            // (a bridge's parapets are beside the deck; the river under a road is no obstacle on the road)
            assertTrue("${r.id} at $x,$z", !map.blocked(x, z, R) || town.water.any { p -> x > p.minOf { it.x } && x < p.maxOf { it.x } && z > p.minOf { it.z } && z < p.maxOf { it.z } })
        }
    }

    private fun field(rot: List<String>, use: String = "grain") = FarmFieldData("t", "f", use, floatArrayOf(0f, 0f, 100f, 100f), true, 50f, 0f, 'N', rot, 0, 1950, null, null)

    @Test fun fieldsFollowTheMidwestCalendar() {
        val corn = field(listOf("corn"))
        fun at(f: FarmFieldData, day: Int, year: Int = 2025) = FieldNow.of(f, book, year, day, 0f)
        assertEquals(FieldLook.WINTER, at(corn, 30).look)
        assertEquals(FieldLook.PLOUGHED, at(corn, 110).look)
        assertEquals(FieldLook.GROWING, at(corn, 140).look); assertEquals(0, at(corn, 125).stage)
        assertEquals(FieldLook.GROWING, at(corn, 200).look); assertTrue(at(corn, 200).stage >= 2)
        assertEquals(FieldLook.RIPE, at(corn, 260).look)
        assertEquals(FieldLook.STUBBLE, at(corn, 300).look)
        // winter wheat: sown in October, young under the snow, ripe late June, cut in July; its stubble after
        val wheat = field(listOf("wheat"))
        assertEquals(1, at(wheat, 20).stage)
        assertEquals(FieldLook.RIPE, at(wheat, 180).look)
        assertEquals(FieldLook.STUBBLE, at(wheat, 200).look)
        // corn then wheat: next year's wheat comes up in the autumn over this year's corn
        val rot = field(listOf("corn", "wheat"))
        val y = (1950..2030).first { rot.cropIn(it) == "corn" }
        assertEquals("wheat", at(rot, 300, y).crop)
        // hay: a meadow in May, mown with bales in late June, brown in winter
        val hay = field(listOf("hay"), "hay")
        assertEquals(FieldLook.MEADOW, at(hay, 140).look)
        assertTrue(at(hay, 172).bales)
        assertEquals(FieldLook.WINTER, at(hay, 20).look)
        // every Midwest crop has a calendar
        for (c in listOf("corn", "wheat", "pumpkin", "tomato", "cabbage", "carrot", "onion", "watermelon", "zucchini", "pepper", "strawberry", "cauliflower", "turnip", "radish"))
            assertTrue(c, book.crops[c]!!.harvest > 0)
    }
}
