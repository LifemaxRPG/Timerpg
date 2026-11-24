import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.lifemax.rpg.feature.habits.domain.GlbLandmarks
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import com.lifemax.rpg.feature.habits.loop.LoopGame
import com.lifemax.rpg.feature.habits.loop.LoopMaps
import com.lifemax.rpg.feature.habits.loop.LoopTown
import com.lifemax.rpg.feature.habits.maps.FullMap
import com.lifemax.rpg.feature.habits.maps.FullMapView
import com.lifemax.rpg.feature.habits.maps.MapData
import com.lifemax.rpg.feature.habits.maps.MapHere
import com.lifemax.rpg.feature.habits.maps.MapImages
import com.lifemax.rpg.feature.habits.maps.MapNav
import com.lifemax.rpg.feature.habits.maps.MapPlayer
import com.lifemax.rpg.feature.habits.maps.MapRules
import com.lifemax.rpg.feature.habits.maps.MapStyle
import com.lifemax.rpg.feature.habits.maps.MapTier
import com.lifemax.rpg.feature.habits.maps.MiniPainter
import com.lifemax.rpg.feature.habits.maps.Minimap
import com.lifemax.rpg.feature.habits.maps.OwnedMap
import com.lifemax.rpg.feature.habits.maps.TownMapModel
import com.lifemax.rpg.feature.habits.maps.minimapUnits
import com.lifemax.rpg.feature.habits.simjs.SimJsThread
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The maps (docs/MAPS.md) on the real capital (gamma): the GPS year, the paper-map rules, buying maps in the loop and
 * keeping them in the save, the waypoint's route along the town's roads, the city map's picture; and renders of the
 * minimap and the full map (-Dmaps.out=DIR, default build/maps) as PNGs to look at.
 * gradle :desktop:crossroadsTest --tests MapsTest
 */
class MapsTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val root = JSONObject(File(dir, "street.json").readText())
    private val town = Town.parse(root)!!
    private val loop = LoopTown.build(town, root, JSONObject(File(dir, "dialogue.json").readText()))
    private val bundle: String by lazy { File(System.getProperty("timerpg.simjs.bundle") ?: "../app/src/main/assets/sim/sim.js").readText() }
    private val model: TownMapModel by lazy { TownMapModel.build(town, GlbLandmarks.parse(root), TownYards.parse(root), TownMapModel.shopNames(root),
        com.lifemax.rpg.feature.habits.domain.TownCollision.build(town, TownYards.parse(root), com.lifemax.rpg.feature.habits.domain.ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
            com.lifemax.rpg.feature.habits.domain.CinemaSiteShape.parse(root), com.lifemax.rpg.feature.habits.domain.StoreLots.parse(root).map { it.sprite }.toSet())) }
    private val out = File(System.getProperty("maps.out") ?: System.getenv("MAPS_OUT") ?: "build/maps")

    /** Gamma as if it were [year] (the same streets and people; the economy and the rules of that year). */
    private fun townIn(year: Int) = LoopTown(loop.town, loop.seed, year, loop.townName, loop.jurisdictionId, loop.places, loop.people, loop.nodes, loop.edges, loop.lamps)

    private val here = MapHere(town.seed, "region:home", onNamedStreet = true)

    @Test fun gpsFromTheYearOfPhoneMaps() {
        assertFalse(MapRules.gps(1952)); assertFalse(MapRules.gps(2007)); assertTrue(MapRules.gps(2008)); assertTrue(MapRules.gps(town.year))
        // gamma is 2025: the phone has every tier, the minimap and the route, with no paper map at all
        val a = MapRules.access(town.year, emptyList(), here)
        assertTrue(a.gps); assertEquals(MapTier.CITY, a.minimap); assertTrue(a.showPosition); assertTrue(a.route)
        assertEquals(setOf(MapTier.CITY, MapTier.REGIONAL, MapTier.WORLD), a.tiers.keys)
        // before GPS with nothing: the compass only
        val b = MapRules.access(1952, emptyList(), here)
        assertFalse(b.gps); assertTrue(b.compassOnly); assertNull(b.opens)
        // a road map in town: the minimap draws it; another town's city map does not count
        val c = MapRules.access(1952, listOf(OwnedMap(MapTier.REGIONAL, "region:home", 1950, ""), OwnedMap(MapTier.CITY, "elsewhere", 1950, "")), here)
        assertEquals(MapTier.REGIONAL, c.minimap); assertEquals(1950, c.printYear(MapTier.REGIONAL)); assertNull(c.printYear(MapTier.CITY)); assertFalse(c.route)
        // off the streets (a back yard), the paper map is there but not the player's spot
        val d = MapRules.access(1952, listOf(OwnedMap(MapTier.CITY, town.seed, 1950, "")), MapHere(town.seed, "region:home", onNamedStreet = false))
        assertEquals(MapTier.CITY, d.minimap); assertFalse(d.showPosition)
        // the free road maps of the gas stations' era, and 1952's prices
        assertEquals(0, MapRules.priceCents(MapTier.REGIONAL, 1952, 1.0, 1.0, atGasStation = true))
        assertEquals(25, MapRules.priceCents(MapTier.REGIONAL, 1952, 1.0, 1.0, atGasStation = false))
        assertEquals(150, MapRules.priceCents(MapTier.WORLD, 1952, 1.0, 1.0, atGasStation = false))
        assertTrue(MapRules.priceCents(MapTier.REGIONAL, 1995, 1.0, .2, atGasStation = true) > 0)
    }

    @Test fun preGpsBuyingTheCityMapGivesTheMinimapAndTheMapsSave() {
        val t1952 = townIn(1952)
        SimJsThread().use { t ->
            t.load(bundle).get()
            val saved = t.run { js ->
                val g = LoopGame(js, t1952)
                g.init(10.0)
                assertFalse(g.view.maps.gps)
                assertTrue("no map at the start", g.ownedMaps.isEmpty())
                assertTrue("compass only", MapRules.access(1952, g.ownedMaps, here).compassOnly)
                // the general store sells the city map at 1952's 35 cents (the price index of 1952 over itself)
                val store = t1952.places.first { LoopMaps.tiersAt(it.id, it.shopKind).contains(MapTier.CITY) && g.isOpenNow(it) }
                val offer = g.mapOffers(store.id).first { it.tier == MapTier.CITY }
                println("MapsTest: 1952 at ${store.name}: " + g.mapOffers(store.id).joinToString { "${it.label} ${it.text}" })
                assertEquals(35, offer.cents)
                val cash0 = g.cash
                val b = g.buyMap(store.id, offer.id, 10.2)
                println("MapsTest: ${b.line}")
                assertTrue(b.line, b.ok)
                assertEquals(cash0 - 35, g.cash)
                assertEquals(1952, g.ownedMaps.single().printYear)
                val a = MapRules.access(1952, g.ownedMaps, here)
                assertEquals(MapTier.CITY, a.minimap); assertTrue(a.showPosition); assertFalse(a.gps)
                assertFalse("a second copy is refused", g.buyMap(store.id, offer.id, 10.3).ok)
                // the gas station gives the road map away in 1952
                g.mapOffers(LoopMaps.STATION).let { assertTrue(it.any { o -> o.tier == MapTier.WORLD }) }
                t1952.places.firstOrNull { it.shopKind == "gas station" && g.isOpenNow(it) }?.let { gas ->
                    val road = g.mapOffers(gas.id).first { it.tier == MapTier.REGIONAL }
                    assertEquals(0, road.cents)
                    assertTrue(g.buyMap(gas.id, road.id, 10.4).ok)
                    assertEquals(cash0 - 35, g.cash)
                }
                g.setWaypoint(1234f, 5678f)
                g.save()
            }.get()
            // the maps and the waypoint come back in a fresh game
            t.run { js ->
                val g = LoopGame(js, t1952)
                g.load(saved)
                assertTrue(g.ownedMaps.any { it.tier == MapTier.CITY && it.area == town.seed && it.printYear == 1952 })
                assertEquals(1234f, g.waypoint!![0], 1e-3f); assertEquals(5678f, g.waypoint!![1], 1e-3f)
                assertEquals(g.ownedMaps.size, g.view.maps.owned.size)
                g.setWaypoint(null, null)
                assertNull(g.view.maps.waypoint)
            }.get()
        }
        // 2025: still for sale (at 2025's prices), but the phone has GPS
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                assertTrue(g.view.maps.gps)
                val offers = g.view.maps.offers.values.flatten()
                println("MapsTest: 2025: " + offers.distinctBy { it.tier }.joinToString { "${it.label} ${it.text}" })
                assertTrue(offers.any { it.tier == MapTier.CITY && it.cents > 35 })
            }.get()
        }
    }

    @Test fun routeAlongTheRoadsIsLongerThanTheCrowFlies() {
        val a = town.nodes.minBy { it.x + it.z }; val b = town.nodes.maxBy { it.x + it.z }
        model.route(a.x, a.z, b.x, b.z) // (the model is made on first use: timed apart)
        val t0 = System.nanoTime()
        val r = model.route(a.x + 30f, a.z + 20f, b.x - 25f, b.z + 15f)
        val ms = (System.nanoTime() - t0) / 1e6
        assertNotNull(r); r!!
        val straight = hypot(b.x - a.x, b.z - a.z)
        println("MapsTest: route %.0f m along the roads, %.0f m straight, %d points, %.1f ms".format(r.length / VehicleWorld.U, straight / VehicleWorld.U, r.pts.size / 2, ms))
        assertTrue("longer than straight", r.length > straight)
        assertTrue("not absurdly long", r.length < straight * 3f)
        // it starts and ends at the two points, and every point between is on a road (no jumps across the blocks)
        assertEquals(a.x + 30f, r.pts[0], 1e-3f); assertEquals(b.z + 15f, r.pts.last(), 1e-3f)
        for (i in 1 until r.pts.size / 2 - 1) assertNotNull("point $i on a road", model.streetAt(r.pts[i * 2], r.pts[i * 2 + 1], reach = 1f))
        for (i in 0 until r.pts.size / 2 - 1) {
            val mx = (r.pts[i * 2] + r.pts[i * 2 + 2]) / 2f; val mz = (r.pts[i * 2 + 1] + r.pts[i * 2 + 3]) / 2f
            if (i in 1 until r.pts.size / 2 - 2) assertNotNull("step $i along a road", model.streetAt(mx, mz, reach = 1f))
        }
        // the same road: a short route; the nav keeps it and its distance
        val nav = MapNav()
        nav.update(model, true, a.x, a.z, floatArrayOf(b.x, b.z))
        assertNotNull(nav.route); assertTrue(nav.distanceM > straight / VehicleWorld.U)
        nav.update(model, false, a.x, a.z, floatArrayOf(b.x, b.z))
        assertNull("no GPS: a bearing only", nav.route)
        // the streets' names where the player stands
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        println("MapsTest: at the spawn: ${model.streetAt(sp.x, sp.z)}")
        assertNotNull(model.streetAt(sp.x, sp.z))
        assertNull(model.streetAt(model.x0 - 5000f, model.z0 - 5000f))
    }

    @Test fun theCityMapBuildsFromTheRealTownAndRenders() {
        installDesktopImages()
        val t0 = System.nanoTime()
        val r = model.raster(MapStyle.GPS)
        val paper = model.raster(MapStyle.PAPER)
        println("MapsTest: city map ${r.w} x ${r.h} px at %.0f units (%.2f m) a pixel, both looks in %.0f ms; %d labels (%d streets)".format(
            r.unitsPerPx, r.unitsPerPx / VehicleWorld.U, (System.nanoTime() - t0) / 1e6, model.labels.size, model.labels.count { it.kind.name == "STREET" }))
        assertTrue(r.w in 300..3000 && r.h in 300..3000)
        assertTrue("several colours", r.px.toSet().size >= 8); assertTrue(paper.px.toSet().size >= 8)
        val img = MapImages.of(r)
        assertEquals(r.w, img.width)
        out.mkdirs()
        // the planet, where its skeleton is in the repository
        val skf = File("../../content/world/alpha/skeleton.twsk.gz")
        val site = if (skf.isFile) skf.inputStream().use { WorldSite.of(WorldSkeleton.read(it), town.seed, town.scale.toDouble()) } else null
        val data = MapData(model, site, town.year)
        if (site != null) {
            val t1 = System.nanoTime()
            val reg = data.layerNow(MapTier.REGIONAL, MapStyle.GPS, town.year)!!
            val world = data.layerNow(MapTier.WORLD, MapStyle.GPS, town.year)!!
            println("MapsTest: regional ${reg.w} x ${reg.h} (${reg.labels.size} labels), world ${world.w} x ${world.h} (${world.labels.size}), %.0f ms; region ${data.region}".format((System.nanoTime() - t1) / 1e6))
            assertTrue(reg.labels.size > 10)
            val old = data.layerNow(MapTier.REGIONAL, MapStyle.GPS, 1925)!!
            println("MapsTest: a 1925 road map names ${old.labels.size} places and routes, 2025's ${reg.labels.size}")
            writePng(reg, File(out, "regional_2025.png")); writePng(world, File(out, "world_2025.png"))
        }
        // the player at the spawn, the waypoint at the police station's door
        val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
        val dir = town.row(town.spawn.row).alongAt(town.spawn.x)
        val st = loop.station!!
        val player = MapPlayer().set(sp.x, sp.z, kotlin.math.atan2(dir.x, -dir.z), 30f)
        val wp = floatArrayOf(st.doorX, st.doorZ)
        val nav = MapNav()
        // the minimap's own picture alone (allocation-free path), then the HUD's composables
        val city = data.layerNow(MapTier.CITY, MapStyle.GPS, town.year)!!
        val mp = MiniPainter()
        nav.update(model, true, player.x, player.z, wp)
        mp.draw(city, player, minimapUnits(city, 2), false, true, wp, nav.route, nav.distanceM)
        val tick = mutableLongStateOf(1L)
        val gpsAccess = MapRules.access(town.year, emptyList(), here)
        fun scene(w: Int, h: Int, content: @androidx.compose.runtime.Composable () -> Unit): org.jetbrains.skia.Image {
            val s = ImageComposeScene(w, h, Density(2f)) { content() }
            var img2 = s.render(0L)
            for (i in 1..6) { Thread.sleep(30); img2 = s.render(i * 16_666_667L) }
            s.close(); return img2
        }
        val mini = scene(560, 480) {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.androidx_bg(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
                Minimap(city, gpsAccess, tick, { it.set(player.x, player.z, player.heading, player.yaw); true }, wp, nav, model, onOpen = {}, size = androidx.compose.ui.unit.Dp(150f))
            }
        }
        File(out, "minimap_gps.png").writeBytes(mini.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        val full = scene(1560, 720) {
            FullMap(data, gpsAccess, town.year, player, wp, nav, onWaypoint = { _, _ -> }, onClose = {},
                start = FullMapView(MapTier.CITY, (player.x + wp[0]) / 2f, (player.z + wp[1]) / 2f, 1560f / (hypot(wp[0] - player.x, wp[1] - player.z) * 1.6f + 8000f)))
        }
        File(out, "fullmap_gps.png").writeBytes(full.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        // a 1952 paper city map, close in (the businesses' names)
        val paperAccess = MapRules.access(1952, listOf(OwnedMap(MapTier.CITY, town.seed, 1952, "")), here)
        val fullPaper = scene(1560, 720) {
            FullMap(data, paperAccess, 1952, player, wp, MapNav(), onWaypoint = { _, _ -> }, onClose = {},
                start = FullMapView(MapTier.CITY, player.x, player.z, 2f / VehicleWorld.U * .55f))
        }
        File(out, "fullmap_paper_1952.png").writeBytes(fullPaper.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        val miniPaper = scene(560, 480) {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.androidx_bg(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
                Minimap(data.layerNow(MapTier.CITY, MapStyle.PAPER, 1952), paperAccess, tick, { it.set(player.x, player.z, player.heading, player.yaw); true }, wp, MapNav(), model, onOpen = {}, size = androidx.compose.ui.unit.Dp(150f))
                com.lifemax.rpg.feature.habits.maps.CompassRose(tick, { 30f })
            }
        }
        File(out, "minimap_paper_1952.png").writeBytes(miniPaper.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        if (site != null) {
            val reg = scene(1560, 720) {
                FullMap(data, gpsAccess, town.year, player, wp, nav, onWaypoint = { _, _ -> }, onClose = {}, start = FullMapView(MapTier.REGIONAL, player.x, player.z, 0f))
            }
            File(out, "fullmap_regional.png").writeBytes(reg.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        data.close()
        println("MapsTest: renders in ${out.absolutePath}")
    }

    private fun writePng(l: com.lifemax.rpg.feature.habits.maps.MapLayer, f: File) {
        f.writeBytes(org.jetbrains.skia.Image.makeFromBitmap(l.image.asSkia()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}

private fun androidx.compose.ui.graphics.ImageBitmap.asSkia() = this.asSkiaBitmap()
/** A dark backdrop like the game's street at dusk under the HUD, and a margin. */
private fun androidx.compose.ui.Modifier.androidx_bg() = this.background(androidx.compose.ui.graphics.Color(0xFF556070)).padding(16.dp)
