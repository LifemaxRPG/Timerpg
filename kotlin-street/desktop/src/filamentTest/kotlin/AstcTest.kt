import com.lifemax.rpg.feature.habits.presentation.scene3d.Astc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The town's ASTC 4x4 encoder (TOWN.md, "GPU memory"): a block of one or two colours (see-through ones too) comes back
 * exactly; more colours are approximated; the sizes are right. The two-partition blocks are checked by the desktop renders (the
 * GL's own ASTC decoder): their error against RGBA8 falls, it would jump on a wrong bit.
 */
class AstcTest {
    private val rnd = java.util.Random(7)
    private fun colour() = rnd.nextInt()

    @Test fun oneAndTwoColourBlocksAreExact() {
        val b = ByteArray(16); val out = IntArray(16)
        repeat(20000) {
            val a = colour(); val c = if (it % 3 == 0) a else if (it % 5 == 0) 0 else colour()
            val t = IntArray(16) { if (rnd.nextBoolean()) a else c }
            val st = Astc.Stats()
            Astc.encodeBlock(t, b, st)
            assertEquals(1L, st.exact)
            assertTrue(Astc.decodeBlock(b, out))
            assertArrayEquals(t, out)
        }
    }

    @Test fun moreColoursAreNearNotExact() {
        val b = ByteArray(16)
        val st = Astc.Stats()
        repeat(2000) { Astc.encodeBlock(IntArray(16) { colour() }, b, st) }
        assertEquals(2000L, st.near)
    }

    @Test fun levelSizesAndExactness() {
        assertEquals(16, Astc.levelBytes(1, 1))
        assertEquals(128 * 231 * 16, Astc.levelBytes(512, 924))
        // (512 x 924's chain, ten levels: a quarter of RGBA8's bytes, give or take the blocks' rounding)
        assertEquals(10, 32 - Integer.numberOfLeadingZeros(924))
        assertTrue(Astc.chainBytes(512, 924, 10) < 512 * 924 * 4 / 3 * 4 / 4 + 8 * 1024)
        val flat = IntArray(64 * 64) { if ((it % 64) / 4 % 2 == 0) 0xFF102030.toInt() else 0 }
        assertTrue(Astc.exact(flat, 64, 64))
        flat[5] = 0x7F0000FF; flat[6] = 0x7F00FF00
        assertTrue(!Astc.exact(flat, 64, 64))
    }
}
