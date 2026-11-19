import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.domain.PropRules
import com.lifemax.rpg.feature.habits.domain.PropSpot
import com.lifemax.rpg.feature.habits.domain.PropStrip
import com.lifemax.rpg.feature.habits.domain.Town
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The street props' placement rules on town gamma (the fixture TownMapTest reads). */
class StreetPropRulesTest {
    private val town: Town = Town.parse(StreetPropRulesTest::class.java.getResource("/town-gamma.json")!!.readText())!!
    private val all: Map<String, List<PropSpot>> = town.rows.associate { it.id to PropRules.forRow(town, it) }

    private fun overlap(a: FloatArray, b: FloatArray, gap: Float = 0f) =
        a[0] < b[1] + gap && a[1] > b[0] - gap && a[2] < b[3] + gap && a[3] > b[2] - gap

    @Test fun deterministic() {
        val again = town.rows.associate { it.id to PropRules.forRow(town, it) }
        assertEquals(all, again)
        // and a fresh parse of the town gives the same street
        val other = Town.parse(StreetPropRulesTest::class.java.getResource("/town-gamma.json")!!.readText())!!
        assertEquals(all["3R"], PropRules.forRow(other, other.row("3R")))
        // the hash is stable (the game's rand(seed, ...keys): the same keys, the same number, forever)
        assertEquals(PropRules.rand("gamma", "3R", 7), PropRules.rand("gamma", "3R", 7), 0f)
        assertTrue(PropRules.rand("gamma", "3R", 7) != PropRules.rand("gamma", "3R", 8))
    }

    @Test fun noOverlapsNoDoorwaysWalkClear() {
        for (row in town.rows) {
            val spots = all.getValue(row.id)
            val fp = spots.map { it.footprint() }
            for (i in fp.indices) for (j in i + 1 until fp.size)
                assertTrue("${row.id}: ${spots[i]} overlaps ${spots[j]}", !overlap(fp[i], fp[j]))
            for ((s, f) in spots.zip(fp)) {
                // never in a doorway: the door's width across the whole pavement
                for (b in row.buildings) assertTrue("${row.id}: $s in ${b.sprite}'s door", f[1] <= b.mid - PropRules.DOOR_HALF || f[0] >= b.mid + PropRules.DOOR_HALF)
                // nor on the lamps', trees' or benches' spots
                for (fu in row.furniture) assertTrue("${row.id}: $s on furniture at ${fu.x}", f[1] <= fu.x - PropRules.FURNITURE_HALF || f[0] >= fu.x + PropRules.FURNITURE_HALF)
                // the walking lanes stay clear: at the kerb or against the wall, never between
                when (s.strip) {
                    PropStrip.KERB -> { assertTrue("${row.id}: $s into the walk", f[2] >= PropRules.WALK_EDGE - 1e-3f); assertTrue("${row.id}: $s over the kerb", f[3] <= Crossroads.PAVE) }
                    PropStrip.WALL -> assertTrue("${row.id}: $s too far out from the wall", f[3] <= PropRules.WALL_EDGE + 1e-3f && f[2] >= 0f)
                }
                // on the row's pavement (not in a crossing road)
                assertTrue("${row.id}: $s off the row", f[0] >= 0f && f[1] <= row.length)
            }
        }
    }

    @Test fun whatGoesWhere() {
        // every home with a mailbox at its front, near its own door
        // (a home whose front runs past its row's corner, as 45L's does on a row 267 long, has no pavement of its own)
        for (row in town.rows) for (b in row.buildings.filter { it.kind == PlacementKind.HOME && it.x0 >= 0f && it.x1 <= row.length - 60f && it.x0 >= 60f }) {
            val mb = all.getValue(row.id).filter { it.kind == PropKind.HOME_MAILBOX }
            assertTrue("${row.id} ${b.sprite} has no mailbox", mb.any { abs(it.x - b.mid) < 60f })
        }
        // the post office has its blue boxes; every row with three or more shops has a blue box
        val post = town.landmarks.first { it.kind == "post" }
        assertTrue(all.getValue(post.row).count { it.kind == PropKind.MAILBOX } >= 2)
        for (row in town.rows.filter { r -> r.buildings.count { it.kind == PlacementKind.SHOP } >= 3 })
            assertTrue("${row.id}: no blue mailbox by its shops", all.getValue(row.id).any { it.kind == PropKind.MAILBOX })
        // civic buildings get bollards; homes' rows never
        val civic = town.landmarks.filter { it.kind in setOf("school", "fire", "police", "hospital", "hotel") }
        assertTrue(civic.isNotEmpty())
        for (l in civic) assertTrue("${l.kind} has no bollards", all.getValue(l.row).count { it.kind == PropKind.BOLLARD } >= 3)
        for (row in town.rows.filter { r -> r.buildings.isNotEmpty() && r.buildings.all { it.kind == PlacementKind.HOME } })
            assertTrue(all.getValue(row.id).none { it.kind == PropKind.BOLLARD || it.kind == PropKind.PARKING_METER })
        // signs at the corners: within the corner's square of a mouth end; name signs carry both names
        for (row in town.rows) for (s in all.getValue(row.id).filter { it.kind == PropKind.STREET_SIGN || it.kind == PropKind.STOP_SIGN || it.kind == PropKind.PED_SIGN }) {
            val nearStart = s.x < 89f + 1f && row.start.mouth
            val nearEnd = s.x > row.length - 89f - 1f && row.end.mouth
            assertTrue("${row.id}: $s not at a corner", nearStart || nearEnd)
            if (s.kind == PropKind.STREET_SIGN) assertTrue(s.text.isNotEmpty() && s.text[0] == PropRules.signText(town.road(row.road).name))
        }
        // the main street stops for nobody; most minor roads stop somewhere
        val main = town.roads.filter { it.main }.map { it.id }.toSet()
        assertTrue(town.rows.filter { it.road in main }.none { r -> all.getValue(r.id).any { it.kind == PropKind.STOP_SIGN } })
        assertTrue(all.values.sumOf { l -> l.count { it.kind == PropKind.STOP_SIGN } } >= 10)
        // one name sign at each corner: no corner gets two from the two rows meeting there
        assertTrue(all.values.sumOf { l -> l.count { it.kind == PropKind.STREET_SIGN } } >= 20)
        // cones are rare
        assertTrue(all.values.sumOf { l -> l.count { it.kind == PropKind.CONE } } <= all.values.sumOf { it.size } / 30)
    }

    @Test fun densityPerNeighbourhoodIsSane() {
        // props per 100 units of building frontage, by neighbourhood (corner signs left out)
        val count = HashMap<String, Int>(); val front = HashMap<String, Float>()
        // (the stores' fronts are kept clear on purpose: their porches, glass and car parks' edges are their own)
        val stores = town.landmarks.filter { it.kind in com.lifemax.rpg.feature.habits.domain.StoreLots.KINDS }.map { it.sprite }.toSet()
        for (row in town.rows) {
            for (b in row.buildings) if (b.sprite !in stores) front.merge(b.neighbourhood, b.width, Float::plus)
            for (s in all.getValue(row.id)) {
                if (s.kind in setOf(PropKind.STREET_SIGN, PropKind.STOP_SIGN, PropKind.PED_SIGN)) continue
                val b = row.buildingAt(s.x) ?: continue
                if (b.sprite in stores) continue
                if (s.x < b.x0 - 40f || s.x > b.x1 + 40f) continue
                count.merge(b.neighbourhood, 1, Int::plus)
            }
        }
        val per = front.mapValues { (k, w) -> (count[k] ?: 0) * 100f / w }
        println("props per 100 units of frontage: " + per.entries.joinToString { "%s %.2f".format(it.key, it.value) })
        for ((k, d) in per) assertTrue("$k: %.2f".format(d), d in .2f..4f)
        // the old town's shops are busier than the woodland's homes
        assertTrue(per.getValue("old-town") > per.getValue("woodside"))
        val total = all.values.sumOf { it.size }
        println("gamma: $total props on ${town.rows.size} rows: " + all.values.flatten().groupingBy { it.kind }.eachCount().entries.sortedBy { it.key.ordinal }.joinToString { "${it.key.name.lowercase()} ${it.value}" })
        assertTrue(total in 200..2000)
    }
}
