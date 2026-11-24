package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.simjs.SimJs
import org.json.JSONArray
import org.json.JSONObject
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What one of the player's actions came to: the line the HUD shows, and what it did to the clock and the player. */
class LoopOutcome(
    val ok: Boolean,
    val line: String,
    /** The sim's hour of day after it (the game's clock follows it when it moved). */
    val hour: Double,
    val hoursPassed: Double = 0.0,
    /** The player is taken here (the police station after an arrest). */
    val moveTo: LoopPlace? = null,
    /** A wary shopkeeper: shown beside the line. */
    val wary: String? = null,
    /** The player is taken to this transit stop (a ride's end). */
    val moveToStop: LoopStop? = null,
)

/** What the HUD and the debug panel show, made after each burst on the sim's thread (read anywhere). */
class LoopView(
    /** The wallet in cents. */
    val cash: Int,
    /** [cash] as the era writes it (`economy.formatMoney`). */
    val cashText: String,
    /** Each shop's shelves today (place id -> stock) and the pawn shop's offers (item key|hot -> cents and text), made on the sim's thread. */
    val stock: Map<String, List<StockItem>>,
    val pawnOffers: Map<String, Int>,
    val pawnOfferTexts: Map<String, String>,
    val items: List<LoopItem>,
    val day: Int,
    val hour: Double,
    /** The debug panel's lines (cash, inventory, the last act's witnesses, heat, cases, the last advance's changes...). */
    val debug: List<String>,
    val officers: List<JSONObject>,
    val messages: List<String>,
    /** The town's fences, each one's trust (0..1) and what each would pay for each carried item ("<fence id>|<item id>"), made on the sim's thread. */
    val fences: List<LoopFence> = emptyList(),
    val fenceTrust: Map<String, Double> = emptyMap(),
    val fenceQuotes: Map<String, FenceQuote> = emptyMap(),
    /** Each shop's opening hours today and now (place id -> hours; `economy.hoursOf / isOpen / staffed`), made on the sim's thread. */
    val hours: Map<String, PlaceHours> = emptyMap(),
    /** The rented room (null until the player rents one), the offer to rent, and "most people here live on about $X a week" (`dailyBudget`); made on the sim's thread. */
    val rent: RentView? = null,
    val lodgingOffer: String = "",
    val lodgingDueCents: Int = 0,
    val livingLine: String = "",
    /** What each stop's panel shows (stop id -> info: next departures, fares; `transit.*`). */
    val transit: Map<String, StopInfo> = emptyMap(),
    /** The train fare for a trip of km kilometres is about `railFare[0] + railFare[1] * km` cents (`transit.fare('railway')`, class: cheapest), null with no train service in the year. */
    val railFare: DoubleArray? = null,
)

/**
 * A shop's hours as the economy sets them (src/economy/hours.ts), for the HUD: [open] now, [today] ("Open 8 a.m.-6 p.m.",
 * "Closed Sundays"), [closedLine] what the door says when it is shut ("Closed. Opens at 8 a.m."), [staff] people at work
 * now, [closeHour] closing time today (0 when closed).
 */
class PlaceHours(val open: Boolean, val today: String, val closedLine: String, val staff: Int, val closeHour: Int)

/**
 * The first playable loop's game side (kotlin-street/docs/LOOP.md): the player's wallet and inventory, the actions at
 * the shops (buy, take, sell at the pawn shop, work a shift), the clock moved in bursts, the shopkeepers' attitudes,
 * the police's plans and an officer's sighting, the arrest; every rule decided by Jeff's facade (src/loop, bundled as
 * sim.js) through [js]. Not thread-safe: everything runs on the simulation's own thread (SimJsThread.run), never the
 * render thread. [view] is the snapshot the UI reads.
 */
class LoopGame(private val js: SimJs, val town: LoopTown) {
    var cash = 0; private set
    val items = ArrayList<LoopItem>()
    private var nextItem = 1
    var day = 1; private set
    var hour = 8.0; private set

    /** Every loop event (name, details) for the playtest recorder (PlayLog). Set by the app. */
    var onEvent: ((String, JSONObject) -> Unit)? = null

    /** Each person's attitude facts for the dialogue's fact sheet, cached until their attitude may have changed. */
    val facts = ConcurrentHashMap<String, List<String>>()
    private val attitudes = HashMap<String, JSONObject>()

    var lastAct: JSONObject? = null; private set
    var lastAdvance: JSONObject? = null; private set
    var lastPolice: JSONObject? = null; private set
    var lastArrest: JSONObject? = null; private set
    var lastSpot: JSONObject? = null; private set
    private val messages = ArrayDeque<String>()
    /** The last call of each facade method (ms) and the most each took: QuickJS on this device. */
    val timings = LinkedHashMap<String, Pair<Double, Double>>()

    @Volatile var view: LoopView = LoopView(0, "", emptyMap(), emptyMap(), emptyMap(), emptyList(), 1, 8.0, emptyList(), emptyList(), emptyList()); private set

    // ------------------------------------------------------------------ the fences (src/economy/fence.ts, docs/LOOP.md "The fence")

    /** The town's fences (0-2, from `townFences` with the town's seed), their leads (who knows of them) and each one's trust in the player; saved with the loop. */
    var fences: List<LoopFence> = emptyList(); private set
    var leads: List<JSONObject> = emptyList(); private set
    private val fenceTrust = HashMap<String, Double>()
    /** The tip-offs so far: the deal (day, fence, item) a fence informed on; there is no police hook for it yet (see [sellToFence]). */
    private val tips = ArrayList<JSONObject>()
    private val quoteCache = HashMap<String, FenceQuote>()

    private fun buildFences() {
        val people = LoopFences.persons(town)
        val raw = econ("townFences", people, town.seed) as? JSONArray ?: JSONArray()
        val list = (0 until raw.length()).map { raw.getJSONObject(it) }
        fences = list.map { LoopFences.place(town, it) }
        val ld = econ("fenceLeads", JSONArray(list), people, town.seed) as? JSONArray ?: JSONArray()
        leads = (0 until ld.length()).map { ld.getJSONObject(it) }
        quoteCache.clear()
    }

    /** Trust of [fence] in the player, 0..1. */
    fun trustOf(fence: LoopFence): Double = fenceTrust[fence.personId] ?: 0.0

    /** The lines about fences [personId] knows of (their leads), for the dialogue's fact sheet. */
    private fun leadFacts(personId: String): List<String> = leads.filter { it.optString("sourceId") == personId }.mapNotNull { l -> fences.firstOrNull { it.personId == l.optString("fenceId") }?.let { LoopFences.leadFact(it) } }

    /** What [fence] would pay for [item] now (`fenceDeal`, heat from [LoopFences.heat]); cached per trust and heat. */
    fun fenceQuote(fence: LoopFence, item: LoopItem): FenceQuote {
        val heat = LoopFences.heat(item, day)
        val trust = trustOf(fence)
        return quoteCache.getOrPut("${fence.personId}|${item.key}|$heat|$trust") {
            val d = econ("fenceDeal", fence.json, item.key, town.year, heat, trust) as? JSONObject
            when {
                d == null -> FenceQuote(false, 0, "no offer", "no_offer", heat)
                d.optBoolean("accepted") -> d.optInt("offerCents").let { FenceQuote(true, it, money(it), null, heat) }
                else -> d.optString("reason").let { r -> FenceQuote(false, 0, when (r) { "category" -> "not their line"; "too_hot" -> "too hot"; else -> "no offer" }, r, heat) }
            }
        }
    }

    // ------------------------------------------------------------------ the era economy (src/economy via simCall "economy.*")

    private val moneyText = HashMap<Int, String>()
    private val pawnCache = HashMap<String, Int>()
    private var indexCache: Double? = null

    private fun econ(method: String, vararg args: Any): Any = org.json.JSONTokener(call("economy.$method", *args)).nextValue()

    /** [cents] as the era writes it (`formatMoney`: "23c", "$29.95"); cached. */
    fun money(cents: Int): String = moneyText.getOrPut(cents) { econ("formatMoney", cents, town.year) as? String ?: "\$%d.%02d".format(cents / 100, cents % 100) }

    /** The town's year's prices as a share of 2025's (`priceIndex`). */
    val priceIndex: Double get() = indexCache ?: ((econ("priceIndex", town.year) as? Number)?.toDouble() ?: 1.0).also { indexCache = it }

    /** What the player arrives with: about $60 of 2025 money in the era's cents. */
    fun startingCash(): Int = max(200, (LoopItems.START_CASH_2025 * priceIndex).roundToInt())

    /** [dollars] of 2025 money (a fine from the facade) in the era's cents. */
    private fun eraCents(dollars: Int): Int = max(if (dollars > 0) 1 else 0, (dollars * 100 * priceIndex).roundToInt())

    /** The facade works in whole dollars (item value, pay): cents rounded to dollars, at least 1. */
    private fun dollars(cents: Int): Int = max(1, (cents / 100.0).roundToInt())

    /** Each shop place's `ShopStock` (economy.openStock, plain JSON), opened the first time it is looked at; saved with the loop. */
    private val stocks = HashMap<String, JSONObject>()
    /** The part of a day the clock has moved since the last whole day passed on to `restock`. */
    private var dayFrac = 0.0

    private fun stockSeed(place: LoopPlace) = "${town.seed}|${place.id}"

    private fun stockState(place: LoopPlace): JSONObject? = stocks[place.id] ?: (econ("openStock", LoopItems.econKind(place.shopKind), town.year, stockSeed(place)) as? JSONObject)?.also { stocks[place.id] = it }

    /** [place]'s shelves now (its `ShopStock` entries, sold-out ones with quantity 0). */
    fun stockFor(place: LoopPlace): List<StockItem> {
        val a = stockState(place)?.optJSONArray("entries") ?: return emptyList()
        return (0 until a.length()).map { k ->
            val e = a.getJSONObject(k)
            StockItem(e.getString("itemId"), e.getString("label"), e.getInt("priceCents"), e.optBoolean("serial"), e.optInt("quantity", 0), money(e.getInt("priceCents")))
        }
    }

    private fun quantityIn(stock: JSONObject, key: String): Int =
        stock.optJSONArray("entries")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("itemId") == key }?.optInt("quantity", 0) } ?: 0

    /** Takes one [key] off [place]'s shelf (`economy.take`); false (shelf unchanged) when it is sold out. */
    private fun takeFromShelf(place: LoopPlace, key: String): Boolean {
        val st = stockState(place) ?: return false
        val before = quantityIn(st, key)
        if (before <= 0) return false
        val next = econ("take", st, key, 1) as? JSONObject ?: return false
        if (quantityIn(next, key) >= before) return false
        stocks[place.id] = next
        return true
    }

    /** Moves every known shop's stock on by the whole days [hours] of the clock came to (`economy.restock`), the rest kept for later. */
    private fun restockFor(hours: Double) {
        dayFrac += hours / 24.0
        val whole = floor(dayFrac + 1e-9).toInt()
        if (whole <= 0) return
        dayFrac = max(0.0, dayFrac - whole)
        for (place in town.places) {
            val st = stocks[place.id] ?: continue
            (econ("restock", st, whole, town.year, stockSeed(place)) as? JSONObject)?.let { stocks[place.id] = it }
        }
    }

    /** What the pawn shop pays for [key] (`pawnOffer`; hot: stolen), 0 when it won't take it; cached. */
    private fun pawnOfferFor(key: String, hot: Boolean): Int = pawnCache.getOrPut("$key|$hot") { (econ("pawnOffer", key, town.year, hot) as? Number)?.toInt() ?: 0 }

    /** The pawn shop's price for [item] (0: they don't buy it). */
    fun pawnPrice(item: LoopItem): Int = pawnOfferFor(item.key, item.stolen)

    /** What the back-room buyer would pay for [item] whatever its history (`fenceOffer`); null when not in the era's catalogue. */
    fun fenceOffer(item: LoopItem): Int? = (econ("fenceOffer", item.key, town.year) as? Number)?.toInt()

    // ------------------------------------------------------------------ the facade

    /** `TimerpgSim[method](...args)` through bridge.ts simCall: objects are passed as JSON text. */
    fun call(method: String, vararg args: Any): String {
        val req = JSONObject().put("method", method).put("args", JSONArray().also { a -> for (x in args) a.put(x) })
        val t0 = System.nanoTime()
        val out = js.call("simCall", req.toString())
        val ms = (System.nanoTime() - t0) / 1e6
        timings[method] = ms to max(ms, timings[method]?.second ?: 0.0)
        return out
    }

    private fun event(name: String, detail: JSONObject) {
        runCatching { onEvent?.invoke(name, detail) }
    }

    private fun say(line: String) {
        messages.addLast("D$day %02d:%02d  ".format((hour.toInt()) % 24, ((hour - floor(hour)) * 60).toInt()) + line)
        while (messages.size > 12) messages.removeFirst()
    }

    /** Starts a new game in the town at [startHour] of day 1, with the era's starting cash. */
    fun init(startHour: Double, appearance: JSONObject = LoopTown.defaultAppearance()): JSONObject {
        val init = town.initJson(startHour, appearance = appearance)
        val res = JSONObject(call("init", init))
        day = res.optInt("day", 1); hour = res.optDouble("hour", startHour)
        cash = startingCash()
        items.clear(); nextItem = 1
        attitudes.clear(); facts.clear(); stocks.clear(); dayFrac = 0.0
        fenceTrust.clear(); tips.clear(); buildFences()
        rental = null; roomItems.clear(); lastRentStatus = null; arrearsKey = ""
        say("Arrived in ${town.townName} with ${money(cash)}.")
        event("init", JSONObject(res.toString()).put("cash", cash).put("places", town.places.count { it.kind != "home" }).put("ms", timings["init"]?.first))
        refreshPolice()
        publish()
        return res
    }

    /** Gives (or takes, when negative) [cents]: a debug and test hook; the game earns money by working or crime. */
    fun addCash(cents: Int, why: String = "debug") { cash = max(0, cash + cents); event("cash", JSONObject().put("delta", cents).put("why", why)); publish() }

    /** The whole loop as one JSON string: the facade's sim, the wallet and the inventory. */
    fun save(): String {
        val o = JSONObject().put("version", 1).put("town", town.seed).put("sim", call("save"))
            .put("cash", cash).put("nextItem", nextItem).put("items", JSONArray().also { a -> for (i in items) a.put(itemJson(i)) })
            .put("stocks", JSONObject().also { m -> for ((k, v) in stocks) m.put(k, v) }).put("dayFrac", dayFrac)
            .put("fences", JSONArray(fences.map { it.json })).put("leads", JSONArray(leads))
            .put("fenceTrust", JSONObject().also { m -> for ((k, v) in fenceTrust) m.put(k, v) }).put("tips", JSONArray(tips))
        rental?.let { r -> o.put("rental", JSONObject().put("kind", r.kind).put("startDay", r.startDay).put("paidCents", r.paidCents).put("nights", r.nights).put("lastNight", r.lastNight)) }
        o.put("roomItems", JSONArray().also { a -> for (i in roomItems) a.put(itemJson(i)) })
        return o.toString()
    }

    /** Puts back a game [save]d before. */
    fun load(saved: String) {
        val o = JSONObject(saved)
        require(o.optString("town") == town.seed) { "the save is for ${o.optString("town")}, not ${town.seed}" }
        val res = JSONObject(call("load", o.getString("sim")))
        day = res.optInt("day", 1); hour = res.optDouble("hour", 8.0)
        cash = o.optInt("cash", 0); nextItem = o.optInt("nextItem", 1)
        items.clear()
        o.optJSONArray("items")?.let { a -> for (k in 0 until a.length()) items += itemOf(a.getJSONObject(k)) }
        attitudes.clear(); facts.clear(); stocks.clear()
        o.optJSONObject("stocks")?.let { m -> for (k in m.keys()) stocks[k] = m.getJSONObject(k) }
        dayFrac = o.optDouble("dayFrac", 0.0)
        fenceTrust.clear(); tips.clear()
        o.optJSONObject("fenceTrust")?.let { m -> for (k in m.keys()) fenceTrust[k] = m.getDouble(k) }
        o.optJSONArray("tips")?.let { a -> for (k in 0 until a.length()) tips += a.getJSONObject(k) }
        val sf = o.optJSONArray("fences")
        if (sf != null) {
            fences = (0 until sf.length()).map { LoopFences.place(town, sf.getJSONObject(it)) }
            leads = o.optJSONArray("leads")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            quoteCache.clear()
        } else buildFences() // (a save from before the fences: the town's are the same ones, from its seed)
        rental = o.optJSONObject("rental")?.let { Rental(it.getString("kind"), it.getInt("startDay"), it.getInt("paidCents"), it.optInt("nights"), it.optInt("lastNight")) }
        roomItems.clear()
        o.optJSONArray("roomItems")?.let { a -> for (k in 0 until a.length()) roomItems += itemOf(a.getJSONObject(k)) }
        arrearsKey = ""; lastRentStatus = rentArrears()?.optString("status")
        say("Back in ${town.townName}.")
        event("load", JSONObject().put("day", day).put("hour", hour).put("cash", cash).put("items", items.size).put("ms", timings["load"]?.first))
        refreshPolice()
        publish()
    }

    // ------------------------------------------------------------------ the clock

    /**
     * Runs the sim on by [hours] (gossip, reports, the police's plans, cooling off: `advance`). Attitudes that changed
     * are looked up again; a new day's gossip can change what anyone knows, so then every cached fact goes.
     */
    fun advance(hours: Double, why: String): JSONObject? {
        if (hours <= 1e-4) return null
        val res = JSONObject(call("advance", hours))
        day = res.optInt("toDay", day); hour = res.optDouble("toHour", hour)
        lastAdvance = res
        restockFor(hours)
        val changes = res.optJSONArray("attitudeChanges") ?: JSONArray()
        // (Jeff's idea: a person's facts are kept until their attitude changes; a new day's gossip can add to what someone
        // already wary knows without changing their stance, so then everyone cached is looked up again too)
        val again = HashSet<String>()
        if (res.optInt("daysElapsed", 0) > 0) again += attitudes.keys
        for (k in 0 until changes.length()) again += changes.getJSONObject(k).optString("personId")
        refreshAttitudes(again)
        for (k in 0 until changes.length()) changes.getJSONObject(k).let { c ->
            say("${c.optString("name")}: ${c.optString("previousStance")} -> ${c.optString("newStance")}")
        }
        res.optJSONArray("newPoliceCases")?.let { if (it.length() > 0) say("The police opened ${it.length()} case(s).") }
        event("advance", JSONObject().put("why", why).put("hours", hours).put("toDay", day).put("toHour", hour)
            .put("attitudeChanges", changes).put("newPoliceCases", res.optJSONArray("newPoliceCases"))
            .put("pawnLeads", res.optJSONArray("pawnLeadsMatched")).put("plans", res.optJSONArray("policePlans")?.length() ?: 0)
            .put("ms", timings["advance"]?.first))
        refreshPolice()
        publish()
        return res
    }

    /**
     * The game's clock reads [gameHour] (hour of day): the sim follows forward. On the hour ([exact] false) it moves
     * only once a whole hour has been crossed; before an action ([exact]) to the minute. A clock set back (the debug
     * slider) is not followed: the sim keeps its time until the game's clock passes it. [wrap]: a clock that reads
     * earlier than the sim's has gone on past midnight (a train ride's arrival).
     */
    fun clockTo(gameHour: Double, exact: Boolean, wrap: Boolean = false): JSONObject? {
        var d = gameHour - hour
        if (d < -1e-6) { if (wrap) d += 24.0 else return null }
        if (!exact && floor(hour + d) <= floor(hour)) return null
        return advance(d, if (exact) "action" else "hour")
    }

    fun waitHours(h: Double): LoopOutcome {
        advance(h, "wait")
        say("Waited ${fmt(h)} h.")
        publish()
        return LoopOutcome(true, "You wait ${fmt(h)} hours.", hour, h)
    }

    /** Sleeps till [wake] o'clock (the next one). */
    fun sleepUntil(wake: Double = 7.0): LoopOutcome {
        var h = wake - hour
        if (h <= .25) h += 24.0
        advance(h, "sleep")
        say("Slept till ${fmt(wake)}:00.")
        publish()
        return LoopOutcome(true, "You sleep till morning.", hour, h)
    }

    // ------------------------------------------------------------------ attitudes

    /** How [personId] feels about the player (`attitude`), cached; [fresh] asks the facade again. */
    fun attitude(personId: String, fresh: Boolean = false): JSONObject {
        if (!fresh) attitudes[personId]?.let { return it }
        val a = JSONObject(call("attitude", personId))
        attitudes[personId] = a
        val known = a.optJSONArray("facts")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty()
        // (someone who would refuse the player or report them does not share who buys stolen goods)
        val shares = a.optString("stance") !in setOf("refuse", "report_on_sight")
        facts[personId] = if (shares) known + leadFacts(personId) else known
        return a
    }

    /** Looks [ids]' attitudes up again (their facts for the dialogue stay current). */
    private fun refreshAttitudes(ids: Collection<String>) {
        for (id in ids.toList()) { attitudes.remove(id); if (town.personById.containsKey(id)) attitude(id) else facts.remove(id) }
    }

    /** Looks up the facts of [ids] not cached yet (before a conversation: their dialogue's fact sheet). */
    fun prefetch(ids: Collection<String>) { for (id in ids) if (!facts.containsKey(id) && town.personById.containsKey(id)) attitude(id) }

    /** Whether [place]'s keeper serves the player: null if they do (and a wary line, if they watch them), else why not. */
    private fun refusal(place: LoopPlace, what: String): Pair<String?, String?> {
        val k = place.keeperId ?: return null to null
        val a = attitude(k, fresh = true)
        val name = a.optString("name", k).substringBefore(' ')
        val why = a.optJSONArray("facts")?.let { f -> (0 until f.length()).map { f.getString(it) }.lastOrNull { !it.startsWith("You know this person") } }
        event("attitude", JSONObject().put("personId", k).put("place", place.id).put("stance", a.optString("stance")).put("trust", a.optDouble("trust")).put("for", what))
        return when (a.optString("stance")) {
            "refuse", "report_on_sight" -> "$name won't $what you." + (why?.let { " (${toPlayer(it, name)})" } ?: "") to null
            "wary" -> null to "$name keeps an eye on you."
            else -> null to null
        }
    }

    // ------------------------------------------------------------------ the actions

    private fun newItem(place: LoopPlace, s: StockItem, stolen: Boolean, crimeId: String?, serial: String?): LoopItem {
        val n = nextItem++
        return LoopItem("item:${s.key}:$n", s.key, s.label, s.priceCents,
            serial ?: if (s.serial) "SN-%06d".format(Math.floorMod(("${town.seed}|${s.key}|$n").hashCode(), 1_000_000)) else null,
            stolen, crimeId, place.id, day)
    }

    private fun itemPayload(i: LoopItem) = JSONObject().put("id", i.id).put("label", i.label).put("value", dollars(i.value)).also { o ->
        i.serial?.let { o.put("serialNumber", it) }; i.crimeId?.let { o.put("stolenFromCrimeId", it) }
    }

    private fun act(event: JSONObject, sceneIn: LoopScene): JSONObject {
        val scene = town.placeById[event.optString("placeId")]?.let { staffedScene(it, sceneIn) } ?: sceneIn
        scene.put(event)
        val res = JSONObject(call("act", event))
        lastAct = res.put("bystanders", scene.bystanders.size).put("inside", scene.inside)
        // (the witnesses' and the clerk's attitudes may have changed)
        val again = HashSet<String>()
        res.optJSONArray("witnessedBy")?.let { w -> for (k in 0 until w.length()) again += w.getJSONObject(k).optString("personId") }
        for (k in listOf("clerkId", "employerId", "ownerId")) event.optString(k).takeIf { it.isNotEmpty() }?.let { again += it }
        // (a shift: the coworkers learn the player's name too)
        if (event.optString("kind") == "work_shift") town.people.filter { it.workplacePlaceId == event.optString("placeId") }.forEach { again += it.id }
        refreshAttitudes(again)
        event("act", JSONObject().put("kind", event.optString("kind")).put("place", event.optString("placeId")).put("summary", res.optString("summary"))
            .put("crimeId", res.opt("crimeId")).put("witnessedBy", res.optJSONArray("witnessedBy")).put("bystanders", scene.bystanders.size)
            .put("sighted", scene.bystanders.count { it.los }).put("inside", scene.inside).put("light", scene.light).put("ms", timings["act"]?.first))
        return res
    }

    // ------------------------------------------------------------------ opening hours (economy.hoursOf / isOpen / staffed)

    /** The weekday (0 = Sunday) of in-game [d]: day 1 is [LoopTown.START_WEEKDAY], the facade's `startDayOfWeek`. */
    fun weekdayOf(d: Int): Int = Math.floorMod(LoopTown.START_WEEKDAY + d - 1, 7)

    /** The hour caches are kept for one day only (cleared when the day changes); all on the sim's thread. */
    private var hoursDay = -1
    private val openCache = HashMap<String, Boolean>()
    private val staffCache = HashMap<String, Int>()
    private val spanCache = HashMap<String, List<IntArray>>()

    private fun hoursSeed(place: LoopPlace) = "${town.seed}|${place.id}"
    private fun alwaysOpen(place: LoopPlace) = place.kind == "police_station" || place.kind == "home"

    /** [place]'s opening spans on in-game day [d] (`hoursOf`; no calendar date, so no holidays), as [open, close) hours; cached per weekday. */
    private fun spansOn(place: LoopPlace, d: Int): List<IntArray> {
        if (alwaysOpen(place)) return listOf(intArrayOf(0, 24))
        val wd = weekdayOf(d)
        return spanCache.getOrPut("${place.id}|$wd") {
            val a = econ("hoursOf", LoopItems.econKind(place.shopKind), town.year, wd, hoursSeed(place)) as? JSONArray ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it).let { o -> intArrayOf(o.optInt("open"), o.optInt("close")) } }
        }
    }

    private fun syncHoursDay() { if (hoursDay != day) { hoursDay = day; openCache.clear(); staffCache.clear() } }

    /** Is [place] open at the sim's hour now (`economy.isOpen`, cached per place per in-game hour)? */
    fun isOpenNow(place: LoopPlace): Boolean {
        if (alwaysOpen(place)) return true
        syncHoursDay()
        val h = floor(hour).toInt() % 24
        return openCache.getOrPut("${place.id}|$h") {
            econ("isOpen", LoopItems.econKind(place.shopKind), town.year, weekdayOf(day), h, hoursSeed(place)) as? Boolean ?: false
        }
    }

    /** How many people work in [place] this hour (`economy.staffed`: 0 shut, 1 at opening and late, more at the lunch rush); cached. */
    fun staffNow(place: LoopPlace): Int {
        if (alwaysOpen(place)) return place.staff.size.coerceAtLeast(1)
        syncHoursDay()
        val h = floor(hour).toInt() % 24
        return staffCache.getOrPut("${place.id}|$h") {
            (econ("staffed", LoopItems.econKind(place.shopKind), town.year, weekdayOf(day), h, hoursSeed(place)) as? Number)?.toInt() ?: 0
        }
    }

    private val dayNames = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private fun hourText(h: Int): String = when (h % 24) { 0 -> "midnight"; 12 -> "noon"; in 1..11 -> "$h a.m."; else -> "${h % 12} p.m." }

    /** The closing hour of the span [place] is open in now (0 when shut). */
    private fun closeNow(place: LoopPlace): Int {
        val h = floor(hour).toInt() % 24
        return spansOn(place, day).firstOrNull { h >= it[0] && h < it[1] }?.get(1) ?: 0
    }

    /** What the door reads: "Open 8 a.m.-6 p.m.", "Open 24 hours", "Closed Sundays". */
    private fun todayText(place: LoopPlace): String {
        val sp = spansOn(place, day)
        if (sp.isEmpty()) return "Closed ${dayNames[weekdayOf(day)]}s"
        return "Open " + sp.joinToString(", ") { if (it[0] == 0 && it[1] == 24) "24 hours" else "${hourText(it[0])}-${hourText(it[1])}" }
    }

    /** "Closed. Opens at 8 a.m." (later today), "Closed. Opens tomorrow at 8 a.m.", "Closed. Opens Monday at 10 a.m.". */
    fun closedLine(place: LoopPlace): String {
        val h = floor(hour).toInt() % 24
        spansOn(place, day).firstOrNull { it[0] > h }?.let { return "Closed. Opens at ${hourText(it[0])}" }
        for (k in 1..7) {
            val sp = spansOn(place, day + k)
            if (sp.isNotEmpty()) return "Closed. Opens ${if (k == 1) "tomorrow" else dayNames[weekdayOf(day + k)]} at ${hourText(sp[0][0])}"
        }
        return "Closed."
    }

    /** [place]'s hours for the HUD (see [PlaceHours]). */
    fun hoursOf(place: LoopPlace): PlaceHours {
        val open = isOpenNow(place)
        return PlaceHours(open, todayText(place), if (open) "" else closedLine(place), if (open) staffNow(place) else 0, if (open) closeNow(place) else 0)
    }

    /**
     * [scene] with the shop's people set to who works in [place] this hour (`economy.staffed`): the keeper plus staff - 1
     * clerks (townspeople who work there, then deterministic stand-ins from the passers so each is a real person who can
     * witness), set along the counter; nobody when it is shut. Outdoor scenes are left as they are.
     */
    fun staffedScene(place: LoopPlace, scene: LoopScene): LoopScene {
        if (!scene.inside || alwaysOpen(place)) return scene
        val n = staffNow(place)
        val keeper = scene.bystanders.firstOrNull { it.id == place.keeperId } ?: return scene
        val clerks = clerksOf(place)
        val k = VehicleWorld.U / 32f
        val out = ArrayList<LoopBystander>()
        if (n >= 1) out += keeper
        for (i in 0 until (n - 1).coerceAtMost(clerks.size)) {
            val dx = 60f * (i + 1) * k
            out += LoopBystander(clerks[i], keeper.x + dx, keeper.z, keeper.fx, keeper.fz, "working", true, keeper.distance + dx)
        }
        return LoopScene(scene.x, scene.z, out, scene.light, true, scene.appearance)
    }

    /** Who stands behind the counter besides the keeper: the town's people who work at [place], then stand-ins picked from the town by the place's id. */
    private fun clerksOf(place: LoopPlace): List<String> = clerkCache.getOrPut(place.id) {
        val own = town.people.filter { it.workplacePlaceId == place.id && it.id != place.keeperId }.map { it.id }
        val pool = town.people.filter { p -> town.places.none { it.kind != "home" && it.keeperId == p.id } && p.age in 16..65 && p.id !in own }.map { it.id }
        val h = place.id.hashCode()
        own + (0 until 3).mapNotNull { i -> pool.takeIf { it.isNotEmpty() }?.get(Math.floorMod(h + 7919 * i, pool.size)) }.distinct()
    }
    private val clerkCache = HashMap<String, List<String>>()

    private fun closed(place: LoopPlace) = closedLine(place)

    fun buy(place: LoopPlace, s: StockItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!isOpenNow(place)) return done(false, closed(place))
        val (no, wary) = refusal(place, "serve")
        if (no != null) return done(false, no)
        val price = s.priceCents
        if (cash < price) return done(false, "You can't afford ${s.label} (${money(price)}; you have ${money(cash)}).")
        if (!takeFromShelf(place, s.key)) return done(false, "Sold out.")
        val item = newItem(place, s, false, null, null)
        act(JSONObject().put("kind", "buy").put("placeId", place.id).put("item", itemPayload(item)).also { o -> place.keeperId?.let { o.put("clerkId", it) } }, scene)
        cash -= price
        items += item
        return done(true, "Bought ${s.label} for ${money(price)}.", wary = wary)
    }

    fun take(place: LoopPlace, s: StockItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!takeFromShelf(place, s.key)) return done(false, "Sold out.")
        // (a closed shop is a break-in: the facade's `enter` with the door shut and forced is a burglary of its own, recorded
        // before the theft; nobody is inside to see it, so its witnesses are whoever the street scene holds)
        val breakIn = if (!isOpenNow(place)) act(JSONObject().put("kind", "enter").put("placeId", place.id).put("isOpen", false).put("forcedEntry", true)
            .also { o -> place.keeperId?.let { o.put("ownerId", it) } }, scene) else null
        val proto = newItem(place, s, true, null, null)
        val res = act(JSONObject().put("kind", "take").put("subkind", "shoplift").put("placeId", place.id).put("item", itemPayload(proto))
            .also { o -> place.keeperId?.let { o.put("ownerId", it) } }, scene)
        val item = proto.copy(crimeId = res.optString("crimeId").ifEmpty { null }, serial = res.optString("stolenSerialNumber").ifEmpty { proto.serial })
        items += item
        val w = res.optJSONArray("witnessedBy") ?: JSONArray()
        val victim = (0 until w.length()).map { w.getJSONObject(it) }.firstOrNull { it.optBoolean("isVictim") }
        val line = when {
            breakIn != null && w.length() == 0 && (breakIn.optJSONArray("witnessedBy")?.length() ?: 0) == 0 -> "You break into ${place.name} and pocket ${s.label}. No one seemed to notice."
            victim != null -> "You pocket ${s.label}. ${victim.optString("name").substringBefore(' ')} saw that!"
            w.length() > 0 -> "You pocket ${s.label}. You feel eyes on you."
            else -> "You pocket ${s.label}. No one seemed to notice."
        }
        return done(true, line)
    }

    fun sell(place: LoopPlace, item: LoopItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (place.kind != "pawn_shop") return done(false, "${place.name} doesn't buy things.")
        if (!isOpenNow(place)) return done(false, closed(place))
        val (no, wary) = refusal(place, "deal with")
        if (no != null) return done(false, no)
        val price = pawnPrice(item)
        if (price <= 0) return done(false, "They don't take ${item.label}.")
        val res = act(JSONObject().put("kind", "sell").put("placeId", place.id).put("item", itemPayload(item)).also { o -> place.keeperId?.let { o.put("clerkId", it) } }, scene)
        cash += price
        items.removeAll { it.id == item.id }
        if (res.optBoolean("pawnTraceFlagged")) say("(the pawn ledger has ${item.label}: it is on file as stolen)")
        return done(true, "Sold ${item.label} for ${money(price)}." + if (item.stolen) " They didn't ask where it came from." else "", wary = wary)
    }

    /**
     * Sells [item] to [fence] (they stand at their spot between their hours): `fenceDeal` decides (category, heat from
     * [LoopFences.heat], the fence's trust in the player); an accepted offer is paid in cents, the item goes, trust rises
     * (saved per fence), and `fenceRisk` is rolled once with a deterministic key (town seed, fence, item id: item ids are
     * unique): on a hit the fence informs. The facade has no such report: a stolen item is then entered into the pawn
     * ledger as if sold at the pawn shop (`act` sell: the police's pawn leads pick it up); otherwise only a note
     * (debug panel, PlayLog "fence_tip"). A fence deal itself leaves no pawn entry (docs/ECONOMY.md).
     */
    fun sellToFence(fence: LoopFence, item: LoopItem, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!fence.atHours(hour)) return done(false, "${fence.first} isn't here now.")
        val q = fenceQuote(fence, item)
        if (!q.accepted) return done(false, "${fence.first} won't take ${item.label}: ${q.text}.")
        val d = econ("fenceDeal", fence.json, item.key, town.year, q.heat, trustOf(fence)) as JSONObject
        cash += q.cents
        items.removeAll { it.id == item.id }
        fenceTrust[fence.personId] = d.optDouble("trustAfter", trustOf(fence))
        quoteCache.clear()
        val risk = (econ("fenceRisk", fence.json, town.year) as? Number)?.toDouble() ?: 0.0
        val roll = LoopFences.unit(town.seed, "fence_risk", fence.personId, item.id)
        val hit = roll < risk
        val line = "Sold ${item.label} to ${fence.first} for ${money(q.cents)}."
        if (hit) {
            val tip = JSONObject().put("day", day).put("fence", fence.personId).put("item", item.id).put("stolen", item.stolen).put("risk", risk).put("roll", roll)
            tips += tip
            say("(${fence.first} tipped the police off about ${item.label})")
            val pawn = town.places.firstOrNull { it.kind == "pawn_shop" }
            if (item.stolen && pawn != null) {
                act(JSONObject().put("kind", "sell").put("placeId", pawn.id).put("item", itemPayload(item)), LoopScene(pawn.doorX, pawn.doorZ, emptyList(), null, false))
            }
        }
        event("fence_deal", JSONObject().put("fence", fence.personId).put("item", item.key).put("cents", q.cents).put("heat", q.heat).put("trust", fenceTrust[fence.personId])
            .put("risk", risk).put("roll", roll).put("tipped", hit))
        return done(true, line)
    }

    /** A shift at [place]: up to 4 hours (not past closing), paid at the end; the clock moves on by it. */
    fun work(place: LoopPlace, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!isOpenNow(place)) return done(false, closed(place))
        val (no, wary) = refusal(place, "take on")
        if (no != null) return done(false, no)
        val hours = min(4.0, floor(closeNow(place) - hour))
        if (hours < 1.0) return done(false, "${place.name} closes soon: no shift today.")
        val w = econ("wage", LoopItems.job(place.shopKind), town.year) as? JSONObject
        val hourly = w?.optInt("hourlyCents", 0) ?: 0
        val pay = max(1, (hours * hourly).roundToInt())
        val task = LoopItems.task(place.shopKind)
        act(JSONObject().put("kind", "work_shift").put("placeId", place.id).put("hours", hours).put("pay", dollars(pay)).put("taskLabel", task)
            .also { o -> place.keeperId?.let { o.put("employerId", it) } }, scene)
        advance(hours, "work")
        cash += pay
        return done(true, "You worked ${hours.toInt()} hours $task at ${place.name}: ${money(pay)}.", hours, wary = wary)
    }

    private fun done(ok: Boolean, line: String, hours: Double = 0.0, moveTo: LoopPlace? = null, wary: String? = null, moveToStop: LoopStop? = null): LoopOutcome {
        say(line + (wary?.let { " $it" } ?: ""))
        publish()
        return LoopOutcome(ok, line, hour, hours, moveTo, wary, moveToStop)
    }


    // ------------------------------------------------------------------ living costs (C022: economy.lodgingOf / dueOn / arrears / dailyBudget)

    /** The room the player rents: [kind] of lodging, the day it began, what has been paid (deposit and rent, oldest first), the nights slept in it. */
    private class Rental(val kind: String, val startDay: Int, var paidCents: Int, var nights: Int = 0, var lastNight: Int = 0)
    private var rental: Rental? = null
    /** Items left in the room: held (not given back) while the player is locked out. */
    private val roomItems = ArrayList<LoopItem>()
    private var lastRentStatus: String? = null
    private val termsCache = HashMap<String, JSONObject>()
    private var arrearsKey = ""; private var arrearsCache: JSONObject? = null
    private var livingCache: String? = null

    /** The era's terms of a [kind] of lodging (`lodgingOf`), null when there are none in the town's year. */
    fun lodgingTerms(kind: String = "boarding_house"): JSONObject? = termsCache[kind] ?: (econ("lodgingOf", kind, town.year) as? JSONObject)?.also { termsCache[kind] = it }

    /** Where rent stands today (`arrears`), cached per day and payment; null without a room. */
    fun rentArrears(): JSONObject? {
        val r = rental ?: return null
        val t = lodgingTerms(r.kind) ?: return null
        val key = "$day|${r.paidCents}|${r.startDay}"
        if (key == arrearsKey) return arrearsCache
        val a = econ("arrears", JSONObject().put("lodging", t).put("startDay", r.startDay).put("day", day).put("paidCents", r.paidCents)) as? JSONObject
        arrearsKey = key; arrearsCache = a
        return a
    }

    val renting: Boolean get() = rental != null
    val lockedOut: Boolean get() = rentArrears()?.optBoolean("belongingsHeld") == true
    val nightsInRoom: Int get() = rental?.nights ?: 0
    val itemsInRoom: List<LoopItem> get() = roomItems.toList()

    /** The place's one-line summary of what most people live on (`dailyBudget('working')`), cached. */
    fun livingLine(): String = livingCache ?: ((econ("dailyBudget", "working", town.year) as? JSONObject)?.optInt("weeklyLivingCents", 0)?.takeIf { it > 0 }
        ?.let { "Most people here live on about ${money(it)} a week." } ?: "").also { livingCache = it }

    /** Rent due now to take the room (`dueOn` on the first day: deposit and the first period), in cents; 0 without terms. */
    fun roomCost(): Int = lodgingTerms()?.let { (econ("dueOn", it, day, day) as? Number)?.toInt() } ?: 0

    private fun lodgingName() = town.lodging?.name ?: "the boarding house"

    /** "Rent a room": pays the deposit and the first period (`lodgingOf`, `dueOn`); the rent then falls due every period. */
    fun rentRoom(gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (rental != null) return done(false, "You already rent a room at ${lodgingName()}.")
        val t = lodgingTerms() ?: return done(false, "There are no rooms to let in ${town.year}.")
        val due = roomCost()
        if (cash < due) return done(false, "A room costs ${money(due)} now (${money(t.getInt("rentCents"))} every ${t.getInt("periodDays")} days); you have ${money(cash)}.")
        cash -= due
        rental = Rental(t.getString("kind"), day, due)
        lastRentStatus = "current"
        event("rent", JSONObject().put("what", "rented").put("day", day).put("paid", due).put("kind", t.getString("kind")))
        return done(true, "You rent a room at ${lodgingName()} for ${money(due)}; the next rent is due in ${t.getInt("periodDays")} days.")
    }

    /** Pays what is owed (`arrears`): the oldest periods first. */
    fun payRent(gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val r = rental ?: return done(false, "You have no room to pay for.")
        val owed = rentArrears()?.optInt("owedCents") ?: 0
        if (owed <= 0) return done(false, "Nothing owed: the rent is paid up.")
        if (cash < owed) return done(false, "You owe ${money(owed)} and have ${money(cash)}.")
        cash -= owed; r.paidCents += owed
        arrearsKey = ""
        event("rent", JSONObject().put("what", "paid").put("day", day).put("paid", owed))
        return done(true, "You pay ${money(owed)} rent.")
    }

    /** Sleeps the night in the rented room (till 7): it restores like a bed (only recorded for now); not while locked out. */
    fun sleepInRoom(): LoopOutcome {
        val r = rental ?: return done(false, "You have no room to sleep in.")
        if (lockedOut) return done(false, "The landlady has locked you out: ${money(rentArrears()?.optInt("owedCents") ?: 0)} is owed.")
        val o = sleepUntil(7.0)
        r.nights++; r.lastNight = day
        event("rent", JSONObject().put("what", "slept").put("day", day).put("nights", r.nights))
        say("(slept in the rented room: night ${r.nights})")
        publish()
        return LoopOutcome(true, "You sleep in your room at ${lodgingName()}.", hour, o.hoursPassed)
    }

    /** Leaves [item] in the room (held for the player while they pay; kept by the landlady when locked out). */
    fun stash(item: LoopItem): LoopOutcome {
        if (rental == null) return done(false, "You have no room to keep things in.")
        if (lockedOut) return done(false, "The room is locked: you can't leave anything there.")
        items.removeAll { it.id == item.id }; roomItems += item
        return done(true, "You leave ${item.label} in your room.")
    }

    /** Takes [item] back out of the room; not while locked out (the things are held until the rent is paid). */
    fun retrieve(item: LoopItem): LoopOutcome {
        if (lockedOut) return done(false, "The landlady is holding your things until you pay ${money(rentArrears()?.optInt("owedCents") ?: 0)}.")
        if (!roomItems.removeAll { it.id == item.id }) return done(false, "That is not in your room.")
        items += item
        return done(true, "You take ${item.label} out of your room.")
    }

    /** What the HUD says of the room, or null without one. */
    private fun rentView(): RentView? {
        val r = rental ?: return null
        val t = lodgingTerms(r.kind) ?: return null
        val a = rentArrears() ?: return null
        val status = a.optString("status"); val owed = a.optInt("owedCents"); val late = a.optInt("daysLate")
        val period = t.getInt("periodDays")
        val next = r.startDay + ((day - r.startDay) / period + 1) * period
        val nextAmt = (econ("dueOn", t, r.startDay, next) as? Number)?.toInt() ?: t.getInt("rentCents")
        val line = when (status) {
            "due" -> "Rent due today: ${money(owed)} for your room."
            "warning" -> "Rent is $late day${if (late == 1) "" else "s"} late: ${money(owed)}. You will be locked out in ${(7 - late).coerceAtLeast(0)} day${if (7 - late == 1) "" else "s"}."
            "locked_out" -> "Locked out of your room: ${money(owed)} owed. Your things are held."
            else -> null
        }
        return RentView(status, owed, money(owed), "${money(t.getInt("rentCents"))} every $period days", "${money(nextAmt)} on day $next", line, status == "locked_out", r.nights, roomItems.toList())
    }

    /** Announces a change in how the rent stands (a new day can turn due into late, late into locked out). */
    private fun rentTick() {
        if (rental == null) return
        val st = rentArrears()?.optString("status") ?: return
        if (st == lastRentStatus) return
        lastRentStatus = st
        when (st) {
            "due" -> say("Your rent is due: ${money(rentArrears()!!.optInt("owedCents"))}.")
            "warning" -> say("Your rent is late (${rentArrears()!!.optInt("daysLate")} day(s)).")
            "locked_out" -> say("You are locked out of your room; ${roomItems.size} item(s) of yours are held.")
        }
        event("rent", JSONObject().put("what", st).put("day", day).put("owed", rentArrears()?.optInt("owedCents")).put("held", roomItems.size))
    }

    // ------------------------------------------------------------------ transit (C024: transit.fare / departures / travelTime)

    /** The service in the town's year: a horsecar, then the streetcar, then the city bus; null when `fare` knows none. */
    val transitMode: String? by lazy {
        val m = when { town.year < 1888 -> "horsecar"; town.year < 1930 -> "streetcar"; else -> "city_bus" }
        m.takeIf { (call("transit.fare", m, town.year, 1.0).toDoubleOrNull() != null) }
    }
    val transitWord: String get() = when (transitMode) { "horsecar" -> "horsecar"; "streetcar" -> "streetcar"; else -> "bus" }

    private fun tr(method: String, vararg args: Any): Any = org.json.JSONTokener(call("transit.$method", *args)).nextValue()
    private val fareCache = HashMap<String, Int>()
    private val timeCache = HashMap<String, Int>()
    private val depCache = HashMap<String, List<Int>>()

    private fun km(a: LoopStop, b: LoopStop): Double = (kotlin.math.hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble()) / VehicleWorld.U / 1000.0).coerceAtLeast(0.05)
    private fun kmKey(d: Double) = Math.round(d * 1000).toString()

    /** What a ride of [km] costs (`fare`, cents). */
    private fun fareCents(mode: String, km: Double): Int = fareCache.getOrPut("$mode|${kmKey(km)}") { (tr("fare", mode, town.year, km) as? Number)?.toInt() ?: 0 }
    /** Minutes on board (`travelTime`, the mode's wait at the stop included). */
    private fun rideMinutes(mode: String, km: Double): Int = timeCache.getOrPut("$mode|${kmKey(km)}") { (tr("travelTime", mode, town.year, km) as? Number)?.toInt() ?: 0 }

    /** [line]'s remaining departures (minute of day) on in-game day [d] from [fromHour] (`departures`, the weekday of that day); cached. */
    private fun departuresOn(line: LoopLine, mode: String, d: Int, fromHour: Double): List<Int> {
        val h = fromHour.coerceIn(0.0, 23.999)
        return depCache.getOrPut("${line.id}|$d|${(h * 60).toInt()}") {
            if (depCache.size > 400) depCache.clear()
            val a = tr("departures", JSONObject().put("id", line.id).put("mode", mode), town.year, weekdayOf(d), h, town.seed) as? JSONArray ?: JSONArray()
            (0 until a.length()).map { a.getInt(it) }
        }
    }

    /** The next departure of [line] from now: (days from today, minute of day), the first of tomorrow when today's service is over. */
    private fun nextDeparture(line: LoopLine, mode: String): Pair<Int, Int>? {
        departuresOn(line, mode, day, hour).firstOrNull()?.let { return 0 to it }
        return departuresOn(line, mode, day + 1, 0.0).firstOrNull()?.let { 1 to it }
    }

    private fun clockText(minute: Int): String {
        val m = Math.floorMod(minute, 1440); val h = m / 60
        return "${if (h % 12 == 0) 12 else h % 12}:%02d ${if (h < 12) "a.m." else "p.m."}".format(m % 60)
    }

    private fun lineOf(stop: LoopStop): LoopLine? = town.lines.firstOrNull { it.street == stop.line }

    /** What each stop's panel shows now: the next departures and the fare, minutes and text of each ride along the line. */
    private fun transitInfo(): Map<String, StopInfo> {
        val mode = transitMode
        val out = HashMap<String, StopInfo>()
        for (line in town.lines) for (stop in line.stops) {
            if (mode == null) { out[stop.id] = StopInfo(stop, "", emptyList(), emptyList(), "No streetcar or bus runs in ${town.year}."); continue }
            val today = departuresOn(line, mode, day, hour)
            val deps = if (today.isNotEmpty()) today.take(4).map { clockText(it) }
                else departuresOn(line, mode, day + 1, 0.0).take(3).map { "tomorrow " + clockText(it) }
            val rides = line.stops.filter { it.id != stop.id }.map { to ->
                val d = km(stop, to); val c = fareCents(mode, d)
                RideOption(to.id, to.name, c, money(c), rideMinutes(mode, d))
            }
            out[stop.id] = StopInfo(stop, transitWord, deps, rides, if (deps.isEmpty()) "No service." else null)
        }
        return out
    }

    /**
     * Rides the line from [from] to [to]: pays the fare (`fare`), waits for the next departure (`departures`, the
     * weekday of the loop's clock as the opening hours use) and rides `travelTime`: the clock moves by both in one
     * advance, then the player is taken to [to].
     */
    fun ride(from: LoopStop, to: LoopStop, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val mode = transitMode ?: return done(false, "No streetcar or bus runs in ${town.year}.")
        val line = lineOf(from) ?: return done(false, "There is no line here.")
        if (to.line != from.line || to.id == from.id) return done(false, "That line does not go there.")
        val d = km(from, to); val fare = fareCents(mode, d)
        if (cash < fare) return done(false, "The fare is ${money(fare)}; you have ${money(cash)}.")
        val dep = nextDeparture(line, mode) ?: return done(false, "No service.")
        val waitH = (dep.first * 24 + dep.second / 60.0 - hour).coerceAtLeast(0.0)
        val minutes = rideMinutes(mode, d)
        cash -= fare
        val total = waitH + minutes / 60.0
        advance(total, "transit")
        val arrive = (hour * 60).toInt()
        event("ride", JSONObject().put("from", from.id).put("to", to.id).put("fare", fare).put("waitMin", Math.round(waitH * 60)).put("minutes", minutes).put("arriveDay", day).put("arriveHour", hour))
        return done(true, "You take the ${clockText(dep.second)} $transitWord to ${to.name}: ${money(fare)}, waited ${Math.round(waitH * 60)} min, rode $minutes min. Arrived ${clockText(arrive)}", total, moveToStop = to)
    }

    /** What a train ticket for [km] costs (`transit.fare('railway')`, the cheapest class of the year), or null when there is no train service in the year. */
    fun railFare(km: Double): Int? {
        val cls = (tr("classesOf", town.year) as? JSONArray)?.takeIf { it.length() > 0 }?.getString(0) ?: return null
        return (tr("fare", "railway", town.year, km, cls) as? Number)?.toInt()
    }

    /** Buys the ticket for a trip of [km] at the depot's window: pays `railFare` (the ride then starts), or says why not. A year without train fares charges nothing. */
    fun buyRailTicket(km: Double): LoopOutcome {
        val f = railFare(km) ?: return done(true, "No fare is set for trains in ${town.year}: you board.")
        if (cash < f) return done(false, "The ticket costs ${money(f)}; you have ${money(cash)}.")
        cash -= f
        event("rail_ticket", JSONObject().put("km", km).put("fare", f))
        return done(true, "Ticket ${money(f)}.")
    }

    // ------------------------------------------------------------------ the police

    /** What the police know now (`policeView`): heat, open cases, the officers' plans. */
    fun refreshPolice(): JSONObject {
        val p = JSONObject(call("policeView", town.jurisdictionId))
        lastPolice = p
        return p
    }

    /** The officers' plans to act out (`policeView`'s officers): route node ids, assignment, reason. */
    val officerPlans: List<JSONObject> get() = lastPolice?.optJSONArray("officers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    /**
     * [officerId] sees the player [distanceM] away in [light] (`spotted`): watching them is shown; an approach or an
     * arrest is the arrest scene ([arrest]). Null when they ignore the player.
     */
    fun spotted(officerId: String, distanceM: Double, light: Double, gameHour: Double): LoopOutcome? {
        clockTo(gameHour, exact = true)
        val s = JSONObject(call("spotted", officerId, JSONObject().put("distance", Math.round(distanceM * 10) / 10.0).put("light", Math.round(light * 100) / 100.0)))
        lastSpot = s
        val action = s.optString("action")
        event("spotted", JSONObject().put("officerId", officerId).put("distance", distanceM).put("light", light).put("action", action)
            .put("recognition", s.optDouble("recognitionChance")).put("case", s.opt("matchedCaseId")).put("ms", timings["spotted"]?.first))
        return when (action) {
            "watch" -> done(true, "A police officer is watching you.")
            "approach_and_question", "arrest" -> arrest(officerId, action)
            else -> { publish(); null }
        }
    }

    /**
     * The arrest scene's outcome (`arrest`): the fine taken from the wallet (what there is), what was stolen taken
     * away, a night in the cell (the clock to the next morning) or the hours of jail; the player is taken to the
     * police station.
     */
    fun arrest(officerId: String?, why: String = "arrest"): LoopOutcome {
        val input = JSONObject().put("jurisdictionId", town.jurisdictionId).put("playerCash", max(0, (cash / 100.0 / priceIndex).roundToInt()))
            .put("carriedItemIds", JSONArray(items.map { it.id }))
        officerId?.let { input.put("officerId", it) }
        val r = JSONObject(call("arrest", input))
        lastArrest = r
        val fine = min(eraCents(r.optInt("fineAmount", 0)), cash)
        cash -= fine
        val gone = r.optJSONArray("confiscatedItems")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val lost = items.filter { it.id in gone }
        items.removeAll { it.id in gone }
        refreshAttitudes(attitudes.keys.toList())
        val jailH = r.optDouble("jailHours", 0.0)
        val before = hour
        // (the facade moves its own clock on by the hours held, without a day's gossip for the days it crosses; its
        // answer has no clock, so it is worked out the same way here)
        val total = hour + jailH
        day += floor(total / 24.0).toInt(); hour = total - 24.0 * floor(total / 24.0)
        val passed = jailH
        restockFor(jailH)
        event("arrest", JSONObject().put("officerId", officerId).put("why", why).put("penalty", r.optString("penalty")).put("fine", fine)
            .put("confiscated", JSONArray(gone.toList())).put("charges", r.optJSONArray("charges")).put("summary", r.optString("summary"))
            .put("fromHour", before).put("hoursPassed", passed).put("ms", timings["arrest"]?.first))
        val what = when (r.optString("penalty")) {
            "warning" -> "You get off with a warning."
            "fine" -> "Fined ${money(fine)}."
            "cell_night" -> "${fmt(jailH)} hours in a cell" + (if (fine > 0) " and ${money(fine)}" else "") + "."
            else -> "${fmt(jailH)} hours in jail."
        }
        refreshPolice()
        return done(true, (if (why == "approach_and_question") "An officer stops you and asks questions. " else "Arrested! ") + what +
            (if (lost.isNotEmpty()) " They take ${lost.joinToString { it.label }}." else ""), passed, town.station)
    }

    // ------------------------------------------------------------------ the debug view

    private fun publish() {
        rentTick()
        val shops = town.places.filter { it.kind != "home" && it.kind != "police_station" }
        val stock = shops.associate { it.id to stockFor(it) }
        val offers = HashMap<String, Int>(); val offerTexts = HashMap<String, String>()
        for (i in items) for (hot in booleanArrayOf(false, true)) pawnOfferFor(i.key, hot).let { o -> offers["${i.key}|$hot"] = o; offerTexts["${i.key}|$hot"] = money(o) }
        val quotes = HashMap<String, FenceQuote>()
        for (f in fences) for (i in items) quotes["${f.personId}|${i.id}"] = fenceQuote(f, i)
        view = LoopView(cash, money(cash), stock, offers, offerTexts, items.toList(), day, hour, debugLines(), officerPlans, messages.toList(),
            fences, fenceTrust.toMap(), quotes, shops.associate { it.id to hoursOf(it) },
            rentView(), lodgingOffer(), roomCost(), livingLine(), transitInfo(), railCoef())
    }

    private fun lodgingOffer(): String = lodgingTerms()?.let { t ->
        "Room at ${lodgingName()}: ${money(t.getInt("rentCents"))} every ${t.getInt("periodDays")} days" + (if (t.getInt("depositCents") > 0) ", ${money(t.getInt("depositCents"))} deposit" else "") + "."
    } ?: "No rooms to let in ${town.year}."

    private var railCoefCache: DoubleArray? = null; private var railCoefDone = false
    /** The train fare as a line of cents (base + per km), for the UI thread's ticket window. */
    private fun railCoef(): DoubleArray? {
        if (!railCoefDone) { railCoefDone = true; val a = railFare(0.0); val b = railFare(1000.0); if (a != null && b != null) railCoefCache = doubleArrayOf(a.toDouble(), (b - a) / 1000.0) }
        return railCoefCache
    }

    private fun debugLines(): List<String> {
        val out = ArrayList<String>()
        out += "Day $day, %02d:%02d · cash ${money(cash)} · ${items.size} item(s)".format(hour.toInt() % 24, ((hour - floor(hour)) * 60).toInt())
        livingLine().takeIf { it.isNotEmpty() }?.let { out += it }
        rentView()?.let { r -> out += "Room: ${r.status}, ${r.rentText}, next ${r.nextDue}, ${r.nights} night(s) slept, ${r.roomItems.size} item(s) kept" + (if (r.owedCents > 0) ", owed ${r.owedText}" else "") }
            ?: town.lodging?.let { out += "Room to rent: ${it.name} (${lodgingOffer()})" }
        for (i in items) out += "  ${i.label} (${money(i.value)})" + (if (i.stolen) " HOT ${i.crimeId ?: ""}" else "") + (i.serial?.let { " #$it" } ?: "")
        lastAct?.let { a ->
            out += "Last act: ${a.optString("summary")} (${a.optInt("bystanders")} near${if (a.optBoolean("inside")) ", inside" else ""})"
            a.optJSONArray("witnessedBy")?.let { w -> for (k in 0 until w.length()) w.getJSONObject(k).let { x ->
                out += "  saw it: ${x.optString("name")} %.0f%%".format(x.optDouble("confidence") * 100) +
                    (if (x.optBoolean("recognizedPlayer")) ", knew you" else "") + (if (x.optBoolean("isVictim")) ", the victim" else "")
            } }
        }
        lastPolice?.let { p ->
            out += "Police ${p.optString("jurisdictionId")}: heat %.2f · %d case(s) · wanted %s".format(p.optDouble("heat"), p.optJSONArray("cases")?.length() ?: 0,
                p.optJSONArray("wantedIds")?.let { if (it.length() > 0) "yes" else "no" } ?: "no")
            p.optJSONArray("cases")?.let { c -> for (k in 0 until c.length()) c.getJSONObject(k).let { x ->
                out += "  ${x.optString("kind")} at ${x.optString("placeName")}: ${x.optInt("reportCount")} report(s), heat %.2f, %s".format(x.optDouble("heat"),
                    x.optJSONObject("strength")?.optString("verdictTier") ?: "?")
            } }
            for (o in officerPlans) out += "  ${o.optString("officerId")}: ${o.optString("assignment")} · ${o.optString("reason")}"
        }
        lastAdvance?.let { a ->
            out += "Last advance: day ${a.optInt("fromDay")} %.1f -> day ${a.optInt("toDay")} %.1f".format(a.optDouble("fromHour"), a.optDouble("toHour"))
            a.optJSONArray("attitudeChanges")?.let { c -> for (k in 0 until c.length()) c.getJSONObject(k).let { x ->
                out += "  ${x.optString("name")}: ${x.optString("previousStance")} -> ${x.optString("newStance")}"
            } }
            a.optJSONArray("newPoliceCases")?.let { if (it.length() > 0) out += "  new cases: ${(0 until it.length()).joinToString { k -> it.getString(k) }}" }
            a.optJSONArray("pawnLeadsMatched")?.let { if (it.length() > 0) out += "  pawn leads: ${it.length()}" }
        }
        for (f in fences) {
            val src = leads.filter { it.optString("fenceId") == f.personId }.mapNotNull { town.personById[it.optString("sourceId")]?.first }
            out += "Fence ${f.name} (${f.cover}): ${f.whereText}, ${f.hoursText}, trust %.2f".format(trustOf(f)) + ", ${src.size} lead(s)" + if (src.isNotEmpty()) ": ${src.take(8).joinToString()}" else ""
        }
        if (fences.isEmpty()) out += "No fence in this town."
        for (t in tips.takeLast(3)) out += "  fence tip-off: day ${t.optInt("day")} ${t.optString("item")} (risk %.2f, roll %.2f)".format(t.optDouble("risk"), t.optDouble("roll"))
        lastSpot?.let { out += "Last sighting: ${it.optString("officerId")} ${it.optString("action")} (recognition %.2f)".format(it.optDouble("recognitionChance")) }
        lastArrest?.let { out += "Last arrest: ${it.optString("summary")}" }
        out += "Facts cached for ${facts.count { it.value.isNotEmpty() }} people (${facts.size} looked up)"
        out += "Timing (ms, last/max): " + timings.entries.joinToString(" · ") { (k, v) -> "$k %.1f/%.1f".format(v.first, v.second) }
        return out
    }

    companion object {
        /** [cents] as the era writes them (the rule of `formatMoney`: under a dollar before 1980 is "23c"), for the UI thread, which cannot call the sim. */
        fun eraMoney(cents: Int, year: Int): String {
            val a = Math.abs(cents); val sign = if (cents < 0) "-" else ""
            return if (a in 1..99 && year < 1980) sign + a + "¢" else sign + "$" + (a / 100) + "." + "%02d".format(a % 100)
        }

        /** A fact from [name]'s fact sheet ("You saw this person steal ...") told to the player ("Mildred saw you steal ..."). */
        fun toPlayer(fact: String, name: String): String = fact.replace("You saw this person", "$name saw you")
            .replace("You heard this person", "$name heard you").replace(" told you this person", " told $name you")
            .replace(" mentioned someone", " told $name someone").replace("this person", "you").removeSuffix(".")

        private fun fmt(h: Double) = if (h == floor(h)) h.toInt().toString() else "%.1f".format(h)

        fun itemJson(i: LoopItem): JSONObject = JSONObject().put("id", i.id).put("key", i.key).put("label", i.label).put("value", i.value)
            .put("stolen", i.stolen).put("day", i.day).also { o -> i.serial?.let { o.put("serial", it) }; i.crimeId?.let { o.put("crimeId", it) }; i.fromPlace?.let { o.put("from", it) } }

        fun itemOf(o: JSONObject) = LoopItem(o.getString("id"), o.optString("key"), o.optString("label"), o.optInt("value"),
            o.optString("serial").ifEmpty { null }, o.optBoolean("stolen"), o.optString("crimeId").ifEmpty { null }, o.optString("from").ifEmpty { null }, o.optInt("day"))
    }
}
