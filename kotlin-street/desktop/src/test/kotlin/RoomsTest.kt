package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Activity
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.domain.PeopleActivities
import com.lifemax.rpg.feature.habits.domain.RoomKind
import com.lifemax.rpg.feature.habits.domain.Silhouettes
import com.lifemax.rpg.feature.habits.presentation.scene.PeopleSprites
import com.lifemax.rpg.feature.habits.presentation.scene.Room
import com.lifemax.rpg.feature.habits.presentation.scene.RoomSprites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rooms behind the windows: the painted layers of every kind and variant, how a floor's rooms
 * are spread, which activities go in which room, and where the people stand in them.
 */
class RoomsTest {
    private val heights: List<Float>
        get() =
            BuildingSpec.HEIGHTS.map { h ->
                val rows = if (h >= BuildingSpec.TWO_ROWS) 2 else 1
                (h - BuildingSpec.BAND_TOP - BuildingSpec.PLAQUE_BAND - 6f * (rows - 1)) / rows
            }

    private fun opaque(r: Room) = r.base.pixels.count { (it ushr 24) == 255 }

    @Test
    fun everyKindAndVariantIsPaintedTheSameEveryTimeAndFillsItsWindow() {
        for (kind in RoomKind.entries) for (v in 0 until RoomSprites.VARIANTS) {
            val a = RoomSprites.render(kind, v, 27.5f)
            val b = RoomSprites.render(kind, v, 27.5f)
            assertTrue(a.base.pixels.contentEquals(b.base.pixels))
            assertTrue(a.lamp.pixels.contentEquals(b.lamp.pixels))
            assertTrue(a.front.pixels.contentEquals(b.front.pixels))
            // the unlit room covers the whole pane: walls and floor behind everything
            assertEquals(a.base.w * a.base.h, a.base.pixels.size)
            assertEquals("$kind $v", a.base.w * a.base.h, opaque(a))
            // the lamp's light is soft: nothing in it is a hard shape but the shade's core
            val hard = a.lamp.pixels.count { (it ushr 24) > 240 }
            assertTrue("$kind $v lamp hard $hard", hard < a.lamp.pixels.size / 12)
            // something stands in front of the person only in the rooms that have a desk, a
            // counter, a sink, a piano or a table
            val front = a.front.pixels.count { (it ushr 24) > 40 }
            val hasFront =
                kind in
                    setOf(
                        RoomKind.OFFICE,
                        RoomKind.DESK,
                        RoomKind.KITCHEN,
                        RoomKind.BATH,
                        RoomKind.PIANO,
                        RoomKind.DINING,
                        RoomKind.BOARD,
                    )
            assertEquals("$kind front", hasFront, front > 100)
            if (RoomSprites.hasScreen(kind))
                assertTrue(a.screen!!.pixels.any { (it ushr 24) > 100 })
            else assertNull(a.screen)
        }
    }

    @Test
    fun theVariantsOfAKindDifferAndTheLitRoomHasTheGeometryOfTheUnlitOne() {
        for (kind in RoomKind.entries) {
            val rooms = (0 until RoomSprites.VARIANTS).map { RoomSprites.render(kind, it, 27.5f) }
            for (i in rooms.indices) for (j in i + 1 until rooms.size) {
                val a = rooms[i].base.pixels
                val b = rooms[j].base.pixels
                var differ = 0
                for (k in a.indices) if (a[k] != b[k]) differ++
                assertTrue("$kind $i vs $j", differ > a.size / 5)
            }
            // the lamp adds light only: the lit room is the unlit one with the lamp layer over it,
            // so the lamp layer has no pixel that is not light (every one is warm and pale)
            for (r in rooms) for (p in r.lamp.pixels) if ((p ushr 24) > 30) {
                val red = (p shr 16) and 255
                val blue = p and 255
                assertTrue(red >= 200 && red >= blue)
            }
        }
    }

    @Test
    fun theRoomsStayWithinTheirBudgetAtEveryWindowHeight() {
        var total = 0
        for (h in heights) for (kind in RoomKind.entries) for (v in 0 until RoomSprites.VARIANTS) {
            val r = RoomSprites.render(kind, v, h)
            val bytes =
                (r.base.w * r.base.h +
                    r.front.w * r.front.h +
                    r.lamp.w * r.lamp.h +
                    (r.screen?.let { it.w * it.h } ?: 0)) * 4
            assertTrue("$kind $v $h", bytes <= RoomSprites.bytes(h))
            total += bytes
        }
        // every kind in every variant at every height: 135 rooms, made only as they are seen
        assertTrue("rooms $total", total < 4 * 1024 * 1024)
        // the floor line is above the people's feet and inside the pane
        for (h in heights) for (kind in RoomKind.entries) {
            val line = RoomSprites.floorLine(h, kind)
            assertTrue(line < h && line > h * .8f)
        }
    }

    // ------------------------------------------------------------------ the spread

    private val titles =
        listOf("Cook dinner", "Read 10 pages", "Glass of water", "Brush teeth", "Zzz", "Journal")

    private fun floor(title: String, seed: Int, lit: Int = 6) =
        Silhouettes.cast(
            title,
            "k$seed/1",
            lit,
            IntArray(lit) { it and 1 },
            IntArray(lit) { it },
            IntArray(lit) { 0 },
        )

    @Test
    fun noTwoWindowsSideBySideLookIntoTheSameKindOfRoom() {
        for (title in titles) for (seed in 0 until 60) {
            val cast = floor(title, seed)
            for (k in 1 until cast.size) assertNotEquals(
                "$title $seed window $k",
                cast[k - 1].room,
                cast[k].room,
            )
            // and the variants rotate, so neighbours of one kind would still differ
            for (k in 1 until cast.size) assertNotEquals(cast[k - 1].variant, cast[k].variant)
        }
    }

    @Test
    fun aCookingHabitHasKitchensInAtMostHalfItsWindowsAndHomesInTheRest() {
        for (seed in 0 until 60) {
            val cast = floor("Cook dinner", seed)
            // the first window does the habit (cooking, or pouring a glass) in its kitchen
            assertTrue(cast[0].activity in Silhouettes.activitiesOf("Cook dinner"))
            assertEquals(RoomKind.KITCHEN, cast[0].room)
            assertTrue(cast.count { it.room == RoomKind.KITCHEN } <= 3)
            // whoever is not in a kitchen is at home doing something a home has a room for
            for (c in cast) assertTrue(c.room in PeopleActivities.roomsOf(c.activity))
        }
        // a floor of two windows: the first is the habit's own room, the second another kind
        for (seed in 0 until 30) {
            val two = floor("Cold shower", seed, 2)
            assertEquals(PeopleActivities.of(two[0].activity).room, two[0].room)
            assertNotEquals(two[0].room, two[1].room)
        }
    }

    @Test
    fun theRoomsOfAFloorAreSpreadByWhereTheWindowsAreNotByTheirOrder() {
        // two windows one above the other (same column) count as neighbours
        for (seed in 0 until 40) {
            val cast =
                Silhouettes.cast(
                    "Read 10 pages",
                    "s$seed/0",
                    4,
                    intArrayOf(0, 1, 1, 0),
                    intArrayOf(0, 0, 3, 3),
                    intArrayOf(0, 1, 0, 1),
                )
            assertNotEquals(cast[0].room, cast[1].room)
            assertNotEquals(cast[2].room, cast[3].room)
        }
        // without positions, the order's neighbours are kept apart
        for (seed in 0 until 40) {
            val cast = Silhouettes.cast("Zzz", "o$seed/0", 6, IntArray(6) { it and 1 })
            for (k in 1 until 6) assertNotEquals(cast[k - 1].room, cast[k].room)
        }
    }

    // ------------------------------------------------------------------ the activities

    @Test
    fun everyActivityHasItsNaturalRoomFirstAndEveryRoomHasSomethingToDoInIt() {
        for (a in Activity.entries) {
            val rooms = PeopleActivities.roomsOf(a)
            assertEquals(PeopleActivities.of(a).room, rooms[0])
            assertEquals(rooms.size, rooms.toSet().size)
        }
        for (room in RoomKind.entries) {
            assertTrue("$room", PeopleActivities.inRoom(room).isNotEmpty())
            assertTrue(PeopleActivities.seenIn(room).first() in PeopleActivities.inRoom(room))
        }
        // the new activities are where they belong
        assertEquals(RoomKind.LIVING, PeopleActivities.of(Activity.TV_WATCHING).room)
        assertEquals(RoomKind.YOGA, PeopleActivities.of(Activity.TREE_POSE).room)
        assertEquals(RoomKind.DINING, PeopleActivities.of(Activity.TALKING).room)
        assertTrue(PeopleActivities.of(Activity.TALKING).pair)
        assertTrue(PeopleActivities.of(Activity.CHILD_PLAYING).child)
        // the child is baked in the child looks only, and is small
        assertEquals(2, PeopleActivities.looksOf(Activity.CHILD_PLAYING))
        assertEquals(1, PeopleActivities.lookFor(Activity.CHILD_PLAYING, 5))
        assertEquals(5, PeopleActivities.lookFor(Activity.READING, 5))
        val child = PeopleActivities.reach(Activity.CHILD_PLAYING)
        val grown = PeopleActivities.reach(Activity.MEDITATING)
        assertTrue(-child[1] < -grown[1] * .8f)
        // cooking only happens in a kitchen, brushing only in a bathroom
        assertEquals(listOf(RoomKind.KITCHEN), PeopleActivities.roomsOf(Activity.COOKING))
        assertEquals(listOf(RoomKind.BATH), PeopleActivities.roomsOf(Activity.BRUSHING))
    }

    @Test
    fun everyoneStandsOnTheFloorOfEveryRoomTheyCanBeInAndFitsAcrossIt() {
        for (a in Activity.entries) {
            if (a == Activity.WALKING) continue
            val spec = PeopleActivities.of(a)
            val r = PeopleActivities.reach(a)
            val fit = PeopleActivities.fitted(a)
            // feet on the floor: nothing below the window's bottom, and the feet right on it
            assertTrue("$a floor", r[3] <= 1.3f && r[3] >= -.2f)
            for (room in spec.rooms) {
                val s = PeopleActivities.roomFit(room)
                val shift = PeopleActivities.shift(room)
                val left = s * (r[0] * fit + shift) + 8.5f
                val right = s * (r[2] * fit + shift) + 8.5f
                assertTrue("$a in $room left $left", left > -.8f)
                assertTrue("$a in $room right $right", right < 17.8f)
                if (spec.pair) {
                    // the mirrored second figure fits too
                    val l2 = s * (-r[2] * fit + shift) + 8.5f
                    val r2 = s * (-r[0] * fit + shift) + 8.5f
                    assertTrue("$a pair", l2 > -.8f && r2 < 17.8f)
                }
            }
        }
        // the new frames bake like the others and stay within the people's budget
        for (a in
            listOf(
                Activity.TV_WATCHING,
                Activity.TREE_POSE,
                Activity.CHILD_PLAYING,
                Activity.TALKING,
            )) {
            val f = PeopleSprites.render(0, a, 0)
            assertTrue(f.pixels.count { (it ushr 24) == 255 } > 60)
        }
        assertTrue(PeopleSprites.bakedBytes() < 6 * 1024 * 1024)
    }

    @Test
    fun aWindowsCurtainsComeInMoreKinds() {
        val seen = HashSet<Int>()
        for (seed in 0 until 80) for (c in floor("Zzz", seed)) seen += c.curtain
        for (k in 0..Silhouettes.CURTAINS) assertTrue("curtain $k", k in seen)
        assertFalse(seen.any { it < 0 || it > Silhouettes.CURTAINS })
    }
}
