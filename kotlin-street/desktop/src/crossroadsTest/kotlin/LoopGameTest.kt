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
                // 6. an odd-job shift where no opening exists this week (where one does, the shift is refused: ask about work)
                val hiring = loop.places.first { it.kind != "home" && it.kind != "police_station" && g.openingsAt(it).isNotEmpty() }
                assertTrue(g.work(hiring, facing(hiring), g.hour).line.contains("hiring"))
                val odd = loop.places.firstOrNull { it.kind != "home" && it.kind != "police_station" && g.openingsAt(it).isEmpty() && g.isOpenNow(it) && it.keeperId != null }
                    ?: loop.places.first { it.kind != "home" && it.kind != "police_station" && g.openingsAt(it).isEmpty() }.also { g.clockTo(11.0, exact = true) }
                val wk = g.work(odd, facing(odd), g.hour)
                println("LoopGameTest: work: ${wk.line}")
                assertTrue(wk.line, wk.ok)
                val hours = wk.hoursPassed
                val wage = JSONObject(js.call("simCall", "{\"method\":\"economy.wage\",\"args\":[\"${LoopItems.job(odd.shopKind)}\",2025]}"))
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

    // ------------------------------------------------------------------ the calendar and employment (docs/CALENDAR.md, docs/JOBS.md)

    private val shopsOnly get() = loop.places.filter { it.kind != "home" && it.kind != "police_station" }

    /** A shop open now with an opening this week (matching [pick]); the clock goes on a week at a time until one is found. */
    private fun findOpening(g: LoopGame, open: Boolean = false, pick: (JSONObject) -> Boolean = { true }): Pair<com.lifemax.rpg.feature.habits.loop.LoopPlace, JSONObject> {
        repeat(8) {
            for (p in shopsOnly) if (!open || g.isOpenNow(p)) g.openingsAt(p).firstOrNull(pick)?.let { return p to it }
            g.advance(24.0 * 7, "test")
        }
        error("no opening found")
    }

    @Test fun theDateAdvancesWeekdaysCycleAndAHolidayClosesTheShops() {
        val market = loop.places.first { it.kind == "market" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(8.0)
                // (2025 gamma starts on Tuesday 6 Bloommonth, the first Tuesday of the month)
                assertEquals(listOf(2025, 5, 6), g.startDate.toList())
                assertEquals("Tue 6 Bloommonth", g.view.dateText)
                assertEquals(2, g.weekdayOf(1))
                for (d in 1..20) assertEquals((g.weekdayOf(d) + 1) % 7, g.weekdayOf(d + 1))
                assertEquals(g.weekdayOf(1), g.weekdayOf(8))
                g.advance(24.0, "test")
                assertEquals("Wed 7 Bloommonth", g.view.dateText)
                assertEquals("Sun 1 Sunmonth", g.dayInfo(27).text)
                assertEquals("", g.view.holidayText)
                println("LoopGameTest: dates ${g.dayInfo(1).text} ... ${g.dayInfo(27).text}")
            }.get()
            t.run { js ->
                // a holiday (Hearth Day, Sunday 5 Frostmonth 2025, shuts the shops): Saturday the market is open, Sunday it is not
                val g = LoopGame(js, loop)
                g.init(10.0, startDate = intArrayOf(2025, 1, 3))
                assertEquals("Fri 3 Frostmonth", g.view.dateText)
                g.advance(25.0, "test")
                assertEquals("Sat 4 Frostmonth", g.view.dateText)
                assertTrue(g.isOpenNow(market)); assertEquals("", g.hoursOf(market).holiday)
                g.advance(24.0, "test")
                assertEquals("Sun 5 Frostmonth", g.view.dateText); assertEquals("Hearth Day", g.view.holidayText)
                assertFalse(g.isOpenNow(market))
                val h = g.hoursOf(market)
                println("LoopGameTest: holiday: ${h.today} | ${h.closedLine} | ${h.holiday}")
                assertEquals("Hearth Day", h.holiday)
                assertTrue(h.closedLine, h.closedLine.startsWith("Closed for Hearth Day. Opens tomorrow at"))
                assertEquals("Closed today for Hearth Day", h.today)
                val no = g.buy(market, g.stockFor(market).minBy { it.priceCents }, facing(market), g.hour)
                assertFalse(no.ok); assertTrue(no.line, no.line.startsWith("Closed for Hearth Day"))
                g.advance(24.0, "test")
                assertTrue("Monday it opens again", g.isOpenNow(market))
            }.get()
        }
    }

    @Test fun applyingLateTwiceAbsenceAndTheft() {
        SimJsThread().use { t ->
            t.load(bundle).get()
            // 1. no reference: a trial; late twice: dismissal with a poor reference
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(20.0) // (in the evening: the first shift is tomorrow's)
                val (place, op) = findOpening(g) { it.optBoolean("wantsReference") }
                val start = op.getInt("startHour")
                val a = g.applyFor(place, op.getString("id"), g.hour)
                println("LoopGameTest: apply: ${a.line}")
                assertTrue(a.line, a.ok); assertTrue(a.line, a.line.contains("on trial"))
                assertEquals("trial", g.view.jobs.job!!.status)
                assertTrue("the opening is taken", g.openingsAt(place).none { it.getString("id") == op.getString("id") })
                g.sleepUntil(5.0)
                // late: more than 15 minutes
                val cash0 = g.cash
                val s1 = g.startShift(place, facing(place), start + 1.0)
                println("LoopGameTest: late 1: ${s1.line}")
                assertTrue(s1.line, s1.ok); assertTrue(s1.line, s1.line.contains("Warning"))
                assertEquals(1, g.view.jobs.job!!.strikes)
                val pay = g.cash - cash0
                assertEquals((s1.hoursPassed * g.view.jobs.job!!.hourlyCents).let { Math.round(it).toInt() }, pay)
                assertTrue("one shift a day", g.startShift(place, facing(place), g.hour).line.contains("done today's"))
                g.sleepUntil(5.0)
                val s2 = g.startShift(place, facing(place), start + 1.0)
                println("LoopGameTest: late 2: ${s2.line}")
                assertFalse(s2.line, s2.ok); assertTrue(s2.line, s2.line.contains("lets you go"))
                assertNull(g.view.jobs.job)
                assertTrue(g.view.jobs.referencesText, g.view.jobs.referencesText.contains("poor"))
                assertFalse("no shift without a job", g.startShift(place, facing(place), g.hour).ok)
            }.get()
            // 2. missing days: 'absent' at each day's end; two of them dismiss
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.5)
                val (place, op) = findOpening(g)
                assertTrue(g.applyFor(place, op.getString("id"), g.hour).ok)
                g.advance(24.0, "test")
                assertEquals(1, g.view.jobs.job!!.strikes)
                assertTrue(g.view.jobs.news, g.view.jobs.news.contains("missed your shift"))
                g.advance(24.0, "test")
                assertNull(g.view.jobs.job)
                assertTrue(g.view.jobs.news, g.view.jobs.news.contains("lets you go"))
            }.get()
            // 3. a theft at the workplace, seen by staff: dismissed
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.5)
                val (place, op) = findOpening(g, open = true)
                assertTrue(g.applyFor(place, op.getString("id"), g.hour).ok)
                val item = g.stockFor(place).first { it.quantity > 0 }
                val r = g.take(place, item, facing(place), g.hour)
                println("LoopGameTest: theft at work: ${r.line}")
                assertTrue(r.line, r.line.contains("caught stealing"))
                assertNull(g.view.jobs.job)
                assertTrue(g.view.jobs.referencesText.contains("poor"))
            }.get()
        }
    }

    @Test fun aSteadyRunGivesARaiseAGoodReferenceAndTheJobSaves() {
        SimJsThread().use { t ->
            t.load(bundle).get()
            val saved = t.run { js ->
                val g = LoopGame(js, loop)
                g.init(20.0)
                val (place, op) = findOpening(g) { it.optBoolean("wantsReference") }
                val start = op.getInt("startHour"); val hourly = op.getInt("hourlyCents"); val len = op.getInt("shiftHours")
                assertTrue(g.applyFor(place, op.getString("id"), g.hour).ok)
                g.sleepUntil(5.0)
                var raised = false
                var mid: String? = null
                for (n in 1..15) {
                    val cash0 = g.cash
                    val r = g.startShift(place, facing(place), start.toDouble())
                    assertTrue("shift $n: ${r.line}", r.ok)
                    if (n == 7) assertTrue(r.line, r.line.contains("hired")).also { assertEquals("hired", g.view.jobs.job!!.status); mid = g.save() }
                    if (r.line.contains("raise")) { raised = true; assertEquals(15, n) }
                    // (the pay is the job's own hourly rate for the shift's hours: the raised rate only from the next shift)
                    if (n < 15) assertEquals(hourly * len, g.cash - cash0)
                    g.sleepUntil(5.0)
                }
                assertTrue("the raise on the 15th shift", raised)
                val j = g.view.jobs.job!!
                assertTrue(j.hasRaise); assertEquals("good", j.reference)
                assertEquals(Math.round(hourly * 1.1).toInt(), j.hourlyCents)
                println("LoopGameTest: steady: ${j.line}")
                assertTrue(j.line, j.line.contains("good reference") && j.line.contains("raised"))
                // leaving with the good reference keeps it for the next application
                val q = g.quitJob()
                assertTrue(q.line, q.line.contains("good reference"))
                assertTrue(g.view.jobs.referencesText.contains("good"))
                mid!!
            }.get()
            // the save from the 7th shift loads into a fresh engine with the job (and its place, strikes, start hour) intact
            SimJsThread().use { t2 ->
                t2.load(bundle).get()
                t2.run { js ->
                    val g = LoopGame(js, loop)
                    g.load(saved)
                    val j = g.view.jobs.job!!
                    assertEquals("hired", j.status)
                    assertEquals(7, j.steadyShifts)
                    assertEquals(g.day, j.lastWorkedDay)
                    assertEquals(listOf(2025, 5, 6), g.startDate.toList())
                    assertEquals(JSONObject(saved).getJSONObject("job").getJSONObject("emp").toString(), JSONObject(g.save()).getJSONObject("job").getJSONObject("emp").toString())
                    val place = loop.placeById.getValue(j.placeId)
                    g.sleepUntil(5.0)
                    val r = g.startShift(place, facing(place), j.startHour.toDouble())
                    assertTrue(r.line, r.ok)
                    assertEquals(8, g.view.jobs.job!!.steadyShifts)
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
                assertEquals("day 1 is the start date's weekday: a Tuesday", 2, g.weekdayOf(1))
                assertEquals(5, g.weekdayOf(4))
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
                g.clockTo(8.25, exact = true)
                assertTrue(g.isOpenNow(market))
                acts.clear()
                val b1 = g.buy(market, item, facing(market), 8.25)
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

    // ------------------------------------------------------------------ needs, weather and cars (docs/LOOP.md "Needs, weather and cars")

    /** Advances the loop to in-game [d] at [h] o'clock (a burst of hours). */
    private fun toDayHour(g: LoopGame, d: Int, h: Double) { val hrs = (d - g.day) * 24.0 + (h - g.hour); if (hrs > 0) g.advance(hrs, "test") }

    @Test fun needsTickHungryEatingRelievesAndASleepOnABenchRestoresLessThanABed() {
        val diner = loop.places.first { it.shopKind == "diner" }
        val pharmacy = loop.places.first { it.shopKind == "pharmacy" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(8.0)
                assertTrue("not hungry at the start", g.view.needs!!.labels.none { it == "hungry" })
                // 1. ten hours on foot: hungry; the walk's pace slows with it
                g.walkActivity = "walking"
                g.advance(10.0, "hour")
                println("LoopGameTest: after 10 h walking: ${g.view.needs!!.labels} hunger %.2f walk x%.2f".format(g.view.needs!!.hunger, g.view.needs!!.walk))
                assertTrue(g.view.needs!!.labels.contains("hungry"))
                assertTrue(g.view.walkMultiplier < 1.0)
                // 2. a meal at the diner (a third of the era's diner day), paid, relieves it
                if (!g.isOpenNow(diner)) toDayHour(g, g.day + 1, 12.0)
                val dn = org.json.JSONObject(js.call("simCall", "{\"method\":\"economy.dailyNeeds\",\"args\":[2025]}"))
                val price = dn.getInt("dinerMealsPerDayCents") / 3
                assertEquals(price, g.eatOption(diner)!!.cents)
                val hungerBefore = g.view.needs!!.hunger; val cash0 = g.cash
                val e = g.eat(diner, g.hour)
                println("LoopGameTest: ${e.line}")
                assertTrue(e.line, e.ok)
                assertEquals(cash0 - price, g.cash)
                assertTrue(g.view.needs!!.hunger < hungerBefore - .3)
                assertTrue(g.view.needs!!.labels.none { it == "hungry" })
                // 3. a snack at a general store is cheaper; the pawn shop serves nothing
                val shop = loop.places.first { it.kind == "market" }
                assertTrue(g.eatOption(shop)!!.cents < price); assertNull(g.eatOption(loop.placeById.getValue("glb:pawn_shop")))
                // 4. a day unwashed and tired: the look goes into what people know of the player
                g.advance(30.0, "hour")
                println("LoopGameTest: after 40 h: ${g.view.needs!!.labels} looks: ${g.view.needs!!.look}")
                assertTrue(g.view.needs!!.look.isNotEmpty())
                val someone = loop.people.first().id
                g.attitude(someone, fresh = true)
                assertTrue(g.facts.getValue(someone).any { it.contains("looks ") && it.contains("this person") })
                // 5. a bench restores less than the bed (the same tired player, saved and put back)
                g.addCash(50000)
                val tired = g.save()
                toDayHour(g, g.day, 22.0).also { if (g.hour >= 22.0) toDayHour(g, g.day + 1, 22.0) }
                val snap = g.save()
                g.sleepUntil(7.0, "bench")
                val bench = g.view.needs!!.fatigue
                g.load(snap)
                g.sleepUntil(7.0, "bed")
                val bed = g.view.needs!!.fatigue
                println("LoopGameTest: fatigue after the night: bench %.2f, bed %.2f".format(bench, bed))
                assertTrue("bench $bench > bed $bed", bench > bed + .1)
                // 6. the needs are saved with the loop
                val s1 = g.save(); val before = g.needsState().toString()
                g.load(tired); g.load(s1)
                assertEquals(before, g.needsState().toString())
                // 7. an injury and the pharmacy: treated for the module's cost, it heals faster
                g.injure("bruise", "a test")
                assertTrue(g.view.needs!!.labels.contains("bruise"))
                val cost = g.view.needs!!.injuries.single().costCents
                assertEquals((js.call("simCall", "{\"method\":\"needs.treatmentCost\",\"args\":[\"bruise\",2025]}")).toInt(), cost)
                if (!g.isOpenNow(pharmacy)) toDayHour(g, g.day + 1, 12.0)
                val c0 = g.cash
                assertTrue(g.treat(pharmacy, "bruise", g.hour).ok)
                assertEquals(c0 - cost, g.cash)
                assertTrue(g.view.needs!!.injuries.single().treated)
                assertFalse(g.treat(pharmacy, "bruise", g.hour).ok)
                // 8. board included: a room_and_board lodging gives two meals a day for nothing
                g.lodgingKind = "room_and_board"
                assertTrue(g.rentRoom(g.hour).ok)
                assertEquals(2, g.boardIncluded())
                val cb = g.cash
                assertTrue(g.takeBoard().ok); assertTrue(g.takeBoard().ok); assertFalse(g.takeBoard().ok)
                assertEquals(cb, g.cash)
            }.get()
        }
    }

    @Test fun theSkyFollowsTheDateAndFogCutsTheWitnessRange() {
        val shop = loop.placeById.getValue("store:generalstore")
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(8.0)
                // the same date, the same sky (also in another game)
                val a = g.skyAt(20, 14.0)!!.toString()
                val g2 = LoopGame(js, loop); g2.init(8.0)
                assertEquals(a, g2.skyAt(20, 14.0)!!.toString())
                val seen = (1..120).map { g.skyAt(it, 14.0)!!.getString("sky") }.toSet()
                println("LoopGameTest: skies at 14:00 over 120 days: $seen; day 20: $a")
                assertTrue("more than one kind of sky: $seen", seen.size >= 3)
                val dFog = (1..500).first { g.skyAt(it, 22.0)!!.getString("sky") == "fog" }
                val dClear = (dFog + 1..dFog + 400).first { g.skyAt(it, 22.0)!!.getString("sky") in setOf("clear", "cloudy") }
                // a keeper 8 m away, looking at the player in the street, at night: seen on a clear night, not in fog (6 m)
                fun theft(d: Int): Pair<Int, Int> {
                    g.init(8.0)
                    toDayHour(g, d, 22.0)
                    val u = com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U
                    val keeper = shop.keeperId!!
                    val sc = LoopScene(shop.doorX, shop.doorZ, listOf(LoopBystander(keeper, shop.doorX + 8f * u, shop.doorZ, -1f, 0f, "idle", true, 8f * u)), .9, false)
                    val it = g.stockFor(shop).first { it.quantity > 0 }
                    g.take(shop, it, sc, g.hour)
                    return g.lastAct!!.optInt("sighted") to (g.lastAct!!.optJSONArray("witnessedBy")?.length() ?: 0)
                }
                val clear = theft(dClear); val fog = theft(dFog)
                println("LoopGameTest: day $dClear (${g.skyAt(dClear, 22.0)!!.getString("sky")}): sighted/witnesses $clear; day $dFog fog: $fog; faces to %.0f m".format(g.view.sky!!.faceM))
                assertEquals(1, clear.first)
                assertEquals(0, fog.first); assertEquals(0, fog.second)
                assertEquals(6.0, g.view.sky!!.faceM, .01)
                assertTrue(g.view.sky!!.walk <= 1.0)
            }.get()
        }
    }

    @Test fun buyingACarPapersFuelWearBreakdownsAndATow() {
        val gas = loop.places.first { it.shopKind == "gas station" }
        SimJsThread().use { t ->
            t.load(bundle).get()
            t.run { js ->
                val g = LoopGame(js, loop)
                g.init(10.0)
                g.addCash(2_000_000)
                if (!g.isOpenNow(gas)) toDayHour(g, g.day + 1, 12.0)
                val lot = g.lotListings()
                println("LoopGameTest: the lot: " + lot.joinToString { "${it.label} ${it.askingText} -> ${it.specKey}" })
                assertTrue(lot.size >= 4)
                assertTrue("round prices", lot.all { it.askingCents % 100 == 0 })
                assertTrue(lot.all { it.specKey in com.lifemax.rpg.feature.habits.vehicle.VehicleSpec.V1 })
                // 1. buying debits the price and makes an owned car, parked at the lot, that saves and loads
                val worn = lot.filter { it.conditionPct < 80 }.minBy { it.conditionPct }
                val cash0 = g.cash
                val b = g.buyCar(gas, worn.id, g.hour)
                println("LoopGameTest: ${b.line}")
                assertTrue(b.line, b.ok)
                assertEquals(cash0 - worn.askingCents, g.cash)
                val car = g.view.cars.owned.single()
                assertTrue(car.atLot); assertEquals(worn.specKey, car.specKey)
                assertTrue("gone from the lot", g.lotListings().none { it.id == worn.id })
                val g2 = LoopGame(js, loop); g2.load(g.save())
                assertEquals(car.id, g2.view.cars.owned.single().id); assertEquals(car.fuel, g2.view.cars.owned.single().fuel, 1e-9)
                g.load(g2.save())
                // 2. papers at the lot, at the era's price
                val reg = g.view.cars.papers.first { it.kind == "registration" }
                val c1 = g.cash
                assertTrue(g.buyPaper(gas, "registration", g.hour).ok)
                assertEquals(c1 - reg.cents, g.cash); assertTrue(g.hasPaper("registration")); assertFalse(g.buyPaper(gas, "registration", g.hour).ok)
                // 3. fuel is paid by the gallon; the tank gives gallons by the car's mpg as it is driven
                val mpg = car.mpg
                g.driveMiles(car.id, 10.0)
                val used = car.fuel - g.view.cars.owned.single().fuel
                assertEquals(10.0 / mpg, used, 1e-6)
                val price = g.view.cars.fuelCents
                val c2 = g.cash; val f0 = g.view.cars.owned.single().fuel
                assertTrue(g.refuel(car.id, 5.0, near = true).ok)
                assertEquals(c2 - 5 * price, g.cash); assertEquals(f0 + 5.0, g.view.cars.owned.single().fuel, 1e-9)
                // (the flat tyre's repair is a tenth of the module's: about $1.50 in 1952 money)
                val module = js.call("simCall", "{\"method\":\"cars.repairCost\",\"args\":[\"flat\",2025]}").let { JSONObject(it).getInt("repairCents") }
                assertEquals(Math.round(module / 10.0).toInt(), g.repairOf("flat")!!.first)
                // 4. wear: miles on a worn car end in a breakdown (a fixed seed: the same miles every run); the engine then does not run
                var miles = 0.0
                while (g.view.cars.owned.single().blocked == null && miles < 30000) {
                    g.driveMiles(car.id, 5.0); miles += 5.0
                    if (g.view.cars.owned.single().fuel < 3.0) g.refuel(car.id, null, near = true)
                }
                val broke = g.view.cars.owned.single()
                println("LoopGameTest: ${broke.label}: ${broke.blocked} after %.0f miles (mileage ${broke.mileage}, condition ${broke.conditionPct}%%); repair ${broke.repairText}, tow ${broke.towText}".format(miles))
                assertNotNull("a worn car breaks down", broke.breakdown)
                val mil = broke.mileage
                g.driveMiles(car.id, 5.0)
                assertEquals("a broken car does not run", mil, g.view.cars.owned.single().mileage)
                // 5. a tow costs and moves it to the lot; a garage repair there costs its price; then it runs
                // (the car was driven off the lot and broke down by the road: its spot is kept by the app)
                g.parkCar(car.id, 4000f, 3000f, 1f, 0f)
                val c3 = g.cash
                assertFalse(g.view.cars.owned.single().atLot)
                // (a garage job is refused by the roadside; a flat or a dead battery is fixed there, not towed: here the tow is tried either way)
                if (!broke.roadside) assertFalse(g.repair(car.id, atLot = false).ok)
                val tow = g.callTow(car.id)
                println("LoopGameTest: ${tow.line}")
                assertTrue(tow.line, tow.ok)
                assertEquals(c3 - broke.towCents, g.cash); assertTrue(g.view.cars.owned.single().atLot)
                val c4 = g.cash
                assertTrue(g.repair(car.id, atLot = true).ok)
                assertEquals(c4 - broke.repairCents, g.cash)
                assertNull(g.view.cars.owned.single().blocked)
            }.get()
        }
    }
}
