package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.HabitFx
import com.lifemax.rpg.feature.habits.domain.HabitKind
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.HabitsEngine
import com.lifemax.rpg.feature.habits.domain.HabitsLoad
import com.lifemax.rpg.feature.habits.domain.HabitsRules
import com.lifemax.rpg.feature.habits.domain.HabitsSave
import com.lifemax.rpg.feature.habits.domain.PrimaryAction
import com.lifemax.rpg.feature.habits.domain.primaryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The habits' rules and commands on the phone's own days: every check of the model the page's test
 * made (tests/habits.test.js, now gone with the page's data), against the Kotlin rules.
 */
class HabitsRulesTest {
    private val storage = MemoryHabitsStorage()
    private val clock = TestClock()
    private val engine = HabitsEngine(storage) { clock.today }

    private fun stacks() = engine.day().stacks

    private fun block(id: String) = stacks().flatMap { it.blocks }.first { it.id == id }

    /**
     * After coffee: Read 10 pages (small: Read 1 page), Journal 5 min. At lights out: Phone in bed,
     * 3 floors.
     */
    private class Ids(val s: String, val r: String, val j: String, val e: String, val f: String)

    private fun build(): Ids {
        val s = engine.addStack("After coffee").id
        val r = engine.addHabit(s, false, "Read 10 pages", "Read 1 page", 0).id
        val j = engine.addHabit(s, false, "Journal 5 min", "", 0).id
        val e = engine.addStack("At lights out").id
        val f = engine.addHabit(e, true, "Phone in bed", "", 3).id
        return Ids(s, r, j, e, f)
    }

    @Test
    fun nothingYetIsEmptyAndWritesNothing() {
        val d = engine.day()
        assertTrue(d.stacks.isEmpty() && d.live && d.laid == 0 && d.built == 0 && d.due == 0)
        assertEquals(HabitsSave(), engine.save)
        assertEquals(0, storage.writes)
        assertEquals(PrimaryAction.FirstFoundation, primaryAction(d.stacks, null))
    }

    @Test
    fun aFoundationAndItsHabitsAreStored() {
        val ids = build()
        assertEquals(
            listOf("s1", "h2", "h3", "s4", "h5"),
            listOf(ids.s, ids.r, ids.j, ids.e, ids.f),
        )
        assertEquals(engine.save, storage.stored)
        assertEquals(3, storage.stored.items.size)
        assertEquals(5, storage.stored.n)
        // a new habit starts at its two-minute size, glass, nothing laid
        val first = stacks()[0].blocks[0]
        assertEquals("Read 1 page", first.name)
        assertEquals(HabitMaterial.GLASS, first.material)
        assertEquals(0 to 0, first.size to first.set)
        assertEquals(0, engine.day().laid)
        assertEquals(ids.r, stacks()[0].next)
        assertEquals(clock.today, engine.save.items[0].born)
        // one to break starts with its floors and no size of its own
        val phone = block(ids.f)
        assertEquals(HabitKind.FADE, phone.kind)
        assertEquals(3 to 3, phone.left to phone.floors)
        assertEquals(listOf("Phone in bed", "Phone in bed", "Phone in bed"), phone.names)
    }

    @Test
    fun emptyNamesAndUnknownStacksAreRefused() {
        val ids = build()
        val before = engine.save
        assertFalse(engine.addStack("   ").ok)
        assertEquals("Say when: something you already do every day.", engine.addStack(" ").message)
        assertFalse(engine.addHabit("nope", false, "x", "", 0).ok)
        assertEquals("Give the habit a name.", engine.addHabit(ids.s, false, "  ", "", 0).message)
        assertEquals(before, engine.save)
        // what is typed is tidied and held to its length
        assertEquals(
            "After my morning run and sho",
            HabitsRules.clean("  After   my morning\nrun and shower  ", 28),
        )
        val long = engine.addStack("x".repeat(60)).id
        assertEquals(28, stacks().first { it.id == long }.cue.length)
        // floors are held to the limits, and none given means the usual fourteen
        assertEquals(60, block(engine.addHabit(ids.e, true, "A", "", 99).id).floors)
        assertEquals(3, block(engine.addHabit(ids.e, true, "B", "", 1).id).floors)
        assertEquals(14, block(engine.addHabit(ids.e, true, "C", "", 0).id).floors)
    }

    @Test
    fun layingABlockCountsOnceADayAndUndoTakesItBack() {
        val ids = build()
        val laid = engine.lay(ids.r)
        assertTrue(laid.ok && laid.changed)
        assertEquals(HabitFx(true, ids.r, null, false), laid.fx)
        val again = engine.lay(ids.r)
        assertTrue(again.ok && !again.changed)
        assertEquals("Already laid today.", again.message)
        assertEquals(1, block(ids.r).reps)
        assertTrue(block(ids.r).done)
        assertEquals(1 to 2, engine.day().built to engine.day().due)
        assertTrue(engine.undo(ids.r).changed)
        assertEquals(0, block(ids.r).reps)
        assertTrue(engine.save.log.isEmpty())
        assertFalse(engine.undo(ids.r).changed)
        assertTrue(engine.lay(ids.r).changed)
        assertFalse(engine.lay("nope").ok)
    }

    @Test
    fun missedDaysCrackThenWobbleAndNeverTakeARepAway() {
        val ids = build()
        engine.lay(ids.r)
        clock.tomorrow()
        // a new day: nothing laid, the rep kept; the one not done yesterday is cracked
        assertEquals(0, engine.day().laid)
        assertEquals(1, block(ids.r).reps)
        assertFalse(block(ids.r).done)
        assertEquals(0, block(ids.r).miss)
        assertEquals(1, block(ids.j).miss)
        clock.tomorrow()
        assertEquals(1, block(ids.r).miss)
        assertEquals(1, block(ids.r).reps)
        assertEquals(2, block(ids.j).miss)
        // coming back leaves a gold seam, which stays
        assertFalse(block(ids.j).scar)
        assertTrue(engine.lay(ids.j).ok)
        assertTrue(block(ids.j).scar && block(ids.j).miss == 0)
        clock.tomorrow()
        engine.lay(ids.j)
        assertTrue(block(ids.j).scar)
        // a habit is not missed before its first day, and one being broken never cracks
        assertEquals(0, block(ids.f).miss)
        assertEquals(
            0,
            HabitsRules.day(engine.save, clock.today, HabitsRules.addDays(clock.today, -30))
                .stacks[0]
                .blocks
                .size,
        )
    }

    @Test
    fun aBlockMovesEarlierOrLaterWithinItsStack() {
        val ids = build()
        val order = { stacks()[0].blocks.map { it.id } }
        assertTrue(engine.move(ids.j, -1).changed)
        assertEquals(listOf(ids.j, ids.r), order())
        assertFalse(engine.move(ids.j, -1).changed)
        assertTrue(engine.move(ids.j, 1).changed)
        assertEquals(listOf(ids.r, ids.j), order())
        assertFalse(engine.move(ids.j, 1).changed)
        // the other stack's habit is left where it is
        assertEquals(listOf(ids.f), stacks()[1].blocks.map { it.id })
        // onto another foundation it goes last
        assertTrue(engine.restack(ids.r, ids.e).changed)
        assertEquals(listOf(ids.f, ids.r), stacks()[1].blocks.map { it.id })
        assertFalse(engine.restack(ids.r, ids.e).changed)
        assertFalse(engine.restack(ids.r, "nope").ok)
    }

    @Test
    fun sizeIsTheHabitsOwnAndARoughDayLaysTheSmallOne() {
        val ids = build()
        assertTrue(engine.names(ids.r, listOf("Read 1 page", "Read 10 pages", "Read 30 min")).ok)
        assertTrue(engine.size(ids.r, 2).changed)
        assertEquals("Read 30 min", block(ids.r).name)
        assertTrue(engine.rough(true).changed)
        assertFalse(engine.rough(true).changed)
        assertTrue(engine.day().rough)
        assertEquals("Read 1 page", block(ids.r).name)
        engine.lay(ids.r)
        assertEquals(1, engine.save.log.getValue(clock.today).getValue(ids.r))
        assertTrue(engine.rough(false).changed)
        // the habit's own size is untouched, and today's block keeps the size it was laid at
        assertEquals(2, engine.save.items.first { it.id == ids.r }.size)
        assertEquals(0 to 2, block(ids.r).size to block(ids.r).set)
        assertEquals("Read 1 page", block(ids.r).name)
        // tomorrow the rough day is over by itself
        engine.rough(true)
        clock.tomorrow()
        assertFalse(engine.day().rough)
        assertEquals("Read 30 min", block(ids.r).name)
        // a size out of range, or for a habit being broken, is refused; names need the normal one
        assertFalse(engine.size(ids.r, 3).ok)
        assertEquals("This habit has no size.", engine.size(ids.f, 1).message)
        assertEquals("Give the habit a name.", engine.names(ids.r, listOf("a", " ", "c")).message)
        engine.names(ids.j, listOf("", "Journal", ""))
        assertEquals(listOf("Journal", "Journal", "Journal"), block(ids.j).names)
        assertTrue(engine.pair(ids.r, "  My  playlist ").changed)
        assertEquals("My playlist", block(ids.r).pair)
        assertFalse(engine.pair(ids.r, "My playlist").changed)
    }

    @Test
    fun theMaterialFollowsTotalReps() {
        assertEquals(
            listOf(
                HabitMaterial.GLASS,
                HabitMaterial.CLAY,
                HabitMaterial.CLAY,
                HabitMaterial.STONE,
                HabitMaterial.STONE,
                HabitMaterial.GOLD,
            ),
            listOf(9, 10, 29, 30, 65, 66).map(HabitsRules::materialAt),
        )
        // seventy days laid, ending six days ago: the block as it stood on the way
        val city = HabitsScenarios.city()
        val read = city.items[0].id
        val at = { daysAgo: Int ->
            HabitsRules.day(
                    city,
                    HabitsScenarios.TODAY,
                    HabitsRules.addDays(HabitsScenarios.TODAY, -daysAgo),
                )
                .stacks[0]
                .blocks
                .first { it.id == read }
        }
        assertEquals(HabitMaterial.GLASS, at(68).material)
        assertEquals(HabitMaterial.CLAY, at(60).material)
        assertEquals(HabitMaterial.STONE, at(40).material)
        assertEquals(HabitMaterial.GOLD, at(0).material)
        assertEquals(71, at(0).reps)
        // the lay that crosses a line says so, and tells the screen what the block turned into
        val storage = MemoryHabitsStorage(HabitsScenarios.city())
        val e = HabitsEngine(storage) { HabitsScenarios.TODAY }
        val tea = e.addHabit(e.save.stacks[2].id, false, "Tea, no phone", "Kettle on", 0).id
        var s = e.save
        for (i in 1..9) s =
            s.copy(
                log =
                    s.log +
                        (HabitsRules.addDays(HabitsScenarios.TODAY, -i) to
                            (s.log[HabitsRules.addDays(HabitsScenarios.TODAY, -i)].orEmpty() +
                                (tea to 1)))
            )
        val e2 = HabitsEngine(MemoryHabitsStorage(s)) { HabitsScenarios.TODAY }
        val laid = e2.lay(tea)
        assertEquals("Tea, no phone is now clay.", laid.message)
        assertEquals(HabitFx(true, tea, HabitMaterial.CLAY, false), laid.fx)
    }

    @Test
    fun theRooftopRewardWaitsForTheWholeStackAndIsClaimedOnceADay() {
        val ids = build()
        engine.editStack(ids.s, "After coffee", "One episode")
        assertEquals("One episode", stacks()[0].treat)
        assertEquals("Finish the stack first.", engine.claim(ids.s).message)
        engine.lay(ids.r)
        assertFalse(stacks()[0].claimable)
        engine.lay(ids.j)
        assertTrue(stacks()[0].claimable && stacks()[0].next.isEmpty())
        assertTrue(engine.claim(ids.s).changed)
        assertTrue(stacks()[0].claimed && !stacks()[0].claimable)
        val again = engine.claim(ids.s)
        assertTrue(again.ok && !again.changed)
        assertEquals("Already enjoyed today.", again.message)
        // tomorrow it is locked again until the stack is done
        clock.tomorrow()
        assertFalse(stacks()[0].claimed || stacks()[0].claimable)
        assertFalse(engine.claim(ids.s).ok)
        // a stack with no reward, or no habits, has nothing to claim
        assertFalse(engine.claim(ids.e).ok)
        assertFalse(engine.claim("nope").ok)
        // an empty cue keeps the old one; an empty reward clears it
        assertFalse(engine.editStack(ids.s, " ", "x").ok)
        engine.editStack(ids.s, "After tea", "")
        assertEquals("After tea" to "", stacks()[0].cue to stacks()[0].treat)
    }

    @Test
    fun aHabitToBreakLosesAFloorPerCleanDayAndEndsCleared() {
        val ids = build()
        // a clean day is not a block laid: it counts in laid, not in built of due
        val before = engine.day()
        val first = engine.lay(ids.f)
        assertEquals(HabitFx(false, ids.f, null, false), first.fx)
        assertEquals(before.laid + 1, engine.day().laid)
        assertEquals(before.built to 2, engine.day().built to engine.day().due)
        assertEquals(2, engine.save.log.getValue(clock.today).getValue(ids.f))
        assertEquals(2, block(ids.f).left)
        clock.tomorrow()
        engine.lay(ids.f)
        assertEquals(1 to false, block(ids.f).left to block(ids.f).cleared)
        clock.tomorrow()
        val last = engine.lay(ids.f)
        assertEquals("Phone in bed is gone. A tree stands where it was.", last.message)
        assertTrue(last.fx!!.cleared)
        assertEquals(0 to true, block(ids.f).left to block(ids.f).cleared)
        assertEquals("", stacks()[1].next)
        // gone is gone: nothing more to lay
        clock.tomorrow()
        assertFalse(engine.lay(ids.f).changed)
        assertEquals(3, block(ids.f).reps)
    }

    @Test
    fun aWriteThatFailsTakesTheChangeBackAndSaysSo() {
        val ids = build()
        val before = engine.save
        val writes = storage.writes
        storage.failing = true
        val replies =
            listOf(
                engine.addStack("After lunch"),
                engine.lay(ids.r),
                engine.who("a reader"),
                engine.rough(true),
                engine.size(ids.r, 2),
                engine.retire(ids.j),
                engine.editStack(ids.s, "After tea", "Coffee"),
            )
        assertTrue(
            replies.all {
                !it.ok &&
                    !it.changed &&
                    it.message == HabitsEngine.UNSAVED &&
                    it.fx == null &&
                    it.id.isEmpty()
            }
        )
        // nothing changed in memory or in the file
        assertEquals(before, engine.save)
        assertEquals(before, storage.stored)
        assertEquals(writes, storage.writes)
        assertFalse(block(ids.r).done)
        // once the phone can write again the same change lands
        storage.failing = false
        assertTrue(engine.lay(ids.r).changed)
        assertEquals(engine.save, storage.stored)
    }

    @Test
    fun retiringAndRemoving() {
        val ids = build()
        engine.lay(ids.j)
        engine.lay(ids.r)
        assertTrue(engine.retire(ids.j).changed)
        assertTrue(engine.save.items.none { it.id == ids.j })
        assertTrue(engine.save.log.values.none { ids.j in it })
        assertEquals(1, engine.day().laid)
        assertEquals("Already removed.", engine.retire(ids.j).message)
        // a foundation with habits is not removed; an empty one is
        assertEquals("Retire its habits first.", engine.removeStack(ids.s).message)
        engine.retire(ids.f)
        assertTrue(stacks()[1].removable)
        assertTrue(engine.removeStack(ids.e).changed)
        assertEquals(listOf(ids.s), stacks().map { it.id })
        assertFalse(engine.removeStack(ids.e).changed)
        // ids are never given twice
        assertEquals("s6", engine.addStack("After lunch").id)
    }

    @Test
    fun whoIsStoredTidied() {
        assertTrue(engine.who("  an athlete   who reads ").changed)
        assertEquals("an athlete who reads", engine.day().who)
        assertFalse(engine.who("an athlete who reads").changed)
        assertEquals(
            40,
            HabitsEngine(MemoryHabitsStorage()) { clock.today }
                .also { it.who("y".repeat(90)) }
                .day()
                .who
                .length,
        )
    }

    @Test
    fun theDayIsThePhonesLocalDayReadAfresh() {
        val ids = build()
        engine.lay(ids.r)
        val day = clock.today
        // just before midnight is still today; a minute later is tomorrow, with nothing laid
        clock.now = clock.now.withHour(23).withMinute(59)
        assertTrue(block(ids.r).done)
        clock.now = clock.now.plusMinutes(1)
        assertEquals(HabitsRules.addDays(day, 1), engine.day().day)
        assertFalse(block(ids.r).done)
        // the clock set back: yesterday's block is laid again, as it was
        clock.now = clock.now.minusDays(1)
        assertTrue(block(ids.r).done)
        assertEquals("2026-03-01", HabitsRules.addDays("2026-03-02", -1))
        assertEquals("2024-02-29", HabitsRules.addDays("2024-03-01", -1))
    }

    @Test
    fun aFileThatCannotBeUsedBlocksEveryChange() {
        val blocked =
            HabitsEngine(MemoryHabitsStorage(opened = HabitsLoad.Blocked("Made by a newer app."))) {
                clock.today
            }
        assertEquals("Made by a newer app.", blocked.problem)
        val reply = blocked.addStack("After coffee")
        assertFalse(reply.ok)
        assertEquals("Made by a newer app.", reply.message)
        assertTrue(blocked.day().stacks.isEmpty())
        // a file that was replaced by its safety copy works, and says what happened
        val noticed =
            HabitsEngine(
                MemoryHabitsStorage(
                    opened = HabitsLoad.Ready(HabitsScenarios.city(), "An older copy was loaded.")
                )
            ) {
                HabitsScenarios.TODAY
            }
        assertEquals("An older copy was loaded.", noticed.problem)
        assertEquals(3, noticed.day().stacks.size)
        assertNull(noticed.lay("nope").fx)
    }

    @Test
    fun theProjectionCarriesTodayAndTwelveWeeksOfThePast() {
        val p = HabitsScenarios.projection("city")
        assertEquals(HabitsScenarios.TODAY, p.day)
        assertEquals(14f, p.hour, 0f)
        assertEquals(2 to 5, p.built to p.due)
        assertEquals("Scholar", p.whoHint)
        assertEquals("", p.problem)
        assertEquals(HabitsRules.LIMITS, p.limits)
        assertEquals(HabitsRules.RULES, p.rules)
        assertEquals((1..12).toList(), p.lapse.map { it.weeks })
        assertEquals("2026-02-23", p.lapse[0].day)
        assertEquals(p.stacks, p.towersAt(0))
        val week = p.towersAt(1)
        assertTrue(week[0].blocks[0].reps < p.stacks[0].blocks[0].reps)
        assertTrue(
            week.all { s ->
                s.next.isEmpty() &&
                    s.treat.isEmpty() &&
                    s.blocks.all { it.done && it.miss == 0 && !it.scar }
            }
        )
        // four weeks ago the gold block was stone; twelve weeks ago glass, and the newer habits
        // were not there
        assertEquals(HabitMaterial.STONE, p.towersAt(4)[0].blocks[0].material)
        assertEquals(HabitMaterial.GLASS, p.towersAt(12)[0].blocks[0].material)
        assertTrue(p.towersAt(12)[0].blocks.size < p.stacks[0].blocks.size)
        assertEquals(p.stacks, p.towersAt(40))
        // the big button follows the stack in front
        assertEquals(
            PrimaryAction.Lay(p.stacks[0].blocks[2]),
            primaryAction(p.stacks, p.stacks[0].id),
        )
        assertEquals(
            PrimaryAction.Knock(p.stacks[1].blocks[0]),
            primaryAction(p.stacks, p.stacks[1].id),
        )
        assertEquals(PrimaryAction.FirstHabit(p.stacks[2]), primaryAction(p.stacks, p.stacks[2].id))
        val done = HabitsScenarios.projection("done")
        assertEquals(
            PrimaryAction.Claim(done.stacks[0]),
            primaryAction(done.stacks, done.stacks[0].id),
        )
        val claimed = HabitsScenarios.projection("claimed")
        assertEquals(
            PrimaryAction.GoTo(claimed.stacks[1]),
            primaryAction(claimed.stacks, claimed.stacks[0].id),
        )
        // a rough day: what is not laid yet shrinks, what was laid keeps its size
        val rough = HabitsScenarios.projection("rough")
        assertEquals("Touch toes", rough.stacks[0].blocks[2].name)
        assertEquals(2, rough.stacks[0].blocks[0].size)
    }
}
