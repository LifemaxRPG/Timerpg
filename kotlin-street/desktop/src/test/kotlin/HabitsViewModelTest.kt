package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.HabitsEngine
import com.lifemax.rpg.feature.habits.domain.HabitsRules
import com.lifemax.rpg.feature.habits.domain.Suggestion
import com.lifemax.rpg.feature.habits.presentation.HabitsSheet
import com.lifemax.rpg.feature.habits.presentation.HabitsViewModel
import com.lifemax.rpg.feature.habits.presentation.SheetKind
import com.lifemax.rpg.feature.habits.presentation.sheetSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's flow over the real rules and an in-memory habits file: a tap changes what is stored
 * and the screen follows; a refusal or a write that failed is said in plain words, takes nothing
 * and keeps a sheet's draft.
 */
class HabitsViewModelTest {
    private val storage = MemoryHabitsStorage(HabitsScenarios.city())
    private val clock = TestClock()
    private val repository = HabitsScenarios.repository(storage, clock)
    private val vm = HabitsViewModel(repository, repository).apply { onProjection() }

    private val ui
        get() = vm.ui.value

    private val p
        get() = repository.state.value!!

    private fun start(name: String): HabitsViewModel {
        val r = HabitsScenarios.repository(MemoryHabitsStorage(HabitsScenarios.save(name)))
        return HabitsViewModel(r, r).apply { onProjection() }
    }

    @Test
    fun itStartsOnTheFirstStackWithABlockToLay() {
        assertEquals(p.stacks[0].id, ui.focus)
        assertNull(ui.selected)
        assertNull(ui.sheet)
        assertFalse(ui.city)
        // with the first stack done it starts on the next one; with nothing built, nowhere
        assertEquals(HabitsScenarios.claimed().stacks[1].id, start("claimed").ui.value.focus)
        assertNull(start("empty").ui.value.focus)
    }

    @Test
    fun theBigButtonLaysTheNextBlockAndPlaysIt() {
        val stretch = p.stacks[0].blocks[2].id
        vm.primary()
        assertTrue(p.block(stretch)!!.done)
        assertEquals(storage.stored, HabitsRules.lay(HabitsScenarios.city(), clock.today, stretch))
        assertEquals(true, ui.play?.fx?.laid)
        assertEquals(stretch, ui.play?.fx?.id)
        assertEquals(1, ui.combo)
        assertNull(ui.toast)
        val first = ui.play!!.id
        vm.played(first)
        assertNull(ui.play)
        // the next press lays the next block: one press, one block
        vm.primary()
        assertTrue(p.stacks[0].blocks.all { it.done })
        assertTrue(ui.play!!.id > first)
        assertEquals(2, ui.combo)
        // the stack is done: the button claims the reward, then goes on to the stack that still has
        // a block
        vm.primary()
        assertTrue(p.stacks[0].claimed)
        vm.primary()
        assertEquals(p.stacks[1].id, ui.focus)
        assertEquals(0, ui.combo)
        // there it knocks a floor off
        vm.primary()
        assertEquals(false, ui.play?.fx?.laid)
        assertEquals(8, p.stacks[1].blocks[0].left)
        // behind the page nothing plays, and it is not replayed
        vm.hidden()
        assertNull(ui.play)
    }

    @Test
    fun aBlockThatChangesMaterialSaysSo() {
        // nine reps behind a habit on the empty foundation: today's makes it clay
        val lunch = p.stacks[2].id
        val tea = repository.addHabit(lunch, false, "Tea, no phone", "Kettle on", 0).id
        var s = storage.stored
        for (i in 1..9) HabitsRules.addDays(clock.today, -i).let { d ->
            s = s.copy(log = s.log + (d to (s.log[d].orEmpty() + (tea to 1))))
        }
        val r = HabitsScenarios.repository(MemoryHabitsStorage(s), clock)
        val model = HabitsViewModel(r, r).apply { onProjection() }
        model.focus(lunch)
        model.primary()
        assertEquals(HabitMaterial.CLAY, model.ui.value.play?.fx?.upgrade)
        assertEquals("Tea, no phone is now clay.", model.ui.value.toast?.text)
        model.toastShown(model.ui.value.toast!!.id)
        assertNull(model.ui.value.toast)
    }

    @Test
    fun aWriteThatFailedIsTakenBackAndSaid() {
        val before = storage.stored
        storage.failing = true
        vm.primary()
        assertNull(ui.play)
        assertEquals(0, ui.combo)
        assertEquals(HabitsEngine.UNSAVED, ui.toast?.text)
        assertFalse(p.stacks[0].blocks[2].done)
        assertEquals(before, storage.stored)
        // the rough-day switch and a dial say it the same way and change nothing
        vm.toastShown(ui.toast!!.id)
        vm.rough()
        assertEquals(HabitsEngine.UNSAVED, ui.toast?.text)
        assertFalse(p.rough)
        vm.select(p.stacks[0].blocks[0].id)
        vm.toastShown(ui.toast!!.id)
        vm.size(0)
        assertEquals(HabitsEngine.UNSAVED, ui.toast?.text)
        assertEquals(2, p.stacks[0].blocks[0].set)
        assertEquals(SheetKind.TUNE, ui.sheet?.kind)
        // a sheet keeps its draft and says why on itself
        vm.closeSheet()
        vm.newStack()
        vm.type(0, "After dinner")
        vm.submit()
        assertEquals(
            HabitsSheet(
                SheetKind.NEW_STACK,
                fields = listOf("After dinner"),
                error = HabitsEngine.UNSAVED,
            ),
            ui.sheet,
        )
        assertEquals(3, p.stacks.size)
        // typing clears the words; once the phone can write, the same press lands
        vm.type(0, "After dinner!")
        assertEquals("", ui.sheet?.error)
        storage.failing = false
        val jump = ui.jump
        vm.submit()
        assertNull(ui.sheet)
        assertEquals("After dinner!", p.stacks[3].cue)
        assertEquals(p.stacks[3].id, ui.focus)
        assertEquals(jump + 1, ui.jump)
        vm.primary()
        assertTrue(ui.play == null && ui.sheet?.kind == SheetKind.NEW_HABIT)
    }

    @Test
    fun aTapOnABlockOpensItsDials() {
        val read = p.stacks[0].blocks[0].id
        vm.select(read)
        assertEquals(read, ui.selected)
        assertEquals(HabitsSheet(SheetKind.TUNE, read), ui.sheet)
        vm.size(1)
        assertEquals(1, p.block(read)!!.set)
        vm.move(1)
        assertEquals(read, p.stacks[0].blocks[1].id)
        vm.undo()
        assertFalse(p.block(read)!!.done)
        assertEquals(SheetKind.TUNE, ui.sheet?.kind)
        assertNull(ui.toast)
        // a tap on the same block puts the dials away
        vm.select(read)
        assertNull(ui.selected)
        assertNull(ui.sheet)
        // a refusal from a dial is a toast, and the dials stay
        val phone = p.stacks[1].blocks[0].id
        vm.select(phone)
        assertEquals(p.stacks[1].id, ui.focus)
        vm.size(1)
        assertEquals("This habit has no size.", ui.toast?.text)
        assertEquals(SheetKind.TUNE, ui.sheet?.kind)
        vm.select("nope")
        assertEquals(phone, ui.selected)
    }

    @Test
    fun namesAndPairingAreTypedAndComeBackToTheDials() {
        val read = p.stacks[0].blocks[0].id
        vm.select(read)
        vm.names()
        assertEquals(
            HabitsSheet(
                SheetKind.NAMES,
                read,
                listOf("Read 1 page", "Read 10 pages", "Read 30 min"),
            ),
            ui.sheet,
        )
        vm.type(1, " ")
        vm.submit()
        assertEquals("Give the habit a name.", ui.sheet?.error)
        assertEquals(" ", ui.sheet!!.fields[1])
        assertNull(ui.toast)
        vm.type(1, "Read a chapter")
        vm.submit()
        assertEquals(HabitsSheet(SheetKind.TUNE, read), ui.sheet)
        assertEquals("Read a chapter", p.block(read)!!.title)
        vm.pair()
        assertEquals(listOf("Morning coffee"), ui.sheet?.fields)
        vm.back()
        assertEquals(HabitsSheet(SheetKind.TUNE, read), ui.sheet)
        vm.pair()
        vm.type(0, "A candle")
        vm.submit()
        assertEquals("A candle", p.block(read)!!.pair)
        assertEquals(HabitsSheet(SheetKind.TUNE, read), ui.sheet)
        vm.back()
        assertNull(ui.sheet)
        assertNull(ui.selected)
    }

    @Test
    fun retireAndRestack() {
        val sit = p.stacks[0].blocks[3].id
        vm.select(sit)
        vm.restack()
        vm.restackTo(p.stacks[0].id) // where it stands already: back to the dials
        assertEquals(SheetKind.TUNE, ui.sheet?.kind)
        vm.restack()
        val jump = ui.jump
        val lunch = p.stacks[2].id
        vm.restackTo(lunch)
        assertNull(ui.sheet)
        assertEquals(lunch, ui.focus)
        assertEquals(jump + 1, ui.jump)
        assertEquals(listOf(sit), p.stacks[2].blocks.map { it.id })
        vm.select(sit)
        vm.retire()
        assertEquals(SheetKind.RETIRE, ui.sheet?.kind)
        vm.submit() // a confirmation has no main button
        assertNotNull(p.block(sit))
        vm.danger()
        assertNull(p.block(sit))
        assertNull(ui.sheet)
        assertNull(ui.selected)
    }

    @Test
    fun aNewHabitToBuildOrToBreak() {
        val lunch = p.stacks[2].id
        vm.focus(lunch)
        vm.primary() // an empty foundation: the button stacks the first habit
        assertEquals(HabitsSheet(SheetKind.NEW_HABIT, lunch, listOf("", "", "")), ui.sheet)
        vm.submit()
        assertEquals("Give the habit a name.", ui.sheet?.error)
        vm.type(0, "Floss")
        vm.type(1, "One tooth")
        // one to break keeps the name and counts clean days, starting from the usual number
        vm.kind(true)
        assertEquals(listOf("Floss", "14", ""), ui.sheet?.fields)
        assertTrue(ui.sheet!!.fade)
        vm.type(1, " 21 ")
        vm.type(2, "Chew gum")
        vm.submit()
        assertNull(ui.sheet)
        val added = p.stacks[2].blocks.single()
        assertTrue(added.fade && added.floors == 21 && added.title == "Floss")
        assertEquals("Chew gum", added.pair)
        vm.newHabit(lunch)
        vm.type(0, "Walk")
        vm.type(1, "Shoes on")
        vm.type(2, "Walk an hour")
        vm.submit()
        assertEquals("Shoes on", p.stacks[2].blocks[1].name)
        assertEquals(listOf("Shoes on", "Walk", "Walk an hour"), p.stacks[2].blocks[1].names)
        // the sizes left empty fall back to the habit's name
        vm.newHabit(lunch)
        vm.type(0, "Tea")
        vm.submit()
        assertEquals(listOf("Tea", "Tea", "Tea"), p.stacks[2].blocks[2].names)
        vm.newHabit("nope")
        assertNull(ui.sheet)
    }

    @Test
    fun aSuggestionFillsEveryFieldOfANewHabit() {
        val lunch = p.stacks[2].id
        vm.newHabit(lunch)
        // nothing typed: what suits the foundation (a meal) first
        val first = sheetSuggestions(ui.sheet!!, p)
        assertEquals(6, first.size)
        assertEquals("Walk 20 minutes", first[0].title)
        vm.type(0, "pu")
        val offers = sheetSuggestions(ui.sheet!!, p)
        assertEquals("Do 10 push-ups", offers[0].title)
        vm.suggest(offers[0])
        // every field is filled, the sheet stays for a look, and nothing more is offered
        assertEquals(
            listOf("Do 10 push-ups", "Do 2 push-ups", "Do 3 sets of push-ups"),
            ui.sheet?.fields,
        )
        assertTrue(ui.sheet!!.filled)
        assertTrue(sheetSuggestions(ui.sheet!!, p).isEmpty())
        // a size can still be changed without the suggestions coming back
        vm.type(2, "Do 50 push-ups")
        assertTrue(ui.sheet!!.filled)
        // typing in the name again brings them back
        vm.type(0, "Do 10 push")
        assertFalse(ui.sheet!!.filled)
        assertEquals("Do 10 push-ups", sheetSuggestions(ui.sheet!!, p)[0].title)
        vm.suggest(sheetSuggestions(ui.sheet!!, p)[0])
        vm.submit()
        assertNull(ui.sheet)
        val added = p.stacks[2].blocks.single()
        assertEquals(
            listOf("Do 2 push-ups", "Do 10 push-ups", "Do 3 sets of push-ups"),
            added.names,
        )
        // what stands on the foundation is not offered again
        vm.newHabit(lunch)
        vm.type(0, "push")
        assertTrue(sheetSuggestions(ui.sheet!!, p).none { it.title == "Do 10 push-ups" })
        // one to break: its clean days and what to do instead
        vm.kind(true)
        assertFalse(ui.sheet!!.filled)
        vm.type(0, "ph")
        val breaks = sheetSuggestions(ui.sheet!!, p)
        assertEquals("Phone in bed", breaks[0].title)
        vm.suggest(breaks[0])
        assertEquals(listOf("Phone in bed", "14", "A book on the pillow"), ui.sheet?.fields)
        // switching back to building clears what was for breaking
        vm.kind(false)
        assertEquals(listOf("Phone in bed", "", ""), ui.sheet?.fields)
        vm.kind(true)
        vm.suggest(breaks[0])
        vm.submit()
        val gone = p.stacks[2].blocks[1]
        assertTrue(gone.fade && gone.floors == 14 && gone.pair == "A book on the pillow")
        // a suggestion means nothing on another sheet
        vm.who()
        vm.suggest(Suggestion("x", listOf("x")))
        assertEquals(listOf(""), ui.sheet?.fields)
        assertTrue(sheetSuggestions(ui.sheet!!, p).isEmpty())
    }

    @Test
    fun whoTheFoundationSheetAndAStarter() {
        vm.who()
        assertEquals(HabitsSheet(SheetKind.WHO, fields = listOf("")), ui.sheet)
        vm.type(0, "a calm reader")
        vm.submit()
        assertNull(ui.sheet)
        assertEquals("a calm reader", p.who)
        val lunch = p.stacks[2].id
        vm.editStack(lunch)
        vm.type(1, "Coffee")
        vm.submit()
        assertEquals("Coffee", p.stack(lunch)!!.treat)
        // a reward that cannot be claimed opens its foundation's sheet, where it is named
        vm.reward(p.stacks[0].id)
        assertEquals(
            HabitsSheet(
                SheetKind.EDIT_STACK,
                p.stacks[0].id,
                listOf("After coffee", "One episode"),
            ),
            ui.sheet,
        )
        // removing a foundation that still carries habits is refused on the sheet
        vm.danger()
        assertEquals("Retire its habits first.", ui.sheet?.error)
        vm.editStack(lunch)
        vm.danger()
        assertNull(ui.sheet)
        assertNull(p.stack(lunch))
        // a suggested foundation is laid at once and comes in front; at two in the afternoon
        // the day's anchors come first, and those in use are left out
        vm.newStack()
        val starters = sheetSuggestions(ui.sheet!!, p)
        assertEquals(6, starters.size)
        assertEquals("When I sit down at my desk", starters[0].title)
        assertTrue(starters.none { it.title == "After coffee" || it.title == "At lights out" })
        vm.type(0, "show")
        val showers = sheetSuggestions(ui.sheet!!, p).map { it.title }
        assertTrue(showers.containsAll(listOf("After my shower", "Before my shower")))
        vm.suggest(sheetSuggestions(ui.sheet!!, p)[0])
        assertNull(ui.sheet)
        assertEquals("After my shower", p.stack(ui.focus)!!.cue)
        vm.newStack()
        vm.type(0, "shower")
        assertTrue(sheetSuggestions(ui.sheet!!, p).none { it.title == "After my shower" })
    }

    @Test
    fun theCityAndItsTimeLapse() {
        vm.select(p.stacks[0].blocks[0].id)
        vm.city(true)
        assertTrue(ui.city)
        assertNull(ui.sheet)
        assertNull(ui.selected)
        vm.lapse(5)
        assertEquals(5, ui.weeks)
        vm.lapse(99)
        assertEquals(12, ui.weeks)
        vm.lapse(-3)
        assertEquals(0, ui.weeks)
        vm.lapse(4)
        // a tap on a tower comes back to today, with it in front
        val jump = ui.jump
        vm.focus(p.stacks[1].id)
        assertFalse(ui.city)
        assertEquals(0, ui.weeks)
        assertEquals(p.stacks[1].id, ui.focus)
        assertEquals(jump + 1, ui.jump)
        // the time-lapse is the city's only
        vm.lapse(3)
        assertEquals(0, ui.weeks)
        // a swipe that already brought a tower in front does not ask the towers to travel
        vm.focus(p.stacks[0].id, byFinger = true)
        assertEquals(jump + 1, ui.jump)
        // with nothing built there is no city, and the button opens the first foundation's sheet
        val empty = start("empty")
        empty.city(true)
        assertFalse(empty.ui.value.city)
        empty.primary()
        assertEquals(SheetKind.NEW_STACK, empty.ui.value.sheet?.kind)
    }
}
