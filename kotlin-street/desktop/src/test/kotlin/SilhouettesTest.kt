package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.Activity
import com.lifemax.rpg.feature.habits.domain.FigBuilder
import com.lifemax.rpg.feature.habits.domain.HabitsRules
import com.lifemax.rpg.feature.habits.domain.Looks
import com.lifemax.rpg.feature.habits.domain.PeopleActivities
import com.lifemax.rpg.feature.habits.domain.PeopleLod
import com.lifemax.rpg.feature.habits.domain.Presence
import com.lifemax.rpg.feature.habits.domain.RoomKind
import com.lifemax.rpg.feature.habits.domain.Silhouettes
import com.lifemax.rpg.feature.habits.domain.Spline
import com.lifemax.rpg.feature.habits.domain.WindowCast
import com.lifemax.rpg.feature.habits.domain.floorWindows
import com.lifemax.rpg.feature.habits.domain.lightOrder
import com.lifemax.rpg.feature.habits.domain.occupancy
import com.lifemax.rpg.feature.habits.presentation.scene.PeopleSprites
import com.lifemax.rpg.feature.habits.presentation.scene.RoomSprites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The people in the windows: what they do, who they are, how they move, how big they are, what they
 * cost.
 */
class SilhouettesTest {
    @Test
    fun peopleDoWhatTheHabitIsCalled() {
        assertEquals(Activity.PUSHUPS, Silhouettes.activityOf("Ten push-ups"))
        assertEquals(Activity.PUSHUPS, Silhouettes.activityOf("Plank 1 min"))
        assertEquals(Activity.READING, Silhouettes.activityOf("Read 10 pages"))
        assertEquals(Activity.READING, Silhouettes.activityOf("Language app"))
        assertEquals(Activity.WRITING, Silhouettes.activityOf("Journal 5 min"))
        assertEquals(Activity.PLANNING, Silhouettes.activityOf("Plan the day"))
        assertEquals(Activity.STRETCHING, Silhouettes.activityOf("Stretch 10 min"))
        assertEquals(Activity.MEDITATING, Silhouettes.activityOf("Meditate"))
        assertEquals(Activity.BRUSHING, Silhouettes.activityOf("Cold shower"))
        assertEquals(Activity.WALKING, Silhouettes.activityOf("Walk 20 min"))
        assertEquals(Activity.JOGGING, Silhouettes.activityOf("Go for a run"))
        assertEquals(Activity.MAKING_BED, Silhouettes.activityOf("Make the bed"))
        assertEquals(Activity.DRINKING, Silhouettes.activityOf("Glass of water"))
        assertEquals(Activity.WATERING, Silhouettes.activityOf("Water the plants"))
        assertEquals(Activity.COOKING, Silhouettes.activityOf("Cook dinner"))
        assertEquals(Activity.SWEEPING, Silhouettes.activityOf("Tidy 5 minutes"))
        assertEquals(Activity.PHONE_CALL, Silhouettes.activityOf("Call a friend"))
        assertEquals(Activity.GUITAR, Silhouettes.activityOf("Practice guitar"))
        assertEquals(Activity.TYPING, Silhouettes.activityOf("Clear the inbox"))
        // nothing fits: ordinary office life instead
        assertNull(Silhouettes.activityOf("Zzz"))
        assertNull(Silhouettes.activityOf(""))
    }

    @Test
    fun theCatalogueWidensWhatAHabitsNameMatches() {
        // "Do 10 push-ups" is in the catalogue with the key "chest"; a name found only by its keys
        // still fits
        assertEquals(Activity.PUSHUPS, Silhouettes.activityOf("Do 10 push-ups"))
        assertTrue(Silhouettes.activitiesOf("Exercise").size >= 3)
    }

    @Test
    fun aFloorsPeopleMostlyDoItsHabitAndSomeDoOtherThings() {
        val reading = (0 until 8).map { Silhouettes.activityFor("Read 30 min", it, 11) }
        assertEquals(Activity.READING, reading[0])
        assertTrue(reading.count { it == Activity.READING } >= 4)
        assertTrue(reading.toSet().size >= 2)
        // a name nothing fits fills its windows with different office life
        val generic = (0 until 8).map { Silhouettes.activityFor("Zzz", it, 5) }
        assertTrue(generic.toSet().size >= 4)
        // the same floor is the same every time
        assertEquals(reading, (0 until 8).map { Silhouettes.activityFor("Read 30 min", it, 11) })
        // and generic floors with different habits differ
        val other = (0 until 8).map { Silhouettes.activityFor("Zzz", it, 6) }
        assertNotEquals(generic, other)
    }

    /**
     * The checker colour of the first [lit] windows of a floor of [height], in the order they
     * light.
     */
    private fun groupsOf(key: String, height: Float, lit: Int): IntArray {
        val spots = floorWindows(height)
        val order = lightOrder(key, spots.size)
        return IntArray(lit) { (spots[order[it]].col + spots[order[it]].row) and 1 }
    }

    private fun castOf(key: String, height: Float, lit: Int, title: String = "Read 30 min") =
        Silhouettes.cast(title, key, lit, groupsOf(key, height, lit))

    @Test
    fun theCastOfAFloorIsDeterministicAndEveryLitWindowIsAFurnishedRoom() {
        val a = castOf("h1/0", 84f, 8)
        val b = castOf("h1/0", 84f, 8)
        assertEquals(8, a.size)
        assertTrue(
            a.indices.all {
                a[it].activity == b[it].activity &&
                    a[it].look == b[it].look &&
                    a[it].curtain == b[it].curtain &&
                    a[it].rank == b[it].rank
            }
        )
        // every lit window has a room (one its activity is seen in) and someone for its turn
        for (w in a) assertTrue(w.room in PeopleActivities.roomsOf(w.activity))
        assertEquals(PeopleActivities.of(a[0].activity).room, a[0].room)
        // the first window's person does the habit; most of the rest do it too
        assertEquals(Activity.READING, a[0].activity)
        assertTrue(a.count { it.activity == Activity.READING } >= 4)
        // the ranks count each colour's windows from 0 without a gap
        for (g in 0..1) {
            val ranks = a.filter { it.group == g }.map { it.rank }.sorted()
            assertEquals(ranks.indices.toList(), ranks)
        }
        // six lit windows on a floor are six different looks
        assertEquals(6, castOf("h1/1", 60f, 6).map { it.look }.toSet().size)
        // curtains and sill things turn up now and then across a few floors
        val many = (0 until 12).flatMap { castOf("x$it", 84f, 8, "Walk").toList() }
        assertTrue(many.any { it.curtain != 0 })
        assertTrue(many.any { it.sill != 0 })
        assertTrue(many.any { it.flip } && many.any { !it.flip })
    }

    @Test
    fun howManyAreLitStillGrowsWithReps() {
        val rules = HabitsRules.RULES
        assertEquals(1, occupancy(rules.clay - 1, 6).lit)
        assertEquals(2, occupancy(rules.clay, 6).lit)
        assertEquals(5, occupancy(rules.stone, 6).lit)
        val gold = occupancy(rules.gold, 6)
        assertEquals(6, gold.lit)
        // the cast follows the lights
        assertEquals(gold.lit, castOf("k", 60f, gold.lit, "Walk").size)
    }

    // ------------------------------------------------------------------ sparse people

    /** A building of [floors] lit floors of [lit] lit windows each: casts of every floor. */
    private fun building(
        seed: String,
        floors: Int,
        lit: Int,
        tall: Float = heightFor(lit),
    ): List<Array<WindowCast>> = List(floors) { castOf("$seed/$it", tall, lit) }

    /** A floor tall enough for [lit] windows: one row up to six, two above. */
    private fun heightFor(lit: Int) = if (lit > 6) 84f else 60f

    /** How present each lit window of each floor is at [seconds], one array per floor. */
    private fun look(
        seed: String,
        casts: List<Array<WindowCast>>,
        seconds: Float,
        cheer: Int = -1,
        cheerSince: Float = -1f,
    ): List<FloatArray> {
        val out = casts.map { FloatArray(it.size) }
        for ((o, c) in casts.withIndex()) Presence.floorPresence(
            out[o],
            c,
            seed.hashCode(),
            "$seed/$o".hashCode(),
            o,
            casts.size,
            seconds,
            if (o == cheer) cheerSince else -1f,
        )
        return out
    }

    private fun neighbours(a: WindowCast, b: WindowCast) = a.group == b.group

    @Test
    fun aStillFrameShowsAboutOneLitWindowInFourWithSomeoneAndNeverNeighboursTogether() {
        var people = 0
        var windows = 0
        for (seed in 0 until 60) for (floors in 1..3) for (lit in listOf(3, 4, 6, 8)) {
            val casts = building("b$seed", floors, lit)
            val v = look("b$seed", casts, 0f)
            for ((o, c) in casts.withIndex()) {
                val n = v[o].count { it > 0f }
                // never a whole floor, never more than the cap
                assertTrue("floor of $lit has $n", n <= Presence.floorCap(lit, floors))
                assertTrue(n < lit || lit <= 1)
                // everyone is settled in, in the still frame
                assertTrue(v[o].all { it == 0f || it == 1f })
                // no two neighbours (side by side, or one above the other) are both occupied
                val spots = floorWindows(heightFor(lit))
                val order = lightOrder("b$seed/$o", spots.size)
                for (i in 0 until lit) for (j in i + 1 until lit) {
                    val a = spots[order[i]]
                    val b = spots[order[j]]
                    val near = kotlin.math.abs(a.col - b.col) + kotlin.math.abs(a.row - b.row) == 1
                    if (near) assertFalse("neighbours both in", v[o][i] > 0f && v[o][j] > 0f)
                }
                for (i in 0 until lit) for (j in i + 1 until lit) if (v[o][i] > 0f && v[o][j] > 0f)
                    assertTrue(neighbours(c[i], c[j]))
            }
            people += v.sumOf { f -> f.count { it > 0f } }
            windows += casts.sumOf { it.size }
        }
        val share = people.toFloat() / windows
        assertTrue("share $share", share in .15f..0.30f)
    }

    @Test
    fun aSmallBuildingAlwaysShowsSomeoneAndATallOneStaysSparse() {
        for (seed in 0 until 40) {
            for (floors in 1..2) {
                val v = look("s$seed", building("s$seed", floors, 5), 0f)
                val n = v.sumOf { f -> f.count { it > 0f } }
                assertTrue("small building has $n", n in 1..3)
            }
            val tall = look("t$seed", building("t$seed", 10, 6, 84f), 0f)
            assertTrue(tall.sumOf { f -> f.count { it > 0f } } in 1..8)
        }
    }

    @Test
    fun overTimeFewPeopleAreVisibleAtOnceAndTheyKeepChanging() {
        for (floors in listOf(1, 2, 3, 6, 10)) {
            val casts = building("t$floors", floors, 6)
            var most = 0
            var sum = 0
            var samples = 0
            val seen = HashSet<Int>()
            var t = .5f
            while (t < 1200f) {
                val v = look("t$floors", casts, t)
                val here = v.sumOf { f -> f.count { it > 0f } }
                most = maxOf(most, here)
                sum += here
                samples++
                for ((o, f) in v.withIndex()) {
                    // never more than the cap on a floor, never neighbours together
                    assertTrue(f.count { it > 0f } <= Presence.floorCap(6, floors))
                    for (i in f.indices) for (j in i + 1 until f.size) if (f[i] > 0f && f[j] > 0f)
                        assertTrue(neighbours(casts[o][i], casts[o][j]))
                    seen += f.indices.filter { f[it] > 0f }.map { o * 100 + it }
                }
                t += 1.7f
            }
            assertTrue(
                "$floors floors: most $most",
                most <= minOf(8, Presence.staffedFloors(floors) * 2),
            )
            // sparse: on average well under one lit window in four holds someone
            val average = sum.toFloat() / samples / (floors * 6)
            assertTrue("$floors floors: average $average", average < .3f)
            // but it is never lifeless: someone is there most of the time
            assertTrue(sum > samples / 2)
            // and in twenty minutes every lit window has had its turn
            assertEquals("$floors floors: windows seen", floors * 6, seen.size)
        }
    }

    @Test
    fun everyLitWindowOfASmallBuildingGetsAPersonWithinTenMinutes() {
        for (seed in 0 until 12) for (floors in 1..3) {
            val casts = building("c$seed", floors, 6)
            val seen = HashSet<Int>()
            var t = .5f
            while (t < 600f) {
                for ((o, f) in look("c$seed", casts, t).withIndex()) seen +=
                    f.indices.filter { f[it] > 0f }.map { o * 100 + it }
                t += 2f
            }
            assertEquals("seed $seed, $floors floors", floors * 6, seen.size)
        }
    }

    @Test
    fun aShiftIsAtLeastFifteenSecondsAndEndsWithinItsBlock() {
        val casts = building("shift", 1, 6)
        // presence of the window with the most turns, sampled finely
        var run = 0f
        val runs = ArrayList<Float>()
        var t = .05f
        var k = -1
        while (t < 1500f) {
            val v = look("shift", casts, t)[0]
            if (k < 0) k = v.indices.firstOrNull { v[it] > 0f } ?: -1
            if (k >= 0) {
                if (v[k] > 0f) run += .05f
                else if (run > 0f) {
                    runs += run
                    run = 0f
                }
            }
            t += .05f
        }
        assertTrue(runs.size >= 4)
        assertTrue("shifts $runs", runs.drop(1).all { it in 15f..52f })
    }

    @Test
    fun schedulesAreDeterministicAndEachBuildingHasItsOwnPhase() {
        val casts = building("d", 3, 6)
        for (t in listOf(0f, 3.3f, 61f, 245.5f)) {
            val a = look("d", casts, t)
            val b = look("d", casts, t)
            assertTrue(a.indices.all { a[it].contentEquals(b[it]) })
        }
        val tracks =
            (0 until 8).map { seed ->
                (0 until 100).map {
                    look("p$seed", building("p$seed", 2, 6), it * 7f + 1f)
                        .flatMap { f -> f.toList() }
                        .count { v -> v > 0f }
                }
            }
        assertTrue(tracks.toSet().size >= 6)
    }

    @Test
    fun theFloorJustLaidCheersWithUpToThreePeopleOneByOneThenSettlesBack() {
        val casts = building("cheer", 3, 6)
        val calm = look("cheer", casts, 40f)
        val out = FloatArray(6)
        var most = 0
        var firstAt = HashMap<Int, Float>()
        var t = 0f
        while (t <= 8f) {
            Presence.floorPresence(
                out,
                casts[1],
                "cheer".hashCode(),
                "cheer/1".hashCode(),
                1,
                3,
                40f + t,
                t,
            )
            val n = out.count { it > 0f }
            most = maxOf(most, n)
            for (i in out.indices) if (out[i] > 0f && i !in firstAt) firstAt[i] = t
            t += .05f
        }
        // up to three at a time, appearing at different moments
        assertTrue("most $most", most in 3..3)
        assertTrue(firstAt.values.toSet().size >= 3)
        // after the six seconds it is the sparse arrangement again
        val after = FloatArray(6)
        Presence.floorPresence(
            after,
            casts[1],
            "cheer".hashCode(),
            "cheer/1".hashCode(),
            1,
            3,
            47f,
            7f,
        )
        val plain = FloatArray(6)
        Presence.floorPresence(plain, casts[1], "cheer".hashCode(), "cheer/1".hashCode(), 1, 3, 47f)
        assertTrue(after.contentEquals(plain))
        assertTrue(calm[1].count { it > 0f } <= Presence.FLOOR_CAP)
        // only the cheering floor
        val other = FloatArray(6)
        Presence.floorPresence(other, casts[0], "cheer".hashCode(), "cheer/0".hashCode(), 0, 3, 41f)
        assertTrue(other.count { it > 0f } <= Presence.FLOOR_CAP)
    }

    @Test
    fun theNumbersOfPeopleAreCapped() {
        for (floors in 1..14) assertTrue(Presence.staffedFloors(floors) * Presence.FLOOR_CAP <= 8)
        assertEquals(0, Presence.staffedFloors(0))
        assertEquals(1, Presence.staffedFloors(1))
        assertEquals(1, Presence.staffedFloors(2))
        // about one in four lit windows, at least one, at most the cap
        assertEquals(1, Presence.peoplePerFloor(1, 3))
        assertEquals(1, Presence.peoplePerFloor(4, 3))
        assertEquals(2, Presence.peoplePerFloor(6, 3))
        assertEquals(2, Presence.peoplePerFloor(12, 5))
        assertEquals(3, Presence.peoplePerFloor(12, 4))
    }

    @Test
    fun anActivityHasAtLeastFourteenKindsAndEachIsAClosedLoop() {
        assertTrue(Activity.entries.size >= 14)
        for (spec in PeopleActivities.ALL) {
            assertTrue("${spec.activity} frames", spec.frames in 2..16)
            assertTrue("${spec.activity} loop closed", Spline.closed(spec.keys, .001f))
            // sampled evenly round the loop: the frame after the last is the first again
            val wrap = Spline.sample(spec.keys, 1f)
            assertTrue(FigBuilder.same(wrap, spec.poseAt(0), .001f))
            assertTrue("${spec.activity} speed", spec.fps in .3f..24f)
        }
        // somebody is typing, reading, writing, stretching, lifting, jogging, meditating,
        // cooking...
        val acts = PeopleActivities.ALL.map { it.activity }.toSet()
        assertEquals(Activity.entries.toSet(), acts)
    }

    @Test
    fun peopleAreBuiltLikePeople() {
        for (l in 0 until Looks.COUNT) {
            val fig =
                FigBuilder(Looks.ALL[l], com.lifemax.rpg.feature.habits.domain.pose(), 0f)
                    .build(null, 0f)
            val b = FigBuilder.bounds(fig.shapes())
            val height = b[3] - b[1]
            // standing: about seven and a half heads, 21 to 28 units
            assertTrue("height $height", height in 20f..28f)
            // the head is about a seventh of it, the shoulders about two head widths
            val head = fig.joints.headY
            assertTrue(head < fig.joints.shoulderY && fig.joints.shoulderY < fig.joints.pelvisY)
            val torso = fig.joints.pelvisY - fig.joints.shoulderY
            assertTrue("torso $torso", torso in 5.5f..8.6f)
            // grounded: the feet are on y = 0
            assertEquals(0f, b[3], .01f)
        }
        // four distinct bodies
        val heights =
            Looks.ALL.map {
                    FigBuilder.bounds(
                        FigBuilder(it, com.lifemax.rpg.feature.habits.domain.pose(), 0f)
                            .build(null, 0f)
                            .shapes()
                    )
                }
                .map { it[3] - it[1] }
        assertTrue(heights.toSet().size >= 3)
    }

    @Test
    fun everyoneStaysInsideTheirWindow() {
        for (a in Activity.entries) {
            val spec = PeopleActivities.of(a)
            val r = PeopleActivities.reach(a)
            val fit = PeopleActivities.fitted(a)
            val room = PeopleActivities.roomFit(spec.room)
            // the figure drawn at its window scale fits across a window 17 wide, from the room's
            // middle
            val shift = PeopleActivities.shift(spec.room)
            val s = room
            val left = s * (r[0] * fit + shift) + 8.5f
            val right = s * (r[2] * fit + shift) + 8.5f
            if (a != Activity.WALKING) {
                assertTrue("$a left $left", left > -.8f)
                assertTrue("$a right $right", right < 17.8f)
            }
            // and no taller than the tallest window
            assertTrue("$a tall", (-r[1]) * fit <= 32f)
            assertTrue("$a floor", r[3] <= 1.3f)
        }
    }

    @Test
    fun theFramesTurnOverInStepsAndEachPersonIsOutOfStepWithTheOthers() {
        for (spec in PeopleActivities.ALL) {
            val frames = (0 until 4000).map { Silhouettes.frame(spec, it / 50f + .01f, 3) }
            assertTrue(frames.all { it in 0 until spec.frames })
            assertEquals((0 until spec.frames).toSet(), frames.toSet())
            // still while time stands still
            assertEquals(0, Silhouettes.frame(spec, 0f, 9))
            assertEquals(0f, Silhouettes.blend(spec, 0f, 9), 0f)
            if (spec.blend) {
                val b = (0 until 100).map { Silhouettes.blend(spec, it / 7f + .3f, 2) }
                assertTrue(b.all { it in 0f..1f } && b.any { it > .5f })
            }
        }
        val typing = PeopleActivities.of(Activity.TYPING)
        val one = (0 until 40).map { Silhouettes.frame(typing, it / 10f + .1f, 1) }
        val two = (0 until 40).map { Silhouettes.frame(typing, it / 10f + .1f, 5) }
        assertNotEquals(one, two)
    }

    @Test
    fun someoneWalkingPastCrossesTheWindowAndLeavesIt() {
        val w = 17f
        assertEquals(w / 2f, Silhouettes.walkX(0f, 4, w), 0f)
        val xs = (1..130).map { Silhouettes.walkX(it / 20f, 4, w) }
        assertTrue(xs.any { it.isNaN() })
        val inside = xs.filter { !it.isNaN() }
        assertTrue(inside.isNotEmpty())
        assertTrue(inside.all { it in -6.01f..(w + 6.01f) })
        val dir = Silhouettes.walkDir(4)
        inside.zipWithNext().forEach { (a, b) ->
            if (kotlin.math.abs(b - a) < 5f) assertTrue((b - a) * dir >= 0f)
        }
    }

    @Test
    fun oneWalkerPassesAWholeBuildingEveryMinuteOrTwo() {
        val seed = 7
        val xs = (1..12000).map { Silhouettes.commuterX(it / 10f, seed, 184f) }
        assertTrue(xs.any { it.isNaN() })
        val inside = xs.filter { !it.isNaN() }
        assertTrue(inside.all { it in -10.1f..194.1f })
        assertTrue(Silhouettes.commuterX(0f, seed, 184f).isNaN())
        // crossings: counted by their starts in twenty minutes, one every 60 to 120 seconds
        val starts = (1..12000).count { !xs[it - 1].isNaN() && (it == 1 || xs[it - 2].isNaN()) }
        assertTrue("walks $starts", starts in 10..20)
        // on one floor at a time, and on different floors over time
        val floors = (1..12000).map { Silhouettes.commuterFloor(it / 10f, seed, 5) }
        assertTrue(floors.all { it in -1..4 })
        assertTrue(floors.filter { it >= 0 }.toSet().size >= 3)
        // exactly while the walker is on the street
        assertTrue(floors.indices.all { (floors[it] >= 0) == !xs[it].isNaN() })
        assertEquals(-1, Silhouettes.commuterFloor(0f, seed, 5))
    }

    @Test
    fun theLobbyIsQuietTheReceptionistIsThereAboutHalfTheTimeAndTheVisitorComesEveryNinetySeconds() {
        val seed = 3
        val desk = (1..12000).map { Silhouettes.receptionist(seed, it / 10f) }
        val share = desk.count { it > .5f }.toFloat() / desk.size
        assertTrue("desk $share", share in .35f..0.65f)
        assertTrue(desk.any { it == 0f } && desk.any { it == 1f })
        val v = (1..12000).map { Silhouettes.lobbyVisitor(it / 10f, 56f, seed) }
        val out = v.count { !it.isNaN() }.toFloat() / v.size
        assertTrue("visitor out $out", out < .32f)
        val visits = (1..12000).count { !v[it - 1].isNaN() && (it == 1 || v[it - 2].isNaN()) }
        assertTrue("visits $visits", visits in 10..20)
        assertTrue((1..12000).any { Silhouettes.lobbyWalking(it / 10f, seed) })
        assertTrue((1..12000).any { Silhouettes.lobbyReturning(it / 10f, seed) })
        // at rest: the receptionist is usually there, the visitor usually not
        val atRest = (0 until 200).map { Silhouettes.receptionist(it, 0f) }
        assertTrue(atRest.count { it == 1f } in 100..170)
        assertTrue(
            (0 until 200).count { !Silhouettes.lobbyVisitor(0f, 56f, it).isNaN() } in 30..110
        )
    }

    @Test
    fun emptyRoomsLampsGoOffForAMomentOnly() {
        val lamp = (1..3000).map { Silhouettes.lamp(4, it / 10f) }
        assertTrue(lamp.any { it == 0f } && lamp.count { it == 1f } > lamp.size * .7f)
        assertEquals(1f, Silhouettes.lamp(4, 0f), 0f)
    }

    @Test
    fun detailFadesWithScale() {
        assertEquals(PeopleLod.FULL, Silhouettes.lod(1.2f))
        assertEquals(PeopleLod.FULL, Silhouettes.lod(.6f))
        assertEquals(PeopleLod.BLOB, Silhouettes.lod(.4f))
        assertEquals(PeopleLod.DOT, Silhouettes.lod(.2f))
        assertEquals(PeopleLod.NONE, Silhouettes.lod(.08f))
        // never more detail further out
        var last = 0
        for (i in 150 downTo 5) {
            val o = Silhouettes.lod(i / 100f).ordinal
            assertTrue(o >= last)
            last = o
        }
    }

    @Test
    fun peopleAreSmallerInAShortWindowAndEveryRoomIsPaintedForEachSize() {
        assertTrue(RoomSprites.personScale(21f) < RoomSprites.personScale(37f))
        assertEquals(1f, RoomSprites.personScale(37f), 0f)
        for (kind in RoomKind.entries) {
            val r = RoomSprites.render(kind, 0, 27.5f)
            assertEquals(r.base.w * r.base.h, r.base.pixels.size)
            // the room has something in it: not an empty overlay
            assertTrue(r.base.pixels.count { (it ushr 24) > 40 } > 100)
            // and a short window's room is shorter
            assertTrue(RoomSprites.render(kind, 0, 21f).base.h < r.base.h)
        }
    }

    @Test
    fun theAtlasStaysWithinItsMemoryBudgetEvenIfEveryFrameIsMade() {
        // every frame of every look, as it is kept (cut to the pixels it uses): the box each
        // activity shares is only the bound it can never pass
        val worst = PeopleSprites.bakedBytes()
        assertTrue(worst <= PeopleSprites.worstCaseBytes())
        assertTrue("people worst case $worst", worst < 6 * 1024 * 1024)
        // the rooms warmed up front (the first variant of every kind at every height) on top
        var rooms = 0
        for (h in com.lifemax.rpg.feature.habits.domain.BuildingSpec.HEIGHTS) {
            val rows =
                if (h >= com.lifemax.rpg.feature.habits.domain.BuildingSpec.TWO_ROWS) 2 else 1
            val wh = (h - 23f - 6f * (rows - 1)) / rows
            rooms += RoomKind.entries.size * RoomSprites.bytes(wh)
        }
        assertTrue("worst case ${worst + rooms}", worst + rooms < 8 * 1024 * 1024)
        // a frame is lit: not black, with a soft glow past its edge
        val f = PeopleSprites.render(0, Activity.TYPING, 0)
        val solid = f.pixels.count { (it ushr 24) == 255 }
        val soft = f.pixels.count { (it ushr 24) in 1..254 }
        assertTrue(solid > 100 && soft > 50)
        val brightest = f.pixels.filter { (it ushr 24) == 255 }.maxOf { (it shr 16) and 255 }
        assertTrue("not pure black", brightest > 40)
        // made the same every time
        assertTrue(f.pixels.contentEquals(PeopleSprites.render(0, Activity.TYPING, 0).pixels))
    }
}
