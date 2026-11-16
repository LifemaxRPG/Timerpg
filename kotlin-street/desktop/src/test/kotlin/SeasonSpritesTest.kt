package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.Trees
import com.lifemax.rpg.feature.habits.presentation.scene.FlareSprites
import com.lifemax.rpg.feature.habits.presentation.scene.SeasonSprites
import com.lifemax.rpg.feature.habits.presentation.scene.TreeDress
import com.lifemax.rpg.feature.habits.presentation.scene.TreeSeason
import com.lifemax.rpg.feature.habits.presentation.scene.TreeSprites
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seasons' sprites are deterministic and clear of their borders; the bare tree stands inside
 * the leafy one's outline (its branches are what the masses sit on); the snow lies on the tree,
 * white; the dress of a tree over the year never jumps; the flare's parts are small and white.
 */
class SeasonSpritesTest {
    private fun alpha(c: Int) = c ushr 24

    @Test
    fun seasonLooksAreTheSameEveryTimeAndClearOfTheBorder() {
        for (season in TreeSeason.entries) for (v in 0 until Trees.VARIANTS) {
            val a = TreeSprites.generate(v, season)
            val b = TreeSprites.generate(v, season)
            assertEquals(3, a.pixels.size)
            for (l in a.pixels.indices) assertArrayEquals(a.pixels[l], b.pixels[l])
            for (look in a.pixels) {
                for (x in 0 until a.w) {
                    assertEquals(0, alpha(look[x]))
                    assertEquals(0, alpha(look[(a.h - 1) * a.w + x]))
                }
                for (y in 0 until a.h) {
                    assertEquals(0, alpha(look[y * a.w]))
                    assertEquals(0, alpha(look[y * a.w + a.w - 1]))
                }
            }
            // a pine is the same tree all year; the others change
            val summer = TreeSprites.generate(v)
            if (Trees.evergreen(v)) assertArrayEquals(summer.pixels[0], a.pixels[0])
            else if (season != TreeSeason.SUMMER)
                assertFalse(summer.pixels[0].contentEquals(a.pixels[0]))
            assertEquals(summer.w, a.w)
            assertEquals(summer.h, a.h)
            assertEquals(summer.footX, a.footX, 0f)
            assertEquals(summer.footY, a.footY, 0f)
        }
    }

    @Test
    fun theBareTreeStandsInsideTheLeafyOne() {
        for (v in 0 until Trees.VARIANTS) {
            if (Trees.evergreen(v)) continue
            val bare = TreeSprites.generate(v, TreeSeason.BARE)
            val leafy = TreeSprites.generate(v)
            var solid = 0
            var outside = 0
            for (y in 0 until bare.h) for (x in 0 until bare.w) {
                if (alpha(bare.pixels[0][y * bare.w + x]) <= 128) continue
                solid++
                // some leafy pixel within two pixels of it
                var covered = false
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= bare.w || yy >= bare.h) continue
                    if (alpha(leafy.pixels[0][yy * bare.w + xx]) > 100) covered = true
                }
                if (!covered) outside++
            }
            assertTrue(
                "variant $v: $outside of $solid bare pixels outside the leafy tree",
                outside * 100 <= solid,
            )
            // and it is a real tree: a trunk with a crown of branches over it, not a stick
            val crown =
                (0 until bare.h / 2).sumOf { y ->
                    (0 until bare.w).count { alpha(bare.pixels[0][y * bare.w + it]) > 60 }
                }
            assertTrue("variant $v crown $crown", crown > 200)
        }
    }

    @Test
    fun snowLiesWhiteOnTheTreeAndOnlyOnIt() {
        for (v in 0 until Trees.VARIANTS) {
            val snow = TreeSprites.snow(v)
            val tree =
                TreeSprites.generate(
                    v,
                    if (Trees.evergreen(v)) TreeSeason.SUMMER else TreeSeason.BARE,
                )
            assertEquals(tree.w * tree.h, snow.size)
            assertArrayEquals(snow, TreeSprites.snow(v))
            var any = 0
            for (y in 0 until tree.h) for (x in 0 until tree.w) {
                val c = snow[y * tree.w + x]
                if (alpha(c) == 0) continue
                any++
                assertEquals(0xFFFFFF, c and 0xFFFFFF)
                // on or just over something of the tree
                var on = false
                for (dy in 0..3) if (
                    y + dy < tree.h && alpha(tree.pixels[0][(y + dy) * tree.w + x]) > 60
                )
                    on = true
                assertTrue("variant $v snow at $x,$y off the tree", on)
            }
            assertTrue("variant $v has $any snow pixels", any > 150)
        }
    }

    /** What each look contributes to the picture: its alpha, less what the looks over it cover. */
    private fun visible(plan: FloatArray): FloatArray {
        val out = FloatArray(plan.size)
        for ((i, s) in TreeDress.ORDER.withIndex()) {
            var seen = plan[s.ordinal]
            for (j in i + 1 until TreeDress.ORDER.size) seen *=
                1f - plan[TreeDress.ORDER[j].ordinal]
            out[s.ordinal] = seen
        }
        return out
    }

    @Test
    fun theTreesDressChangesSmoothlyOverTheYear() {
        val a = FloatArray(TreeSeason.entries.size)
        val b = FloatArray(TreeSeason.entries.size)
        for (v in 0 until Trees.VARIANTS) {
            TreeDress.plan(Season.atDay(366f, 2025), v, a)
            var seenBefore = visible(a)
            for (d in 1..366) {
                TreeDress.plan(Season.atDay(d.toFloat(), 2026), v, b)
                val seen = visible(b)
                for (i in a.indices) {
                    assertTrue(b[i] in 0f..1f)
                    assertTrue(
                        "variant $v day $d look $i ${seenBefore[i]} -> ${seen[i]}",
                        abs(seenBefore[i] - seen[i]) < .12f,
                    )
                }
                seenBefore = seen
            }
            // high summer: the summer look alone; deep winter: the bare tree alone (a pine: summer)
            TreeDress.plan(Season.SUMMER, v, a)
            assertEquals(1f, a[TreeSeason.SUMMER.ordinal], 0f)
            assertEquals(1f, a.sum(), 0f)
            TreeDress.plan(Season.atDay(20f, 2026), v, a)
            if (Trees.evergreen(v)) assertEquals(1f, a[TreeSeason.SUMMER.ordinal], 0f)
            else {
                assertEquals(1f, a[TreeSeason.BARE.ordinal], 0f)
                assertEquals(0f, TreeDress.canopy(a), 0f)
            }
            // late October: colour on the tree
            TreeDress.plan(Season.atDay(298f, 2026), v, a)
            if (!Trees.evergreen(v)) assertTrue(a[TreeSeason.AUTUMN.ordinal] > .9f)
            // blossom only on the flowering variants, in April
            TreeDress.plan(Season.atDay(110f, 2026), v, a)
            assertEquals(Trees.blossoms(v), a[TreeSeason.BLOSSOM.ordinal] > .5f)
        }
    }

    @Test
    fun theSeasonsStayInsideTheMemoryBudget() {
        // the deepest autumn day holds the bare tree, the turning and the autumn looks; winter the
        // bare tree and the snow
        val autumn =
            TreeSprites.bytes(TreeSeason.BARE) +
                TreeSprites.bytes(TreeSeason.TURNING) +
                TreeSprites.bytes(TreeSeason.AUTUMN)
        assertTrue("autumn adds $autumn", autumn < 3_600_000)
        assertTrue(TreeSprites.bytes(TreeSeason.BARE) + TreeSprites.snowBytes() < 1_800_000)
        assertTrue(SeasonSprites.bytes() < 16_000)
        assertTrue(FlareSprites.bytes() < 48_000)
        assertEquals(TreeSprites.bytes(), TreeSprites.bytes(TreeSeason.SUMMER))
    }

    @Test
    fun leavesPetalsAndTheFlakeAreSmallDeterministicAndClearOfTheirBorders() {
        val leaves = SeasonSprites.leaves()
        assertEquals(SeasonSprites.LEAF_SHAPES * 2, leaves.size)
        assertArrayEquals(leaves[1], SeasonSprites.leaves()[1])
        for ((i, l) in leaves.withIndex()) {
            assertEquals(SeasonSprites.LEAF * SeasonSprites.LEAF, l.size)
            assertTrue(l.count { alpha(it) > 128 } in 20..150)
            for (k in 0 until SeasonSprites.LEAF) assertEquals(0, alpha(l[k]))
            // amber and red tell apart: the red ones have less green
            val g = l.filter { alpha(it) > 128 }.map { (it shr 8) and 255 }.average()
            if (i % 2 == 1) assertTrue(g < 90) else assertTrue(g > 90)
        }
        val petals = SeasonSprites.petals()
        assertEquals(2, petals.size)
        for (p in petals) assertTrue(p.count { alpha(it) > 128 } in 8..60)
        val flake = SeasonSprites.flake()
        assertArrayEquals(flake, SeasonSprites.flake())
        assertTrue(flake.any { alpha(it) > 200 })
        for (k in 0 until SeasonSprites.FLAKE) assertEquals(0, alpha(flake[k]))
        assertTrue(flake.all { alpha(it) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
    }

    @Test
    fun theFlaresPartsAreWhiteFeatheredAndDifferent() {
        val f = FlareSprites.flare()
        val g = FlareSprites.flare()
        assertArrayEquals(f.disc.pixels, g.disc.pixels)
        for (l in listOf(f.disc, f.ring, f.poly)) {
            assertTrue(l.pixels.any { alpha(it) > 150 })
            assertTrue(l.pixels.all { alpha(it) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
            for (k in 0 until l.size) assertEquals(0, alpha(l.pixels[k]))
            // nothing touches the border, the middle is soft
            assertEquals(0, alpha(l.pixels[l.size / 2]))
        }
        // the ring is hollow, the disc is not
        val mid = f.disc.size / 2 * f.disc.size + f.disc.size / 2
        assertTrue(alpha(f.disc.pixels[mid]) > 100)
        assertTrue(alpha(f.ring.pixels[mid]) < 40)
        assertFalse(f.poly.pixels.contentEquals(f.disc.pixels))
        assertEquals(f.streakW * f.streakH, f.streak.size)
        assertTrue(alpha(f.streak[(f.streakH / 2) * f.streakW + f.streakW / 2]) > 150)
        assertEquals(0, alpha(f.streak[0]))
        assertEquals(FlareSprites.bytes(), f.bytes())
    }
}
