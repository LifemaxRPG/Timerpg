import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.TalkRange
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene.locate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The walkable town as the app ships and plays it (TOWN.md T10): the assets in app/src/main/assets/town/gamma/ (street.json
 * v2 with its yards, the buildings' art, dialogue.json and interiors.json of the same town and year), loaded, the player
 * spawned on the civic avenue, and a short scripted walk through [TownPlay] (the stage's game loop calls the same step):
 * along the row, round the corner at its end, over the road; the events crash reports keep; the people drawn on the live
 * rows; who is near enough to talk to.
 */
class TownPlayTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val json by lazy { File(dir, "street.json").readText() }
    private val town: Town by lazy { Town.parse(json)!! }
    private val dt = 1f / 60f
    private val events = ArrayList<String>()

    @Before fun setUp() {
        GameState.moveX = 0f; GameState.moveLane = 0f
        CornerWalk.reset(); CornerWalk.blocked = false
        events.clear()
        TownPlay.onEvent = { events += it }
    }

    @After fun tearDown() {
        TownPlay.stop()
        TownPlay.onEvent = null
        TownPlay.keepers = emptyMap()
        GameState.moveX = 0f; GameState.moveLane = 0f
    }

    private fun hold(x: Float, lane: Float, seconds: Float, until: () -> Boolean = { false }): Int {
        GameState.moveX = x; GameState.moveLane = lane
        var n = 0
        while (n * dt < seconds && !until()) { TownPlay.step(dt); n++ }
        GameState.moveX = 0f; GameState.moveLane = 0f
        TownPlay.step(dt)
        return n
    }

    private fun keepers(): Map<String, Int> {
        val a = JSONObject(File(dir, "dialogue.json").readText()).getJSONArray("npcs")
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); m.putIfAbsent(o.getString("building"), o.getString("id").hashCode() and 0x7fffffff) }
        return m
    }

    @Test fun shippedAssetsAreOneTown() {
        val root = JSONObject(json)
        assertEquals(2, root.getInt("version"))
        val seed = root.getString("town"); val year = root.getInt("year")
        assertEquals("gamma", seed)
        assertEquals(seed, town.seed)
        assertTrue("yards shipped", TownYards.parse(json).yards.isNotEmpty())
        val sprites = town.rows.flatMap { r -> r.buildings.map { it.sprite } }.toSet()
        assertEquals(113, sprites.size)
        // the movie theater (graph.cinema) stands as one of them, drawn by code (CinemaKit): no art files, a bill to show
        val cinema = root.getJSONObject("graph").getJSONObject("cinema")
        val cinemaSprite = root.getJSONObject("graph").getJSONArray("landmarks").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .first { it.getString("id") == cinema.getString("landmark") }.getString("sprite")
        assertTrue("cinema $cinemaSprite is a building", cinemaSprite in sprites)
        assertTrue("the cinema has a bill", cinema.getJSONArray("nowShowing").length() > 0 && cinema.getJSONArray("marquee").length() > 1)
        // the dialogue and the rooms are of the same town and year (DialogueEngine refuses a mismatch), at its buildings
        val d = JSONObject(File(dir, "dialogue.json").readText())
        assertEquals(seed, d.getString("town")); assertEquals(year, d.getInt("year")); assertEquals(2, d.getInt("version"))
        val npcs = d.getJSONArray("npcs")
        assertTrue(npcs.length() > 100)
        val rowIds = town.rows.map { it.id }.toSet()
        for (i in 0 until npcs.length()) {
            val o = npcs.getJSONObject(i)
            assertTrue("${o.getString("building")} is in the town", o.getString("building") in sprites)
            assertTrue("${o.opt("row")} is a row", o.opt("row").toString() in rowIds)
        }
        val rooms = JSONObject(File(dir, "interiors.json").readText())
        assertEquals(seed, rooms.getString("town")); assertEquals(year, rooms.getInt("year"))
        val keys = rooms.getJSONObject("rooms").keys().asSequence().toList()
        assertTrue(keys.size >= 90)
        for (k in keys) assertTrue("room $k is a building", k in sprites)
        // every building has its art, and the ground's pictures are shipped
        val files = File(dir, "buildings").list()!!.toSet()
        // (the cinema and the stores are built by code: CinemaKit, StoreKit)
        val stores = com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet()
        assertEquals(4, stores.size)
        for (sp in stores) assertTrue("store $sp has no art", files.none { it.startsWith("${sp}_") || it.startsWith("$sp.") })
        for (sp in sprites - cinemaSprite - stores) assertTrue("art of $sp", files.any { it.startsWith("${sp}_") || it.startsWith("$sp.") })
        for (p in listOf("asphalt/asphalt_24.png", "sidewalk/sidewalk_25.png", "sidewalk/sidewalk_27.png", "grass/grass_9.png", "tree/tree_1.png"))
            assertTrue("art $p", File("../app/src/main/assets/town/art/$p").exists())
    }

    @Test fun spawnsOnTheCivicAvenueAndWalks() {
        val w = TownPlay.start(town)
        assertTrue(TownPlay.active)
        assertEquals(town.spawn.row, w.row.id)
        assertEquals(town.civicName ?: "Civic Avenue", TownPlay.streetName())
        assertNotNull(TownPlay.hoodName())
        assertTrue(events.first().startsWith("town gamma 2025: spawned on ${town.spawn.row}"))
        // along the row
        val x0 = w.x
        hold(.7f, 0f, 3f)
        assertTrue("walked on (${w.x} from $x0)", w.x > x0 + 100f)
        assertEquals(town.spawn.row, w.row.id)
        // to its end, where a turn leads into the side street: up walks round the corner
        val r0 = w.row
        assertNotNull("the spawn row turns in at its end", r0.end.turnIn)
        hold(1f, 0f, 60f) { w.x > r0.length - 60f }
        assertTrue(GameState.atCorner)
        hold(0f, -1f, 1f) { w.transit != null }
        assertEquals(TransitionKind.TURN_IN, w.transit?.kind)
        hold(0f, -1f, 30f) { w.transit == null }
        assertNull(w.transit)
        assertEquals(r0.end.turnIn, w.row.id)
        assertTrue(events.any { it.startsWith("turn start: ${r0.id} -> ${w.row.id}") })
        assertTrue(events.any { it.startsWith("turn end") })
        assertTrue(events.any { it.startsWith("row ${w.row.id}: ") })
        assertEquals(1, TownPlay.rowChanges)
        // to the kerb and over the road (a crossing, a row change)
        val r1 = w.row
        hold(0f, 0f, .2f)
        hold(0f, 1f, 4f)
        hold(0f, 0f, .2f)
        hold(0f, 1f, 1f) { w.transit != null }
        assertEquals(TransitionKind.CROSS, w.transit?.kind)
        hold(0f, 1f, 30f) { w.transit == null }
        assertEquals(town.facing(r1)?.id, w.row.id)
        assertTrue(events.any { it.startsWith("cross start") })
        assertEquals(2, TownPlay.rowChanges)
    }

    @Test fun peopleOnTheLiveRowsAndWhoHears() {
        TownPlay.keepers = keepers()
        val w = TownPlay.start(town)
        // a row with someone to talk to: stand by their door
        val (r, b) = town.rows.firstNotNullOf { r -> r.buildings.firstOrNull { it.sprite in TownPlay.keepers }?.let { r to it } }
        w.placeOn(r, b.mid + TalkRange.KEEPER_DX, 6f)
        TownPlay.step(dt)
        val n = TownPlay.people(600f)
        val figs = TownPlay.figures.take(n)
        assertEquals("one player", 1, figs.count { it.me })
        val live = w.liveRows()
        val expectedKeepers = live.sumOf { row -> row.buildings.count { it.sprite in TownPlay.keepers } }
        assertEquals(expectedKeepers, figs.count { it.keeper != null })
        assertTrue("passers at 10 minutes", figs.count { !it.me && it.keeper == null } > 0)
        for (f in figs) assertFalse(f.x.isNaN() || f.z.isNaN())
        // they stand on the live rows' pavements (within a road's width of one of them)
        for (f in figs) assertTrue(live.any { row -> val (lx, _) = row.locate(f.x, f.z); val p = row.pointAt(lx.coerceIn(0f, row.length), 7f); kotlin.math.hypot(p.x - f.x, p.z - f.z) < 120f })
        assertEquals(b.sprite, TownPlay.nearestKeeper())
        assertTrue(TownPlay.fromPlayer(b.sprite) < TalkRange.distance)
        assertEquals(b.sprite, TownPlay.frontSprite())
        // the projector (the renderer's camera) gives the heads' screen spots; without it, none
        assertNull(TownPlay.keeperHead(b.sprite))
        TownPlay.projector = { x, y, z -> androidx.compose.ui.geometry.Offset(x, z - y) }
        assertNotNull(TownPlay.keeperHead(b.sprite))
        assertNotNull(TownPlay.playerHead())
        // far along the row nobody hears
        w.placeOn(r, if (b.mid > r.length / 2f) 30f else r.length - 30f, 6f)
        TownPlay.step(dt)
        if (r.buildings.none { it.sprite in TownPlay.keepers && kotlin.math.abs(it.mid + TalkRange.KEEPER_DX - w.x) < 120f }) assertNull(TownPlay.nearestKeeper())
    }

    /**
     * Frames come at the display's rate only while something moves or the renderer is still building: standing still
     * (the town built) the stage's own 30 Hz clock is enough; and behind the loading screen the keys move nobody.
     */
    @Test fun redrawsOnlyWhenSomethingMovesAndStaysPutWhileLoading() {
        val w = TownPlay.start(town)
        TownPlay.renderBusy = false
        hold(0f, 0f, .2f)
        TownPlay.step(dt); TownPlay.step(dt)
        assertFalse("standing still, the town built: no frame every display refresh", GameState.inMotion)
        TownPlay.renderBusy = true
        TownPlay.step(dt)
        assertTrue("still building: frames keep coming", GameState.inMotion)
        TownPlay.renderBusy = false
        GameState.moveX = .7f
        TownPlay.step(dt)
        assertTrue("walking: a frame every refresh", GameState.inMotion)
        GameState.moveX = 0f
        // (come to a stop first)
        hold(0f, 0f, 1f)
        // the first view being built: the keys push nobody
        val x0 = w.x; val wx0 = w.wx; val wz0 = w.wz
        TownPlay.frozen = true
        try {
            GameState.moveX = 1f
            repeat(60) { TownPlay.step(dt) }
            assertEquals(wx0, w.wx, .01f); assertEquals(wz0, w.wz, .01f)
        } finally { TownPlay.frozen = false; GameState.moveX = 0f }
        hold(.7f, 0f, .5f)
        assertTrue("walks again once the view is ready", kotlin.math.abs(w.x - x0) > 5f)
    }

    /** The live rows are kept between frames (the renderer asks every frame) and follow the row and the transit. */
    @Test fun liveRowsAreKeptUntilTheRowChanges() {
        val w = TownPlay.start(town)
        val a = w.liveRows()
        assertTrue(a === w.liveRows())
        val other = town.rows.first { it.id != w.row.id && it !in a }
        w.placeOn(other, 30f, 6f)
        val b = w.liveRows()
        assertTrue(other in b)
        assertFalse(a === b)
    }

    /**
     * The open world through [TownPlay] as the app plays it: with the town's collision the open walk is played (the
     * default view), the stick walks the way the camera looks and never turns it, the HUD's street and the dialogue's
     * nearest building follow the derived row, "Talk to" finds the nearest person from any side, and the view switch hands
     * the player over to the street view's walk and back where they stand.
     */
    @Test fun openWorldPlaysTheOpenWalkAndSwitchesView() {
        val root = JSONObject(json)
        val yards = TownYards.parse(root)
        val map = com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, yards,
            com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
            com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())
        val cam = com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
        val wasOpen = cam.open
        try {
            cam.open = true
            TownPlay.keepers = keepers()
            val street = TownPlay.start(town, map, yards)
            val o = TownPlay.open!!
            assertTrue(TownPlay.walk === o && TownPlay.openWorld)
            assertTrue(events.first().contains("open world"))
            // stick up with the camera looking east: the player walks east, whatever the street's direction
            o.cameraYaw = (Math.PI / 2).toFloat()
            val x0 = o.wx; val z0 = o.wz
            hold(0f, -1f, 1f)
            assertTrue("walked east ${o.wx - x0}", o.wx - x0 > 30f && kotlin.math.abs(o.wz - z0) < (o.wx - x0) * .5f + 1f)
            assertEquals("the walk never turns the camera", (Math.PI / 2).toFloat(), o.cameraYaw, 0f)
            assertNotNull(TownPlay.streetName())
            assertFalse(GameState.atCorner)
            assertTrue(GameState.nearest in o.row.buildings.indices || o.row.buildings.isEmpty())
            // someone to talk to, approached from four sides: found by world distance
            val (r, b) = town.rows.firstNotNullOf { r -> r.buildings.firstOrNull { it.sprite in TownPlay.keepers }?.let { r to it } }
            var found = 0
            for (side in 0 until 4) {
                val q = TownPlay.keeperSpot(r, b); val p = q.x to q.z
                val a = side * Math.PI / 2
                TownPlay.placeAt(p.first + 20f * kotlin.math.cos(a).toFloat(), p.second + 20f * kotlin.math.sin(a).toFloat(), 1f, 0f)
                TownPlay.step(dt)
                if (o.fromKeeper(b.sprite) <= TalkRange.distance) {
                    // (the nearest, this one unless a neighbour's stands nearer)
                    val n = TownPlay.nearestKeeper()!!
                    assertTrue(o.fromKeeper(n) <= o.fromKeeper(b.sprite))
                    assertNotNull(TownPlay.talkSprite())
                    found++
                    assertTrue(TownPlay.fromPlayer(b.sprite) < TalkRange.distance)
                }
            }
            assertTrue("found from $found of 4 sides", found >= 3)
            // the player's figure faces the way they walk (the camera then picks front, back or side)
            hold(1f, 0f, .4f)
            TownPlay.people(600f)
            val me = TownPlay.figures.first { it.me }
            assertEquals(o.hx, me.dx, 1e-6f); assertEquals(0, me.facing)
            // the view switch: to the street view where the player stands, and back
            val ox = o.wx; val oz = o.wz
            cam.open = false
            TownPlay.step(dt)
            assertTrue(TownPlay.walk === street && !TownPlay.openWorld)
            assertTrue("handed over near (${street.wx}, ${street.wz}) from ($ox, $oz)", kotlin.math.hypot(street.wx - ox, street.wz - oz) < 120f)
            hold(1f, 0f, .5f)
            cam.open = true
            TownPlay.step(dt)
            assertTrue(TownPlay.walk === o)
            assertTrue(kotlin.math.hypot(street.wx - o.wx, street.wz - o.wz) < 20f)
            assertFalse(map.blocked(o.wx, o.wz, com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk.RADIUS - .5f))
            assertTrue(events.any { it.startsWith("view street") } && events.any { it.startsWith("view open world") })
        } finally { cam.open = wasOpen }
    }
}
