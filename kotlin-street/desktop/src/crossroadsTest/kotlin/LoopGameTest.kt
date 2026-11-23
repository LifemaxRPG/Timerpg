import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.loop.LoopBystander
import com.lifemax.rpg.feature.habits.loop.LoopFigure
import com.lifemax.rpg.feature.habits.loop.LoopGame
import com.lifemax.rpg.feature.habits.loop.LoopItems
import com.lifemax.rpg.feature.habits.loop.LoopRoutes
import com.lifemax.rpg.feature.habits.loop.LoopScene
import com.lifemax.rpg.feature.habits.loop.LoopSight
import com.lifemax.rpg.feature.habits.loop.LoopTown
import com.lifemax.rpg.feature.habits.loop.LoopOfficerWalk
import com.lifemax.rpg.feature.habits.simjs.SimJsThread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first playable loop (docs/LOOP.md) on the real capital (gamma): the facade's init built from the town's own
 * street.json and dialogue.json, then the loop played through Jeff's facade in QuickJS (assets/sim/sim.js): a purchase,
 * a theft the shopkeeper sees, two days for word to spread, the shopkeeper refusing to serve, the stolen thing sold at
 * the pawn shop; a save and a load; the police's plans walked on the town's roads; line of sight through a building.
 * Timings are this computer's (an x86 server), not the S25's.
 * gradle :desktop:crossroadsTest --tests LoopGameTest
 */
class LoopGameTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val root = JSONObject(File(dir, "street.json").readText())
    private val town = Town.parse(root)!!
    private val loop = LoopTown.build(town, root, JSONObject(File(dir, "dialogue.json").readText()))
    private val bundle: String by lazy { File(System.getProperty("timerpg.simjs.bundle") ?: "../app/src/main/assets/sim/sim.js").readText() }

    /** The keeper behind the counter, the player in front of it: seen face on (room pixels, as InteriorRoom's). */
    private fun facing(place: com.lifemax.rpg.feature.habits.loop.LoopPlace) = LoopSight.inside(place, 320f, 300f, 320f, 560f, 320f, 380f, .9)
    /** The keeper with their back to the player (the player behind them, at the shelves). */
    private fun behind(place: com.lifemax.rpg.feature.habits.loop.LoopPlace) = LoopSight.inside(place, 320f, 300f, 320f, 560f, 320f, 180f, .9)

    @Test fun initFromTheRealTown() {
        val shops = loop.places.filter { it.kind != "home" }
        println("LoopGameTest: ${loop.people.size} people, ${loop.places.size} places (${shops.size} not homes: " +
            shops.joinToString { "${it.name} [${it.kind}/${it.shopKind}] keeper ${it.keeperId?.let { k -> loop.personById[k]?.name }}" } + "), " +
            "${loop.nodes.size} nodes, ${loop.edges.size} roads, ${loop.lamps.size} lamps")
        assertNotNull(loop.placeById["glb:pawn_shop"]); assertNotNull(loop.placeById["glb:police_station"])
        assertTrue("a general store or its successor", loop.places.any { it.id == "store:generalstore" })
        assertTrue("a market", loop.places.any { it.kind == "market" })
        assertTrue("every shop has a keeper", shops.filter { it.kind != "police_station" }.all { it.keeperId != null })
        assertTrue("spouses found", loop.people.count { it.spouseId != null } > 20)
        assertTrue("friends", loop.people.count { it.friends.isNotEmpty() || it.spouseId != null } > loop.people.size * 9 / 10)
        val init = loop.initJson(10.0)
        assertEquals(loop.people.size, init.getJSONArray("people").length())
        // (metres: the town is about 2 km across)
        val xs = (0 until init.getJSONObject("streetGraph").getJSONArray("nodes").length()).map { init.getJSONObject("streetGraph").getJSONArray("nodes").getJSONObject(it).getJSONObject("position").getDouble("x") }
        assertTrue("metres, not street units: ${xs.max()}", xs.max() in 1000.0..3000.0)
        println("LoopGameTest: init JSON %.0f KB".format(init.toString().length / 1024.0))
    }

    @Test fun buyStealGossipRefuseSellSaveLoad() {
        val antiques = loop.placeById.getValue("store:generalstore")
        val pawn = loop.placeById.getValue("glb:pawn_shop")
        SimJsThread().use { t ->
            t.load(bundle).get()
            val saved = t.run { js ->
                val g = LoopGame(js, loop)
                val events = ArrayList<String>()
                g.onEvent = { n, _ -> events += n }
                g.init(10.0)
                val cash0 = g.cash
                assertEquals(LoopItems.startingCash(2025), cash0)
                val stock = LoopItems.stockFor(antiques.shopKind, loop.year)
                // 1. a purchase at the general store's successor (the antiques dealer in 2025)
                val cheap = stock.minBy { it.price2025 }
                val b = g.buy(antiques, cheap, facing(antiques), 10.25)
                println("LoopGameTest: buy: ${b.line}")
                assertTrue(b.line, b.ok)
                assertEquals(cash0 - LoopItems.eraPrice(cheap.price2025, 2025), g.cash)
                // 2. a theft the keeper sees, face on
                val watch = stock.first { it.key == "pocketwatch" }
                val tk = g.take(antiques, watch, facing(antiques), 10.5)
                println("LoopGameTest: take: ${tk.line} | ${g.lastAct}")
                assertTrue(tk.ok)
                val w = g.lastAct!!.getJSONArray("witnessedBy")
                assertTrue("the keeper saw it", (0 until w.length()).any { w.getJSONObject(it).getString("personId") == antiques.keeperId })
                assertTrue("the watch is hot", g.items.single { it.key == "pocketwatch" }.stolen)
                // 3. two days pass: word spreads, a report goes in
                val adv = g.advance(48.0, "test")!!
                println("LoopGameTest: advance 48 h: ${adv.optJSONArray("attitudeChanges")?.length()} attitude changes, cases ${adv.optJSONArray("newPoliceCases")}, plans ${adv.optJSONArray("policePlans")?.length()}")
                val att = g.attitude(antiques.keeperId!!, fresh = true)
                println("LoopGameTest: the keeper: ${att.optString("stance")} ${att.optJSONArray("facts")}")
                assertTrue(g.facts.getValue(antiques.keeperId!!).isNotEmpty())
                // 4. the keeper won't serve the player now
                val again = g.buy(antiques, cheap, facing(antiques), g.hour + .1)
                println("LoopGameTest: buy again: ${again.line}")
                assertFalse(again.line, again.ok)
                assertTrue(again.line, again.line.contains("won't"))
                // 5. the stolen watch sold at the pawn shop (opens at 10: the clock to 11)
                val cashBefore = g.cash
                val watchItem = g.items.single { it.key == "pocketwatch" }
                val s = g.sell(pawn, watchItem, facing(pawn), if (g.hour < 11.0) 11.0 else g.hour + .1)
                println("LoopGameTest: sell: ${s.line} | ${g.lastAct}")
                assertTrue(s.line, s.ok)
                assertEquals(cashBefore + g.pawnPrice(watchItem), g.cash)
                assertTrue(g.items.none { it.key == "pocketwatch" })
                // 6. a shift at the pawn shop
                val wk = g.work(pawn, facing(pawn), g.hour)
                println("LoopGameTest: work: ${wk.line}")
                // 7. another day, then the police's view
                g.advance(24.0, "test")
                println("LoopGameTest: debug view:\n  " + g.view.debug.joinToString("\n  "))
                println("LoopGameTest: events ${events.groupingBy { it }.eachCount()}")
                println("LoopGameTest: QuickJS timings on the real town (this x86 server, not the S25), ms last/max: " +
                    g.timings.entries.joinToString { (k, v) -> "$k %.1f/%.1f".format(v.first, v.second) } + "; heap %.1f MB".format(js.memoryUsed / 1048576.0))
                g.save().also { println("LoopGameTest: save %.0f KB".format(it.length / 1024.0)) } to Triple(g.cash, g.items.map { it.id }, g.day)
            }.get()
            // 8. the save loads into a fresh engine and gives the same save back
            SimJsThread().use { t2 ->
                t2.load(bundle).get()
                t2.run { js ->
                    val g2 = LoopGame(js, loop)
                    val t0 = System.nanoTime()
                    g2.load(saved.first)
                    println("LoopGameTest: load %.1f ms".format((System.nanoTime() - t0) / 1e6))
                    assertEquals(saved.second.first, g2.cash)
                    assertEquals(saved.second.second, g2.items.map { it.id })
                    assertEquals(saved.second.third, g2.day)
                    assertEquals(JSONObject(saved.first).getString("sim"), JSONObject(g2.save()).getString("sim"))
                    // and the keeper still refuses
                    val again = g2.buy(antiques, LoopItems.stockFor(antiques.shopKind, 2025).minBy { it.price2025 }, facing(antiques), g2.hour + .1)
                    assertFalse(again.line, again.ok)
                }.get()
            }
        }
    }

    @Test fun unseenTheftAndTheArrest() {
        val shop = loop.places.first { it.id.startsWith("shop:") && it.shopKind == "bakery" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(11.0)
                // the keeper's back turned, nobody else near: nobody sees
                val tk = g.take(shop, LoopItems.stockFor(shop.shopKind, 2025).first(), behind(shop), 11.2)
                println("LoopGameTest: unseen take: ${tk.line} | ${g.lastAct?.optString("summary")}")
                assertEquals(0, g.lastAct!!.getJSONArray("witnessedBy").length())
                // a theft in plain view, then the police find the player
                val shop2 = loop.places.first { it.id.startsWith("shop:") && it.shopKind == "music store" }
                g.take(shop2, LoopItems.stockFor(shop2.shopKind, 2025).first(), facing(shop2), 11.5)
                g.advance(30.0, "test")
                val officer = g.officerPlans.firstOrNull()?.optString("officerId") ?: "officer:${loop.jurisdictionId}:0"
                val seen = g.spotted(officer, 4.0, .95, g.hour)
                println("LoopGameTest: spotted: ${g.lastSpot}; ${seen?.line}")
                assertTrue("a recognised description is stopped", g.lastSpot!!.optString("action") in setOf("approach_and_question", "arrest"))
                val r = seen!!
                println("LoopGameTest: arrest: ${r.line} | ${g.lastArrest?.optString("penalty")} | at ${r.moveTo?.name} | hour ${g.hour}")
                assertNotNull(r.moveTo)
                assertTrue("what was stolen is taken", g.items.none { it.stolen })
            }.get()
        }
    }

    /** A busier week: 20 thefts each seen by several people, then a day at a time (timing; this server, not the S25). */
    @Test fun aBusyWeekTiming() {
        val shops = loop.places.filter { it.kind == "general_store" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(9.0)
                val acts = ArrayList<Double>()
                for (k in 0 until 20) {
                    val shop = shops[k % shops.size]
                    val others = loop.people.drop(k * 5).take(4).map { it.id }
                    val sc = LoopSight.inside(shop, 320f, 300f, 320f, 560f, 320f, 380f, .9, others)
                    g.take(shop, LoopItems.stockFor(shop.shopKind, 2025).first(), sc, 9.0 + k * .25)
                    acts += g.timings.getValue("act").first
                }
                val days = ArrayList<Double>()
                repeat(7) { g.advance(24.0, "test"); days += g.timings.getValue("advance").first }
                val att = ArrayList<Double>()
                for (p in loop.people) { g.attitude(p.id, fresh = true); att += g.timings.getValue("attitude").first }
                val save = g.save()
                println("LoopGameTest: busy week (x86 server, not the S25): act mean %.2f max %.2f ms; advance 24 h mean %.1f max %.1f ms; attitude x%d mean %.2f ms; save %.0f KB; heap %.1f MB; %d people know something"
                    .format(acts.average(), acts.max(), days.average(), days.max(), att.size, att.average(), save.length / 1024.0, js.memoryUsed / 1048576.0, g.facts.count { it.value.isNotEmpty() }))
            }.get()
        }
    }

    @Test fun officersWalkTheRoadsAndBuildingsBlockTheView() {
        val routes = LoopRoutes(loop)
        val ids = loop.nodes.take(4).map { it.id } + LoopTown.STATION_NODE
        val path = routes.path(ids)
        val walk = LoopOfficerWalk("o", "patrol", "test", path, 0L)
        println("LoopGameTest: a route through ${ids.size} nodes: ${path.size / 2} points, %.0f m".format(walk.length / com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U))
        assertTrue(path.size >= 4); assertTrue(walk.length > 0f)
        val a = walk.at(5_000_000_000L)
        assertTrue(a[0].isFinite() && a[1].isFinite())
        // line of sight: across open street yes, through the pawn shop's building no
        val col = TownCollision.build(town, TownYards.parse(root), ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
            CinemaSiteShape.parse(root), StoreLots.parse(root).map { it.sprite }.toSet())
        // (a shop of boxes: the landmarks' solids are added as their models are built, not in this base map)
        val pawn = loop.places.first { it.id.startsWith("shop:") && it.shopKind == "bakery" }
        val (r, b) = town.placement(pawn.sprite)!!
        val front = r.pointAt(b.mid, 0f); val n = r.normalAt(b.mid)
        val inside = (b.setback + b.depth / 2f)
        val behindX = front.x + n.x * (b.setback + b.depth + 200f); val behindZ = front.z + n.z * (b.setback + b.depth + 200f)
        println("LoopGameTest: the bakery's door (${pawn.doorX}, ${pawn.doorZ}), behind ($behindX, $behindZ), depth ${b.depth}, middle $inside")
        assertFalse("the building blocks the view", LoopSight.clear(col, pawn.doorX, pawn.doorZ, behindX, behindZ))
        val acrossX = pawn.doorX - n.x * 300f; val acrossZ = pawn.doorZ - n.z * 300f
        assertTrue("open street", LoopSight.clear(col, pawn.doorX, pawn.doorZ, acrossX, acrossZ))
        // the outdoor scene: a passer across the street sees, one behind the building doesn't
        val crowd = listOf(LoopFigure(acrossX, acrossZ, n.x, n.z, true, 7, null), LoopFigure(behindX, behindZ, -n.x, -n.z, true, 8, null))
        val sc: LoopScene = LoopSight.outdoors(loop, col, crowd, pawn.doorX, pawn.doorZ)
        println("LoopGameTest: outdoors: " + sc.bystanders.joinToString { b2: LoopBystander -> "${b2.id} los ${b2.los} %.0f m".format(b2.distance / com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U) })
        assertEquals(listOf(true, false), sc.bystanders.map { it.los })
    }
}
