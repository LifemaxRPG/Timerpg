package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.simjs.SimJs
import org.json.JSONArray
import org.json.JSONObject
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
)

/** What the HUD and the debug panel show, made after each burst on the sim's thread (read anywhere). */
class LoopView(
    val cash: Int,
    val items: List<LoopItem>,
    val day: Int,
    val hour: Double,
    /** The debug panel's lines (cash, inventory, the last act's witnesses, heat, cases, the last advance's changes...). */
    val debug: List<String>,
    val officers: List<JSONObject>,
    val messages: List<String>,
)

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

    @Volatile var view: LoopView = LoopView(0, emptyList(), 1, 8.0, emptyList(), emptyList(), emptyList()); private set

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
        cash = LoopItems.startingCash(town.year)
        items.clear(); nextItem = 1
        attitudes.clear(); facts.clear()
        say("Arrived in ${town.townName} with \$$cash.")
        event("init", JSONObject(res.toString()).put("cash", cash).put("places", town.places.count { it.kind != "home" }).put("ms", timings["init"]?.first))
        refreshPolice()
        publish()
        return res
    }

    /** The whole loop as one JSON string: the facade's sim, the wallet and the inventory. */
    fun save(): String {
        val o = JSONObject().put("version", 1).put("town", town.seed).put("sim", call("save"))
            .put("cash", cash).put("nextItem", nextItem).put("items", JSONArray().also { a -> for (i in items) a.put(itemJson(i)) })
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
        attitudes.clear(); facts.clear()
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
        facts[personId] = a.optJSONArray("facts")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty()
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
        return LoopItem("item:${s.key}:$n", s.key, s.label, LoopItems.eraPrice(s.price2025, town.year),
            serial ?: if (s.serial) "SN-%06d".format(Math.floorMod(("${town.seed}|${s.key}|$n").hashCode(), 1_000_000)) else null,
            stolen, crimeId, place.id, day)
    }

    private fun itemPayload(i: LoopItem) = JSONObject().put("id", i.id).put("label", i.label).put("value", i.value).also { o ->
        i.serial?.let { o.put("serialNumber", it) }; i.crimeId?.let { o.put("stolenFromCrimeId", it) }
    }

    private fun act(event: JSONObject, scene: LoopScene): JSONObject {
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

    private fun closed(place: LoopPlace) = "${place.name} is closed (open ${place.open}:00-${place.close}:00)."

    fun buy(place: LoopPlace, s: StockItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!place.openAt(hour)) return done(false, closed(place))
        val (no, wary) = refusal(place, "serve")
        if (no != null) return done(false, no)
        val price = LoopItems.eraPrice(s.price2025, town.year)
        if (cash < price) return done(false, "You can't afford ${s.label} (\$$price; you have \$$cash).")
        val item = newItem(place, s, false, null, null)
        act(JSONObject().put("kind", "buy").put("placeId", place.id).put("item", itemPayload(item)).also { o -> place.keeperId?.let { o.put("clerkId", it) } }, scene)
        cash -= price
        items += item
        return done(true, "Bought ${s.label} for \$$price.", wary = wary)
    }

    fun take(place: LoopPlace, s: StockItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!place.openAt(hour)) return done(false, "The door of ${place.name} is locked.")
        val proto = newItem(place, s, true, null, null)
        val res = act(JSONObject().put("kind", "take").put("subkind", "shoplift").put("placeId", place.id).put("item", itemPayload(proto))
            .also { o -> place.keeperId?.let { o.put("ownerId", it) } }, scene)
        val item = proto.copy(crimeId = res.optString("crimeId").ifEmpty { null }, serial = res.optString("stolenSerialNumber").ifEmpty { proto.serial })
        items += item
        val w = res.optJSONArray("witnessedBy") ?: JSONArray()
        val victim = (0 until w.length()).map { w.getJSONObject(it) }.firstOrNull { it.optBoolean("isVictim") }
        val line = when {
            victim != null -> "You pocket ${s.label}. ${victim.optString("name").substringBefore(' ')} saw that!"
            w.length() > 0 -> "You pocket ${s.label}. You feel eyes on you."
            else -> "You pocket ${s.label}. No one seemed to notice."
        }
        return done(true, line)
    }

    /** The pawn shop's price for [item] (0: they don't buy it). */
    fun pawnPrice(item: LoopItem): Int {
        val s = LoopItems.STOCK.values.flatten().firstOrNull { it.key == item.key } ?: return 0
        if (s.resale <= 0.0) return 0
        return max(1, (item.value * s.resale * (if (item.stolen) LoopItems.HOT_DISCOUNT else 1.0)).roundToInt())
    }

    fun sell(place: LoopPlace, item: LoopItem, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (place.kind != "pawn_shop") return done(false, "${place.name} doesn't buy things.")
        if (!place.openAt(hour)) return done(false, closed(place))
        val (no, wary) = refusal(place, "deal with")
        if (no != null) return done(false, no)
        val price = pawnPrice(item)
        if (price <= 0) return done(false, "They don't take ${item.label}.")
        val res = act(JSONObject().put("kind", "sell").put("placeId", place.id).put("item", itemPayload(item)).also { o -> place.keeperId?.let { o.put("clerkId", it) } }, scene)
        cash += price
        items.removeAll { it.id == item.id }
        if (res.optBoolean("pawnTraceFlagged")) say("(the pawn ledger has ${item.label}: it is on file as stolen)")
        return done(true, "Sold ${item.label} for \$$price." + if (item.stolen) " They didn't ask where it came from." else "", wary = wary)
    }

    /** A shift at [place]: up to 4 hours (not past closing), paid at the end; the clock moves on by it. */
    fun work(place: LoopPlace, scene: LoopScene, gameHour: Double): LoopOutcome {
        clockTo(gameHour, exact = true)
        if (!place.openAt(hour)) return done(false, closed(place))
        val (no, wary) = refusal(place, "take on")
        if (no != null) return done(false, no)
        val hours = min(4.0, floor(place.close - hour))
        if (hours < 1.0) return done(false, "${place.name} closes soon: no shift today.")
        val pay = max(1, (hours * LoopItems.hourlyWage(town.year)).roundToInt())
        val task = LoopItems.task(place.shopKind)
        act(JSONObject().put("kind", "work_shift").put("placeId", place.id).put("hours", hours).put("pay", pay).put("taskLabel", task)
            .also { o -> place.keeperId?.let { o.put("employerId", it) } }, scene)
        advance(hours, "work")
        cash += pay
        return done(true, "You worked ${hours.toInt()} hours $task at ${place.name}: \$$pay.", hours, wary = wary)
    }

    private fun done(ok: Boolean, line: String, hours: Double = 0.0, moveTo: LoopPlace? = null, wary: String? = null): LoopOutcome {
        say(line + (wary?.let { " $it" } ?: ""))
        publish()
        return LoopOutcome(ok, line, hour, hours, moveTo, wary)
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
        val input = JSONObject().put("jurisdictionId", town.jurisdictionId).put("playerCash", cash)
            .put("carriedItemIds", JSONArray(items.map { it.id }))
        officerId?.let { input.put("officerId", it) }
        val r = JSONObject(call("arrest", input))
        lastArrest = r
        val fine = min(r.optInt("fineAmount", 0), cash)
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
        event("arrest", JSONObject().put("officerId", officerId).put("why", why).put("penalty", r.optString("penalty")).put("fine", fine)
            .put("confiscated", JSONArray(gone.toList())).put("charges", r.optJSONArray("charges")).put("summary", r.optString("summary"))
            .put("fromHour", before).put("hoursPassed", passed).put("ms", timings["arrest"]?.first))
        val what = when (r.optString("penalty")) {
            "warning" -> "You get off with a warning."
            "fine" -> "Fined \$$fine."
            "cell_night" -> "${fmt(jailH)} hours in a cell" + (if (fine > 0) " and \$$fine" else "") + "."
            else -> "${fmt(jailH)} hours in jail."
        }
        refreshPolice()
        return done(true, (if (why == "approach_and_question") "An officer stops you and asks questions. " else "Arrested! ") + what +
            (if (lost.isNotEmpty()) " They take ${lost.joinToString { it.label }}." else ""), passed, town.station)
    }

    // ------------------------------------------------------------------ the debug view

    private fun publish() {
        view = LoopView(cash, items.toList(), day, hour, debugLines(), officerPlans, messages.toList())
    }

    private fun debugLines(): List<String> {
        val out = ArrayList<String>()
        out += "Day $day, %02d:%02d · cash \$$cash · ${items.size} item(s)".format(hour.toInt() % 24, ((hour - floor(hour)) * 60).toInt())
        for (i in items) out += "  ${i.label} (\$${i.value})" + (if (i.stolen) " HOT ${i.crimeId ?: ""}" else "") + (i.serial?.let { " #$it" } ?: "")
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
        lastSpot?.let { out += "Last sighting: ${it.optString("officerId")} ${it.optString("action")} (recognition %.2f)".format(it.optDouble("recognitionChance")) }
        lastArrest?.let { out += "Last arrest: ${it.optString("summary")}" }
        out += "Facts cached for ${facts.count { it.value.isNotEmpty() }} people (${facts.size} looked up)"
        out += "Timing (ms, last/max): " + timings.entries.joinToString(" · ") { (k, v) -> "$k %.1f/%.1f".format(v.first, v.second) }
        return out
    }

    companion object {
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
