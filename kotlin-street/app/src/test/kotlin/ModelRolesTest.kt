package com.timerpg.street

import com.timerpg.street.dialogue.ModelFiles
import com.timerpg.street.dialogue.ModelRole
import com.timerpg.street.dialogue.ModelRoles
import com.timerpg.street.dialogue.ModelRoles.Load
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which dialogue model loads, and where, for the phone's free memory, the town's state and the game being in front. */
class ModelRolesTest {
    private val bank = 5_680_522_464L
    private val live = ModelFiles.LIVE_BYTES
    private fun mb(n: Long) = n shl 20

    private fun pick(freeMb: Long, foreground: Boolean = true, built: Boolean = true, bankBytes: Long = bank, liveBytes: Long = live,
                     accelerated: Boolean = true, forceBank: Boolean = false, low: Boolean = false, writing: Boolean = false,
                     liveWorkMb: Long = 300) =
        ModelRoles.pick(ModelRoles.Input(foreground, built, mb(freeMb), low, bankBytes, liveBytes, accelerated, forceBank, writing,
            liveWorkBytes = mb(liveWorkMb)))

    @Test fun theNeedsAsThePhoneSawThem() {
        // the main model's NPU copy: 6617 MB with the margin (the figure the phone reported); the light one's ~3.7 GB
        assertEquals(6617L, ModelRoles.needBytes(bank, true) shr 20)
        assertEquals(3663L, ModelRoles.needBytes(live, true) shr 20)
        assertEquals(2431L, ModelRoles.needBytes(live, false) shr 20)
    }

    @Test fun inPlayBesideTheTownTheLightModelGoesOnTheNpu() {
        // the S25 Ultra with the town built: 4067 MB free (measured)
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(4067))
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(3700))
    }

    @Test fun inPlayWithRoomTheMainModelGoesOnTheNpu() {
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000))
    }

    @Test fun shortOfMemoryTheLightModelRunsOnTheCpuElseNothingLoads() {
        assertEquals(Load(ModelRole.LIVE, copied = false), pick(3000))
        assertTrue(pick(2000) is ModelRoles.Wait)
        assertTrue(pick(8000, low = true) is ModelRoles.Wait)
    }

    @Test fun nothingLoadsWhileTheTownIsBuilt() {
        val w = pick(8000, built = false)
        assertTrue(w is ModelRoles.Wait && w.why.contains("town"))
    }

    @Test fun behindTheGameTheMainModelWritesTheBank() {
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, foreground = false))
        // its copy does not fit: on the CPU from the file (the earlier fallback), not the light model
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, foreground = false))
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, foreground = false, built = false))
    }

    @Test fun theOvernightWriterAlwaysWritesWithTheMainModel() {
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, writing = true))
        // even with the game in front (and its town up or not): the bank is the main model's
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, writing = true, foreground = true))
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, writing = true, built = false))
    }

    @Test fun thePanelsSwitchPutsTheMainModelInPlay() {
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, forceBank = true))
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, forceBank = true))
    }

    @Test fun inPlayWithoutTheLightModelTheMainOneLoadsOnlyWhereItsCopyFits() {
        // (the main model on the CPU from its file writes a line in tens of seconds: the panel asks for the light model)
        val n = pick(4067, liveBytes = 0)
        assertTrue(n is ModelRoles.NeedLive && n.needBytes shr 20 == 6617L)
        assertTrue(pick(7000, liveBytes = 0, accelerated = false) is ModelRoles.NeedLive)
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, liveBytes = 0))
        // the panel's comparison switch still puts it in play, on the CPU if it must
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, liveBytes = 0, forceBank = true))
    }

    @Test fun theMeasuredWorkingMemoryDecidesTheCopy() {
        // the phone (build 321): 3621 MB free, the light copy 42 MB short with the flat 300 MB guess
        assertEquals(Load(ModelRole.LIVE, copied = false), pick(3621))
        // with the cache and buffers the load measured (an 8-bit cache, a smaller context) it fits on the NPU
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(3621, liveWorkMb = 150))
        assertEquals(3513L, ModelRoles.needBytes(live, true, mb(150)) shr 20)
    }

    @Test fun withOneModelOnThePhoneThatOneLoads() {
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, liveBytes = 0, foreground = false))
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(4067, bankBytes = 0, foreground = false))
        assertEquals(ModelRoles.NoModel, pick(8000, bankBytes = 0, liveBytes = 0))
    }

    @Test fun onTheCpuByChoiceTheLightModelAnswersInPlay() {
        assertEquals(Load(ModelRole.LIVE, copied = false), pick(7000, accelerated = false))
        assertEquals(Load(ModelRole.BANK, copied = false), pick(7000, accelerated = false, foreground = false))
    }
}
