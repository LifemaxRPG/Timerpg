package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.AnchorCatalog
import com.lifemax.rpg.feature.habits.domain.BreakCatalog
import com.lifemax.rpg.feature.habits.domain.DayPart
import com.lifemax.rpg.feature.habits.domain.HabitCatalog
import com.lifemax.rpg.feature.habits.domain.HabitsRules
import com.lifemax.rpg.feature.habits.domain.HabitsSuggest
import com.lifemax.rpg.feature.habits.domain.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogs behind the sheets' suggestions, and how what is typed finds an entry. */
class HabitsSuggestTest {
    private val limits = HabitsRules.LIMITS

    private fun anchors(typed: String, hour: Float = 14f, taken: List<String> = emptyList()) =
        HabitsSuggest.anchors(typed, hour, taken, limits.cue).map { it.title }

    private fun habits(typed: String, cue: String = "", taken: List<String> = emptyList()) =
        HabitsSuggest.habits(typed, cue, taken).map { it.title }

    private fun breaks(typed: String, cue: String = "", taken: List<String> = emptyList()) =
        HabitsSuggest.breaks(typed, cue, taken).map { it.title }

    private fun tidy(text: String, max: Int) {
        assertTrue("\"$text\" is empty", text.isNotBlank())
        assertTrue("\"$text\" is longer than $max", text.length <= max)
        assertEquals("\"$text\" is not tidy", HabitsRules.clean(text, max), text)
    }

    private fun keys(owner: String, keys: List<String>) {
        assertTrue("$owner has no other words", keys.isNotEmpty())
        keys.forEach {
            assertEquals("$owner: \"$it\" is not lower case", it.lowercase(), it)
            assertEquals("$owner: \"$it\" is not tidy", it.trim(), it)
            assertTrue("$owner: an empty word", it.isNotEmpty())
        }
        assertEquals("$owner repeats a word", keys.size, keys.toSet().size)
    }

    @Test
    fun everyAnchorFitsAFoundationAndIsThereOnce() {
        val all = AnchorCatalog.ALL
        assertTrue(all.size in 80..120)
        all.forEach {
            tidy(it.cue, limits.cue)
            keys(it.cue, it.keys)
            // said the way a foundation is
            assertTrue(it.cue, it.cue.split(' ').first() in WHEN)
        }
        assertEquals(all.size, all.map { HabitsSuggest.norm(it.cue) }.toSet().size)
        // every part of the day and every kind of moment has some
        assertEquals(DayPart.entries.toSet(), all.map { it.part }.toSet())
        assertEquals(Spot.entries.toSet(), all.map { it.spot }.toSet())
        // the owner's overnight shifts
        val cues = all.map { it.cue }
        assertTrue(
            cues.containsAll(
                listOf(
                    "Before my shift",
                    "On my break",
                    "After my shift",
                    "When I'm home from my shift",
                )
            )
        )
    }

    @Test
    fun everyHabitHasItsThreeSizesAndIsThereOnce() {
        val all = HabitCatalog.ALL
        assertTrue(all.size in 120..200)
        all.forEach { h ->
            listOf(h.name, h.small, h.stretch).forEach { tidy(it, limits.name) }
            // three sizes, three names
            assertEquals(h.name, 3, setOf(h.name, h.small, h.stretch).size)
            keys(h.name, h.keys)
            assertTrue(h.name, h.fits.isNotEmpty() && h.fits.size == h.fits.toSet().size)
        }
        assertEquals(all.size, all.map { HabitsSuggest.norm(it.name) }.toSet().size)
        // every kind of moment has habits that suit it
        assertEquals(Spot.entries.toSet(), all.flatMap { it.fits }.toSet())
    }

    @Test
    fun everyHabitToBreakHasItsDaysAndAReplacement() {
        val all = BreakCatalog.ALL
        assertTrue(all.size in 30..60)
        all.forEach { b ->
            tidy(b.name, limits.name)
            tidy(b.instead, limits.pair)
            assertTrue(b.name, b.days in limits.floorsMin..limits.floorsMax)
            keys(b.name, b.keys)
            assertTrue(b.name, b.fits.isNotEmpty())
        }
        assertEquals(all.size, all.map { HabitsSuggest.norm(it.name) }.toSet().size)
    }

    @Test
    fun whatIsTypedIsComparedWithoutCaseHyphensOrSpaces() {
        assertEquals("push ups", HabitsSuggest.norm("  Push-Ups! "))
        assertEquals("im home", HabitsSuggest.norm("I'm   home"))
        assertEquals("", HabitsSuggest.norm(" - "))
        val keys = listOf("pushups", "press-ups")
        // the start of any word of the entry itself
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("pu", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("PUSHUPS", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("push ups", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("Push-Up", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("do 10", "Do 10 push-ups", keys))
        // not the middle of a word
        assertNull(HabitsSuggest.rank("ush", "Do 10 push-ups", keys))
        // its other words
        assertEquals(HabitsSuggest.KEYWORD, HabitsSuggest.rank("press", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.KEYWORD, HabitsSuggest.rank("press ups", "Do 10 push-ups", keys))
        // one slip of the finger, from four letters on
        assertEquals(HabitsSuggest.FUZZY, HabitsSuggest.rank("pusj", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.FUZZY, HabitsSuggest.rank("puhs", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.FUZZY, HabitsSuggest.rank("pushhups", "Do 10 push-ups", keys))
        assertEquals(HabitsSuggest.FUZZY, HabitsSuggest.rank("prss", "Do 10 push-ups", keys))
        assertNull(HabitsSuggest.rank("pxs", "Do 10 push-ups", keys))
        assertNull(HabitsSuggest.rank("pxsj", "Do 10 push-ups", keys))
        // several words each find their own place, little words passed over
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("after shower", "After my shower"))
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("shower after", "After my shower"))
        assertEquals(HabitsSuggest.PREFIX, HabitsSuggest.rank("after the sho", "After my shower"))
        assertNull(HabitsSuggest.rank("after lunch", "After my shower"))
        assertNull(HabitsSuggest.rank("  ", "After my shower"))
        // one letter changed, missing, added or swapped, and no more
        assertTrue(HabitsSuggest.oneOff("shower", "shower"))
        assertTrue(HabitsSuggest.oneOff("shawer", "shower"))
        assertTrue(HabitsSuggest.oneOff("shwer", "shower"))
        assertTrue(HabitsSuggest.oneOff("showwer", "shower"))
        assertTrue(HabitsSuggest.oneOff("shwoer", "shower"))
        assertTrue(HabitsSuggest.oneOff("showe", "shower"))
        assertFalse(HabitsSuggest.oneOff("shawwer", "shower"))
        assertFalse(HabitsSuggest.oneOff("sohwre", "shower"))
        assertFalse(HabitsSuggest.oneOff("sh", "shower"))
    }

    @Test
    fun foundationsAreFoundAsTheyAreTyped() {
        // both sides of a moment
        assertEquals(listOf("After my shower", "Before my shower"), anchors("shower").take(2))
        assertTrue(anchors("show").containsAll(listOf("After my shower", "Before my shower")))
        assertEquals("After coffee", anchors("cof")[0])
        assertTrue(anchors("teeth").contains("After brushing my teeth"))
        // the owner's shifts
        assertTrue(anchors("shift").containsAll(listOf("Before my shift", "After my shift")))
        // an entry's own words before its other words, then the catalog's order
        val work = anchors("work")
        assertEquals("After a workout", work.first { it.contains("workout") })
        assertTrue(work.indexOf("Before leaving for work") < work.indexOf("After a workout"))
        // one slip of the finger still finds it
        assertTrue(anchors("shwoer").contains("After my shower"))
        assertTrue(anchors("brekfast").contains("After breakfast"))
        // never more than six, never one that is there already, whatever its case
        assertTrue(anchors("a").size == HabitsSuggest.MAX)
        assertFalse(
            anchors("shower", taken = listOf("after my SHOWER")).contains("After my shower")
        )
        // an activity the catalog does not know is offered both ways, the owner's words kept
        assertEquals(listOf("After yoga class", "Before yoga class"), anchors("Yoga class"))
        assertEquals(
            listOf("After band practice", "Before band practice"),
            anchors("band practice"),
        )
        // but not when it already says when, is too short, too long, or there already
        assertTrue(anchors("after yoga class").isEmpty())
        assertTrue(anchors("zz").isEmpty())
        assertTrue(anchors("a very long thing to do indeed").isEmpty())
        assertEquals(
            listOf("Before yoga class"),
            anchors("yoga class", taken = listOf("After yoga class")),
        )
        // the same typing gives the same answer
        assertEquals(anchors("mor"), anchors("mor"))
    }

    @Test
    fun withNothingTypedTheAnchorsOfTheHourComeFirst() {
        assertEquals(DayPart.MORNING, HabitsSuggest.dayPart(4f))
        assertEquals(DayPart.MORNING, HabitsSuggest.dayPart(10.9f))
        assertEquals(DayPart.DAY, HabitsSuggest.dayPart(11f))
        assertEquals(DayPart.EVENING, HabitsSuggest.dayPart(17f))
        assertEquals(DayPart.NIGHT, HabitsSuggest.dayPart(21f))
        assertEquals(DayPart.NIGHT, HabitsSuggest.dayPart(3.9f))
        val part = AnchorCatalog.ALL.associate { it.cue to it.part }
        val spot = AnchorCatalog.ALL.associate { it.cue to it.spot }
        for ((hour, now) in
            listOf(
                7f to DayPart.MORNING,
                14f to DayPart.DAY,
                19f to DayPart.EVENING,
                23f to DayPart.NIGHT,
                2f to DayPart.NIGHT,
            )) {
            val first = anchors("", hour)
            assertEquals(HabitsSuggest.MAX, first.size)
            assertTrue(first.all { part[it] == now })
            // a mix of moments, not six of a kind
            assertTrue(first.map { spot[it] }.toSet().size >= 4)
        }
        assertEquals("After waking up", anchors("", 7f)[0])
        assertEquals("Before bed", anchors("", 23f)[0])
        assertTrue(anchors("", 23f).contains("Before my shift"))
        // those in use make room for the next
        val without = anchors("", 7f, taken = listOf("After waking up", "after coffee"))
        assertEquals(HabitsSuggest.MAX, without.size)
        assertFalse(without.contains("After waking up") || without.contains("After coffee"))
    }

    @Test
    fun habitsAreFoundWithTheirSizes() {
        val push = HabitsSuggest.habits("pu", "", emptyList())[0]
        assertEquals("Do 10 push-ups", push.title)
        assertEquals(
            listOf("Do 10 push-ups", "Do 2 push-ups", "Do 3 sets of push-ups"),
            push.fields,
        )
        for (typed in
            listOf("pushups", "push ups", "press-ups", "Push-Up", "pushpus")) assertEquals(
            typed,
            "Do 10 push-ups",
            habits(typed)[0],
        )
        assertEquals("Read 10 pages", habits("read")[0])
        assertEquals(
            listOf("Read 10 pages", "Read 1 page", "Read 30 minutes"),
            HabitsSuggest.habits("read", "", emptyList())[0].fields,
        )
        assertTrue(habits("water").contains("Drink a glass of water"))
        assertTrue(
            habits("teeth").containsAll(listOf("Floss my teeth", "Brush my teeth 2 minutes"))
        )
        assertTrue(habits("medit").contains("Meditate 10 minutes"))
        assertTrue(habits("meditaton").contains("Meditate 10 minutes"))
        assertTrue(habits("gratitude").contains("Write 3 things I'm grateful for"))
        assertTrue(habits("language").contains("Do a language lesson"))
        assertTrue(habits("guitar").contains("Practise my instrument 15 min"))
        assertTrue(habits("cold").contains("Take a cold shower"))
        assertTrue(habits("bed").contains("Make the bed"))
        assertTrue(habits("qqqq").isEmpty())
        assertTrue(habits("re").size == HabitsSuggest.MAX)
        // a habit on the foundation is not offered again, by any of its sizes
        assertFalse(habits("read", taken = listOf("read 1 PAGE")).contains("Read 10 pages"))
        assertFalse(
            habits("", "After my shower", listOf("Floss my teeth")).contains("Floss my teeth")
        )
        // exact starts before other words before slips; the sure ones crowd the slips out
        val walk = habits("walk")
        assertEquals("Walk 20 minutes", walk[0])
        assertTrue(walk.indexOf("Walk 8,000 steps") < walk.indexOf("Walk the dog"))
    }

    @Test
    fun withNothingTypedTheHabitsThatSuitTheFoundationComeFirst() {
        assertEquals(Spot.WASH, HabitsSuggest.spotOf("After my shower"))
        assertEquals(Spot.WASH, HabitsSuggest.spotOf("after MY shower"))
        assertEquals(Spot.MEAL, HabitsSuggest.spotOf("After coffee"))
        assertEquals(Spot.BED, HabitsSuggest.spotOf("At lights out"))
        assertEquals(Spot.WORK, HabitsSuggest.spotOf("Before my shift"))
        // one of the owner's own that mentions a moment the catalog knows
        assertEquals(Spot.WASH, HabitsSuggest.spotOf("shower"))
        assertNull(HabitsSuggest.spotOf("After band practice"))
        assertNull(HabitsSuggest.spotOf(""))
        val wash = habits("", "After my shower")
        assertEquals(HabitsSuggest.MAX, wash.size)
        assertEquals("Floss my teeth", wash[0])
        val fits = HabitCatalog.ALL.associate { it.name to it.fits }
        assertTrue(wash.all { Spot.WASH in fits[it]!! })
        assertEquals("Be in bed by 11", habits("", "At lights out")[0])
        // a foundation the catalog knows nothing of: the well-worn ones
        assertEquals(
            listOf(
                "Read 10 pages",
                "Drink a glass of water",
                "Meditate 10 minutes",
                "Do 10 push-ups",
                "Journal 5 minutes",
                "Stretch 10 minutes",
            ),
            habits("", "After band practice"),
        )
    }

    @Test
    fun habitsToBreakComeWithTheirDaysAndAReplacement() {
        val phone = HabitsSuggest.breaks("ph", "", emptyList())[0]
        assertEquals("Phone in bed", phone.title)
        assertEquals(listOf("Phone in bed", "14", "A book on the pillow"), phone.fields)
        assertTrue(
            breaks("ph")
                .containsAll(listOf("Phone in bed", "Phone first thing", "Phone at the table"))
        )
        assertEquals("Hitting snooze", breaks("snooz")[0])
        assertEquals("Doomscrolling", breaks("doom")[0])
        assertTrue(breaks("soda").contains("Sugary drinks"))
        assertTrue(breaks("smok").contains("Smoking"))
        assertTrue(breaks("vape").contains("Vaping"))
        assertTrue(breaks("nails").contains("Biting my nails"))
        assertTrue(breaks("breakfast").contains("Skipping breakfast"))
        assertTrue(breaks("impulse").contains("Impulse buys"))
        assertTrue(breaks("snack").contains("Late-night snacking"))
        assertFalse(breaks("ph", taken = listOf("phone in bed")).contains("Phone in bed"))
        // nothing typed: what goes with the foundation first
        assertEquals("Phone in bed", breaks("", "At lights out")[0])
        assertEquals(HabitsSuggest.MAX, breaks("", "After band practice").size)
    }

    private companion object {
        val WHEN = setOf("After", "Before", "When", "At", "On", "While", "Halfway")
    }
}
