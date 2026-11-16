package com.lifemax.rpg.feature.habits.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The street's trees, with no drawing in it: a handful of shapes (broadleaf, tall, pine, shrub), chosen by a seed and
 * the same every time, as the clumps their foliage is made of, in dp at full size, standing on the pavement line at
 * x 0 with up as a negative y. The scene turns each shape into a painted sprite once.
 */

enum class TreeKind {
    ROUND,
    TALL,
    PINE,
    SHRUB,
}

/**
 * One tree. [clumps] are four numbers each (across, up as a negative, half width, half height): the
 * masses of leaves the canopy is built from (a pine's are its tiers, widest at their foot, the
 * lowest first). [halfWidth] and [height] bound the whole of it, [crown] is where the trunk ends
 * and the foliage begins (up as a negative), [warmth] (0 to .35) how far its green leans to olive.
 */
class TreeShape(
    val kind: TreeKind,
    val clumps: FloatArray,
    val halfWidth: Float,
    val height: Float,
    val crown: Float,
    val warmth: Float,
)

object Trees {
    const val VARIANTS = 8

    /**
     * The variant a tree with [seed] is, 0 until [VARIANTS]: three round, two tall, two pines, a
     * shrub.
     */
    fun variant(seed: Int): Int =
        (Ambient.hash(seed, 0, 90) * VARIANTS).toInt().coerceIn(0, VARIANTS - 1)

    val shapes: List<TreeShape> by lazy { List(VARIANTS) { build(it) } }

    fun shape(seed: Int): TreeShape = shapes[variant(seed)]

    /** Whether [variant] keeps its needles all year (a pine): the seasons leave it green. */
    fun evergreen(variant: Int): Boolean = shapes[variant].kind == TreeKind.PINE

    /** Whether [variant] flowers in spring: the ornamental (pink) and the shrub (white). */
    fun blossoms(variant: Int): Boolean = variant == 2 || variant == 7

    /** Whether [variant] turns red in autumn (the others turn amber). */
    fun turnsRed(variant: Int): Boolean = variant % 2 == 1

    private fun kindOf(v: Int) =
        when (v) {
            0,
            1,
            2 -> TreeKind.ROUND
            3,
            4 -> TreeKind.TALL
            5,
            6 -> TreeKind.PINE
            else -> TreeKind.SHRUB
        }

    /**
     * Each variant's clumps before they are jittered and fitted: across, up, half width, half
     * height.
     */
    private val BASE: Array<FloatArray> =
        arrayOf(
            // an oak: a broad crown of seven masses
            floatArrayOf(
                0f,
                -46f,
                14f,
                11.5f,
                -11f,
                -38f,
                11f,
                9f,
                11f,
                -38f,
                11f,
                9f,
                -6f,
                -54f,
                9.5f,
                8f,
                7f,
                -53f,
                9.5f,
                8f,
                0f,
                -36f,
                12f,
                8.5f,
                -15f,
                -31f,
                6.5f,
                5.5f,
            ),
            // a maple: lower and wider
            floatArrayOf(
                0f,
                -40f,
                15f,
                10.5f,
                -13f,
                -33f,
                9.5f,
                7.5f,
                13f,
                -33f,
                9.5f,
                7.5f,
                -7f,
                -47f,
                9f,
                7.5f,
                8f,
                -46f,
                9f,
                7.5f,
                0f,
                -30f,
                12f,
                6.5f,
            ),
            // an ornamental: small and round
            floatArrayOf(
                0f,
                -40f,
                12f,
                10f,
                -8f,
                -34f,
                8.5f,
                7.5f,
                8f,
                -34f,
                8.5f,
                7.5f,
                -3f,
                -47f,
                7.5f,
                6.5f,
                5f,
                -44f,
                7f,
                6f,
            ),
            // a tall oval
            floatArrayOf(
                0f,
                -52f,
                9.5f,
                9f,
                -4f,
                -40f,
                10f,
                8.5f,
                4f,
                -39f,
                10.5f,
                8.5f,
                0f,
                -29f,
                10.5f,
                7f,
                -2f,
                -61f,
                6.5f,
                5.5f,
                -5f,
                -33f,
                7f,
                5.5f,
            ),
            // a poplar: a narrow column
            floatArrayOf(
                0f,
                -55f,
                6.5f,
                8f,
                -1f,
                -46f,
                8f,
                8.5f,
                1f,
                -37f,
                8.5f,
                8f,
                0f,
                -29f,
                8f,
                7f,
                0f,
                -62f,
                4.5f,
                4.5f,
                -1.5f,
                -22f,
                6.5f,
                5f,
            ),
            // a pine: five tiers, lowest first (centre, base of tier, half width at the foot,
            // height)
            floatArrayOf(
                0f,
                -16f,
                13f,
                13f,
                0f,
                -26f,
                11.2f,
                12.5f,
                0f,
                -37f,
                9.2f,
                12f,
                0f,
                -48f,
                7f,
                11.5f,
                0f,
                -58f,
                4.6f,
                9f,
            ),
            // a slim pine: six tiers
            floatArrayOf(
                0f,
                -15f,
                9.8f,
                11f,
                0f,
                -23f,
                8.8f,
                10.5f,
                0f,
                -31f,
                7.8f,
                10f,
                0f,
                -40f,
                6.6f,
                9.5f,
                0f,
                -49f,
                5f,
                9f,
                0f,
                -58f,
                3.4f,
                7.5f,
            ),
            // a shrub: a low mound of four masses
            floatArrayOf(
                0f,
                -13f,
                10f,
                8.5f,
                -9f,
                -9f,
                7.5f,
                6f,
                9f,
                -9f,
                7.5f,
                6f,
                -2f,
                -19f,
                6.5f,
                5.5f,
            ),
        )

    private fun build(v: Int): TreeShape {
        val kind = kindOf(v)
        val clumps = BASE[v].copyOf()
        // every tree is its own: the masses nudge a little, the same every time
        var half = 0f
        var top = 0f
        var i = 0
        while (i < clumps.size) {
            val amount = if (kind == TreeKind.PINE) .5f else 1.4f
            clumps[i] += (Ambient.hash(v, i, 91) - .5f) * amount
            if (kind != TreeKind.PINE) clumps[i + 1] += (Ambient.hash(v, i + 1, 91) - .5f) * amount
            half = max(half, abs(clumps[i]) + clumps[i + 2])
            top =
                min(
                    top,
                    clumps[i + 1] - (if (kind == TreeKind.PINE) clumps[i + 3] else clumps[i + 3]),
                )
            i += 4
        }
        // fitted to the room a tree is given: at most 21.5 wide each way and 64 tall
        val room = min(1f, min(21.5f / half, 64f / (-top)))
        if (room < 1f) {
            for (k in clumps.indices) clumps[k] *= room
            half *= room
            top *= room
        }
        // a pine's tier is drawn from its base up; the others are centred on their mass
        val height =
            if (kind == TreeKind.PINE) {
                var t = 0f
                var k = 0
                while (k < clumps.size) {
                    t = min(t, clumps[k + 1] - clumps[k + 3])
                    k += 4
                }
                -t
            } else -top
        val crown =
            when (kind) {
                TreeKind.ROUND -> -28f
                TreeKind.TALL -> -25f
                TreeKind.PINE -> -17f
                TreeKind.SHRUB -> -6f
            }
        return TreeShape(
            kind,
            clumps,
            half,
            height,
            crown,
            if (kind == TreeKind.PINE) 0f else .35f * Ambient.hash(v, 7, 92),
        )
    }
}
