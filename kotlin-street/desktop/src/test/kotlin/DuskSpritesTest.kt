package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.presentation.scene.BuildingPainter
import com.lifemax.rpg.feature.habits.presentation.scene.DuskLayer
import com.lifemax.rpg.feature.habits.presentation.scene.DuskSprites
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small life's painted pieces are baked once from pure Kotlin: the same pixels every time, the
 * steam white at every alpha so it takes a tint, every piece clear along its border, the pigeon
 * drawn in every frame with its feet on the last rows, the whole well inside its budget; and the
 * painter's only memory of it (when each building's pigeons went up) is a few hundred bytes.
 */
class DuskSpritesTest {
    private fun alpha(c: Int) = c ushr 24

    private fun borderClear(l: DuskLayer) {
        for (x in 0 until l.w) {
            assertEquals(0, alpha(l.pixels[x]))
            assertEquals(0, alpha(l.pixels[(l.h - 1) * l.w + x]))
        }
        for (y in 0 until l.h) {
            assertEquals(0, alpha(l.pixels[y * l.w]))
            assertEquals(0, alpha(l.pixels[y * l.w + l.w - 1]))
        }
    }

    @Test
    fun everyPieceIsTheSameEveryTimeAndClearAtItsBorder() {
        for (v in 0 until DuskSprites.PUFFS) {
            val a = DuskSprites.puff(v)
            assertArrayEquals(a.pixels, DuskSprites.puff(v).pixels)
            assertEquals(DuskSprites.PUFF * DuskSprites.PUFF, a.pixels.size)
            borderClear(a)
            // white at every alpha, and soft: no pixel fully opaque
            assertTrue(a.pixels.all { alpha(it) == 0 || (it and 0xFFFFFF) == 0xFFFFFF })
            assertTrue(a.pixels.none { alpha(it) == 255 })
            val filled = a.pixels.count { alpha(it) > 0 }
            assertTrue("$filled filled", filled in 200..700)
        }
        for (f in 0 until DuskSprites.PIGEON_FRAMES) {
            val a = DuskSprites.pigeon(f)
            assertArrayEquals(a.pixels, DuskSprites.pigeon(f).pixels)
            assertEquals(DuskSprites.PIGEON_W * DuskSprites.PIGEON_H, a.pixels.size)
            borderClear(a)
            val filled = a.pixels.count { alpha(it) > 0 }
            assertTrue("frame $f: $filled filled", filled in 30..120)
        }
    }

    @Test
    fun theFramesDiffer() {
        val frames = (0 until DuskSprites.PIGEON_FRAMES).map { DuskSprites.pigeon(it).pixels }
        for (i in frames.indices) for (j in i + 1 until frames.size) assertTrue(
            "$i and $j",
            !frames[i].contentEquals(frames[j]),
        )
        val puffs = (0 until DuskSprites.PUFFS).map { DuskSprites.puff(it).pixels }
        for (i in puffs.indices) for (j in i + 1 until puffs.size) assertTrue(
            !puffs[i].contentEquals(puffs[j])
        )
    }

    @Test
    fun aStandingPigeonHasItsFeetOnTheGroundAndAFlyingOneDoesNot() {
        val w = DuskSprites.PIGEON_W
        val h = DuskSprites.PIGEON_H
        for (f in 0 until 3) {
            val p = DuskSprites.pigeon(f)
            val feetRow = (h - 2) * w
            assertTrue("frame $f", (0 until w).any { alpha(p.pixels[feetRow + it]) == 255 })
        }
        for (f in 3 until 5) {
            val p = DuskSprites.pigeon(f)
            val feetRow = (h - 2) * w
            assertTrue("frame $f", (0 until w).none { alpha(p.pixels[feetRow + it]) == 255 })
        }
    }

    @Test
    fun theWholeFitsItsBudget() {
        val bytes = DuskSprites.bytes()
        assertEquals(
            DuskSprites.PUFFS * DuskSprites.PUFF * DuskSprites.PUFF * 4 +
                DuskSprites.PIGEON_FRAMES * DuskSprites.PIGEON_W * DuskSprites.PIGEON_H * 4,
            bytes,
        )
        assertTrue("$bytes bytes", bytes < 16 * 1024)
        assertEquals(
            bytes,
            (0 until DuskSprites.PUFFS).sumOf { DuskSprites.puff(it).bytes } +
                (0 until DuskSprites.PIGEON_FRAMES).sumOf { DuskSprites.pigeon(it).bytes },
        )
        // the painter remembers the pigeons' scares in one float per slot
        assertEquals(64, BuildingPainter.PIGEON_SLOTS)
        assertTrue(BuildingPainter.PIGEON_SLOTS * 4 <= 512)
    }
}
