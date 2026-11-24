import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.loop.LoopBystander
import com.lifemax.rpg.feature.habits.loop.LoopFigure
import com.lifemax.rpg.feature.habits.loop.LoopFences
import com.lifemax.rpg.feature.habits.loop.LoopGame
import com.lifemax.rpg.feature.habits.loop.LoopRoutes
import com.lifemax.rpg.feature.habits.loop.LoopScene
import com.lifemax.rpg.feature.habits.loop.LoopSight
import com.lifemax.rpg.feature.habits.loop.LoopTown
import com.lifemax.rpg.feature.habits.loop.LoopOfficerWalk
import com.lifemax.rpg.feature.habits.simjs.SimJsThread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONArray
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private fun econStock(js: com.lifemax.rpg.feature.habits.simjs.SimJs, kind: String, year: Int, seed: String): List<Pair<String, Int>> {
        val a = org.json.JSONArray(js.call("simCall", "{\"method\":\"economy.stockOf\",\"args\":[\"$kind\",$year,\"$seed\"]}"))
        return (0 until a.length()).map { a.getJSONObject(it).let { e -> e.getString("itemId") to e.getInt("priceCents") } }
    }
    private fun econLong(js: com.lifemax.rpg.feature.habits.simjs.SimJs, method: String, args: String): Long =
        js.call("simCall", "{\"method\":\"economy.$method\",\"args\":[$args]}").toLong()

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
                // (the economy's numbers, asked of the bundle directly: 2025's index is 1, so $60 is 6000 cents)
                assertEquals(6000, cash0)
                assertEquals("\$60.00", g.money(cash0))
                val stock = g.stockFor(antiques)
                val opened = org.json.JSONObject(js.call("simCall", "{\"method\":\"economy.openStock\",\"args\":[\"pawn\",2025,\"${loop.seed}|${antiques.id}\"]}")).getJSONArray("entries")
                assertEquals((0 until opened.length()).map { opened.getJSONObject(it).let { e -> e.getString("itemId") to e.getInt("priceCents") } }, stock.map { it.key to it.priceCents })
                assertTrue("a shelf of many things", stock.size > 5)
                assertEquals("stock is cached per day", stock, g.stockFor(antiques))
                // 1. a purchase at the general store's successor (the antiques dealer in 2025)
                val cheap = stock.minBy { it.priceCents }
                val b = g.buy(antiques, cheap, facing(antiques), 10.25)
                println("LoopGameTest: buy: ${b.line}")
                assertTrue(b.line, b.ok)
                assertEquals(cash0 - cheap.priceCents, g.cash)
                assertTrue(g.items.single().value == cheap.priceCents)
                // 2. a theft the keeper sees, face on
                val watch = stock.first { it.key == "pocketwatch" }
                assertTrue(watch.serial)
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
                // (the hot offer is the economy's own: half the clean offer)
                assertEquals(econLong(js, "pawnOffer", "\"pocketwatch\",2025,true"), g.pawnPrice(watchItem).toLong())
                assertEquals(econLong(js, "pawnOffer", "\"pocketwatch\",2025,false") / 2, g.pawnPrice(watchItem).toLong())
                assertTrue(g.items.none { it.key == "pocketwatch" })
                val cashAfterSale = g.cash
                // 6. a shift at the pawn shop
                val wk = g.work(pawn, facing(pawn), g.hour)
                println("LoopGameTest: work: ${wk.line}")
                assertTrue(wk.line, wk.ok)
                val hours = wk.hoursPassed
                val wage = JSONObject(js.call("simCall", "{\"method\":\"economy.wage\",\"args\":[\"odd_jobs\",2025]}"))
                assertEquals((hours * wage.getInt("hourlyCents")).toInt(), g.cash - cashAfterSale)
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
                    val again = g2.buy(antiques, g2.stockFor(antiques).minBy { it.priceCents }, facing(antiques), g2.hour + .1)
                    assertFalse(again.line, again.ok)
                }.get()
            }
        }
    }

    private fun theFence(g: LoopGame) = g.fences.first()

    @Test fun fencesLeadsAndDeals() {
        val antiques = loop.placeById.getValue("store:generalstore")
        SimJsThread().use { t ->
            t.load(bundle).get()
            val saved = t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                println("LoopGameTest: fences: " + g.fences.joinToString { "${it.name} (${it.cover}) ${it.whereText} ${it.hoursText} at (${it.spotX}, ${it.spotZ})" } + "; ${g.leads.size} leads")
                assertTrue("0-2 fences", g.fences.size in 1..2)
                // deterministic: a second new game has the same ones
                val g2 = LoopGame(js, loop); g2.init(10.0)
                assertEquals(g.fences.map { it.personId }, g2.fences.map { it.personId })
                assertEquals(g.leads.map { it.toString() }, g2.leads.map { it.toString() })
                assertTrue("a fence stands at a real spot", g.fences.all { it.spotX != 0f || it.spotZ != 0f })
                assertTrue(g.fences.all { it.atHours(22.0) && it.atHours(1.0) && !it.atHours(12.0) })
                // a lead is in the source person's facts (unless they would refuse the player)
                assertTrue("leads exist", g.leads.isNotEmpty())
                val lead = g.leads.first(); val fence = g.fences.first { it.personId == lead.getString("fenceId") }
                g.attitude(lead.getString("sourceId"), fresh = true)
                val fact = g.facts.getValue(lead.getString("sourceId"))
                println("LoopGameTest: lead facts of ${loop.personById[lead.getString("sourceId")]?.name}: $fact")
                assertEquals("one line per lead", fact.distinct(), fact)
                assertTrue(fact.any { it.contains(fence.name) && it.contains("no questions asked") && it.contains(fence.whereText) })
                // the debug panel lists them
                assertTrue(g.view.debug.any { it.startsWith("Fence ${fence.name}") })
                // a warm item (stolen three days ago) to the fence: pays less than the pawn shop's clean price, trust rises
                val f = theFence(g)
                val watch = g.stockFor(antiques).first { it.key == "pocketwatch" }
                assertTrue(g.take(antiques, watch, behind(antiques), 10.5).ok)
                g.advance(72.0, "test")
                val item = g.items.single { it.key == "pocketwatch" }
                assertEquals(.6, LoopFences.heat(item, g.day), 1e-9)
                val cash = g.cash
                val q = g.fenceQuote(f, item)
                println("LoopGameTest: fence quote for a warm watch: ${q.text} (${q.accepted}, ${q.reason}); pawn clean ${econLong(js, "pawnOffer", "\"pocketwatch\",2025,false")}, hot ${g.pawnPrice(item)}")
                assertTrue(q.accepted)
                val r = g.sellToFence(f, item, 22.0)
                println("LoopGameTest: ${r.line}")
                assertTrue(r.line, r.ok)
                assertEquals(cash + q.cents, g.cash)
                assertTrue("less than the clean pawn price", q.cents < econLong(js, "pawnOffer", "\"pocketwatch\",2025,false"))
                assertTrue(g.items.none { it.key == "pocketwatch" })
                assertTrue("trust rose", g.trustOf(f) > 0.0)
                // a fence deal leaves no pawn entry; the loop still saves
                g.save() to (f.personId to g.trustOf(f))
            }.get()
            // save/load keeps the fences, their leads and trust
            SimJsThread().use { t2 ->
                t2.load(bundle).get()
                t2.run { js ->
                    val g3 = LoopGame(js, loop)
                    g3.load(saved.first)
                    assertEquals(saved.second.first, g3.fences.first().personId)
                    assertEquals(saved.second.second, g3.trustOf(g3.fences.first()), 1e-9)
                    assertTrue(g3.leads.isNotEmpty())
                }.get()
            }
        }
    }

    @Test fun aVeryHotItemIsRefusedForAStranger() {
        val antiques = loop.placeById.getValue("store:generalstore")
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                val f = theFence(g)
                assertTrue(g.take(antiques, g.stockFor(antiques).first { it.key == "pocketwatch" }, behind(antiques), 10.5).ok)
                val item = g.items.single { it.key == "pocketwatch" }
                assertEquals(.9, LoopFences.heat(item, g.day), 1e-9)
                val cash = g.cash
                val r = g.sellToFence(f, item, 21.0)
                println("LoopGameTest: hot to a stranger: ${r.line}")
                assertFalse(r.line, r.ok)
                assertTrue(r.line, r.line.contains("too hot"))
                assertEquals(cash, g.cash); assertEquals(1, g.items.size); assertEquals(0.0, g.trustOf(f), 0.0)
                // and not outside their hours
                val day = g.sellToFence(f, item, 21.5).also { assertTrue(it.line, !it.ok) }
                val noon = LoopGame(js, loop).also { it.init(12.0); it.items += item }.sellToFence(f, item, 12.5)
                assertFalse(noon.line, noon.ok); assertTrue(noon.line, noon.line.contains("isn't here"))
            }.get()
        }
    }

    @Test fun openingHoursClosedShopsAndStaffing() {
        val market = loop.places.first { it.kind == "market" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            // (the economy's hours through the bundle: Sunday is shut before 1965 and short after)
            t.run { js ->
                fun spans(year: Int, wd: Int) = org.json.JSONArray(js.call("simCall", "{\"method\":\"economy.hoursOf\",\"args\":[\"market\",$year,$wd,\"x\"]}")).length()
                assertEquals(0, spans(1925, 0)); assertEquals(1, spans(1985, 0)); assertEquals(1, spans(1925, 1))
            }.get()
            t.run { js ->
                val g = LoopGame(js, loop)
                val acts = ArrayList<String>()
                g.onEvent = { n, d -> if (n == "act") acts += d.optString("kind") + ":" + d.optInt("bystanders") }
                g.init(7.0)
                assertEquals("day 1 is the start weekday", LoopTown.START_WEEKDAY, g.weekdayOf(1))
                assertEquals((LoopTown.START_WEEKDAY + 3) % 7, g.weekdayOf(4))
                val stock = g.stockFor(market)
                val item = stock.minBy { it.priceCents }
                // 1. shut at 7: Buy is refused with the opening time, and the HUD's hours say so
                assertFalse(g.isOpenNow(market))
                val no = g.buy(market, item, facing(market), 7.5)
                println("LoopGameTest: closed: ${no.line} | ${g.hoursOf(market).today}")
                assertFalse(no.ok); assertTrue(no.line, no.line.startsWith("Closed. Opens at "))
                assertEquals(6000, g.cash)
                assertFalse(g.work(market, facing(market), 7.6).ok)
                // 2. taking from a shut shop is a break-in: an `enter` (forced) is recorded before the theft, nobody inside
                val tk = g.take(market, item, facing(market), 7.7)
                println("LoopGameTest: closed take: ${tk.line} | acts $acts")
                assertTrue(tk.line, tk.ok)
                assertEquals(listOf("enter:0", "take:0"), acts)
                assertTrue(g.items.single().stolen)
                // 3. opening hour vs the lunch rush: fewer eyes at the start
                g.clockTo(10.25, exact = true)
                assertTrue(g.isOpenNow(market))
                acts.clear()
                val b1 = g.buy(market, item, facing(market), 10.25)
                val atOpening = g.lastAct!!.getInt("bystanders")
                val b2 = g.buy(market, item, facing(market), 12.5)
                val atRush = g.lastAct!!.getInt("bystanders")
                println("LoopGameTest: staff ${g.staffNow(market)} at 12:30; bystanders opening $atOpening, rush $atRush; ${g.hoursOf(market).today}")
                assertTrue(b1.line + b2.line, b1.ok && b2.ok)
                assertEquals(1, atOpening); assertTrue("rush: $atRush", atRush > atOpening)
            }.get()
        }
    }

    @Test fun shelvesDepleteRestockAndSave() {
        val shop = loop.places.first { it.id.startsWith("shop:") && it.shopKind == "bakery" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            val saved = t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                val item = g.stockFor(shop).first { it.quantity > 0 }
                // buy every unit, then one more
                repeat(item.quantity) {
                    val r = g.take(shop, g.stockFor(shop).first { s -> s.key == item.key }, behind(shop), 10.1)
                    assertTrue(r.line, r.ok)
                }
                assertEquals(0, g.stockFor(shop).first { it.key == item.key }.quantity)
                val again = g.take(shop, g.stockFor(shop).first { it.key == item.key }, behind(shop), 10.2)
                assertFalse(again.ok); assertTrue(again.line, again.line.contains("Sold out", true))
                val cash = g.cash
                val buy = g.buy(shop, g.stockFor(shop).first { it.key == item.key }, facing(shop), 10.3)
                assertFalse(buy.ok); assertTrue(buy.line, buy.line.contains("Sold out", true)); assertEquals(cash, g.cash)
                g.save() to item
            }.get()
            // saved empty shelf survives a load
            SimJsThread().use { t2 ->
                t2.load(bundle).get()
                t2.run { js ->
                    val g2 = LoopGame(js, loop)
                    g2.load(saved.first)
                    assertEquals(0, g2.stockFor(shop).first { it.key == saved.second.key }.quantity)
                    // a market-like bakery delivers daily: a few days on, the shelf is full again
                    g2.advance(24.0 * 3, "test")
                    assertTrue("restocked", g2.stockFor(shop).first { it.key == saved.second.key }.quantity > 0)
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
                val tk = g.take(shop, g.stockFor(shop).first(), behind(shop), 11.2)
                println("LoopGameTest: unseen take: ${tk.line} | ${g.lastAct?.optString("summary")}")
                assertEquals(0, g.lastAct!!.getJSONArray("witnessedBy").length())
                // a theft in plain view, then the police find the player
                val shop2 = loop.places.first { it.id.startsWith("shop:") && it.shopKind == "music store" }
                g.take(shop2, g.stockFor(shop2).first(), facing(shop2), 11.5)
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
                    g.take(shop, g.stockFor(shop).first(), sc, 9.0 + k * .25)
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

    private fun econJson(js: com.lifemax.rpg.feature.habits.simjs.SimJs, method: String, args: String): Any =
        org.json.JSONTokener(js.call("simCall", "{\"method\":\"$method\",\"args\":[$args]}")).nextValue()

    @Test fun theBoardingHouseAndTheStopsAreFound() {
        val l = loop.lodging!!
        println("LoopGameTest: ${l.name} at ${l.placeId}; lines " + loop.lines.joinToString { ln -> "${ln.street}: " + ln.stops.joinToString { it.name } })
        assertTrue(loop.placeById.getValue(l.placeId).kind == "home")
        assertTrue("Main Street and two more lines", loop.lines.size >= 2 && loop.lines.any { it.street == "Main Street" })
        assertTrue(loop.lines.all { it.stops.size >= 2 })
    }

    @Test fun rentComesDueThenWarnsThenLocksOutAndTheLedgerSaves() {
        val shop = loop.placeById.getValue("store:generalstore")
        SimJsThread().use { t ->
            t.load(bundle).get()
            val saved = t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                // 1. a room costs more than the $60 the player arrives with
                val poor = g.rentRoom(10.0)
                assertFalse(poor.line, poor.ok); assertTrue(poor.line, poor.line.contains("A room costs"))
                assertEquals(6000, g.cash)
                assertTrue(g.view.livingLine.startsWith("Most people here live on about \$"))
                println("LoopGameTest: ${g.view.livingLine} | ${g.view.lodgingOffer}")
                // 2. renting debits the deposit and the first period (lodgingOf, dueOn)
                g.addCash(20000)
                val terms = econJson(js, "economy.lodgingOf", "\"boarding_house\",2025") as JSONObject
                val due = (econJson(js, "economy.dueOn", "${terms},1,1") as Number).toInt()
                assertEquals(terms.getInt("rentCents") + terms.getInt("depositCents"), due)
                val before = g.cash
                val kept = g.stockFor(shop).minBy { it.priceCents }
                val r = g.rentRoom(10.0)
                println("LoopGameTest: ${r.line}")
                assertTrue(r.line, r.ok); assertEquals(before - due, g.cash)
                assertEquals("current", g.view.rent!!.status); assertNull(g.view.rent!!.line)
                assertFalse(g.rentRoom(10.0).ok)
                // an item is left in the room
                assertTrue(g.buy(shop, kept, facing(shop), 10.1).ok)
                val item = g.items.single()
                assertTrue(g.stash(item).ok); assertTrue(g.items.isEmpty())
                // 3. the period is up (day 8): the rent is due, nothing is taken by itself
                g.advance(7 * 24.0 - 0.2, "test")
                println("LoopGameTest: day ${g.day}: ${g.view.rent!!.line}")
                assertEquals(8, g.day); assertEquals("due", g.view.rent!!.status)
                assertTrue(g.view.rent!!.line!!.contains("due"))
                assertEquals(terms.getInt("rentCents"), g.view.rent!!.owedCents)
                // 4. a day later: a warning
                g.advance(24.0, "test")
                println("LoopGameTest: day ${g.day}: ${g.view.rent!!.line}")
                assertEquals("warning", g.view.rent!!.status); assertTrue(g.view.rent!!.line!!.contains("late"))
                assertTrue(g.sleepInRoom().ok.also { assertEquals(1, g.nightsInRoom) })
                // 5. a week late: locked out, no sleeping there, the things are held
                g.advance(24.0 * 6, "test")
                println("LoopGameTest: day ${g.day}: ${g.view.rent!!.line}")
                assertEquals("locked_out", g.view.rent!!.status); assertTrue(g.view.rent!!.lockedOut)
                val night = g.sleepInRoom(); assertFalse(night.line, night.ok)
                val back = g.retrieve(item); assertFalse(back.line, back.ok); assertEquals(1, g.itemsInRoom.size)
                assertFalse(g.stash(item).ok)
                // 6. the ledger is saved and loaded
                val owed = g.view.rent!!.owedCents
                val save = g.save()
                SimJsThread().use { t2 ->
                    t2.load(bundle).get()
                    t2.run { js2 ->
                        val g2 = LoopGame(js2, loop)
                        g2.load(save)
                        assertEquals(owed, g2.view.rent!!.owedCents); assertEquals("locked_out", g2.view.rent!!.status)
                        assertEquals(1, g2.nightsInRoom); assertEquals(listOf(item.id), g2.itemsInRoom.map { it.id })
                        assertFalse(g2.sleepInRoom().ok)
                    }.get()
                }
                // 7. paying clears the arrears; the room and the things are the player's again
                g.addCash(owed)
                val cash = g.cash
                assertTrue(g.payRent(g.hour).ok); assertEquals(cash - owed, g.cash)
                assertEquals("current", g.view.rent!!.status)
                assertTrue(g.retrieve(item).ok); assertEquals(1, g.items.size)
                val h0 = g.day * 24 + g.hour
                assertTrue(g.sleepInRoom().ok); assertEquals(2, g.nightsInRoom)
                assertEquals(7.0, g.hour, 1e-6); assertTrue(g.day * 24 + g.hour > h0)
                g.save()
            }.get()
            assertTrue(saved.contains("\"rental\""))
        }
    }

    @Test fun aRidePaysTheFareWaitsAndMovesThePlayer() {
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                val line = loop.lines.first { it.street == "Main Street" }
                val a = line.stops.first(); val b = line.stops.last()
                val info = g.view.transit.getValue(a.id)
                println("LoopGameTest: at ${a.name}: next ${info.departures}; to ${info.rides.joinToString { "${it.toName} ${it.text} ${it.minutes} min" }}")
                assertTrue(info.departures.isNotEmpty())
                val opt = info.rides.first { it.toId == b.id }
                // the expected numbers, straight from the economy's transit module
                val mode = g.transitMode!!
                val km = Math.hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble()) / com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U / 1000.0
                val fare = (econJson(js, "transit.fare", "\"$mode\",2025,$km") as Number).toInt()
                val minutes = (econJson(js, "transit.travelTime", "\"$mode\",2025,$km") as Number).toInt()
                val deps = econJson(js, "transit.departures", "{\"id\":\"${line.id}\",\"mode\":\"$mode\"},2025,${(loop.let { 0 })},10,\"${loop.seed}\"") as JSONArray
                val dep = deps.getInt(0)
                assertEquals(fare, opt.cents); assertEquals(minutes, opt.minutes)
                val cash = g.cash; val t0 = g.day * 24 + g.hour
                val r = g.ride(a, b, 10.0)
                println("LoopGameTest: ${r.line}")
                assertTrue(r.line, r.ok)
                assertEquals(cash - fare, g.cash)
                val expected = (dep / 60.0 - 10.0) + minutes / 60.0
                assertEquals(expected, g.day * 24 + g.hour - t0, 2e-3)
                assertEquals(expected, r.hoursPassed, 1e-9)
                assertSame(b, r.moveToStop)
                // too poor for the fare: nothing happens
                g.addCash(-g.cash + fare - 1)
                val h1 = g.hour
                val no = g.ride(b, a, g.hour); assertFalse(no.line, no.ok); assertEquals(h1, g.hour, 1e-9)
                // a train ticket takes its price from transit.fare('railway'), not RailNet's table
                g.addCash(20000)
                val rail = (econJson(js, "transit.fare", "\"railway\",2025,120.0,\"second\"") as Number).toInt()
                val c0 = g.cash
                assertEquals(rail, g.railFare(120.0)); assertTrue(g.buyRailTicket(120.0).ok); assertEquals(c0 - rail, g.cash)
                val co = g.view.railFare!!
                assertEquals(rail.toDouble(), co[0] + co[1] * 120.0, 1.0)
            }.get()
        }
    }
}
