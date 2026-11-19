package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs

/*
 * The street's small things (hydrants, mailboxes, bins, signs, bollards...) and where they stand, with no drawing in it:
 * scene3d/PropKit.kt makes each one's mesh and pixel picture, scene3d/PropLayer.kt draws them in Filament.
 *
 * Everything is placed in a row's own frame (see [Row]): x along the street, `out` how far from the building line towards
 * the road (the pavement is [Crossroads.PAVE] deep, then the kerb). The player and the passers walk the pavement's middle
 * lanes, so props keep to two strips: the KERB strip along the kerb's edge (almost everything) and the WALL strip against
 * the fronts (planters at a shop's ends). Placement is deterministic, from a seeded hash of the town, the row and the
 * building, never Math.random, like the game's rand(seed, ...keys).
 */

/**
 * One kind of prop: its footprint on the ground ([w] along the street, [d] across it, street units; a person is 36
 * tall, so a metre is about 20.6), its height [h], whether it throws a shadow, whether something on it glows at night,
 * and how many looks it has (colours, a bike in the rack...).
 */
enum class PropKind(val w: Float, val d: Float, val h: Float, val shadow: Boolean, val glows: Boolean, val variants: Int) {
    /** A fire hydrant (variant 0 red, 1 yellow). */
    HYDRANT(9f, 9f, 16.6f, true, false, 2),
    /** The blue street collection box (no service's name or emblem on it: MAIL and an envelope). */
    MAILBOX(14.5f, 14.5f, 26f, true, false, 1),
    /** A house's mailbox on a post, its door to the road (variants: its colours). */
    HOME_MAILBOX(5.5f, 11f, 23f, true, false, 6),
    /** A wheelie bin out on the kerb, handle to the road (green, recycling blue lid, grey, garden brown lid). */
    WHEELIE_BIN(12f, 14.5f, 22f, true, false, 4),
    /** The public litter bin: slatted, a domed lid (green, black). */
    LITTER_BIN(11f, 11f, 19.5f, true, false, 2),
    /** A newspaper vending box on its pedestal (red, blue, yellow, green). */
    NEWS_BOX(14.5f, 10f, 21f, true, false, 4),
    /** A post (variant 0 iron, 1 lit: a band near its top glows at night). */
    BOLLARD(5f, 5f, 18.5f, true, true, 2),
    /** A trough of flowers (wood, terracotta, stone; the flowers' colours vary with it). */
    PLANTER(21f, 11f, 16f, true, false, 3),
    /** Two hoops (variant 1 with a bike locked to one). */
    BIKE_RACK(36f, 9f, 20f, true, false, 2),
    /** The street name sign at a corner: this street's name on one blade, the crossing street's on the other. */
    STREET_SIGN(3.2f, 3.2f, 66.2f, true, false, 1),
    /** A stop sign, turned to the traffic coming up to the crossing. */
    STOP_SIGN(3f, 3f, 52f, true, false, 1),
    /** A pedestrian crossing sign (a walking figure on a yellow diamond). */
    PED_SIGN(3f, 3f, 52f, true, false, 1),
    /** A fire alarm call post: a red pillar, its box, a lamp on top that glows at night. */
    CALL_BOX(7f, 7f, 29f, true, true, 1),
    /** A street utility cabinet (green-grey, beige). */
    UTILITY_BOX(19f, 10f, 24f, true, false, 2),
    /** A traffic cone. */
    CONE(7.5f, 7.5f, 14.5f, true, false, 1),
    /** A park bench, slats and iron ends (the bench sprite in 3D), facing the road. */
    BENCH(37f, 14f, 17.5f, true, false, 1),
    /** A parking meter (its little display glows at night). */
    PARKING_METER(5f, 5f, 25f, true, true, 1),
    /** A young street tree in its guard (bars, rings and a grate). */
    TREE_GUARD(14f, 14f, 44f, true, false, 1),
}

/** Where a prop strip runs: against the fronts, or along the kerb. */
enum class PropStrip { WALL, KERB }

/**
 * One prop placed on a street: [kind] and [variant] at [x] along it, its middle [out] from the building line towards the
 * road; [turn] its turn about the vertical (radians; 0: its front faces the road, positive turns its front towards +x);
 * [text] what a sign says (the street's name, the crossing's); [seed] for its own small differences (wear, flowers).
 */
data class PropSpot(
    val kind: PropKind,
    val variant: Int,
    val x: Float,
    val out: Float,
    val turn: Float = 0f,
    val text: List<String> = emptyList(),
    val seed: Int = 0,
) {
    val strip: PropStrip get() = if (out < PropRules.MIDDLE) PropStrip.WALL else PropStrip.KERB
    /** Its footprint on the ground (x0, x1 along, out0, out1 across), turned a quarter if it is turned more than 45 degrees. */
    fun footprint(): FloatArray {
        val q = abs(Math.sin(turn.toDouble())) > .7071
        val hw = (if (q) kind.d else kind.w) / 2f
        val hd = (if (q) kind.w else kind.d) / 2f
        return floatArrayOf(x - hw, x + hw, out - hd, out + hd)
    }
}

/** A building's front on a street, as the props see it: where its door is and what it is. */
class PropFront(
    val x0: Float,
    val x1: Float,
    val kind: PlacementKind,
    val neighbourhood: String,
    /** The landmark's kind (post, school, fire, police, hospital, hotel...), for a landmark. */
    val landmark: String? = null,
    /** Its own key for the hash (the sprite). */
    val id: String,
    /** Where its door opens onto the pavement (the front's middle unless known). */
    val door: Float = (x0 + x1) / 2f,
)

/**
 * A corner of this street's pavement at a crossing: [x] where the crossing street's building line meets ours, [dir] +1
 * when our pavement runs on to +x from it (the corner's square, shared with the crossing street's pavement, is then
 * x - 38 .. x), -1 when it runs on to -x (x .. x + 38). [cross]: the crossing street's name; [stop]: traffic on our road
 * stops here; [ped]: a zebra (a pedestrian sign); [sign]: this street puts up the corner's name sign (one per corner).
 */
class PropCorner(val x: Float, val dir: Int, val cross: String?, val stop: Boolean, val ped: Boolean, val sign: Boolean)

/**
 * One street (a row of the town, or one of the crossroads' streets) as the prop rules see it: its own [key] for the hash,
 * [name], the pavement from [x0] to [x1], the crossing roads' carriageways where there is no pavement ([gaps]), its
 * fronts, the lamps', trees' and benches' places ([furniture]), its corners, and what sort of road it is.
 */
class PropStreet(
    val key: String,
    val name: String,
    val x0: Float,
    val x1: Float,
    val fronts: List<PropFront>,
    val furniture: List<Float> = emptyList(),
    val corners: List<PropCorner> = emptyList(),
    val gaps: List<Pair<Float, Float>> = emptyList(),
    val main: Boolean = false,
    val civic: Boolean = false,
)

object PropRules {
    /** The kerb strip's outer edge: props at the kerb stand with their road side here (the kerb itself is at 33). */
    const val KERB_EDGE = Crossroads.PAVE - 1.5f
    /** Nothing on the walking lanes: every kerb prop's inner side is at least this far out (lane 8 and a shoulder). */
    const val WALK_EDGE = 17f
    /** Wall props stand within this of the fronts. */
    const val WALL_EDGE = 12f
    /** Below this a prop is in the wall strip, above it the kerb strip. */
    const val MIDDLE = 16f
    /** Either side of a door, kept clear across the whole pavement. */
    const val DOOR_HALF = 9f
    /** Where the person who keeps the building stands (its middle + 30, lane 3.6: StreetLifePainter), kept clear. */
    const val KEEPER_X = 30f
    /** Either side of a lamp, tree or bench of the street's own furniture, kept clear. */
    const val FURNITURE_HALF = 8f
    /** Free space kept between two props. */
    const val GAP = 2.5f
    /** The corner's square: a crossing street's pavement and kerb. */
    const val CORNER = Crossroads.PAVE + Crossroads.KERB

    /** How busy each neighbourhood's pavements are (a multiplier on every chance), by its id. */
    val density: Map<String, Float> = mapOf("old-town" to 1.25f, "heights" to .8f, "gardens" to 1f, "woodside" to .6f, "waterfront" to .9f)

    /** Landmarks that get bollards along their fronts (the civic buildings and the hotel). */
    private val civicKinds = setOf("school", "fire", "police", "hospital", "hotel", "clocktower", "townhall", "library", "museum", "church")

    /**
     * A number in [0, 1) from [keys], always the same for the same keys (FNV-1a over their text, then a 64-bit mix): the
     * game's rand(seed, ...keys).
     */
    fun rand(vararg keys: Any): Float {
        var h = -0x340d631b7bdddcdbL
        for ((i, k) in keys.withIndex()) {
            if (i > 0) { h = h xor 0x1fL; h *= 0x100000001b3L }
            for (c in k.toString()) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        }
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        return ((h ushr 40).toFloat()) / 16777216f
    }

    /** A whole number in 0 until [n] from [keys]. */
    fun pick(n: Int, vararg keys: Any): Int = (rand(*keys) * n).toInt().coerceIn(0, n - 1)

    /** Where a kerb prop's middle stands across the pavement (its road side at [KERB_EDGE]). */
    fun kerbOut(kind: PropKind, turned: Boolean = false): Float = KERB_EDGE - (if (turned) kind.w else kind.d) / 2f
    fun wallOut(kind: PropKind): Float = 1f + kind.d / 2f

    /** The text on a sign: the name in capitals, its last word shortened as street signs do. */
    fun signText(name: String): String {
        val words = name.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) return ""
        val short = mapOf(
            "STREET" to "ST", "AVENUE" to "AVE", "LANE" to "LN", "ROAD" to "RD", "DRIVE" to "DR", "BOULEVARD" to "BLVD",
            "COURT" to "CT", "PLACE" to "PL", "CLOSE" to "CL", "CRESCENT" to "CRES", "TERRACE" to "TER", "PARKWAY" to "PKWY",
            "HIGHWAY" to "HWY", "SQUARE" to "SQ", "GARDENS" to "GDNS", "GROVE" to "GR",
        )
        if (words.size > 1) short[words.last()]?.let { words[words.size - 1] = it }
        var s = words.joinToString(" ")
        if (s.length > 14) s = s.take(14).trimEnd()
        return s
    }

    /** The props of [street], in order along it. */
    fun place(street: PropStreet): List<PropSpot> = Placer(street).run()

    private class Placer(val st: PropStreet) {
        val out = ArrayList<PropSpot>()
        /** Kept clear: x0, x1, out0, out1. */
        val blocked = ArrayList<FloatArray>()

        fun dens(hood: String) = density[hood] ?: 1f

        fun clear(f: FloatArray): Boolean {
            if (f[0] < st.x0 || f[1] > st.x1) return false
            for (g in st.gaps) if (f[1] > g.first && f[0] < g.second) return false
            for (b in blocked) if (f[0] < b[1] && f[1] > b[0] && f[2] < b[3] && f[3] > b[2]) return false
            for (p in out) {
                val o = p.footprint()
                if (f[0] < o[1] + GAP && f[1] > o[0] - GAP && f[2] < o[3] + GAP && f[3] > o[2] - GAP) return false
            }
            return true
        }

        /**
         * Puts [kind] at [x] (or as near as a few steps either way, towards [prefer] first: +1 or -1) in its strip; null
         * when there is no room.
         */
        fun put(kind: PropKind, variant: Int, x: Float, wall: Boolean = false, turn: Float = 0f, text: List<String> = emptyList(), prefer: Int = 1, tries: Int = 4, outAt: Float? = null): PropSpot? {
            val turned = abs(Math.sin(turn.toDouble())) > .7071
            val o = outAt ?: if (wall) wallOut(kind) else kerbOut(kind, turned)
            for (t in 0..tries * 2) {
                val step = (t + 1) / 2 * (if (t % 2 == 1) prefer else -prefer) * 6f
                val s = PropSpot(kind, variant, x + step, o, turn, text, (rand(st.key, kind.name, x.toInt(), t) * 65536f).toInt())
                if (clear(s.footprint())) { out += s; return s }
            }
            return null
        }

        fun nearest(kind: PropKind, x: Float): Float = out.filter { it.kind == kind }.minOfOrNull { abs(it.x - x) } ?: Float.MAX_VALUE

        fun run(): List<PropSpot> {
            val k = st.key
            // what is kept clear: the doors and where the keepers stand, the street's own furniture
            for (f in st.fronts) {
                blocked += floatArrayOf(f.door - DOOR_HALF, f.door + DOOR_HALF, -1f, Crossroads.PAVE + Crossroads.KERB)
                val kx = (f.x0 + f.x1) / 2f + KEEPER_X
                blocked += floatArrayOf(kx - 6f, kx + 6f, 0f, 12f)
            }
            for (x in st.furniture) blocked += floatArrayOf(x - FURNITURE_HALF, x + FURNITURE_HALF, -1f, Crossroads.PAVE + Crossroads.KERB)
            // a cinema's whole front is kept clear (its doors, ticket booth, poster cases and the queue under the marquee)
            for (f in st.fronts) if (f.landmark == "cinema") blocked += floatArrayOf(f.x0 + 30f, f.x1 - 30f, -1f, Crossroads.PAVE + Crossroads.KERB)
            // and a store's (its porch and steps, its glass and doors, its car park's mouth and road sign are its own)
            for (f in st.fronts) if (f.landmark in StoreLots.KINDS || f.landmark?.startsWith("landmark_glb_") == true) blocked += floatArrayOf(f.x0 + 12f, f.x1 - 12f, -1f, Crossroads.PAVE + Crossroads.KERB)
            val hoodAt = { x: Float -> st.fronts.minByOrNull { f -> if (x in f.x0..f.x1) 0f else minOf(abs(f.x0 - x), abs(f.x1 - x)) }?.neighbourhood ?: "" }

            // 1. the corners: the name sign, the stop or the crossing sign at the crossing road's kerb, a hydrant just past
            for (c in st.corners) {
                val d = c.dir.toFloat()
                // a sign faces along the street, away from the crossing, to the traffic coming up to it (turned a little to
                // the road): seen from the pavement opposite, side on
                val face = c.dir.toFloat()
                if (c.stop) put(PropKind.STOP_SIGN, 0, c.x - d * 34f, turn = face * (Math.PI.toFloat() / 2f - .35f), prefer = c.dir, tries = 1)
                else if (c.ped) put(PropKind.PED_SIGN, 0, c.x - d * 34f, turn = face * (Math.PI.toFloat() / 2f - .35f), prefer = c.dir, tries = 1)
                if (c.sign) put(PropKind.STREET_SIGN, 0, c.x - d * 18f, text = listOfNotNull(signText(st.name), c.cross?.let { signText(it) }), prefer = c.dir, tries = 1)
                if (rand(k, "corner-hydrant", c.x.toInt()) < .4f * dens(hoodAt(c.x)))
                    put(PropKind.HYDRANT, if (rand(k, "hydrant-colour", c.x.toInt()) < .3f) 1 else 0, c.x + d * (16f + 14f * rand(k, "hydrant-at", c.x.toInt())), prefer = c.dir)
            }

            // 2. along the fronts
            val shopsOnStreet = st.fronts.count { it.kind == PlacementKind.SHOP }
            for (f in st.fronts.sortedBy { it.x0 }) {
                val id = f.id
                val p = dens(f.neighbourhood)
                val w = f.x1 - f.x0
                when (f.kind) {
                    PlacementKind.SHOP -> {
                        // planters at the shop's two ends, against its front
                        if (rand(k, id, "planters") < .45f * p) {
                            val v = pick(3, k, id, "planter-look")
                            put(PropKind.PLANTER, v, f.x0 + 14f, wall = true, tries = 1)
                            put(PropKind.PLANTER, v, f.x1 - 14f, wall = true, tries = 1)
                        }
                        // the blue mailbox: one within reach of every shop run
                        if (nearest(PropKind.MAILBOX, f.door) > 600f && rand(k, id, "mailbox") < .7f) put(PropKind.MAILBOX, 0, f.door - 26f, prefer = -1)
                        // parking meters along a main street's or the old town's shops
                        if ((st.main || f.neighbourhood == "old-town") && rand(k, id, "meters") < .7f) {
                            var x = f.x0 + 12f
                            while (x < f.x1 - 8f) { put(PropKind.PARKING_METER, 0, x, tries = 1); x += 92f }
                        }
                        if (rand(k, id, "bikes") < .32f * p) put(PropKind.BIKE_RACK, if (rand(k, id, "bike") < .5f) 1 else 0, f.x0 + w * (.3f + .4f * rand(k, id, "bikes-at")))
                        if (rand(k, id, "news") < .25f * p) put(PropKind.NEWS_BOX, pick(4, k, id, "news-look"), f.door + 24f)
                        if (rand(k, id, "litter") < .45f * p) put(PropKind.LITTER_BIN, if (st.main) 1 else 0, f.x1 - 6f, prefer = -1)
                        if (rand(k, id, "bench") < .12f * p) put(PropKind.BENCH, 0, f.x0 + w * .5f)
                    }
                    PlacementKind.LANDMARK -> {
                        val lk = f.landmark ?: ""
                        if (lk == "post") {
                            put(PropKind.MAILBOX, 0, f.door - 28f, prefer = -1)
                            put(PropKind.MAILBOX, 0, f.door + 28f)
                        }
                        if (lk in civicKinds) {
                            // a row of lit bollards along its front, leaving the door's way open
                            var x = f.x0 + 10f
                            while (x <= f.x1 - 10f) {
                                if (abs(x - f.door) > 22f) put(PropKind.BOLLARD, 1, x, tries = 0)
                                x += 16f
                            }
                            if (lk == "hotel" || lk == "school") {
                                put(PropKind.PLANTER, 2, f.x0 + 14f, wall = true, tries = 1)
                                put(PropKind.PLANTER, 2, f.x1 - 14f, wall = true, tries = 1)
                            }
                        }
                        if (lk == "fire") put(PropKind.CALL_BOX, 0, f.door + 40f)
                        if (lk == "hospital" || lk == "hotel") put(PropKind.BENCH, 0, f.door + 70f)
                        if (lk == "cinema") {
                            // bins at its two ends, by the corners of the front
                            put(PropKind.LITTER_BIN, 1, f.x0 + 20f, prefer = -1, tries = 2)
                            put(PropKind.LITTER_BIN, 1, f.x1 - 20f, tries = 2)
                        } else if (rand(k, id, "litter") < .6f) put(PropKind.LITTER_BIN, 1, f.door - 40f, prefer = -1)
                    }
                    PlacementKind.EXTRA -> {}
                    PlacementKind.HOME -> {
                        // the mailbox at the kerb beside the path to the door; the bins further from the path
                        val side = if (rand(k, id, "mailbox-side") < .5f) -1 else 1
                        val mb = put(PropKind.HOME_MAILBOX, homeMailbox(f.neighbourhood, rand(k, id, "mailbox-look")), f.door + side * 22f, prefer = side)
                        if (mb != null && rand(k, id, "bins") < .35f * p) {
                            val v = pick(4, k, id, "bin-look")
                            put(PropKind.WHEELIE_BIN, v, mb.x + side * 15f, prefer = side, tries = 2)
                            if (rand(k, id, "two-bins") < .5f) put(PropKind.WHEELIE_BIN, (v + 1) % 4, mb.x + side * 31f, prefer = side, tries = 2)
                        }
                        if (nearest(PropKind.HYDRANT, f.door) > 420f && rand(k, id, "hydrant") < .5f)
                            put(PropKind.HYDRANT, if (rand(k, id, "hydrant-colour") < .3f) 1 else 0, f.x1 + 12f)
                    }
                }
                // works: a few cones, rarely
                if (rand(k, id, "cones") < .04f) {
                    val x = f.x0 + w * rand(k, id, "cones-at")
                    val n = 2 + pick(2, k, id, "cones-n")
                    for (i in 0 until n) put(PropKind.CONE, 0, x + i * 13f, tries = 1)
                }
            }

            // 3. the stretches between buildings: hydrants now and then, a cabinet, a young tree in its guard by shops
            val spans = ArrayList<Pair<Float, Float>>()
            var at = st.x0
            for (f in st.fronts.sortedBy { it.x0 }) { if (f.x0 - at > 70f) spans += at to f.x0; at = maxOf(at, f.x1) }
            if (st.x1 - at > 70f) spans += at to st.x1
            for ((a, b) in spans) {
                val hood = hoodAt((a + b) / 2f)
                val p = dens(hood)
                var x = a + 120f
                while (x < b - 60f) {
                    if (nearest(PropKind.HYDRANT, x) > 1800f) put(PropKind.HYDRANT, if (rand(k, "gap-hydrant-colour", x.toInt()) < .3f) 1 else 0, x)
                    else if (rand(k, "cabinet", x.toInt()) < .18f * p) put(PropKind.UTILITY_BOX, pick(2, k, "cabinet-look", x.toInt()), x)
                    else if (shopsOnStreet > 0 && rand(k, "tree-guard", x.toInt()) < .2f * p) put(PropKind.TREE_GUARD, 0, x)
                    x += 300f
                }
            }
            // one fire alarm post on a street of shops
            if (shopsOnStreet >= 3 && out.none { it.kind == PropKind.CALL_BOX } && rand(k, "call-box") < .5f) {
                val f = st.fronts.filter { it.kind == PlacementKind.SHOP }.let { it[pick(it.size, k, "call-box-at")] }
                put(PropKind.CALL_BOX, 0, f.x1 + 10f)
            }
            // a street of three shops or more always has its blue box (the rolls above can all miss on a short run)
            if (shopsOnStreet >= 3 && out.none { it.kind == PropKind.MAILBOX }) {
                val shops = st.fronts.filter { it.kind == PlacementKind.SHOP }.sortedBy { it.x0 }
                for (f in listOf(shops[shops.size / 2]) + shops) if (put(PropKind.MAILBOX, 0, f.door - 26f, prefer = -1) != null) break
            }
            return out.sortedBy { it.x }
        }
    }

    /** A home's mailbox colour, by its neighbourhood's taste: 0 black, 1 white, 2 green, 3 red, 4 blue, 5 silver. */
    fun homeMailbox(hood: String, r: Float): Int {
        val choices = when (hood) {
            "heights" -> intArrayOf(0, 0, 1, 5, 4)
            "gardens" -> intArrayOf(2, 1, 3, 2, 0)
            "woodside" -> intArrayOf(0, 2, 5, 3)
            "waterfront" -> intArrayOf(4, 1, 1, 5, 0)
            else -> intArrayOf(0, 3, 1, 2, 4, 5)
        }
        return choices[(r * choices.size).toInt().coerceIn(0, choices.size - 1)]
    }

    // ------------------------------------------------------------------ the town's rows

    /** The props of one [row] of [town] (see [PropStreet]); its spots' x and out are in the row's frame. */
    fun forRow(town: Town, row: Row): List<PropSpot> = place(street(town, row))

    /** [row] of [town] as the prop rules see it. */
    fun street(town: Town, row: Row): PropStreet {
        val road = town.road(row.road)
        val corners = ArrayList<PropCorner>()
        val gaps = ArrayList<Pair<Float, Float>>()
        // which end the traffic in the lane by this pavement drives up to (right-hand traffic): the end ahead when the
        // buildings are on the right of +x
        val right = (-row.along.z * row.normal.x + row.along.x * row.normal.z) > 0f
        for (e in RowEnd.values()) {
            val info = row.ends(e)
            if (!info.mouth) continue
            val other = town.rowOrNull(info.turnIn) ?: town.rowOrNull(info.straight)
            val crossRoad = other?.let { town.road(it.road) }
            val half = (crossRoad?.width ?: Crossroads.MOUTH) / 2f
            val x = if (e == RowEnd.START) half else row.length - half
            val dir = if (e == RowEnd.START) 1 else -1
            val approach = (e == RowEnd.END) == right
            val stop = !road.main && road.id != town.civicRoad && approach && town.node(row.node(e)).kind.let { it == NodeKind.CROSS || it == NodeKind.TEE }
            val ped = road.main || road.id == town.civicRoad
            // one name sign a corner: the row whose id comes first of the two that meet there puts it up
            val sign = other == null || row.id < other.id
            corners += PropCorner(x, dir, crossRoad?.name, stop, ped, sign)
            gaps += if (e == RowEnd.START) (-1e9f to x - CORNER) else (x + CORNER to 1e9f)
        }
        val fronts = row.buildings.filter { it.kind != PlacementKind.EXTRA }.map { b ->
            PropFront(b.x0, b.x1, b.kind, b.neighbourhood, town.landmarks.firstOrNull { it.sprite == b.sprite }?.kind, b.sprite)
        }
        return PropStreet(
            key = "${town.seed}:${town.year}:${row.id}", name = road.name, x0 = 0f, x1 = row.length, fronts = fronts,
            furniture = row.furniture.map { it.x }, corners = corners, gaps = gaps, main = road.main, civic = road.id == town.civicRoad,
        )
    }

    /** Where [spot] of [row] stands in the town (world x, z), and its along and out directions there (unit x, z each). */
    fun world(row: Row, spot: PropSpot): FloatArray {
        val p = row.pointAt(spot.x, spot.out / Crossroads.LANE)
        val a = row.alongAt(spot.x)
        val n = row.normalAt(spot.x)
        return floatArrayOf(p.x, p.z, a.x, a.z, -n.x, -n.z)
    }
}
