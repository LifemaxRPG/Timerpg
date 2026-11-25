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
    /** A sale of stolen goods the shop knew for hot (`sell`'s `hot`): the HUD shows the line as hot. */
    val hot: Boolean = false,
    /** A street stop to play out before the line (an officer's papers and questions): [PoliceStop]. */
    val stop: PoliceStop? = null,
)

/**
 * A street stop as the player lives it (`spotted`'s `stopOutcome`): what is said, in order ([lines]: who, what), then
 * [released] (the player walks on where they stand) or arrested (the outcome's line and the station, after the lines).
 */
class PoliceStop(val lines: List<Pair<String, String>>, val released: Boolean, val checkedPapers: Boolean, val caseStrength: Double, val threshold: Double)

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
    /** Today's date as the HUD writes it ("Tue 14 Harvestmonth"), and today's holiday ("" if none); `calendar.*`. */
    val dateText: String = "",
    val holidayText: String = "",
    /** Employment: the job, references, openings by shop (`jobs.*`). */
    val jobs: JobsView = JobsView(),
    /** The player's needs and what they do (src/needs), what each shop offers to eat, the sky (src/weather), the cars (src/cars) and the walking speed they allow now (needs x sky); docs/LOOP.md. */
    val needs: NeedsView? = null,
    val eat: Map<String, EatView> = emptyMap(),
    val sky: SkyView? = null,
    val cars: CarsView = CarsView(),
    val walkMultiplier: Double = 1.0,
    /** "Take your board (2 meals a day included)": the room's board, or "" when the lodging has none. */
    val boardLine: String = "",
    /** What each food place serves (place id -> menu; `places.menuOf`), the shops for sale or to let and the player's own shops (place id -> ...; `business.*`), "Rooms elsewhere: ..." (`places.lodgingOffers`), the speakeasy's back door when the year has one. */
    val menus: Map<String, MenuView> = emptyMap(),
    val forSale: Map<String, SaleView> = emptyMap(),
    val owned: Map<String, OwnedShopView> = emptyMap(),
    val roomsElsewhere: String = "",
    val speakeasy: SpeakeasyView? = null,
    /** The player's maps (docs/MAPS.md): the paper maps owned, GPS this year, the waypoint, what each place sells. */
    val maps: MapsView = MapsView(),
)

/**
 * A shop's hours as the economy sets them (src/economy/hours.ts), for the HUD: [open] now, [today] ("Open 8 a.m.-6 p.m.",
 * "Closed Sundays"), [closedLine] what the door says when it is shut ("Closed. Opens at 8 a.m."), [staff] people at work
 * now, [closeHour] closing time today (0 when closed).
 */
class PlaceHours(val open: Boolean, val today: String, val closedLine: String, val staff: Int, val closeHour: Int, val holiday: String = "")

/** What the calendar says about an in-game day (`calendar.dateOf`, `holidaysOn`): [text] is "Tue 14 Harvestmonth"; [holiday] today's holiday name, [closesShops] whether it shuts the shops. */
class DayInfo(val weekday: Int, val text: String, val month: Int, val dom: Int, val season: String, val holiday: String?, val closesShops: Boolean)

/** One job on offer at a shop this week, as the shop panel lists it (`jobs.openings`), made on the sim's thread. */
class OpeningView(val id: String, val title: String, val hourlyCents: Int, val wageText: String, val startHour: Int, val shiftHours: Int, val minAge: Int, val line: String)

/** The player's job (`jobs.apply` / `jobs.shift` state), for the HUD and the shop panel; [line] is the status line, [reference] what the job has earned so far. */
class JobView(
    val placeId: String, val placeName: String, val title: String, val status: String, val hourlyCents: Int, val wageText: String,
    val startHour: Int, val shiftHours: Int, val strikes: Int, val steadyShifts: Int, val hasRaise: Boolean, val reference: String,
    val lastWorkedDay: Int, val fromDay: Int, val line: String,
) {
    /** A shift may be started from [startHour] for 3 hours (it is late after 15 minutes); the day is lost after that. */
    val windowEnd: Double get() = kotlin.math.min(24.0, startHour + 3.0)
}

/** Employment as the UI reads it: the job (null when none), the references carried from past jobs, the news of the last change, what is on offer in each shop. */
class JobsView(val job: JobView? = null, val referencesText: String = "", val news: String = "", val openings: Map<String, List<OpeningView>> = emptyMap())

/**
 * The first playable loop's game side (kotlin-street/docs/LOOP.md): the player's wallet and inventory, the actions at
 * the shops (buy, take, sell at the pawn shop, work a shift), the clock moved in bursts, the shopkeepers' attitudes,
 * the police's plans and an officer's sighting, the arrest; every rule decided by Jeff's facade (src/loop, bundled as
 * sim.js) through [js]. Not thread-safe: everything runs on the simulation's own thread (SimJsThread.run), never the
 * render thread. [view] is the snapshot the UI reads.
 */
class LoopGame(private val js: SimJs, val town: LoopTown) {
    var cash = 0; internal set
    val items = ArrayList<LoopItem>()
    private var nextItem = 1
    var day = 1; private set
    var hour = 8.0; private set

    /** Every loop event (name, details) for the playtest recorder (PlayLog). Set by the app. */
    var onEvent: ((String, JSONObject) -> Unit)? = null

    /** Each person's attitude facts for the dialogue's fact sheet, cached until their attitude may have changed. */
    val facts = ConcurrentHashMap<String, List<String>>()
    private val attitudes = HashMap<String, JSONObject>()
    /** Each person's facts without the note on how the player looks (see [lookFacts]). */
    private val baseFacts = HashMap<String, List<String>>()

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

    /** Each shop place's `ShopStock` (economy.openStock, plain JSON), opened the first time it is looked at; saved with the loop. */
    private val stocks = HashMap<String, JSONObject>()
    /** The part of a day the clock has moved since the last whole day passed on to `restock`. */
    private var dayFrac = 0.0

    private fun stockSeed(place: LoopPlace) = "${town.seed}|${place.id}"

    private fun stockState(place: LoopPlace): JSONObject? = businesses[place.id]?.biz?.optJSONObject("stock") ?: stocks[place.id] ?: (econ("openStock", LoopItems.econKind(place.shopKind), town.year, stockSeed(place)) as? JSONObject)?.also { stocks[place.id] = it }

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
        // (the player's own shop: the shelf is the business's; a loss comes off it at the day's end, as an event of `runDay`, not now)
        if (businesses.containsKey(place.id)) return before - pendingEvents.count { it.optString("placeId") == place.id && it.optString("kind") == "stock_theft" && it.optString("itemId") == key && it.optInt("day") == day } > 0
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

    /**
     * Starts a new game in the town at [startHour] of day 1, with the era's starting cash, the player called
     * [playerName] (the facade's facts say "you" to them; strangers describe them from [appearance]).
     */
    fun init(startHour: Double, appearance: JSONObject = LoopTown.defaultAppearance(), startDate: IntArray? = null, playerName: String = LoopTown.DEFAULT_PLAYER_NAME): JSONObject {
        this.startDate = startDate ?: defaultStartDate(town.year)
        dayCache.clear(); spanCache.clear(); openCache.clear(); staffCache.clear(); hoursDay = -1
        val init = town.initJson(startHour, playerName = playerName, appearance = appearance, startWeekday = dayInfo(1).weekday)
        val res = JSONObject(call("init", init))
        day = res.optInt("day", 1); hour = res.optDouble("hour", startHour)
        cash = startingCash()
        items.clear(); nextItem = 1
        attitudes.clear(); facts.clear(); baseFacts.clear(); stocks.clear(); dayFrac = 0.0
        fenceTrust.clear(); tips.clear(); buildFences()
        rental = null; roomItems.clear(); lastRentStatus = null; arrearsKey = ""
        job = null; references.clear(); takenOpenings.clear(); jobNews = ""
        businesses.clear(); pendingEvents.clear(); menuCache.clear()
        resetPersonal()
        ownedMaps.clear(); waypoint = null
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
        o.put("startDate", JSONArray(startDate.toList()))
        job?.let { j -> o.put("job", JSONObject().put("emp", j.emp).put("placeId", j.placeId).put("title", j.title).put("startHour", j.startHour)
            .put("shiftHours", j.shiftHours).put("fromDay", j.fromDay).put("lastWorked", j.lastWorked)) }
        o.put("businesses", JSONObject().also { m -> for ((k, ob) in businesses) m.put(k, JSONObject().put("biz", ob.biz).put("tenure", ob.tenure).put("since", ob.sinceDay).put("log", JSONArray(ob.log)).put("warning", ob.warning)) })
        o.put("bizEvents", JSONArray(pendingEvents))
        o.put("references", JSONArray(references)).put("takenOpenings", JSONArray(takenOpenings.toList())).put("jobNews", jobNews)
        savePersonal(o)
        o.put("maps", org.json.JSONArray(ownedMaps.map { it.json() }))
        waypoint?.let { o.put("waypoint", org.json.JSONArray().put(it[0].toDouble()).put(it[1].toDouble())) }
        return o.toString()
    }

    /** Puts back a game [save]d before. */
    fun load(saved: String) {
        val o = JSONObject(saved)
        require(o.optString("town") == town.seed) { "the save is for ${o.optString("town")}, not ${town.seed}" }
        // (a save from before the junctions kept the town's own ids: moved to them by position, LoopTown.migrateNodeIds)
        val res = JSONObject(call("load", LoopTown.migrateNodeIds(o.getString("sim"), town.nodes, town::vec)))
        // (a save from before the calendar began on the default start date, as the game did)
        startDate = o.optJSONArray("startDate")?.let { a -> intArrayOf(a.getInt(0), a.getInt(1), a.getInt(2)) } ?: defaultStartDate(town.year)
        dayCache.clear(); spanCache.clear(); openCache.clear(); staffCache.clear(); hoursDay = -1
        day = res.optInt("day", 1); hour = res.optDouble("hour", 8.0)
        cash = o.optInt("cash", 0); nextItem = o.optInt("nextItem", 1)
        items.clear()
        o.optJSONArray("items")?.let { a -> for (k in 0 until a.length()) items += itemOf(a.getJSONObject(k)) }
        attitudes.clear(); facts.clear(); baseFacts.clear(); stocks.clear()
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
        job = o.optJSONObject("job")?.let { Job(it.getJSONObject("emp"), it.getString("placeId"), it.optString("title"), it.optInt("startHour"), it.optInt("shiftHours"), it.optInt("fromDay"), it.optInt("lastWorked")) }
        references.clear(); o.optJSONArray("references")?.let { a -> for (k in 0 until a.length()) references += a.getJSONObject(k) }
        takenOpenings.clear(); o.optJSONArray("takenOpenings")?.let { a -> for (k in 0 until a.length()) takenOpenings += a.getString(k) }
        jobNews = o.optString("jobNews")
        businesses.clear(); pendingEvents.clear(); menuCache.clear()
        o.optJSONObject("businesses")?.let { m -> for (k in m.keys()) m.getJSONObject(k).let { e ->
            businesses[k] = OwnedBiz(e.getJSONObject("biz"), e.optString("tenure", "buy"), e.optInt("since")).also { ob ->
                e.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) ob.log += a.getJSONObject(i) }; ob.warning = e.optString("warning") }
        } }
        o.optJSONArray("bizEvents")?.let { a -> for (i in 0 until a.length()) pendingEvents += a.getJSONObject(i) }
        loadPersonal(o)
        ownedMaps.clear(); ownedMaps += LoopMaps.loadOwned(o.optJSONArray("maps"))
        waypoint = o.optJSONArray("waypoint")?.let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
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
        // (the player's needs move with the clock: what they were doing, or asleep in a bed)
        tickNeeds(hours, when (why) { "sleep" -> "sleeping"; "work" -> "working"; "hour", "action" -> walkActivity; else -> "idle" }, sleepBed)
        val res = JSONObject(call("advance", hours))
        return clockMoved(hours, why, res, res.optInt("toDay", day), res.optDouble("toHour", hour))
    }

    /**
     * The facade's clock has moved [hours] to [toDay] at [toHour] (an `advance`, or the hours a shift or a cell owns
     * itself: `act work_shift` and `arrest` run them through the facade's own advance and answer with the same world
     * changes in [res]): the game's side follows (restocking, the job's and the shop's days, attitudes, the police).
     * The needs are the caller's (asleep, working, in a cell).
     */
    private fun clockMoved(hours: Double, why: String, res: JSONObject, toDay: Int, toHour: Double): JSONObject {
        val fromDay = day
        day = toDay; hour = toHour
        lastAdvance = res
        restockFor(hours)
        daysEnded(fromDay)
        businessDaysEnded(fromDay)
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

    /** Sleeps till [wake] o'clock (the next one) on a [bed] (`needs.sleep`: bed in the rented room, bench anywhere else: "Sleep rough"). */
    fun sleepUntil(wake: Double = 7.0, bed: String = "bench"): LoopOutcome {
        var h = wake - hour
        if (h <= .25) h += 24.0
        sleepBed = bed
        try { advance(h, "sleep") } finally { sleepBed = "bench" }
        say(if (bed == "bed") "Slept till ${fmt(wake)}:00." else "Slept rough till ${fmt(wake)}:00.")
        publish()
        return LoopOutcome(true, if (bed == "bed") "You sleep till morning." else "You sleep rough till morning" + needsNote(), hour, h)
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
        val base = if (shares) known + leadFacts(personId) else known
        baseFacts[personId] = base
        facts[personId] = base + lookFacts()
        return a
    }

    /** Looks [ids]' attitudes up again (their facts for the dialogue stay current). */
    private fun refreshAttitudes(ids: Collection<String>) {
        for (id in ids.toList()) { attitudes.remove(id); if (town.personById.containsKey(id)) attitude(id) else { facts.remove(id); baseFacts.remove(id) } }
    }

    /** Looks up the facts of [ids] not cached yet (before a conversation: their dialogue's fact sheet). */
    fun prefetch(ids: Collection<String>) { for (id in ids) if (!facts.containsKey(id) && town.personById.containsKey(id)) attitude(id) }

    /** Whether [place]'s keeper serves the player: null if they do (and a wary line, if they watch them), else why not. */
    private fun refusal(place: LoopPlace, what: String): Pair<String?, String?> {
        val k = place.keeperId ?: return null to null
        val a = attitude(k, fresh = true)
        val name = a.optString("name", k).substringBefore(' ')
        val why = a.optJSONArray("facts")?.let { f -> (0 until f.length()).map { f.getString(it) }.lastOrNull { !it.startsWith(KNOWS_NAME) } }
        event("attitude", JSONObject().put("personId", k).put("place", place.id).put("stance", a.optString("stance")).put("trust", a.optDouble("trust")).put("for", what))
        return when (a.optString("stance")) {
            // (the facade's facts are the person's own words to the player: "I saw you steal ...", quoted as they are)
            "refuse", "report_on_sight" -> "$name won't $what you." + (why?.let { " \"$it\"" } ?: "") to null
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

    // (money crosses to the facade as integer cents of the era, as the wallet keeps it)
    private fun itemPayload(i: LoopItem) = JSONObject().put("id", i.id).put("label", i.label).put("value", i.value).also { o ->
        i.serial?.let { o.put("serialNumber", it) }; i.crimeId?.let { o.put("stolenFromCrimeId", it) }
    }

    private fun act(event: JSONObject, sceneIn: LoopScene): JSONObject {
        // (the weather's face recognition range: in fog or heavy rain, people outdoors beyond it do not see who it is)
        val scene = (town.placeById[event.optString("placeId")]?.let { staffedScene(it, sceneIn) } ?: sceneIn).limitedTo(faceRangeUnits())
        scene.put(event)
        // (the facade counts a place's `staff` as at the counter and watching while it is open unless the scene names
        // them; the game knows who works this hour and where they stand (staffedScene), so the rest are named, unseeing)
        town.placeById[event.optString("placeId")]?.let { p ->
            val here = scene.bystanders.mapTo(HashSet()) { it.id }
            val a = event.optJSONArray("bystanders") ?: JSONArray().also { event.put("bystanders", it) }
            for (id in town.staffOf(p)) if (id !in here) a.put(JSONObject().put("id", id).put("position", town.vec(p.doorX, p.doorZ)).put("activity", "working").put("hasLineOfSight", false))
        }
        val res = JSONObject(call("act", event))
        lastAct = res.put("bystanders", scene.bystanders.size).put("inside", scene.inside).put("sighted", scene.bystanders.count { it.los })
        // (the witnesses' and the clerk's attitudes may have changed)
        val again = HashSet<String>()
        res.optJSONArray("witnessedBy")?.let { w -> for (k in 0 until w.length()) again += w.getJSONObject(k).optString("personId") }
        for (k in listOf("clerkId", "employerId", "ownerId")) event.optString(k).takeIf { it.isNotEmpty() }?.let { again += it }
        // (a shift: the place's staff and coworkers learn the player's name too)
        if (event.optString("kind") == "work_shift") {
            town.people.filter { it.workplacePlaceId == event.optString("placeId") }.forEach { again += it.id }
            town.placeById[event.optString("placeId")]?.let { again += town.staffOf(it) }
        }
        refreshAttitudes(again)
        event("act", JSONObject().put("kind", event.optString("kind")).put("place", event.optString("placeId")).put("summary", res.optString("summary"))
            .put("crimeId", res.opt("crimeId")).put("witnessedBy", res.optJSONArray("witnessedBy")).put("bystanders", scene.bystanders.size)
            .put("sighted", scene.bystanders.count { it.los }).put("inside", scene.inside).put("light", scene.light).put("ms", timings["act"]?.first))
        return res
    }

    // ------------------------------------------------------------------ opening hours (economy.hoursOf / isOpen / staffed)

    // ------------------------------------------------------------------ the calendar (src/calendar, docs/CALENDAR.md)

    /** The date of in-game day 1 (year, month, day), set by [init] (default [defaultStartDate]) and saved with the loop; the economy keeps the town's year. */
    var startDate: IntArray = intArrayOf(town.year, 5, 6); private set
    private val dayCache = HashMap<Int, DayInfo>()
    private fun cal(method: String, vararg args: Any): Any = org.json.JSONTokener(call("calendar.$method", *args)).nextValue()
    private fun dateJson(y: Int, m: Int, d: Int) = JSONObject().put("year", y).put("month", m).put("day", d)

    /** What the calendar says about in-game day [d] (day 1 is [startDate]); cached, never per frame. */
    fun dayInfo(d: Int): DayInfo = dayCache.getOrPut(d) {
        val o = cal("dateOf", d - 1, dateJson(startDate[0], startDate[1], startDate[2])) as? JSONObject
            ?: return@getOrPut DayInfo(Math.floorMod(d - 1, 7), "Day $d", 1, 1, "", null, false)
        val hol = cal("holidaysOn", dateJson(o.getInt("year"), o.getInt("month"), o.getInt("day")), town.year) as? JSONArray ?: JSONArray()
        val names = (0 until hol.length()).map { hol.getJSONObject(it) }
        val closing = names.firstOrNull { h -> h.optJSONArray("closes")?.let { c -> (0 until c.length()).any { c.getString(it) == "shops" } } == true }
        DayInfo(o.getInt("weekday"), "${o.getString("weekdayName").take(3)} ${o.getInt("day")} ${o.getString("monthName")}", o.getInt("month"), o.getInt("day"),
            o.optString("season"), (closing ?: names.firstOrNull())?.optString("name"), closing != null)
    }

    /** The weekday (0 = Sunday) of in-game [d] from the calendar; the facade's `startDayOfWeek` is day 1's. */
    fun weekdayOf(d: Int): Int = dayInfo(d).weekday

    /** The first Tuesday of Bloommonth (month 5) of [year]: a weekday in the spring, away from the year's holidays that close the shops. */
    fun defaultStartDate(year: Int): IntArray {
        val w = (cal("dateOf", 0, dateJson(year, 5, 1)) as? JSONObject)?.optInt("weekday", 2) ?: 2
        return intArrayOf(year, 5, 1 + Math.floorMod(2 - w, 7))
    }

    private fun shopDate(di: DayInfo) = JSONObject().put("month", di.month).put("day", di.dom)

    /** The hour caches are kept for one day only (cleared when the day changes); all on the sim's thread. */
    private var hoursDay = -1
    private val openCache = HashMap<String, Boolean>()
    private val staffCache = HashMap<String, Int>()
    private val spanCache = HashMap<String, List<IntArray>>()

    private fun hoursSeed(place: LoopPlace) = "${town.seed}|${place.id}"
    private fun alwaysOpen(place: LoopPlace) = place.kind == "police_station" || place.kind == "home"

    /** [place]'s opening spans on in-game day [d] (`hoursOf` with the day's ShopDate; none on a day the calendar's holiday shuts the shops), as [open, close) hours; cached per day. */
    private fun spansOn(place: LoopPlace, d: Int): List<IntArray> {
        if (alwaysOpen(place)) return listOf(intArrayOf(0, 24))
        val di = dayInfo(d)
        if (di.closesShops) return emptyList()
        return spanCache.getOrPut("${place.id}|$d") {
            // (food venues take their hours from src/places: a bar opens at 5 p.m. and shuts at midnight)
            val venue = LoopPlaces.hoursVenue(place.shopKind)
            val a = (if (venue != null) org.json.JSONTokener(call("places.placeHours", venue, town.year, di.weekday, hoursSeed(place), shopDate(di))).nextValue()
                else econ("hoursOf", LoopItems.econKind(place.shopKind), town.year, di.weekday, hoursSeed(place), shopDate(di))) as? JSONArray ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it).let { o -> intArrayOf(o.optInt("open"), o.optInt("close")) } }
        }
    }

    private fun syncHoursDay() { if (hoursDay != day) { hoursDay = day; openCache.clear(); staffCache.clear() } }

    /** Is [place] open at the sim's hour now (`economy.isOpen`, cached per place per in-game hour)? */
    fun isOpenNow(place: LoopPlace): Boolean {
        if (alwaysOpen(place)) return true
        syncHoursDay()
        val h = floor(hour).toInt() % 24
        val di = dayInfo(day)
        if (di.closesShops) return false
        if (LoopPlaces.hoursVenue(place.shopKind) != null) return openCache.getOrPut("${place.id}|$h") { spansOn(place, day).any { h >= it[0] && h < it[1] } }
        return openCache.getOrPut("${place.id}|$h") {
            econ("isOpen", LoopItems.econKind(place.shopKind), town.year, di.weekday, h, hoursSeed(place), shopDate(di)) as? Boolean ?: false
        }
    }

    /** How many people work in [place] this hour (`economy.staffed`: 0 shut, 1 at opening and late, more at the lunch rush); cached. */
    fun staffNow(place: LoopPlace): Int {
        if (alwaysOpen(place)) return place.staff.size.coerceAtLeast(1)
        syncHoursDay()
        val h = floor(hour).toInt() % 24
        val di = dayInfo(day)
        if (di.closesShops) return 0
        if (LoopPlaces.hoursVenue(place.shopKind) != null) return staffCache.getOrPut("${place.id}|$h") {
            if (!spansOn(place, day).any { h >= it[0] && h < it[1] }) 0
            else max(1, (econ("staffed", "diner", town.year, di.weekday, h, hoursSeed(place), shopDate(di)) as? Number)?.toInt() ?: 1)
        }
        return staffCache.getOrPut("${place.id}|$h") {
            (econ("staffed", LoopItems.econKind(place.shopKind), town.year, di.weekday, h, hoursSeed(place), shopDate(di)) as? Number)?.toInt() ?: 0
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
        if (sp.isEmpty()) {
            val di = dayInfo(day)
            return if (di.closesShops) "Closed today for ${di.holiday}" else if (spansOn(place, day + 7).isEmpty()) "Closed ${dayNames[di.weekday]}s" else "Closed today"
        }
        return "Open " + sp.joinToString(", ") { if (it[0] == 0 && it[1] == 24) "24 hours" else "${hourText(it[0])}-${hourText(it[1])}" }
    }

    /** "Closed. Opens at 8 a.m." (later today), "Closed. Opens tomorrow at 8 a.m.", "Closed. Opens Monday at 10 a.m."; on a holiday "Closed for Hearth Day. Opens tomorrow at ...". */
    fun closedLine(place: LoopPlace): String {
        val h = floor(hour).toInt() % 24
        val di = dayInfo(day)
        val closed = if (di.closesShops) "Closed for ${di.holiday}." else "Closed."
        spansOn(place, day).firstOrNull { it[0] > h }?.let { return "Closed. Opens at ${hourText(it[0])}" }
        for (k in 1..7) {
            val sp = spansOn(place, day + k)
            if (sp.isNotEmpty()) return "$closed Opens ${if (k == 1) "tomorrow" else dayNames[weekdayOf(day + k)]} at ${hourText(sp[0][0])}"
        }
        return closed
    }

    /** [place]'s hours for the HUD (see [PlaceHours]). */
    fun hoursOf(place: LoopPlace): PlaceHours {
        val open = isOpenNow(place)
        val di = dayInfo(day)
        return PlaceHours(open, todayText(place), if (open) "" else closedLine(place), if (open) staffNow(place) else 0, if (open) closeNow(place) else 0,
            if (!open && di.closesShops && !alwaysOpen(place)) di.holiday.orEmpty() else "")
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

    /** Who stands behind the counter besides the keeper ([LoopTown.clerksOf]: the facade knows them as the place's `staff`). */
    private fun clerksOf(place: LoopPlace): List<String> = town.clerksOf(place)

    private fun closed(place: LoopPlace) = closedLine(place)

    fun buy(place: LoopPlace, s: StockItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (businesses.containsKey(place.id)) return done(false, "It's your own shop: take what you need from the shelves in the back.")
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
        // (a theft at the player's own shop is a loss on its books: the stock gone, and a forced door's repair)
        if (businesses.containsKey(place.id)) {
            if (breakIn != null) shopIncident(place.id, "breakage", cents = breakInCents())
            shopIncident(place.id, "stock_theft", itemId = s.key, quantity = 1)
        }
        val w = res.optJSONArray("witnessedBy") ?: JSONArray()
        val victim = (0 until w.length()).map { w.getJSONObject(it) }.firstOrNull { it.optBoolean("isVictim") }
        val fired = theftAtWork(place, w)
        val line = (if (fired != null) "$fired " else "") + when {
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
        if (businesses.containsKey(place.id)) return done(false, "It's your own shop.")
        if (!isOpenNow(place)) return done(false, closed(place))
        val (no, wary) = refusal(place, "deal with")
        if (no != null) return done(false, no)
        val price = pawnPrice(item)
        if (price <= 0) return done(false, "They don't take ${item.label}.")
        val res = act(JSONObject().put("kind", "sell").put("placeId", place.id).put("item", itemPayload(item)).also { o -> place.keeperId?.let { o.put("clerkId", it) } }, scene)
        cash += price
        items.removeAll { it.id == item.id }
        if (res.optBoolean("pawnTraceFlagged")) say("(the pawn ledger has ${item.label}: it is on file as stolen)")
        // (a hot sale, the facade's `hot`: the player is told, as the clerk writes it in the ledger the police read)
        val hot = res.optBoolean("hot")
        return done(true, "Sold ${item.label} for ${money(price)}." + if (hot) " Hot goods: the clerk writes it in the ledger${item.serial?.let { ", serial number and all" } ?: ""}." else "",
            wary = wary, hot = hot)
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
        if (businesses.containsKey(place.id)) return done(false, "It's your own shop.")
        if (!isOpenNow(place)) return done(false, closed(place))
        if (active()?.placeId == place.id) return done(false, "You work here: start your shift instead.")
        if (openingsAt(place).isNotEmpty()) return done(false, "They're hiring: ask about work.")
        val (no, wary) = refusal(place, "take on")
        if (no != null) return done(false, no)
        val hours = min(4.0, floor(closeNow(place) - hour))
        if (hours < 1.0) return done(false, "${place.name} closes soon: no shift today.")
        val w = econ("wage", LoopItems.job(place.shopKind), town.year) as? JSONObject
        val hourly = w?.optInt("hourlyCents", 0) ?: 0
        val pay = max(1, (hours * hourly).roundToInt())
        val task = LoopItems.task(place.shopKind)
        val paid = shift(place, hours, pay, task, scene)
        cash += paid
        return done(true, "You worked ${hours.toInt()} hours $task at ${place.name}: ${money(paid)}.", hours, wary = wary)
    }

    /**
     * [hours] of work at [place] for [pay] cents (`act work_shift`): the facade owns the shift's clock (it runs the hours
     * through its own advance and answers with the day, the hour and the world's changes), so the game follows that answer
     * instead of advancing again. Returns the cents the facade paid (`payEarned`).
     */
    private fun shift(place: LoopPlace, hours: Double, pay: Int, task: String, scene: LoopScene): Int {
        tickNeeds(hours, "working")
        val res = act(JSONObject().put("kind", "work_shift").put("placeId", place.id).put("hours", hours).put("pay", pay).put("taskLabel", task)
            .also { o -> place.keeperId?.let { o.put("employerId", it) } }, scene)
        val end = hour + hours
        clockMoved(hours, "work", res, res.optInt("day", day + floor(end / 24.0).toInt()), res.optDouble("hour", end - 24.0 * floor(end / 24.0)))
        return res.optInt("payEarned", pay)
    }

    private fun done(ok: Boolean, line: String, hours: Double = 0.0, moveTo: LoopPlace? = null, wary: String? = null, moveToStop: LoopStop? = null,
                     hot: Boolean = false, stop: PoliceStop? = null): LoopOutcome {
        say(line + (wary?.let { " $it" } ?: ""))
        publish()
        return LoopOutcome(ok, line, hour, hours, moveTo, wary, moveToStop, hot, stop)
    }


    // ------------------------------------------------------------------ employment (src/jobs, docs/JOBS.md: jobs.openings / apply / shift)

    /** The player's job: the facade-independent employment record of `jobs.apply` / `jobs.shift` (kept here, saved with the loop), where and when it is worked. */
    private class Job(var emp: JSONObject, val placeId: String, val title: String, val startHour: Int, val shiftHours: Int, val fromDay: Int,
                      var lastWorked: Int = 0, var status: String = emp.optString("status"))
    private var job: Job? = null
    /** References carried from past jobs (a dismissal's poor one, a good one on leaving): ref ("good" / "poor"), from (the shop), day; `apply` gets the refs. */
    private val references = ArrayList<JSONObject>()
    /** Openings the player has taken (the caller tracks the filled ones, docs/JOBS.md); they do not come back that week. */
    private val takenOpenings = HashSet<String>()
    private var jobNews = ""
    private val openingCache = HashMap<String, List<JSONObject>>()
    private val jobEffects = ArrayList<String>()

    /** The player's age for `apply` (the game has none yet) and papers (assumed in hand: the game does not track them). */
    private val PLAYER_AGE = 25
    private val PLAYER_HAS_PAPERS = true

    /** Whether a shift (a job's) may be started: from the start hour for this many hours (it is late after 15 minutes), after that the day is lost. */
    private val START_WINDOW = 3.0

    /** The openings at [place] this week (`jobs.openings` for the one place, the week of the day, cached), less those taken. */
    fun openingsAt(place: LoopPlace): List<JSONObject> {
        if (alwaysOpen(place)) return emptyList()
        val week = day / 7
        return openingCache.getOrPut("${place.id}|$week") {
            val a = call("jobs.openings", JSONArray().put(JSONObject().put("id", place.id).put("kind", LoopItems.econKind(place.shopKind))), town.year, day, town.seed)
            val arr = JSONArray(a)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.filter { it.getString("id") !in takenOpenings }
    }

    /** The shop keeper's standing with the player as a reputation for `apply`: serve 0.6, wary 0.35, refuse / report on sight 0.1 (no keeper: 0.6). */
    private fun reputationAt(place: LoopPlace): Double {
        val k = place.keeperId ?: return 0.6
        return when (attitude(k, fresh = true).optString("stance")) { "wary" -> 0.35; "refuse", "report_on_sight" -> 0.1; else -> 0.6 }
    }

    private fun refsJson(): JSONArray = JSONArray().also { a -> for (r in references) a.put(r.optString("ref")) }
    private fun workedAt(): LoopPlace? = job?.let { town.placeById[it.placeId] }
    private fun active(): Job? = job?.takeIf { it.status != "dismissed" }

    private fun wageText(cents: Int) = money(cents) + "/h"

    /** A shift's record to `jobs.shift` (on [d]); applies what came back and returns the line the player reads (null: nothing to say). */
    private fun recordShift(j: Job, d: Int, outcome: String): String? {
        val res = JSONObject(call("jobs.shift", j.emp, JSONObject().put("day", d).put("outcome", outcome)))
        j.emp = res.getJSONObject("job"); j.status = j.emp.optString("status")
        val ev = res.optString("event")
        val where = town.placeById[j.placeId]?.name ?: "work"
        val line = when (ev) {
            "warning" -> "Warning at $where: ${if (outcome == "absent") "you missed your shift" else "you were late"} (one more and you're out)."
            "hired" -> "Your trial at $where is over: you're hired."
            "raise" -> "A raise at $where: ${wageText(j.emp.optInt("hourlyCents"))} now, and a good reference."
            "dismissed" -> "$where lets you go (${if (outcome == "caught_stealing") "caught stealing" else if (outcome == "absent") "missed shifts" else "late again"}). They give you a poor reference."
            else -> null
        }
        if (ev == "dismissed") { references += JSONObject().put("ref", j.emp.optString("reference", "poor")).put("from", where).put("day", d); job = null }
        if (line != null) { jobNews = line; say(line); event("job", JSONObject().put("event", ev).put("outcome", outcome).put("place", j.placeId).put("day", d)) }
        return line
    }

    /** "Apply" for [openingId] at [place]: `jobs.apply` with age 25, the carried references, the keeper's reputation and papers; a trial or a hire starts the job. */
    fun applyFor(place: LoopPlace, openingId: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        active()?.let { return done(false, "You already work at ${workedAt()?.name ?: "a shop"}.") }
        if (businesses.containsKey(place.id)) return done(false, "It's your own shop.")
        val opening = openingsAt(place).firstOrNull { it.getString("id") == openingId } ?: return done(false, "That position has been filled.")
        val rep = reputationAt(place)
        val res = JSONObject(call("jobs.apply", opening, JSONObject().put("age", PLAYER_AGE).put("references", refsJson()).put("reputation", rep).put("hasPapers", PLAYER_HAS_PAPERS)))
        event("job_apply", JSONObject().put("place", place.id).put("opening", openingId).put("reputation", rep).put("result", res.optString("status")).put("reason", res.optString("reason")))
        val title = opening.optString("title")
        if (res.optString("status") == "refused") {
            val line = when (res.optString("reason")) {
                "age" -> "You're too young for the ${title.lowercase()} job."
                "papers" -> "They want your papers."
                else -> "${place.keeperId?.let { town.personById[it]?.first } ?: "They"} turns you down: they've heard things about you."
            }
            return done(false, line)
        }
        takenOpenings += openingId
        val start = opening.optInt("startHour"); val len = opening.optInt("shiftHours")
        // (the first shift is today's if it can still be started, else tomorrow's)
        val j = Job(res.getJSONObject("job"), place.id, title, start, len, if (hour < start + START_WINDOW) day else day + 1)
        job = j
        val trial = res.optString("status") == "trial"
        val line = (if (trial) "${title} at ${place.name}: you're on trial" else "You're hired as ${title.lowercase()} at ${place.name}") +
            " at ${wageText(j.emp.optInt("hourlyCents"))}, shifts from ${hourText(start)} (${len} h)."
        jobNews = line
        return done(true, line)
    }

    /** Whether today's shift can be started now at [place], and why not (else null). */
    private fun shiftBlock(j: Job, place: LoopPlace): String? {
        if (place.id != j.placeId) return "You work at ${workedAt()?.name ?: "another shop"}."
        if (j.lastWorked == day) return "You've done today's shift."
        if (spansOn(place, day).isEmpty()) return "${place.name} is closed today: no shift."
        if (hour < j.startHour) return "Your shift starts at ${hourText(j.startHour)}."
        if (hour >= j.startHour + START_WINDOW || hour >= 24.0) return "You missed today's shift (it started at ${hourText(j.startHour)})."
        return null
    }

    /**
     * "Start shift" at the job's [place], from its start hour: more than 15 minutes late records 'late' (a second strike, or the next, dismisses), on time
     * 'on_time'; the clock moves on by the shift's hours (the rest of them when late) and the job's `hourlyCents` pays at the end.
     */
    fun startShift(place: LoopPlace, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val j = active() ?: return done(false, "You have no job here.")
        shiftBlock(j, place)?.let { return done(false, it) }
        val late = hour > j.startHour + 0.25
        val note = recordShift(j, day, if (late) "late" else "on_time")
        j.lastWorked = day
        if (j.status == "dismissed") return done(false, note ?: "You're let go.")
        val hours = if (late) max(1.0, min(j.shiftHours.toDouble(), j.startHour + j.shiftHours - hour)) else j.shiftHours.toDouble()
        val pay = max(1, (hours * j.emp.optInt("hourlyCents")).roundToInt())
        val task = LoopItems.task(place.shopKind)
        val paid = shift(place, hours, pay, task, scene)
        cash += paid
        return done(true, "Shift as ${j.title.lowercase()} at ${place.name}: ${fmt(hours)} h, ${money(paid)}." + (if (late) " (late)" else "") + (note?.let { " $it" } ?: ""), hours)
    }

    /** Gives the job up: the reference it earned so far (good after a steady run) is kept for the next application. */
    fun quitJob(): LoopOutcome {
        val j = active() ?: return done(false, "You have no job.")
        val where = workedAt()?.name ?: "work"
        val ref = j.emp.optString("reference", "none")
        if (ref != "none") references += JSONObject().put("ref", ref).put("from", where).put("day", day)
        job = null
        jobNews = "You left $where."
        return done(true, "You quit $where." + if (ref == "good") " They give you a good reference." else "")
    }

    /** After a clock advance from day [fromDay]: each day that ended with no shift worked (the shop open that day, the job begun) is recorded 'absent'. */
    private fun daysEnded(fromDay: Int) {
        for (d in fromDay until day) {
            val j = active() ?: return
            if (d < j.fromDay || j.lastWorked == d) continue
            val place = workedAt() ?: continue
            if (spansOn(place, d).isEmpty()) continue
            recordShift(j, d, "absent")
        }
    }

    /** A theft at the workplace while employed: seen by staff, 'caught_stealing' (dismissal, a poor reference). */
    private fun theftAtWork(place: LoopPlace, witnesses: JSONArray?): String? {
        val j = active() ?: return null
        if (j.placeId != place.id || witnesses == null) return null
        val staff = (place.staff + clerksOf(place)).toSet()
        if ((0 until witnesses.length()).none { witnesses.getJSONObject(it).optString("personId") in staff }) return null
        // (one outcome per job per day: a theft after today's shift counts as the next day's record, which the job's record then accepts)
        return recordShift(j, max(day, (j.emp.opt("lastShiftDay") as? Number)?.toInt()?.plus(1) ?: day), "caught_stealing")
    }

    private fun jobsView(shops: List<LoopPlace>): JobsView {
        val j = active()
        val jv = j?.let {
            val place = workedAt()
            val e = it.emp
            val ref = e.optString("reference", "none")
            val line = "${it.title} at ${place?.name ?: "?"}: ${if (it.status == "trial") "on trial" else "regular"}, ${wageText(e.optInt("hourlyCents"))}, shifts from ${hourText(it.startHour)} (${it.shiftHours} h)" +
                (if (e.optInt("strikes") > 0) ", ${e.optInt("strikes")} strike" else "") + (if (e.optBoolean("hasRaise")) ", raised" else "") + (if (ref == "good") ", good reference" else "")
            JobView(it.placeId, place?.name ?: "", it.title, it.status, e.optInt("hourlyCents"), wageText(e.optInt("hourlyCents")), it.startHour, it.shiftHours,
                e.optInt("strikes"), e.optInt("steadyShifts"), e.optBoolean("hasRaise"), ref, it.lastWorked, it.fromDay, line)
        }
        val open = if (j != null) emptyMap() else shops.associate { p ->
            p.id to openingsAt(p).map { o ->
                val c = o.optInt("hourlyCents"); val s = o.optInt("startHour"); val h = o.optInt("shiftHours")
                OpeningView(o.getString("id"), o.optString("title"), c, wageText(c), s, h, o.optInt("minAge"),
                    "${o.optString("title")}: ${wageText(c)}, from ${hourText(s)} for $h h" + if (o.optBoolean("requiresPapers")) ", papers needed" else "")
            }
        }.filterValues { it.isNotEmpty() }
        val refs = references.joinToString("; ") { "${it.optString("ref")} (${it.optString("from")})" }
        return JobsView(jv, if (refs.isEmpty()) "" else "References: $refs", jobNews, open)
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
    fun lodgingTerms(kind: String = lodgingKind): JSONObject? = termsCache[kind] ?: (econ("lodgingOf", kind, town.year) as? JSONObject)?.also { termsCache[kind] = it }

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
        val o = sleepUntil(7.0, "bed")
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
     * [officerId] sees the player [distanceM] away in [light] (`spotted`): watching them is shown; a stop
     * (`approach_and_question`) is played on the street (papers, a question or two: [PoliceStop]) and ends as the
     * facade's `stopOutcome` says, released where they stand or arrested; an arrest is the arrest scene ([arrest]).
     * Null when they ignore the player.
     */
    fun spotted(officerId: String, distanceM: Double, light: Double, gameHour: Double): LoopOutcome? {
        clockTo(gameHour, exact = true)
        val s = JSONObject(call("spotted", officerId, JSONObject().put("distance", Math.round(distanceM * 10) / 10.0).put("light", Math.round(light * 100) / 100.0)))
        lastSpot = s
        val action = s.optString("action")
        event("spotted", JSONObject().put("officerId", officerId).put("distance", distanceM).put("light", light).put("action", action)
            .put("recognition", s.optDouble("recognitionChance")).put("case", s.opt("matchedCaseId"))
            .put("stop", s.optJSONObject("stopOutcome")?.optString("outcome")).put("ms", timings["spotted"]?.first))
        return when (action) {
            "watch" -> done(true, "A police officer is watching you.")
            "approach_and_question" -> streetStop(officerId, s)
            "arrest" -> arrest(officerId, action)
            else -> { publish(); null }
        }
    }

    /** A stop on the street (`spotted`'s `stopOutcome`): released, the player walks on; else the arrest, from the stop. */
    private fun streetStop(officerId: String, spot: JSONObject): LoopOutcome {
        val so = spot.optJSONObject("stopOutcome")
        val lines = stopLines(officerId, spot, so)
        if (so == null || so.optString("outcome") != "released") return arrest(officerId, "approach_and_question", lines)
        val stop = PoliceStop(lines + (OFFICER to pick(officerId, "go", "All right. On your way.", "That'll do. Move along.")), true,
            so.optBoolean("checkedPapers"), so.optDouble("caseStrength"), so.optDouble("thresholdUsed"))
        event("stop", JSONObject().put("officerId", officerId).put("outcome", "released").put("papers", stop.checkedPapers)
            .put("caseStrength", stop.caseStrength).put("threshold", stop.threshold).put("reason", so.optString("reason")))
        refreshPolice()
        return done(true, "An officer stops you, asks a few questions and lets you go.", stop = stop)
    }

    /** One of [options] for this officer today (the same stop reads the same; another day, another way of saying it). */
    private fun pick(officerId: String, key: String, vararg options: String): String = options[Math.floorMod("$officerId|$day|$key".hashCode(), options.size)]

    /**
     * What is said at a stop, from the facade's [so] (`checkedPapers`, `questioned`) and the case the officer matched
     * ([spot]'s `matchedCaseId`, its place and kind from `policeView`): the officer's opening, the papers (an ID from
     * 1950), the questions about the place, the player's answers. Plain lines in the game's voice, not the dialogue model's.
     */
    private fun stopLines(officerId: String, spot: JSONObject, so: JSONObject?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += OFFICER to pick(officerId, "hi", "Hold on there. A word?", "You there. Stop a minute.", "Hold up a second.")
        if (so?.optBoolean("checkedPapers") == true) {
            out += OFFICER to if (town.year >= 1950) pick(officerId, "id", "Let's see some ID.", "ID, please.") else pick(officerId, "id", "Your papers, please.", "Let's see your papers.")
            out += YOU to pick(officerId, "idok", "(You hand them over. The officer reads them slowly and gives them back.)", "(The officer looks from your papers to your face, and back.)")
        }
        if (so == null || so.optBoolean("questioned", true)) {
            val case = spot.optString("matchedCaseId").takeIf { it.isNotEmpty() }?.let { id ->
                lastPolice?.optJSONArray("cases")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("crimeId") == id } }
            }
            val where = case?.optString("placeName")?.takeIf { it.isNotEmpty() }
            out += OFFICER to (where?.let { pick(officerId, "q1", "Were you anywhere near $it lately?", "You been by $it these last few days?") }
                ?: pick(officerId, "q1", "Where are you headed?", "What brings you around here?"))
            out += YOU to pick(officerId, "a1", "(You say you were only passing through.)", "(You say you don't remember.)")
            out += OFFICER to when (case?.optString("kind")) {
                "shoplifting", "theft" -> "Something went missing from there. Someone of your description was seen."
                "burglary" -> "Somebody broke in there. You wouldn't know anything about that?"
                "car_theft" -> "A car went missing near there. Know anything about it?"
                "robbery", "assault" -> "Someone got hurt there. Your description came up."
                "intimidation" -> "Somebody was threatened there. Your description came up."
                "bribery", "perjury" -> "There are questions about what went on there. Your name came up."
                null -> pick(officerId, "q2", "You look like someone we're looking for.", "Someone matching your description has been seen about.")
                else -> "There was trouble there. Your description came up."
            }
        }
        return out
    }

    /**
     * The arrest scene's outcome (`arrest`, with the cash in the wallet): the fine taken (what there is), what was stolen
     * taken away, a night in the cell or the hours of jail (the facade runs them through its own clock and answers with
     * the day and hour: the game follows); the player is taken to the police station. From a stop ([why]
     * `approach_and_question`, with its [stopLines]) the facade may still let a thin case go: then the player walks on.
     */
    fun arrest(officerId: String?, why: String = "arrest", stopLines: List<Pair<String, String>>? = null): LoopOutcome {
        val input = JSONObject().put("jurisdictionId", town.jurisdictionId).put("playerCash", cash)
            .put("carriedItemIds", JSONArray(items.map { it.id }))
        officerId?.let { input.put("officerId", it) }
        val fromStop = why == "approach_and_question"
        if (fromStop) input.put("fromStop", true).put("why", why)
        val r = JSONObject(call("arrest", input))
        lastArrest = r
        if (fromStop && r.optBoolean("releasedAfterStop")) {
            event("arrest", JSONObject().put("officerId", officerId).put("why", why).put("penalty", r.optString("penalty")).put("released", true)
                .put("summary", r.optString("summary")).put("ms", timings["arrest"]?.first))
            refreshPolice()
            val stop = PoliceStop(stopLines.orEmpty() + (OFFICER to "All right. On your way."), true, false, r.optDouble("totalCaseStrength"), 0.0)
            return done(true, "An officer stops you, asks a few questions and lets you go.", stop = stop)
        }
        val fine = min(r.optInt("fineAmount", 0), cash)
        cash -= fine
        val gone = r.optJSONArray("confiscatedItems")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val lost = items.filter { it.id in gone }
        items.removeAll { it.id in gone }
        val jailH = r.optDouble("jailHours", 0.0)
        val before = hour
        // (hours held: a night on the cell's bunk, else sitting about; the facade ran them through its clock, so gossip,
        // reports and the police went on meanwhile, and its answer has the day and hour it let the player out)
        tickNeeds(jailH, if (jailH >= 6.0) "sleeping" else "idle", "cell")
        if (jailH > 1e-6) clockMoved(jailH, "cell", r, r.optInt("day", day), r.optDouble("hour", hour))
        refreshAttitudes(attitudes.keys.toList())
        event("arrest", JSONObject().put("officerId", officerId).put("why", why).put("penalty", r.optString("penalty")).put("fine", fine)
            .put("confiscated", JSONArray(gone.toList())).put("charges", r.optJSONArray("charges")).put("summary", r.optString("summary"))
            .put("fromHour", before).put("hoursPassed", jailH).put("toDay", day).put("toHour", hour).put("ms", timings["arrest"]?.first))
        val what = when (r.optString("penalty")) {
            "warning" -> "You get off with a warning."
            "fine" -> "Fined ${money(fine)}."
            "cell_night" -> "${fmt(jailH)} hours in a cell" + (if (fine > 0) " and ${money(fine)}" else "") + "."
            else -> "${fmt(jailH)} hours in jail."
        }
        refreshPolice()
        val so = lastSpot?.optJSONObject("stopOutcome")
        val stop = stopLines?.let { PoliceStop(it + (OFFICER to pick(officerId ?: "", "take", "You'll need to come with me.", "That's enough. You're coming to the station.")), false,
            so?.optBoolean("checkedPapers") == true, r.optDouble("totalCaseStrength"), so?.optDouble("thresholdUsed") ?: 0.0) }
        return done(true, (if (fromStop) "Stopped and arrested. " else "Arrested! ") + what +
            (if (lost.isNotEmpty()) " They take ${lost.joinToString { it.label }}." else ""), jailH, town.station, stop = stop)
    }


    // ------------------------------------------------------------------ needs (src/needs, docs/NEEDS.md: needs.tick / eat / sleep / injure / treat / effects)

    /** What the player is doing while the clock moves on its own (walking, running, idle): set by the app before each burst from the walk. */
    @Volatile var walkActivity = "idle"
    /** The bed the next sleep is on (`bed` the rented room's, `bench` anywhere else, `cell`). */
    private var sleepBed = "bench"
    /** Which lodging the room is rented from ([lodgingTerms]'s default): the boarding house; `room_and_board` includes meals (a test or a later choice sets it). */
    var lodgingKind = "boarding_house"
    private var needs: JSONObject? = null
    private var effectsCache: JSONObject? = null
    private var lookNote: String? = null
    private var boardDay = 0
    private var boardTaken = 0

    private fun nd(method: String, vararg args: Any): Any = org.json.JSONTokener(call("needs.$method", *args)).nextValue()

    /** The player's Needs record (`needs.initialNeeds` at first; saved with the loop). */
    fun needsState(): JSONObject = needs ?: (nd("initialNeeds") as JSONObject).also { needs = it }

    /** `needs.effects` of the state now (labels, walking multiplier, social modifier), cached until the needs change. */
    fun needEffects(): JSONObject = effectsCache ?: (nd("effects", needsState()) as JSONObject).also { effectsCache = it }

    private fun labelsOf(e: JSONObject): List<String> = e.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private fun setNeeds(n: JSONObject) {
        val before = labelsOf(needEffects())
        needs = n; effectsCache = null
        val after = labelsOf(needEffects())
        for (l in after) if (l !in before) { say("You are $l."); event("needs", JSONObject().put("label", l).put("day", day).put("hour", hour)) }
        val note = computeLook()
        if (note != lookNote) { lookNote = note; for ((k, v) in baseFacts) facts[k] = v + lookFacts() }
    }

    /** Time passes: the player [activity] (walking, running, working, idle, sleeping on [bed]) for [hours]. */
    private fun tickNeeds(hours: Double, activity: String, bed: String? = null) {
        if (hours <= 1e-6) return
        setNeeds((if (activity == "sleeping") nd("sleep", needsState(), hours, bed ?: "bench") else nd("tick", needsState(), hours, activity)) as JSONObject)
    }

    private fun needsNote(): String = labelsOf(needEffects()).let { if (it.isEmpty()) "" else " (you are ${it.joinToString(", ")})" }

    /** How the player looks to others from their hygiene and tiredness (the social modifier's cause): null when they look all right. */
    private fun computeLook(): String? {
        val social = needEffects().optDouble("socialReactionModifier", 0.0)
        if (social > -0.05) return null
        val n = needsState()
        val bits = ArrayList<String>()
        if (n.optDouble("hygiene", 1.0) < 0.6) bits += "unwashed"
        if (n.optDouble("fatigue", 0.0) >= 0.85) bits += "exhausted" else if (n.optDouble("fatigue", 0.0) > 0.55) bits += "worn out"
        return bits.takeIf { it.isNotEmpty() }?.joinToString(" and ")
    }

    /** A note in everyone's fact sheet on how the player looks, in their own words as the facade's facts are ("I notice you look unwashed and exhausted."), none when fine. */
    private fun lookFacts(): List<String> = lookNote?.let { listOf("I notice you look $it.") }.orEmpty()

    private fun dailyNeedsJson(): JSONObject? = dailyNeedsCache ?: (econ("dailyNeeds", town.year) as? JSONObject)?.also { dailyNeedsCache = it }
    private var dailyNeedsCache: JSONObject? = null

    /** A diner meal: a third of the era's diner day (`dailyNeeds(year).dinerMealsPerDayCents / 3`, until the diners have a menu of their own); a snack about 30% of it. */
    fun mealCents(meal: String): Int {
        val diner = ((dailyNeedsJson()?.optInt("dinerMealsPerDayCents", 0) ?: 0) / 3).coerceAtLeast(1)
        return if (meal == "snack") max(1, (diner * 0.3).roundToInt()) else diner
    }

    /** What [place] offers to eat: a meal at a diner, a snack at a general store, market, five-and-dime or gas station; null elsewhere. */
    fun eatOption(place: LoopPlace): EatView? {
        if (LoopPlaces.spec(place.shopKind) != null) return null // (a food place has its menu: [menuOf])
        val meal = when (LoopItems.econKind(place.shopKind)) { "diner" -> "diner"; "general", "market", "five_and_dime", "gas_station" -> "snack"; else -> return null }
        val c = mealCents(meal)
        return EatView(if (meal == "diner") "Eat a meal" else "Have a snack", c, money(c), meal)
    }

    /** "Eat" at [place]: pays the meal's price, relieves hunger (`needs.eat`). At the player's own shop it is free. */
    fun eat(place: LoopPlace, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val opt = eatOption(place) ?: return done(false, "${place.name} doesn't serve food.")
        if (!isOpenNow(place)) return done(false, closed(place))
        val own = businesses.containsKey(place.id)
        val (no, wary) = if (own) null to null else refusal(place, "serve")
        if (no != null) return done(false, no)
        if (!own && cash < opt.cents) return done(false, "${opt.label} costs ${opt.text}; you have ${money(cash)}.")
        if (!own) cash -= opt.cents
        setNeeds(nd("eat", needsState(), opt.meal) as JSONObject)
        event("eat", JSONObject().put("place", place.id).put("meal", opt.meal).put("cents", if (own) 0 else opt.cents))
        return done(true, "${if (opt.meal == "diner") "You eat a meal" else "You have a snack"} at ${place.name}" + (if (own) " (your own, on the house)" else " for ${opt.text}") + "." + needsNote(), wary = wary)
    }

    // ------------------------------------------------------------------ menus and lodging (src/places, docs/PLACES.md: places.menuOf / lodgingOffers / placeHours)

    private val menuCache = HashMap<String, List<MenuItemView>>()

    /** The era's menu for a [kind] of venue (`places.menuOf` with the venue's [seed]): the items in the town's year, empty when the venue does not exist then (a legal bar under Prohibition). */
    fun menuOf(kind: String, seed: String = "${town.seed}|$kind", year: Int = town.year): List<MenuItemView> {
        val a = org.json.JSONTokener(call("places.menuOf", kind, year, seed)).nextValue() as? JSONArray ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { MenuItemView(it.getString("id"), it.getString("label"), it.getInt("priceCents"), money(it.getInt("priceCents")), it.getString("meal")) }
    }

    /** What [place] serves (its venue's menu, ice cream parlors keeping only the snacks), cached; null for a shop that sells goods. */
    fun menuFor(place: LoopPlace): MenuView? {
        val spec = LoopPlaces.spec(place.shopKind) ?: return null
        val items = menuCache.getOrPut(place.id) {
            spec.kinds.firstNotNullOfOrNull { k -> menuOf(k, "${town.seed}|${place.id}").filter { !spec.snacksOnly || it.meal == "snack" }.takeIf { it.isNotEmpty() } }.orEmpty()
        }
        val note = if (items.isNotEmpty()) "" else if (LoopPlaces.isBar(place.shopKind) && town.year in 1920..1933) "No legal drink: Prohibition." else "Nothing is served."
        return MenuView(spec.kinds.first(), items, note)
    }

    /** Orders [itemId] from [place]'s menu: pays its price (free at the player's own place) and relieves hunger by the item's meal kind (`needs.eat`). */
    fun eatItem(place: LoopPlace, itemId: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val menu = menuFor(place) ?: return done(false, "${place.name} doesn't serve food.")
        val item = menu.items.firstOrNull { it.id == itemId } ?: return done(false, if (menu.items.isEmpty()) menu.note else "They don't have that.")
        if (!isOpenNow(place)) return done(false, closed(place))
        val own = businesses.containsKey(place.id)
        val (no, wary) = if (own) null to null else refusal(place, "serve")
        if (no != null) return done(false, no)
        if (!own && cash < item.cents) return done(false, "${item.label} costs ${item.text}; you have ${money(cash)}.")
        if (!own) cash -= item.cents
        setNeeds(nd("eat", needsState(), item.meal) as JSONObject)
        event("eat", JSONObject().put("place", place.id).put("item", item.id).put("meal", item.meal).put("cents", if (own) 0 else item.cents))
        return done(true, "You have ${item.label.replaceFirstChar { it.lowercase() }} at ${place.name}" + (if (own) " (your own, on the house)" else " for ${item.text}") + "." + needsNote(), wary = wary)
    }

    /** "Rooms elsewhere: Grand hotel $X a night (private bath); ..." (`places.lodgingOffers` for each hotel kind the year has): info only, the town has no hotel. */
    val roomsElsewhere: String by lazy {
        val parts = LoopPlaces.LODGING.mapNotNull { (kind, name) ->
            val a = org.json.JSONTokener(call("places.lodgingOffers", kind, town.year, "${town.seed}|$kind")).nextValue() as? JSONArray ?: return@mapNotNull null
            val o = a.optJSONObject(0) ?: return@mapNotNull null
            val days = o.optInt("billingDays", 1); val rate = o.optInt("rateCents")
            "$name ${money(rate)}" + (if (days <= 1) " a night" else " every $days days") + " (${o.optString("bathroom")} bath" + (if (o.optInt("depositCents") > 0) ", ${money(o.optInt("depositCents"))} deposit" else "") + ")"
        }
        if (parts.isEmpty()) "" else "Rooms elsewhere: " + parts.joinToString("; ") + "."
    }

    // ------------------------------------------------------------------ the speakeasy (1920-1933: places.menuOf('speakeasy') / placeHours)

    private fun speakeasyHours(d: Int): List<IntArray> {
        val di = dayInfo(d)
        val a = org.json.JSONTokener(call("places.placeHours", "speakeasy", town.year, di.weekday, "${town.seed}|speakeasy", shopDate(di))).nextValue() as? JSONArray ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> intArrayOf(o.optInt("open"), o.optInt("close")) } }
    }

    /** Whether the back door is open at the clock now (night hours only). */
    fun speakeasyOpen(): Boolean { val h = floor(hour).toInt() % 24; return speakeasyHours(day).any { h >= it[0] && h < it[1] } }

    /** The speakeasy's menu (empty outside the Prohibition years). */
    fun speakeasyMenu(): List<MenuItemView> = if (town.speakeasy == null) emptyList() else menuCache.getOrPut("speakeasy") { menuOf("speakeasy", "${town.seed}|speakeasy") }

    /** Orders [itemId] at the back door: only at night, paid, relieves hunger by the item's meal kind. (Drinking is against the law then; the facade has no hook for it, see docs/LOOP.md.) */
    fun orderAtSpeakeasy(itemId: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val sp = town.speakeasy ?: return done(false, "There is nothing behind that door.")
        val item = speakeasyMenu().firstOrNull { it.id == itemId } ?: return done(false, "They don't have that.")
        if (!speakeasyOpen()) return done(false, "The back door is shut. Try after dark.")
        if (cash < item.cents) return done(false, "${item.label} costs ${item.text}; you have ${money(cash)}.")
        cash -= item.cents
        setNeeds(nd("eat", needsState(), item.meal) as JSONObject)
        event("eat", JSONObject().put("place", sp.placeId).put("speakeasy", true).put("item", item.id).put("meal", item.meal).put("cents", item.cents))
        return done(true, "Behind the back door of ${town.placeById[sp.placeId]?.name ?: "the shop"} you have ${item.label.replaceFirstChar { it.lowercase() }} for ${item.text}." + needsNote())
    }

    private fun speakeasyView(): SpeakeasyView? {
        val sp = town.speakeasy ?: return null
        val menu = speakeasyMenu().takeIf { it.isNotEmpty() } ?: return null
        val spans = speakeasyHours(day)
        return SpeakeasyView(sp.name, speakeasyOpen(), if (spans.isEmpty()) "Shut today" else "Open " + spans.joinToString(", ") { "${hourText(it[0])}-${hourText(it[1])}" }, menu)
    }

    // ------------------------------------------------------------------ small businesses (src/business, docs/BUSINESS.md)

    /** A shop the player owns: its `Business` JSON (the module's, saved), how it was taken ("buy" / "lease"), the day taken, a log of the days run (newest last), the last warning from the landlord or the bank. */
    private class OwnedBiz(biz: JSONObject, val tenure: String, val sinceDay: Int) {
        var biz: JSONObject = biz; set(v) { field = v; valuation = null }
        var valuation: Int? = null
        val log = ArrayList<JSONObject>()
        var warning = ""
    }
    private val businesses = LinkedHashMap<String, OwnedBiz>()
    /** Events at the player's shops not yet run through `runDay`: place id, day, kind (`stock_theft` itemId quantity / `cash_theft` / `breakage` cents). */
    private val pendingEvents = ArrayList<JSONObject>()
    /** The most clerks a shop takes on. */
    val MAX_CLERKS = 3

    /** The offers (`business.businessesForSale` for the town's shops, the town's seed), made once. */
    private val offers: Map<String, JSONObject> by lazy {
        val shops = JSONArray()
        for (p in town.places) if (p.kind != "home" && p.kind != "police_station") shops.put(JSONObject().put("id", p.id).put("kind", LoopItems.econKind(p.shopKind)).put("name", p.name))
        val a = org.json.JSONTokener(call("business.businessesForSale", shops, town.year, town.seed)).nextValue() as? JSONArray ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getJSONObject("place").getString("id") }
    }

    fun ownsShop(placeId: String) = businesses.containsKey(placeId)
    val ownedShopIds: List<String> get() = businesses.keys.toList()

    /** The till float the player puts in the drawer when they take a shop: about a week of the lease (at least a dollar). */
    private fun floatCents(offer: JSONObject): Int = max(100, offer.optInt("leaseMonthlyCents") / 4)
    /** What taking [offer] costs now: [buy] the asking price, else the opening stock (the lease's rent accrues daily from the drawer), and the float. */
    private fun dueFor(offer: JSONObject, buy: Boolean): Int = (if (buy) offer.optInt("askingCents") else offer.optInt("stockCents")) + floatCents(offer)

    /** A forced door's repair (`breakage`): $35 of 1952 money scaled by the price index. */
    private fun breakInCents(): Int = max(1, (3500.0 * priceIndex / ((econ("priceIndex", 1952) as? Number)?.toDouble() ?: 1.0)).roundToInt())

    /** Takes [place] by [tenure] ("buy" or "lease"): pays from the wallet (refused if short), puts the float in the drawer, and the shop is the player's (saved). */
    fun buyBusiness(place: LoopPlace, tenure: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (businesses.containsKey(place.id)) return done(false, "You already own ${place.name}.")
        val offer = offers[place.id] ?: return done(false, "${place.name} is not for sale.")
        val buy = tenure == "buy"
        val due = dueFor(offer, buy)
        if (cash < due) return done(false, "${if (buy) "Buying" else "Leasing"} ${place.name} costs ${money(due)} now (${if (buy) "the price and a till float" else "the stock and a till float"}); you have ${money(cash)}.")
        val b = org.json.JSONTokener(call("business.openBusiness", offer, if (buy) "buy" else "lease", floatCents(offer), town.seed)).nextValue() as? JSONObject
            ?: return done(false, "The deal falls through.")
        cash -= due
        businesses[place.id] = OwnedBiz(b, if (buy) "buy" else "lease", day)
        stocks.remove(place.id)
        event("business", JSONObject().put("what", tenure).put("place", place.id).put("paid", due).put("day", day))
        return done(true, if (buy) "You buy ${place.name} for ${money(due)} (with ${money(floatCents(offer))} in the till)." else "You lease ${place.name}: ${money(due)} for the stock and the till, then ${money(offer.optInt("leaseMonthlyCents"))} a month.")
    }

    /** Records an event at an owned shop for the day's `runDay` ([kind]: `stock_theft` with [itemId] and [quantity], `cash_theft` or `breakage` with [cents]). A no-op at a shop the player does not own. */
    fun shopIncident(placeId: String, kind: String, itemId: String = "", quantity: Int = 1, cents: Int = 0) {
        if (!businesses.containsKey(placeId)) return
        pendingEvents += JSONObject().put("placeId", placeId).put("day", day).put("kind", kind).also { if (kind == "stock_theft") it.put("itemId", itemId).put("quantity", quantity) else it.put("cents", cents) }
        event("business", JSONObject().put("what", "incident").put("place", placeId).put("kind", kind).put("item", itemId).put("cents", cents).put("day", day))
    }

    /** The calendar's absolute number of in-game day [d] (`dayNumberOf(startDate)` + the days since), what `runDay` takes. */
    private fun absoluteDay(d: Int): Int = ((cal("dayNumberOf", dateJson(startDate[0], startDate[1], startDate[2])) as? Number)?.toInt() ?: 0) + d - 1

    /** Runs every owned shop's books for each day that ended between [fromDay] and now (`runDay`, one day at a time). */
    private fun businessDaysEnded(fromDay: Int) {
        if (businesses.isEmpty()) return
        for (d in fromDay until day) for (pid in businesses.keys.toList()) runBusinessDay(pid, d)
    }

    private fun runBusinessDay(pid: String, d: Int) {
        val ob = businesses[pid] ?: return
        val place = town.placeById[pid]
        val closed = place == null || spansOn(place, d).isEmpty()
        val crowd = skyAt(d, 12.0)?.let { skyEffectsOf(it).optDouble("streetActivityMultiplier", 1.0) } ?: 1.0
        val events = JSONArray()
        val today = pendingEvents.filter { it.optString("placeId") == pid && it.optInt("day") <= d }
        for (e in today) events.put(if (e.optString("kind") == "stock_theft") JSONObject().put("kind", "stock_theft").put("itemId", e.optString("itemId")).put("quantity", e.optInt("quantity"))
            else JSONObject().put("kind", e.optString("kind")).put("cents", e.optInt("cents")))
        pendingEvents.removeAll(today.toSet())
        val cond = JSONObject().put("streetActivity", crowd).put("closed", closed).put("events", events)
        val r = org.json.JSONTokener(call("business.runDay", ob.biz, absoluteDay(d), town.seed, cond)).nextValue() as? JSONObject ?: return
        ob.biz = r.getJSONObject("business")
        ob.log += JSONObject().put("day", d).put("sales", r.optInt("salesCents")).put("profit", r.optInt("profitCents")).put("customers", r.optInt("customers"))
            .put("goods", r.optInt("costOfGoodsCents")).put("restock", r.optInt("restockCents")).put("wages", r.optInt("wagesCents")).put("rent", r.optInt("rentCents"))
            .put("bank", r.optInt("bankCents")).put("loss", r.optInt("lossCents")).put("closed", closed)
        while (ob.log.size > 28) ob.log.removeAt(0)
        val landlord = if (r.isNull("landlord")) "" else r.optString("landlord")
        val bank = if (r.isNull("bank")) "" else r.optString("bank")
        val name = place?.name ?: "your shop"
        event("business", JSONObject().put("what", "day").put("place", pid).put("day", d).put("sales", r.optInt("salesCents")).put("profit", r.optInt("profitCents")).put("loss", r.optInt("lossCents")).put("customers", r.optInt("customers"))
            .put("wages", r.optInt("wagesCents")).put("rent", r.optInt("rentCents")).put("restock", r.optInt("restockCents")).put("goods", r.optInt("costOfGoodsCents")).put("crowd", crowd).put("closed", closed)
            .put("landlord", landlord).put("bank", bank).put("ms", timings["business.runDay"]?.first))
        when {
            landlord == "lockout" -> { businesses.remove(pid); say("The landlord has locked you out of $name: the rent was not paid. The shop is gone."); return }
            bank == "repossess" -> { businesses.remove(pid); say("The bank has repossessed $name: the payments were missed. The shop is gone."); return }
        }
        val warn = when { landlord == "warning" -> "The landlord is waiting for the rent (${ob.biz.optInt("missedRentDays")} day(s) late)."; bank == "warning" -> "The bank is waiting for a payment."; else -> "" }
        if (warn.isNotEmpty() && warn != ob.warning) say("$name: $warn")
        ob.warning = warn
    }

    private fun clerkWageCents(): Int = (econ("wage", "shop_clerk", town.year) as? JSONObject)?.optInt("hourlyCents", 0) ?: 0

    /** Takes on a clerk at an owned shop (`{job: shop_clerk, hours: 8}`, paid by `runDay` for the hours it is open), up to [MAX_CLERKS]. */
    fun hireClerk(place: LoopPlace): LoopOutcome {
        val ob = businesses[place.id] ?: return done(false, "It is not your shop.")
        val staff = ob.biz.optJSONArray("staff") ?: JSONArray()
        if (staff.length() >= MAX_CLERKS) return done(false, "${place.name} has all the clerks it can use.")
        val next = JSONObject(ob.biz.toString())
        next.put("staff", JSONArray(staff.toString()).put(JSONObject().put("job", "shop_clerk").put("hours", 8)))
        ob.biz = next
        return done(true, "You hire a clerk at ${place.name}: ${money(clerkWageCents())} an hour, up to 8 hours a day.")
    }

    /** Lets the last clerk go. */
    fun letClerkGo(place: LoopPlace): LoopOutcome {
        val ob = businesses[place.id] ?: return done(false, "It is not your shop.")
        val staff = ob.biz.optJSONArray("staff") ?: JSONArray()
        if (staff.length() == 0) return done(false, "You have no one to let go.")
        val next = JSONObject(ob.biz.toString())
        val kept = JSONArray(); for (k in 0 until staff.length() - 1) kept.put(staff.getJSONObject(k))
        next.put("staff", kept)
        ob.biz = next
        return done(true, "You let a clerk go at ${place.name}.")
    }

    /** Moves the drawer's cash to the wallet. */
    fun takeCashOut(place: LoopPlace): LoopOutcome {
        val ob = businesses[place.id] ?: return done(false, "It is not your shop.")
        val c = ob.biz.optInt("cashCents")
        if (c <= 0) return done(false, "The drawer is empty.")
        ob.biz = JSONObject(ob.biz.toString()).put("cashCents", 0)
        cash += c
        event("business", JSONObject().put("what", "cash_out").put("place", place.id).put("cents", c))
        return done(true, "You take ${money(c)} out of the drawer at ${place.name}.")
    }

    private fun valuationOf(ob: OwnedBiz): Int = ob.valuation ?: ((org.json.JSONTokener(call("business.valuation", ob.biz, town.year)).nextValue() as? Number)?.toInt() ?: 0).also { ob.valuation = it }

    /** Sells the shop for its going-concern value (`valuation`: fixtures, stock and 90 days of the average profit, less what is owed) and takes the drawer's cash too. */
    fun sellUp(place: LoopPlace): LoopOutcome {
        val ob = businesses[place.id] ?: return done(false, "It is not your shop.")
        val v = valuationOf(ob); val drawer = ob.biz.optInt("cashCents")
        businesses.remove(place.id)
        pendingEvents.removeAll { it.optString("placeId") == place.id }
        cash += v + drawer
        event("business", JSONObject().put("what", "sold").put("place", place.id).put("paid", v).put("drawer", drawer).put("day", day))
        return done(true, "You sell ${place.name} for ${money(v)}" + (if (drawer > 0) " and take the ${money(drawer)} from the drawer" else "") + ".")
    }

    private fun signed(c: Int): String = if (c < 0) "-" + money(-c) else money(c)

    private fun saleViews(shops: List<LoopPlace>): Map<String, SaleView> = shops.mapNotNull { p ->
        if (businesses.containsKey(p.id)) return@mapNotNull null
        val o = offers[p.id] ?: return@mapNotNull null
        val weekly = o.optJSONArray("recentWeeklyTakingsCents")?.let { a -> if (a.length() == 0) 0 else (0 until a.length()).sumOf { a.getInt(it) } / a.length() } ?: 0
        val ask = o.optInt("askingCents"); val lease = o.optInt("leaseMonthlyCents")
        val buyDue = dueFor(o, true); val leaseDue = dueFor(o, false)
        p.id to SaleView(ask, money(ask), lease, money(lease), buyDue, money(buyDue), leaseDue, money(leaseDue), "about ${money(weekly)} a week",
            "For sale ${money(ask)} / To let ${money(lease)} a month")
    }.toMap()

    private fun ownedViews(): Map<String, OwnedShopView> = businesses.mapValues { (_, ob) ->
        val b = ob.biz
        val last = ob.log.lastOrNull()
        val week = ob.log.takeLast(7)
        fun costs(r: JSONObject) = r.optInt("sales") - r.optInt("profit")
        val lastText = if (last == null) "No day has been run yet: the books close at the end of the day." else
            "Last day (${dayInfo(last.optInt("day")).text})" + (if (last.optBoolean("closed")) ", shut" else "") + ": takings ${money(last.optInt("sales"))}, costs ${money(costs(last))}, profit ${signed(last.optInt("profit"))}"
        val weekText = if (week.size < 2) "" else "Last ${week.size} days: takings ${money(week.sumOf { it.optInt("sales") })}, costs ${money(week.sumOf { costs(it) })}, profit ${signed(week.sumOf { it.optInt("profit") })}"
        val staff = b.optJSONArray("staff")?.length() ?: 0
        val v = valuationOf(ob)
        OwnedShopView(ob.tenure, b.optInt("cashCents"), money(b.optInt("cashCents")), staff, MAX_CLERKS, money(clerkWageCents()) + "/h", lastText, weekText, v, money(v), ob.warning)
    }

    /** The meals the room's lodging includes a day (`includedMealsPerDay`: 0 for a boarding house, 2 for room and board), 0 without a room or while locked out. */
    fun boardIncluded(): Int = if (lockedOut) 0 else rental?.let { lodgingTerms(it.kind)?.optInt("includedMealsPerDay", 0) } ?: 0
    fun boardLeft(): Int = max(0, boardIncluded() - if (boardDay == day) boardTaken else 0)
    private fun boardLine(): String = boardIncluded().let { n -> if (n <= 0) "" else "Board: $n meals a day included, ${boardLeft()} left today." }

    /** "Take your board": a meal the room's rent already paid for (`needs.eat` 'boarding_house_board'), up to the included number a day. */
    fun takeBoard(): LoopOutcome {
        if (rental == null) return done(false, "You have no room with board.")
        if (boardIncluded() <= 0) return done(false, "Your room has no board.")
        if (boardLeft() <= 0) return done(false, "You've had today's board.")
        if (boardDay != day) { boardDay = day; boardTaken = 0 }
        boardTaken++
        setNeeds(nd("eat", needsState(), "boarding_house_board") as JSONObject)
        return done(true, "You take your board at ${lodgingName()}." + needsNote())
    }

    /** "Wash up" in the rented room (`needs.wash`). */
    fun washUp(): LoopOutcome {
        if (rental == null || lockedOut) return done(false, "You have nowhere to wash.")
        setNeeds(nd("wash", needsState()) as JSONObject)
        return done(true, "You wash up.")
    }

    /** The player is hurt ([kind]: `bruise`, `sprain`, `cut`; `why`: a crash or a fall). */
    fun injure(kind: String, why: String) {
        setNeeds(nd("injure", needsState(), kind) as JSONObject)
        say("You are hurt ($why): a $kind.")
        event("injury", JSONObject().put("kind", kind).put("why", why).put("day", day).put("hour", hour))
        publish()
    }

    /** The cost of treating [kind] now (`needs.treatmentCost`), cents of the town's year. */
    fun treatmentCents(kind: String): Int = (nd("treatmentCost", kind, town.year) as? Number)?.toInt() ?: 0

    /** Treats an injury at the pharmacy: pays `treatmentCost`, the injury then heals twice as fast (`needs.treat`). */
    fun treat(place: LoopPlace, kind: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (place.shopKind != "pharmacy") return done(false, "${place.name} can't treat that.")
        if (!isOpenNow(place)) return done(false, closed(place))
        val inj = needsState().optJSONArray("injuries")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("kind") == kind } }
            ?: return done(false, "You have no $kind.")
        if (inj.optBoolean("treated")) return done(false, "Your $kind is already treated.")
        val c = treatmentCents(kind)
        if (cash < c) return done(false, "Treating a $kind costs ${money(c)}; you have ${money(cash)}.")
        cash -= c
        setNeeds(nd("treat", needsState(), kind) as JSONObject)
        return done(true, "The pharmacist treats your $kind for ${money(c)}; it will heal faster.")
    }

    private fun needsView(): NeedsView? {
        val n = needsState(); val e = needEffects()
        val inj = n.optJSONArray("injuries")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map {
            val c = treatmentCents(it.optString("kind")); InjuryView(it.optString("kind"), Math.ceil(it.optDouble("remainingHours")).toInt(), it.optBoolean("treated"), c, money(c))
        }
        return NeedsView(labelsOf(e), n.optDouble("hunger"), n.optDouble("fatigue"), n.optDouble("hygiene"), n.optDouble("health"),
            e.optDouble("walkingSpeedMultiplier", 1.0), e.optDouble("socialReactionModifier", 0.0), lookNote.orEmpty(), inj)
    }

    // ------------------------------------------------------------------ the sky (src/weather, docs/WEATHER.md: weather.weatherAt / weatherEffects)

    /** The climate of the town (gamma's: `temperate_coast`), a `weather.weatherAt` climate. */
    var climate = "temperate_coast"
    private val skyCache = HashMap<String, JSONObject>()
    private val skyEffects = HashMap<String, JSONObject>()

    /** The sky on in-game day [d] at hour [h] (`weatherAt` for the date and the town's seed: the same date always gives the same sky); cached per hour. */
    fun skyAt(d: Int, h: Double): JSONObject? {
        val key = "$d|${floor(h).toInt()}"
        skyCache[key]?.let { return it }
        val w = org.json.JSONTokener(call("weather.weatherAt", climate, d - 1, floor(h), town.seed, dateJson(startDate[0], startDate[1], startDate[2]))).nextValue() as? JSONObject ?: return null
        if (skyCache.size > 600) skyCache.clear()
        skyCache[key] = w
        return w
    }

    /** `weather.weatherEffects` of [w]: faceRecognitionM, walkingSpeedMultiplier, streetActivityMultiplier, carGripMultiplier; cached per sky. */
    fun skyEffectsOf(w: JSONObject): JSONObject = skyEffects.getOrPut("${w.optString("sky")}|${w.optInt("temperatureC")}|${w.optInt("visibilityM")}") {
        org.json.JSONTokener(call("weather.weatherEffects", w)).nextValue() as? JSONObject ?: JSONObject()
    }

    /** The sky now. */
    fun skyNow(): JSONObject? = skyAt(day, hour)

    /** How far people outdoors recognise a face now, in street units (35 m on a clear day, 6 m in fog). */
    fun faceRangeUnits(): Float = (skyNow()?.let { skyEffectsOf(it).optDouble("faceRecognitionM", 35.0) } ?: 35.0).toFloat() * VehicleWorld.U

    /** The walking speed the sky and the player's needs allow together (1 = a rested player on a clear day). */
    fun walkMultiplier(): Double {
        val needsW = needEffects().optDouble("walkingSpeedMultiplier", 1.0)
        val skyW = skyNow()?.let { skyEffectsOf(it).optDouble("walkingSpeedMultiplier", 1.0) } ?: 1.0
        return (needsW * skyW).coerceIn(0.3, 1.0)
    }

    private fun skyView(): SkyView? {
        val w = skyNow() ?: return null
        val e = skyEffectsOf(w)
        val t = w.optInt("temperatureC")
        return SkyView(w.optString("sky"), t, w.optInt("windKph"), w.optInt("visibilityM"), e.optDouble("faceRecognitionM"), e.optDouble("walkingSpeedMultiplier", 1.0),
            e.optDouble("streetActivityMultiplier", 1.0), e.optDouble("carGripMultiplier", 1.0),
            "${w.optString("sky")}, $t C, wind ${w.optInt("windKph")} km/h, visibility ${w.optInt("visibilityM")} m; faces seen to %.0f m, walking x%.2f, street x%.2f, grip x%.2f"
                .format(e.optDouble("faceRecognitionM"), e.optDouble("walkingSpeedMultiplier", 1.0), e.optDouble("streetActivityMultiplier", 1.0), e.optDouble("carGripMultiplier", 1.0)))
    }

    // ------------------------------------------------------------------ cars (src/cars, docs/CARS.md: the used-car lot, papers, running costs, breakdowns)

    /** A car the player owns: the module's `Car` record (kept as JSON, saved), the model it is drawn as, its fuel, and where it is. */
    private class Owned(val id: String, var car: JSONObject, val body: String, val specKey: String, val colour: Int, var fuel: Double,
                        var atLot: Boolean = true, var x: Float = 0f, var z: Float = 0f, var fx: Float = 0f, var fz: Float = 1f)
    private val owned = ArrayList<Owned>()
    private var papers = JSONObject().put("registration", false).put("licence", false).put("insurance", false)
    private val runCache = HashMap<String, JSONObject?>()
    private val lotCache = HashMap<String, List<JSONObject>>()
    private var papersCostCache: JSONObject? = null

    private fun cr(method: String, vararg args: Any): Any = org.json.JSONTokener(call("cars.$method", *args)).nextValue()

    private fun resetPersonal() {
        needs = null; effectsCache = null; lookNote = null; boardDay = 0; boardTaken = 0
        owned.clear(); papers = JSONObject().put("registration", false).put("licence", false).put("insurance", false)
        needs = nd("initialNeeds") as JSONObject
        // (the starting state is the module's: not hungry, a little tired)
        effectsCache = null
    }

    private fun savePersonal(o: JSONObject) {
        o.put("needs", needsState()).put("board", JSONObject().put("day", boardDay).put("taken", boardTaken)).put("papers", papers)
        o.put("ownedCars", JSONArray().also { a -> for (c in owned) a.put(JSONObject().put("id", c.id).put("car", c.car).put("body", c.body).put("specKey", c.specKey).put("colour", c.colour)
            .put("fuel", c.fuel).put("atLot", c.atLot).put("x", c.x.toDouble()).put("z", c.z.toDouble()).put("fx", c.fx.toDouble()).put("fz", c.fz.toDouble())) })
    }

    private fun loadPersonal(o: JSONObject) {
        needs = o.optJSONObject("needs") ?: (nd("initialNeeds") as JSONObject)
        effectsCache = null; lookNote = computeLook()
        val b = o.optJSONObject("board"); boardDay = b?.optInt("day") ?: 0; boardTaken = b?.optInt("taken") ?: 0
        papers = o.optJSONObject("papers") ?: JSONObject().put("registration", false).put("licence", false).put("insurance", false)
        owned.clear()
        o.optJSONArray("ownedCars")?.let { a -> for (k in 0 until a.length()) a.getJSONObject(k).let { c ->
            owned += Owned(c.getString("id"), c.getJSONObject("car"), c.getString("body"), c.getString("specKey"), c.optInt("colour"), c.optDouble("fuel"),
                c.optBoolean("atLot", true), c.optDouble("x").toFloat(), c.optDouble("z").toFloat(), c.optDouble("fx").toFloat(), c.optDouble("fz", 1.0).toFloat())
        } }
    }

    private fun running(body: String): JSONObject? = runCache.getOrPut(body) { cr("runningCosts", body, town.year) as? JSONObject }

    /** The gas station's price of fuel a gallon (`runningCosts`' `gasolinePerGallonCents`: the economy's `gasoline`), cents. */
    fun fuelCents(): Int = running("sedan")?.optInt("gasolinePerGallonCents") ?: 0

    /** The cars on the lot now (`cars.carsForSale`, the lot's seed turning over every 28 days), those the player owns left out; asking prices rounded ([LoopCars.round]). */
    fun lotListings(): List<LotCar> {
        val month = (day - 1) / 28
        val seed = "${town.seed}|lot|$month"
        val raw = lotCache.getOrPut(seed) { (cr("carsForSale", town.year, seed) as? JSONArray)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
        return raw.filter { l -> owned.none { it.id == l.getJSONObject("car").getString("id") } }.map { l ->
            val c = l.getJSONObject("car"); val body = c.getString("body"); val my = c.getInt("modelYear")
            val ask = LoopCars.round(l.getInt("askingCents"))
            LotCar(c.getString("id"), body, LoopCars.label(body, my), my, c.optDouble("mileage").roundToInt(), (c.optDouble("condition") * 100).roundToInt(), ask, money(ask), LoopCars.specFor(body, my))
        }
    }

    private fun gasStation(place: LoopPlace) = place.shopKind == "gas station"

    /** Buys the lot's car [lotId] at [place] (the gas station): pays the asking price, the car is the player's, parked at the lot with a little fuel. */
    fun buyCar(place: LoopPlace, lotId: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!gasStation(place)) return done(false, "${place.name} doesn't sell cars.")
        if (!isOpenNow(place)) return done(false, closed(place))
        val lot = lotListings().firstOrNull { it.id == lotId } ?: return done(false, "That car has been sold.")
        if (cash < lot.askingCents) return done(false, "The ${lot.label} is ${lot.askingText}; you have ${money(cash)}.")
        val raw = lotCache.values.flatten().first { it.getJSONObject("car").getString("id") == lotId }.getJSONObject("car")
        cash -= lot.askingCents
        val o = Owned(lotId, JSONObject(raw.toString()), lot.body, lot.specKey, Math.floorMod("${town.seed}|$lotId".hashCode(), 1_000_000), min(LoopCars.tank(lot.body), 5.0))
        owned += o
        event("car_buy", JSONObject().put("car", lotId).put("label", lot.label).put("cents", lot.askingCents).put("model", lot.specKey))
        return done(true, "You buy the ${lot.label} for ${lot.askingText}. It is parked at ${place.name}, with ${fmt(o.fuel)} gallons in the tank.")
    }

    /** The papers' prices (`cars.papersFor`) for the town's year, null before 1905. */
    private fun papersCosts(): JSONObject? = papersCostCache ?: (cr("papersFor", town.year) as? JSONObject)?.also { papersCostCache = it }

    fun hasPaper(kind: String): Boolean = papers.optBoolean(kind)

    /** Buys [kind] (`registration`, `licence`, `insurance`) at the lot at the era's price. */
    fun buyPaper(place: LoopPlace, kind: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!gasStation(place)) return done(false, "${place.name} doesn't do papers.")
        val c = papersCosts()?.takeIf { !it.isNull("${kind}Cents") }?.optInt("${kind}Cents") ?: return done(false, "There is no $kind in ${town.year}.")
        if (hasPaper(kind)) return done(false, "You already have your $kind.")
        if (cash < c) return done(false, "The $kind costs ${money(c)}; you have ${money(cash)}.")
        cash -= c; papers.put(kind, true)
        return done(true, "You buy a $kind for ${money(c)}.")
    }

    private fun breakdownText(b: String) = when (b) { "flat" -> "a flat tyre"; "overheating" -> "the engine is overheating"; "dead_battery" -> "a dead battery"; else -> "the engine has failed" }

    private fun blockedReason(o: Owned): String? =
        o.car.optString("breakdown").takeIf { it.isNotEmpty() && it != "null" }?.let { "broken down: ${breakdownText(it)}" } ?: if (o.fuel <= 0.001) "out of fuel" else null

    /** What fixing [breakdown] costs (`cars.repairCost`; a flat tyre a tenth of the module's number: about $1.50 in 1952 money, not $15), and whether it can be done at the roadside. */
    fun repairOf(breakdown: String): Pair<Int, Boolean>? {
        val r = cr("repairCost", breakdown, town.year) as? JSONObject ?: return null
        val cents = r.optInt("repairCents")
        return (if (breakdown == "flat") max(1, (cents / 10.0).roundToInt()) else cents) to r.optBoolean("roadside")
    }

    /** What a tow to the lot costs: $6 in 1952 money (the module has no tow), scaled by the price index. */
    fun towCents(): Int = max(1, (600.0 * priceIndex / ((econ("priceIndex", 1952) as? Number)?.toDouble() ?: 1.0)).roundToInt())

    private fun ownedBy(id: String): Owned? = owned.firstOrNull { it.id == id }

    /**
     * Driving [miles] in the owned car [id] (the app measures the car's travelled distance): `cars.drive` wears it and may break it down
     * (at the sky's temperature, the town's seed), the tank gives gallons by `runningCosts.mpg`; a breakdown or an empty tank stops the engine.
     */
    fun driveMiles(id: String, miles: Double) {
        val o = ownedBy(id) ?: return
        if (blockedReason(o) != null || miles <= 0.0) return
        val temp = skyNow()?.optInt("temperatureC") ?: 15
        val r = cr("drive", o.car, miles, JSONObject().put("year", town.year).put("temperatureC", temp).put("seed", town.seed)) as? JSONObject ?: return
        o.car = r.getJSONObject("car")
        val done = r.optDouble("milesDriven")
        val mpg = running(o.body)?.optInt("mpg", 20) ?: 20
        o.fuel = max(0.0, o.fuel - done / mpg)
        val b = r.optString("breakdown").takeIf { it.isNotEmpty() && it != "null" }
        val label = LoopCars.label(o.body, o.car.optInt("modelYear"))
        if (b != null) { say("Your $label: ${breakdownText(b)}."); event("car_breakdown", JSONObject().put("car", id).put("kind", b).put("mileage", o.car.optDouble("mileage")).put("tempC", temp)) }
        else if (o.fuel <= 0.001) { say("Your $label is out of fuel."); event("car_empty", JSONObject().put("car", id)) }
        publish()
    }

    /** The car's parked spot (street units) after the player left it. */
    fun parkCar(id: String, x: Float, z: Float, fx: Float, fz: Float) { ownedBy(id)?.let { it.atLot = false; it.x = x; it.z = z; it.fx = fx; it.fz = fz; publish() } }

    /** Fixes [id]'s breakdown for the era's price: at the roadside for a flat or a dead battery, a garage job (at the lot) otherwise. */
    fun repair(id: String, atLot: Boolean): LoopOutcome {
        val o = ownedBy(id) ?: return done(false, "That is not your car.")
        val b = o.car.optString("breakdown").takeIf { it.isNotEmpty() && it != "null" } ?: return done(false, "Nothing is broken.")
        val (cents, roadside) = repairOf(b) ?: return done(false, "No one can fix that in ${town.year}.")
        if (!roadside && !(atLot || o.atLot)) return done(false, "That needs a garage: call a tow.")
        if (cash < cents) return done(false, "Fixing ${breakdownText(b)} costs ${money(cents)}; you have ${money(cash)}.")
        cash -= cents
        o.car = JSONObject(o.car.toString()).put("breakdown", JSONObject.NULL)
        event("car_repair", JSONObject().put("car", id).put("kind", b).put("cents", cents).put("roadside", roadside))
        return done(true, "You fix ${breakdownText(b)} for ${money(cents)}.")
    }

    /** "Call a tow": pays [towCents] and the car is moved to the lot (broken or out of fuel). */
    fun callTow(id: String): LoopOutcome {
        val o = ownedBy(id) ?: return done(false, "That is not your car.")
        if (blockedReason(o) == null) return done(false, "Your car runs: no need for a tow.")
        if (o.atLot) return done(false, "It is already at the lot.")
        val c = towCents()
        if (cash < c) return done(false, "A tow costs ${money(c)}; you have ${money(cash)}.")
        cash -= c
        o.atLot = true
        event("car_tow", JSONObject().put("car", id).put("cents", c))
        return done(true, "The tow truck takes your car to the lot for ${money(c)}.")
    }

    /** A service (`cars.serviceCar`: condition up to 85% at least, service debt cleared, any breakdown cleared) at the lot. */
    fun service(id: String, near: Boolean): LoopOutcome {
        val o = ownedBy(id) ?: return done(false, "That is not your car.")
        if (!near && !o.atLot) return done(false, "Bring the car to the lot.")
        val c = running(o.body)?.optInt("serviceCents") ?: return done(false, "No one services that.")
        if (cash < c) return done(false, "A service costs ${money(c)}; you have ${money(cash)}.")
        cash -= c
        o.car = cr("serviceCar", o.car) as JSONObject
        return done(true, "The car is serviced for ${money(c)}.")
    }

    /** Fuel: buys [gallons] (null: fill the tank) at the gas station's price a gallon (`gasolinePerGallonCents`); the car must be at the pumps ([near]) or the lot. */
    fun refuel(id: String, gallons: Double?, near: Boolean): LoopOutcome {
        val o = ownedBy(id) ?: return done(false, "That is not your car.")
        if (!near && !o.atLot) return done(false, "Bring the car to the pumps.")
        val tank = LoopCars.tank(o.body)
        val room = max(0.0, tank - o.fuel)
        if (room < 0.05) return done(false, "The tank is full.")
        val want = min(gallons ?: room, room)
        val price = fuelCents()
        val cost = (want * price).roundToInt()
        if (cash < cost) return done(false, "${fmt(want)} gallons is ${money(cost)}; you have ${money(cash)}.")
        cash -= cost; o.fuel += want
        event("refuel", JSONObject().put("car", id).put("gallons", want).put("cents", cost).put("pricePerGallon", price))
        return done(true, "You buy ${fmt(want)} gallons at ${money(price)} a gallon: ${money(cost)}.")
    }

    private fun carsView(): CarsView {
        val costs = papersCosts()
        val papersList = listOf("registration" to "Registration", "licence" to "Licence", "insurance" to "Insurance").mapNotNull { (k, label) ->
            costs?.takeIf { !it.isNull("${k}Cents") }?.optInt("${k}Cents")?.let { c -> PaperView(k, label, c, money(c), hasPaper(k)) }
        }
        val req = costs?.optBoolean("insuranceRequired") == true
        val tow = towCents()
        val ownedViews = owned.map { o ->
            val b = o.car.optString("breakdown").takeIf { it.isNotEmpty() && it != "null" }
            val rep = b?.let { repairOf(it) }
            val run = running(o.body)
            LoopCars.label(o.body, o.car.optInt("modelYear")).let { label ->
                OwnedCarView(o.id, label, o.specKey, o.colour, o.body, o.car.optDouble("mileage").roundToInt(), (o.car.optDouble("condition") * 100).roundToInt(),
                    o.fuel, LoopCars.tank(o.body), run?.optInt("mpg") ?: 20, b, b?.let { breakdownText(it) }, rep?.first ?: 0, money(rep?.first ?: 0), rep?.second ?: false, tow, money(tow),
                    run?.optInt("serviceCents") ?: 0, money(run?.optInt("serviceCents") ?: 0), o.atLot, o.x, o.z, o.fx, o.fz, blockedReason(o))
            }
        }
        return CarsView(lotListings(), papersList, if (req && papersList.any { it.kind == "insurance" && !it.have }) "Insurance is required to drive." else "", fuelCents(), money(fuelCents()), ownedViews)
    }

    // ------------------------------------------------------------------ the debug view

    // ------------------------------------------------------------------ maps (docs/MAPS.md)

    /** The paper maps the player owns (saved as "maps"). */
    val ownedMaps = ArrayList<com.lifemax.rpg.feature.habits.maps.OwnedMap>()
    /** The waypoint (street x, z), or null (saved as "waypoint"). */
    var waypoint: FloatArray? = null; private set
    /**
     * The region (the landmass) the town stands on, the road maps' area: set by the game from the planet
     * (PlanetMapModel.regionOf) before the loop starts; "region:home" where the planet is not loaded.
     */
    var region = "region:home"
    private var index1952: Double? = null
    private val mapPriceCache = HashMap<String, Int>()

    /** What a map of [tier] costs at [atGas] (a gas station: free road maps in their era), in the town's year's cents. */
    fun mapCents(tier: com.lifemax.rpg.feature.habits.maps.MapTier, atGas: Boolean): Int = mapPriceCache.getOrPut("$tier|$atGas") {
        val i52 = index1952 ?: ((econ("priceIndex", 1952) as? Number)?.toDouble() ?: 1.0).also { index1952 = it }
        com.lifemax.rpg.feature.habits.maps.MapRules.priceCents(tier, town.year, priceIndex, i52, atGas)
    }

    /** The maps [placeId] sells (a loop place, or [LoopMaps.STATION]), printed this year. */
    fun mapOffers(placeId: String): List<MapOffer> {
        val place = town.placeById[placeId]
        if (place == null && placeId != LoopMaps.STATION) return emptyList()
        val gas = place?.shopKind == "gas station"
        return LoopMaps.tiersAt(placeId, place?.shopKind).map { t ->
            val area = LoopMaps.area(t, town.seed, region)
            val c = mapCents(t, gas)
            MapOffer("${t.name}|$area", t, area, LoopMaps.label(t, town.townName, town.year), c, if (c == 0) "free" else money(c), LoopMaps.has(ownedMaps, t, area, town.year))
        }
    }

    /** Buys (or, free, takes) the map [offerId] at [placeId]: paid from the wallet, kept with its print year (this year). */
    fun buyMap(placeId: String, offerId: String, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        val o = mapOffers(placeId).firstOrNull { it.id == offerId } ?: return done(false, "They don't sell that map here.")
        val place = town.placeById[placeId]
        if (place != null) {
            if (!isOpenNow(place)) return done(false, closed(place))
            val (no, _) = refusal(place, "serve")
            if (no != null) return done(false, no)
        }
        if (o.owned) return done(false, "You already have the ${o.label}.")
        if (cash < o.cents) return done(false, "The ${o.label} is ${o.text}; you have ${money(cash)}.")
        cash -= o.cents
        ownedMaps += com.lifemax.rpg.feature.habits.maps.OwnedMap(o.tier, o.area, town.year, o.label)
        event("map_buy", JSONObject().put("tier", o.tier.name).put("area", o.area).put("cents", o.cents).put("place", placeId))
        return done(true, if (o.cents == 0) "They give you a ${o.label}, free with the gas." else "You buy a ${o.label} for ${o.text}.")
    }

    /** Sets the waypoint at street ([x], [z]) (from the full map), or clears it (null). */
    fun setWaypoint(x: Float?, z: Float?) {
        waypoint = if (x == null || z == null) null else floatArrayOf(x, z)
        event("waypoint", JSONObject().put("set", waypoint != null).also { o -> waypoint?.let { o.put("x", it[0].toDouble()).put("z", it[1].toDouble()) } })
        publish()
    }

    private fun mapsView(shops: List<LoopPlace>): MapsView = MapsView(ownedMaps.toList(), com.lifemax.rpg.feature.habits.maps.MapRules.gps(town.year), region, waypoint?.copyOf(),
        (shops.map { it.id } + LoopMaps.STATION).mapNotNull { id -> mapOffers(id).takeIf { it.isNotEmpty() }?.let { id to it } }.toMap())

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
            rentView(), lodgingOffer(), roomCost(), livingLine(), transitInfo(), railCoef(),
            dayInfo(day).text, dayInfo(day).holiday.orEmpty(), jobsView(shops),
            needsView(), shops.mapNotNull { p -> eatOption(p)?.let { p.id to it } }.toMap(), skyView(), carsView(), walkMultiplier(), boardLine(),
            menus = shops.mapNotNull { p -> menuFor(p)?.let { p.id to it } }.toMap(), forSale = saleViews(shops), owned = ownedViews(), roomsElsewhere = roomsElsewhere, speakeasy = speakeasyView(),
            maps = mapsView(shops))
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
        needsView()?.let { n -> out += "Needs: hunger %.2f, tiredness %.2f, hygiene %.2f, health %.2f".format(n.hunger, n.fatigue, n.hygiene, n.health) +
            (if (n.labels.isNotEmpty()) " (${n.labels.joinToString()})" else "") + ", walking x%.2f, looks: ${n.look.ifEmpty { "fine" }}".format(n.walk) }
        skyView()?.let { out += "Sky: ${it.text}" }
        carsView().let { c -> for (o in c.owned) out += "Car ${o.label}: ${o.mileage} mi, ${o.conditionPct}%% condition, fuel %.1f/%.0f gal".format(o.fuel, o.tank) + (o.blocked?.let { " - $it" } ?: "") }
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

        /** Who speaks a stop's lines ([PoliceStop.lines]): the officer, and the player's own doings in brackets. */
        const val OFFICER = "Officer"
        const val YOU = "You"

        /** How the facade's facts begin when the person knows the player's name ("I know you by name as Alex Mercer."). */
        const val KNOWS_NAME = "I know you by name"

        private fun fmt(h: Double) = if (h == floor(h)) h.toInt().toString() else "%.1f".format(h)

        fun itemJson(i: LoopItem): JSONObject = JSONObject().put("id", i.id).put("key", i.key).put("label", i.label).put("value", i.value)
            .put("stolen", i.stolen).put("day", i.day).also { o -> i.serial?.let { o.put("serial", it) }; i.crimeId?.let { o.put("crimeId", it) }; i.fromPlace?.let { o.put("from", it) } }

        fun itemOf(o: JSONObject) = LoopItem(o.getString("id"), o.optString("key"), o.optString("label"), o.optInt("value"),
            o.optString("serial").ifEmpty { null }, o.optBoolean("stolen"), o.optString("crimeId").ifEmpty { null }, o.optString("from").ifEmpty { null }, o.optInt("day"))
    }
}
