package com.timerpg.street

import com.timerpg.street.dialogue.ModelDownloads
import com.timerpg.street.dialogue.ModelFiles
import com.timerpg.street.dialogue.ModelPrep
import com.timerpg.street.dialogue.ModelPrep.Step
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * The light model's download, fallback and one-time preparation ([ModelPrep]) over a directory of fake model files
 * (sparse: their sizes are real, their bytes are not), with a fake native side that "reorders" by making the prepared
 * file at its real size (the source + 128 bytes, as measured for the 4B MTP file) and remembers its K.
 */
class ModelPrepTest {
    private lateinit var dir: File
    private val mtp = ModelPrep.SOURCES[0]
    private val old = ModelPrep.SOURCES[1]
    private val k = ModelPrep.K_DEFAULT
    private val lots = 100L shl 30

    @Before fun setUp() { dir = createTempDir("modelprep") }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun sized(name: String, bytes: Long) = File(dir, name).also { f -> RandomAccessFile(f, "rw").use { it.setLength(bytes) } }
    private fun raw(s: ModelPrep.Source) = sized(s.name, s.bytes)
    private fun part(s: ModelPrep.Source, bytes: Long) = sized(s.name + ".part", bytes)
    private fun names() = dir.list()!!.filter { !it.startsWith(".") }.sorted()

    /** The native side, faked: K of each prepared file, and how each call should end. */
    private inner class Fake(var reorderCode: String = "done", var free: Long = lots) : ModelPrep.Ops {
        val ks = HashMap<String, Long>()
        var reorders = 0
        var retargets = 0
        override fun headK(f: File) = ks[f.name] ?: 0L
        override fun freeBytes(dir: File) = free
        override fun reorder(src: File, dst: File, k: Int, progress: (Double) -> Boolean): ModelPrep.Result {
            reorders++
            val part = File(dst.path + ".part")
            RandomAccessFile(part, "rw").use { it.setLength(src.length() / 2) }
            if (!progress(0.5) || reorderCode == "cancelled") { part.delete(); return ModelPrep.Result("cancelled", 100) }
            if (reorderCode == "failed") { part.delete(); return ModelPrep.Result("failed", 100, error = "row 977 of token_embd.weight is wrong") }
            RandomAccessFile(part, "rw").use { it.setLength(src.length() + 128) }
            assertTrue(part.renameTo(dst))
            ks[dst.name] = k.toLong()
            progress(1.0)
            return ModelPrep.Result("done", 3826, 5 shl 10, 89 shl 10)
        }
        override fun retarget(f: File, k: Int): ModelPrep.Result {
            retargets++
            if ((ks[f.name] ?: 0L) == k.toLong()) return ModelPrep.Result("already")
            ks[f.name] = k.toLong(); return ModelPrep.Result("done", 1)
        }
    }

    @Test fun theDownloadIsTheMtpFileAtItsHuggingFaceSize() {
        val item = ModelDownloads.ITEMS.single { it.path == ModelFiles.LIVE_MTP }
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-4B-MTP-GGUF/resolve/main/Qwen3.5-4B-Q4_0.gguf", item.url)
        assertEquals(2_669_209_920L, item.bytes)
        // the old file is not downloaded any more
        assertTrue(ModelDownloads.ITEMS.none { it.path == ModelFiles.LIVE })
        assertEquals("qwen3.5-4b-mtp-q4_0-en.gguf", ModelPrep.preparedName(ModelFiles.LIVE_MTP))
    }

    @Test fun nothingOnThePhone() {
        assertNull(ModelPrep.live(dir))
        assertEquals(Step.Idle, ModelPrep.next(dir, k, { 0 }, lots))
        assertTrue(ModelDownloads.missingIn(dir, ModelDownloads.ITEMS).any { it.path == ModelFiles.LIVE_MTP })
    }

    @Test fun theOldFileIsUsedWhileTheMtpOneDownloads() {
        raw(old)
        part(mtp, 1L shl 30)
        val live = ModelPrep.live(dir)!!
        assertEquals(old.name, live.file.name)
        assertFalse(live.mtp)
        // a half-downloaded file is never loaded, and the MTP download is still missing (to resume)
        assertTrue(ModelDownloads.missingIn(dir, ModelDownloads.ITEMS).any { it.path == ModelFiles.LIVE_MTP })
    }

    @Test fun theOldFileIsPreparedMeanwhileWhenTheSecondCopyFitsBesideTheRestOfTheDownload() {
        raw(old)
        part(mtp, 1L shl 30)
        val rest = mtp.bytes - (1L shl 30)
        val need = old.bytes + ModelPrep.SPARE_BYTES + rest
        assertEquals(Step.NoSpace(need, need - 1), ModelPrep.next(dir, k, { 0 }, need - 1))
        assertEquals(Step.Reorder(File(dir, old.name), File(dir, ModelPrep.preparedName(old.name)), k), ModelPrep.next(dir, k, { 0 }, need))
    }

    @Test fun theMtpFileOnceCompleteReplacesTheOldOneAndIsPrepared() {
        raw(old)
        raw(mtp)
        val live = ModelPrep.live(dir)!!
        assertEquals(mtp.name, live.file.name)
        assertTrue(live.mtp)
        assertFalse(live.prepared)
        val fake = Fake()
        val out = ModelPrep.run(dir, k, fake, allowed = { true })
        assertTrue(out.ok)
        assertTrue(out.changed)
        // the old file deleted first (superseded: room for the second copy), then the reorder, then the download
        assertEquals(listOf(ModelPrep.preparedName(mtp.name)), names())
        assertEquals(1, fake.reorders)
        assertTrue(out.log[0], out.log[0].startsWith("deleted ${old.name}"))
        val prepared = ModelPrep.live(dir)!!
        assertTrue(prepared.prepared && prepared.mtp)
        assertEquals(mtp.bytes + 128, prepared.file.length())
        // nothing to download: the prepared file stands for the download
        assertTrue(ModelDownloads.missingIn(dir, ModelDownloads.ITEMS).none { it.path == ModelFiles.LIVE_MTP })
        // and a second run does nothing
        val again = ModelPrep.run(dir, k, fake, allowed = { true })
        assertTrue(again.ok); assertFalse(again.changed); assertTrue(again.log.isEmpty()); assertEquals(1, fake.reorders)
    }

    @Test fun aSwapInterruptedAfterTheRenameDeletesTheDownloadNextTime() {
        raw(mtp)
        sized(ModelPrep.preparedName(mtp.name), mtp.bytes + 128)
        val fake = Fake().apply { ks[ModelPrep.preparedName(mtp.name)] = k.toLong() }
        assertEquals(Step.Delete(listOf(File(dir, mtp.name)), "prepared into ${ModelPrep.preparedName(mtp.name)}"), ModelPrep.next(dir, k, fake::headK, lots))
        assertTrue(ModelPrep.run(dir, k, fake, allowed = { true }).ok)
        assertEquals(listOf(ModelPrep.preparedName(mtp.name)), names())
        assertEquals(0, fake.reorders)
    }

    @Test fun aConversationCancelsTheReorderAndTheNextRunStartsAgain() {
        raw(mtp)
        var talking = false
        val fake = Fake()
        // the conversation begins half-way: the reorder is cancelled through its progress callback
        val out = ModelPrep.run(dir, k, fake, allowed = { !talking }, progress = { f -> if (f >= 0.5) talking = true })
        assertFalse(out.ok)
        assertFalse(out.changed)
        assertTrue(out.stopped!!, out.stopped!!.startsWith("cancelled"))
        assertEquals(listOf(mtp.name), names())          // no part file left, the download untouched
        assertEquals(mtp.name, ModelPrep.live(dir)!!.file.name)
        // held: nothing starts during a conversation
        assertTrue(ModelPrep.run(dir, k, fake, allowed = { false }).stopped!!.startsWith("held"))
        assertEquals(1, fake.reorders)
        talking = false
        assertTrue(ModelPrep.run(dir, k, fake, allowed = { !talking }).ok)
        assertEquals(listOf(ModelPrep.preparedName(mtp.name)), names())
    }

    @Test fun aFailedReorderKeepsTheDownload() {
        raw(mtp)
        val out = ModelPrep.run(dir, k, Fake(reorderCode = "failed"), allowed = { true })
        assertFalse(out.ok)
        assertTrue(out.stopped!!, out.stopped!!.contains("row 977"))
        assertEquals(listOf(mtp.name), names())
        assertEquals(mtp.name, ModelPrep.live(dir)!!.file.name)
    }

    @Test fun aStalePartFileIsRemovedFirst() {
        raw(mtp)
        sized(ModelPrep.preparedName(mtp.name) + ".part", 123_456_789)
        sized(ModelFiles.LIVE + ".part", 5_000_000)
        val st = ModelPrep.next(dir, k, { 0 }, lots)
        assertTrue(st is Step.Delete && st.files.size == 2)
    }

    @Test fun theQualitySettingChangesKInPlaceAndBack() {
        raw(mtp)
        val fake = Fake()
        ModelPrep.run(dir, k, fake, allowed = { true })
        val f = File(dir, ModelPrep.preparedName(mtp.name))
        assertEquals(Step.Retarget(f, ModelPrep.K_QUALITY), ModelPrep.next(dir, ModelPrep.K_QUALITY, fake::headK, lots))
        val q = ModelPrep.run(dir, ModelPrep.K_QUALITY, fake, allowed = { true })
        assertTrue(q.ok); assertTrue(q.changed)
        assertEquals(ModelPrep.K_QUALITY.toLong(), fake.headK(f))
        assertEquals(1, fake.reorders)                    // no new reorder, no download
        assertTrue(ModelPrep.run(dir, k, fake, allowed = { true }).changed)
        assertEquals(k.toLong(), fake.headK(f))
        assertEquals(listOf(f.name), names())
    }

    @Test fun aPreparedFileWithoutTheKeyIsNotTrusted() {
        sized(ModelPrep.preparedName(mtp.name), mtp.bytes + 128)
        assertEquals(Step.Delete(listOf(File(dir, ModelPrep.preparedName(mtp.name))), "not a prepared file (no timerpg.head_vocab)"),
            ModelPrep.next(dir, k, { 0 }, lots))
    }

    @Test fun sizesThatAreNotTheDownloadsAreNotUsed() {
        sized(mtp.name, mtp.bytes - 1)                 // short: still downloading, or broken
        sized(ModelPrep.preparedName(old.name), old.bytes)    // not bigger than its source: not a finished preparation
        assertNull(ModelPrep.live(dir))
        assertEquals(Step.Idle, ModelPrep.next(dir, k, { 0 }, lots))
    }

    @Test fun anOldPreparedFileIsUsedUntilTheMtpOneIsHereThenDeleted() {
        sized(ModelPrep.preparedName(old.name), old.bytes + 160)
        val fake = Fake().apply { ks[ModelPrep.preparedName(old.name)] = k.toLong() }
        assertEquals(ModelPrep.preparedName(old.name), ModelPrep.live(dir)!!.file.name)
        assertTrue(ModelPrep.run(dir, k, fake, allowed = { true }).ok)
        raw(mtp)
        val out = ModelPrep.run(dir, k, fake, allowed = { true })
        assertTrue(out.ok && out.changed)
        assertEquals(listOf(ModelPrep.preparedName(mtp.name)), names())
        assertTrue(ModelPrep.live(dir)!!.mtp)
    }
}
