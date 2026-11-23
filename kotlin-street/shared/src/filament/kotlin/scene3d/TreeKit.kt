package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The town's trees and bushes in 3D, made by code in the yard kit's way (pixel pictures painted texel by texel, sampled
 * nearest, on simple real geometry, lit and shadowed by the sun): several species, each grown from a seed so no two of a
 * kind stand the same.
 *
 *  - broadleaves: oak (a wide crown of many lumpy lobes on spreading limbs), maple (round and dense), linden (a tall
 *    ovoid, dark), copper beech (the oak's build in copper), birch (white, slender, leaning, small airy lobes), fruit
 *    trees (short and spreading, fruit or blossom in the leaves), poplar (a narrow column);
 *  - conifers: spruce (tiers of star-shaped, drooping needle skirts to a spike) and pine (a tall bare trunk, flat needle
 *    clumps on branches at the top);
 *  - bushes: round, tall (a column of lobes) and topiary (two balls).
 *
 * Every tree has its trunk's flare and roots, its limbs reaching into the crown and twigs to the outer lobes. Each comes
 * in two levels of detail ([build] `detail`): the full one near by (about 500-1200 triangles a tree) and a simple one
 * further off: the same tree, every lobe, limb and the trunk where they are near by (one sequence of seeded choices
 * for both, so the two are one silhouette and the switch between them is no pop, never a crown floating off its
 * trunk), only cut coarser (fewer facets) and without the roots, twigs and fruit (about 160-400 triangles; until
 * 2026-11-23 it was a different, sparser tree of 80-170, whose crowns floated off their trunks: the owner's "trees render
 * halfway"), the far cards beyond that (TownGround). All pictures of all species live in one shared atlas ([atlas]), so any number of trees merge into one mesh, one draw ([merge]).
 *
 * The leaves keep their summer colours (the Filament town has no seasons yet).
 */
internal object TreeKit {
    const val OAK = 0; const val MAPLE = 1; const val LINDEN = 2; const val COPPER = 3; const val BIRCH = 4; const val FRUIT = 5
    const val PINE = 6; const val SPRUCE = 7; const val POPLAR = 8
    const val BUSH_ROUND = 9; const val BUSH_TALL = 10; const val BUSH_TOPIARY = 11

    /** A tree or bush to grow: its [species], a [variant] (its shape's seed; a fruit tree's fruit, a bush's flowers) and its [height] (street units). */
    class Spec(val species: Int, val variant: Int, val height: Float) {
        /** Its cache key: sizes in steps of 4 units (a forest of a few dozen shapes, not thousands). */
        val key: Int get() = (species * 64 + (variant and 63)) * 512 + (height / 4f).roundToInt().coerceIn(0, 511)
    }

    // ------------------------------------------------------------------------------------------------ palettes
    private fun c(rgb: Long) = Paint.c(rgb)
    private val MAPLE_LEAF = Ramp(c(0x1C3A30), c(0x2A6A3A), c(0x3C8A3A), c(0x5AA83C), c(0x86C24A))
    private val POPLAR_LEAF = Ramp(c(0x16342C), c(0x22603E), c(0x2E7C44), c(0x46984A), c(0x6CB458))
    private val PINE_NEEDLE = Ramp(c(0x14302A), c(0x1E4E3A), c(0x2C6844), c(0x3E8250), c(0x5A9C5E))
    private val BUSH_LEAF = Ramp(c(0x163A30), c(0x1E5A3C), c(0x2A7A44), c(0x3E9448), c(0x5EAE56))

    /** Each species' leaves (lit, middle, middle, shade) and, for the dotted ones, its dots. */
    private fun leafRamp(species: Int, variant: Int): Ramp = when (species) {
        OAK, FRUIT -> Paint.LEAF
        MAPLE -> MAPLE_LEAF
        LINDEN -> YP.LEAF_DARK
        COPPER -> YP.LEAF_COPPER
        BIRCH -> YP.LEAF_YELLOW
        PINE -> PINE_NEEDLE
        SPRUCE -> YP.LEAF_BLUE
        POPLAR -> POPLAR_LEAF
        else -> if (variant % 3 == 2) Paint.LEAF else BUSH_LEAF
    }
    private fun dotsOf(species: Int, variant: Int): IntArray? = when (species) {
        FRUIT -> if (variant % 8 >= 6) YP.BLOOMS[4] else YP.FRUIT[variant % YP.FRUIT.size]
        BUSH_ROUND, BUSH_TALL, BUSH_TOPIARY -> if (variant % 4 == 1) YP.BLOOMS[(variant / 4) % 2 * 3] else null
        else -> null
    }
    private fun pics(species: Int, variant: Int): List<PixImg> {
        val r = leafRamp(species, variant)
        val dots = dotsOf(species, variant)
        val seed = 600 + species * 17 + (if (dots != null) (dots[0] and 0xFF) else 0)
        val rate = if (dots != null) .05f else .02f
        return if (species == PINE || species == SPRUCE) listOf(YardPics.needles(r, 1, seed), YardPics.needles(r, 0, seed + 1), YardPics.needles(r, 0, seed + 2), YardPics.leaves(r, -1, seed + 3, null, .02f, fine = true))
        else listOf(YardPics.leaves(r, 1, seed, dots, rate), YardPics.leaves(r, 0, seed + 1, dots, rate), YardPics.leaves(r, 0, seed + 2, dots, rate), YardPics.leaves(r, -1, seed + 3, dots, rate * .5f))
    }

    /** The warm brown of the pack's tree trunks (the sprites'), furrowed; a birch's white with black marks. */
    private val TRUNK = Ramp(c(0x3A2418), c(0x5E3A24), c(0x7A4E30), c(0x946640), c(0xB08050))
    fun barkPic(birch: Boolean): PixImg = if (birch) YardPics.bark(true) else YardPics.pic("treebark", 16, 32) { im ->
        im.fill(TRUNK.b)
        val rnd = Paint.Rnd(905)
        // furrows down the bark, broken; lit ridges between
        for (x in 0 until 16 step 4) for (y in 0 until 32) {
            if (rnd.next() < .8f) im[x + (if (y % 9 < 4) 0 else 1), y] = TRUNK.d
            if (rnd.next() < .35f) im[x + 2, y] = TRUNK.l
        }
        repeat(10) { im[rnd.int(16), rnd.int(32)] = TRUNK.o }
        repeat(8) { im[rnd.int(16), rnd.int(32)] = TRUNK.h }
    }

    // ------------------------------------------------------------------------------------------------ the shared atlas
    /** Every picture any tree or bush wears, packed once ([MeshBuilder.build] with it): trees merge without repacking. */
    val atlas: MeshBuilder.FixedAtlas by lazy {
        val all = LinkedHashSet<PixImg>()
        for (sp in 0..BUSH_TOPIARY) for (v in 0 until 16) all += pics(sp, v)
        all += barkPic(false); all += barkPic(true)
        for (f in YP.FRUIT + listOf(YP.BLOOMS[4])) all += YardPics.swatch(f[0])
        MeshBuilder.FixedAtlas.pack(all.toList())
    }

    // ------------------------------------------------------------------------------------------------ the meshes
    /** The meshes made lately (a few dozen: a cell's trees repeat their shapes; held bytes bounded for the phone's heap). */
    private val cache = object : LinkedHashMap<Long, PropMesh>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PropMesh>?) = size > 96
    }

    /** [spec]'s mesh at its own foot (y up, +z its front), [detail] or simple, wearing the shared [atlas]. */
    fun mesh(spec: Spec, detail: Boolean): PropMesh {
        val k = spec.key.toLong() * 2 + (if (detail) 1 else 0)
        synchronized(cache) { cache[k]?.let { return it } }
        val m = MeshBuilder()
        build(m, Spec(spec.species, spec.variant, (spec.key % 512) * 4f), detail)
        val mesh = m.build(atlas)
        synchronized(cache) { cache[k] = mesh }
        return mesh
    }

    /** Lets the meshes go (after a town's build). */
    fun trim() = synchronized(cache) { cache.clear() }

    /**
     * Many trees in the world as one mesh (no atlas repacked: they all wear [atlas]): each ([mesh], placed by x, z, along
     * x, z, out x, z as [PropLayer] places things). Null for none.
     */
    fun merge(list: List<Pair<PropMesh, FloatArray>>): PropMesh? {
        if (list.isEmpty()) return null
        val nv = list.sumOf { it.first.vertexCount }; val ni = list.sumOf { it.first.idx.size }
        val pos = FloatArray(nv * 3); val uv = FloatArray(nv * 2); val nr = FloatArray(nv * 3); val tn = FloatArray(nv * 3); val idx = IntArray(ni)
        var vi = 0; var ii = 0
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for ((m, a) in list) {
            for (i in 0 until m.vertexCount) {
                val lx = m.pos[i * 3]; val ly = m.pos[i * 3 + 1]; val lz = m.pos[i * 3 + 2]
                val wx = a[0] + a[2] * lx + a[4] * lz; val wz = a[1] + a[3] * lx + a[5] * lz
                val o = (vi + i) * 3
                pos[o] = wx; pos[o + 1] = ly; pos[o + 2] = wz
                nr[o] = a[2] * m.nrm[i * 3] + a[4] * m.nrm[i * 3 + 2]; nr[o + 1] = m.nrm[i * 3 + 1]; nr[o + 2] = a[3] * m.nrm[i * 3] + a[5] * m.nrm[i * 3 + 2]
                tn[o] = a[2] * m.tan[i * 3] + a[4] * m.tan[i * 3 + 2]; tn[o + 1] = m.tan[i * 3 + 1]; tn[o + 2] = a[3] * m.tan[i * 3] + a[5] * m.tan[i * 3 + 2]
                uv[(vi + i) * 2] = m.uv[i * 2]; uv[(vi + i) * 2 + 1] = m.uv[i * 2 + 1]
                b[0] = min(b[0], wx); b[1] = min(b[1], ly); b[2] = min(b[2], wz); b[3] = max(b[3], wx); b[4] = max(b[4], ly); b[5] = max(b[5], wz)
            }
            for (k in m.idx) idx[ii++] = vi + k
            vi += m.vertexCount
        }
        val f = list[0].first
        return PropMesh(pos, uv, nr, tn, idx, f.texW, f.texH, f.tex, f.mask, b)
    }

    // ------------------------------------------------------------------------------------------------ the species for a place
    /**
     * The tree a forest or street sprite of the pack stands for near by: [sprite] its index among TownGround's tree
     * sprites (the small round ones, the full round ones, the tall ones, the big oaks), [look] the place's own 0..1,
     * [height] the sprite's height in the world. Round sprites become broadleaves of several kinds, tall ones conifers
     * and poplars, the big ones oaks; heights vary by a tenth either way.
     */
    fun forSprite(sprite: Int, look: Float, height: Float): Spec {
        val h = (look * 104729f).toInt() and 0x7fffffff
        val pick = when (sprite) {
            0, 1 -> intArrayOf(MAPLE, LINDEN, BIRCH, OAK, MAPLE, BIRCH, LINDEN, OAK, MAPLE, COPPER, BIRCH, MAPLE)
            2, 3 -> intArrayOf(MAPLE, LINDEN, MAPLE, LINDEN, OAK)
            4, 5 -> intArrayOf(SPRUCE, SPRUCE, PINE, POPLAR, SPRUCE, PINE)
            else -> intArrayOf(OAK, OAK, MAPLE, OAK, LINDEN)
        }
        val sp = pick[h % pick.size]
        val scale = .9f + ((h / 7) % 21) / 100f
        // (a pine stands taller than the sprite's cone; a birch a little)
        val tall = when (sp) { PINE -> 1.15f; BIRCH -> 1.05f; else -> 1f }
        return Spec(sp, (h / 131) % 16, height * scale * tall)
    }

    /** A bush sprite of the pack ([sprite] its index among TownGround's bush sprites) near by, [height] tall. */
    fun forBush(sprite: Int, look: Float, height: Float): Spec {
        val h = (look * 7919f).toInt() and 0x7fffffff
        val sp = when (sprite) { 5, 6, 8, 9 -> BUSH_TALL; 7 -> BUSH_TOPIARY; else -> BUSH_ROUND }
        // (the pack's third bush has flowers)
        val v = if (sprite == 2) 1 + 4 * (h % 2) else (h % 16).let { if (it % 4 == 1) it + 1 else it }
        return Spec(sp, v, height)
    }

    /** A yard's tree item ([kind] tree or fruitTree, [variant] its look, its crown [s] across half its rect). */
    fun forYard(kind: String, variant: Int, s: Float): Spec = if (kind == "fruitTree") Spec(FRUIT, variant, s * 2.3f)
    else when (variant) {
        5 -> Spec(SPRUCE, variant, s * 3.6f)
        6 -> Spec(PINE, variant, s * 3.6f)
        7 -> Spec(BIRCH, variant, s * 3.1f)
        8 -> Spec(POPLAR, variant, s * 3.9f)
        1 -> Spec(LINDEN, variant, s * 2.8f)
        2, 9 -> Spec(MAPLE, variant, s * 2.6f)
        4 -> Spec(COPPER, variant, s * 2.6f)
        else -> Spec(OAK, variant, s * 2.6f)
    }

    // ------------------------------------------------------------------------------------------------ growing one
    /** Grows [spec] into [m] at the origin. */
    fun build(m: MeshBuilder, spec: Spec, detail: Boolean) {
        val g = Grow(m, spec, detail)
        when (spec.species) {
            OAK, COPPER -> g.oak()
            MAPLE -> g.maple()
            LINDEN -> g.linden()
            BIRCH -> g.birch()
            FRUIT -> g.fruit()
            PINE -> g.pine()
            SPRUCE -> g.spruce()
            POPLAR -> g.poplar()
            BUSH_TALL -> g.bushTall()
            BUSH_TOPIARY -> g.topiary()
            else -> g.bushRound()
        }
    }

    private class Grow(val m: MeshBuilder, val spec: Spec, val detail: Boolean) {
        val H = spec.height.coerceAtLeast(6f)
        val rnd = Paint.Rnd(spec.species * 7919 + spec.variant * 131 + 17)
        val pics = pics(spec.species, spec.variant)
        val bark = barkPic(spec.species == BIRCH)
        fun r(a: Float, b: Float) = a + (b - a) * rnd.next()

        // --- the wood
        /** The trunk: a tapered prism to [top], its flare at the foot and (near by) roots running out into the ground. */
        fun trunk(top: FloatArray, r0: Float, r1: Float, roots: Int = 4) {
            limb(floatArrayOf(0f, 0f, 0f), top, r0, r1, 7)
            if (r0 < .8f) return
            // the flare: a short wide frustum
            m.upright(0f, 0f, 0f, r0 * 1.4f, r0 * 1.55f, r0 * .98f, if (detail) 7 else 5, bark)
            // (the roots' choices are made at both levels, so what grows after them is the same tree)
            val a0 = r(0f, 6.28f)
            for (k in 0 until roots) {
                val a = a0 + k * 6.28f / roots + r(-.3f, .3f)
                val out = r0 * r(2.1f, 2.8f)
                if (detail) limb(floatArrayOf(cos(a) * r0 * .3f, r0 * 1.1f, sin(a) * r0 * .3f), floatArrayOf(cos(a) * out, 0f, sin(a) * out), r0 * .5f, r0 * .12f, 4)
            }
        }

        /** A tapered limb from [a] to [b]. */
        fun limb(a: FloatArray, b: FloatArray, r0: Float, r1: Float, sides0: Int = 5) {
            val sides = if (detail) sides0 else max(3, sides0 - 2)
            val d = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
            val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            if (l < 1e-3f) return
            val e2 = if (abs(d[1]) / l < .9f) floatArrayOf(0f, 1f, 0f).let { up -> orth(d, up) } else orth(d, floatArrayOf(0f, 0f, 1f))
            m.prism(a, d, e2, r0, r1, l, sides, bark)
        }
        private fun orth(d: FloatArray, ref: FloatArray): FloatArray {
            // ref less its part along d
            val l2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
            val k = (ref[0] * d[0] + ref[1] * d[1] + ref[2] * d[2]) / l2
            return floatArrayOf(ref[0] - d[0] * k, ref[1] - d[1] * k, ref[2] - d[2] * k)
        }

        // --- the leaves
        /**
         * A lump of foliage round ([cx], [cy], [cz]): a faceted ellipsoid whose surface is pushed in and out by a seeded
         * noise (no two the same, no smooth ball), its underside flatter; each band wears the picture for its height.
         */
        fun lobe(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, n0: Int = 8, rings0: Int = 5, bumpy: Float = .16f) {
            val n = if (detail) n0 else max(5, n0 - 3)
            val rings = if (detail) rings0 else max(3, rings0 - 2)
            val seed = rnd.int(100000)
            val tw = r(0f, 6.28f)
            val pts = Array(rings + 1) { i -> Array(n) { j ->
                val phi = PI.toFloat() * i / rings
                val th = 2f * PI.toFloat() * j / n + tw + (if (i % 2 == 1) PI.toFloat() / n else 0f)
                val bump = if (i == 0 || i == rings) 1f else 1f + bumpy * (hash(seed, i, j) * 2f - 1f)
                val y = cos(phi).let { if (it < 0f) it * .78f else it }
                floatArrayOf(cx + rx * sin(phi) * sin(th) * bump, cy + ry * y * (if (i == 0 || i == rings) 1f else (1f + bumpy * .5f * (hash(seed + 7, i, j) - .5f))), cz + rz * sin(phi) * cos(th) * bump)
            } }
            val c = floatArrayOf(cx, cy, cz)
            for (i in 0 until rings) {
                val img = pics[(i * pics.size / rings).coerceIn(0, pics.size - 1)]
                for (j in 0 until n) {
                    val j1 = (j + 1) % n
                    val face = when (i) {
                        0 -> listOf(pts[0][0], pts[1][j1], pts[1][j])
                        rings - 1 -> listOf(pts[i][j], pts[i][j1], pts[rings][0])
                        else -> listOf(pts[i][j], pts[i][j1], pts[i + 1][j1], pts[i + 1][j])
                    }
                    if (face.size == 4) { tface(listOf(face[0], face[1], face[2]), img, c); tface(listOf(face[0], face[2], face[3]), img, c) }
                    else tface(face, img, c)
                }
            }
        }

        /**
         * A conifer's tier: a skirt of needles from a star-shaped rim at [y0] (its points [rOut] out, drooping by
         * [droop], the notches between [notch] of that) up to the trunk [h] higher, with a shaded underside.
         */
        fun skirt(y0: Float, h: Float, rOut: Float, droop: Float, notch: Float = .68f) {
            val n = if (detail) 11 else 5
            val tw = r(0f, 6.28f)
            // (its points' lengths from a seed of its own: the same draws from the tree's sequence at both levels)
            val sd = rnd.int(100000)
            val top = floatArrayOf(r(-.04f, .04f) * rOut, y0 + h, r(-.04f, .04f) * rOut)
            val under = floatArrayOf(0f, y0 + h * .22f, 0f)
            val rim = ArrayList<FloatArray>()
            for (k in 0 until n * 2) {
                val a = tw + k * PI.toFloat() / n
                val point = k % 2 == 0
                val rr = rOut * (if (point) .9f + .18f * hash(sd, k, 0) else notch)
                rim += floatArrayOf(cos(a) * rr, y0 - (if (point) droop else droop * .35f), sin(a) * rr)
            }
            val c = floatArrayOf(0f, y0 + h * .3f, 0f)
            for (k in rim.indices) {
                val a = rim[k]; val b = rim[(k + 1) % rim.size]
                val img = pics[if (k % 2 == 0) 0 else 1]
                tface(listOf(a, b, top), img, c)
                tface(listOf(b, a, under), pics[3], floatArrayOf(0f, y0 + h, 0f))
            }
        }

        // --- the species
        fun oak() {
            val th = H * r(.3f, .38f)
            val tr = H * .05f
            trunk(floatArrayOf(r(-.02f, .02f) * H, th, r(-.02f, .02f) * H), tr, tr * .72f, 5)
            val cy = H * .6f
            val limbs = 5
            val a0 = r(0f, 6.28f)
            for (k in 0 until limbs) {
                val a = a0 + k * 6.28f / limbs + r(-.35f, .35f)
                val out = H * r(.2f, .3f)
                val end = floatArrayOf(cos(a) * out, cy + H * r(-.1f, .04f), sin(a) * out)
                limb(floatArrayOf(0f, th * r(.8f, .98f), 0f), end, tr * .62f, tr * .26f)
                // twigs on to the crown's outer lobes (near by only)
                for (t in 0 until 2) {
                    val b = a + r(-.6f, .6f)
                    val tip = floatArrayOf(end[0] + cos(b) * H * .1f, end[1] + H * r(.02f, .12f), end[2] + sin(b) * H * .1f)
                    if (detail) limb(end, tip, tr * .24f, tr * .1f, 4)
                }
                lobe(end[0], end[1] + H * .04f, end[2], H * r(.19f, .24f), H * r(.17f, .21f), H * r(.19f, .24f), 9, 6)
            }
            // the crown's heart and top
            lobe(0f, cy + H * .08f, 0f, H * .28f, H * .26f, H * .28f, 10, 6, .1f)
            run {
                for (k in 0 until 3) {
                    val a = r(0f, 6.28f); val rr = H * r(.05f, .16f)
                    lobe(cos(a) * rr, H * r(.8f, .9f), sin(a) * rr, H * r(.13f, .17f), H * r(.1f, .13f), H * r(.13f, .17f))
                }
                // a few low lobes hanging at the rim
                for (k in 0 until 3) {
                    val a = r(0f, 6.28f); val rr = H * r(.26f, .32f)
                    lobe(cos(a) * rr, H * r(.44f, .52f), sin(a) * rr, H * .14f, H * .12f, H * .14f, 8, 5)
                }
            }
        }

        fun maple() {
            val th = H * r(.26f, .32f)
            val tr = H * .042f
            trunk(floatArrayOf(0f, th, 0f), tr, tr * .75f)
            val cy = H * .6f; val R = H * .3f
            for (k in 0 until 4) {
                val a = k * 1.57f + r(-.3f, .3f)
                limb(floatArrayOf(0f, th * .9f, 0f), floatArrayOf(cos(a) * R * .55f, cy, sin(a) * R * .55f), tr * .55f, tr * .25f)
            }
            lobe(0f, cy, 0f, R * .82f, R * .78f, R * .82f, 10, 6, .08f)
            val n = 12
            for (k in 0 until n) {
                // lobes over the crown's surface: a ring round its middle, a ring above, one on top
                val ring = if (k < n * 7 / 12) 0 else if (k < n - 1) 1 else 2
                val a = r(0f, 6.28f)
                val (rr, y) = when (ring) { 0 -> R * .72f to cy + r(-.12f, .06f) * R; 1 -> R * .45f to cy + R * .55f; else -> 0f to cy + R * .8f }
                val s = R * r(.36f, .48f)
                lobe(cos(a) * rr, y, sin(a) * rr, s, s * .85f, s)
            }
        }

        fun linden() {
            val th = H * .3f
            val tr = H * .04f
            trunk(floatArrayOf(0f, th, 0f), tr, tr * .75f)
            val cy = H * .64f; val rx = H * .25f; val ry = H * .34f
            for (k in 0 until 3) {
                val a = k * 2.09f + r(-.3f, .3f)
                limb(floatArrayOf(0f, th * .85f, 0f), floatArrayOf(cos(a) * rx * .5f, cy - ry * .2f, sin(a) * rx * .5f), tr * .55f, tr * .22f)
            }
            lobe(0f, cy, 0f, rx * .85f, ry * .9f, rx * .85f, 10, 7, .08f)
            val n = 10
            for (k in 0 until n) {
                val t = r(-.75f, .8f)
                val a = r(0f, 6.28f)
                val w = sqrt((1f - t * t).coerceAtLeast(.05f))
                val s = H * r(.1f, .14f)
                lobe(cos(a) * rx * w * .8f, cy + ry * t * .85f, sin(a) * rx * w * .8f, s, s, s)
            }
        }

        fun birch() {
            val lean = floatArrayOf(r(-.08f, .08f) * H, H * .62f, r(-.08f, .08f) * H)
            val tr = H * .026f
            trunk(lean, tr, tr * .55f, 3)
            val n = 4
            for (k in 0 until n) {
                val t = r(.45f, .85f)
                val from = floatArrayOf(lean[0] * t, lean[1] * t, lean[2] * t)
                val a = r(0f, 6.28f)
                val to = floatArrayOf(from[0] + cos(a) * H * .14f, from[1] + H * r(.12f, .2f), from[2] + sin(a) * H * .14f)
                limb(from, to, tr * .4f, tr * .15f, 4)
                lobe(to[0], to[1] + H * .03f, to[2], H * .11f, H * .12f, H * .11f, 7, 5, .22f)
            }
            // the crown: small airy lobes up a narrow column
            val m2 = 7
            for (k in 0 until m2) {
                val t = .5f + .5f * k / m2.toFloat()
                val a = r(0f, 6.28f); val rr = H * r(.03f, .1f) * (1.2f - t)
                val s = H * r(.09f, .12f)
                lobe(lean[0] * t + cos(a) * rr, H * (.48f + .45f * k / m2.toFloat()), lean[2] * t + sin(a) * rr, s, s * 1.2f, s, 7, 5, .22f)
            }
        }

        fun fruit() {
            val th = H * .3f
            val tr = H * .05f
            trunk(floatArrayOf(H * r(-.04f, .04f), th, H * r(-.04f, .04f)), tr, tr * .7f, 3)
            val cy = H * .62f
            val n = 5
            val a0 = r(0f, 6.28f)
            for (k in 0 until n) {
                val a = a0 + k * 6.28f / n + r(-.3f, .3f)
                val end = floatArrayOf(cos(a) * H * .32f, cy + H * r(-.05f, .05f), sin(a) * H * .32f)
                limb(floatArrayOf(0f, th * .95f, 0f), end, tr * .55f, tr * .2f)
                lobe(end[0], end[1], end[2], H * .22f, H * .14f, H * .22f, 8, 5)
            }
            lobe(0f, cy + H * .1f, 0f, H * .3f, H * .18f, H * .3f, 10, 5, .1f)
            run {
                // the crown's top, lumpy
                for (k in 0 until 3) {
                    val a = r(0f, 6.28f); val rr = H * r(.08f, .2f)
                    lobe(cos(a) * rr, cy + H * r(.18f, .24f), sin(a) * rr, H * .15f, H * .1f, H * .15f, 8, 5)
                }
                val f = (dotsOf(FRUIT, spec.variant) ?: YP.FRUIT[0])[0]
                val sw = YardPics.swatch(f)
                if (spec.variant % 8 < 6) for (k in 0..2) {
                    val a = r(0f, 6.28f); val rr = H * r(.15f, .35f); val s = H * .025f
                    if (detail) m.solidBox(cos(a) * rr - s, 0f, sin(a) * rr - s, cos(a) * rr + s, s * 2f, sin(a) * rr + s, sw)
                }
            }
        }

        fun pine() {
            // a Scots pine: a tall bare trunk with a few dead stubs, flat clumps of needles at the top on its branches
            val top = floatArrayOf(H * r(-.04f, .04f), H * .8f, H * r(-.04f, .04f))
            val tr = H * .035f
            trunk(top, tr, tr * .5f, 3)
            for (k in 0 until 3) {
                val t = r(.35f, .6f); val a = r(0f, 6.28f)
                val from = floatArrayOf(top[0] * t, top[1] * t, top[2] * t)
                if (detail) limb(from, floatArrayOf(from[0] + cos(a) * H * .06f, from[1] + H * .02f, from[2] + sin(a) * H * .06f), tr * .25f, tr * .1f, 3)
            }
            val n = 8
            val a0 = r(0f, 6.28f)
            for (k in 0 until n) {
                val t = k / n.toFloat()
                val a = a0 + k * 2.4f
                val y = H * (.62f + .3f * t)
                val out = H * (.17f - .11f * t) * r(.8f, 1.1f)
                val from = floatArrayOf(top[0] * y / top[1], y - H * .06f, top[2] * y / top[1])
                val end = floatArrayOf(cos(a) * out, y, sin(a) * out)
                limb(from, end, tr * .35f, tr * .15f, 4)
                val s = H * (.13f - .04f * t)
                lobe(end[0], end[1], end[2], s, s * .75f, s, 8, 5, .24f)
            }
        }

        fun spruce() {
            val tr = H * .03f
            trunk(floatArrayOf(0f, H * .3f, 0f), tr, tr * .6f, 4)
            val tiers = 10
            val base = H * r(.1f, .14f)
            val span = H - base
            for (k in 0 until tiers) {
                val t = k / tiers.toFloat()
                val y0 = base + span * t * .92f
                val rOut = H * .25f * (1f - t * .88f) * r(.92f, 1.06f)
                val h = span * .2f * (1f - t * .3f)
                skirt(y0, h, rOut, droop = rOut * .25f, notch = .66f)
            }
            // the leader at the top
            m.prism(floatArrayOf(0f, H * .92f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), H * .018f, 0f, H * .1f, 4, pics[0])
        }

        fun poplar() {
            val tr = H * .03f
            trunk(floatArrayOf(0f, H * .3f, 0f), tr, tr * .7f, 3)
            val n = 7
            for (k in 0 until n) {
                val t = k / (n - 1f)
                val y = H * (.25f + .62f * t)
                val w = H * .12f * (1.1f - .5f * abs(t - .4f))
                lobe(r(-.02f, .02f) * H, y, r(-.02f, .02f) * H, w, H * .13f, w, 8, 5, .14f)
            }
        }

        fun bushRound() {
            val w = H * 1.2f
            val n = 5
            lobe(0f, H * .45f, 0f, w * .45f, H * .5f, w * .45f, 9, 5, .12f)
            for (k in 0 until n) {
                val a = k * 6.28f / n + r(-.4f, .4f)
                val rr = w * r(.2f, .3f)
                val s = w * r(.22f, .3f)
                lobe(cos(a) * rr, H * r(.3f, .5f), sin(a) * rr, s, H * r(.32f, .42f), s, 8, 5, .15f)
            }
        }

        fun bushTall() {
            val w = H * .5f
            val n = 5
            for (k in 0 until n) {
                val t = k / (n - 1f).coerceAtLeast(1f)
                lobe(r(-.04f, .04f) * w, H * (.2f + .62f * t), r(-.04f, .04f) * w, w * r(.48f, .56f), H * .2f, w * r(.48f, .56f), 9, 5, .12f)
            }
        }

        fun topiary() {
            val w = H * .55f
            lobe(0f, H * .3f, 0f, w * .55f, H * .3f, w * .55f, 10, 6, .05f)
            lobe(0f, H * .76f, 0f, w * .4f, H * .22f, w * .4f, 9, 5, .05f)
        }

        // --- a face, its picture laid from its own top-left at [TEXEL] a texel (scaled down to fit when bigger)
        fun tface(pts: List<FloatArray>, img: PixImg, inside: FloatArray) {
            var nx = 0f; var ny = 0f; var nz = 0f
            for (i in pts.indices) {
                val a = pts[i]; val b = pts[(i + 1) % pts.size]
                nx += (a[1] - b[1]) * (a[2] + b[2]); ny += (a[2] - b[2]) * (a[0] + b[0]); nz += (a[0] - b[0]) * (a[1] + b[1])
            }
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-7f) return
            var n = floatArrayOf(nx / nl, ny / nl, nz / nl)
            val cx = pts.sumOf { it[0].toDouble() }.toFloat() / pts.size; val cy = pts.sumOf { it[1].toDouble() }.toFloat() / pts.size; val cz = pts.sumOf { it[2].toDouble() }.toFloat() / pts.size
            if (n[0] * (cx - inside[0]) + n[1] * (cy - inside[1]) + n[2] * (cz - inside[2]) < 0f) n = floatArrayOf(-n[0], -n[1], -n[2])
            // the picture's right: horizontal in the face, its down: n x right
            val right = if (abs(n[1]) > .95f) floatArrayOf(if (n[1] < 0f) -1f else 1f, 0f, 0f) else MeshBuilder.nrm(MeshBuilder.cross(floatArrayOf(0f, 1f, 0f), n))
            val down = MeshBuilder.nrm(MeshBuilder.cross(n, right)).let { if (abs(n[1]) <= .95f && it[1] > 0f) floatArrayOf(-it[0], -it[1], -it[2]) else it }
            val us = FloatArray(pts.size) { MeshBuilder.dot(pts[it], right) / TEXEL }; val vs = FloatArray(pts.size) { MeshBuilder.dot(pts[it], down) / TEXEL }
            val u0 = us.min(); val v0 = vs.min()
            val spanU = us.max() - u0; val spanV = vs.max() - v0
            val su = if (spanU > img.w) img.w / spanU else 1f
            val sv = if (spanV > img.h) img.h / spanV else 1f
            val ou = min(rnd.next() * 30f, img.w - spanU * su).coerceAtLeast(0f)
            val ov = min(rnd.next() * 30f, img.h - spanV * sv).coerceAtLeast(0f)
            val p = FloatArray(pts.size * 3); val uv = FloatArray(pts.size * 2)
            for ((i, q) in pts.withIndex()) { q.copyInto(p, i * 3); uv[i * 2] = ou + (us[i] - u0) * su; uv[i * 2 + 1] = ov + (vs[i] - v0) * sv }
            m.poly(p, uv, img, n, right)
        }
    }

    /** A 0..1 hash of a seed and two grid indices (a lobe's bumps). */
    private fun hash(seed: Int, i: Int, j: Int): Float {
        var h = seed * 374761393 + i * 668265263 + j * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
    }
}
