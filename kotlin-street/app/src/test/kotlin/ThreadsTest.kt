package com.timerpg.street.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How many threads the model gets on a few phone layouts: cpu_capacity per core (the fastest is 1024; the others are
 * typical values, not read from these exact phones) or max clocks in kHz.
 */
class ThreadsTest {
    private fun cores(vararg groups: Pair<Int, Long>) = groups.flatMap { (n, v) -> List(n) { v } }

    @Test
    fun noLittleCoresGivesSixThreads() {
        // Snapdragon 8 Elite for Galaxy (S25 Ultra): 6 x 3.53 GHz + 2 x 4.47 GHz of one design
        assertEquals(6, NativeLlm.threadsFor(8, cores(6 to 809L, 2 to 1024L), cores(6 to 3_532_800L, 2 to 4_473_600L)))
        assertEquals(6, NativeLlm.threadsFor(8, cores(8 to 1024L)))
    }

    @Test
    fun littleCoresAreLeftOut() {
        // Snapdragon 8 Gen 2: 3 x A510, 4 x A715/A710, 1 x X3 -> 5 big
        assertEquals(5, NativeLlm.threadsFor(8, cores(3 to 280L, 4 to 760L, 1 to 1024L)))
        // 6 x A55 + 2 x A76 (Snapdragon 7-series), and 4 + 4
        assertEquals(2, NativeLlm.threadsFor(8, cores(6 to 350L, 2 to 1024L)))
        assertEquals(4, NativeLlm.threadsFor(8, cores(4 to 380L, 4 to 1024L)))
    }

    @Test
    fun clocksOnlyCountTheCoresAboveTheSlowestCluster() {
        // 4 x A55 at 1.9 GHz next to A78s at 2.2-2.4 GHz: clocks this close must not make the little cores count
        assertEquals(4, NativeLlm.threadsFor(8, emptyList(), cores(4 to 1_900_800L, 3 to 2_208_000L, 1 to 2_400_000L)))
        assertEquals(2, NativeLlm.threadsFor(8, emptyList(), cores(6 to 1_804_800L, 2 to 2_400_000L)))
        // without capacities the S25 Ultra gets its 2 fastest cores (conservative)
        assertEquals(2, NativeLlm.threadsFor(8, emptyList(), cores(6 to 3_532_800L, 2 to 4_473_600L)))
        assertEquals(6, NativeLlm.threadsFor(8, emptyList(), cores(8 to 2_000_000L)))
    }

    @Test
    fun alwaysLeavesTwoCoresForTheGame() {
        assertEquals(2, NativeLlm.threadsFor(4, emptyList()))
        assertEquals(2, NativeLlm.threadsFor(4, cores(4 to 1024L)))
        assertEquals(1, NativeLlm.threadsFor(2, emptyList()))
        assertEquals(1, NativeLlm.threadsFor(1, emptyList()))
    }
}
