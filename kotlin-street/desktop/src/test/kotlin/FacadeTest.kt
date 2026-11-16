package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.RuinWindow
import com.lifemax.rpg.feature.habits.domain.floorWindows
import com.lifemax.rpg.feature.habits.domain.ruinWindow
import com.lifemax.rpg.feature.habits.presentation.scene.BuildingDetail
import com.lifemax.rpg.feature.habits.presentation.scene.DetailInk
import com.lifemax.rpg.feature.habits.presentation.scene.FacadeLook
import com.lifemax.rpg.feature.habits.presentation.scene.FacadeSprites
import com.lifemax.rpg.feature.habits.presentation.scene.FloorExtra
import com.lifemax.rpg.feature.habits.presentation.scene.SceneInk
import com.lifemax.rpg.feature.habits.presentation.scene.Shapes
import com.lifemax.rpg.feature.habits.presentation.scene.WallTile
import com.lifemax.rpg.feature.habits.presentation.scene.facade
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facades: each material's baked wall tile (the same every time, opaque, seamless, the
 * palette's colour on average, inside the budget) and the architecture worked out from the
 * building's and the floor's seeds (the same every time, sparse, and inside the floor's front).
 */
class FacadeTest {
    private val tiles: Map<HabitMaterial, WallTile> by lazy {
        HabitMaterial.entries.associateWith { FacadeSprites.tile(it) }
    }

    private fun channel(c: Int, shift: Int) = (c ushr shift) and 255

    @Test
    fun tilesAreTheSameEveryTimeOpaqueAndTheirStatedSize() {
        for (m in HabitMaterial.entries) {
            val a = tiles.getValue(m)
            val b = FacadeSprites.tile(m)
            assertArrayEquals(a.pixels, b.pixels)
            val (uw, uh) = FacadeSprites.tileUnits(m)
            assertEquals((uw * FacadeSprites.PPU).toInt(), a.w)
            assertEquals((uh * FacadeSprites.PPU).toInt(), a.h)
            assertEquals(a.w * a.h, a.pixels.size)
            assertTrue("$m opaque", a.pixels.all { (it ushr 24) == 255 })
        }
        // and five different walls
        val all = tiles.values.toList()
        for (i in all.indices) for (j in i + 1 until all.size) assertTrue(
            !all[i].pixels.contentEquals(all[j].pixels)
        )
    }

    @Test
    fun tilesRepeatWithoutASeam() {
        for (m in HabitMaterial.entries) {
            val t = tiles.getValue(m)
            // the step from the last column to the first is no bigger than the steps inside
            fun diff(x0: Int, x1: Int): Float {
                var sum = 0f
                for (y in 0 until t.h) {
                    val a = t.pixels[y * t.w + x0]
                    val b = t.pixels[y * t.w + x1]
                    for (s in intArrayOf(0, 8, 16)) sum += abs(channel(a, s) - channel(b, s))
                }
                return sum / t.h
            }
            fun diffRow(y0: Int, y1: Int): Float {
                var sum = 0f
                for (x in 0 until t.w) {
                    val a = t.pixels[y0 * t.w + x]
                    val b = t.pixels[y1 * t.w + x]
                    for (s in intArrayOf(0, 8, 16)) sum += abs(channel(a, s) - channel(b, s))
                }
                return sum / t.w
            }
            var inside = 0f
            for (x in 0 until t.w - 1) inside = maxOf(inside, diff(x, x + 1))
            var insideRows = 0f
            for (y in 0 until t.h - 1) insideRows = maxOf(insideRows, diffRow(y, y + 1))
            // (a panel's seam is a hard edge by design: the wrap is allowed to match it)
            assertTrue(
                "$m seam across ${diff(t.w - 1, 0)} vs $inside",
                diff(t.w - 1, 0) <= inside * 1.05f + 2f,
            )
            assertTrue(
                "$m seam down ${diffRow(t.h - 1, 0)} vs $insideRows",
                diffRow(t.h - 1, 0) <= insideRows * 1.05f + 2f,
            )
        }
    }

    @Test
    fun tilesKeepThePalettesWallColourOnAverage() {
        for (m in HabitMaterial.entries) {
            val t = tiles.getValue(m)
            val f = facade(m)
            var r = 0L
            var g = 0L
            var b = 0L
            for (c in t.pixels) {
                r += channel(c, 16)
                g += channel(c, 8)
                b += channel(c, 0)
            }
            val n = t.pixels.size.toFloat()
            val old =
                floatArrayOf(
                    (f.wallTop.red + f.wallBottom.red) / 2f,
                    (f.wallTop.green + f.wallBottom.green) / 2f,
                    (f.wallTop.blue + f.wallBottom.blue) / 2f,
                )
            val mean = floatArrayOf(r / n / 255f, g / n / 255f, b / n / 255f)
            for (i in 0..2) assertTrue(
                "$m channel $i ${mean[i]} vs ${old[i]}",
                abs(mean[i] - old[i]) < .11f,
            )
            // and it is a surface, not a flat colour
            var varied = 0
            for (c in t.pixels) if (abs(channel(c, 8) - mean[1] * 255f) > 6f) varied++
            assertTrue("$m varied $varied", varied > t.pixels.size / 20)
        }
    }

    @Test
    fun theWholePoolIsInsideItsBudget() {
        val bytes = tiles.values.sumOf { it.pixels.size * 4 }
        val lobby = FacadeSprites.lobbyTile()
        val grime = FacadeSprites.grime()
        val drip = FacadeSprites.drip()
        assertEquals(
            bytes + lobby.pixels.size * 4 + grime.w * grime.h * 4 + drip.w * drip.h * 4,
            FacadeSprites.bytes(),
        )
        assertTrue("${FacadeSprites.bytes()}", FacadeSprites.bytes() < 1_000_000)
    }

    @Test
    fun theLobbysGraniteIsBakedOnceTheSameAndInTone() {
        val a = FacadeSprites.lobbyTile()
        assertArrayEquals(a.pixels, FacadeSprites.lobbyTile().pixels)
        assertTrue(a.pixels.all { (it ushr 24) == 255 })
        // the old flat lobby wall's colour on average
        val wall = SceneInk.LobbyWall
        val n = a.pixels.size.toFloat()
        val mean =
            floatArrayOf(
                a.pixels.sumOf { channel(it, 16) } / n / 255f,
                a.pixels.sumOf { channel(it, 8) } / n / 255f,
                a.pixels.sumOf { channel(it, 0) } / n / 255f,
            )
        val old = floatArrayOf(wall.red, wall.green, wall.blue)
        for (i in 0..2) assertTrue("lobby $i ${mean[i]}", abs(mean[i] - old[i]) < .06f)
        // seamless across: the step from the last column to the first no bigger than the steps
        // inside (a joint is a hard edge by design)
        fun step(x0: Int, x1: Int) =
            (0 until a.h).sumOf { y ->
                (0 until 3).sumOf {
                    abs(
                        channel(a.pixels[y * a.w + x0], it * 8) -
                            channel(a.pixels[y * a.w + x1], it * 8)
                    )
                }
            }
        val inside = (0 until a.w - 1).maxOf { step(it, it + 1) }
        val wrap = step(a.w - 1, 0)
        assertTrue("granite seam $wrap vs $inside", wrap <= inside * 1.05f + 2 * a.h)
    }

    @Test
    fun theRevealsAndTheCastShadowsFlipWithTheLightsSide() {
        val fromLeft = -.8f
        val fromRight = .8f
        // a face turned left is lit by a light from the left and in shade under one from the right
        assertTrue(FacadeLook.alpha(DetailInk.FACE_L, fromLeft, 1f, 0f) > .1f)
        assertTrue(FacadeLook.alpha(DetailInk.FACE_L, fromRight, 1f, 0f) < -.1f)
        assertTrue(FacadeLook.alpha(DetailInk.FACE_R, fromRight, 1f, 0f) > .1f)
        assertTrue(FacadeLook.alpha(DetailInk.FACE_R, fromLeft, 1f, 0f) < -.1f)
        // each side's cast shadows and the jambs' shadows on the glass only under their own light
        for ((ink, side) in
            listOf(
                DetailInk.CAST_FROM_LEFT to fromLeft,
                DetailInk.CAST_FROM_RIGHT to fromRight,
                DetailInk.GLASS_FROM_LEFT to fromLeft,
                DetailInk.GLASS_FROM_RIGHT to fromRight,
            )) {
            assertTrue("$ink", FacadeLook.alpha(ink, side, 1f, 0f) < -.1f)
            assertEquals("$ink", 0f, FacadeLook.alpha(ink, -side, 1f, 0f), 1e-6f)
            // softer under an overcast sky (a weak light with no direct sun) than in the sun
            assertTrue(
                abs(FacadeLook.alpha(ink, side * .2f, 0f, 0f)) <
                    abs(FacadeLook.alpha(ink, side, 1f, 0f)) * .3f
            )
        }
        // face on, nothing is cast to a side; the heads are in shade, deeper in the sun
        assertEquals(0f, FacadeLook.alpha(DetailInk.CAST_FROM_LEFT, 0f, 1f, 0f), 1e-6f)
        assertTrue(
            FacadeLook.alpha(DetailInk.SOFFIT, 0f, 1f, 0f) <
                FacadeLook.alpha(DetailInk.SOFFIT, 0f, 0f, 0f)
        )
        // and the shapes: each shadow with the light from the left lies to the right of its twin
        // with the light from the right; the left jamb faces right
        val win = windows(60f, false)
        val wall = Shapes()
        BuildingDetail.floor(
            HabitMaterial.STONE,
            false,
            -100f,
            -40f,
            win,
            win.size / 4,
            2,
            -1,
            7,
            0,
            Float.NaN,
            null,
            wall,
            Shapes(),
        )
        val l = wall.rects(DetailInk.CAST_FROM_LEFT)
        val r = wall.rects(DetailInk.CAST_FROM_RIGHT)
        assertEquals(l.size, r.size)
        for (i in 0 until l.size / 4) assertTrue(l[i * 4] > r[i * 4])
        val faceR = wall.rects(DetailInk.FACE_R)
        val x0 = win[0]
        var leftJamb = false
        for (i in 0 until faceR.size / 4) if (
            faceR[i * 4] < x0 && faceR[i * 4] + faceR[i * 4 + 2] <= x0 + 1e-3f
        )
            leftJamb = true
        assertTrue(leftJamb)
    }

    @Test
    fun theSnowStillLandsOnTheSillsAndTheCornice() {
        for (m in HabitMaterial.entries) for (h in BuildingSpec.HEIGHTS) {
            val ruin = m == HabitMaterial.FADE
            val height = if (ruin) BuildingSpec.RUIN else h
            val win = windows(height, ruin)
            val top = -300f
            val wall = Shapes()
            BuildingDetail.floor(
                m,
                ruin,
                top,
                top + height,
                win,
                win.size / 4,
                2,
                -1,
                3,
                0,
                Float.NaN,
                null,
                wall,
                Shapes(),
            )
            val trim = wall.rects(DetailInk.TRIM)
            for (k in 0 until win.size / 4) {
                // the painter's snow cap: from x - .7, w + 1.4 wide, at the sill's top
                val y = BuildingDetail.sillTop(top + win[k * 4 + 1], win[k * 4 + 3])
                assertEquals(top + win[k * 4 + 1] + win[k * 4 + 3], y, 1e-4f)
                var under = false
                for (i in 0 until trim.size / 4) if (
                    abs(trim[i * 4 + 1] - y) < 1e-3f &&
                        trim[i * 4] <= win[k * 4] - .7f &&
                        trim[i * 4] + trim[i * 4 + 2] >= win[k * 4] + win[k * 4 + 2] + .7f
                )
                    under = true
                assertTrue("$m $h sill $k", under)
            }
        }
        // the cornice's coping at the top of its band, where the roof's snow lies
        for (m in HabitMaterial.entries) {
            val s = Shapes()
            BuildingDetail.cornice(m, -200f, s)
            val light = s.rects(DetailInk.LIGHT)
            assertTrue(
                (0 until light.size / 4).any {
                    abs(light[it * 4 + 1] - (-200f - BuildingSpec.CORNICE)) < 1e-3f &&
                        light[it * 4 + 2] >= BuildingSpec.W + 6f - 1e-3f
                }
            )
        }
    }

    @Test
    fun choicesAreTheSameEveryTimeAndSparse() {
        var noPipe = 0
        var left = 0
        var stacks = 0
        val n = 400
        for (seed in 0 until n) {
            val a = BuildingDetail.choices(seed * 7919 + 13)
            val b = BuildingDetail.choices(seed * 7919 + 13)
            assertEquals(a.pipeSide, b.pipeSide)
            assertEquals(a.roof.dish, b.roof.dish)
            if (a.pipeSide == 0) noPipe++
            if (a.pipeSide < 0) left++
            if (a.roof.stack) stacks++
            assertTrue(abs(a.pipeX) < BuildingSpec.W / 2f)
        }
        assertTrue("$noPipe without a pipe", noPipe in n / 20..n / 3)
        assertTrue("$left on the left", left in n / 5..n * 3 / 5)
        assertTrue("$stacks stacks", stacks in n / 3..n * 3 / 4)
        var ac = 0
        var balcony = 0
        for (seed in 0 until n) {
            val e = BuildingDetail.extra(seed * 31 + 5, HabitMaterial.STONE, 4, 6)
            assertEquals(e, BuildingDetail.extra(seed * 31 + 5, HabitMaterial.STONE, 4, 6))
            if (e == FloorExtra.AIR_CON) ac++
            if (e == FloorExtra.BALCONY) balcony++
            // nothing stands in front of a window of a floor whose every window lights up
            assertEquals(FloorExtra.NONE, BuildingDetail.extra(seed, HabitMaterial.CLAY, 6, 6))
            assertEquals(FloorExtra.NONE, BuildingDetail.extra(seed, HabitMaterial.GLASS, 2, 6))
        }
        assertTrue("$ac air conditioners", ac in n / 25..n / 3)
        assertTrue("$balcony balconies", balcony in n / 25..n / 3)
    }

    private fun windows(h: Float, ruin: Boolean): FloatArray {
        val spots = floorWindows(h, ruin)
        val out = FloatArray(spots.size * 4)
        for ((k, s) in spots.withIndex()) {
            out[k * 4] = s.x
            out[k * 4 + 1] = s.y
            out[k * 4 + 2] = s.w
            out[k * 4 + 3] = s.h
        }
        return out
    }

    @Test
    fun aFloorsArchitectureStaysInsideItsFrontAndKeepsItsSills() {
        val half = BuildingSpec.W / 2f
        for (m in HabitMaterial.entries) for (h in BuildingSpec.HEIGHTS) for (pipe in
            floatArrayOf(Float.NaN, half - 4.5f, -(half - 4.5f))) for (seed in 0 until 12) {
            val ruin = m == HabitMaterial.FADE
            val height = if (ruin) BuildingSpec.RUIN else h
            val win = windows(height, ruin)
            val count = win.size / 4
            val top = -200f
            val bottom = top + height
            val wall = Shapes()
            val front = Shapes()
            val over = Shapes()
            val ruins = Array(count) { ruinWindow("k$seed", seed % 3, 3, it) }
            BuildingDetail.floor(
                m,
                ruin,
                top,
                bottom,
                win,
                count,
                lit = 2 + seed % 4,
                hall = 1,
                seed = seed * 101,
                storey = seed % 3,
                pipeX = pipe,
                ruins = if (ruin) ruins else null,
                wall = wall,
                front = front,
                over = over,
            )
            // what goes over the glass stays on the glass
            val ob = over.bounds()
            if (ob != null) {
                val minX = (0 until count).minOf { win[it * 4] }
                val maxX = (0 until count).maxOf { win[it * 4] + win[it * 4 + 2] }
                val minY = (0 until count).minOf { top + win[it * 4 + 1] }
                val maxY = (0 until count).maxOf { top + win[it * 4 + 1] + win[it * 4 + 3] }
                assertTrue(ob[0] >= minX - 1e-3f && ob[2] <= maxX + 1e-3f)
                assertTrue(ob[1] >= minY - 1e-3f && ob[3] <= maxY + 1e-3f)
            }
            for (s in listOf(wall, front)) {
                val b = s.bounds() ?: continue
                // the string course reaches a unit past each edge, nothing more; nothing above the
                // floor's top or below its foot
                assertTrue("$m $h left ${b[0]}", b[0] >= -half - 1f - 1e-3f)
                assertTrue("$m $h right ${b[2]}", b[2] <= half + 1f + 1e-3f)
                assertTrue("$m $h top ${b[1]}", b[1] >= top - 1e-3f)
                assertTrue("$m $h bottom ${b[3]}", b[3] <= bottom + 1e-3f)
            }
            // a sill under every window, where the snow lands (its top at the pane's foot)
            val trim = wall.rects(DetailInk.TRIM)
            for (k in 0 until count) {
                val x = win[k * 4]
                val yb = top + win[k * 4 + 1] + win[k * 4 + 3]
                var sill = false
                for (i in 0 until trim.size / 4) if (
                    abs(trim[i * 4 + 1] - yb) < 1e-3f &&
                        trim[i * 4] < x &&
                        trim[i * 4] + trim[i * 4 + 2] > x + win[k * 4 + 2]
                )
                    sill = true
                assertTrue("$m window $k sill", sill)
            }
            // the string course is there, along the whole top
            var course = false
            for (i in 0 until trim.size / 4) if (
                abs(trim[i * 4 + 1] - top) < 1e-3f && trim[i * 4 + 2] >= BuildingSpec.W
            )
                course = true
            assertTrue(course)
            if (!pipe.isNaN() && m != HabitMaterial.GLASS && !ruin)
                assertTrue(wall.rects(DetailInk.IRON).size > 0)
            if (ruin) {
                // tags only on boarded windows
                for (p in front.lines(DetailInk.SPRAY)) {
                    val x = p[0]
                    val k =
                        (0 until count).first {
                            x >= win[it * 4] - 2f && x <= win[it * 4] + win[it * 4 + 2] + 2f
                        }
                    assertEquals(RuinWindow.BOARDED, ruins[k])
                }
            } else if (front.bounds() != null) {
                // what stands in front of a window stands in the last to light
                val k = count - 1
                val b = front.bounds()!!
                assertTrue(b[0] >= win[k * 4] - 3f - 1e-3f)
                assertTrue(b[2] <= win[k * 4] + win[k * 4 + 2] + 3f + 1e-3f)
            }
        }
    }

    @Test
    fun theWeatheringAndTheDripsAreBakedOnceSoftAndClampable() {
        val g = FacadeSprites.grime()
        assertArrayEquals(g.light, FacadeSprites.grime().light)
        assertEquals((FacadeSprites.GRIME_W * FacadeSprites.GRIME_PPU).toInt(), g.w)
        assertEquals((FacadeSprites.GRIME_H * FacadeSprites.GRIME_PPU).toInt(), g.h)
        // one dark colour at an alpha, never more than a veil
        val rgb = g.light[0] and 0xFFFFFF
        assertTrue(g.light.all { (it and 0xFFFFFF) == rgb })
        assertTrue(channel(rgb, 16) < 60 && channel(rgb, 8) < 60 && channel(rgb, 0) < 60)
        assertTrue(g.light.all { (it ushr 24) <= 160 })
        // clear on its last row (it is clamped below the strip), the soot heaviest under the ledge
        assertTrue((0 until g.w).all { (g.light[(g.h - 1) * g.w + it] ushr 24) == 0 })
        fun row(y: Int) = (0 until g.w).sumOf { g.light[y * g.w + it] ushr 24 } / g.w
        assertTrue("${row(0)} vs ${row(g.h / 2)}", row(0) > row(g.h / 2) * 2)
        // streaks: some columns run dark well down the strip, most are clear there
        val y = g.h / 2
        val streaked = (0 until g.w).count { (g.light[y * g.w + it] ushr 24) > 10 }
        assertTrue("$streaked", streaked in 4..g.w / 2)
        // seamless across
        var step = 0
        var wrap = 0
        for (yy in 0 until g.h) {
            wrap += abs((g.light[yy * g.w + g.w - 1] ushr 24) - (g.light[yy * g.w] ushr 24))
            step =
                maxOf(
                    step,
                    (0 until g.w - 1).maxOf {
                        abs((g.light[yy * g.w + it] ushr 24) - (g.light[yy * g.w + it + 1] ushr 24))
                    },
                )
        }
        assertTrue("grime seam $wrap", wrap <= step * g.h / 4 + g.h)
        val d = FacadeSprites.drip()
        assertArrayEquals(d.light, FacadeSprites.drip().light)
        assertTrue((0 until d.w).all { (d.light[(d.h - 1) * d.w + it] ushr 24) == 0 })
        assertTrue((0 until d.h).all { (d.light[it * d.w] ushr 24) == 0 })
        // darkest at the sill, fading down
        fun drow(yy: Int) = (0 until d.w).sumOf { d.light[yy * d.w + it] ushr 24 }
        assertTrue(drow(2) > drow(d.h * 3 / 4) * 2)
    }

    @Test
    fun brickFloorsHaveQuoinsAndAFireEscapeThatClimbsTheBuilding() {
        val half = BuildingSpec.W / 2f
        for (h in BuildingSpec.HEIGHTS) {
            val win = windows(h, false)
            val count = win.size / 4
            val top = -400f
            val bottom = top + h
            // the quoins: trim blocks at both corners, inside the front
            val plain = Shapes()
            BuildingDetail.floor(
                HabitMaterial.CLAY,
                false,
                top,
                bottom,
                win,
                count,
                2,
                -1,
                5,
                0,
                Float.NaN,
                null,
                plain,
                Shapes(),
            )
            val trim = plain.rects(DetailInk.TRIM)
            var left = 0
            var right = 0
            for (i in 0 until trim.size / 4) {
                if (trim[i * 4 + 3] < 3f) continue
                if (abs(trim[i * 4] + half) < 1e-3f) left++
                if (abs(trim[i * 4] + trim[i * 4 + 2] - half) < 1e-3f) right++
            }
            assertTrue("$h quoins $left $right", left >= 4 && left == right)
            // the escape: a platform and railing in front, a stair up and one arriving, the drop
            // ladder only under the lowest platform; all inside the floor
            for ((below, above) in
                listOf(Float.NaN to true, 60f to true, 84f to false, Float.NaN to false)) {
                val wall = Shapes()
                val front = Shapes()
                BuildingDetail.floor(
                    HabitMaterial.CLAY,
                    false,
                    top,
                    bottom,
                    win,
                    count,
                    2,
                    -1,
                    5,
                    0,
                    Float.NaN,
                    null,
                    wall,
                    front,
                    escape =
                        com.lifemax.rpg.feature.habits.presentation.scene.Escape(3, below, above),
                )
                val b = front.bounds()!!
                assertTrue(b[0] >= -half - 1e-3f && b[2] <= half + 1e-3f)
                assertTrue("$h ${b[1]} ${b[3]}", b[1] >= top - 1e-3f && b[3] <= bottom + 1e-3f)
                val lines = front.lines(DetailInk.IRON).size
                // the braces, and a stringer and handrail for each flight drawn
                val flights = (if (above) 1 else 0) + (if (below.isNaN()) 0 else 1)
                assertEquals("$h $below $above", 2 + 2 * flights, lines)
                // the escape stands on the right, clear of the plaque
                assertTrue(b[0] > -half + BuildingSpec.PLAQUE_X + 100f)
            }
        }
        // half the buildings or so have one, the same every time
        var with = 0
        for (seed in 0 until 400) {
            val c = BuildingDetail.choices(seed * 7919 + 13)
            assertEquals(c.fireEscape, BuildingDetail.choices(seed * 7919 + 13).fireEscape)
            if (c.fireEscape) with++
        }
        assertTrue("$with", with in 120..280)
    }

    @Test
    fun theCorniceAndTheLobbyStayInTheirBands() {
        val half = BuildingSpec.W / 2f
        for (m in HabitMaterial.entries) {
            val s = Shapes()
            BuildingDetail.cornice(m, -300f, s)
            val b = s.bounds()
            assertNotNull(b)
            assertTrue(b!![0] >= -half - 3f - 1e-3f && b[2] <= half + 3f + 1e-3f)
            assertTrue(b[1] >= -300f - BuildingSpec.CORNICE - 1e-3f && b[3] <= -300f + 1e-3f)
        }
        val iron = HashMap<Boolean, Int>()
        for (pipe in floatArrayOf(Float.NaN, half - 4.5f)) {
            val s = Shapes()
            BuildingDetail.lobby(-BuildingSpec.LOBBY_H, pipe, s)
            val b = s.bounds()!!
            assertTrue(b[0] >= -half - 1e-3f && b[2] <= half + 1e-3f)
            assertTrue(b[1] >= -BuildingSpec.LOBBY_H - 1e-3f && b[3] <= 1e-3f)
            iron[pipe.isNaN()] = s.rects(DetailInk.IRON).size
        }
        // the pipe adds its length and brackets to the lamps' brackets
        assertTrue(iron.getValue(false) > iron.getValue(true))
    }
}
