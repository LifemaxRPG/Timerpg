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
                     liveWorkMb: Long = 300, reclaimMb: Long = 0, npu: Boolean = accelerated) =
        ModelRoles.pick(ModelRoles.Input(foreground, built, mb(freeMb), low, bankBytes, liveBytes, accelerated, forceBank, writing,
            liveWorkBytes = mb(liveWorkMb), reclaimBytes = mb(reclaimMb), npu = npu))

    /** In play nothing loads on the CPU: a [ModelRoles.Wait] for the NPU copy's room. */
    private fun waitsForNpu(c: ModelRoles.Choice) = c is ModelRoles.Wait && c.device

    @Test fun withWhatAndroidFindsTheLightModelsCopyIsTriedBeforeTheCpu() {
        // the phone's reports: 2.0 to 3.6 GB free beside the town, 3663 MB needed
        assertEquals(Load(ModelRole.LIVE, copied = true, tried = true), pick(3000, reclaimMb = 1024))
        assertEquals(Load(ModelRole.LIVE, copied = true, tried = true), pick(2700, reclaimMb = 1024))
        // not enough even so: in play nothing on the CPU (the owner's rule, 2026-11-23): wait for the NPU copy's room
        assertTrue(waitsForNpu(pick(2500, reclaimMb = 500)))
        // a copy that fits outright is no try
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(3700, reclaimMb = 1024))
        // in play the main model's copy is never a try while the light one's fits
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(4000, reclaimMb = 4000))
        assertEquals(Load(ModelRole.LIVE, copied = true, tried = true), pick(3000, reclaimMb = 4000))
        // Android says memory is low: no try
        assertTrue(pick(3000, reclaimMb = 1024, low = true) is ModelRoles.Wait)
        // writing (Writer mode): the main model's copy may be a try
        assertEquals(Load(ModelRole.BANK, copied = true, tried = true), pick(5200, writing = true, reclaimMb = 2000))
        // without the light model in play, the main one's copy tried, else the light model is asked for
        assertEquals(Load(ModelRole.BANK, copied = true, tried = true), pick(5200, liveBytes = 0, reclaimMb = 2000))
        assertTrue(pick(3000, liveBytes = 0, reclaimMb = 1000) is ModelRoles.NeedLive)
        // the game behind: no try (the first process Android ends)
        assertEquals(Load(ModelRole.BANK, copied = false), pick(5200, foreground = false, reclaimMb = 2000))
    }

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

    @Test fun shortOfMemoryInPlayNothingLoadsAndItWaitsForTheNpu() {
        // (before 2026-11-23 the light model ran on the CPU from its file here: the owner's playtest found it useless)
        assertTrue(waitsForNpu(pick(3000)))
        assertTrue(waitsForNpu(pick(2000)))
        assertTrue(waitsForNpu(pick(8000, low = true)))
    }

    @Test fun inPlayTheModelNeverRunsOnTheCpu() {
        // every free memory, with and without what Android finds, the panel's switch, the measured work: never the CPU
        for (free in listOf(500L, 1500L, 2244L, 2804L, 3000L, 3500L, 3751L, 4067L, 5000L, 6617L, 8000L, 12000L))
            for (reclaim in listOf(0L, 500L, 1514L))
                for (force in listOf(false, true))
                    for (low in listOf(false, true))
                        for (liveBytes in listOf(live, 0L)) {
                            val c = pick(free, reclaimMb = reclaim, forceBank = force, low = low, liveBytes = liveBytes)
                            assertTrue("$free MB free, $reclaim found, force $force, low $low, light ${liveBytes > 0}: $c",
                                c !is Load || c.copied)
                        }
    }

    @Test fun theS25sPlaytestOf715WaitsForTheNpuInsteadOfTheCpu() {
        // build 0.1.715 beside the capital: 2804 MB free, the light copy's own memory measured on the NPU at 387 MB
        // (KV 19 + state 50 + compute 318): 3751 MB needed. Then it ran on the CPU; now it waits and says so
        val w = pick(2804, liveWorkMb = 388)
        assertTrue(waitsForNpu(w))
        w as ModelRoles.Wait
        assertEquals(3751L, w.needBytes shr 20)
        assertTrue(w.why, w.why.contains("2804 MB free") && w.why.contains("3751 MB needed"))
        // with what Android found for this copy beside the town on build 0.1.598 (1514 MB, measured): tried on the NPU
        assertEquals(Load(ModelRole.LIVE, copied = true, tried = true), pick(2804, liveWorkMb = 388, reclaimMb = 1514))
    }

    @Test fun noNpuAndShortOfMemoryAreToldApart() {
        // the S25's report of build 0.1.848: no NPU read (the backends not loaded yet), 3088 MB free. The wait says it is
        // no NPU, and why; with the NPU there and memory short it says the NPU is there and gives the figures
        val none = ModelRoles.pick(ModelRoles.Input(true, true, mb(3088), false, bank, live, accelerated = true,
            liveWorkBytes = mb(388), npu = false, npuWhy = "this build has no NPU library"))
        none as ModelRoles.Wait
        assertTrue(none.noNpu && none.device && none.needBytes == 0L)
        assertTrue(none.why, none.why.contains("this build has no NPU library"))
        val short = pick(3088, liveWorkMb = 388)
        short as ModelRoles.Wait
        assertTrue(!short.noNpu && short.device && short.needBytes shr 20 == 3751L)
        assertTrue(short.why, short.why.contains("The NPU is there") && short.why.contains("3088 MB free") && short.why.contains("3751 MB needed"))
    }

    @Test fun inPlayWithoutAnNpuNothingLoads() {
        // the GPU (or the CPU's repacked kernels) but no NPU: in play nothing, people speak their lines written ahead
        assertTrue(waitsForNpu(pick(9000, npu = false)))
        assertTrue(waitsForNpu(pick(9000, accelerated = false)))
        // behind the game the old rules: the CPU from the file
        assertEquals(Load(ModelRole.BANK, copied = false), pick(9000, accelerated = false, foreground = false))
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
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, writing = true, built = false))
    }

    @Test fun writingTheBankNeverFallsBackToTheCpuWhileThereIsADevice() {
        // the main model's copy does not fit: the light model's copy on the NPU, not the main one from its file on the CPU
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(4067, writing = true, foreground = true))
        // neither copy fits: wait (and say so), nothing on the CPU
        assertTrue(pick(3000, writing = true) is ModelRoles.Wait)
        // no light model on the phone: wait for room for the main one's copy
        assertTrue(pick(4067, writing = true, liveBytes = 0) is ModelRoles.Wait)
        // no accelerator (or the CPU chosen): the CPU from the file, as before
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, writing = true, accelerated = false))
    }

    @Test fun thePanelsSwitchPutsTheMainModelInPlay() {
        // its copy does not fit: it waits for room (on the NPU only), not the CPU
        assertTrue(waitsForNpu(pick(4067, forceBank = true)))
        assertEquals(Load(ModelRole.BANK, copied = true, tried = true), pick(5200, forceBank = true, reclaimMb = 2000))
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, forceBank = true))
    }

    @Test fun inPlayWithoutTheLightModelTheMainOneLoadsOnlyWhereItsCopyFits() {
        // (the main model on the CPU from its file writes a line in tens of seconds: the panel asks for the light model)
        val n = pick(4067, liveBytes = 0)
        assertTrue(n is ModelRoles.NeedLive && n.needBytes shr 20 == 6617L)
        assertTrue(pick(7000, liveBytes = 0, accelerated = false) is ModelRoles.NeedLive)
        assertEquals(Load(ModelRole.BANK, copied = true), pick(7000, liveBytes = 0))
        // the panel's comparison switch puts it in play only where its copy fits: never on the CPU
        assertTrue(waitsForNpu(pick(4067, liveBytes = 0, forceBank = true)))
    }

    @Test fun theMeasuredWorkingMemoryDecidesTheCopy() {
        // the phone (build 321): 3621 MB free, the light copy 42 MB short with the flat 300 MB guess: waits (no CPU)
        assertTrue(waitsForNpu(pick(3621)))
        // with the cache and buffers the load measured (an 8-bit cache, a smaller context) it fits on the NPU
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(3621, liveWorkMb = 150))
        assertEquals(3513L, ModelRoles.needBytes(live, true, mb(150)) shr 20)
    }

    @Test fun withOneModelOnThePhoneThatOneLoads() {
        assertEquals(Load(ModelRole.BANK, copied = false), pick(4067, liveBytes = 0, foreground = false))
        assertEquals(Load(ModelRole.LIVE, copied = true), pick(4067, bankBytes = 0, foreground = false))
        assertEquals(ModelRoles.NoModel, pick(8000, bankBytes = 0, liveBytes = 0))
    }

    @Test fun theCpuByChoiceAppliesOnlyBehindTheGame() {
        assertTrue(waitsForNpu(pick(7000, accelerated = false)))
        assertEquals(Load(ModelRole.BANK, copied = false), pick(7000, accelerated = false, foreground = false))
    }
}
