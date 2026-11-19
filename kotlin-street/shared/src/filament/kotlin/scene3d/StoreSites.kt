package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder.Side
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * A store site through the years (docs/STORES.md "Time travel"): street.json v2's `graph.storeSites[]` carries every
 * site the town's plan reserves with its whole life (future events too), so one export can show any year. [StoreSiteInfo.at]
 * gives the [StoreInfo] the builder takes for a year, mirroring the generator's storeState / storeFeatures
 * (src/town/stores.ts; a fixture test keeps the two equal year by year), and [StoreSitePlaceholder] builds the site
 * before its store opens (a field; the year before, a building site). [StoreTraffic] says how full a car park is at an
 * hour of a day, and [StoreCars] builds the parked cars as their own small model, so the town can re-seed them as its
 * clock runs without rebuilding the store.
 */

/** One event in a store site's life (street.json v2 `storeSites[].events[]`). */
data class StoreEvent(
    val year: Int,
    val event: String,
    val name: String,
    val variant: String = "",
    val reuse: String = "",
    val why: String = "",
    val trade: String = "",
    val discount: Boolean = false,
    /** A new storefront from this event (a pack look: "pack:store_s_3"). */
    val look: String = "",
)

/** A store site for every year: its [events] from street.json v2 `graph.storeSites[]`, the town's [people] by year. */
data class StoreSiteInfo(
    val id: String,
    val landmark: String,
    val site: String,
    val style: String,
    val seed: Int,
    val dog: Boolean,
    val events: List<StoreEvent>,
    /** The plot along the street and back from it (street units). */
    val width: Float,
    val depth: Float,
    /** The plot in world units (x0, z0, x1, z1), like the landmarks' rect. */
    val rect: FloatArray,
    val town: String = "",
    /** (year, living people) every five years. */
    val people: List<Pair<Int, Int>> = emptyList(),
) {
    /** The year its store opens (the site is a field before). */
    val opens: Int get() = events.firstOrNull()?.year ?: Int.MAX_VALUE

    /** The town's people in [year] (between the five-yearly counts; the ends held). */
    fun peopleIn(year: Int): Int {
        if (people.isEmpty()) return 0
        if (year <= people.first().first) return people.first().second
        if (year >= people.last().first) return people.last().second
        val k = people.indexOfLast { it.first <= year }
        val (y0, p0) = people[k]; val (y1, p1) = people[k + 1]
        return (p0 + (p1 - p0) * (year - y0).toFloat() / (y1 - y0)).roundToInt()
    }

    /** What the site is in [year], as the generator says (null before its store opens). */
    class State(val kind: String, val state: String, val name: String, val sign: String, val features: List<String>, val reuse: String,
                val closed: Int, val trade: String, val discount: Boolean, val style: String = "")

    fun stateAt(year: Int): State? {
        val past = events.filter { it.year <= year }
        if (past.isEmpty()) return null
        var variant = "general"; var name = ""; var sign = ""; var status = "open"; var reuse = ""; var closed = 0; var trade = ""; var discount = false; var style = this.style
        for (e in past) {
            if (e.look.isNotEmpty()) style = e.look
            if (e.variant.isNotEmpty()) variant = e.variant
            if (e.trade.isNotEmpty()) trade = e.trade
            if (e.event == "opened") discount = e.discount
            if (e.event == "expanded") discount = false
            if (e.event == "opened" || e.event == "renamed" || e.event == "moved") { name = e.name; sign = e.name }
            if (e.event == "closed") { status = "closed"; closed = e.year }
            if (e.event == "reused") { status = "reused"; reuse = e.reuse; sign = e.name }
        }
        val kind = StoreKind.parse(variant) ?: StoreKind.GENERAL
        val st = when (status) { "closed" -> StoreState.CLOSED; "reused" -> StoreState.REUSED; else -> StoreState.OPEN }
        return State(variant, status, name, sign, featuresOf(kind, st, year, StoreReuse.parse(reuse), trade.ifEmpty { null }, discount, dog), reuse, closed, trade, discount, style)
    }

    /** The store for the builder in [year] at [hour] of [weekday] (null before it opens: build [StoreSitePlaceholder]). */
    fun at(year: Int, hour: Float = 13f, weekday: Int = 3): StoreInfo? {
        val s = stateAt(year) ?: return null
        val kind = StoreKind.parse(s.kind) ?: return null
        val st = when (s.state) { "closed" -> StoreState.CLOSED; "reused" -> StoreState.REUSED; else -> StoreState.OPEN }
        return StoreInfo(kind, s.name, year, st, StoreReuse.parse(s.reuse), if (st == StoreState.REUSED) s.sign else "", opens, s.closed,
            width, depth, town, seed, s.style, s.features.toSet(), hour, weekday, peopleIn(year))
    }

    companion object {
        /**
         * The generator's storeFeatures (src/town/stores.ts featuresOf), feature for feature and in its order: what
         * shows on a site of [kind] in [state] in [year].
         */
        fun featuresOf(kind: StoreKind, state: StoreState, year: Int, reuse: StoreReuse?, trade: String?, discount: Boolean, dog: Boolean): List<String> {
            val f = ArrayList<String>()
            val open = state == StoreState.OPEN
            when (kind) {
                StoreKind.GENERAL -> {
                    f += listOf("porch", "bench", "paintedSign", "backyard", "shed")
                    if (open) f += listOf("barrels", "crates", "iceChest", "windowGoods")
                    if (open && year < 1975) f += "gasPump"
                    if (open && year >= 1975) f += listOf("soda", "propaneCage")
                    if (open && trade == "deli") f += "deli"
                    if (open && trade == "bait") f += "bait"
                    if (state == StoreState.CLOSED) f += "boarded"
                }
                StoreKind.MARKET -> {
                    f += listOf("plateGlass", "litSign", "awning", "smallLot", "serviceYard")
                    if (open) f += listOf("windowPosters", "carts")
                }
                StoreKind.SUPERMARKET -> {
                    f += listOf("longGlassFront", "flatRoof", "pylonSign", "carPark", "serviceYard")
                    if (open) f += listOf("cartCorrals", "windowPosters")
                }
                StoreKind.SUPERSTORE -> {
                    f += listOf("flatRoof", "pylonSign", "carPark", "gardenCentre", "loadingDocks", "carParkLamps")
                    if (open) f += "cartCorrals"
                    if (discount) f += "discountStore"
                    if (open && year >= 2000 && !discount) f += "pharmacy"
                    if (open && year >= 2012) f += "pickupBays"
                    if (open && year >= 2020) f += "evChargers"
                }
            }
            if (state == StoreState.CLOSED) f += listOf(if (kind == StoreKind.GENERAL) "fadedSign" else "forLease", "emptyLot")
            if (state == StoreState.CLOSED && kind != StoreKind.GENERAL) f += "securityLights"
            if (state == StoreState.REUSED && reuse != null) f += "reuse:${reuse.name.lowercase()}"
            if (kind == StoreKind.GENERAL && dog && open) f += "dogOnPorch"
            return f
        }

        /** One site from `graph.storeSites[]` (with the graph's `storePeople`). */
        fun fromJson(o: org.json.JSONObject, town: String = "", people: List<Pair<Int, Int>> = emptyList()): StoreSiteInfo {
            val ev = o.optJSONArray("events")
            val events = (0 until (ev?.length() ?: 0)).map { k ->
                val e = ev!!.getJSONObject(k)
                StoreEvent(e.optInt("year"), e.optString("event"), e.optString("name"), e.optString("variant"), e.optString("reuse"), e.optString("why"),
                    e.optString("trade"), e.optBoolean("discount"), e.optString("look"))
            }
            val fp = o.optJSONObject("footprint")
            val r = o.optJSONArray("rect")
            return StoreSiteInfo(o.optString("id"), o.optString("landmark"), o.optString("site"), o.optString("style"), o.optInt("seed", 1), o.optBoolean("dog"),
                events, fp?.optDouble("width")?.toFloat() ?: 0f, fp?.optDouble("depth")?.toFloat() ?: 0f,
                FloatArray(4) { (r?.optDouble(it) ?: 0.0).toFloat() }, town, people)
        }

        /** Every reserved store site of a street.json v2 `graph` (`graph.storeSites`, `graph.storePeople`), or none. */
        fun fromGraph(graph: org.json.JSONObject): List<StoreSiteInfo> {
            val a = graph.optJSONArray("storeSites") ?: return emptyList()
            val pa = graph.optJSONArray("storePeople")
            val people = (0 until (pa?.length() ?: 0)).map { val p = pa!!.getJSONArray(it); p.getInt(0) to p.getInt(1) }
            return (0 until a.length()).map { fromJson(a.getJSONObject(it), graph.optString("town"), people) }
        }
    }

    override fun equals(other: Any?): Boolean = other is StoreSiteInfo && other.id == id && other.events == events
    override fun hashCode(): Int = id.hashCode() * 31 + events.hashCode()
}

/**
 * How full a store's car park is (docs/STORES.md "Car parks"): shoppers by the hour and the day of the week, scaled by
 * the town's people (the store also serves the farms and the next towns: a superstore draws about 0.9 cars per living
 * person at the Saturday-afternoon peak, so a 215-person town fills ~30% of its 650 bays then and ~20% on a weekday
 * lunchtime), plus the staff's cars all day and a few overnight at a 24-hour store; after closing the lot is empty but
 * for the staff by the side door. A reused building fills by its use (a church on Sunday morning, a gym at dawn and
 * after work).
 */
object StoreTraffic {
    /** Opening hours [open, close) of a store of [kind] in [year] (a 24-hour superstore from 1997: 0..24). */
    fun hours(kind: StoreKind, year: Int, weekday: Int): Pair<Float, Float> = when (kind) {
        StoreKind.GENERAL -> if (weekday == 0) 12f to 17f else 7f to 19f
        StoreKind.MARKET -> if (weekday == 0 && year < 1975) 0f to 0f else 8f to 21f
        StoreKind.SUPERMARKET -> 7f to 22f
        StoreKind.SUPERSTORE -> if (year >= 1997) 0f to 24f else 7f to 23f
    }

    /**
     * Shoppers through the day (0..1): on a weekday a late-morning rise, a lunchtime bump and the after-work peak; at the
     * weekend one broad midday peak; little at night.
     */
    fun curve(hour: Float, weekday: Int = 3): Float {
        fun bump(c: Float, w: Float) = exp(-((hour - c) / w).pow(2))
        return if (weekday == 0 || weekday == 6) max(bump(13.5f, 3.2f), .5f * bump(18f, 2f))
        else maxOf(.6f * bump(11f, 2f), .7f * bump(12.5f, .9f), bump(17.5f, 1.6f), .45f * bump(20f, 1.5f))
    }

    /** The day's weight: Saturday busiest, Sunday afternoons next, Monday-Thursday quiet. */
    fun day(weekday: Int): Float = when (weekday) { 6 -> 1f; 0 -> 0.8f; 5 -> 0.85f; else -> 0.65f }

    /** Cars parked at [info]'s store (hour and weekday from it), staff included, at most [bays]. */
    fun cars(info: StoreInfo, bays: Int): Int = (shoppers(info) + staff(info)).coerceIn(0, bays)

    /** The staff's cars (by the side of the building, the far end of the lot). */
    fun staff(info: StoreInfo): Int {
        if (info.state == StoreState.CLOSED) return 0
        val (o, c) = hours(info.kind, info.year, info.weekday)
        val h = info.hour
        val working = c > o && h >= o - 1f && h < c + 1f
        return when {
            info.state == StoreState.REUSED -> if (working) 2 else 0
            info.kind == StoreKind.SUPERSTORE -> if (working) (if (h in 6f..22f) 28 else 10) else 0
            info.kind == StoreKind.SUPERMARKET -> if (working) 10 else 0
            info.kind == StoreKind.MARKET -> if (working) 4 else 0
            else -> if (working) 1 else 0
        }
    }

    /** The shoppers' (or the new use's visitors') cars. */
    fun shoppers(info: StoreInfo): Int {
        if (info.state == StoreState.CLOSED) return 0
        val people = (if (info.people > 0) info.people else 215).toFloat()
        val h = info.hour
        if (info.state == StoreState.REUSED) {
            val wd = info.weekday
            val n = when (info.reuse) {
                StoreReuse.CHURCH -> if (wd == 0 && h in 9f..12.5f) people * 0.25f else if (wd == 3 && h in 18.5f..21f) people * 0.05f else 0f
                StoreReuse.GYM -> if (h in 5.5f..9f || h in 16.5f..20.5f) people * 0.06f else if (h in 9f..21.5f) people * 0.02f else 0f
                StoreReuse.FLEA -> if ((wd == 6 || wd == 0) && h in 8f..16f) people * 0.2f else 0f
                StoreReuse.COMMUNITY -> if (h in 17f..21f) people * 0.05f else if (h in 9f..17f) people * 0.02f else 0f
                else -> if (wd != 0 && h in 9.5f..18f) people * 0.05f else 0f
            }
            return n.roundToInt()
        }
        val (o, c) = hours(info.kind, info.year, info.weekday)
        if (c <= o || h < o || h >= c) return 0
        val perPerson = when (info.kind) { StoreKind.SUPERSTORE -> 0.9f; StoreKind.SUPERMARKET -> 0.45f; StoreKind.MARKET -> 0.16f; StoreKind.GENERAL -> 0.03f }
        // a big store's car park outlives its town's decline less well: few shoppers in its last year
        return (people * perPerson * curve(h, info.weekday) * day(info.weekday)).roundToInt()
    }
}

/** One parking bay of a store's car park: where a car stands, which way it faces, how far it is from the doors. */
class StoreBay(val x: Float, val z: Float, val nose: Float, val along: Boolean, val door: Float, val staff: Boolean = false)

/**
 * The parked cars of a store at its [StoreInfo.hour] and [StoreInfo.weekday] ([StoreTraffic]), as their own small
 * [CinemaModel] beside the store's (the store's [StoreKit.StoreModel.bays] say where): shoppers take the bays nearest
 * the doors first (seeded), the staff the far end; rebuild it when the clock moves on without touching the store.
 */
object StoreCars {
    fun build(info: StoreInfo, bays: List<StoreBay>): CinemaModel {
        val b = StoreB()
        val m = b.det("cars")
        place(b, m, info, bays)
        return b.model(info.kind, info.lot, info.deep)
    }

    /** The cars into [m] (the store's own builder, for [StoreKit.build]'s single model). */
    internal fun place(b: StoreB, m: MeshBuilder, info: StoreInfo, bays: List<StoreBay>) {
        if (bays.isEmpty()) return
        val shop = bays.filter { !it.staff }
        val staffBays = bays.filter { it.staff }.ifEmpty { shop.sortedByDescending { it.door } }
        val n = min(StoreTraffic.shoppers(info), shop.size)
        val ns = min(StoreTraffic.staff(info), staffBays.size)
        // a new set of cars each hour of each day, the same for that hour
        val r = Paint.Rnd(info.seed * 7919 + info.year * 31 + info.weekday * 977 + info.hour.toInt() * 13)
        // weighted sampling without replacement (nearest the doors likeliest): key = u^(1/w)
        val scale = max(200f, shop.maxOfOrNull { it.door }?.let { it * .35f } ?: 200f)
        val picked = shop.map { it to r.next().coerceAtLeast(1e-6f).toDouble().pow(1.0 / (1.0 / (1.0 + it.door / scale))) }.sortedByDescending { it.second }.take(n).map { it.first }
        val taken = HashSet<StoreBay>(picked)
        val staff = staffBays.filter { it !in taken }.take(ns)
        for (bay in picked + staff) {
            val look = StoreGrounds.carLook(b, info.year, 0, r.int(10))
            StoreGrounds.car(b, m, bay.x + (r.next() - .5f) * 4f, bay.z + (r.next() - .5f) * 5f, look, bay.nose, bay.along)
        }
    }
}

/**
 * A store site before its store opens: the field the plan keeps (long grass, a few trees, a farm fence along the road)
 * and, the year before it opens, a building site (graded dirt, a site fence, a COMING SOON board).
 */
object StoreSitePlaceholder {
    fun build(site: StoreSiteInfo, year: Int): CinemaModel {
        val b = StoreB()
        val L = site.width.takeIf { it > 0f } ?: 1000f; val D = site.depth.takeIf { it > 0f } ?: 1000f
        val kind = StoreKind.parse(site.events.firstOrNull()?.variant ?: "") ?: StoreKind.GENERAL
        val building = year == site.opens - 1
        val r = Paint.Rnd(site.seed * 31 + 5)
        val x0 = -L / 2f + 1f; val x1 = L / 2f - 1f; val zb = -D + 1f
        if (building) {
            b.ground(DIRT).box(x0, -1f, zb, x1, .12f, -1f, setOf(Side.TOP))
            val fence = b.pic("siteFence") { PixImg(8, 8).also { im -> for (y in 0 until 8) for (x in 0 until 8) if ((x + y) % 4 == 0 || (x - y + 8) % 4 == 0) im[x, y] = c(0xB8BCC4); im.hline(0, 7, 0, c(0x6A6E78)) } }
            b.tile(fence).box(x0 + 4f, 0f, -14f, x1 - 4f, 60f, -12f, setOf(Side.FRONT, Side.BACK))
            val board = b.pic("soon$kind") { PixImg(StoreKit.tx(120f), StoreKit.tx(60f)).also { im ->
                im.fill(c(0xF6F2E6)); im.border(c(0x1E3A5A)); im.rect(1, 1, im.w - 2, im.h / 3, c(0x1E3A5A))
                StoreSigns.letters(im, "COMING SOON", 3, 2, im.w - 4, im.h / 3 - 1, null, null, 2) { _, _ -> c(0xF6F2E6) }
                StoreSigns.letters(im, if (kind == StoreKind.SUPERSTORE) "NEW STORE" else "NEW MARKET", 3, im.h / 3 + 3, im.w - 4, im.h - 4, null, null, 2) { _, _ -> c(0x1E3A5A) }
            } }
            val m = b.det("site")
            for (dx in listOf(-50f, 50f)) m.beam(MeshBuilder.v(L / 4f + dx, 0f, -30f), MeshBuilder.v(L / 4f + dx, 70f, -30f), 3f, b.swatch(c(0x6A4A2A)))
            CinemaKit.face(m, board, L / 4f, 70f, -28f, 120f, 60f, Side.FRONT)
            // the dirt piles and a parked digger's box
            for (k in 0 until 3) { val px = x0 + L * (.2f + k * .25f); val pz = -D * (.35f + r.next() * .3f); b.box(m, px - 60f, 0f, pz - 40f, px + 60f, 18f + k * 6f, pz + 40f, DIRT_R, 4 + k) }
            b.box(m, 0f, 0f, -D * .5f, 70f, 40f, -D * .5f + 36f, Paint.YELLOW, 6)
        } else {
            b.ground(FIELD).box(x0, -1f, zb, x1, .12f, -1f, setOf(Side.TOP))
            // a farm fence along the road, a few trees and bushes over the field
            val m = b.det("fence")
            var x = x0 + 10f
            while (x < x1) { m.beam(MeshBuilder.v(x, 0f, -16f), MeshBuilder.v(x, 34f, -16f), 2f, b.swatch(c(0x7A6A54))); x += 80f }
            for (y in listOf(14f, 28f)) m.beam(MeshBuilder.v(x0 + 10f, y, -16f), MeshBuilder.v(x1 - 10f, y, -16f), .6f, b.swatch(c(0x8A8A90)))
            val n = (6 + L * D / 120000f).toInt().coerceAtMost(60)
            repeat(n) { StoreGrounds.tree(b, x0 + 90f + r.next() * (L - 180f), -100f - r.next() * (D - 200f), .7f + r.next() * .3f, site.seed + it) }
        }
        return b.model(kind, L, D)
    }

    private fun c(rgb: Long) = Paint.c(rgb)
    private val DIRT_R = Ramp(c(0x3A2A1A), c(0x6A4E34), c(0x7E5E40), c(0x92704E), c(0xA8865E))
    private val DIRT: PixImg by lazy { PixImg(32, 32).also { im -> val r = Paint.Rnd(9); im.fill(DIRT_R.b); for (k in 0 until 140) im[r.int(32), r.int(32)] = if (r.next() < .5f) DIRT_R.d else DIRT_R.l } }
    private val FIELD: PixImg by lazy { PixImg(32, 32).also { im -> val r = Paint.Rnd(4); im.fill(c(0x6A8A3A)); for (k in 0 until 260) { val x = r.int(32); val y = r.int(31); val col = listOf(c(0x7A9A44), c(0x5A7A32), c(0x8AA852), c(0xA8A050))[r.int(4)]; im[x, y] = col; im[x, y + 1] = col } } }
}
