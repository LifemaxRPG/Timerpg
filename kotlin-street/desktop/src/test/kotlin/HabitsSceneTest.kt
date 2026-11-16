package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.AIM_AIR
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.CAMERA_MAX
import com.lifemax.rpg.feature.habits.domain.CAMERA_MIN
import com.lifemax.rpg.feature.habits.domain.Camera
import com.lifemax.rpg.feature.habits.domain.Dice
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.Hit
import com.lifemax.rpg.feature.habits.domain.HitKind
import com.lifemax.rpg.feature.habits.domain.ORB_EDGE
import com.lifemax.rpg.feature.habits.domain.Occupancy
import com.lifemax.rpg.feature.habits.domain.PieceKind
import com.lifemax.rpg.feature.habits.domain.RuinWindow
import com.lifemax.rpg.feature.habits.domain.STREET_LABEL
import com.lifemax.rpg.feature.habits.domain.STREET_MAX_SCALE
import com.lifemax.rpg.feature.habits.domain.SceneWindow
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.buildingAt
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.centred
import com.lifemax.rpg.feature.habits.domain.coast
import com.lifemax.rpg.feature.habits.domain.floorWindows
import com.lifemax.rpg.feature.habits.domain.focusCamera
import com.lifemax.rpg.feature.habits.domain.hit
import com.lifemax.rpg.feature.habits.domain.lapseFraction
import com.lifemax.rpg.feature.habits.domain.lapseWeeks
import com.lifemax.rpg.feature.habits.domain.lightOrder
import com.lifemax.rpg.feature.habits.domain.occupancy
import com.lifemax.rpg.feature.habits.domain.orbAt
import com.lifemax.rpg.feature.habits.domain.orbSpot
import com.lifemax.rpg.feature.habits.domain.position
import com.lifemax.rpg.feature.habits.domain.rubberBand
import com.lifemax.rpg.feature.habits.domain.ruinWindow
import com.lifemax.rpg.feature.habits.domain.settle
import com.lifemax.rpg.feature.habits.domain.skyPhase
import com.lifemax.rpg.feature.habits.domain.skyTone
import com.lifemax.rpg.feature.habits.domain.starsShown
import com.lifemax.rpg.feature.habits.domain.streetLayout
import com.lifemax.rpg.feature.habits.domain.streetView
import com.lifemax.rpg.feature.habits.domain.towards
import com.lifemax.rpg.feature.habits.domain.xAt
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The street's geometry: a building per foundation, a floor per habit, the street they stand on,
 * how it scrolls and settles, the camera, taps, windows and the people in them, and the sky.
 */
class HabitsSceneTest {
    private val city = HabitsScenarios.projection("city")
    private val coffee = city.stacks[0]
    private val night = city.stacks[1]
    private val layouts = city.stacks.map { buildingLayout(it) }
    private val street = streetLayout(layouts)

    @Test
    fun aBuildingRisesFromItsLobbyAFloorPerHabitInOrder() {
        val b = buildingLayout(coffee)
        assertEquals(
            listOf(
                PieceKind.LOBBY,
                PieceKind.FLOOR,
                PieceKind.FLOOR,
                PieceKind.FLOOR,
                PieceKind.FLOOR,
                PieceKind.SIGN,
            ),
            b.pieces.map { it.kind },
        )
        // the lobby on the pavement, each floor on the one below, in the stack's order
        assertEquals(0f, b.pieces[0].bottom, 0f)
        assertEquals(B.LOBBY_H, b.pieces[0].height, 0f)
        b.pieces.subList(0, 5).zipWithNext().forEach { (p, q) -> assertEquals(p.top, q.bottom, 0f) }
        assertEquals(coffee.blocks.map { it.id }, b.pieces.subList(1, 5).map { it.id })
        // a floor's height is its size: stretch, normal, normal, two minutes
        assertEquals(
            listOf(B.HEIGHTS[2], B.HEIGHTS[1], B.HEIGHTS[1], B.HEIGHTS[0]),
            b.pieces.subList(1, 5).map { it.height },
        )
        // every floor stands, done today or not: the roof is over all of them, the sign over the
        // parapet
        val floors = B.LOBBY_H + B.HEIGHTS[2] + 2 * B.HEIGHTS[1] + B.HEIGHTS[0]
        assertEquals(floors, b.roof, .001f)
        assertEquals(floors + B.CORNICE, b.pieces[5].bottom, .001f)
        assertEquals(floors + B.CORNICE + B.SIGN_H + B.DY, b.height, .001f)
        assertEquals(B.W + B.DX, b.lot, 0f)
        assertTrue(b.trees.isEmpty())
    }

    @Test
    fun theGhostAndTheSignAreTodaysOnly() {
        val live = buildingLayout(coffee, ghost = true)
        assertEquals(PieceKind.GHOST, live.pieces.last().kind)
        assertEquals(
            live.pieces[live.pieces.size - 2].top + B.GHOST_GAP,
            live.pieces.last().bottom,
            .001f,
        )
        val past = buildingLayout(city.towersAt(1)[0], live = false, ghost = true)
        assertTrue(past.pieces.none { it.kind == PieceKind.GHOST || it.kind == PieceKind.SIGN })
        // a stack with no reward has no sign, and an empty one is only its lobby
        assertTrue(buildingLayout(night).pieces.none { it.kind == PieceKind.SIGN })
        val empty = buildingLayout(city.stacks[2])
        assertEquals(listOf(PieceKind.LOBBY), empty.pieces.map { it.kind })
        assertEquals(B.LOBBY_H, empty.roof, 0f)
    }

    @Test
    fun aHabitBeingBrokenIsARunDownStoreyPerFloorAndAClearedOneAParkTree() {
        val b = buildingLayout(night)
        val phone = night.blocks[0]
        val ruins = b.pieces.filter { it.id == phone.id }
        assertEquals(9, phone.left)
        assertEquals(9, ruins.size)
        assertTrue(ruins.all { it.kind == PieceKind.RUIN && it.height == B.RUIN })
        // only its top storey carries the name
        assertEquals(
            listOf(false, false, false, false, false, false, false, false, true),
            ruins.map { it.labelled },
        )
        assertEquals(ruins.last(), b.labelled(phone.id))
        assertEquals(B.LOBBY_H to B.LOBBY_H + 9 * B.RUIN, b.span(phone.id))
        // Late snacks is gone: a tree in the park beside the building, which widens its lot
        val snacks = night.blocks[1]
        assertTrue(snacks.cleared)
        assertTrue(b.pieces.none { it.id == snacks.id })
        assertEquals(listOf(snacks.id), b.trees.map { it.id })
        assertTrue(b.trees[0].x > B.W / 2f + B.DX)
        assertTrue(b.trees[0].x < B.W / 2f + B.DX + B.PARK_W)
        assertEquals(B.W + B.DX + B.PARK_W, b.lot, 0f)
        assertEquals(0f to B.TREE_H, b.span(snacks.id))
        // the floor being built stands over the run-down storeys
        val clothes = night.blocks[2]
        assertEquals(B.LOBBY_H + 9 * B.RUIN, b.span(clothes.id)!!.first, .001f)
        // never more than the drawn maximum of storeys
        val tall = night.copy(blocks = listOf(phone.copy(left = 40)))
        assertEquals(B.MAX_RUINS, buildingLayout(tall).pieces.count { it.kind == PieceKind.RUIN })
    }

    @Test
    fun theStreetStandsTheBuildingsSideBySideWithFurnitureInTheGaps() {
        assertEquals(3, street.centers.size)
        assertEquals(0f, street.centers[0], 0f)
        for (i in 0 until 2) {
            // the next lot starts a gap after this one ends
            assertEquals(street.lefts[i] + layouts[i].lot + B.GAP, street.lefts[i + 1], .001f)
            assertEquals(street.lefts[i] + B.W / 2f, street.centers[i], .001f)
        }
        // something stands in every gap between two lots, by turns a lamp, a tree, a lamp
        val inside = street.furniture.filter { it.seed >= 0 }
        assertEquals(listOf(FurnitureKind.LAMP, FurnitureKind.TREE), inside.map { it.kind })
        inside.forEachIndexed { i, f ->
            assertTrue(f.x > street.lefts[i] + layouts[i].lot)
            assertTrue(f.x < street.lefts[i + 1])
        }
        // and the street is finished off at both ends
        assertTrue(street.furniture.any { it.x < street.lefts[0] })
        assertTrue(street.furniture.any { it.x > street.lefts[2] + layouts[2].lot })
        assertTrue(street.start < street.lefts[0] && street.end > street.lefts[2] + layouts[2].lot)
        // no buildings: still a street, with nothing in it
        val none = streetLayout(emptyList())
        assertTrue(none.centers.isEmpty())
        assertEquals(0, none.centred(500f))
    }

    @Test
    fun whereAlongTheStreetAndWhichBuildingIsInFront() {
        for (i in 0 until 3) {
            assertEquals(i.toFloat(), street.position(street.centers[i]), .0001f)
            assertEquals(street.centers[i], street.xAt(i.toFloat()), .001f)
            assertEquals(i, street.centred(street.centers[i]))
        }
        val between = (street.centers[0] + street.centers[1]) / 2f
        assertEquals(.5f, street.position(between), .001f)
        assertEquals(1.25f, street.position(street.xAt(1.25f)), .001f)
        // the nearest middle is the one in front; past the ends, the end one
        assertEquals(0, street.centred(between - 1f))
        assertEquals(1, street.centred(between + 1f))
        assertEquals(0, street.centred(-1000f))
        assertEquals(2, street.centred(10000f))
        assertTrue(street.position(-100f) < 0f)
        // one building: the street is measured by a building's width and a gap
        val one = streetLayout(layouts.take(1))
        assertEquals(0, one.centred(150f))
        assertEquals(0f, one.position(0f), 0f)
    }

    @Test
    fun aReleaseCoastsAndSettlesSoftlyOnTheNearestBuilding() {
        val at = street.centers[1]
        // let go still: it stays on the building nearest
        assertEquals(1, street.settle(at + 40f, 0f))
        assertEquals(
            2,
            street.settle(street.centers[1] + (street.centers[2] - street.centers[1]) * .6f, 0f),
        )
        // a flick coasts on: a gentle one to the next, a hard one along the street, never past the
        // ends
        val spacing = street.centers[1] - street.centers[0]
        assertEquals(2, street.settle(at, spacing * .7f / coast(1f)))
        assertEquals(0, street.settle(at, -spacing * .7f / coast(1f)))
        assertEquals(2, street.settle(street.centers[0], 50000f))
        assertEquals(0, street.settle(street.centers[2], -50000f))
        // a slow drag lifted short of the middle goes back
        assertEquals(1, street.settle(at + spacing * .3f, 20f))
        // the pull past either end is held back, and nothing is held back inside it
        assertEquals(at, street.rubberBand(at), 0f)
        val past = street.rubberBand(street.centers[2] + 300f)
        assertTrue(past > street.centers[2] && past < street.centers[2] + 120f)
        assertTrue(street.rubberBand(-300f) > -120f)
        assertEquals(0, streetLayout(emptyList()).settle(10f, 100f))
    }

    @Test
    fun aShortBuildingIsEnlargedAndATallOneIsLoweredToItsNextFloor() {
        val short = focusCamera(120f, 400f)
        assertEquals(CAMERA_MAX, short.scale, 0f)
        assertEquals(0f, short.pan, 0f)
        assertEquals(0f, short.maxPan, 0f)
        // fits at a scale between the bounds
        val fits = focusCamera(400f, 400f)
        assertEquals(400f / (400f + B.HEADROOM), fits.scale, .0001f)
        assertEquals(0f, fits.maxPan, .001f)
        // too tall even at the smallest scale: the street is lowered so the next floor shows whole,
        // with air over it, and its foot never drops out of the window
        val tall = buildingLayout(HabitsScenarios.projection("tall").stacks[0])
        val stack = HabitsScenarios.projection("tall").stacks[0]
        val aim = tall.span(stack.next)!!
        val cam = focusCamera(tall.height, 300f, aim)
        assertEquals(CAMERA_MIN, cam.scale, 0f)
        assertTrue(cam.maxPan > 0f)
        assertTrue(cam.above(aim.first) >= -.001f)
        assertTrue(cam.above(aim.second + AIM_AIR) <= 300f + .001f || cam.above(aim.first) < .001f)
        // with nothing to aim at it shows the top; a drag sets it, within its bounds
        assertEquals(cam.maxPan, focusCamera(tall.height, 300f).pan, 0f)
        assertEquals(cam.maxPan, focusCamera(tall.height, 300f, pan = 99999f).pan, 0f)
        assertEquals(0f, focusCamera(tall.height, 300f, pan = -5f).pan, 0f)
        // between two buildings the camera is between their cameras
        val mid = Camera(1f, 0f, 0f).towards(Camera(.8f, 40f, 60f), .5f)
        assertEquals(.9f, mid.scale, .0001f)
        assertEquals(20f, mid.pan, .0001f)
    }

    @Test
    fun zoomedOutTheWholeStreetFits() {
        val width = 411f
        val avail = 300f
        val view = streetView(street, layouts.map { it.height }, width, avail)
        val from = street.lefts.first() - B.GAP / 2f
        val to = street.end - B.GAP / 2f
        assertTrue(view.scale <= STREET_MAX_SCALE)
        assertTrue((to - from) * view.scale <= width - 24f + .01f)
        assertTrue(layouts.maxOf { it.height } * view.scale <= avail - STREET_LABEL)
        assertEquals((from + to) / 2f, view.middle, .001f)
        // a tap on the zoomed-out street finds the building under it
        for (i in 0 until 3) assertEquals(i, street.buildingAt(street.centers[i], layouts))
        assertNull(street.buildingAt(street.lefts[1] - B.GAP / 2f, layouts))
    }

    @Test
    fun aTapFindsTheLobbyAFloorTheSignTheGhostOrATree() {
        val b = buildingLayout(coffee, ghost = true)
        assertEquals(Hit(HitKind.LOBBY, coffee.id), b.hit(0f, 10f))
        val read = b.pieces[1]
        assertEquals(Hit(HitKind.FLOOR, coffee.blocks[0].id), b.hit(-60f, read.middle))
        // the side face, leaning up to the right
        assertEquals(Hit(HitKind.FLOOR, coffee.blocks[0].id), b.hit(B.W / 2f + 5f, read.middle))
        val sign = b.pieces.first { it.kind == PieceKind.SIGN }
        assertEquals(Hit(HitKind.SIGN, coffee.id), b.hit(0f, sign.middle))
        val ghost = b.pieces.last()
        assertEquals(Hit(HitKind.GHOST, coffee.id), b.hit(0f, ghost.middle))
        // beside the building, nothing
        assertNull(b.hit(B.W, 30f))
        assertNull(b.hit(0f, -20f))
        // a run-down storey is its habit; the tree in the park is the cleared one's
        val n = buildingLayout(night)
        assertEquals(Hit(HitKind.FLOOR, night.blocks[0].id), n.hit(0f, B.LOBBY_H + B.RUIN * 3.5f))
        assertEquals(Hit(HitKind.TREE, night.blocks[1].id), n.hit(n.trees[0].x, 20f))
    }

    @Test
    fun aFloorHasAGridOfWindowsOverItsPlaque() {
        val one = floorWindows(B.HEIGHTS[1])
        assertEquals(B.COLS, one.size)
        assertTrue(one.all { it.row == 0 })
        // a stretch floor has two rows, every window of the grid in both
        val two = floorWindows(B.HEIGHTS[2])
        assertEquals(2 * B.COLS, two.size)
        assertEquals(setOf(0, 1), two.map { it.row }.toSet())
        for (h in B.HEIGHTS.toList()) {
            for (w in floorWindows(h)) {
                // inside the front, inside the floor, clear of the plaque's band under them
                assertTrue(w.x >= -B.W / 2f && w.x + w.w <= B.W / 2f)
                assertTrue(w.y >= B.BAND_TOP - .001f && w.y + w.h <= h - B.PLAQUE_BAND + .001f)
                assertTrue(w.h > 6f)
            }
        }
        // a run-down storey: thin bands, a row of windows
        val ruin = floorWindows(B.RUIN, ruin = true)
        assertEquals(B.COLS, ruin.size)
        assertTrue(
            ruin.all { it.y >= B.RUIN_BAND - .001f && it.y + it.h <= B.RUIN - B.RUIN_BAND + .001f }
        )
        // the windows line up from floor to floor: the same columns at the same x
        val xs = floorWindows(B.HEIGHTS[0]).map { it.x }
        assertEquals(xs, floorWindows(B.HEIGHTS[2]).filter { it.row == 0 }.map { it.x })
        assertEquals(xs, ruin.map { it.x })
        // and never overlap
        xs.zipWithNext().forEach { (a, b) -> assertTrue(b - a > B.WIN_W) }
        // the plaque's band holds a line of words
        assertTrue(B.PLAQUE_BAND >= B.PLAQUE_H + 2f)
        // the windows of the smallest floor are still tall enough for someone, a little smaller
        assertTrue(floorWindows(B.HEIGHTS[0]).all { it.h >= 20f })
    }

    @Test
    fun lightsGrowWithTheReps() {
        assertEquals(Occupancy(1), occupancy(0, 8))
        assertEquals(Occupancy(1), occupancy(9, 8))
        assertEquals(Occupancy(2), occupancy(10, 8))
        assertEquals(Occupancy(2), occupancy(29, 8))
        // most windows from 30, every one from 66
        val most = occupancy(30, 8)
        assertTrue(most.lit in 5..7)
        assertEquals(occupancy(65, 8), most)
        assertEquals(8, occupancy(66, 8).lit)
        // never more than the windows there are, and never none
        assertEquals(Occupancy(1), occupancy(100, 1))
        assertEquals(Occupancy(0), occupancy(100, 0))
        assertTrue((0..200).all { r -> occupancy(r, 4).lit <= 4 })
        // the order the windows light up in is the floor's own, and every window once
        val order = lightOrder("h1/0", 10)
        assertEquals((0 until 10).toList(), order.sorted())
        assertEquals(order.toList(), lightOrder("h1/0", 10).toList())
        assertNotEquals(order.toList(), lightOrder("h2/0", 10).toList())
    }

    @Test
    fun aRunDownBuildingIsBoardedUpMoreTheHigherItGoes() {
        fun boarded(storey: Int) =
            (0 until 400).count { ruinWindow("h$it", storey, 9, it % 6) == RuinWindow.BOARDED }
        assertTrue(boarded(8) > boarded(0))
        assertEquals(ruinWindow("h1", 3, 9, 2), ruinWindow("h1", 3, 9, 2))
        // some are still dimly lit: someone still lives with the habit
        assertTrue((0 until 100).any { ruinWindow("h$it", 0, 9, 0) == RuinWindow.DIM })
    }

    @Test
    fun theTimeLapseGrowsTheBuildingsFloorByFloor() {
        val back = city.towersAt(city.rules.weeks).map { buildingLayout(it, live = false) }
        val now = city.towersAt(0).map { buildingLayout(it, live = false) }
        // twelve weeks ago the coffee building had fewer floors (Journal, Stretch and Meditate
        // were not born yet) and the floors that stood were younger materials
        assertTrue(
            back[0].pieces.count { it.kind == PieceKind.FLOOR } <
                now[0].pieces.count { it.kind == PieceKind.FLOOR }
        )
        assertTrue(back[0].roof < now[0].roof)
        val then = back[0].pieces.mapNotNull { it.block }
        assertTrue(then.all { it.material.ordinal <= HabitMaterial.GOLD.ordinal })
        // and grows week by week, never shrinking for a habit being built
        val roofs =
            (city.rules.weeks downTo 0).map { w ->
                buildingLayout(city.towersAt(w)[0], live = false).roof
            }
        roofs.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // every floor of the past is done (lit) and has no marks of today
        assertTrue(
            city.towersAt(3).flatMap { it.blocks }.all { it.done && it.miss == 0 && !it.scar }
        )
    }

    @Test
    fun lapseSliderAndWeeks() {
        assertEquals(0, lapseWeeks(1f, 12))
        assertEquals(12, lapseWeeks(0f, 12))
        assertEquals(6, lapseWeeks(.5f, 12))
        assertEquals(1f, lapseFraction(0, 12), 0f)
        assertEquals(0f, lapseFraction(30, 12), 0f)
        assertEquals(1f, lapseFraction(3, 0), 0f)
    }

    @Test
    fun theSkyFollowsTheHourAndBlendsBetweenPhases() {
        assertEquals(SkyPhase.NIGHT, skyPhase(0f))
        assertEquals(SkyPhase.DUSK, skyPhase(5.5f))
        assertEquals(SkyPhase.DAY, skyPhase(7.5f))
        assertEquals(SkyPhase.DUSK, skyPhase(18f))
        assertEquals(SkyPhase.NIGHT, skyPhase(20.5f))
        // the tone: night 0, dusk 1, day 2 at the hours the screenshots use
        assertEquals(0f, skyTone(23f), 0f)
        assertEquals(0f, skyTone(22f), 0f)
        assertEquals(1f, skyTone(19f), 0f)
        assertEquals(2f, skyTone(14f), 0f)
        // and it never jumps: a tenth of an hour moves it a tenth at most
        for (i in 0 until 240) {
            val a = skyTone(i / 10f)
            val b = skyTone((i + 1) / 10f)
            assertTrue(abs(a - b) <= .1001f)
            assertTrue(a in 0f..2f)
        }
        // stars: all at night, faint at dusk, none by day, fading in between
        assertEquals(1f, starsShown(0f), 0f)
        assertTrue(starsShown(1f) in .2f..0.5f)
        assertEquals(0f, starsShown(2f), 0f)
        assertTrue(starsShown(skyTone(20.5f)) in starsShown(1f)..1f)
        // the sun crosses from sunrise to sunset; the moon is highest at one in the morning
        assertEquals(.5f, orbAt(13f).first, .001f)
        assertEquals(1f, orbAt(1f).second, .001f)
    }

    @Test
    fun theSunAndTheMoonKeepUnderTheHeading() {
        val width = 411f
        val top = 126f
        val horizon = 327f
        val column = 110f
        for (tenth in 0 until 240) {
            val hour = tenth / 10f
            val (x, y) = orbSpot(hour, width, top, horizon, column)
            assertTrue(x >= ORB_EDGE && x <= width - ORB_EDGE)
            assertTrue(y - 13f >= top)
            assertTrue(y + 13f <= horizon)
            val (_, high) = orbSpot(hour, width, top, horizon, column, street = 1f)
            assertTrue(high - 13f >= top && high + 13f <= top + STREET_LABEL)
        }
        assertTrue(orbSpot(9f, width, top, horizon, column).first < width / 2f)
        assertTrue(orbSpot(16f, width, top, horizon, column).first > width / 2f)
    }

    @Test
    fun theWindowKeepsTheBuildingsBetweenTheBands() {
        val w = SceneWindow(616f, 120f, 200f)
        assertEquals(616f - 200f - SceneWindow.GROUND_MARGIN, w.ground, 0f)
        assertEquals(w.ground - 120f, w.avail, 0f)
        assertEquals(120f, w.windowTop, .001f)
        assertEquals(w.ground - 10f, w.y(10f), 0f)
        // a view too small for both bands still has room for a building
        assertEquals(SceneWindow.MIN_AVAIL, SceneWindow(300f, 200f, 200f).avail, 0f)
    }

    @Test
    fun aKeysDiceAlwaysRollTheSame() {
        val a = Dice("s1h4")
        val b = Dice("s1h4")
        val first = List(6) { a.next() }
        assertEquals(first, List(6) { b.next() })
        assertTrue(first.all { it >= 0f && it < 1f })
    }
}
