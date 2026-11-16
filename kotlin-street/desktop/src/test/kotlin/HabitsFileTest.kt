package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.data.HabitsCodec
import com.lifemax.rpg.feature.habits.data.HabitsFile
import com.lifemax.rpg.feature.habits.data.LocalHabitsRepository
import com.lifemax.rpg.feature.habits.data.toHabitsSurroundings
import com.lifemax.rpg.feature.habits.domain.HabitsLoad
import com.lifemax.rpg.feature.habits.domain.HabitsRules
import com.lifemax.rpg.feature.habits.domain.HabitsSave
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The habits file: its format, its atomic write and safety copy, and that what it cannot read is
 * never overwritten; and the page's surroundings.
 */
class HabitsFileTest {
    private val dir: File = Files.createTempDirectory("habits-test").toFile()
    private val main = File(dir, "habits.json")
    private val previous = File(dir, "habits.prev.json")
    private val file = HabitsFile(dir) { 1234L }
    private val city = HabitsScenarios.city()

    @After
    fun clean() {
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    private fun ready() = file.load() as HabitsLoad.Ready

    @Test
    fun theFormatRoundTripsEverySave() {
        for (name in listOf("empty", "city", "done", "claimed", "rough", "tall")) {
            val save = HabitsScenarios.save(name)
            assertEquals(name, save, HabitsCodec.decode(HabitsCodec.encode(save)))
        }
        val j = JSONObject(HabitsCodec.encode(HabitsScenarios.claimed()))
        assertEquals(1, j.getInt("v"))
        assertEquals(
            setOf("v", "who", "n", "stacks", "items", "log", "rough", "claimed"),
            j.keySet(),
        )
        assertEquals(
            setOf("id", "stack", "kind", "names", "size", "born", "pair", "scar", "floors"),
            j.getJSONArray("items").getJSONObject(0).keySet(),
        )
        assertEquals("fade", j.getJSONArray("items").getJSONObject(4).getString("kind"))
        assertEquals(1, j.getJSONObject("claimed").getJSONArray(HabitsScenarios.TODAY).length())
        assertEquals(1, HabitsCodec.schemaOf(j.toString()))
        assertEquals(7, HabitsCodec.schemaOf("""{"v":7}"""))
        assertNull(HabitsCodec.schemaOf("not json"))
        assertNull(HabitsCodec.schemaOf("""{"v":"1"}"""))
    }

    @Test
    fun readingIsStrict() {
        fun rejects(change: JSONObject.() -> Unit) {
            assertThrows(Exception::class.java) {
                HabitsCodec.decode(JSONObject(HabitsCodec.encode(city)).apply(change).toString())
            }
        }
        fun item(j: JSONObject) = j.getJSONArray("items").getJSONObject(0)
        rejects { put("v", 2) }
        rejects { remove("v") }
        rejects { remove("who") }
        rejects { put("who", 3) }
        rejects { put("n", -1) }
        rejects { put("n", "7") }
        rejects { remove("stacks") }
        rejects { getJSONArray("stacks").getJSONObject(0).put("cue", " ") }
        rejects {
            getJSONArray("stacks")
                .getJSONObject(1)
                .put("id", getJSONArray("stacks").getJSONObject(0).getString("id"))
        }
        rejects { item(this).put("stack", "nope") }
        rejects { item(this).put("kind", "other") }
        rejects { item(this).put("size", 3) }
        rejects { item(this).put("born", "yesterday") }
        rejects { item(this).put("scar", "no") }
        rejects { item(this).put("floors", -2) }
        rejects { item(this).getJSONArray("names").remove(2) }
        rejects { item(this).getJSONArray("names").put(1, " ") }
        rejects { item(this).remove("pair") }
        rejects { getJSONArray("items").getJSONObject(1).put("id", item(this).getString("id")) }
        rejects { getJSONObject("log").put("someday", JSONObject()) }
        rejects {
            getJSONObject("log")
                .getJSONObject(HabitsScenarios.TODAY)
                .put(item(this).getString("id"), 9)
        }
        rejects { put("rough", "x") }
        rejects { getJSONObject("claimed").put("x", org.json.JSONArray()) }
        assertThrows(Exception::class.java) { HabitsCodec.decode("") }
        assertThrows(Exception::class.java) { HabitsCodec.decode("[]") }
    }

    @Test
    fun aFirstRunIsEmptyAndWritesNothing() {
        assertEquals(HabitsSave(), ready().save)
        assertEquals("", ready().notice)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun aWriteIsWholeAndKeepsTheFileItReplacedAsASafetyCopy() {
        assertTrue(file.save(city))
        assertEquals(city, HabitsCodec.decode(main.readText()))
        assertFalse(previous.exists())
        assertEquals(listOf("habits.json"), dir.list()!!.sorted())
        val next = HabitsRules.who(city, "a reader")!!
        assertTrue(file.save(next))
        assertEquals(next, HabitsCodec.decode(main.readText()))
        assertEquals(city, HabitsCodec.decode(previous.readText()))
        // rolling: only the one before
        val third = HabitsRules.who(next, "a calm reader")!!
        assertTrue(file.save(third))
        assertEquals(next, HabitsCodec.decode(previous.readText()))
        assertEquals(listOf("habits.json", "habits.prev.json"), dir.list()!!.sorted())
        assertEquals(third, ready().save)
        // a new file object (the app started again) reads the same
        assertEquals(third, (HabitsFile(dir).load() as HabitsLoad.Ready).save)
    }

    @Test
    fun aWriteThatCannotBeMadeAnswersFalseAndLeavesTheFileAlone() {
        assertTrue(file.save(city))
        // the directory's place is taken by a file: nothing can be written there
        val blockedDir = File(dir, "not-a-directory").apply { writeText("x") }
        assertFalse(HabitsFile(blockedDir).save(city))
        assertEquals("x", blockedDir.readText())
        // the temporary file cannot be created (its name is a directory): the current file stays as
        // it was
        File(dir, "habits.json.tmp").mkdir()
        assertFalse(file.save(HabitsRules.who(city, "someone else")!!))
        assertEquals(city, HabitsCodec.decode(main.readText()))
        assertFalse(previous.exists())
    }

    @Test
    fun aCrashBetweenTheTwoRenamesLosesNothingStored() {
        file.save(city)
        // the moment after the current file became the safety copy and before the new one took its
        // name
        assertTrue(main.renameTo(previous))
        assertEquals(city, ready().save)
        assertEquals("", ready().notice)
    }

    @Test
    fun aFileFromANewerAppIsNeverTouched() {
        val newer = """{"v":2,"who":"future me","towers":[]}"""
        main.writeText(newer)
        previous.writeText(HabitsCodec.encode(city))
        val load = file.load()
        assertEquals(HabitsLoad.Blocked(HabitsFile.NEWER), load)
        assertEquals(newer, main.readText())
        assertEquals(listOf("habits.json", "habits.prev.json"), dir.list()!!.sorted())
        // through the repository: the owner is told, and nothing they tap is written
        val repository =
            LocalHabitsRepository(file, TestClock()).apply {
                accept(HabitsScenarios.surroundings(), true)
            }
        assertEquals(HabitsFile.NEWER, repository.state.value!!.problem)
        assertTrue(repository.state.value!!.stacks.isEmpty())
        val reply = repository.addStack("After coffee")
        assertFalse(reply.ok)
        assertEquals(HabitsFile.NEWER, reply.message)
        assertEquals(newer, main.readText())
    }

    @Test
    fun aFileThatCannotBeReadIsKeptAsideAndTheSafetyCopyLoaded() {
        file.save(city)
        file.save(HabitsRules.who(city, "a reader")!!)
        main.writeText("{ this is not a habits file")
        val load = ready()
        assertEquals(city, load.save)
        assertEquals(
            "Your habits file could not be read, so the copy from just before your last change was loaded. The unreadable file was kept as habits.unreadable-1234.json.",
            load.notice,
        )
        assertEquals(
            "{ this is not a habits file",
            File(dir, "habits.unreadable-1234.json").readText(),
        )
        assertFalse(main.exists())
        // the next change writes a good file again, and the unreadable one stays where it was put
        assertTrue(file.save(load.save))
        assertEquals(city, ready().save)
        assertTrue(File(dir, "habits.unreadable-1234.json").exists())
    }

    @Test
    fun withNoSafetyCopyAnUnreadableFileIsKeptAndHabitsStartsEmpty() {
        main.writeText("""{"v":1,"who":"half a file""")
        val load = ready()
        assertEquals(HabitsSave(), load.save)
        assertEquals(
            "Your habits file could not be read. It was kept as habits.unreadable-1234.json, and Habits starts empty.",
            load.notice,
        )
        assertTrue(File(dir, "habits.unreadable-1234.json").exists())
        // a schema 1 document with a contradiction in it is unreadable too, not half read
        main.writeText(
            JSONObject(HabitsCodec.encode(city))
                .apply { getJSONArray("items").getJSONObject(0).put("stack", "nope") }
                .toString()
        )
        assertTrue(ready().notice.startsWith("Your habits file could not be read"))
    }

    @Test
    fun theRepositoryWritesEveryChangeAndTakesBackOneThatFails() {
        val clock = TestClock()
        val repository =
            LocalHabitsRepository(file, clock).apply {
                accept(HabitsScenarios.surroundings(), true)
            }
        val stack = repository.addStack("After coffee").id
        val habit = repository.addHabit(stack, false, "Read 10 pages", "Read 1 page", 0).id
        assertTrue(repository.lay(habit).ok)
        assertTrue(repository.state.value!!.stacks[0].blocks[0].done)
        val stored = HabitsCodec.decode(main.readText())
        assertEquals(1, stored.log.getValue(clock.today).getValue(habit))
        // the phone cannot write: the answer says so, and the screen's state stays as it was
        File(dir, "habits.json.tmp").mkdir()
        val before = repository.state.value
        val reply = repository.undo(habit)
        assertFalse(reply.ok)
        assertEquals(
            "Could not save your habits to this phone (storage full?). Nothing was changed.",
            reply.message,
        )
        assertEquals(before, repository.state.value)
        assertEquals(stored, HabitsCodec.decode(main.readText()))
        // a fresh start reads what was stored
        val again =
            LocalHabitsRepository(HabitsFile(dir), clock).apply {
                accept(HabitsScenarios.surroundings(), true)
            }
        assertEquals(before!!.stacks, again.state.value!!.stacks)
    }

    @Test
    fun theSurroundingsAreThePagesAndStrict() {
        val s = HabitsScenarios.surroundings().toHabitsSurroundings()
        assertEquals("habits-test", s.session)
        assertTrue(!s.light && !s.reducedMotion)
        assertEquals("Scholar", s.className)
        assertNull(s.haptics)
        assertEquals(17, s.tokens.size)
        val buzz =
            HabitsScenarios.surroundings()
                .put("haptics", JSONObject("""{"dm":1,"add":0,"amp":160,"min":4,"max":260}"""))
                .toHabitsSurroundings()
        assertEquals(160, buzz.haptics!!.amp)
        fun rejects(change: JSONObject.() -> Unit) {
            assertThrows(Exception::class.java) {
                HabitsScenarios.surroundings().apply(change).toHabitsSurroundings()
            }
        }
        rejects { put("protocol", 2) }
        rejects { put("v", 2) }
        rejects { put("webSessionId", " ") }
        rejects { put("sequence", 0) }
        rejects { remove("reducedMotion") }
        rejects { put("light", "yes") }
        rejects { remove("className") }
        // the developer flag is optional (an older page sends none) and strict when it is there
        assertFalse(HabitsScenarios.surroundings().toHabitsSurroundings().dev)
        assertTrue(HabitsScenarios.surroundings().put("dev", true).toHabitsSurroundings().dev)
        rejects { put("dev", "yes") }
        rejects { remove("haptics") }
        rejects { put("haptics", JSONObject("""{"dm":1,"add":0,"amp":999,"min":4,"max":260}""")) }
        rejects { getJSONObject("tokens").remove("accent") }
        rejects { remove("light") }
        // order: an older update is ignored, another page session is taken only on an open; a
        // rejected one changes nothing
        val repository =
            HabitsScenarios.repository(
                MemoryHabitsStorage(city),
                surroundings = HabitsScenarios.surroundings(sequence = 5),
            )
        repository.accept(HabitsScenarios.surroundings(sequence = 4, light = true))
        assertFalse(repository.state.value!!.light)
        repository.accept(
            HabitsScenarios.surroundings(sequence = 9, light = true).put("webSessionId", "other")
        )
        assertFalse(repository.state.value!!.light)
        assertThrows(Exception::class.java) {
            repository.accept(
                HabitsScenarios.surroundings(sequence = 6, light = true).put("light", "yes")
            )
        }
        assertFalse(repository.state.value!!.light)
        repository.accept(HabitsScenarios.surroundings(sequence = 6, light = true))
        assertTrue(repository.state.value!!.light)
        repository.accept(
            HabitsScenarios.surroundings(sequence = 1).put("webSessionId", "other"),
            true,
        )
        assertFalse(repository.state.value!!.light)
        // nothing is published before the surroundings arrive
        assertNull(LocalHabitsRepository(MemoryHabitsStorage(city), TestClock()).state.value)
    }
}
