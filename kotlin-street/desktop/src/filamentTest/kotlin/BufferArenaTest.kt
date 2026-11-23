import com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared buffer pages' free list ([BufferArena.Page]): ranges never overlap, and what is given back merges again. */
class BufferArenaTest {
    @Test fun takesAndMerges() {
        val p = BufferArena.Page(null, null, 100)
        assertEquals(0, p.take(30)); assertEquals(30, p.take(30)); assertEquals(60, p.take(40))
        assertEquals(-1, p.take(1))
        p.give(30, 30)
        assertEquals(-1, p.take(31))
        p.give(0, 30)
        // (the first two merged: 60 free at the start)
        assertEquals(60, p.largest())
        p.give(60, 40)
        assertEquals(100, p.largest()); assertEquals(0, p.used); assertEquals(1, p.free.size)
    }

    @Test fun randomRangesNeverOverlap() {
        val cap = 65536
        val p = BufferArena.Page(null, null, cap)
        val rnd = java.util.Random(7)
        val held = ArrayList<IntArray>()
        val owner = IntArray(cap) { -1 }
        repeat(20000) { step ->
            if (held.isNotEmpty() && (rnd.nextInt(3) == 0 || p.capacity - p.used < 2000)) {
                val r = held.removeAt(rnd.nextInt(held.size))
                for (i in r[0] until r[0] + r[1]) owner[i] = -1
                p.give(r[0], r[1])
            } else {
                val n = 1 + rnd.nextInt(1500)
                val at = p.take(n)
                if (at >= 0) {
                    for (i in at until at + n) { assertTrue("overlap at $i", owner[i] == -1); owner[i] = step }
                    held += intArrayOf(at, n)
                }
            }
            assertEquals(held.sumOf { it[1] }, p.used)
        }
        for (r in held) p.give(r[0], r[1])
        assertEquals(0, p.used); assertEquals(cap, p.largest()); assertEquals(1, p.free.size)
    }
}
