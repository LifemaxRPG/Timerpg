package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.presentation.scene3d.MeshBuilder.Side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The town's stores (docs/STORES.md), made by code like the cinema ([CinemaKit]): low-poly boxes, prisms and cut-out
 * quads wearing pixel pictures painted texel by texel at the buildings' density ([TEXEL]), nearest-sampled, lit and
 * shadowed, whole from every side and from above. Four kinds by the town's size and year ([StoreKind]):
 *  - the GENERAL store of a small town (and of a medium one in the early decades): a wooden false-front store (or a
 *    brick one with a corbelled parapet) on a covered porch with a bench, barrels, crates and an ice chest, a hand-painted
 *    name board, goods in its windows, a gas pump out front in the early decades, a lean-to and a stovepipe behind;
 *    open, closed (boarded up, its sign faded, weeds, a FOR SALE sign) or reused (an antiques shop, a chapel, a cafe);
 *  - the 1950s-60s MARKET: a modest flat-roofed supermarket with a big plate-glass front under a canopy, a lit sign
 *    on the parapet, a sign tower at the road and a small car park;
 *  - the 1970s-80s SUPERMARKET: a larger flat-roofed store with a shake mansard across its front and a backlit sign, a
 *    long glass front between brick piers, cart corrals, a car park under sodium lamps, loading docks behind;
 *  - the 1990s-on SUPERSTORE: a huge big box with entrance towers, a lit name sign, a garden centre, an auto care end,
 *    a giant car park (painted bays, LED lamps, cart corrals, islands with trees), a pylon sign at the road and a truck
 *    court of loading docks behind.
 *
 * The model's frame: x along the street (the plot's middle at 0), y up, z out towards the road; the plot's front edge
 * (where it meets the pavement) at z 0, the plot running back to -[StoreInfo.depth]. Everything stays on the plot
 * (StoreKitTest). The parts and lights are a [CinemaModel]'s, so [CinemaLayer] and [CinemaLights] draw and light a store
 * exactly as they do the theater (signs from dusk, windows from nightfall, real lamps in the car parks).
 */

/** What a store is, by the town's size and year (the town's generator picks; see docs/STORES.md). */
enum class StoreKind {
    /** A small town's general store (wooden false front or brick, a porch). */
    GENERAL,
    /** The 1950s-60s market: a modest supermarket with a plate-glass front and a small lot. */
    MARKET,
    /** The 1970s-80s supermarket: a long glass front under a mansard, a big car park. */
    SUPERMARKET,
    /** The 1990s-on big-box superstore with its car park, garden centre and docks. */
    SUPERSTORE;

    companion object {
        fun parse(s: String): StoreKind? = when (s.lowercase()) { "general" -> GENERAL; "market" -> MARKET; "supermarket" -> SUPERMARKET; "superstore" -> SUPERSTORE; else -> null }
    }
}

/** Whether the store trades ([OPEN]), stands empty ([CLOSED]: boarded up) or is something else now ([REUSED]). */
enum class StoreState { OPEN, CLOSED, REUSED }

/** What a closed general store became. */
enum class StoreReuse {
    ANTIQUES, CHURCH, CAFE, MUSEUM, FEED, THRIFT, GYM, FLEA, OUTLET, COMMUNITY;

    /** The general store's windows and porch for this use (the three looks it has: old goods, a chapel, a cafe; or a store's goods). */
    val look: StoreReuse get() = when (this) { ANTIQUES, MUSEUM, THRIFT, FLEA -> ANTIQUES; CHURCH -> CHURCH; CAFE, COMMUNITY, GYM -> CAFE; FEED, OUTLET -> FEED }
    /** The porch fascia's word for it. */
    val word: String get() = when (this) { ANTIQUES -> "ANTIQUES  COLLECTIBLES"; CHURCH -> "ALL ARE WELCOME"; CAFE -> "COFFEE  PIE  LUNCH"; MUSEUM -> "HISTORICAL MUSEUM";
        FEED -> "FEED  SEED  SUPPLY"; THRIFT -> "THRIFT SHOP"; GYM -> "FITNESS"; FLEA -> "FLEA MARKET"; OUTLET -> "DISCOUNT OUTLET"; COMMUNITY -> "COMMUNITY CENTER" }
    companion object { fun parse(s: String): StoreReuse? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
}

/**
 * One store of the town as the town's generator decides it for the year (TASK A's block, see docs/STORES.md;
 * [fromJson] reads it): its [kind], made-up [name], the town's [year], whether it is [state] open, closed or reused
 * (and as what: [reuse], [reuseName]), the years it [opened] and [closed] (0: unknown / still open), the plot's [width]
 * along the street and [depth] back (street units; 0: the kind's own), the [town]'s name and a [seed] for its looks.
 */
data class StoreInfo(
    val kind: StoreKind,
    val name: String,
    val year: Int,
    val state: StoreState = StoreState.OPEN,
    val reuse: StoreReuse? = null,
    val reuseName: String = "",
    val opened: Int = 0,
    val closed: Int = 0,
    val width: Float = 0f,
    val depth: Float = 0f,
    val town: String = "",
    val seed: Int = 1,
    /** The general store's build: "frontier" (a wooden false front), "brick", or "" to pick by seed. */
    val style: String = "",
    /** What the town's generator says shows this year (docs/STORES.md: gasPump, boarded, ...); empty: by year. */
    val features: Set<String> = emptySet(),
) {
    /** The plot as built: the given size or the kind's own. */
    val lot: Float get() = if (width > 0f) width else StoreKit.defaultLot(kind)
    val deep: Float get() = if (depth > 0f) depth else StoreKit.defaultDeep(kind)

    /** The name the signs spell (the reuse's when it is something else now). */
    val signName: String get() = if (state == StoreState.REUSED && reuseName.isNotBlank()) reuseName else name

    companion object {
        /**
         * One store from street.json v2's `graph.stores[]` entry:
         * `{id, landmark, kind:'general'|'market'|'supermarket'|'superstore', name, year, state:'open'|'closed'|'reused',
         *   reuse?:{kind:'antiques'|'church'|'cafe', name}, opened, closed?, seed, footprint:{width, depth}}`.
         */
        fun fromJson(o: org.json.JSONObject, town: String = ""): StoreInfo? {
            val kind = StoreKind.parse(o.optString("kind")) ?: return null
            val st = when (o.optString("state").lowercase()) { "closed" -> StoreState.CLOSED; "reused" -> StoreState.REUSED; else -> StoreState.OPEN }
            val ru = o.optJSONObject("reuse")
            val fp = o.optJSONObject("footprint")
            val feats = o.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val reuse = ru?.optString("kind")?.let { StoreReuse.parse(it) }
            val sign = o.optString("sign").ifBlank { ru?.optString("name") ?: "" }
            return StoreInfo(kind, o.optString("name"), o.optInt("year"), st, reuse, if (st == StoreState.REUSED) sign else ru?.optString("name") ?: "",
                o.optInt("opened"), o.optInt("closed"), fp?.optDouble("width")?.toFloat() ?: 0f, fp?.optDouble("depth")?.toFloat() ?: 0f,
                o.optString("town", town), o.optInt("seed", 1), o.optString("style"), feats)
        }

        /** Every store in a street.json v2 `graph` (`graph.stores`), or none. */
        fun fromGraph(graph: org.json.JSONObject): List<StoreInfo> {
            val a = graph.optJSONArray("stores") ?: return emptyList()
            return (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it), graph.optString("town")) }
        }

        /** A made-up store for the preview and the tests. */
        fun sample(kind: StoreKind, year: Int, seed: Int = 3, state: StoreState = StoreState.OPEN, reuse: StoreReuse? = null, town: String = "Gamma"): StoreInfo =
            StoreInfo(kind, StoreNames.name(kind, seed, town), year, state, reuse, reuse?.let { StoreNames.reuseName(it, seed, town) } ?: "",
                opened = when (kind) { StoreKind.GENERAL -> 1880 + seed % 30; StoreKind.MARKET -> 1950 + seed % 8; StoreKind.SUPERMARKET -> 1970 + seed % 10; StoreKind.SUPERSTORE -> 1992 + seed % 8 },
                town = town, seed = seed)
    }
}

/**
 * The store builder: its parts by role (each group one atlas, kept small: pictures are cached so a hundred boxes of one
 * size share one picture), its tiled walls, its lights. [model] makes the [CinemaModel] the engine takes.
 */
internal class StoreB {
    val tiles = LinkedHashMap<PixImg, TileMesh>()
    private val groups = LinkedHashMap<String, Pair<MeshBuilder, CinemaPart.Role>>()
    val lights = ArrayList<CinemaLight>()
    private val cache = HashMap<String, PixImg>()
    /** Tiles that throw no shadow (ground). */
    val flat = HashSet<PixImg>()
    /** A shut store: nothing glows and no lamp is lit (masks dropped, lights off). */
    var dark = false

    fun tile(img: PixImg) = tiles.getOrPut(img) { TileMesh(img) }
    fun ground(img: PixImg) = tile(img).also { flat += img }
    fun group(name: String, role: CinemaPart.Role) = groups.getOrPut(name) { MeshBuilder() to role }.first
    /** Things that do not glow (a group per part of the site keeps each atlas small). */
    fun det(name: String = "body") = group("det:$name", CinemaPart.Role.BODY)
    /** Windows lit in the lamp's colour (or their own) from nightfall. */
    fun lit(name: String = "windows") = group("lit:$name", CinemaPart.Role.WINDOWS)
    /** Signs and lit glass in their own colours from dusk. */
    fun sign(name: String = "signs") = group("sign:$name", CinemaPart.Role.SIGN)

    /** A picture made once per [key] (boxes of one size and look share it). */
    fun pic(key: String, make: () -> PixImg): PixImg = cache.getOrPut(key, make)
    fun panel(w: Int, h: Int, r: Ramp, seed: Int = 3, wear: Float = 1f, edges: Boolean = true) = pic("p$w,$h,${r.b},$seed,$wear,$edges") { Paint.panel(w, h, r, seed, edges, wear) }
    fun top(w: Int, h: Int, r: Ramp) = pic("t$w,$h,${r.b}") { CinemaKit.topOf(w, h, r) }
    fun swatch(c: Int) = pic("s$c") { Paint.swatch(c) }

    /** A box in [r]'s panels (outlined, lit top), its pictures shared by size. */
    fun box(m: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Ramp, seed: Int = 3, bottom: Boolean = false, wear: Float = 1f) {
        // a long slab (a walk, a dock) wears the ramp's profile stretched along it, not a picture as long as it is
        if (maxOf(x1 - x0, y1 - y0, z1 - z0) / TEXEL > 300f) return CinemaKit.ledge(m, x0, y0, z0, x1, y1, z1, r, bottom)
        m.box(x0, y0, z0, x1, y1, z1) { s, w, h -> if (s == Side.BOTTOM) (if (bottom) panel(w, h, r, seed, wear) else null) else if (s == Side.TOP) top(w, h, r) else panel(w, h, r, seed, wear) }
    }

    /** A box in one flat colour. */
    fun solid(m: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, col: Int) =
        m.box(x0, y0, z0, x1, y1, z1) { s, _, _ -> if (s == Side.BOTTOM) null else swatch(col) }

    /** Each part's group name, in the model's order (for the preview's report). */
    val names = ArrayList<String>()

    fun model(kind: StoreKind, width: Float, depth: Float): CinemaModel {
        val parts = ArrayList<CinemaPart>()
        for ((img, t) in tiles) if (!t.isEmpty) { parts += CinemaPart(t.build(), img !in flat, true, CinemaPart.Role.BODY); names += "tile" }
        for ((name, g) in groups) {
            val mesh = g.first.build()
            if (mesh.idx.isEmpty()) continue
            val casts = !name.startsWith("det:ground")
            names += name
            parts += if (dark) CinemaPart(PropMesh(mesh.pos, mesh.uv, mesh.nrm, mesh.tan, mesh.idx, mesh.texW, mesh.texH, mesh.tex, null, mesh.bounds), casts, false, CinemaPart.Role.BODY)
            else CinemaPart(mesh, casts, false, g.second)
        }
        val fp = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var top = 0f
        for (p in parts) { val bd = p.mesh.bounds; fp[0] = min(fp[0], bd[0]); fp[1] = min(fp[1], bd[2]); fp[2] = max(fp[2], bd[3]); fp[3] = max(fp[3], bd[5]); top = max(top, bd[4]) }
        // (the cinema's era is a theater's; a store carries the nearest in look, unused by the layer)
        val era = when (kind) { StoreKind.GENERAL -> CinemaEra.PALACE; StoreKind.MARKET -> CinemaEra.MODERN; StoreKind.SUPERMARKET -> CinemaEra.TWIN; StoreKind.SUPERSTORE -> CinemaEra.MULTIPLEX }
        return CinemaModel(era, parts, fp, top, width, depth, if (dark) emptyList() else lights.toList())
    }
}

/** Builds a store for a town's [StoreInfo]. */
object StoreKit {
    internal fun c(rgb: Long) = Paint.c(rgb)
    internal val v = MeshBuilder.Companion::v
    internal fun tx(u: Float) = max(1, (u / TEXEL).roundToInt())

    /** The plot each kind is built for when the town gives none (street units: about 21 a metre). */
    fun defaultLot(k: StoreKind) = when (k) { StoreKind.GENERAL -> 360f; StoreKind.MARKET -> 780f; StoreKind.SUPERMARKET -> 1360f; StoreKind.SUPERSTORE -> 2900f }
    fun defaultDeep(k: StoreKind) = when (k) { StoreKind.GENERAL -> 640f; StoreKind.MARKET -> 1060f; StoreKind.SUPERMARKET -> 1720f; StoreKind.SUPERSTORE -> 3400f }

    /**
     * The store for [info] on its plot with the site round it ([StoreGrounds]): building, grounds, signs and lights, one
     * [CinemaModel] (hand it to [CinemaLayer.pack] / [CinemaLights] and let it go).
     */
    fun build(info: StoreInfo): CinemaModel = buildWithSite(info).model

    /** [build], with where the main block stands ([StoreModel.block]: x0, front z, x1, back z), for cameras and the town. */
    fun buildWithSite(info: StoreInfo): StoreModel {
        val b = StoreB()
        b.dark = info.state == StoreState.CLOSED
        val site = when (info.kind) {
            StoreKind.GENERAL -> general(b, info)
            StoreKind.MARKET -> StoreMarkets.market(b, info)
            StoreKind.SUPERMARKET -> StoreMarkets.supermarket(b, info)
            StoreKind.SUPERSTORE -> StoreMarkets.superstore(b, info)
        }
        StoreGrounds.build(b, info, site)
        val model = b.model(info.kind, info.lot, info.deep)
        return StoreModel(model, floatArrayOf(site.x0, site.zf, site.x1, site.zb), b.names.toList())
    }

    /** A built store: its [model] (the engine's parts and lights) and its main block's plan [block] (x0, front z, x1, back z). */
    class StoreModel(val model: CinemaModel, val block: FloatArray, val names: List<String> = emptyList())

    /**
     * Where the building stands on its plot, for the grounds: the main block [x0]..[x1] from its front wall [zf] back to
     * [zb] (the model's frame), and anything else the grounds must keep clear of (a porch, a garden centre, a pump).
     */
    internal class Site(val x0: Float, val x1: Float, val zf: Float, val zb: Float, val keep: List<FloatArray> = emptyList(), val pump: FloatArray? = null)

    // ------------------------------------------------------------------ shared pictures

    /** Painted clapboard: boards five texels apart, each lit along its top and shadowed under its lap. */
    internal fun clapboard(r: Ramp, seed: Int, peel: Float = 0f): PixImg = PixImg(48, 40).also { im ->
        val rnd = Paint.Rnd(seed)
        for (y in 0 until 40) for (x in 0 until 48) im[x, y] = when (y % 5) { 0 -> r.l; 4 -> r.d; else -> r.b }
        repeat(30) { val x = rnd.int(48); val y = rnd.int(40); if (y % 5 in 1..3) im[x, y] = if (rnd.next() < .5f) r.l else r.d }
        // butt joints, staggered
        for (row in 0 until 8) { val x = (row * 17 + seed * 5) % 48; for (y in row * 5 + 1 until row * 5 + 4) im[x, y] = r.d }
        if (peel > 0f) repeat((48 * 40 * peel / 6).toInt()) {
            val x = rnd.int(46); val y = rnd.int(38)
            val grey = if (rnd.next() < .5f) c(0x8A8274) else c(0x6E685C)
            im[x, y] = grey; im[x + 1, y] = grey; if (rnd.next() < .5f) im[x + 2, y] = grey
        }
    }

    /** Rough boards (a false front's back, a lean-to): vertical planks, unpainted grey-brown, battens. */
    internal val BOARDS: PixImg = PixImg(32, 32).also { im ->
        val rnd = Paint.Rnd(41)
        val tones = intArrayOf(c(0x7A6A58), c(0x84745E), c(0x6E604E))
        for (k in 0 until 4) { val t = tones[rnd.int(3)]; for (y in 0 until 32) for (x in k * 8 until k * 8 + 8) im[x, y] = if (rnd.next() < .06f) Pix.mix(t, c(0x000000), .2f) else t }
        for (k in 0 until 4) { im.vline(k * 8, 0, 31, c(0x4E4438)); im.vline(k * 8 + 1, 0, 31, c(0x9A8A72)) }
    }

    /** Corrugated tin roofing: ribs every four texels, a rust streak or two. */
    internal fun tin(seed: Int, rust: Boolean): PixImg = PixImg(32, 32).also { im ->
        val rnd = Paint.Rnd(seed)
        val b = if (rust) c(0x8A6A5A) else c(0x9AA0AC); val l = if (rust) c(0xA8846A) else c(0xC0C6D0); val d = if (rust) c(0x6A4A3E) else c(0x707684)
        for (y in 0 until 32) for (x in 0 until 32) im[x, y] = when (y % 4) { 0 -> l; 3 -> d; else -> b }
        repeat(if (rust) 60 else 14) { val x = rnd.int(32); val y = rnd.int(32); im[x, y] = c(0x9A5A34) }
        for (x in 0 until 32) if (x % 16 == 0) im.vline(x, 0, 31, d)
    }

    /** Asphalt shingles: courses of tabs in a dark weathered tone. */
    internal fun shingles(r: Ramp, seed: Int): PixImg = PixImg(32, 30).also { im ->
        val rnd = Paint.Rnd(seed)
        for (row in 0 until 6) {
            val off = if (row % 2 == 0) 0 else 4
            for (y in row * 5 until row * 5 + 5) for (x in 0 until 32) im[x, y] = if (y == row * 5 + 4) r.o else if (rnd.next() < .15f) r.l else if (rnd.next() < .1f) r.d else r.b
            for (k in 0 until 4) im.vline((k * 8 + off) % 32, row * 5, row * 5 + 3, r.d)
        }
    }

    /** Split-face block: coursed rough blocks with a deep joint. */
    internal fun blockTile(r: Ramp, seed: Int): PixImg = PixImg(48, 40).also { im ->
        val rnd = Paint.Rnd(seed)
        for (row in 0 until 4) {
            val off = if (row % 2 == 0) 0 else 12
            for (y in row * 10 until row * 10 + 10) for (x in 0 until 48) im[x, y] = if (y == row * 10 + 9) r.o else { val t = rnd.next(); if (t < .2f) r.d else if (t < .4f) r.l else r.b }
            for (k in 0 until 2) im.vline((k * 24 + off) % 48, row * 10, row * 10 + 8, r.o)
        }
    }

    /** Smooth panels (EIFS, porcelain enamel, metal): faint joints [jw] x [jh] texels apart. */
    internal fun panelTile(r: Ramp, seed: Int, jw: Int = 48, jh: Int = 48, specks: Int = 40): PixImg = PixImg(jw, jh).also { im ->
        im.fill(r.b)
        val rnd = Paint.Rnd(seed)
        repeat(specks) { im[rnd.int(jw), rnd.int(jh)] = if (rnd.next() < .5f) r.l else r.d }
        im.hline(0, jw - 1, jh - 1, r.d); im.vline(jw - 1, 0, jh - 1, r.d); im.hline(0, jw - 1, 0, r.l)
    }

    /** Cedar shakes (a 1970s mansard): long split shakes in staggered courses, browns. */
    internal fun shakes(seed: Int): PixImg = PixImg(40, 36).also { im ->
        val rnd = Paint.Rnd(seed)
        val tones = intArrayOf(c(0x6A4428), c(0x7A5232), c(0x5A3A22), c(0x86603A))
        for (row in 0 until 4) {
            var x = (row * 7) % 6
            for (k in 0 until 12) {
                val w = 3 + rnd.int(3); val t = tones[rnd.int(4)]
                for (y in row * 9 until row * 9 + 9) for (xx in x until x + w) im[(xx + 40) % 40, y] = if (y == row * 9 + 8) c(0x2E1E12) else if (y == row * 9) Pix.mix(t, c(0xFFFFFF), .15f) else t
                im.vline((x + 40) % 40, row * 9, row * 9 + 8, c(0x3E2814))
                x += w
            }
        }
    }

    internal val ROOF get() = CinemaKit.ROOF

    /** A picture [img] laid across [x0]..[x1] x [y0]..[y1] at [z] facing +z, in strips no wider than an atlas's 480 texels. */
    internal fun front(m: MeshBuilder, img: PixImg, x0: Float, x1: Float, y0: Float, y1: Float, z: Float, back: Boolean = false) {
        val strips = (img.w + 479) / 480
        for (k in 0 until strips) {
            val a = k * img.w / strips; val e = (k + 1) * img.w / strips
            val part = crop(img, a, e)
            val xa = x0 + (x1 - x0) * a / img.w; val xe = x0 + (x1 - x0) * e / img.w
            if (!back) m.quad(v(xa, y1, z), v(xe, y1, z), v(xe, y0, z), v(xa, y0, z), part)
            else m.quad(v(xe, y1, z), v(xa, y1, z), v(xa, y0, z), v(xe, y0, z), part)
        }
    }

    /** A plinth or band round a block's walls [x0]..[x1] x [zb]..[zf] from [y0] to [y1], [t] proud: four thin ledges (not one slab). */
    internal fun rim(m: MeshBuilder, x0: Float, zb: Float, x1: Float, zf: Float, y0: Float, y1: Float, t: Float, r: Ramp, front: Boolean = true) {
        if (front) CinemaKit.ledge(m, x0 - t, y0, zf - .5f, x1 + t, y1, zf + t, r)
        CinemaKit.ledge(m, x0 - t, y0, zb - t, x1 + t, y1, zb + .5f, r)
        CinemaKit.ledge(m, x0 - t, y0, zb + .5f, x0 + .5f, y1, zf - (if (front) .5f else 0f), r)
        CinemaKit.ledge(m, x1 - .5f, y0, zb + .5f, x1 + t, y1, zf - (if (front) .5f else 0f), r)
    }

    /** Columns [a] until [e] of [im] (its glow mask too, if it glows). */
    internal fun crop(im: PixImg, a: Int, e: Int): PixImg {
        if (a == 0 && e == im.w) return im
        val out = PixImg(e - a, im.h)
        for (y in 0 until im.h) for (x in a until e) out.px[y * out.w + x - a] = im.px[y * im.w + x]
        if (im.glows) {
            out.glow(0, 0, 0, 0, 0)
            for (y in 0 until im.h) for (x in a until e) out.mask[y * out.w + x - a] = im.mask[y * im.w + x]
        }
        return out
    }

    /** [im] filled from a repeating [tile] (so a painted front matches the walls round it). */
    internal fun fillFrom(im: PixImg, tile: PixImg, x0: Int = 0, y0: Int = 0, x1: Int = im.w - 1, y1: Int = im.h - 1, ox: Int = 0, oy: Int = 0) {
        for (y in y0..y1) for (x in x0..x1) im[x, y] = tile.px[((y + oy) % tile.h + tile.h) % tile.h * tile.w + ((x + ox) % tile.w + tile.w) % tile.w]
    }

    /** A sash window picture ([w] x [h] units): its frame and trim in [trim], panes of dark glass lit warm at night. */
    internal fun sash(b: StoreB, w: Float, h: Float, trim: Ramp, lit: Boolean, key: String = "", fill: ((PixImg, Int, Int, Int, Int) -> Unit)? = null): PixImg = b.pic("sash$w,$h,${trim.b},$lit,$key") {
        val im = PixImg(tx(w), tx(h))
        im.fill(trim.l)
        im.border(trim.o)
        val x0 = 2; val x1 = im.w - 3; val y0 = 2; val y1 = im.h - 4
        if (fill != null) fill(im, x0, y0, x1, y1)
        else {
            StoreSigns.glass(im, x0, y0, x1, y1, w.toInt())
            // a curtain and a lamp's glow in the upper room
            im.rect(x0, y0, x0 + 2, y1, c(0xC8B890)); im.rect(x1 - 2, y0, x1, y1, c(0xC8B890))
            if (lit) CinemaKit.lamp(im, x0, y0, x1, y1, 140)
        }
        im.hline(x0, x1, (y0 + y1) / 2, trim.b); im.hline(x0, x1, (y0 + y1) / 2 + 1, trim.d)
        im.vline((x0 + x1) / 2, y0, y1, trim.b)
        im.rect(0, im.h - 3, im.w - 1, im.h - 1, trim.h); im.hline(0, im.w - 1, im.h - 1, trim.o)
        im
    }

    // ------------------------------------------------------------------ palettes

    internal val WHITEWASH = Ramp(c(0x5A5A6A), c(0xC8C4B8), c(0xE4E0D4), c(0xF0ECE2), c(0xFAF8F2))
    internal val BARN = Ramp(c(0x3A1414), c(0x7A2A22), c(0x9A3A2E), c(0xB45040), c(0xC86A58))
    internal val SAGE = Ramp(c(0x2A3A2E), c(0x6A8A6E), c(0x86A486), c(0x9CB89A), c(0xB8D0B4))
    internal val MUSTARD = Ramp(c(0x4A3A14), c(0xA8862E), c(0xC8A23E), c(0xDCBA5A), c(0xECD27E))
    internal val TRIM_W = Ramp(c(0x3A3A4A), c(0xB8B4AC), c(0xDCD8CC), c(0xF2EEE4), c(0xFFFFFA))
    internal val TRIM_G = Ramp(c(0x102018), c(0x1E3A2A), c(0x2A4E38), c(0x3A6A4A), c(0x5A8A68))
    internal val OXBLOOD = Ramp(c(0x200A0A), c(0x4A1414), c(0x5E1C1A), c(0x7A2A26), c(0x9A4A40))
    internal val RED_BRICK = Ramp(c(0x3A1E1A), c(0x7A3A2A), c(0x9A4A34), c(0xB4603E), c(0xC87A52))
    internal val BUFF_BRICK = Ramp(c(0x5A4A36), c(0xB89A70), c(0xD0B48A), c(0xE0C8A0), c(0xEED8B8))
    internal val TAN_BRICK = Ramp(c(0x4A3424), c(0x9A7454), c(0xB08866), c(0xC49C7A), c(0xD8B494))
    internal val DECK = Ramp(c(0x3A2A1E), c(0x6A5038), c(0x84664A), c(0x9C7E5E), c(0xB49674))
    internal val SIGNBOARD = listOf(Ramp(c(0x0E1A12), c(0x1A3022), c(0x22402C), c(0x2E583C), c(0x4A7A58)), OXBLOOD,
        Ramp(c(0x0A0E1E), c(0x16203A), c(0x1E2A4E), c(0x2A3A66), c(0x4A5A8A)), Ramp(c(0x1A1A1A), c(0x2A2A2A), c(0x343434), c(0x444444), c(0x5A5A5A)))
    internal val LETTER = c(0xF4E8C4)

    // ------------------------------------------------------------------ the general store

    /** A small town's general store on its plot; returns where it stands for the grounds. */
    internal fun general(b: StoreB, info: StoreInfo): Site {
        val seed = info.seed
        val L = info.lot; val D = info.deep
        val rnd = Paint.Rnd(seed * 7 + 1)
        val open = info.state == StoreState.OPEN
        val closed = info.state == StoreState.CLOSED
        val reuseKind = if (info.state == StoreState.REUSED) info.reuse ?: StoreReuse.ANTIQUES else null
        val reuse = reuseKind?.look
        val brick = when (info.style) { "brick" -> true; "frontier" -> false; else -> seed % 3 == 2 }
        val paint = listOf(WHITEWASH, BARN, SAGE, MUSTARD)[seed % 4]
        val trim = if (paint == WHITEWASH || paint == MUSTARD) TRIM_G else TRIM_W
        val wallTile = if (brick) CinemaKit.brickTile(RED_BRICK, c(0xB8A088), seed) else clapboard(paint, seed, if (closed) .16f else if (info.year - info.opened > 60 && reuse == null) .12f else 0f)
        // the main block: a little narrower than the plot, a gravel drive down one side
        val W = (L - 130f).coerceIn(170f, 270f)
        val cx = -L / 2f + 32f + W / 2f
        val x0 = cx - W / 2f; val x1 = cx + W / 2f
        val zf = -104f
        val BD = min(380f, D - 104f - 200f).coerceAtLeast(240f)
        val zb = zf - BD
        val EH = 150f
        val pitch = .55f
        val RH = if (brick) EH + 4f else EH + W / 2f * pitch
        val FFH = if (brick) EH + 26f else max(RH + 26f, 236f)
        val ffw = W / 2f + 5f
        val t = b.tile(wallTile)
        val det = b.det()
        // the walls (the front is the painted false front's picture)
        t.box(x0, 0f, zb, x1, EH, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
        // a stone footing round it
        val stone = Ramp(c(0x3A3A40), c(0x6A6A70), c(0x86868C), c(0x9A9AA0), c(0xB4B4BA))
        rim(det, x0, zb, x1, zf, 0f, 8f, 1.5f, stone, front = false)
        // corner boards
        if (!brick) for (x in listOf(x0, x1)) for (z in listOf(zb)) CinemaKit.ledge(det, x - 1.6f, 8f, z - 1.6f, x + 1.6f, EH, z + 1.6f, trim)
        if (!brick) {
            // the gable roof behind the false front: ridge along z, tin or shingles, eaves over the sides
            val roofImg = if (seed % 2 == 0) tin(seed, closed || info.year - info.opened > 50) else shingles(Ramp(c(0x1E1E26), c(0x34343E), c(0x44444E), c(0x56565E), c(0x6A6A74)), seed)
            val rt = b.tile(roofImg)
            val ov = 9f; val ey = EH - ov * pitch
            val rzf = zf - 5f; val rzb = zb - 10f
            rt.quad(v(cx, RH, rzf), v(cx, RH, rzb), v(x1 + ov, ey, rzb), v(x1 + ov, ey, rzf))
            rt.quad(v(cx, RH, rzb), v(cx, RH, rzf), v(x0 - ov, ey, rzf), v(x0 - ov, ey, rzb))
            // the eaves' undersides (boards), a fascia along each eave, the rake boards at the back
            val under = b.tile(BOARDS)
            under.quad(v(x1 + ov, ey, rzf), v(x1 + ov, ey, rzb), v(x1, EH, rzb), v(x1, EH, rzf))
            under.quad(v(x0, EH, rzf), v(x0, EH, rzb), v(x0 - ov, ey, rzb), v(x0 - ov, ey, rzf))
            CinemaKit.ledge(det, x1 + ov - 1.4f, ey - 5f, rzb, x1 + ov + .4f, ey + .6f, rzf, trim)
            CinemaKit.ledge(det, x0 - ov - .4f, ey - 5f, rzb, x0 - ov + 1.4f, ey + .6f, rzf, trim)
            // the back gable (the wall's boards up to the ridge) and its rake boards, an attic vent
            t.quad(v(cx, RH, zb), v(cx, RH, zb), v(x0, EH, zb), v(x1, EH, zb))
            under.quad(v(cx, RH - .3f, rzb), v(cx, RH - .3f, zb), v(x1 + ov, ey - .3f, zb), v(x1 + ov, ey - .3f, rzb))
            under.quad(v(cx, RH - .3f, zb), v(cx, RH - .3f, rzb), v(x0 - ov, ey - .3f, rzb), v(x0 - ov, ey - .3f, zb))
            det.beam(v(x0 - ov, ey - 1.5f, rzb - .8f), v(cx, RH - 1.5f, rzb - .8f), 2.4f, b.swatch(trim.l))
            det.beam(v(cx, RH - 1.5f, rzb - .8f), v(x1 + ov, ey - 1.5f, rzb - .8f), 2.4f, b.swatch(trim.l))
            val vent = b.pic("vent") { PixImg(14, 14).also { im -> im.fill(trim.l); im.border(trim.o); for (y in 3 until 12 step 2) im.hline(2, 11, y, trim.d) } }
            CinemaKit.face(det, vent, cx, EH + (RH - EH) * .45f, zb - .3f, 11f, 11f, Side.BACK)
            // the stovepipe through the roof, its cap
            val px = cx + W * .22f; val pz = zf - BD * .62f
            det.upright(px, pz, EH + (W / 2f - (px - cx)) * pitch - 6f, RH + 26f, 2.6f, 2.6f, 8, Paint.strip(Paint.stripSize(2.6f, 60f).first, 30, Paint.IRON, 3), cap = null)
            det.upright(px, pz, RH + 26f, RH + 30f, 5f, 2f, 8, Paint.strip(Paint.stripSize(5f, 4f).first, 4, Paint.IRON, 4), cap = Paint.cap(2f, Paint.IRON))
        } else {
            // a brick store's flat roof behind its parapet, falling to the back, a corbelled coping, a scupper and downpipe
            b.tile(ROOF).box(x0 + 2f, 0f, zb + 2f, x1 - 2f, EH - .5f, zf - 2f, setOf(Side.TOP))
            t.box(x0, EH - 1f, zb, x1, EH + 10f, zf, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
            rim(det, x0, zb, x1, zf, EH + 10f, EH + 13f, 1f, Paint.CONCRETE)
            b.tile(wallTile).box(x0 + 3f, EH - 1f, zb + 3f, x1 - 3f, EH + 10f, zf - 3f, setOf(Side.LEFT, Side.RIGHT, Side.BACK, Side.FRONT))
            det.beam(v(x1 - 20f, 4f, zb - 2f), v(x1 - 20f, EH, zb - 2f), 2f, b.swatch(Paint.IRON.l))
            b.box(det, cx + 30f, EH - .5f, zb + 40f, cx + 62f, EH + 12f, zb + 64f, Paint.STEEL, 5)
        }
        // the false front (or the brick parapet front): one painted picture, its back boards, its edges and cornice
        val fw = tx(ffw * 2f); val fh = tx(FFH)
        val F = PixImg(fw, fh)
        fun col(x: Float) = ((x - (cx - ffw)) / (ffw * 2f) * fw).toInt()
        fun row(y: Float) = ((FFH - y) / FFH * fh).toInt().coerceIn(0, fh - 1)
        fillFrom(F, wallTile)
        val sb = SIGNBOARD[(seed / 4) % SIGNBOARD.size]
        // the storefront under the porch: kickplates, two display windows, the door, transoms
        val porchTop = 112f
        val wy0 = 26f; val wy1 = 94f
        val dx0 = cx - 21f; val dx1 = cx + 21f
        F.rect(col(cx - ffw + 8f), row(porchTop), col(cx + ffw - 8f), fh - 1, trim.b)
        for ((a, e) in listOf(cx - ffw + 12f to dx0 - 6f, dx1 + 6f to cx + ffw - 12f)) {
            val ca = col(a); val ce = col(e)
            // the kickplate's raised panels
            F.rect(ca, row(wy0 - 2f), ce, row(10f), trim.l); for (k in 0 until 3) { val pa = ca + 2 + k * (ce - ca) / 3; F.rect(pa, row(wy0 - 5f), pa + (ce - ca) / 3 - 4, row(13f), trim.b); F.hline(pa, pa + (ce - ca) / 3 - 4, row(wy0 - 5f), trim.h) }
            val gx0 = ca + 2; val gx1 = ce - 2; val gy0 = row(wy1); val gy1 = row(wy0)
            when {
                closed -> StoreSigns.boarded(F, gx0, gy0, gx1, gy1, seed + ca)
                reuse == StoreReuse.ANTIQUES -> StoreSigns.antiquesWindow(F, gx0, gy0, gx1, gy1, seed + ca)
                reuse == StoreReuse.FEED -> StoreSigns.generalWindow(F, gx0, gy0, gx1, gy1, seed + ca, true)
                reuse == StoreReuse.CHURCH -> StoreSigns.chapelWindow(F, gx0, gy0, gx1, gy1, seed + ca)
                reuse == StoreReuse.CAFE -> StoreSigns.cafeWindow(F, gx0, gy0, gx1, gy1, seed + ca)
                else -> StoreSigns.generalWindow(F, gx0, gy0, gx1, gy1, seed + ca, info.year < 1950)
            }
            F.rect(ca, gy0 - 2, ce, gy0 - 1, trim.l); F.vline(ca, gy0, gy1, trim.o); F.vline(ce, gy0, gy1, trim.o)
            // the transom over it (prism glass) and a sill
            F.rect(ca, row(porchTop - 4f), ce, row(wy1 + 4f), c(0x8AA0B4)); for (x in ca until ce step 4) F.vline(x, row(porchTop - 4f), row(wy1 + 4f), trim.d)
            if (!closed) CinemaKit.lamp(F, ca + 1, row(porchTop - 4f), ce - 1, row(wy1 + 4f), 140)
            F.hline(ca - 1, ce + 1, gy1 + 1, trim.h); F.hline(ca - 1, ce + 1, gy1 + 2, trim.o)
        }
        // the door: a pair with glass above, a screen door, a push bar; boarded with a CLOSED board when shut
        val dy1 = 90f
        F.rect(col(dx0), row(dy1), col(dx1), row(10f), trim.d)
        val doorG = trim.b
        for (k in 0..1) {
            val a = col(dx0 + 2f + k * 20f); val e = col(dx0 + 20f + k * 20f)
            F.rect(a, row(dy1 - 2f), e, row(10f), Pix.mix(doorG, c(0x3A2A1E), .4f))
            StoreSigns.glass(F, a + 2, row(dy1 - 5f), e - 2, row(48f), seed)
            if (open || reuse != null) CinemaKit.lamp(F, a + 2, row(dy1 - 5f), e - 2, row(48f), 200)
            F.rect(a + 2, row(40f), e - 2, row(16f), Pix.mix(doorG, c(0x000000), .2f))
            F.hline(a + 1, e - 1, row(52f), c(0xC8A84A))
        }
        if (open) StoreSigns.small(F, col(cx), row(78f), "OPEN", c(0xC82A2A))
        if (closed) { StoreSigns.boarded(F, col(dx0) + 1, row(dy1 - 1f), col(dx1) - 1, row(12f), seed + 77); StoreSigns.card(F, col(cx) - 13, row(64f), "CLOSED", c(0xE8E0C8), c(0x8A1A1A)) }
        F.rect(col(dx0) - 1, row(dy1 + 6f), col(dx1) + 1, row(dy1), c(0x8AA0B4)); if (!closed) CinemaKit.lamp(F, col(dx0), row(dy1 + 6f), col(dx1), row(dy1), 150)
        // the name board over the porch roof, framed; the upper windows; the cornice
        val by0 = 122f; val by1 = 162f
        F.rect(col(cx - ffw + 8f), row(by1), col(cx + ffw - 8f), row(by0), trim.o)
        F.rect(col(cx - ffw + 10f), row(by1 - 2f), col(cx + ffw - 10f), row(by0 + 2f), sb.b)
        F.hline(col(cx - ffw + 10f), col(cx + ffw - 10f), row(by1 - 2f), sb.l)
        val faded = closed
        val ink = if (faded) Pix.mix(LETTER, sb.b, .55f) else LETTER
        val shadow = if (faded) null else sb.o
        val signName = when { reuse != null -> info.signName; closed -> info.name; else -> info.name }
        StoreSigns.letters(F, signName, col(cx - ffw + 14f), row(by1 - 4f), col(cx + ffw - 14f), row(by0 + 4f), null, shadow, 3) { r, h -> if (r < h / 3) Pix.mix(ink, c(0xFFFFFF), .3f) else ink }
        if (faded) { val r2 = Paint.Rnd(seed + 9); repeat(70) { val x = col(cx - ffw + 12f) + r2.int(max(1, fw - 30)); val y = row(by1 - 3f) + r2.int(max(1, row(by0 + 3f) - row(by1 - 3f))); F[x, y] = Pix.mix(sb.b, c(0x8A8A8A), .4f) } }
        if (!brick) {
            for (wx in listOf(cx - W * .25f, cx + W * .25f)) {
                val a = col(wx - 15f); val e = col(wx + 15f); val top = row(220f); val bot = row(172f)
                F.rect(a - 2, top - 2, e + 2, bot + 3, trim.l); F.border2(a - 2, top - 2, e + 2, bot + 3, trim.o)
                if (closed) StoreSigns.boarded(F, a, top, e, bot, seed + a) else {
                    StoreSigns.glass(F, a, top, e, bot, a); F.rect(a, top, a + 2, bot, c(0xD8C8A0)); F.rect(e - 2, top, e, bot, c(0xD8C8A0))
                    CinemaKit.lamp(F, a, top, e, bot, 130)
                }
                F.hline(a, e, (top + bot) / 2, trim.b); F.vline((a + e) / 2, top, bot, trim.b)
            }
            // the frieze under the cornice: dentils; the founding year in a little panel
            F.rect(0, row(FFH - 2f), fw - 1, row(FFH - 20f), trim.b)
            for (x in 0 until fw step 4) F.rect(x, row(FFH - 18f), x + 1, row(FFH - 22f), trim.l)
            if (info.opened > 0) StoreSigns.small(F, fw / 2, row(FFH - 8f), "EST ${info.opened}", trim.o)
        } else {
            // corbelled brick and a stone lintel band
            F.rect(0, row(FFH), fw - 1, row(FFH - 10f), RED_BRICK.d)
            for (x in 0 until fw step 6) F.rect(x, row(FFH - 10f), x + 3, row(FFH - 14f), RED_BRICK.l)
            F.rect(col(cx - ffw + 8f), row(by1 + 8f), col(cx + ffw - 8f), row(by1 + 4f), c(0xC8C0B0))
            for (wx in listOf(cx - W * .25f, cx + W * .25f)) { val a = col(wx - 12f); val e = col(wx + 12f); F.rect(a, row(EH + 12f), e, row(by1 + 12f), if (closed) c(0x5A4A3A) else c(0x2A3A54)); if (!closed) CinemaKit.lamp(F, a, row(EH + 12f), e, row(by1 + 12f), 170) }
            if (info.opened > 0) StoreSigns.small(F, fw / 2, row(FFH - 2f), "${info.opened}", c(0xE8E0D0))
        }
        // corner boards of the false front
        if (!brick) { F.rect(0, 0, 3, fh - 1, trim.l); F.vline(0, 0, fh - 1, trim.o); F.rect(fw - 4, 0, fw - 1, fh - 1, trim.l); F.vline(fw - 1, 0, fh - 1, trim.o) }
        val lit = b.lit("front")
        front(lit, F, cx - ffw, cx + ffw, 0f, FFH, zf)
        // its edges and back (rough boards), braced to the roof
        val fb = b.tile(if (brick) wallTile else BOARDS)
        fb.box(cx - ffw, EH - 2f, zf - 5f, cx + ffw, FFH, zf, setOf(Side.BACK, Side.LEFT, Side.RIGHT))
        b.tile(wallTile).box(cx - ffw, 0f, zf - 5f, cx - ffw + .01f, EH, zf, setOf(Side.LEFT))
        b.tile(wallTile).box(cx + ffw - .01f, 0f, zf - 5f, cx + ffw, EH, zf, setOf(Side.RIGHT))
        if (!brick) {
            // the cornice: a projecting box with brackets under it, a raised pediment in the middle
            val corn = Ramp(trim.o, trim.d, trim.b, trim.l, trim.h)
            b.box(det, cx - ffw - 4f, FFH - 4f, zf - 6f, cx + ffw + 4f, FFH + 2f, zf + 7f, corn, 4, bottom = true)
            var bx = cx - ffw
            while (bx <= cx + ffw + .1f) { b.box(det, bx - 1.5f, FFH - 16f, zf, bx + 1.5f, FFH - 4f, zf + 5f, corn, 6, bottom = true); bx += (ffw * 2f) / 8f }
            val pw = W * .2f
            b.box(det, cx - pw, FFH + 2f, zf - 4f, cx + pw, FFH + 16f, zf + 2f, corn, 8)
            b.box(det, cx - pw - 3f, FFH + 16f, zf - 5f, cx + pw + 3f, FFH + 19f, zf + 4f, corn, 9, bottom = true)
            for (k in listOf(-1f, 1f)) det.beam(v(cx + k * W * .3f, FFH - 12f, zf - 5f), v(cx + k * W * .18f, RH - 16f + 4f, zf - 70f), 3f, b.swatch(c(0x6A5A48)))
        } else CinemaKit.ledge(det, cx - ffw - 3f, FFH, zf - 6f, cx + ffw + 3f, FFH + 4f, zf + 3f, Paint.CONCRETE, bottom = true)
        if (reuseKind == StoreReuse.CHURCH) {
            // a cross over the front, a little belfry on the ridge
            val gold = b.swatch(c(0xF0E8D8))
            val top = FFH + (if (brick) 4f else 19f)
            det.beam(v(cx, top, zf - 1f), v(cx, top + 40f, zf - 1f), 3.6f, gold)
            det.beam(v(cx - 12f, top + 28f, zf - 1f), v(cx + 12f, top + 28f, zf - 1f), 3.6f, gold)
        }
        // the sides' windows (two storeys) and a side door on the drive's side
        val sideWin = sash(b, 28f, 46f, trim, !closed, if (closed) "x" else "") { im, a, top, e, bot -> if (closed) StoreSigns.boarded(im, a, top, e, bot, 5) else { StoreSigns.glass(im, a, top, e, bot, 3); if (open) CinemaKit.lamp(im, a, top, e, bot, 150) } }
        val upWin = sash(b, 24f, 38f, trim, !closed, if (closed) "x" else "u") { im, a, top, e, bot -> if (closed) StoreSigns.boarded(im, a, top, e, bot, 6) else { StoreSigns.glass(im, a, top, e, bot, 4); im.rect(a, top, a + 1, bot, c(0xD8C8A0)); im.rect(e - 1, top, e, bot, c(0xD8C8A0)); CinemaKit.lamp(im, a, top, e, bot, 170) } }
        val nSide = max(2, (BD / 110f).toInt())
        for (side in listOf(-1, 1)) {
            val x = if (side < 0) x0 - .35f else x1 + .35f
            val n = if (side < 0) Side.LEFT else Side.RIGHT
            for (k in 0 until nSide) {
                val z = zf - BD * (k + .5f) / nSide
                if (side > 0 && k == nSide - 1) continue
                CinemaKit.face(lit, sideWin, x, 54f, z, 28f, 46f, n)
                if (!brick) CinemaKit.face(lit, upWin, x, 116f, z, 24f, 38f, n)
            }
        }
        val doorPic = b.pic("sdoor$closed") { PixImg(tx(22f), tx(48f)).also { im -> im.fill(DECK.b); im.border(DECK.o); for (y in 3 until im.h - 3 step 3) im.hline(2, im.w - 3, y, DECK.d); if (!closed) { im.rect(3, 4, im.w - 4, 18, c(0x2A3A54)); CinemaKit.lamp(im, 3, 4, im.w - 4, 18, 150) }; im[im.w - 4, im.h / 2] = c(0xC8A84A) } }
        val sdz = zf - BD * (nSide - .5f) / nSide
        CinemaKit.face(lit, doorPic, x1 + .35f, 34f, sdz, 22f, 48f, Side.RIGHT)
        b.box(det, x1, 0f, sdz - 16f, x1 + 18f, 10f, sdz + 16f, Paint.CONCRETE, 2)
        b.box(det, x1 + 18f, 0f, sdz - 14f, x1 + 26f, 5f, sdz + 14f, Paint.CONCRETE, 3)
        b.box(det, x1, 64f, sdz - 16f, x1 + 14f, 67f, sdz + 16f, trim, 4, bottom = true)
        // the lean-to behind: a lower shed-roofed storeroom of rough boards, its door and stoop
        val lw = W * .62f; val lx0 = x0 + 8f; val lx1 = lx0 + lw
        val ld = min(96f, D + zb - 140f).coerceAtLeast(60f)
        val lzb = zb - ld
        val lean = b.tile(if (brick) wallTile else BOARDS)
        lean.box(lx0, 0f, lzb, lx1, 84f, zb, setOf(Side.LEFT, Side.RIGHT, Side.BACK))
        lean.quad(v(lx0, 104f, zb), v(lx0, 104f, zb), v(lx0, 84f, lzb), v(lx0, 84f, zb))
        lean.quad(v(lx1, 104f, zb), v(lx1, 104f, zb), v(lx1, 84f, zb), v(lx1, 84f, lzb))
        val lroof = b.tile(tin(seed + 4, true))
        lroof.quad(v(lx0 - 5f, 106f, zb + 1f), v(lx1 + 5f, 106f, zb + 1f), v(lx1 + 5f, 81f, lzb - 8f), v(lx0 - 5f, 81f, lzb - 8f))
        b.tile(BOARDS).quad(v(lx0 - 5f, 81f, lzb - 8f), v(lx1 + 5f, 81f, lzb - 8f), v(lx1 + 5f, 84f, lzb), v(lx0 - 5f, 84f, lzb))
        val backDoor = b.pic("bdoor") { PixImg(tx(24f), tx(50f)).also { im -> im.fill(c(0x7A6A58)); im.border(c(0x3A3028)); for (x in 3 until im.w - 2 step 4) im.vline(x, 2, im.h - 3, c(0x5E5244)); Pix.line(im, 2, im.h - 4, im.w - 3, 4, c(0x5E5244)); im[im.w - 4, im.h / 2] = c(0x2A2A2A) } }
        CinemaKit.face(det, backDoor, lx0 + lw * .35f, 25f, lzb - .3f, 24f, 50f, Side.BACK)
        b.box(det, lx0 + lw * .35f - 18f, 0f, lzb - 20f, lx0 + lw * .35f + 18f, 6f, lzb, Paint.CONCRETE, 5)
        CinemaKit.face(det, sideWin, lx0 + lw * .75f, 50f, lzb - .3f, 28f, 46f, Side.BACK)
        // the main block's back wall above the lean-to: its upper windows
        if (!brick) for (k in listOf(.3f, .72f)) CinemaKit.face(lit, upWin, x0 + W * k, 120f, zb - .35f, 24f, 38f, Side.BACK)
        else for (k in listOf(.3f, .72f)) CinemaKit.face(lit, sideWin, x0 + W * k, 116f, zb - .35f, 28f, 46f, Side.BACK)
        // the porch: a deck on piers, steps, posts, a shed roof with a lettered fascia, railings at the ends
        val pz0 = zf; val pz1 = zf + 72f
        val px0 = cx - ffw - 2f; val px1 = cx + ffw + 2f
        val deckH = 12f
        val plank = b.pic("plank$closed") { PixImg(16, 16).also { im -> im.fill(DECK.l); for (y in 0 until 16 step 4) im.hline(0, 15, y, DECK.d); val r = Paint.Rnd(3); repeat(14) { im[r.int(16), r.int(16)] = DECK.b }; if (closed) { im.rect(0, 5, 15, 7, c(0x2A2018)) } } }
        b.tile(plank).box(px0, 0f, pz0, px1, deckH, pz1, setOf(Side.TOP))
        val skirt = b.pic("skirt") { PixImg(16, 12).also { im -> im.fill(DECK.d); for (x in 0 until 16) for (y in 0 until 12) if ((x + y) % 4 == 0 || (x - y + 16) % 4 == 0) im[x, y] = DECK.l; im.border(DECK.o) } }
        b.tile(skirt).box(px0, 0f, pz0, px1, deckH, pz1, setOf(Side.FRONT, Side.LEFT, Side.RIGHT))
        CinemaKit.ledge(det, px0 - 1f, deckH - 2f, pz1 - 1f, px1 + 1f, deckH + .4f, pz1 + 1.5f, DECK)
        // steps: two treads down to the ground in front of the door
        b.box(det, cx - 30f, 0f, pz1, cx + 30f, 8f, pz1 + 10f, DECK, 5)
        b.box(det, cx - 30f, 0f, pz1 + 10f, cx + 30f, 4f, pz1 + 20f, DECK, 6)
        val postR = Ramp(trim.o, trim.d, trim.b, trim.l, trim.h)
        val nPost = 5
        val roofLo = 100f; val roofHi = porchTop + 2f
        for (k in 0 until nPost) {
            val px = px0 + 5f + (px1 - px0 - 10f) * k / (nPost - 1)
            if (closed && k == 1) { det.beam(v(px, deckH, pz1 - 4f), v(px + 4f, roofLo - 30f, pz1 - 4f), 4f, b.swatch(trim.d)); continue }
            b.box(det, px - 2.5f, deckH, pz1 - 6.5f, px + 2.5f, roofLo, pz1 - 1.5f, postR, 7)
            // brackets
            det.beam(v(px, roofLo - 14f, pz1 - 4f), v(px, roofLo - 2f, pz1 - 16f), 1.6f, b.swatch(trim.l))
            if (k > 0) det.beam(v(px - 2.5f, roofLo - 12f, pz1 - 4f), v(px - 14f, roofLo - 1f, pz1 - 4f), 1.6f, b.swatch(trim.l))
            if (k < nPost - 1) det.beam(v(px + 2.5f, roofLo - 12f, pz1 - 4f), v(px + 14f, roofLo - 1f, pz1 - 4f), 1.6f, b.swatch(trim.l))
        }
        // the porch roof: tin on top, beadboard under, the fascia lettered "GENERAL STORE" (or the reuse's word)
        val pr = b.tile(tin(seed + 2, closed))
        val prz1 = pz1 + 6f
        pr.quad(v(px0 - 3f, roofHi + 3f, pz0), v(px1 + 3f, roofHi + 3f, pz0), v(px1 + 3f, roofLo + 3f, prz1), v(px0 - 3f, roofLo + 3f, prz1))
        val bead = b.pic("bead") { PixImg(16, 16).also { im -> im.fill(c(0xD8D0B8)); for (x in 0 until 16 step 3) im.vline(x, 0, 15, c(0xB8B098)) } }
        b.tile(bead).quad(v(px0 - 3f, roofLo, prz1), v(px1 + 3f, roofLo, prz1), v(px1 + 3f, roofHi, pz0), v(px0 - 3f, roofHi, pz0))
        b.tile(BOARDS).quad(v(px0 - 3f, roofHi + 3f, pz0), v(px0 - 3f, roofLo + 3f, prz1), v(px0 - 3f, roofLo - 12f, prz1), v(px0 - 3f, roofHi, pz0))
        b.tile(BOARDS).quad(v(px1 + 3f, roofLo + 3f, prz1), v(px1 + 3f, roofHi + 3f, pz0), v(px1 + 3f, roofHi, pz0), v(px1 + 3f, roofLo - 12f, prz1))
        val fasciaWord = reuseKind?.word ?: if (info.year < 1930) "GENERAL MERCHANDISE" else "GENERAL STORE"
        val fasc = PixImg(tx(px1 - px0 + 6f), tx(15f)).also { im ->
            im.fill(trim.l); im.hline(0, im.w - 1, 0, trim.h); im.hline(0, im.w - 1, im.h - 1, trim.o); im.hline(0, im.w - 1, im.h - 2, trim.d)
            val inkF = if (closed) Pix.mix(sb.b, trim.l, .6f) else sb.b
            StoreSigns.letters(im, fasciaWord, 4, 2, im.w - 5, im.h - 3, null, null, 2) { _, _ -> inkF }
        }
        front(det, fasc, px0 - 3f, px1 + 3f, roofLo - 12f, roofLo + 3f, prz1 + .05f)
        // railings at the porch's two ends
        val rail = b.swatch(trim.l)
        for (x in listOf(px0 + 1f, px1 - 1f)) {
            det.beam(v(x, deckH + 30f, pz0 + 2f), v(x, deckH + 30f, pz1 - 4f), 2.4f, rail)
            det.beam(v(x, deckH + 4f, pz0 + 2f), v(x, deckH + 4f, pz1 - 4f), 1.6f, rail)
            var z = pz0 + 8f
            while (z < pz1 - 6f) { if (!(closed && z > pz0 + 30f && z < pz0 + 44f)) det.beam(v(x, deckH + 4f, z), v(x, deckH + 30f, z), 1.3f, rail); z += 7f }
        }
        // porch things
        val pd = b.det("porch")
        porchThings(b, pd, info, reuse, cx, ffw, pz0, pz1, deckH, seed)
        // the lights: a bulb under the porch roof, gooseneck lamps on the name board (not when closed)
        if (!closed) {
            val bulb = b.pic("bulb") { PixImg(3, 3).also { im -> im.fill(c(0xFFF0C0)); CinemaKit.glowAll(im) } }
            for (k in listOf(-1f, 1f)) {
                val gx = cx + k * W * .3f
                pd.beam(v(gx, by1 + 6f, zf), v(gx, by1 + 10f, zf + 10f), 1f, b.swatch(Paint.IRON.b))
                b.sign("lamps").box(gx - 3f, by1 + 4f, zf + 8f, gx + 3f, by1 + 9f, zf + 14f) { s, _, _ -> if (s == Side.BOTTOM) bulb else b.swatch(c(0x2E4A3A)) }
                b.lights += CinemaLight(gx, by1 + 2f, zf + 14f, 1f, .82f, .55f, 9000f, 90f, down = true, cone = 1.4f)
            }
            b.sign("lamps").box(cx - 2f, roofHi - 8f, zf + 30f, cx + 2f, roofHi - 4f, zf + 34f) { s, _, _ -> if (s == Side.TOP) null else bulb }
            b.lights += CinemaLight(cx, roofHi - 10f, zf + 32f, 1f, .8f, .5f, 12000f, 120f, down = true, cone = 1.5f)
        }
        // the gas pump out front in the early decades (rusting without its globe once the store has shut)
        val pumpAt = if (if (info.features.isNotEmpty()) "gasPump" in info.features else info.year < 1966 || (info.opened in 1..1965 && !open && info.state != StoreState.REUSED)) floatArrayOf(px1 + 46f, -26f) else null
        if (pumpAt != null) gasPump(b, pumpAt[0], pumpAt[1], info.year, open)
        return Site(x0, x1, zf, lzb, listOf(floatArrayOf(px0 - 4f, pz0, px1 + 4f, pz1 + 22f), floatArrayOf(lx0 - 6f, lzb - 22f, lx1 + 6f, zb), floatArrayOf(x1, sdz - 18f, x1 + 28f, sdz + 18f)), pumpAt)
    }

    private fun PixImg.border2(x0: Int, y0: Int, x1: Int, y1: Int, col: Int) { hline(x0, x1, y0, col); hline(x0, x1, y1, col); vline(x0, y0, y1, col); vline(x1, y0, y1, col) }

    /** The porch's things: a bench, barrels, crates, an ice chest, sacks, a broom; a rocking chair, tables or a notice board for a reuse. */
    private fun porchThings(b: StoreB, m: MeshBuilder, info: StoreInfo, reuse: StoreReuse?, cx: Float, ffw: Float, z0: Float, z1: Float, y: Float, seed: Int) {
        val r = Paint.Rnd(seed + 33)
        val wood = DECK
        val left = cx - ffw + 14f; val right = cx + ffw - 14f
        when {
            info.state == StoreState.CLOSED -> {
                // a broken chair, an old crate, leaves
                b.box(m, right - 30f, y, z0 + 8f, right - 14f, y + 12f, z0 + 24f, wood, 9, wear = 2f)
                for (k in 0 until 9) { val lx = left + r.next() * (right - left); val lz = z0 + 4f + r.next() * (z1 - z0 - 10f); b.solid(m, lx, y, lz, lx + 3f, y + .4f, lz + 2f, if (k % 2 == 0) c(0x8A5A2A) else c(0x6A4A22)) }
            }
            reuse == StoreReuse.ANTIQUES -> {
                rocker(b, m, left + 14f, z0 + 26f, y)
                // a wagon wheel leaning on the wall, milk cans, a washtub
                m.prism(v(right - 18f, y + 15.5f, z0 + 3f), v(0f, 0f, 1f), v(0f, 1f, 0f), 15f, 15f, 2f, 12, null, swatch = b.swatch(c(0x5A3A22)))
                for (k in 0 until 2) m.upright(right - 40f - k * 10f, z0 + 14f, y, y + 16f, 4f, 3.4f, 8, Paint.strip(Paint.stripSize(4f, 16f).first, 20, Paint.STEEL, 3 + k), cap = Paint.cap(3.4f, Paint.STEEL))
                m.upright(left + 44f, z0 + 16f, y, y + 7f, 9f, 11f, 10, Paint.strip(Paint.stripSize(11f, 7f).first, 9, Paint.STEEL, 5), cap = Paint.cap(9f, Paint.IRON))
            }
            reuse == StoreReuse.CAFE -> {
                for (k in 0 until 2) { val tx0 = left + 20f + k * (right - left - 40f); table(b, m, tx0, z0 + 34f, y) }
                // a chalk sandwich board at the steps
                sandwich(b, m, cx + 46f, z1 + 14f, "PIE", "COFFEE")
            }
            reuse == StoreReuse.CHURCH -> {
                bench(b, m, left + 20f, z0 + 14f, y)
                bench(b, m, right - 20f, z0 + 14f, y)
            }
            else -> {
                bench(b, m, left + 22f, z0 + 12f, y)
                // barrels by the door, crates, sacks, the ice chest, a broom rack
                for (k in 0 until 2) m.prism(v(right - 10f - k * 18f, y, z0 + 14f), v(0f, 1f, 0f), v(0f, 0f, 1f), 7.5f, 7.5f, 20f, 10, StoreKit.barrelStrip(b), capFar = Paint.cap(7.5f, DECK))
                b.box(m, right - 30f, y, z0 + 30f, right - 12f, y + 14f, z0 + 46f, wood, 4)
                b.box(m, right - 28f, y + 14f, z0 + 32f, right - 14f, y + 24f, z0 + 44f, wood, 5)
                for (k in 0 until 3) b.box(m, cx + 30f + k * 9f, y, z0 + 6f, cx + 38f + k * 9f, y + 11f, z0 + 12f, Ramp(c(0x6A604A), c(0xB8A884), c(0xD8C8A0), c(0xE8DAB4), c(0xF6EACC)), 6 + k)
                if (info.year >= 1925) {
                    val ice = b.pic("ice") { PixImg(tx(40f), tx(28f)).also { im -> im.fill(c(0xE8ECF0)); im.border(c(0x5A6A7A)); im.hline(1, im.w - 2, 2, c(0xFFFFFF)); StoreSigns.letters(im, "ICE", 3, 5, im.w - 4, im.h - 6, null, null, 2) { _, _ -> c(0x2A5AA8) } } }
                    val iceSide = b.pic("iceS") { PixImg(tx(22f), tx(28f)).also { im -> im.fill(c(0xD8DCE0)); im.border(c(0x5A6A7A)) } }
                    m.box(left + 50f, y, z0 + 6f, left + 90f, y + 28f, z0 + 28f) { s, w, h -> when (s) { Side.BOTTOM -> null; Side.TOP -> b.top(w, h, Paint.WHITE); Side.FRONT -> ice; Side.BACK -> b.panel(w, h, Paint.WHITE); else -> iceSide } }
                }
                // a broom and a shovel leaning by the door
                m.beam(v(cx + 26f, y, z0 + 3f), v(cx + 24f, y + 40f, z0 + 1f), 1.2f, b.swatch(c(0xC8A060)))
                b.solid(m, cx + 24f, y, z0 + 2f, cx + 30f, y + 8f, z0 + 5f, c(0xD8B860))
            }
        }
    }

    internal fun barrelStrip(b: StoreB) = b.pic("barrelStrip") { PixImg(40, 24).also { im ->
        im.fill(c(0x9A6438)); for (x in 0 until 40 step 3) im.vline(x, 0, 23, c(0x6A4428))
        for (yy in listOf(2, 11, 12, 21)) im.hline(0, 39, yy, c(0x4A4A5A))
    } }

    private fun bench(b: StoreB, m: MeshBuilder, x: Float, z: Float, y: Float) {
        val wood = b.swatch(DECK.l); val dark = b.swatch(DECK.d)
        for (dx in listOf(-22f, 22f)) { m.beam(v(x + dx, y, z - 4f), v(x + dx, y + 10f, z - 4f), 1.6f, dark); m.beam(v(x + dx, y, z - 12f), v(x + dx, y + 22f, z - 12f), 1.6f, dark) }
        m.box(x - 25f, y + 10f, z - 13f, x + 25f, y + 11.6f, z - 2f) { s, _, _ -> if (s == Side.BOTTOM) dark else wood }
        m.box(x - 25f, y + 15f, z - 13f, x + 25f, y + 21f, z - 11.6f) { s, _, _ -> if (s == Side.BOTTOM) null else wood }
    }

    private fun rocker(b: StoreB, m: MeshBuilder, x: Float, z: Float, y: Float) {
        val w = b.swatch(c(0x7A4A24))
        for (dx in listOf(-7f, 7f)) { m.beam(v(x + dx, y + 1f, z - 8f), v(x + dx, y + 1f, z + 8f), 1.4f, w); m.beam(v(x + dx, y + 1f, z + 4f), v(x + dx, y + 12f, z + 3f), 1.2f, w); m.beam(v(x + dx, y + 1f, z - 5f), v(x + dx, y + 30f, z - 8f), 1.2f, w) }
        m.box(x - 8f, y + 11f, z - 6f, x + 8f, y + 12.6f, z + 5f) { s, _, _ -> if (s == Side.BOTTOM) null else w }
        for (k in 0 until 4) m.beam(v(x - 7f, y + 16f + k * 4f, z - 6.5f - k * .6f), v(x + 7f, y + 16f + k * 4f, z - 6.5f - k * .6f), 1.2f, w)
    }

    private fun table(b: StoreB, m: MeshBuilder, x: Float, z: Float, y: Float) {
        m.upright(x, z, y, y + 28f, 1.2f, 1.2f, 6, null, swatch = b.swatch(Paint.IRON.b))
        m.upright(x, z, y + 28f, y + 29.4f, 10f, 10f, 10, null, swatch = b.swatch(c(0xE8E0D0)), cap = Paint.cap(10f, Paint.WHITE))
        for (dz in listOf(-16f, 16f)) {
            b.solid(m, x - 6f, y, z + dz - 6f, x + 6f, y + 17f, z + dz + 6f, c(0x2A3A4A)).also { }
            m.box(x - 6f, y + 17f, z + dz + (if (dz < 0) -6f else 4.6f), x + 6f, y + 36f, z + dz + (if (dz < 0) -4.6f else 6f)) { s, _, _ -> if (s == Side.BOTTOM) null else b.swatch(c(0x2A3A4A)) }
        }
    }

    internal fun sandwich(b: StoreB, m: MeshBuilder, x: Float, z: Float, a: String, e: String) {
        val board = b.pic("sand$a$e") { PixImg(tx(22f), tx(30f)).also { im -> im.fill(c(0x23302A)); im.border(c(0x6A4A2A)); StoreSigns.small(im, a, im.w / 2, 5, c(0xF0F0E0)); StoreSigns.small(im, e, im.w / 2, 14, c(0xF6D890)); im.hline(4, im.w - 5, 24, c(0xC8C8B8)) } }
        m.quad(v(x - 11f, 30f, z), v(x + 11f, 30f, z), v(x + 11f, 0f, z + 9f), v(x - 11f, 0f, z + 9f), board)
        m.quad(v(x + 11f, 30f, z), v(x - 11f, 30f, z), v(x - 11f, 0f, z - 9f), v(x + 11f, 0f, z - 9f), board)
    }

    /** A gas pump at ([x], [z]): the tall visible-glass pump before the war, the boxy pump of the fifties; a lit globe on top while it pumps. */
    private fun gasPump(b: StoreB, x: Float, z: Float, year: Int, working: Boolean) {
        val m = b.det("pump")
        val red = if (working) Paint.RED else Ramp(c(0x3A2A22), c(0x6A3A2A), c(0x8A4A34), c(0x9A5A40), c(0xA86A50))
        b.box(m, x - 22f, 0f, z - 16f, x + 22f, 5f, z + 16f, Paint.CONCRETE, 2)
        if (year < 1940) {
            // the visible pump: a tall column, a glass cylinder of fuel at the top, a hose
            m.upright(x, z, 5f, 46f, 6f, 5f, 10, Paint.strip(Paint.stripSize(6f, 41f).first, 34, red, 4), cap = Paint.cap(5f, red))
            val glass = b.pic("pglass$working") { PixImg(Paint.stripSize(5f, 22f).first, 18).also { im -> im.fill(if (working) c(0xE8A83A) else c(0x6A6A70)); for (y in 0 until 18 step 4) im.hline(0, im.w - 1, y, c(0xF0E0B0)); if (working) CinemaKit.glowAll(im, 120) } }
            (if (working) b.sign("pump") else m).upright(x, z, 46f, 62f, 5f, 5f, 10, glass, cap = Paint.cap(5f, red))
            m.upright(x, z, 62f, 65f, 6f, 3f, 10, null, swatch = b.swatch(red.d), cap = Paint.cap(3f, red))
        } else {
            b.box(m, x - 10f, 5f, z - 8f, x + 10f, 56f, z + 8f, red, 6, wear = if (working) .4f else 2f)
            val dial = b.pic("dial$working") { PixImg(tx(14f), tx(14f)).also { im -> im.fill(c(0xF0F0E8)); im.border(c(0x2A2A2A)); Pix.disc(im, im.w / 2f, im.h / 2f, im.w / 2f - 2f, c(0xF6F6F0)); im.hline(3, im.w - 4, im.h / 2, c(0x1A1A1A)); if (working) CinemaKit.glowAll(im, 110) } }
            CinemaKit.face(if (working) b.sign("pump") else m, dial, x, 42f, z + 8.1f, 14f, 14f, Side.FRONT)
            CinemaKit.face(if (working) b.sign("pump") else m, dial, x, 42f, z - 8.1f, 14f, 14f, Side.BACK)
            m.beam(v(x + 10f, 30f, z), v(x + 13f, 34f, z), 2f, b.swatch(c(0x1A1A1A)))
        }
        if (working) {
            val globe = b.pic("globe") { PixImg(Paint.stripSize(6f, 10f).first, 10).also { im -> im.fill(c(0xF6F6EC)); im.rect(0, 3, im.w - 1, 6, c(0xC82A2A)); CinemaKit.glowAll(im, 200) } }
            val gy = if (year < 1940) 65f else 56f
            b.sign("pump").upright(x, z, gy, gy + 10f, 5f, 6f, 10, globe, cap = Paint.cap(6f, Paint.WHITE))
            b.lights += CinemaLight(x, gy + 14f, z, 1f, .7f, .5f, 3000f, 60f)
        }
        // the hose hanging on its hook
        m.beam(v(x - 10f, 40f, z + 4f), v(x - 13f, 20f, z + 6f), 1.4f, b.swatch(c(0x1A1A1A)))
    }
}
