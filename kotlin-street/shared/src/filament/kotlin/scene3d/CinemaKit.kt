package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The town's movie theater: a big landmark made by code like the street props ([PropKit]): low-poly boxes, prisms and
 * cut-out quads wearing pixel pictures painted texel by texel at the buildings' density ([TEXEL]) in the pack's palette,
 * nearest-sampled, lit and shadowed. Four looks by the year the town is in ([CinemaEra]): the 1940s-50s picture palace
 * (a tall blade sign with the name, a three-sided marquee of chasing bulbs, an octagonal ticket booth, brass doors,
 * poster cases), the 1960s-70s modernised front (a backlit letter-board marquee, channel letters, aluminium fins), the
 * 1980s-90s twin (boxy stucco, neon trim, a pylon, a row of poster cases) and the 2000s-2020s multiplex (a glass atrium,
 * a wide lit sign, a digital marquee, a poster wall, a drum tower). Behind every front stands the big plain auditorium
 * box: brick or render, fire exits, a loading door and a ladder at the back, the projection booth's bump, vents and
 * plant on the roof, so it is whole from behind and above.
 *
 * The model's own frame (as a prop's): x along the street (its middle at 0), y up, z out towards the road; the front wall
 * stands at z 0, the marquee and blade sign reach out over the pavement to +z, the auditorium runs back to -z. Big plain
 * walls wear small repeating tiles ([TileMesh]: the wall's pattern once, sampled with repeat), so the model's pictures
 * stay small for the phone's heap; what is unique (the fronts, signs, posters, marquee boards) is packed per part.
 */

/** The theater's look by the town's year. */
enum class CinemaEra {
    /** 1940s-50s picture palace. */
    PALACE,
    /** 1960s-70s modernised front. */
    MODERN,
    /** 1980s-90s twin/triple. */
    TWIN,
    /** 2000s-2020s multiplex. */
    MULTIPLEX;

    companion object {
        fun of(year: Int): CinemaEra = when { year < 1960 -> PALACE; year < 1980 -> MODERN; year < 2000 -> TWIN; else -> MULTIPLEX }
    }
}

/**
 * What the theater is and what plays this year (from the town's generator; [CinemaBill.sample] makes one up for the
 * preview): its [name] (made up, never a real chain's), the town's [year] (its [era] follows), the films [nowShowing]
 * (the marquee spells them) and [comingSoon] (their posters too).
 */
data class CinemaInfo(
    val name: String,
    val year: Int,
    val nowShowing: List<CinemaFilm>,
    val comingSoon: List<CinemaFilm> = emptyList(),
    val era: CinemaEra = CinemaEra.of(year),
    /** Screens (the twin's and the multiplex's sign: "CINEMA 3", "CINEMAS 8"); 0 picks by era. */
    val screens: Int = 0,
    /** The marquee's lines as the town wrote them ("NOW PLAYING", the titles...); empty: made from the bill. */
    val marquee: List<String> = emptyList(),
    /** The building's width along the street and its depth back (street units, the plot's: lobby and hall); 0: the era's own. */
    val width: Float = 0f,
    val depth: Float = 0f,
) {
    /** The house name the signs spell: "The Avalon" -> AVALON, "Avalon 8" -> AVALON, "Gamma Cinemas 8" -> GAMMA. */
    val signName: String get() {
        var n = name.uppercase().trim()
        if (n.startsWith("THE ")) n = n.removePrefix("THE ")
        n = n.replace(Regex("\\s+(TWIN|TRIPLE|CINEMAS?|PALACE|THEATRE|THEATER|\\d+)(\\s+\\d+)?$"), "")
        n = n.replace(Regex("\\s+(CINEMAS?|PALACE)$"), "")
        return n.ifBlank { name.uppercase() }
    }

    companion object {
        /** Lines for a poster whose tagline another film on the bill already prints. */
        private val SPARE = listOf("SEE IT ON THE BIG SCREEN", "THE ONE TO SEE", "BRING A FRIEND", "ONE NIGHT ONLY", "NOTHING PREPARED THEM",
            "BELIEVE IT", "IT IS NOT OVER YET", "THE ADVENTURE BEGINS", "A STORY YOU WILL NEVER FORGET", "ONE LAST TIME", "HOLD ON TIGHT", "EXPECT THE UNEXPECTED")

        /** [now] and [soon] with no tagline printed twice on the bill (a repeat takes a spare line, by its title). */
        fun distinctTaglines(now: List<CinemaFilm>, soon: List<CinemaFilm>): Pair<List<CinemaFilm>, List<CinemaFilm>> {
            val seen = HashSet<String>()
            fun one(f: CinemaFilm): CinemaFilm {
                val key = f.tagline.uppercase().trim()
                if (key.isEmpty() || seen.add(key)) return f
                val k0 = (f.title.hashCode() and 0x7fffffff) % SPARE.size
                val alt = (SPARE.indices).map { SPARE[(k0 + it) % SPARE.size] }.firstOrNull { it !in seen } ?: return f
                seen += alt
                return f.copy(tagline = alt)
            }
            // and no two posters of one motif with the same picture: each takes the next of its motif's compositions
            val count = HashMap<String, Int>()
            val year = (now + soon).firstOrNull()?.seed ?: 0
            fun look(f: CinemaFilm): CinemaFilm { val k = f.motif.ifEmpty { f.genre }; val i = count.getOrDefault(k, 0); count[k] = i + 1; return f.copy(look = i + (year and 0xff)) }
            val a = now.map(::one).map(::look); val b = soon.map(::one).map(::look)
            return a to b
        }

        /**
         * The town's cinema from street.json v2's `graph` (its `cinema` block and the cinema landmark's footprint), or
         * null when the town has none (see docs/CINEMA.md).
         */
        fun fromGraph(graph: org.json.JSONObject): CinemaInfo? {
            val c = graph.optJSONObject("cinema") ?: return null
            val lm = graph.optJSONArray("landmarks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == c.optString("landmark") } }
            val fp = lm?.optJSONObject("footprint")
            val decade = c.optInt("decade", c.optInt("year") / 10 * 10)
            fun films(k: String): List<CinemaFilm> {
                val a = c.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { i ->
                    val f = a.getJSONObject(i)
                    val p = f.optJSONObject("poster")
                    val pal = p?.optJSONArray("palette")?.let { pa -> (0 until pa.length()).mapNotNull { j -> pa.optString(j).removePrefix("#").toLongOrNull(16)?.let { (0xFF000000L or it).toInt() } } } ?: emptyList()
                    CinemaFilm(f.optString("title"), f.optString("tagline"), f.optString("rating"), f.optString("genre"), decade,
                        palette = 0, seed = p?.optInt("seed") ?: i, style = p?.optString("style") ?: "", motif = p?.optString("motif") ?: "", star = f.optString("star"), colours = pal)
                }
            }
            val era0 = when (c.optString("era")) { "palace" -> CinemaEra.PALACE; "modern" -> CinemaEra.MODERN; "twin" -> CinemaEra.TWIN; "multiplex" -> CinemaEra.MULTIPLEX; else -> CinemaEra.of(c.optInt("year")) }
            val marquee = c.optJSONArray("marquee")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val (now, soon) = distinctTaglines(films("nowShowing"), films("comingSoon"))
            return CinemaInfo(c.optString("name"), c.optInt("year"), now, soon, era0, c.optInt("screens"), marquee,
                fp?.optDouble("width")?.toFloat() ?: 0f, fp?.optDouble("depth")?.toFloat() ?: 0f)
        }
    }
}

/** One part of the theater for the engine: a mesh, whether it throws a shadow, whether its picture repeats, how it glows. */
class CinemaPart(val mesh: PropMesh, val casts: Boolean, val tiled: Boolean, val role: Role) {
    enum class Role {
        /** Walls and things that do not glow. */
        BODY,
        /** Windows and lobby glass: lit in the warm lamp colour from nightfall. */
        WINDOWS,
        /** Signs, the marquee's board, the lit poster cases: on from dusk in their own colours. */
        SIGN,
        /** The marquee's bulbs, one of three chasing phases each. */
        CHASE0, CHASE1, CHASE2,
        /** A digital board's lines, lit one page after another (slow: a line every [CinemaLayer.PAGE_SECONDS]). */
        PAGE0, PAGE1, PAGE2,
    }
}

/**
 * A real light the theater throws at night (the marquee's soffit on the pavement, a sign's wash, a car park's lamps): at
 * ([x], [y], [z]) in the model's frame, its colour, [lumens] at full night, reaching [reach]; [down] a spot aimed
 * straight down (a cone of [cone] radians) rather than a point.
 */
class CinemaLight(val x: Float, val y: Float, val z: Float, val r: Float, val g: Float, val b: Float, val lumens: Float, val reach: Float, val down: Boolean = false, val cone: Float = .9f) {
    fun moved(dx: Float) = CinemaLight(x + dx, y, z, r, g, b, lumens, reach, down, cone)
}

/**
 * The whole theater: its [parts], its footprint as built (min x, min z, max x, max z: hoods, steps and the marquee's reach
 * over the pavement included), its height (the blade's crown), the main block's [width] and [depth] (lobby and auditorium).
 */
class CinemaModel(val era: CinemaEra, val parts: List<CinemaPart>, val footprint: FloatArray, val height: Float, val width: Float, val depth: Float, val lights: List<CinemaLight> = emptyList(),
                  /** Cars standing in its car park, for the town to draw as real cars ([LotCars]; built with `realCars`), in the model's frame. */
                  val parked: List<LotCar> = emptyList()) {
    /** Texels in all its pictures (each mesh's atlas, glow masks counted). */
    val texels: Long get() = parts.sumOf { p -> p.mesh.texW.toLong() * p.mesh.texH * (if (p.mesh.mask != null) 2 else 1) }
    /** The bytes those pictures take as ARGB ints on the Java heap (on the GPU as RGBA8 with mips, a third more). */
    val bytes: Long get() = texels * 4
    val triangles: Int get() = parts.sumOf { it.mesh.idx.size / 3 }
}

/** A mesh whose faces all wear one small picture repeated (sampled with repeat): bricks, render, roofing, cladding. */
internal class TileMesh(val img: PixImg) {
    private val quads = ArrayList<Array<FloatArray>>()
    val isEmpty get() = quads.isEmpty()

    /** A quad: [a] top-left, [b] top-right, [c] bottom-right, [d] bottom-left as seen from outside. */
    fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) { quads += arrayOf(a, b, c, d) }

    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, sides: Set<MeshBuilder.Side>) {
        val v = MeshBuilder.Companion::v
        if (MeshBuilder.Side.FRONT in sides) quad(v(x0, y1, z1), v(x1, y1, z1), v(x1, y0, z1), v(x0, y0, z1))
        if (MeshBuilder.Side.BACK in sides) quad(v(x1, y1, z0), v(x0, y1, z0), v(x0, y0, z0), v(x1, y0, z0))
        if (MeshBuilder.Side.RIGHT in sides) quad(v(x1, y1, z1), v(x1, y1, z0), v(x1, y0, z0), v(x1, y0, z1))
        if (MeshBuilder.Side.LEFT in sides) quad(v(x0, y1, z0), v(x0, y1, z1), v(x0, y0, z1), v(x0, y0, z0))
        if (MeshBuilder.Side.TOP in sides) quad(v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1))
    }

    /** The mesh: walls take their picture's rows from the height (courses line up all round), tops from x and z. */
    fun build(): PropMesh {
        val n = quads.size
        val pos = FloatArray(n * 12); val uv = FloatArray(n * 8); val nr = FloatArray(n * 12); val tn = FloatArray(n * 12)
        val idx = IntArray(n * 6)
        val bmin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val bmax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val su = TEXEL * img.w; val sv = TEXEL * img.h
        for ((k, q) in quads.withIndex()) {
            val nrm = MeshBuilder.nrm(MeshBuilder.cross(MeshBuilder.sub(q[3], q[0]), MeshBuilder.sub(q[1], q[0])))
            val flat = abs(nrm[1]) > .7f
            val t = if (flat) floatArrayOf(1f, 0f, 0f) else MeshBuilder.nrm(floatArrayOf(q[1][0] - q[0][0], 0f, q[1][2] - q[0][2]))
            for (i in 0..3) {
                val p = q[i]
                for (c in 0..2) {
                    pos[k * 12 + i * 3 + c] = p[c]; nr[k * 12 + i * 3 + c] = nrm[c]; tn[k * 12 + i * 3 + c] = t[c]
                    bmin[c] = min(bmin[c], p[c]); bmax[c] = max(bmax[c], p[c])
                }
                if (flat) { uv[k * 8 + i * 2] = p[0] / su; uv[k * 8 + i * 2 + 1] = p[2] / sv }
                else { uv[k * 8 + i * 2] = (p[0] * t[0] + p[2] * t[2]) / su; uv[k * 8 + i * 2 + 1] = -p[1] / sv }
            }
            val b = k * 4
            idx[k * 6] = b; idx[k * 6 + 1] = b + 1; idx[k * 6 + 2] = b + 2; idx[k * 6 + 3] = b; idx[k * 6 + 4] = b + 2; idx[k * 6 + 5] = b + 3
        }
        return PropMesh(pos, uv, nr, tn, idx, img.w, img.h, img.px, null, floatArrayOf(bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]))
    }
}

/** Builds the movie theater for a town's [CinemaInfo]. */
object CinemaKit {
    /** How far the back's fittings (ladders, fire escapes, the loading door's hood) stand out behind the hall: kept on the plot. */
    private const val BACK_FITTINGS = 10f
    /** How far the multiplex's drum stands out past the front block's side (its radius and offset). */
    private const val DRUM_REACH = 32f
    internal fun c(rgb: Long) = Paint.c(rgb)
    internal val v = MeshBuilder.Companion::v
    internal fun tx(u: Float) = max(1, (u / TEXEL).roundToInt())

    // ------------------------------------------------------------------ palettes
    private val CREAM = Ramp(c(0x6A5A48), c(0xC8B488), c(0xE2D2A8), c(0xF0E4C4), c(0xFAF2DC))
    private val GOLD = Ramp(c(0x5A3A14), c(0xA87420), c(0xE0A830), c(0xF6D060), c(0xFFF0A0))
    private val TEAL = Ramp(c(0x0E2E2E), c(0x1E5A5A), c(0x2E8C8C), c(0x4AAAA8), c(0x7AD0C8))
    private val MAROON = Ramp(c(0x1A0810), c(0x3A0E1A), c(0x5A1A2A), c(0x7A2A3A), c(0x9A4A5A))
    private val NAVY = Ramp(c(0x0A0A1A), c(0x14142E), c(0x1E1E44), c(0x2E2E5E), c(0x4A4A80))
    private val STUCCO = Ramp(c(0x5A4A3A), c(0xC8B094), c(0xE0CCAE), c(0xECDCC2), c(0xF6ECD8))
    private val PANEL = Ramp(c(0x2A2E3A), c(0x8A90A0), c(0xA8AEBC), c(0xBCC2CE), c(0xD8DCE4))
    private val GLASS = Ramp(c(0x1A2A3A), c(0x2A4A64), c(0x3A6080), c(0x5A86A8), c(0x9ABCD8))
    private val MAGENTA = Ramp(c(0x3A0A24), c(0x8A1A5A), c(0xC82A7A), c(0xE85AA0), c(0xFF9AD0))
    private val CRIMSON = Ramp(c(0x3A0A0E), c(0x8A1A1E), c(0xC82A2A), c(0xE85048), c(0xFF8A7A))
    private val BRICK = Ramp(c(0x3A1E1A), c(0x7A3A2A), c(0x9A4A34), c(0xB4603E), c(0xC87A52))
    private val WARM_IN = c(0xF6C870)
    private val NEON_PINK = c(0xFF5AC8)
    private val NEON_CYAN = c(0x5AF0FF)

    // ------------------------------------------------------------------ the builder

    internal class B(val era: CinemaEra) {
        val det = MeshBuilder()
        val lit = MeshBuilder()
        val sign = MeshBuilder()
        val chase = Array(3) { MeshBuilder() }
        val page = Array(3) { MeshBuilder() }
        val lights = ArrayList<CinemaLight>()
        val tiles = LinkedHashMap<PixImg, TileMesh>()
        /** The car park's cars as places (drawn as real cars by the town) when [realCars], else as boxes in [det]. */
        var realCars = false
        val parked = ArrayList<LotCar>()
        /** Tiles that throw no shadow (the recess floors). */
        fun tile(img: PixImg) = tiles.getOrPut(img) { TileMesh(img) }
        var phase = 0

        /** A run of bulbs from [a] to [b] every [step] units facing [n] (each the next phase, so they chase along). */
        fun bulbs(a: FloatArray, b: FloatArray, step: Float, n: MeshBuilder.Side, size: Float = 1.9f) = bulbs(a, b, step, sideVec(n), size)

        fun bulbs(a: FloatArray, b: FloatArray, step: Float, n: FloatArray, size: Float = 1.9f) {
            val d = MeshBuilder.sub(b, a); val l = MeshBuilder.len(d)
            val k = max(1, (l / step).roundToInt())
            for (i in 0..k) {
                val p = MeshBuilder.add(a, MeshBuilder.mul(d, i / k.toFloat()))
                faceN(chase[phase % 3], BULB, p, n, size, size)
                phase++
            }
        }

        fun model(footprint: FloatArray, height: Float, width: Float, depth: Float): CinemaModel {
            val parts = ArrayList<CinemaPart>()
            fun add(m: MeshBuilder, casts: Boolean, role: CinemaPart.Role) {
                val mesh = m.build(); if (mesh.idx.isNotEmpty()) parts += CinemaPart(mesh, casts, false, role) }
            for ((img, t) in tiles) if (!t.isEmpty) parts += CinemaPart(t.build(), img !== FLOOR, true, CinemaPart.Role.BODY)
            add(det, true, CinemaPart.Role.BODY)
            add(lit, true, CinemaPart.Role.WINDOWS)
            add(sign, true, CinemaPart.Role.SIGN)
            add(chase[0], false, CinemaPart.Role.CHASE0); add(chase[1], false, CinemaPart.Role.CHASE1); add(chase[2], false, CinemaPart.Role.CHASE2)
            add(page[0], false, CinemaPart.Role.PAGE0); add(page[1], false, CinemaPart.Role.PAGE1); add(page[2], false, CinemaPart.Role.PAGE2)
            // the footprint as built (every hood, step and sign included), a unit's margin round it
            val fp = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var top = 0f
            for (p in parts) { val bd = p.mesh.bounds; fp[0] = min(fp[0], bd[0]); fp[1] = min(fp[1], bd[2]); fp[2] = max(fp[2], bd[3]); fp[3] = max(fp[3], bd[5]); top = max(top, bd[4]) }
            return CinemaModel(era, parts, floatArrayOf(fp[0] - 1f, fp[1] - 1f, fp[2] + 1f, fp[3] + 1f), top, width, depth, lights.toList())
        }
    }

    /**
     * A flat picture [w] x [h] units with its middle at ([cx], [cy], [cz]), facing [n] (FRONT +z, BACK -z, RIGHT +x,
     * LEFT -x, TOP +y, BOTTOM -y: a top's picture has its up away from the road, a bottom's towards the facade).
     */
    internal fun face(m: MeshBuilder, img: PixImg, cx: Float, cy: Float, cz: Float, w: Float, h: Float, n: MeshBuilder.Side, u0: Float = 0f, v0: Float = 0f, u1: Float = img.w.toFloat(), v1: Float = img.h.toFloat()) {
        val a = w / 2f; val b = h / 2f
        when (n) {
            MeshBuilder.Side.FRONT -> m.quad(v(cx - a, cy + b, cz), v(cx + a, cy + b, cz), v(cx + a, cy - b, cz), v(cx - a, cy - b, cz), img, u0, v0, u1, v1)
            MeshBuilder.Side.BACK -> m.quad(v(cx + a, cy + b, cz), v(cx - a, cy + b, cz), v(cx - a, cy - b, cz), v(cx + a, cy - b, cz), img, u0, v0, u1, v1)
            MeshBuilder.Side.RIGHT -> m.quad(v(cx, cy + b, cz + a), v(cx, cy + b, cz - a), v(cx, cy - b, cz - a), v(cx, cy - b, cz + a), img, u0, v0, u1, v1)
            MeshBuilder.Side.LEFT -> m.quad(v(cx, cy + b, cz - a), v(cx, cy + b, cz + a), v(cx, cy - b, cz + a), v(cx, cy - b, cz - a), img, u0, v0, u1, v1)
            MeshBuilder.Side.TOP -> m.quad(v(cx - a, cy, cz - b), v(cx + a, cy, cz - b), v(cx + a, cy, cz + b), v(cx - a, cy, cz + b), img, u0, v0, u1, v1)
            MeshBuilder.Side.BOTTOM -> m.quad(v(cx - a, cy, cz + b), v(cx + a, cy, cz + b), v(cx + a, cy, cz - b), v(cx - a, cy, cz - b), img, u0, v0, u1, v1)
        }
    }

    internal fun sideVec(n: MeshBuilder.Side): FloatArray = when (n) {
        MeshBuilder.Side.FRONT -> v(0f, 0f, 1f); MeshBuilder.Side.BACK -> v(0f, 0f, -1f); MeshBuilder.Side.RIGHT -> v(1f, 0f, 0f)
        MeshBuilder.Side.LEFT -> v(-1f, 0f, 0f); MeshBuilder.Side.TOP -> v(0f, 1f, 0f); MeshBuilder.Side.BOTTOM -> v(0f, -1f, 0f)
    }

    /** A flat picture [w] x [h] with its middle at [p] facing [n] (any direction; upright unless it faces up or down). */
    internal fun faceN(m: MeshBuilder, img: PixImg, p: FloatArray, n: FloatArray, w: Float, h: Float) {
        val up = if (n[1] > .7f) v(0f, 0f, -1f) else if (n[1] < -.7f) v(0f, 0f, 1f) else v(0f, 1f, 0f)
        val right = MeshBuilder.nrm(MeshBuilder.cross(MeshBuilder.mul(n, -1f), up))
        val r = MeshBuilder.mul(right, w / 2f); val u = MeshBuilder.mul(up, h / 2f)
        val a = MeshBuilder.add(MeshBuilder.sub(p, r), u); val b = MeshBuilder.add(MeshBuilder.add(p, r), u)
        val c = MeshBuilder.sub(MeshBuilder.add(p, r), u); val d = MeshBuilder.sub(MeshBuilder.sub(p, r), u)
        m.quad(a, b, c, d, img)
    }

    /** Marks a rectangle of [im] lit in the lamp's colour at night (not its own: dark glass glowing warm). */
    internal fun lamp(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, amount: Int = 255) {
        im.glow(x0, y0, x0, y0, 0)
        for (y in y0..y1) for (x in x0..x1) if (x in 0 until im.w && y in 0 until im.h) im.mask[y * im.w + x] = (255 shl 24) or (amount shl 16)
    }

    /** Marks every texel of [im] that is not [except] (or every texel) to glow in its own colours. */
    internal fun glowAll(im: PixImg, amount: Int = 255, except: Int? = null) {
        im.glow(0, 0, 0, 0, amount)
        for (i in im.px.indices) im.mask[i] = if (except != null && im.px[i] == except || im.px[i] ushr 24 == 0) 0 else (255 shl 24) or (amount shl 16) or 255
    }

    /** A top in [r]: the pack's lit top when small, a stretched profile when big (a few texels instead of a sheet). */
    internal fun topOf(w: Int, h: Int, r: Ramp): PixImg = if (w * h <= 600) Paint.top(w, h, r) else profile(h, Ramp(r.b, r.l, r.l, r.l, r.h), true)

    /** A ramp's profile across [n] texels: the outline, a lit lip, the base, a darker foot, the outline. */
    internal fun profile(n: Int, r: Ramp, vertical: Boolean): PixImg {
        val k = max(1, n)
        val im = if (vertical) PixImg(1, k) else PixImg(k, 1)
        for (i in 0 until k) {
            val c = when { k == 1 -> r.b; k == 2 -> if (i == 0) r.l else r.d; i == 0 || i == k - 1 -> r.o; i == 1 -> r.l; i == k - 2 -> r.d; else -> r.b }
            if (vertical) im[0, i] = c else im[i, 0] = c
        }
        return im
    }

    /**
     * A trim box (a coping, a pilaster, a plinth) whose faces wear [r]'s profile stretched along them (one texel wide:
     * long thin trims cost a few texels, not a strip as long as they are); [bottom] gives it a bottom face.
     */
    internal fun ledge(m: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Ramp, bottom: Boolean = false) {
        val cache = HashMap<Long, PixImg>()
        m.box(x0, y0, z0, x1, y1, z1) { s, w, h ->
            if (s == MeshBuilder.Side.BOTTOM && !bottom) null
            else if (s == MeshBuilder.Side.TOP || s == MeshBuilder.Side.BOTTOM) {
                val vertical = w >= h
                val n = if (vertical) h else w
                cache.getOrPut((n.toLong() shl 2) or (if (vertical) 1L else 0L) or 2L) { profile(n, Ramp(r.d, r.b, r.l, r.l, r.h), vertical) }
            } else {
                val vertical = w >= h
                val n = if (vertical) h else w
                cache.getOrPut((n.toLong() shl 2) or (if (vertical) 1L else 0L)) { profile(n, r, vertical) }
            }
        }
    }

    // ------------------------------------------------------------------ shared pictures

    /** A marquee bulb: a gold socket ring, a warm white glass (its corners cut away). */
    internal val BULB: PixImg = PixImg(4, 4).also { im ->
        val ring = c(0xB8862A); val core = c(0xFFFBE8); val glass = c(0xFFF0B0); val low = c(0xF6D070)
        for (y in 0..3) for (x in 0..3) im[x, y] = if ((x == 0 || x == 3) && (y == 0 || y == 3)) 0 else if (x == 0 || x == 3 || y == 0 || y == 3) ring else if (x == 1 && y == 1) core else if (x + y >= 4) low else glass
        im.glow(0, 0, 3, 3, 255)
        for (i in im.px.indices) if (im.px[i] == 0) im.mask[i] = 0
    }

    /** Brick in running bond, two tones of brick, the mortar a pale line. */
    internal fun brickTile(r: Ramp, mortar: Int, seed: Int): PixImg {
        val im = PixImg(64, 64)
        val rnd = Paint.Rnd(seed)
        for (row in 0 until 16) {
            val off = if (row % 2 == 0) 0 else 4
            for (k in -1 until 9) {
                val x0 = k * 8 + off
                val t = rnd.next()
                val base = if (t < .18f) r.d else if (t < .3f) r.l else r.b
                for (y in row * 4 until row * 4 + 3) for (x in x0 until x0 + 7) im[(x + 64) % 64, y] = base
                im[(x0 + 64) % 64, row * 4] = if (base == r.d) r.b else r.h
                if (rnd.next() < .25f) im[(x0 + 2 + rnd.int(4) + 64) % 64, row * 4 + 1 + rnd.int(2)] = r.d
            }
            for (x in 0 until 64) im[x, row * 4 + 3] = mortar
            for (k in 0 until 8) im[(k * 8 + off + 7 + 64) % 64, row * 4] = mortar
            for (k in 0 until 8) for (y in row * 4 until row * 4 + 3) im[(k * 8 + off + 7 + 64) % 64, y] = mortar
        }
        return im
    }

    /** Smooth render: its base with a few specks, a faint joint every 48 texels down. */
    internal fun renderTile(r: Ramp, seed: Int, joints: Boolean = true): PixImg {
        val im = PixImg(64, 48)
        im.fill(r.b)
        val rnd = Paint.Rnd(seed)
        repeat(110) { im[rnd.int(64), rnd.int(48)] = if (rnd.next() < .6f) r.l else r.d }
        if (joints) { im.hline(0, 63, 47, r.d); im.hline(0, 63, 0, r.l) }
        return im
    }

    /** Metal cladding: vertical panels, a dark joint and a lit edge each. */
    private fun claddingTile(r: Ramp): PixImg {
        val im = PixImg(32, 48)
        im.fill(r.b)
        for (y in 0 until 48) { im[0, y] = r.d; im[1, y] = r.h; im[16, y] = r.d; im[17, y] = r.l }
        im.hline(0, 31, 47, r.o); im.hline(0, 31, 0, r.l)
        val rnd = Paint.Rnd(5)
        repeat(20) { im[2 + rnd.int(28), rnd.int(46) + 1] = r.l }
        return im
    }

    /** Roofing: grey gravel over a membrane with its seams. */
    internal val ROOF: PixImg = PixImg(64, 64).also { im ->
        val r = Paint.Rnd(77)
        val b = c(0x7A7A88); val d = c(0x5E5E6E); val l = c(0x9A9AA8)
        for (y in 0 until 64) for (x in 0 until 64) { val t = r.next(); im[x, y] = if (t < .22f) d else if (t < .36f) l else b }
        for (x in 0 until 64) { im[x, 31] = c(0x4E4E5E); im[x, 32] = c(0xA8A8B4) }
    }

    /** Terrazzo for the entrance floors. */
    internal val FLOOR: PixImg = PixImg(32, 32).also { im ->
        val r = Paint.Rnd(13)
        for (y in 0 until 32) for (x in 0 until 32) im[x, y] = if (r.next() < .12f) c(0xA89880) else c(0xD8CCB4)
        for (x in 0 until 32) { im[x, 0] = c(0xB8A888); im[0, x] = c(0xB8A888) }
    }

    // ------------------------------------------------------------------ lettering

    /** The marquee's letters: [s] in the big font across [im] at row [y], centred in [x0]..[x1], cut at a word if too long. */
    private fun board(im: PixImg, s: String, x0: Int, x1: Int, y: Int, c: Int, scale: Int = 1) {
        var t = s.uppercase().filter { BigFont.has(it) }
        var sc = scale
        while (sc > 1 && BigFont.width(t, sc, sc) > x1 - x0) sc--
        // too long for the board even at its smallest: whole words dropped from the end (never a smaller font)
        while (t.isNotEmpty() && BigFont.width(t, sc, sc) > x1 - x0) t = if (t.contains(' ')) t.substringBeforeLast(' ') else t.dropLast(1)
        BigFont.draw(im, t, (x0 + x1 - BigFont.width(t, sc, sc)) / 2, y, c, sc, sc)
    }

    /** A marquee's letter board: white (backlit), its slots ruled, the lines of [lines] in black letters, framed in [frame]. */
    private fun letterBoard(w: Int, h: Int, lines: List<String>, frame: Ramp, ink: Int = c(0x14141E), field: Int = c(0xFAF6E8), colours: List<Int> = emptyList()): PixImg {
        val im = PixImg(w, h)
        im.fill(field)
        val rows = lines.size.coerceAtLeast(1)
        val pitch = ((h - 4) / rows).coerceAtLeast(9)
        // one letter size for the whole board: the biggest every line fits at (and the rows' pitch allows)
        var sc = 3
        while (sc > 1 && (BigFont.H * sc + 2 > pitch || lines.any { BigFont.width(it.uppercase().filter { ch -> BigFont.has(ch) }, sc, sc) > w - 9 })) sc--
        val top = (h - pitch * rows) / 2 + (pitch - 7 * sc) / 2
        for (k in 0 until rows) { val ry = (h - pitch * rows) / 2 + k * pitch; im.hline(2, w - 3, ry + pitch - 1, Pix.mix(field, c(0x8A8A9A), .25f)) }
        // (the field glows softly: a full glow would wash the letters' one-texel strokes out where the picture is minified)
        glowAll(im, 150)
        for ((k, s) in lines.withIndex()) board(im, s, 4, w - 5, top + k * pitch, colours.getOrNull(k) ?: ink, sc)
        for (i in im.px.indices) if (im.px[i] == ink || colours.contains(im.px[i])) im.mask[i] = 0
        for (x in 0 until w) { im[x, 0] = frame.o; im[x, 1] = frame.l; im[x, h - 1] = frame.o; im[x, h - 2] = frame.d }
        for (y in 0 until h) { im[0, y] = frame.o; im[1, y] = frame.b; im[w - 1, y] = frame.o; im[w - 2, y] = frame.d }
        // (the frame does not glow: only the board's field and its letters)
        for (x in 0 until w) for (y in listOf(0, 1, h - 2, h - 1)) im.mask[y * w + x] = 0
        for (y in 0 until h) for (x in listOf(0, 1, w - 2, w - 1)) im.mask[y * w + x] = 0
        return im
    }

    /** Lines for the marquee: the films now showing (and coming), as many as [rows]. */
    private fun bill(info: CinemaInfo, rows: Int, times: Boolean = false): List<String> {
        // the town's own lines (a heading, then the titles): the titles, as many as fit, the heading if there is room
        if (info.marquee.isNotEmpty()) {
            val titles = info.marquee.filter { it.isNotBlank() && !it.uppercase().startsWith("NOW ") && !it.uppercase().startsWith("COMING") }.map { it.uppercase() }
            val out = titles.take(rows).mapIndexed { i, t -> if (times) "$t ${listOf("7:00 9:15", "6:45 9:00", "7:30 9:40", "5:15 8:20")[i % 4]}" else t }.toMutableList()
            while (out.size < rows) out += ""
            return out
        }
        val now = info.nowShowing.map { it.title.uppercase() }
        val out = ArrayList<String>()
        for ((i, t) in now.withIndex()) {
            if (out.size >= rows) break
            out += if (times) "$t ${listOf("7:00 9:15", "6:45 9:00", "7:30 9:40", "5:15 8:20")[i % 4]}" else t
        }
        info.comingSoon.firstOrNull()?.let { if (out.size < rows) out += "SOON ${it.title.uppercase()}" }
        while (out.size < rows) out += ""
        return out
    }

    /** A sign's name letters stacked down a strip [w] x [h]: as big as fits, outlined, in [fill]. */
    private fun stacked(im: PixImg, name: String, x0: Int, y0: Int, w: Int, h: Int, fill: Int, out: Int, hi: Int) {
        val s = name.uppercase().filter { BigFont.has(it) && it != ' ' }
        if (s.isEmpty()) return
        var sc = 4
        while (sc > 1 && (BigFont.W * sc + 2 > w || s.length * (BigFont.H * sc + sc + 1) > h)) sc--
        val pitch = BigFont.H * sc + sc + 1
        var y = y0 + (h - s.length * pitch) / 2 + 1
        for (ch in s) {
            BigFont.outlined(im, ch.toString(), x0 + (w - BigFont.W * sc) / 2, y, sc, 0, out, null) { if (it < sc) hi else fill }
            y += pitch
        }
    }

    // ------------------------------------------------------------------ posters

    /** A lit poster case on a wall: its frame, a header, the poster behind glass; facing [n] at ([cx], [cy], [cz]). */
    private fun posterCase(b: B, film: CinemaFilm?, cx: Float, cy: Float, cz: Float, n: MeshBuilder.Side, frame: Ramp, header: String, pw: Float = 24f, ph: Float = 36f) {
        val fw = pw + 6f; val fh = ph + 10f
        val out = when (n) { MeshBuilder.Side.FRONT -> v(0f, 0f, 1f); MeshBuilder.Side.BACK -> v(0f, 0f, -1f); MeshBuilder.Side.RIGHT -> v(1f, 0f, 0f); else -> v(-1f, 0f, 0f) }
        val across = abs(out[2]) > .5f
        val d = 2.2f
        // the frame: a shallow box standing out from the wall
        val hx = if (across) fw / 2f else d / 2f; val hz = if (across) d / 2f else fw / 2f
        val ox = cx + out[0] * d / 2f; val oz = cz + out[2] * d / 2f
        b.det.box(ox - hx, cy - fh / 2f, oz - hz, ox + hx, cy + fh / 2f, oz + hz) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, frame, 3 + s.ordinal) }
        // the header strip (lit) and the poster
        val head = PixImg(tx(fw) - 2, 7).also { im ->
            im.fill(c(0x14141E)); val t = header.uppercase(); im.text(t, (im.w - PixImg.textWidth(t)) / 2, 1, c(0xF6E8C0)); glowAll(im, 255, c(0x14141E))
        }
        val fx = cx + out[0] * (d + .08f); val fz = cz + out[2] * (d + .08f)
        face(b.sign, head, fx, cy + fh / 2f - 3.6f, fz, fw - 2f, 5.6f, n)
        val poster = film?.let { CinemaPosters.paint(it) } ?: PixImg(CinemaPosters.W, CinemaPosters.H).also { im -> im.fill(c(0x2A2A3A)); im.text("SOON", 16, 34, c(0xC8C8D8)) }
        // (a poster glows softly: a full glow blows its pale tones out to white and loses its lettering at night)
        glowAll(poster, 120)
        face(b.sign, poster, fx, cy - 2f, fz, pw, ph, n)
    }

    /** A block's corner pilasters and the coping round its roof ([out] proud of the front, [t] tall above [h]). */
    private fun coping(b: B, x0: Float, x1: Float, zb: Float, zf: Float, h: Float, r: Ramp, out: Float, t: Float) {
        for (x in listOf(x0, x1)) for (z in listOf(zb, zf)) ledge(b.det, x - 1.2f, 0f, z - 1.2f, x + 1.2f, h, z + 1.2f, r)
        ledge(b.det, x0 - 2f, h - .5f, zf - 2f, x1 + 2f, h + t, zf + out, r, bottom = true)
        ledge(b.det, x0 - 2f, h - .5f, zb - 1f, x1 + 2f, h + t, zb + 2f, r, bottom = true)
        ledge(b.det, x0 - 2f, h - .5f, zb + 2f, x0 + 2f, h + t, zf - 2f, r, bottom = true)
        ledge(b.det, x1 - 2f, h - .5f, zb + 2f, x1 + 2f, h + t, zf - 2f, r, bottom = true)
    }

    /** Poster case centres from [from] stepping [pitch] towards [to] (as many as fit, keeping clear of [to]). */
    private fun caseRow(from: Float, to: Float, pitch: Float): List<Float> {
        val out = ArrayList<Float>()
        var x = from
        while (x >= to && out.size < 8) { out += x; x -= pitch }
        return out
    }

    // ------------------------------------------------------------------ the auditorium (every era)

    /**
     * The auditorium: a plain box [x0]..[x1] from [zf] back to [zb], [h] tall in [wall] tiles, its roof in roofing behind a
     * coping, fire exits down both sides, a loading door, a ladder and pipes at the back, the projection booth's bump on
     * the roof at the front and plant and vents behind it.
     */
    private fun auditorium(b: B, x0: Float, x1: Float, zf: Float, zb: Float, h: Float, wall: PixImg, trim: Ramp, seed: Int, fly: Float = 0f, back: Boolean = true) {
        val t = b.tile(wall)
        // a palace's stage house: the fly tower over the stage at the back, rising over the hall's roof
        if (fly > 0f) {
            val fz = zb + min(110f, (zf - zb) * .25f)
            t.box(x0, h - 1f, zb, x1, h + fly, fz, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.BACK, MeshBuilder.Side.FRONT))
            b.tile(ROOF).box(x0 + 1.5f, 0f, zb + 1.5f, x1 - 1.5f, h + fly - .6f, fz - 1.5f, setOf(MeshBuilder.Side.TOP))
            ledge(b.det, x0 - .4f, h + fly - 3.2f, zb - .4f, x1 + .4f, h + fly + .8f, zb + 1.6f, trim)
            ledge(b.det, x0 - .4f, h + fly - 3.2f, fz - 1.6f, x1 + .4f, h + fly + .8f, fz + .4f, trim)
            ledge(b.det, x0 - .4f, h + fly - 3.2f, zb + 1.6f, x0 + 1.6f, h + fly + .8f, fz - 1.6f, trim)
            ledge(b.det, x1 - 1.6f, h + fly - 3.2f, zb + 1.6f, x1 + .4f, h + fly + .8f, fz - 1.6f, trim)
            // its smoke vents (the stage's lantern)
            for (k in listOf(-1, 1)) b.det.box((x0 + x1) / 2f + k * 40f - 14f, h + fly - .6f, zb + 20f, (x0 + x1) / 2f + k * 40f + 14f, h + fly + 12f, zb + 46f) { s, w, hh -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) topOf(w, hh, Paint.STEEL) else Paint.panel(w, hh, Paint.STEEL, 3 + s.ordinal).also { im -> for (y in 2 until hh - 2 step 2) im.hline(2, w - 3, y, Paint.STEEL.d) } }
        }
        t.box(x0, 0f, zb, x1, h, zf, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.BACK, MeshBuilder.Side.FRONT))
        b.tile(ROOF).box(x0 + 1.5f, 0f, zb + 1.5f, x1 - 1.5f, h - .6f, zf - 1.5f, setOf(MeshBuilder.Side.TOP))
        // the coping round the roof's edge (a parapet a few units high)
        val cp = 1.6f; val ch = 3.2f
        val panel = { s: MeshBuilder.Side, w: Int, hh: Int -> if (s == MeshBuilder.Side.BOTTOM) null else if (s == MeshBuilder.Side.TOP) topOf(w, hh, trim) else Paint.panel(w, hh, trim, seed + s.ordinal, wear = .3f) }
        ledge(b.det, x0 - .4f, h - ch, zb - .4f, x1 + .4f, h + .8f, zb + cp, trim)
        ledge(b.det, x0 - .4f, h - ch, zf - cp, x1 + .4f, h + .8f, zf + .4f, trim)
        ledge(b.det, x0 - .4f, h - ch, zb + cp, x0 + cp, h + .8f, zf - cp, trim)
        ledge(b.det, x1 - cp, h - ch, zb + cp, x1 + .4f, h + .8f, zf - cp, trim)
        // a plinth along the foot
        val dark = Ramp(trim.o, trim.o, trim.d, trim.b, trim.l)
        ledge(b.det, x0 - .5f, 0f, zb - .5f, x1 + .5f, 3f, zb + 1f, dark)
        ledge(b.det, x0 - .5f, 0f, zb + 1f, x0 + 1f, 3f, zf, dark)
        ledge(b.det, x1 - 1f, 0f, zb + 1f, x1 + .5f, 3f, zf, dark)
        // fire exits down both sides, each with its little hood, its steps and a lit EXIT over it
        val len = zf - zb
        val exits = max(2, (len / 170f).toInt())
        // piers up the long sides (the hall's frame showing through), clear of the exits
        val exitZ = (0 until exits).map { k -> zb + len * (k + .6f) / (exits + .2f) }
        var pz = zb + 40f
        while (pz < zf - 30f) {
            if (exitZ.none { abs(it - pz) < 24f }) {
                t.box(x0 - 3f, 3f, pz - 6f, x0, h - 3.2f, pz + 6f, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK))
                t.box(x1, 3f, pz - 6f, x1 + 3f, h - 3.2f, pz + 6f, setOf(MeshBuilder.Side.RIGHT, MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK))
            }
            pz += 96f
        }
        for (side in listOf(-1, 1)) {
            val x = if (side < 0) x0 else x1
            val n = if (side < 0) MeshBuilder.Side.LEFT else MeshBuilder.Side.RIGHT
            for (k in 0 until exits) {
                val z = zb + len * (k + .6f) / (exits + .2f)
                exitDoor(b, x + side * .25f, z, n, trim)
            }
            // downpipes
            for (z in listOf(zb + 8f, zf - 10f)) b.det.beam(v(x + side * 1.6f, 1f, z), v(x + side * 1.6f, h - 2f, z), 1.6f, Paint.swatch(dark.b))
        }
        if (!back) return roofKit(b, x0, x1, zf, zb, h, trim, seed)
        // the back: a roller door with its bollards, an exit, a ladder to the roof, a vent grille
        val back = zb - .25f
        val rw = 64f; val rh = 70f
        val roller = PixImg(tx(rw), tx(rh)).also { im ->
            im.fill(Paint.STEEL.b)
            for (y in 0 until im.h) if (y % 3 == 0) im.hline(0, im.w - 1, y, Paint.STEEL.d) else if (y % 3 == 1) im.hline(0, im.w - 1, y, Paint.STEEL.l)
            im.border(Paint.OUT); im.rect(0, 0, im.w - 1, 2, Paint.IRON.b)
            im.rect(im.w / 2 - 3, im.h - 4, im.w / 2 + 2, im.h - 3, Paint.YELLOW.b)
        }
        face(b.det, roller, x0 + (x1 - x0) * .3f, rh / 2f, back, rw, rh, MeshBuilder.Side.BACK)
        for (dx in listOf(-rw / 2f - 5f, rw / 2f + 5f)) b.det.upright(x0 + (x1 - x0) * .3f + dx, zb - 5f, 0f, 22f, 2.4f, 2.4f, 8, Paint.strip(18, 27, Paint.YELLOW, 4).also { im -> for (y in 0 until im.h step 6) im.rect(0, y, im.w - 1, y + 2, c(0x1A1A22)) }, cap = Paint.cap(2.4f, Paint.YELLOW))
        // its hood and the dock's light over it
        b.det.box(x0 + (x1 - x0) * .3f - rw / 2f - 4f, rh + 2f, zb - 9f, x0 + (x1 - x0) * .3f + rw / 2f + 4f, rh + 5f, zb) { s, w, hh -> if (s == MeshBuilder.Side.TOP) topOf(w, hh, trim) else Paint.panel(w, hh, trim, 6 + s.ordinal) }
        b.lights += CinemaLight(x0 + (x1 - x0) * .3f, rh + 1f, zb - 14f, 1f, .78f, .55f, 22000f, 120f, down = true)
        exitDoor(b, x0 + (x1 - x0) * .62f, back, MeshBuilder.Side.BACK, trim)
        ladder(b, x0 + (x1 - x0) * .82f, back - .4f, h + 4f)
        val grille = PixImg(tx(26f), tx(16f)).also { im -> im.fill(Paint.STEEL.d); for (y in 1 until im.h - 1 step 2) im.hline(1, im.w - 2, y, Paint.STEEL.o); im.border(Paint.OUT) }
        face(b.det, grille, x0 + (x1 - x0) * .62f, h - 30f, back, 26f, 16f, MeshBuilder.Side.BACK)
        roofKit(b, x0, x1, zf, zb, h, trim, seed)
    }

    /** The roof's fittings: the projection booth's bump at the front, plant and vents behind, a walkway between them. */
    private fun roofKit(b: B, x0: Float, x1: Float, zf: Float, zb: Float, h: Float, trim: Ramp, seed: Int) {
        // the roof: the projection booth's bump at the front, plant and vents behind
        val boothW = min(90f, (x1 - x0) * .4f)
        val bz0 = zf - 46f; val bz1 = zf - 6f
        val bmx = (x0 + x1) / 2f
        b.det.box(bmx - boothW / 2f, h - 1f, bz0, bmx + boothW / 2f, h + 16f, bz1) { s, w, hh ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> topOf(w, hh, trim)
                else -> Paint.panel(w, hh, trim, seed + 30 + s.ordinal, wear = .4f).also { im -> if (s == MeshBuilder.Side.BACK || s == MeshBuilder.Side.LEFT) { im.rect(im.w / 2 - 4, im.h - 15, im.w / 2 + 3, im.h - 2, Paint.IRON.b); im.rect(im.w / 2 - 3, im.h - 14, im.w / 2 + 2, im.h - 2, Paint.STEEL.d) } }
            }
        }
        b.det.upright(bmx + boothW / 2f - 8f, (bz0 + bz1) / 2f, h + 16f, h + 24f, 2.2f, 2.2f, 8, Paint.strip(17, 10, Paint.STEEL, 5), cap = Paint.cap(2.2f, Paint.STEEL))
        b.det.upright(bmx + boothW / 2f - 8f, (bz0 + bz1) / 2f, h + 24f, h + 26f, 3.4f, 3.4f, 8, Paint.strip(26, 3, Paint.IRON, 6), cap = Paint.cap(3.4f, Paint.IRON))
        // a walkway of grating from the booth back down the roof to the plant
        val walk = PixImg(8, 8).also { im -> im.fill(Paint.STEEL.b); for (k in 0 until 8 step 2) { im.hline(0, 7, k, Paint.STEEL.d) }; im.vline(0, 0, 7, Paint.STEEL.l) }
        b.tile(walk).box(bmx - 5f, h - .6f, zb + 24f, bmx + 5f, h + .4f, bz0, setOf(MeshBuilder.Side.TOP, MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT))
        // plant and vents by the roof's size: a unit to about every 160 x 200, staggered, clear of the booth
        val cols = max(1, ((x1 - x0) / 160f).toInt()); val rows = max(2, ((zf - zb - 60f) / 200f).toInt())
        for (i in 0 until cols) for (j in 0 until rows) {
            val ux = x0 + (x1 - x0) * (i + .5f) / cols + (if (j % 2 == 0) -18f else 18f) + (if (abs(x0 + (x1 - x0) * (i + .5f) / cols - bmx) < 30f) 34f else 0f)
            val uz = zb + 30f + (zf - zb - 90f) * (j + .5f) / rows
            hvac(b, ux, h - .6f, uz, seed + i * 7 + j)
        }
        for (i in 0..cols * 2) for (j in 0 until rows + 1) {
            val ux = x0 + 16f + (x1 - x0 - 32f) * (i + .5f) / (cols * 2 + 1)
            val uz = zb + 20f + (zf - zb - 70f) * j / max(1, rows) + ((i * 37) % 3) * 9f
            if ((i + j) % 2 == 0) mushroom(b, ux, h - .6f, uz)
        }
    }

    private fun exitDoor(b: B, x: Float, z: Float, n: MeshBuilder.Side, trim: Ramp) {
        val w = 22f; val h = 46f
        val door = PixImg(tx(w), tx(h)).also { im ->
            im.fill(Paint.GREY.b); im.border(Paint.OUT)
            im.vline(im.w / 2, 1, im.h - 2, Paint.OUT)
            im.hline(2, im.w - 3, im.h / 2, Paint.STEEL.h); im.hline(2, im.w - 3, im.h / 2 + 1, Paint.STEEL.d)
            for (y in 2 until im.h - 2) { im[1, y] = Paint.GREY.l }
        }
        val cx = if (n == MeshBuilder.Side.LEFT || n == MeshBuilder.Side.RIGHT) x else x
        face(b.det, door, cx, h / 2f, z, w, h, n)
        val exit = PixImg(17, 7).also { im -> im.fill(c(0x1E8A3A)); im.border(c(0x0E4A1E)); im.text("EXIT", 1, 1, c(0xE8FFE8)); glowAll(im) }
        val o = when (n) { MeshBuilder.Side.LEFT -> v(-1f, 0f, 0f); MeshBuilder.Side.RIGHT -> v(1f, 0f, 0f); MeshBuilder.Side.BACK -> v(0f, 0f, -1f); else -> v(0f, 0f, 1f) }
        face(b.sign, exit, x + o[0] * .3f, h + 8f, z + o[2] * .3f, 14f, 5.8f, n)
        // the hood over it, the step under it
        val d = 6f
        fun boxOut(y0: Float, y1: Float, out: Float, wide: Float, r: Ramp) {
            val ax = if (abs(o[0]) > .5f) min(x, x + o[0] * out) else x - wide / 2f
            val bx = if (abs(o[0]) > .5f) max(x, x + o[0] * out) else x + wide / 2f
            val az = if (abs(o[2]) > .5f) min(z, z + o[2] * out) else z - wide / 2f
            val bz = if (abs(o[2]) > .5f) max(z, z + o[2] * out) else z + wide / 2f
            b.det.box(ax, y0, az, bx, y1, bz) { s, ww, hh -> if (s == MeshBuilder.Side.BOTTOM && y0 < .1f) null else if (s == MeshBuilder.Side.TOP) topOf(ww, hh, r) else Paint.panel(ww, hh, r, 9 + s.ordinal) }
        }
        boxOut(h + 1f, h + 3f, d + 4f, w + 10f, trim)
        boxOut(0f, 3f, d + 10f, w + 10f, Paint.CONCRETE)
    }

    private fun ladder(b: B, x: Float, z: Float, top: Float) {
        val rail = Paint.swatch(Paint.IRON.l)
        for (dx in listOf(-4.5f, 4.5f)) b.det.beam(v(x + dx, 50f, z - 1.6f), v(x + dx, top, z - 1.6f), 1f, rail)
        var y = 53f
        while (y < top) { b.det.beam(v(x - 4.5f, y, z - 1.6f), v(x + 4.5f, y, z - 1.6f), .7f, rail); y += 6f }
        // its cage's hoops
        y = 72f
        while (y < top - 2f) { b.det.beam(v(x - 6f, y, z - 1.6f), v(x - 6f, y, z - 9f), .6f, rail); b.det.beam(v(x + 6f, y, z - 1.6f), v(x + 6f, y, z - 9f), .6f, rail); b.det.beam(v(x - 6f, y, z - 9f), v(x + 6f, y, z - 9f), .6f, rail); y += 14f }
        for (dx in listOf(-4.5f, 4.5f)) b.det.beam(v(x + dx, top, z - 1.6f), v(x + dx, top + 6f, z + 4f), 1f, rail)
    }

    /** A rooftop air handler: a steel box with grilles, two round fans on top. */
    private fun hvac(b: B, x: Float, y: Float, z: Float, seed: Int) {
        val w = 36f; val d = 22f; val h = 14f
        b.det.box(x - w / 2f, y, z - d / 2f, x + w / 2f, y + h, z + d / 2f) { s, ww, hh ->
            when (s) {
                MeshBuilder.Side.BOTTOM -> null
                MeshBuilder.Side.TOP -> Paint.top(ww, hh, Paint.STEEL).also { im ->
                    for (k in 0..1) { val cx = ww * (k + .5f) / 2f; val cy = hh / 2f; Pix.disc(im, cx, cy, hh * .38f, Paint.IRON.b); Pix.ring(im, cx, cy, hh * .38f, 1f, Paint.OUT); Pix.disc(im, cx, cy, 1.5f, Paint.STEEL.l); im.hline((cx - hh * .3f).toInt(), (cx + hh * .3f).toInt(), cy.toInt(), Paint.STEEL.d) }
                }
                else -> Paint.panel(ww, hh, Paint.STEEL, seed + s.ordinal, wear = .5f).also { im -> for (yy in 3 until hh - 3 step 2) im.hline(3, ww - 4, yy, Paint.STEEL.d) }
            }
        }
        // its duct down into the roof
        b.det.box(x + w / 2f - 8f, y, z + d / 2f, x + w / 2f - 2f, y + 6f, z + d / 2f + 8f) { s, ww, hh -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(ww, hh, Paint.STEEL, 3) }
    }

    private fun mushroom(b: B, x: Float, y: Float, z: Float) {
        b.det.upright(x, z, y, y + 6f, 1.4f, 1.4f, 8, Paint.strip(11, 7, Paint.STEEL, 2), cap = null)
        b.det.upright(x, z, y + 6f, y + 8f, 3.2f, 1f, 8, Paint.strip(25, 2, Paint.IRON, 3), cap = Paint.cap(1f, Paint.IRON))
    }

    // ------------------------------------------------------------------ front walls

    /**
     * A front wall from [x0] to [x1], [y0] to [y1] at [z], wearing [img] all over (its texels laid evenly across), with
     * an entrance [hx0]..[hx1] up to [hy] set back [depth] (its back wall wears the same picture's part; its sides
     * [reveal], its ceiling [soffit]).
     */
    private fun frontWall(m: MeshBuilder, img: PixImg, x0: Float, x1: Float, y0: Float, y1: Float, z: Float, hx0: Float, hx1: Float, hy: Float, depth: Float, reveal: Ramp, soffit: PixImg, b: B) {
        fun u(x: Float) = (x - x0) / (x1 - x0) * img.w
        fun vv(y: Float) = (y1 - y) / (y1 - y0) * img.h
        // a picture wider than an atlas's 512 texels goes in strips (else its atlas doubles to 1024 and stands half empty)
        val strips = (img.w + STRIP - 1) / STRIP
        val cut = (0 until strips).map { k -> crop(img, k * img.w / strips, (k + 1) * img.w / strips) }
        fun piece(ax0: Float, ax1: Float, ay0: Float, ay1: Float, zz: Float) {
            if (ax1 <= ax0 + 1e-3f || ay1 <= ay0 + 1e-3f) return
            for (k in 0 until strips) {
                val s0 = k * img.w / strips; val s1 = (k + 1) * img.w / strips
                val a = max(ax0, x0 + (x1 - x0) * s0 / img.w); val e = min(ax1, x0 + (x1 - x0) * s1 / img.w)
                if (e <= a + 1e-3f) continue
                m.quad(v(a, ay1, zz), v(e, ay1, zz), v(e, ay0, zz), v(a, ay0, zz), cut[k], u(a) - s0, vv(ay1), u(e) - s0, vv(ay0))
            }
        }
        if (hx1 <= hx0) { piece(x0, x1, y0, y1, z); return }
        piece(x0, hx0, y0, y1, z); piece(hx1, x1, y0, y1, z); piece(hx0, hx1, hy, y1, z)
        piece(hx0, hx1, y0, hy, z - depth)
        val rv = Paint.panel(tx(depth), tx(hy - y0), reveal, 4)
        b.det.quad(v(hx0, hy, z), v(hx0, hy, z - depth), v(hx0, y0, z - depth), v(hx0, y0, z), rv)
        b.det.quad(v(hx1, hy, z - depth), v(hx1, hy, z), v(hx1, y0, z), v(hx1, y0, z - depth), rv)
        b.lit.quad(v(hx0, hy, z), v(hx1, hy, z), v(hx1, hy, z - depth), v(hx0, hy, z - depth), soffit)
        b.tile(FLOOR).box(hx0, -1f, z - depth, hx1, .12f, z, setOf(MeshBuilder.Side.TOP))
    }

    /** The widest strip of a picture laid whole ([frontWall]). */
    private const val STRIP = 480

    /** Columns [a] until [e] of [im] (its glow mask too). */
    private fun crop(im: PixImg, a: Int, e: Int): PixImg {
        if (a == 0 && e == im.w) return im
        val out = PixImg(e - a, im.h)
        var glows = false
        for (y in 0 until im.h) for (x in a until e) { out.px[y * out.w + x - a] = im.px[y * im.w + x]; val mk = im.mask[y * im.w + x]; out.mask[y * out.w + x - a] = mk; if (mk != 0) glows = true }
        if (glows) { val keep = out.mask[0]; out.glow(0, 0, 0, 0, 0); out.mask[0] = keep }
        return out
    }

    /** A soffit of downlights in a grid on [r]'s dark tone ([w] x [h] texels). */
    private fun downlights(w: Int, h: Int, r: Ramp, bulbs: Boolean = false): PixImg = PixImg(w, h).also { im ->
        im.fill(r.d)
        for (y in 2 until h - 1 step 4) for (x in 2 until w - 1 step 4) { im[x, y] = if (bulbs) c(0xFFF0B0) else c(0xF6F0E0); if (bulbs) im[x + 1, y] = c(0xF6D070) }
        for (y in 2 until h - 1 step 4) for (x in 2 until w - 1 step 4) { im.glow(x, y, x + (if (bulbs) 1 else 0), y, 255) }
        im.border(r.o)
    }

    /** The lobby seen through glass: warm walls, a red carpet, a candy counter, ceiling lights; lit in its own colours. */
    private fun lobbyView(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, era: CinemaEra, seed: Int) {
        val wall = when (era) { CinemaEra.PALACE -> c(0xC88A4A); CinemaEra.MODERN -> c(0xD8B888); CinemaEra.TWIN -> c(0xB85AA0); CinemaEra.MULTIPLEX -> c(0xA87448) }
        val carpet = when (era) { CinemaEra.TWIN -> c(0x3A2A6A); CinemaEra.MULTIPLEX -> c(0x6A1E2A); else -> c(0xA8282A) }
        val h = y1 - y0
        for (y in y0..y1) for (x in x0..x1) im[x, y] = if (y > y0 + h * .72f) (if ((x + y) % 7 == 0) Pix.mix(carpet, c(0xF6C83A), .5f) else carpet) else if (y < y0 + 2) Pix.mix(wall, c(0x000000), .4f) else wall
        // ceiling lights
        for (x in x0 + 3..x1 - 2 step 7) { im[x, y0 + 2] = c(0xFFF6D0); im[x + 1, y0 + 2] = c(0xFFF6D0) }
        // the counter with its glass front and a menu board over it
        val cx0 = x0 + (x1 - x0) / 4; val cx1 = x1 - (x1 - x0) / 4
        val cy = y0 + (h * .55f).toInt()
        if (cx1 - cx0 > 6) {
            im.rect(cx0, cy, cx1, (y0 + h * .78f).toInt(), c(0xE8E0C8)); im.hline(cx0, cx1, cy, c(0xFFFFFF))
            for (x in cx0 + 2 until cx1 - 1 step 3) im[x, cy + 2] = if ((x / 3 + seed) % 2 == 0) c(0xE83A3A) else c(0xF6D83A)
            im.rect(cx0 + 2, y0 + 4, cx1 - 2, y0 + 4 + max(2, h / 6), c(0x1A1A28))
            for (x in cx0 + 3 until cx1 - 2 step 2) im[x, y0 + 5] = c(0xF6E8A0)
        }
        // a few people in the queue
        val rnd = Paint.Rnd(seed)
        for (k in 0 until max(1, (x1 - x0) / 18)) {
            val px = x0 + 3 + rnd.int(max(1, x1 - x0 - 6)); val ph = (h * .45f).coerceAtLeast(6f)
            Pix.figure(im, px.toFloat(), y1.toFloat() - 1f, ph, Pix.mix(wall, c(0x1A1A28), .6f), pose = rnd.int(2) * 4)
        }
        // (the multiplex's tall atrium glows softer: a wall of glass at full lobby light reads as a lit sign)
        val amount = if (era == CinemaEra.MULTIPLEX) 120 else 200
        for (y in y0..y1) for (x in x0..x1) if (x in 0 until im.w && y in 0 until im.h) im.mask[y * im.w + x] = (255 shl 24) or (amount shl 16) or 255
        im.glow(x0, y0, x0, y0, amount)
    }

    /** Doors of glass in [frame] across [x0]..[x1] (texels), [n] of them, the lobby behind. */
    private fun glassDoors(im: PixImg, x0: Int, y0: Int, x1: Int, y1: Int, n: Int, frame: Ramp, era: CinemaEra, seed: Int) {
        lobbyView(im, x0, y0, x1, y1, era, seed)
        val w = (x1 - x0 + 1) / n.toFloat()
        for (k in 0..n) { val x = (x0 + k * w).roundToInt().coerceAtMost(x1); im.rect(x - 1, y0, x, y1, frame.b); im.vline(x - 1, y0, y1, frame.l) }
        im.rect(x0, y0, x1, y0 + 1, frame.b); im.hline(x0, x1, y1, frame.d)
        // push plates and pulls
        for (k in 0 until n) { val x = (x0 + (k + .5f) * w).roundToInt(); im.rect(x - 1, (y0 + y1) / 2, x, (y0 + y1) / 2 + 3, frame.h) }
        for (y in y0..y1) for (x in listOf(x0, x1)) im.mask[y * im.w + x.coerceIn(0, im.w - 1)] = 0
    }

    // ------------------------------------------------------------------ the eras

    /**
     * The theater on its plot (the model's frame: the plot's middle at x 0, the building line at z 0): the plot [lot] wide
     * and [deep] deep, the building's main block [w] wide with its middle at [cx], the auditorium's back at [back] (the
     * service yard or car park behind it, to the plot's back).
     */
    internal class Plot(val lot: Float, val deep: Float, val w: Float, val cx: Float, val back: Float)

    /** The plot when the town gives none (the town's 24 x 36 tile cinema plot). */
    const val LOT = 630f
    const val DEEP = 920f

    private fun plot(info: CinemaInfo): Plot {
        val l = if (info.width > 0f) info.width else LOT
        val d = if (info.depth > 0f) info.depth else DEEP
        // the yard behind: what the plot spares past a lobby and a hall at least 330 deep, up to [want]
        fun backOf(want: Float) = -(d - 4f - max(0f, min(want, d - 4f - 90f - 330f)))
        return when (info.era) {
            CinemaEra.PALACE -> Plot(l, d, (l - 150f).coerceIn(340f, 540f).coerceAtMost(l - 16f), 0f, backOf(120f))
            CinemaEra.MODERN -> Plot(l, d, (l - 150f).coerceIn(360f, 540f).coerceAtMost(l - 16f), 0f, backOf(120f))
            CinemaEra.TWIN -> {
                val w = (l - 240f).coerceIn(360f, 560f).coerceAtMost(l - 16f)
                Plot(l, d, w, if (l - w >= 200f) -l / 2f + 14f + w / 2f else 0f, backOf(140f))
            }
            CinemaEra.MULTIPLEX -> {
                val w = (l - 2f * DRUM_REACH - 110f).coerceIn(440f, 760f).coerceAtMost(l - 2f * DRUM_REACH - 8f)
                val x0 = -l / 2f + DRUM_REACH + 4f
                Plot(l, d, w, x0 + w / 2f, backOf(250f))
            }
        }
    }

    /**
     * Builds the theater for [info] (its look by [CinemaInfo.era]) on its plot with the site round it (alleys, yard, car
     * park: [CinemaGrounds]); [seed] varies the wear, the people inside and the cars outside.
     */
    fun build(info: CinemaInfo, seed: Int = 1, realCars: Boolean = false): CinemaModel {
        val p = plot(info)
        val b = when (info.era) {
            CinemaEra.PALACE -> palace(info, p, seed)
            CinemaEra.MODERN -> modern(info, p, seed)
            CinemaEra.TWIN -> twin(info, p, seed)
            CinemaEra.MULTIPLEX -> multiplex(info, p, seed)
        }
        val body = b.model(FloatArray(4), 0f, p.w, -p.back).let { if (p.cx != 0f) shifted(it, p.cx) else it }
        val siteB = CinemaGrounds.build(info.era, p, body.footprint, seed, realCars)
        val site = siteB.model(FloatArray(4), 0f, p.lot, p.deep)
        val fp = floatArrayOf(min(body.footprint[0], site.footprint[0]), min(body.footprint[1], site.footprint[1]), max(body.footprint[2], site.footprint[2]), max(body.footprint[3], site.footprint[3]))
        return CinemaModel(info.era, body.parts + site.parts, fp, max(body.height, site.height), p.lot, p.deep, body.lights + site.lights, siteB.parked.toList())
    }

    /** [m] moved [dx] along the street (its meshes' places, bounds and lights). */
    private fun shifted(m: CinemaModel, dx: Float): CinemaModel {
        for (part in m.parts) {
            val pos = part.mesh.pos
            for (i in pos.indices step 3) pos[i] += dx
            part.mesh.bounds[0] += dx; part.mesh.bounds[3] += dx
        }
        val fp = m.footprint
        return CinemaModel(m.era, m.parts, floatArrayOf(fp[0] + dx, fp[1], fp[2] + dx, fp[3]), m.height, m.width, m.depth, m.lights.map { it.moved(dx) })
    }

    /** The films for [n] poster cases: now showing first, then coming soon, round again if there are more cases. */
    private fun films(info: CinemaInfo, n: Int = 8): List<CinemaFilm?> = (info.nowShowing + info.comingSoon).let { l -> List(n) { if (l.isEmpty()) null else l[it % l.size] } }
    private fun header(info: CinemaInfo, i: Int): String { val n = info.nowShowing.size + info.comingSoon.size; val k = if (n == 0) i else i % n; return if (k < info.nowShowing.size) "NOW" else "SOON" }

    /** Warm light thrown down from a marquee's or canopy's soffit onto the pavement and the people under it. */
    private fun soffitLights(b: B, x0: Float, x1: Float, y: Float, z: Float, warm: Boolean, n: Int = 2) {
        for (k in 0 until n) {
            val x = x0 + (x1 - x0) * (k + .5f) / n
            b.lights += if (warm) CinemaLight(x, y - 2f, z, 1f, .78f, .5f, 70000f, 200f, down = true, cone = 1.2f)
            else CinemaLight(x, y - 2f, z, .9f, .93f, 1f, 60000f, 200f, down = true, cone = 1.2f)
        }
    }

    /** 1940s-50s: the picture palace. */
    private fun palace(info: CinemaInfo, p: Plot, seed: Int): B {
        val b = B(CinemaEra.PALACE)
        val W = p.w; val H = 280f; val LD = 90f; val AH = 300f
        val x0 = -W / 2f; val x1 = W / 2f
        // the hall behind (taller than the lobby block: the big room shows over it), a fly tower over its stage at the back
        auditorium(b, x0 + 8f, x1 - 8f, -LD, p.back, AH, brickTile(BRICK, c(0xC8A890), 11), Paint.CONCRETE, seed, fly = 60f)
        // the lobby block: cream render all round, its front painted
        b.tile(renderTile(CREAM, 3)).box(x0, 0f, -LD, x1, H, 0f, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.BACK))
        b.tile(ROOF).box(x0, 0f, -LD, x1, H - .5f, 0f, setOf(MeshBuilder.Side.TOP))
        val fw = tx(W); val fh = tx(H)
        val F = PixImg(fw, fh)
        fun col(x: Float) = ((x - x0) / W * fw).toInt()
        fun row(y: Float) = ((H - y) / H * fh).toInt()
        F.fill(CREAM.b)
        Paint.speckle(F, CREAM, seed, .6f)
        val gf = 62f
        // the ground floor: black-maroon glass panels with chrome strips
        F.rect(0, row(gf), fw - 1, fh - 1, MAROON.b)
        for (x in 0 until fw step tx(24f)) F.vline(x, row(gf), fh - 1, MAROON.o)
        F.hline(0, fw - 1, row(gf), Paint.STEEL.h); F.hline(0, fw - 1, row(gf) + 1, Paint.STEEL.d)
        F.hline(0, fw - 1, row(6f), Paint.STEEL.l); F.rect(0, row(6f) + 1, fw - 1, fh - 1, MAROON.o)
        for (x in 0 until fw step 3) F[x, row(gf) + 3] = MAROON.l
        // the upper storeys: fluted pilasters with gold capitals, tall arched windows in three bays, deco bands, the cornice
        val hw = W / 2f
        for (px in listOf(x0, x1 - 14f, -W * .3f - 7f, W * .3f - 7f)) {
            val a = col(px); val e = col(px + 14f)
            F.rect(a, row(262f), e, row(gf) - 1, CREAM.l)
            for (x in a + 1 until e step 2) F.vline(x, row(254f), row(gf + 6f), CREAM.d)
            F.vline(a, row(262f), row(gf) - 1, CREAM.o); F.vline(e, row(262f), row(gf) - 1, CREAM.d)
            F.rect(a - 1, row(266f), e + 1, row(256f), GOLD.b); F.hline(a - 1, e + 1, row(266f), GOLD.h); F.hline(a - 1, e + 1, row(256f), GOLD.o)
        }
        for (wc in listOf(.2f, .42f, .73f, .88f).flatMap { listOf(-it, it) }) {
            val wx = wc * hw - 8f
            val a = col(wx); val e = col(wx + 16f); val top = row(246f); val bot = row(152f)
            val r = (e - a) / 2f
            F.rect(a - 1, top + r.toInt(), e + 1, bot + 1, GOLD.d)
            Pix.disc(F, (a + e + 1) / 2f, top + r + 1, r + 1.5f, GOLD.d)
            Pix.disc(F, (a + e + 1) / 2f, top + r + 1, r, c(0x2A3A5A)); F.rect(a, top + r.toInt() + 1, e, bot, c(0x2A3A5A))
            // its deco glazing bars, a lit room behind, a sill and a keystone
            F.vline((a + e) / 2, top + 2, bot, GOLD.b); for (y in top + r.toInt() + 6 until bot step 8) F.hline(a, e, y, GOLD.b)
            for (y in top until bot + 1) for (x in a - 1..e + 1) if (F[x, y] == c(0x2A3A5A)) lamp(F, x, y, x, y, 170)
            F.hline(a - 3, e + 3, bot + 2, CREAM.h); F.hline(a - 3, e + 3, bot + 3, CREAM.o)
            F.rect((a + e) / 2 - 2, top - 4, (a + e) / 2 + 2, top - 1, GOLD.b)
        }
        // the deco bands: teal and gold zigzag over the marquee, a frieze of chevrons under the cornice
        F.rect(0, row(140f), fw - 1, row(134f), TEAL.b); F.hline(0, fw - 1, row(140f), TEAL.l)
        for (x in 0 until fw) { val y = row(137f) + (if ((x / 3) % 2 == 0) x % 3 else 2 - x % 3) - 1; F[x, y] = GOLD.l }
        F.rect(0, 0, fw - 1, row(268f), GOLD.b); F.hline(0, fw - 1, row(268f), GOLD.o)
        for (x in 0 until fw step 6) Pix.tri(F, x.toFloat(), row(268f) + 1f, x + 6f, row(268f) + 1f, x + 3f, row(262f).toFloat(), TEAL.b)
        for (x in 0 until fw step 4) F[x, row(274f)] = GOLD.h
        F.hline(0, fw - 1, 0, GOLD.h)
        // the entrance: brass doors to the lobby, a lit transom
        val ex0 = -96f; val ex1 = 96f; val ey = 60f
        F.rect(col(ex0), row(ey), col(ex1), fh - 1, GOLD.d)
        glassDoors(F, col(ex0) + 2, row(44f), col(ex1) - 2, fh - 2, 8, GOLD, CinemaEra.PALACE, seed)
        F.rect(col(ex0) + 2, row(58f), col(ex1) - 2, row(46f), c(0xF6D890)); for (x in col(ex0) + 4 until col(ex1) - 2 step 5) F.vline(x, row(58f), row(46f), GOLD.d)
        lamp(F, col(ex0) + 2, row(58f), col(ex1) - 2, row(46f), 255)
        frontWall(b.lit, F, x0, x1, 0f, H, 0f, ex0, ex1, ey, 18f, GOLD, downlights(tx(ex1 - ex0), tx(18f), GOLD, bulbs = true), b)
        coping(b, x0, x1, -LD, 0f, H, GOLD, 2.5f, 3f)
        // the raised centre: stepped deco crown with a sunburst
        val steps = listOf(Triple(86f, H + 3f, 26f), Triple(60f, H + 29f, 18f), Triple(34f, H + 47f, 12f))
        for ((k, st) in steps.withIndex()) {
            val (sw, y0, sh) = st
            b.det.box(-sw, y0, -10f, sw, y0 + sh, 0f) { side, w, h ->
                when (side) {
                    MeshBuilder.Side.BOTTOM -> null
                    MeshBuilder.Side.TOP -> topOf(w, h, GOLD)
                    MeshBuilder.Side.FRONT -> Paint.panel(w, h, CREAM, 40 + k).also { im ->
                        im.hline(0, w - 1, 1, GOLD.l); im.hline(0, w - 1, h - 3, TEAL.b); im.hline(0, w - 1, h - 2, TEAL.d)
                        if (k == 0) for (r in 0 until 9) Pix.line(im, w / 2, h - 3, w / 2 + ((r - 4) * w / 10), 2, GOLD.b)
                        if (k == 0) Pix.disc(im, w / 2f, h - 3f, 5f, GOLD.l)
                    }
                    else -> Paint.panel(w, h, CREAM, 44 + k + side.ordinal)
                }
            }
        }
        // the marquee: three faces of letters framed in bulbs, a soffit of bulbs, over the pavement
        val my0 = 66f; val my1 = 130f; val mf = 54f; val mhw = 140f; val mbw = 176f
        val bl = listOf(v(-mbw, 0f, .5f), v(mbw, 0f, .5f), v(mhw, 0f, mf), v(-mhw, 0f, mf))
        fun at(q: FloatArray, y: Float) = v(q[0], y, q[2])
        val bands = 9f
        val frontW = tx(mhw * 2); val fH = tx(my1 - my0)
        val mq = PixImg(frontW, fH)
        val board = letterBoard(frontW - 4, tx(my1 - my0 - 2 * bands), bill(info, 3), GOLD)
        mq.fill(MAROON.b)
        for (x in 0 until frontW) { mq[x, 0] = GOLD.o; mq[x, 1] = GOLD.l; mq[x, fH - 1] = GOLD.o; mq[x, fH - 2] = GOLD.d }
        for (y in 0 until board.h) for (x in 0 until board.w) { mq[2 + x, tx(bands) + y] = board[x, y]; mq.mask[(tx(bands) + y) * frontW + 2 + x] = board.mask[y * board.w + x] }
        mq.glow(0, 0, 0, 0, 0)
        // the name along the crown band
        BigFont.draw(mq, info.signName, (frontW - BigFont.width(info.signName)) / 2, 2, GOLD.l)
        for (y in 2..9) for (x in 0 until frontW) if (mq[x, y] == GOLD.l) mq.mask[y * frontW + x] = (255 shl 24) or (255 shl 16) or 255
        val sideL = MeshBuilder.len(MeshBuilder.sub(bl[3], bl[0]))
        val sideImg = PixImg(tx(sideL), fH).also { im ->
            im.fill(MAROON.b)
            for (x in 0 until im.w) { im[x, 0] = GOLD.o; im[x, 1] = GOLD.l; im[x, fH - 1] = GOLD.o; im[x, fH - 2] = GOLD.d }
            val sb = letterBoard(im.w - 4, tx(my1 - my0 - 2 * bands), listOf("NOW", "ON", "SCREEN"), GOLD, ink = MAROON.b)
            for (y in 0 until sb.h) for (x in 0 until sb.w) { im[2 + x, tx(bands) + y] = sb[x, y]; im.mask[(tx(bands) + y) * im.w + 2 + x] = sb.mask[y * sb.w + x] }
            im.glow(0, 0, 0, 0, 0)
        }
        b.sign.hexa(bl.map { at(it, my0) }, bl.map { at(it, my1) }, mq, Paint.swatch(MAROON.d), sideImg, sideImg, marqueeTop(tx(mhw * 2), tx(mf), MAROON))
        b.sign.flat(bl.map { at(it, my0) }.reversed(), downlights(tx(mbw * 2), tx(mf), MAROON, bulbs = true), v(-mbw, my0, mf), v(1f, 0f, 0f), v(0f, 0f, -1f), v(0f, -1f, 0f))
        // its bulbs: along the foot band of all three faces and the crown of the sides and the top edge, chasing round
        fun run(a: FloatArray, e: FloatArray, y: Float, inset: Float = 2.2f) {
            val dx = e[0] - a[0]; val dz = e[2] - a[2]; val l = sqrt(dx * dx + dz * dz)
            var nx = dz / l; var nz = -dx / l
            if (nx * (a[0] + e[0]) / 2f + nz * ((a[2] + e[2]) / 2f - mf / 2f) < 0f) { nx = -nx; nz = -nz }
            val o = .35f
            b.bulbs(v(a[0] + nx * o + dx / l * inset, y, a[2] + nz * o + dz / l * inset), v(e[0] + nx * o - dx / l * inset, y, e[2] + nz * o - dz / l * inset), 4.2f, v(nx, 0f, nz))
        }
        for (y in listOf(my0 + bands / 2f, my1 - 1.1f)) { run(bl[0], bl[3], y); run(bl[3], bl[2], y); run(bl[2], bl[1], y) }
        // the bulbs round the top's lip, seen from above
        b.bulbs(v(-mhw + 3f, my1 + .3f, mf - 3f), v(mhw - 3f, my1 + .3f, mf - 3f), 6f, MeshBuilder.Side.TOP, size = 2.2f)
        // the crest over the marquee: a gold fan with the bulbs round its rim
        val crest = PixImg(tx(80f), tx(22f)).also { im ->
            val cx = im.w / 2f; val cy = im.h.toFloat()
            for (y in 0 until im.h) for (x in 0 until im.w) { val dx = x + .5f - cx; val dy = y + .5f - cy; val d = sqrt(dx * dx + dy * dy); if (d < im.h - 1) im[x, y] = if (d > im.h - 3) GOLD.o else if (((kotlin.math.atan2(dy, dx) * 8 / PI).toInt()) % 2 == 0) GOLD.b else GOLD.l }
            Pix.disc(im, cx, cy, 6f, MAROON.b)
        }
        b.det.box(-40f, my1, mf - 5f, 40f, my1 + 22f, mf - 3f) { s, _, _ -> when (s) { MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK -> crest; else -> null } }
        // the blade sign: projecting square to the street so it reads up and down it, the name stacked down both broad
        // faces and the edge, bulbs chasing up its edges, a gold crown; it rises past the cornice to the crown's height
        blade(b, info.signName, 0f, 160f, 430f, 4f, 80f, 18f, MAROON, GOLD, CREAM.h, chase = true)
        // the ticket booth: an octagon of glass on a maroon base under a gold dome
        booth(b, 0f, -8f, seed)
        // poster cases: four each side of the doors on the front, two down each side (onto the alleys)
        val fl = films(info)
        var i = 0
        for (x in caseRow(x1 - 26f, ex1 + 22f, 30f)) for (sx in listOf(-1f, 1f)) { posterCase(b, fl[i % 8], sx * x, 32f, 0f, MeshBuilder.Side.FRONT, GOLD, header(info, i)); i++ }
        for (z in listOf(-24f, -62f)) {
            posterCase(b, fl[i % 8], x0, 32f, z, MeshBuilder.Side.LEFT, GOLD, header(info, i)); i++
            posterCase(b, fl[i % 8], x1, 32f, z, MeshBuilder.Side.RIGHT, GOLD, header(info, i)); i++
        }
        // the marquee's soffit and the blade light the pavement and the front
        soffitLights(b, -mhw, mhw, my0, mf * .55f, warm = true, n = 3)
        b.lights += CinemaLight(0f, 300f, 110f, 1f, .72f, .5f, 50000f, 260f)
        return b
    }

    /** A marquee's or canopy's top from above: tar-dark sheet metal with a raised lip round it and its seams. */
    private fun marqueeTop(w: Int, h: Int, r: Ramp): PixImg = PixImg(w, h).also { im ->
        im.fill(c(0x3A3A46))
        val rnd = Paint.Rnd(w * 31 + h)
        repeat(w * h / 12) { im[rnd.int(w), rnd.int(h)] = if (rnd.next() < .5f) c(0x2E2E38) else c(0x464654) }
        for (x in 0 until w step 12) im.vline(x, 2, h - 3, c(0x2A2A34))
        for (k in 0..1) { im.hline(0, w - 1, k, if (k == 0) r.o else r.l); im.hline(0, w - 1, h - 1 - k, if (k == 0) r.o else r.d); im.vline(k, 0, h - 1, r.b); im.vline(w - 1 - k, 0, h - 1, r.b) }
    }

    /**
     * A blade sign standing out from the front: [name] stacked down both faces and the edge, its frame in [frame], from
     * [y0] to [y1], [z0] to [z1] out, [t] thick; bulbs up its edge chasing; a crown on top.
     */
    private fun blade(b: B, name: String, x: Float, y0: Float, y1: Float, z0: Float, z1: Float, t: Float, field: Ramp, frame: Ramp, letters: Int, chase: Boolean, slab: Boolean = false) {
        val h = y1 - y0
        fun signFace(w: Int, hh: Int): PixImg = PixImg(w, hh).also { im ->
            im.fill(field.b)
            im.border(frame.o)
            im.rect(1, 1, w - 2, 2, frame.l); im.rect(1, hh - 3, w - 2, hh - 2, frame.d); im.rect(1, 1, 2, hh - 2, frame.b); im.rect(w - 3, 1, w - 2, hh - 2, frame.b)
            val hi = Pix.mix(letters, c(0xFFFFFF), .45f)
            stacked(im, name, 3, 5, w - 6, hh - 10, letters, field.o, hi)
            for (i in im.px.indices) if (im.px[i] == letters || im.px[i] == hi) im.mask[i] = (255 shl 24) or (255 shl 16) or 255
            im.glow(0, 0, 0, 0, 0)
        }
        val sideImg = signFace(tx(z1 - z0), tx(h))
        val edgeImg = signFace(tx(t), tx(h))
        b.sign.box(x - t / 2f, y0, z0, x + t / 2f, y1, z1) { s, w, hh ->
            when (s) {
                MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> sideImg
                MeshBuilder.Side.FRONT -> edgeImg
                MeshBuilder.Side.BACK -> profile(tx(t), frame, false)
                MeshBuilder.Side.TOP, MeshBuilder.Side.BOTTOM -> topOf(w, hh, frame)
            }
        }
        // its brackets back to the wall
        for (yy in listOf(y0 + 10f, y1 - 14f)) b.det.beam(v(x, yy, 0f), v(x, yy, z0 + .2f), 2f, Paint.swatch(Paint.IRON.b))
        if (chase) {
            for (sx in listOf(-1f, 1f)) b.bulbs(v(x + sx * (t / 2f - 1.2f), y0 + 2f, z1 + .3f), v(x + sx * (t / 2f - 1.2f), y1 - 2f, z1 + .3f), 4.2f, MeshBuilder.Side.FRONT)
            // and down both broad faces by the outer edge, so it chases seen along the street too
            for (sx in listOf(-1f, 1f)) b.bulbs(v(x + sx * (t / 2f + .3f), y0 + 6f, z1 - 2.6f), v(x + sx * (t / 2f + .3f), y1 - 6f, z1 - 2.6f), 4.2f, if (sx < 0) MeshBuilder.Side.LEFT else MeshBuilder.Side.RIGHT)
            for (sx in listOf(-1f, 1f)) {
                val n = if (sx < 0) MeshBuilder.Side.LEFT else MeshBuilder.Side.RIGHT
                b.bulbs(v(x + sx * (t / 2f + .3f), y1 - 2f, z0 + 2f), v(x + sx * (t / 2f + .3f), y1 - 2f, z1 - 2f), 4.2f, n)
                b.bulbs(v(x + sx * (t / 2f + .3f), y0 + 2f, z0 + 2f), v(x + sx * (t / 2f + .3f), y0 + 2f, z1 - 2f), 4.2f, n)
            }
        }
        if (!slab) {
            // the crown: a gold ball on a spire
            val mz = (z0 + z1) / 2f
            b.det.upright(x, mz, y1, y1 + 4f, t * .7f, t * .25f, 8, Paint.strip(Paint.stripSize(t * .7f, 4f).first, 5, frame, 2), cap = null)
            b.sign.upright(x, mz, y1 + 4f, y1 + 14f, 1.2f, .3f, 6, Paint.strip(8, 12, frame, 3), cap = null)
            b.sign.upright(x, mz, y1 + 3.5f, y1 + 8.5f, 2.6f, 2.6f, 8, Paint.strip(20, 6, GOLD, 4).also { glowAll(it) }, cap = Paint.cap(2.6f, GOLD))
        }
    }

    /** The palace's ticket booth: an octagon, a maroon base, a band of lit glass, a gold dome. */
    private fun booth(b: B, x: Float, z: Float, seed: Int) {
        val r = 11f
        val (w, h0) = Paint.stripSize(r, 16f)
        b.det.upright(x, z, 0f, 16f, r, r, 8, Paint.strip(w, h0, MAROON, seed).also { im -> im.hline(0, im.w - 1, 2, Paint.STEEL.h); im.hline(0, im.w - 1, im.h - 4, Paint.STEEL.l) })
        val (gw, gh) = Paint.stripSize(r - .6f, 24f)
        val glass = PixImg(gw, gh).also { im ->
            lobbyView(im, 0, 0, gw - 1, gh - 1, CinemaEra.PALACE, seed)
            for (x in 0 until gw step gw / 8) im.vline(x, 0, gh - 1, GOLD.b)
            val t = "TICKETS"; im.rect(gw / 2 - 15, 1, gw / 2 + 14, 7, MAROON.d); im.text(t, gw / 2 - PixImg.textWidth(t) / 2, 2, GOLD.h)
            Pix.figure(im, gw / 2f, gh - 1f, 20f, c(0x3A2A2A))
            im.rect(gw / 2 - 3, gh - 5, gw / 2 + 2, gh - 4, GOLD.l)
        }
        b.lit.upright(x, z, 16f, 40f, r - .6f, r - .6f, 8, glass)
        b.det.upright(x, z, 40f, 43f, r + 1f, r + 1f, 8, Paint.strip(Paint.stripSize(r + 1f, 3f).first, 4, GOLD, 2))
        b.det.upright(x, z, 43f, 51f, r, 2f, 8, Paint.strip(Paint.stripSize(r, 8f).first, 8, GOLD, 3), cap = Paint.cap(2f, GOLD))
        b.det.upright(x, z, 51f, 56f, .7f, .2f, 4, Paint.strip(4, 4, GOLD, 4))
    }

    /** 1960s-70s: the old palace's bones with a modernised front. */
    private fun modern(info: CinemaInfo, p: Plot, seed: Int): B {
        val b = B(CinemaEra.MODERN)
        val W = p.w; val H = 220f; val LD = 80f; val AH = 290f
        val x0 = -W / 2f; val x1 = W / 2f
        auditorium(b, x0 + 8f, x1 - 8f, -LD, p.back, AH, brickTile(Ramp(c(0x3A2A22), c(0x6A4A3A), c(0x8A6048), c(0xA07454), c(0xB88A66)), c(0xB8A894), 21), Paint.CONCRETE, seed, fly = 40f)
        val stone = Ramp(c(0x5A5040), c(0xC0B49A), c(0xD8CCB0), c(0xE6DCC4), c(0xF4ECDA))
        b.tile(renderTile(stone, 9)).box(x0, 0f, -LD, x1, H, 0f, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.BACK))
        b.tile(ROOF).box(x0, 0f, -LD, x1, H - .5f, 0f, setOf(MeshBuilder.Side.TOP))
        val fw = tx(W); val fh = tx(H)
        val F = PixImg(fw, fh)
        fun col(x: Float) = ((x - x0) / W * fw).toInt()
        fun row(y: Float) = ((H - y) / H * fh).toInt()
        // the upper front: turquoise mosaic in small squares
        for (y in 0 until fh) for (x in 0 until fw) F[x, y] = if ((x / 2 + y / 2) % 5 == 0) TEAL.l else if ((x / 2 * 7 + y / 2 * 3) % 11 == 0) TEAL.d else TEAL.b
        for (y in 0 until fh step 2) for (x in 0 until fw step 2) F[x, y] = Pix.mix(F[x, y], c(0x1E4A4A), .25f)
        // a band of tall slot windows of the upstairs lounge between the fins
        for (k in 0 until 9) {
            val wx = x0 + W * (k + .5f) / 9f
            if (abs(wx - (x0 + 24f)) < 20f) continue
            val a = col(wx - 3f); val e = col(wx + 3f)
            F.rect(a - 1, row(200f), e + 1, row(148f), stone.d)
            F.rect(a, row(199f), e, row(149f), c(0x1A2A3A))
            lamp(F, a, row(199f), e, row(149f), 150)
        }
        // the ground floor: travertine panels
        F.rect(0, row(62f), fw - 1, fh - 1, stone.b)
        for (y in row(62f) until fh) for (x in 0 until fw) if ((x * 13 + y * 7) % 23 == 0) F[x, y] = stone.d
        for (x in 0 until fw step tx(20f)) F.vline(x, row(62f), fh - 1, stone.d)
        for (y in listOf(30f, 6f)) F.hline(0, fw - 1, row(y), stone.d)
        F.hline(0, fw - 1, row(62f), Paint.STEEL.h); F.hline(0, fw - 1, row(62f) + 1, Paint.STEEL.d)
        F.rect(0, 0, fw - 1, 2, Paint.STEEL.l); F.hline(0, fw - 1, 3, Paint.STEEL.o)
        // the entrance: a glass shopfront in aluminium, the lobby behind
        val ex0 = -100f; val ex1 = 100f; val ey = 58f
        F.rect(col(ex0), row(ey), col(ex1), fh - 1, Paint.STEEL.b)
        glassDoors(F, col(ex0) + 1, row(ey) + 1, col(ex1) - 1, fh - 2, 8, Paint.STEEL, CinemaEra.MODERN, seed)
        frontWall(b.lit, F, x0, x1, 0f, H, 0f, ex0, ex1, ey, 14f, Paint.STEEL, downlights(tx(ex1 - ex0), tx(14f), Paint.WHITE), b)
        // aluminium fins up the mosaic, a cap along the top
        val fins = ((W - 24f) / 26f).toInt()
        for (k in 0..fins) {
            val x = x0 + 12f + k * (W - 24f) / fins
            b.det.box(x - 1.2f, 128f, 0f, x + 1.2f, H - 2f, 5f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.STEEL, 3, edges = false) }
        }
        coping(b, x0, x1, -LD, 0f, H - 2f, Paint.STEEL, 4.5f, 4f)
        // the marquee: a box of backlit boards, aluminium bands, rows of chasing bulbs along its foot and its top
        val my0 = 66f; val my1 = 122f; val mf = 60f; val mhw = 172f
        val bands = 6f
        val bh = tx(my1 - my0 - 2 * bands)
        fun boardFace(w: Int, lines: List<String>, colours: List<Int> = emptyList()) = PixImg(w, tx(my1 - my0)).also { im ->
            im.fill(Paint.STEEL.b)
            for (x in 0 until w) { im[x, 0] = Paint.OUT; im[x, 1] = Paint.STEEL.h; im[x, im.h - 1] = Paint.OUT; im[x, im.h - 2] = Paint.STEEL.d }
            val bd = letterBoard(w - 4, bh, lines, Paint.STEEL, colours = colours)
            for (y in 0 until bd.h) for (x in 0 until bd.w) { im[2 + x, tx(bands) + y] = bd[x, y]; im.mask[(tx(bands) + y) * w + 2 + x] = bd.mask[y * bd.w + x] }
            im.glow(0, 0, 0, 0, 0)
        }
        val red = c(0xC82A2A)
        val front = boardFace(tx(mhw * 2), bill(info, 3), listOf(c(0x14141E), red, c(0x14141E)))
        val side = boardFace(tx(mf), listOf("NOW", "TODAY"))
        b.sign.box(-mhw, my0, 0f, mhw, my1, mf) { s, w, h ->
            when (s) {
                MeshBuilder.Side.FRONT -> front
                MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> side
                MeshBuilder.Side.TOP -> marqueeTop(w, h, Paint.STEEL)
                MeshBuilder.Side.BOTTOM -> downlights(w, h, Paint.STEEL)
                MeshBuilder.Side.BACK -> null
            }
        }
        b.bulbs(v(-mhw + 2f, my0 + 2.4f, mf + .35f), v(mhw - 2f, my0 + 2.4f, mf + .35f), 4.4f, MeshBuilder.Side.FRONT)
        b.bulbs(v(-mhw + 2f, my1 - 2.4f, mf + .35f), v(mhw - 2f, my1 - 2.4f, mf + .35f), 4.4f, MeshBuilder.Side.FRONT)
        // the name in channel letters standing on the marquee, red faced and lit
        channelLetters(b, info.signName, 0f, my1, mf - 10f, 34f, red, c(0xFFFFFF), 3)
        // a slab sign up the left corner, rising over the roof
        blade(b, info.signName, x0 + 24f, 76f, 320f, 4f, 48f, 12f, Ramp(c(0x3A3A50), c(0xD8D8E0), c(0xF6F6FA), c(0xFFFFFF), c(0xFFFFFF)), Paint.STEEL, red, chase = false, slab = true)
        // poster cases: four each side of the entrance on the front, two on each side
        val fl = films(info)
        var i = 0
        for (x in caseRow(x1 - 22f, ex1 + 22f, 30f)) for (sx in listOf(-1f, 1f)) { posterCase(b, fl[i % 8], sx * x, 32f, 0f, MeshBuilder.Side.FRONT, Paint.STEEL, header(info, i)); i++ }
        for (z in listOf(-22f, -56f)) {
            posterCase(b, fl[i % 8], x0, 32f, z, MeshBuilder.Side.LEFT, Paint.STEEL, header(info, i)); i++
            posterCase(b, fl[i % 8], x1, 32f, z, MeshBuilder.Side.RIGHT, Paint.STEEL, header(info, i)); i++
        }
        soffitLights(b, -mhw, mhw, my0, mf * .55f, warm = true, n = 3)
        b.lights += CinemaLight(0f, 200f, 100f, 1f, .8f, .62f, 36000f, 240f)
        return b
    }

    /** Channel letters standing on a ledge at [y] (its foot), centred at [x], [z] out, [h] tall: cut out of the sky. */
    private fun channelLetters(b: B, text: String, x: Float, y: Float, z: Float, h: Float, face: Int, rim: Int, scale: Int) {
        val s = text.filter { BigFont.has(it) }
        val w = BigFont.width(s, scale, scale) + 4
        val im = PixImg(w, BigFont.H * scale + 4)
        BigFont.outlined(im, s, 2, 2, scale, scale, rim, null) { if (it < scale) Pix.mix(face, c(0xFFFFFF), .35f) else face }
        for (i in im.px.indices) if (im.px[i] ushr 24 != 0) im.mask[i] = (255 shl 24) or (255 shl 16) or 255
        im.glow(0, 0, 0, 0, 255)
        val ww = h * im.w / im.h
        // their backs: the same shapes (mirrored, so they line up through the gaps) in the cans' dark metal
        val back = PixImg(im.w, im.h)
        for (yy in 0 until im.h) for (xx in 0 until im.w) if (im[im.w - 1 - xx, yy] ushr 24 != 0) back[xx, yy] = if (yy % 4 == 0) Paint.IRON.l else Paint.IRON.b
        b.sign.box(x - ww / 2f, y, z - 1f, x + ww / 2f, y + h, z + 1f) { side, _, _ -> if (side == MeshBuilder.Side.FRONT) im else if (side == MeshBuilder.Side.BACK) back else null }
        // the struts holding them up
        for (k in -2..2) b.det.beam(v(x + k * ww / 5f, y, z - 1.2f), v(x + k * ww / 5f, y + h * .6f, z - 1.2f), .7f, Paint.swatch(Paint.IRON.b))
    }

    private val neonCache = HashMap<Int, PixImg>()

    /** A neon tube from [a] to [e] in [col] (lit in its own colour from dusk); [phase] 0..2 puts it in a chase step (it steps). */
    private fun neon(b: B, a: FloatArray, e: FloatArray, col: Int, phase: Int = -1) {
        val sw = neonCache.getOrPut(col) { PixImg(2, 2).also { it.fill(col); it[0, 0] = Pix.mix(col, c(0xFFFFFF), .6f); glowAll(it) } }
        (if (phase < 0) b.sign else b.chase[phase % 3]).beam(a, e, .9f, sw)
    }

    /** A neon run cut in [n] pieces that light one after another (the chase steps), from [a] to [e]. */
    private fun neonRun(b: B, a: FloatArray, e: FloatArray, col: Int, n: Int) {
        for (k in 0 until n) {
            val t0 = k / n.toFloat() + .004f; val t1 = (k + 1) / n.toFloat() - .004f
            neon(b, MeshBuilder.add(a, MeshBuilder.mul(MeshBuilder.sub(e, a), t0)), MeshBuilder.add(a, MeshBuilder.mul(MeshBuilder.sub(e, a), t1)), col, k % 3)
        }
    }

    /** 1980s-90s: the boxy twin (or triple) with neon trim and a row of poster cases, its car park beside it. */
    private fun twin(info: CinemaInfo, p: Plot, seed: Int): B {
        val b = B(CinemaEra.TWIN)
        val W = p.w; val H = 150f; val LD = 72f; val AH = 240f
        val x0 = -W / 2f; val x1 = W / 2f
        val screens = if (info.screens > 0) info.screens else 3
        // two (or three) auditoria side by side: a split roof line shows from above and behind
        val halls = screens.coerceIn(2, 3)
        val hx0 = x0 + 8f; val hx1 = x1 - 8f
        for (k in 0 until halls) {
            val a = hx0 + (hx1 - hx0) * k / halls + (if (k > 0) 2f else 0f); val e = hx0 + (hx1 - hx0) * (k + 1) / halls - (if (k < halls - 1) 2f else 0f)
            auditorium(b, a, e, -LD, p.back + (k % 2) * 40f, AH - (k % 2) * 26f, renderTile(STUCCO, 31), Paint.CONCRETE, seed + k * 5)
        }
        b.tile(renderTile(STUCCO, 31)).box(x0, 0f, -LD, x1, H, 0f, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.BACK))
        b.tile(ROOF).box(x0, 0f, -LD, x1, H - .5f, 0f, setOf(MeshBuilder.Side.TOP))
        val fw = tx(W); val fh = tx(H)
        val F = PixImg(fw, fh)
        fun col(x: Float) = ((x - x0) / W * fw).toInt()
        fun row(y: Float) = ((H - y) / H * fh).toInt()
        F.fill(STUCCO.b); Paint.speckle(F, STUCCO, seed, 1f)
        // the stripes: teal and magenta bands round the front, a darker plinth; glass-block windows over the canopy
        F.rect(0, row(130f), fw - 1, row(122f), TEAL.b); F.hline(0, fw - 1, row(130f), TEAL.l); F.hline(0, fw - 1, row(122f), TEAL.d)
        F.rect(0, row(117f), fw - 1, row(113f), MAGENTA.b); F.hline(0, fw - 1, row(113f), MAGENTA.d)
        for (k in listOf(-1, 1)) {
            val cx = k * W * .36f
            val a = col(cx - 26f); val e = col(cx + 26f)
            F.rect(a, row(106f), e, row(72f), c(0xB8D8E8))
            for (x in a..e) for (y in row(106f)..row(72f)) if ((x - a) % 5 == 0 || (y - row(106f)) % 5 == 0) F[x, y] = c(0x8AA8C0)
            lamp(F, a, row(106f), e, row(72f), 130)
        }
        F.rect(0, row(5f), fw - 1, fh - 1, STUCCO.d); F.hline(0, fw - 1, row(5f), STUCCO.o)
        F.rect(0, 0, fw - 1, 2, STUCCO.l)
        // the entrance: dark tinted glass and the lobby's pink glow
        val ex0 = -84f; val ex1 = 84f; val ey = 56f
        F.rect(col(ex0), row(ey), col(ex1), fh - 1, NAVY.b)
        glassDoors(F, col(ex0) + 1, row(ey) + 1, col(ex1) - 1, fh - 2, 6, NAVY, CinemaEra.TWIN, seed)
        frontWall(b.lit, F, x0, x1, 0f, H, 0f, ex0, ex1, ey, 12f, NAVY, downlights(tx(ex1 - ex0), tx(12f), NAVY), b)
        coping(b, x0, x1, -LD, 0f, H, TEAL, 2f, 2.5f)
        // the canopy with its letter board, neon round its edges, bulbs chasing along its face and under it
        val my0 = 60f; val my1 = 104f; val mf = 42f; val mhw = 118f
        // one size on the board: the showtimes go before the letters shrink
        val withTimes = bill(info, 3, times = true).take(screens.coerceAtMost(3)).mapIndexed { i, s -> if (s.isEmpty()) s else "${i + 1} $s" }
        val bw = tx(mhw * 2) - 6 - 9
        val lines = if (withTimes.all { BigFont.width(it.filter { ch -> BigFont.has(ch) }) <= bw }) withTimes
            else bill(info, 3).take(screens.coerceAtMost(3)).mapIndexed { i, s -> if (s.isEmpty()) s else "${i + 1} $s" }
        val front = PixImg(tx(mhw * 2), tx(my1 - my0)).also { im ->
            im.fill(NAVY.b)
            val bd = letterBoard(im.w - 6, im.h - 8, lines, NAVY, colours = listOf(c(0x14141E), c(0xC82A7A), c(0x14141E)))
            for (y in 0 until bd.h) for (x in 0 until bd.w) { im[3 + x, 4 + y] = bd[x, y]; im.mask[(4 + y) * im.w + 3 + x] = bd.mask[y * bd.w + x] }
            im.border(NAVY.o); im.glow(0, 0, 0, 0, 0)
        }
        val side = PixImg(tx(mf), tx(my1 - my0)).also { im -> im.fill(NAVY.b); im.border(NAVY.o); val t = "$screens"; BigFont.draw(im, t, (im.w - BigFont.width(t, 3)) / 2, (im.h - 21) / 2, NEON_CYAN, 3); glowAll(im, 255, NAVY.b) }
        b.sign.box(-mhw, my0, 0f, mhw, my1, mf) { s, w, h ->
            when (s) {
                MeshBuilder.Side.FRONT -> front
                MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> side
                MeshBuilder.Side.TOP -> marqueeTop(w, h, NAVY)
                MeshBuilder.Side.BOTTOM -> downlights(w, h, NAVY, bulbs = true)
                MeshBuilder.Side.BACK -> null
            }
        }
        // the canopy's neon: pink along its top edge stepping in a run, cyan under it, its ends
        neonRun(b, v(-mhw, my1 + .5f, mf + .5f), v(mhw, my1 + .5f, mf + .5f), NEON_PINK, 9)
        neon(b, v(-mhw, my0 - .5f, mf + .5f), v(mhw, my0 - .5f, mf + .5f), NEON_CYAN)
        for (sx in listOf(-1f, 1f)) { neon(b, v(sx * (mhw + .5f), my0, mf + .5f), v(sx * (mhw + .5f), my1, mf + .5f), NEON_PINK); neon(b, v(sx * (mhw + .5f), my1 + .5f, mf), v(sx * (mhw + .5f), my1 + .5f, 0f), NEON_PINK) }
        b.bulbs(v(-mhw + 3f, my0 - .4f, mf - 3f), v(mhw - 3f, my0 - .4f, mf - 3f), 4.4f, MeshBuilder.Side.BOTTOM)
        b.bulbs(v(-mhw + 3f, my0 + 2.2f, mf + .4f), v(mhw - 3f, my0 + 2.2f, mf + .4f), 4.4f, MeshBuilder.Side.FRONT, size = 1.7f)
        // the name sign on the parapet: neon letters on a navy box, a cyan rim
        val sw = 270f; val sy0 = H - 4f; val sy1 = H + 44f
        val nameImg = PixImg(tx(sw), tx(sy1 - sy0)).also { im ->
            im.fill(NAVY.b)
            for (x in 0 until im.w) { im[x, 1] = NEON_CYAN; im[x, im.h - 2] = NEON_CYAN }
            for (y in 1 until im.h - 1) { im[1, y] = NEON_CYAN; im[im.w - 2, y] = NEON_CYAN }
            val name = info.signName
            var sc = 4
            while (sc > 1 && (BigFont.width(name, sc, sc) > im.w - 10 || BigFont.H * sc > im.h - 18)) sc--
            BigFont.outlined(im, name, (im.w - BigFont.width(name, sc, sc)) / 2, 5, sc, sc, c(0x8A1A5A), null) { if (it < sc) c(0xFFD0F0) else NEON_PINK }
            val sub = "CINEMA $screens"
            BigFont.draw(im, sub, (im.w - BigFont.width(sub)) / 2, im.h - 10, NEON_CYAN)
            glowAll(im, 255, NAVY.b)
            im.border(NAVY.o); for (x in 0 until im.w) { im.mask[x] = 0; im.mask[(im.h - 1) * im.w + x] = 0 }
        }
        b.sign.box(-sw / 2f, sy0, -6f, sw / 2f, sy1, 3.5f) { s, w, h -> when (s) { MeshBuilder.Side.FRONT -> nameImg; MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> topOf(w, h, NAVY); else -> Paint.panel(w, h, NAVY, 5 + s.ordinal) } }
        // neon along the parapet's top edge and the teal stripe, stepping
        neonRun(b, v(x0, H + 2.6f, 2.5f), v(-sw / 2f - 2f, H + 2.6f, 2.5f), NEON_PINK, 3); neonRun(b, v(sw / 2f + 2f, H + 2.6f, 2.5f), v(x1, H + 2.6f, 2.5f), NEON_PINK, 3)
        neon(b, v(x0, 126f, .7f), v(-mhw - 1f, 126f, .7f), NEON_CYAN); neon(b, v(mhw + 1f, 126f, .7f), v(x1 - 30f, 126f, .7f), NEON_CYAN)
        // the pylon at the right corner (over the car park's way in): a tower with the name down it in neon, a pyramid cap
        val px0 = x1 - 30f; val px1 = x1 - 2f; val pz0 = -6f; val pz1 = 18f; val ph = 300f
        val pylonFace = PixImg(tx(px1 - px0), tx(ph - 24f)).also { im ->
            im.fill(TEAL.b); for (y in 0 until im.h) { im[0, y] = TEAL.o; im[1, y] = TEAL.l; im[im.w - 1, y] = TEAL.o; im[im.w - 2, y] = TEAL.d }
            for (y in 0 until im.h step 12) im.hline(2, im.w - 3, y, TEAL.d)
            stacked(im, info.signName, 3, tx(40f), im.w - 6, im.h - tx(50f), NEON_PINK, c(0x8A1A5A), c(0xFFD0F0))
            for (i in im.px.indices) if (im.px[i] == NEON_PINK || im.px[i] == c(0xFFD0F0)) im.mask[i] = (255 shl 24) or (255 shl 16) or 255
            im.glow(0, 0, 0, 0, 0)
        }
        b.sign.box(px0, 24f, pz0, px1, ph, pz1) { s, w, h -> when (s) { MeshBuilder.Side.FRONT -> pylonFace; MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> null; else -> Paint.panel(w, h, TEAL, 7 + s.ordinal).also { im -> for (y in 0 until h step 12) im.hline(1, w - 2, y, TEAL.d) } } }
        b.det.box(px0, 0f, pz0, px1, 24f, pz1) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, MAGENTA, 3 + s.ordinal) }
        val pc = (px0 + px1) / 2f; val pzc = (pz0 + pz1) / 2f
        b.det.hexa(listOf(v(px0, ph, pz0), v(px1, ph, pz0), v(px1, ph, pz1), v(px0, ph, pz1)), listOf(v(pc - .5f, ph + 20f, pzc - .5f), v(pc + .5f, ph + 20f, pzc - .5f), v(pc + .5f, ph + 20f, pzc + .5f), v(pc - .5f, ph + 20f, pzc + .5f)),
            Paint.panel(tx(28f), tx(20f), MAGENTA, 1), Paint.panel(tx(28f), tx(20f), MAGENTA, 2), Paint.panel(tx(24f), tx(20f), MAGENTA, 3), Paint.panel(tx(24f), tx(20f), MAGENTA, 4), null)
        // its edges in cyan neon, lighting up the tower a step at a time
        for (sx in listOf(px0 - .5f, px1 + .5f)) neonRun(b, v(sx, 26f, pz1 + .5f), v(sx, ph - 2f, pz1 + .5f), NEON_CYAN, 6)
        // poster cases: three left of the doors, two right (the pylon), one down each side
        val fl = films(info)
        var i = 0
        for (x in caseRow(-x0 - 18f, ex1 + 16f, 30f)) { posterCase(b, fl[i % 8], -x, 30f, 0f, MeshBuilder.Side.FRONT, TEAL, header(info, i), pw = 22f, ph = 33f); i++ }
        for (x in caseRow(px0 - 18f, ex1 + 16f, 30f)) { posterCase(b, fl[i % 8], x, 30f, 0f, MeshBuilder.Side.FRONT, TEAL, header(info, i), pw = 22f, ph = 33f); i++ }
        posterCase(b, fl[i % 8], x0, 30f, -26f, MeshBuilder.Side.LEFT, TEAL, header(info, i), pw = 22f, ph = 33f); i++
        posterCase(b, fl[i % 8], x1, 30f, -40f, MeshBuilder.Side.RIGHT, TEAL, header(info, i), pw = 22f, ph = 33f)
        soffitLights(b, -mhw, mhw, my0, mf * .55f, warm = true, n = 2)
        b.lights += CinemaLight(px0 - 10f, 180f, 50f, 1f, .45f, .85f, 30000f, 220f)
        return b
    }

    /** 2000s-2020s: the multiplex: a glass atrium, a wide lit sign, a digital marquee and a poster wall; stepped halls. */
    private fun multiplex(info: CinemaInfo, p: Plot, seed: Int): B {
        val b = B(CinemaEra.MULTIPLEX)
        val W = p.w; val H = 200f; val LD = 80f
        val x0 = -W / 2f; val x1 = W / 2f
        val screens = if (info.screens > 0) info.screens else 12
        val clad = claddingTile(PANEL)
        val render = renderTile(Ramp(c(0x3A3A48), c(0x8A8A98), c(0xA2A2AE), c(0xB4B4C0), c(0xCACAD4)), 41)
        // the auditoria: a tall block of stadium halls, a lower block beside it, both over the lobby
        val split = x0 + W * .6f
        auditorium(b, x0 + 4f, split, -LD, p.back, 330f, clad, PANEL, seed)
        auditorium(b, split + 4f, x1 - 4f, -LD, p.back + 60f, 270f, render, PANEL, seed + 7)
        // exit stair towers up the halls' sides: a slit of lit stair windows, a door at the foot
        stairTower(b, x0 + 4f, -LD - 220f, -1f, 300f, clad)
        stairTower(b, x1 - 4f, -LD - 160f, 1f, 250f, clad)
        // the street-facing flank of the low block (onto the lane): the red band carried round, lit poster boxes under it
        ledge(b.det, x1 - 4.6f, 96f, p.back + 64f, x1 - 3f, 104f, -LD, CRIMSON)
        ledge(b.det, x0 + 2.4f, 96f, p.back + 4f, x0 + 4f, 104f, -LD, CRIMSON)
        val fl = films(info, 13)
        for (k in 0 until 3) {
            val z = -LD - 60f - k * 70f
            posterCase(b, fl[10 + k], x1 - 4f, 34f, z, MeshBuilder.Side.RIGHT, Ramp(c(0x0A0A12), c(0x1A1A24), c(0x2A2A34), c(0x3A3A44), c(0x5A5A64)), header(info, 10 + k), pw = 26f, ph = 39f)
        }
        b.tile(clad).box(x0, 0f, -LD, x1, H, 0f, setOf(MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT, MeshBuilder.Side.BACK, MeshBuilder.Side.FRONT))
        b.tile(ROOF).box(x0, 0f, -LD, x1, H - .5f, 0f, setOf(MeshBuilder.Side.TOP))
        // the wings' front: a red band and a dark plinth (trims)
        val ax0 = -112f; val ax1 = 112f
        ledge(b.det, x0, 96f, -1f, x1, 104f, .8f, CRIMSON)
        ledge(b.det, x0, 0f, -1f, x1, 4f, .6f, Ramp(c(0x1A1E28), c(0x2A2E3A), c(0x3A3E4A), c(0x4A4E5A), c(0x5A5E6A)))
        coping(b, x0, x1, -LD, 0f, H, PANEL, 2f, 3f)
        // the atrium: a box of glass standing out of the front, mullions in a grid, the warm lobby glowing inside
        val az = 18f; val ah = 220f
        val gw = tx(ax1 - ax0); val gh = tx(ah)
        val glass = PixImg(gw, gh).also { im ->
            lobbyView(im, 0, gh / 2, gw - 1, gh - 1, CinemaEra.MULTIPLEX, seed)
            lobbyView(im, 0, 0, gw - 1, gh / 2 - 1, CinemaEra.MULTIPLEX, seed + 1)
            im.rect(0, gh / 2 - 2, gw - 1, gh / 2 + 1, PANEL.d); for (x in 0 until gw step 4) im.vline(x, gh / 2 - 6, gh / 2 - 3, Paint.STEEL.l)
            Pix.line(im, gw / 5, gh - 4, gw / 2, gh / 2 + 2, Paint.STEEL.h); Pix.line(im, gw / 5 + 1, gh - 4, gw / 2 + 1, gh / 2 + 2, Paint.STEEL.d)
            for (x in 0 until gw step tx(28f)) { im.vline(x, 0, gh - 1, c(0x2A2E3A)); im.vline(x + 1, 0, gh - 1, PANEL.l) }
            for (y in 0 until gh step tx(28f)) im.hline(0, gw - 1, y, c(0x2A2E3A))
            im.hline(0, gw - 1, gh - 1, c(0x2A2E3A)); im.vline(gw - 1, 0, gh - 1, c(0x2A2E3A))
            for (k in 0 until 6) { val x = gw / 2 - 3 * tx(14f) + k * tx(14f); im.vline(x, gh - tx(40f), gh - 1, Paint.STEEL.b); im.rect(x + 5, gh - tx(20f), x + 6, gh - tx(16f), Paint.STEEL.h) }
            for (i in im.px.indices) if (im.px[i] == c(0x2A2E3A)) im.mask[i] = 0
        }
        val gside = PixImg(tx(az), gh).also { im -> im.fill(GLASS.b); for (y in 0 until gh step tx(28f)) im.hline(0, im.w - 1, y, c(0x2A2E3A)); for (y in 0 until gh) for (x in 0 until im.w) if ((x + y) % 7 == 0) im[x, y] = GLASS.l; lamp(im, 0, 0, im.w - 1, gh - 1, 150) }
        b.lit.box(ax0, 0f, -1f, ax1, ah, az) { s, w, h ->
            when (s) { MeshBuilder.Side.FRONT -> glass; MeshBuilder.Side.LEFT, MeshBuilder.Side.RIGHT -> gside; MeshBuilder.Side.TOP -> topOf(w, h, PANEL); else -> null }
        }
        b.tile(FLOOR).box(ax0, -1f, az, ax1, .12f, az + 6f, setOf(MeshBuilder.Side.TOP))
        // the entrance canopy: a thin slab with downlights, LED dots chasing round its edge
        val cy = 62f; val cz = az + 34f; val chw = 96f
        b.lit.box(-chw, cy, az, chw, cy + 5f, cz) { s, w, h -> when (s) { MeshBuilder.Side.BOTTOM -> downlights(w, h, PANEL); MeshBuilder.Side.TOP -> marqueeTop(w, h, PANEL); MeshBuilder.Side.BACK -> null; else -> Paint.panel(w, h, PANEL, 8 + s.ordinal) } }
        for (sx in listOf(-1f, 1f)) b.det.beam(v(sx * (chw - 6f), 0f, cz - 4f), v(sx * (chw - 6f), cy, cz - 4f), 2.4f, Paint.swatch(Paint.STEEL.d))
        b.bulbs(v(-chw + 2f, cy + 2.5f, cz + .3f), v(chw - 2f, cy + 2.5f, cz + .3f), 4.6f, MeshBuilder.Side.FRONT, size = 2.2f)
        for (sx in listOf(-1f, 1f)) b.bulbs(v(sx * (chw + .3f), cy + 2.5f, cz - 2f), v(sx * (chw + .3f), cy + 2.5f, az + 2f), 4.6f, if (sx < 0) MeshBuilder.Side.LEFT else MeshBuilder.Side.RIGHT, size = 2.2f)
        // the wide lit sign over the atrium: the name big, CINEMAS and the screens under it
        val sw = 320f; val sy0 = ah; val sy1 = ah + 48f
        val nameImg = PixImg(tx(sw), tx(sy1 - sy0)).also { im ->
            im.fill(c(0x1A1E2A)); im.border(c(0x0A0A12))
            im.rect(2, im.h - 13, im.w - 3, im.h - 3, CRIMSON.b)
            val name = info.signName
            var sc = 4
            while (sc > 1 && (BigFont.width(name, sc, sc) > im.w - 12 || BigFont.H * sc > im.h - 20)) sc--
            BigFont.outlined(im, name, (im.w - BigFont.width(name, sc, sc)) / 2, 4, sc, sc, c(0x0A0A12), null) { if (it < sc) c(0xFFFFFF) else c(0xF0F4FF) }
            val sub = "CINEMAS $screens"
            BigFont.draw(im, sub, (im.w - BigFont.width(sub)) / 2, im.h - 11, c(0xFFFFFF))
            glowAll(im, 255, c(0x1A1E2A))
            for (x in 0 until im.w) { im.mask[x] = 0; im.mask[(im.h - 1) * im.w + x] = 0 }
        }
        b.sign.box(-sw / 2f, sy0, -2f, sw / 2f, sy1, az + 2f) { s, w, h -> when (s) { MeshBuilder.Side.FRONT -> nameImg; MeshBuilder.Side.BOTTOM -> null; MeshBuilder.Side.TOP -> topOf(w, h, PANEL); else -> Paint.panel(w, h, Ramp(c(0x0A0A12), c(0x14141E), c(0x1A1E2A), c(0x2A2E3A), c(0x3A3E4A)), 3 + s.ordinal) } }
        // the digital marquee on the left wing: its header always lit, each title a line that lights in turn (paging)
        val dw = 104f; val dh = 66f; val dx = (x0 + ax0) / 2f; val dy0 = 112f
        val led = PixImg(tx(dw), tx(dh)).also { im ->
            im.fill(c(0x08080C))
            for (y in 0 until im.h step 2) for (x in 0 until im.w step 2) im[x, y] = c(0x14141A)
            im.border(c(0x2A2A30))
            board(im, "NOW SHOWING", 3, im.w - 4, 3, c(0xF6B03A))
            glowAll(im, 255, c(0x08080C))
            for (i in im.px.indices) if (im.px[i] == c(0x14141A) || im.px[i] == c(0x2A2A30)) im.mask[i] = 0
        }
        b.sign.box(dx - dw / 2f - 3f, dy0 - 3f, 0f, dx + dw / 2f + 3f, dy0 + dh + 3f, 4f) { s, w, h -> when (s) { MeshBuilder.Side.FRONT -> PixImg(w, h).also { im -> im.fill(c(0x2A2A30)); im.border(c(0x0A0A12)) }; MeshBuilder.Side.BOTTOM -> null; else -> Paint.panel(w, h, Paint.IRON, 5 + s.ordinal) } }
        face(b.sign, led, dx, dy0 + dh / 2f, 4.05f, dw, dh, MeshBuilder.Side.FRONT)
        val titles = bill(info, 6).filter { it.isNotEmpty() }.take(6)
        val cols = listOf(c(0xFFFFFF), c(0x5AE8FF), c(0x8AF65A), c(0xFFFFFF), c(0xF65AB8), c(0xF6E05A))
        val lineH = (dh - 14f) / 6f
        for ((k, t) in titles.withIndex()) {
            // a row a title; the rows light up in turn by night (the board pages through the bill)
            val li = PixImg(tx(dw - 4f), tx(lineH)).also { im ->
                im.fill(c(0x08080C)); for (y in 0 until im.h step 2) for (x in 0 until im.w step 2) im[x, y] = c(0x14141A)
                board(im, t, 1, im.w - 2, (im.h - 7) / 2, cols[k % cols.size])
                glowAll(im, 255, c(0x08080C))
                for (i in im.px.indices) if (im.px[i] == c(0x14141A)) im.mask[i] = 0
            }
            face(b.page[k % 3], li, dx, dy0 + dh - 14f - lineH * (k + .5f), 4.1f, dw - 4f, lineH, MeshBuilder.Side.FRONT)
        }
        // the feature's light box on the right wing: framed, standing proud of the wall
        info.nowShowing.firstOrNull()?.let { f ->
            val pcx = (x1 + ax1) / 2f; val pw = 52f; val ph = 78f; val py = 112f + ph / 2f
            b.det.box(pcx - pw / 2f - 4f, py - ph / 2f - 4f, 0f, pcx + pw / 2f + 4f, py + ph / 2f + 4f, 4f) { s, w, h -> if (s == MeshBuilder.Side.BOTTOM) null else Paint.panel(w, h, Paint.IRON, 3 + s.ordinal) }
            val pi = CinemaPosters.paint(f); glowAll(pi, 130)
            face(b.sign, pi, pcx, py, 4.1f, pw, ph, MeshBuilder.Side.FRONT)
        }
        // the poster wall: light boxes each side of the atrium, as many as the wings hold
        val xs = generateSequence(126f) { it + 30f }.takeWhile { it <= x1 - 16f }.toList()
        var i = 0
        for (x in xs) for (sx in listOf(-1f, 1f)) { posterCase(b, fl[i % 10], sx * x, 34f, 0f, MeshBuilder.Side.FRONT, Ramp(c(0x0A0A12), c(0x1A1A24), c(0x2A2A34), c(0x3A3A44), c(0x5A5A64)), header(info, i), pw = 26f, ph = 39f); i++ }
        // the drum at the left corner: red panels, CINEMA down a lit band, a ring of light at its top
        val dr = 18f; val dcx = x0 - 12f; val dcz = -24f; val dtop = 250f
        val (sw2, sh2) = Paint.stripSize(dr, dtop)
        val drum = Paint.strip(sw2, sh2, CRIMSON, seed).also { im ->
            for (x in 0 until sw2 step 6) im.vline(x, 0, sh2 - 1, CRIMSON.d)
            val bx0 = sw2 / 2 - 8; val bx1 = sw2 / 2 + 7
            im.rect(bx0, 6, bx1, sh2 - 30, c(0xF6F0E8))
            stacked(im, "CINEMA", bx0, 8, bx1 - bx0 + 1, sh2 - 40, CRIMSON.b, CRIMSON.o, CRIMSON.l)
            glowAll(im, 255)
            for (k in im.px.indices) { val x = k % sw2; if (x < bx0 || x > bx1) im.mask[k] = 0 }
            im.rect(0, 1, sw2 - 1, 3, c(0xF6F0E8)); for (y in 1..3) for (x in 0 until sw2) im.mask[y * sw2 + x] = (255 shl 24) or (255 shl 16) or 255
        }
        b.sign.upright(dcx, dcz, 0f, dtop, dr, dr, 16, drum, cap = Paint.cap(dr, PANEL))
        soffitLights(b, -chw, chw, cy, (az + cz) / 2f, warm = true, n = 2)
        b.lights += CinemaLight(0f, ah + 24f, 90f, .9f, .92f, 1f, 30000f, 240f)
        b.lights += CinemaLight(dx, dy0 + 20f, 50f, 1f, .7f, .4f, 16000f, 150f)
        return b
    }

    /** An exit stair tower against a hall's side at [x] (out to [out]: -1 left, +1 right), centred at [z], [h] tall. */
    private fun stairTower(b: B, x: Float, z: Float, out: Float, h: Float, wall: PixImg) {
        val d = 28f; val w = 44f
        val xa = if (out < 0f) x - d else x; val xb = if (out < 0f) x else x + d
        b.tile(wall).box(xa, 0f, z - w / 2f, xb, h, z + w / 2f, setOf(MeshBuilder.Side.FRONT, MeshBuilder.Side.BACK, if (out < 0f) MeshBuilder.Side.LEFT else MeshBuilder.Side.RIGHT))
        b.tile(ROOF).box(xa, 0f, z - w / 2f, xb, h - .5f, z + w / 2f, setOf(MeshBuilder.Side.TOP))
        coping(b, xa, xb, z - w / 2f, z + w / 2f, h, PANEL, 1.4f, 2f)
        val slit = PixImg(tx(6f), tx(h - 70f)).also { im -> im.fill(c(0x1A2A3A)); for (y in 0 until im.h step 10) im.hline(0, im.w - 1, y, PANEL.d); lamp(im, 0, 0, im.w - 1, im.h - 1, 170) }
        face(b.lit, slit, if (out < 0f) xa - .2f else xb + .2f, 40f + (h - 70f) / 2f, z - 12f, 6f, h - 70f, if (out < 0f) MeshBuilder.Side.LEFT else MeshBuilder.Side.RIGHT)
        exitDoor(b, (xa + xb) / 2f, z - w / 2f - .25f, MeshBuilder.Side.BACK, PANEL)
    }

    /** Every era's sample theater (the preview's and the tests'): a bill of [year]. */
    fun sample(year: Int, name: String = "LYRIC", seed: Int = 7): CinemaInfo = CinemaBill.sample(name, year, seed)
}
