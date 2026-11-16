package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.domain.AIM_AIR
import com.lifemax.rpg.feature.habits.domain.AIM_AIR_MAX
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.CAMERA_MIN
import com.lifemax.rpg.feature.habits.domain.CAMERA_TALL_MIN
import com.lifemax.rpg.feature.habits.domain.Camera
import com.lifemax.rpg.feature.habits.domain.STREET_LABEL
import com.lifemax.rpg.feature.habits.domain.SceneWindow
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.focusCamera
import com.lifemax.rpg.feature.habits.domain.streetLayout
import com.lifemax.rpg.feature.habits.domain.streetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the camera stands against the bands the controls take: the focused building's ground floor
 * never reaches the bottom band, its roof is only cut by the heading when the street is lowered,
 * and the zoomed-out street gets the height the compact time-lapse leaves.
 */
class HabitsCameraTest {
    /** Phone-sized windows: the usual text, a large text, a short screen, a tall one. */
    private val windows =
        listOf(
            SceneWindow(617f, 133f, 238f),
            SceneWindow(617f, 175f, 300f),
            SceneWindow(480f, 120f, 200f),
            SceneWindow(900f, 140f, 230f),
        )

    private val scenes =
        listOf("tall" to 0, "city" to 0, "city" to 1, "done" to 0, "fresh" to 0, "park" to 0)

    private data class Framed(val camera: Camera, val aim: Pair<Float, Float>?, val height: Float)

    private fun framed(name: String, stack: Int, w: SceneWindow): Framed {
        val s = HabitsScenarios.projection(name).stacks[stack]
        val l = buildingLayout(s, ghost = true)
        val id = s.next.takeIf { it.isNotEmpty() && l.span(it) != null }
        val cam =
            focusCamera(
                l.height,
                w.avail,
                id?.let(l::span),
                panRoom = w.panRoom,
                air = id?.let(l::aimAir) ?: AIM_AIR,
            )
        return Framed(cam, id?.let(l::span), l.height)
    }

    @Test
    fun theGroundFloorNeverReachesTheBottomBand() {
        for (w in windows) for ((name, stack) in scenes) {
            val cam = framed(name, stack, w).camera
            // the pavement line, lowered by the pan, is clear of the band's top by the lobby's air
            assertTrue(
                "$name $stack $w",
                w.y(-cam.pan) <= w.bandTop - SceneWindow.LOBBY_AIR + .001f,
            )
            // so the lobby, with its sign and awning, stands over the band
            assertTrue(w.y(cam.above(B.LOBBY_H)) < w.bandTop - SceneWindow.LOBBY_AIR)
        }
        // whatever a drag asks for
        val w = windows[0]
        for (height in listOf(100f, 300f, 500f, 900f, 2000f)) {
            val cam = focusCamera(height, w.avail, null, 99999f, w.panRoom)
            assertTrue(w.y(-cam.pan) <= w.lowest + .001f)
        }
    }

    @Test
    fun aBuildingThatFitsIsWholeAndATallOneIsLoweredNoFurtherThanTheRoom() {
        for (w in windows) for (height in listOf(80f, 150f, 250f, 300f, 340f, 400f, 450f, 600f)) {
            val cam = focusCamera(height, w.avail, null, null, w.panRoom)
            val drawn = cam.scale * (height + B.HEADROOM)
            if (drawn <= w.avail + .001f) {
                // fits: the roof and the air over it are inside the window, nothing is cut
                assertEquals(0f, cam.pan, .001f)
                assertTrue(cam.above(height + B.HEADROOM) <= w.avail + .001f)
            } else if (drawn - w.avail <= w.panRoom) {
                // fits by lowering the street: it shows whole
                assertTrue(cam.above(height + B.HEADROOM) <= w.avail + .001f)
            }
            assertTrue(cam.pan <= w.panRoom + .001f)
        }
    }

    @Test
    fun theNextFloorStaysReachableInTheTallBuildings() {
        for (w in windows) {
            val f = framed("tall", 0, w)
            val aim = f.aim!!
            assertTrue(f.camera.scale >= CAMERA_TALL_MIN - .0001f)
            assertTrue(f.camera.scale <= CAMERA_MIN + .0001f)
            // the floor to build is whole in the window: under the heading, over the ground
            assertTrue(f.camera.above(aim.first) >= -.001f)
            if (w.avail >= 200f) assertTrue(f.camera.above(aim.second) <= w.avail + .001f)
        }
    }

    @Test
    fun theFloorAboveTheNextOneShowsToo() {
        val w = windows[0]
        val s = HabitsScenarios.projection("city").stacks[0]
        val l = buildingLayout(s, ghost = true)
        val air = l.aimAir(s.next)
        assertTrue(air > AIM_AIR && air <= AIM_AIR_MAX)
        val f = framed("city", 0, w)
        assertTrue(f.camera.above(f.aim!!.second + air) <= w.avail + .001f)
        // nothing above a top floor, and nothing for an id that is not there: the usual air
        assertEquals(AIM_AIR, l.aimAir("nobody"), 0f)
        assertEquals(AIM_AIR, l.aimAir(s.blocks.last { !it.cleared }.id), 0f)
    }

    @Test
    fun theZoomedOutStreetUsesTheHeightItIsGiven() {
        val city = HabitsScenarios.projection("city")
        val layouts = city.stacks.map { buildingLayout(it, live = false) }
        val street = streetLayout(layouts)
        val heights = layouts.map { it.height }
        val tallest = heights.max() + B.HEADROOM / 2f
        // a short window is limited by its height, so more of it makes the buildings larger,
        // until the street's width is the limit
        val short = streetView(street, heights, 2000f, 200f)
        val taller = streetView(street, heights, 2000f, 300f)
        assertEquals((200f - STREET_LABEL) / tallest, short.scale, .0001f)
        assertEquals(((300f - STREET_LABEL) / tallest).coerceAtMost(.7f), taller.scale, .0001f)
        assertTrue(taller.scale > short.scale)
        val wide = streetView(street, heights, 411f, 900f)
        val to = street.end - B.GAP / 2f
        val from = street.lefts.first() - B.GAP / 2f
        assertEquals((411f - 24f) / (to - from), wide.scale, .0001f)
    }
}
