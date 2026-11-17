package com.timerpg.street

import com.lifemax.rpg.feature.habits.domain.Furniture
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.MoveInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Between the stick and the player: [MoveInput]'s gating (closed, held until neutral, let go, the pad against the stick),
 * and [GameState]'s step across the pavement and round the furniture at its back.
 */
class ControlsTest {
    private val T = 48f

    @Before fun reset() {
        MoveInput.closed = false; MoveInput.scripted = null
        MoveInput.releaseAll()
        MoveInput.stick.travel = T; MoveInput.stick.follow = 16f
        GameState.x = 1000f; GameState.lane = 7f; GameState.dir = 1f; GameState.facing = 0
        GameState.vx = 0f; GameState.motion.vy = 0f
        GameState.moveX = 0f; GameState.moveLane = 0f
    }

    @After fun tidy() { MoveInput.closed = false; MoveInput.scripted = null; MoveInput.releaseAll() }

    private fun thumb(dx: Float, dy: Float) { MoveInput.stick.move(500f + dx, 300f + dy); MoveInput.publish() }

    @Test fun theStickWalksThePlayer() {
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(T, 0f)
        assertEquals(1f, GameState.moveX, 1e-6f); assertEquals(0f, GameState.moveLane, 0f)
        // up the screen is into the buildings: moveLane < 0
        thumb(0f, -T)
        assertEquals(-1f, GameState.moveLane, 1e-6f)
    }

    @Test fun closedIsStillButKeepsTheThumb() {
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(T, 0f)
        MoveInput.closed = true; MoveInput.publish()
        assertEquals(0f, GameState.moveX, 0f)
        // the thumb is still followed while closed, and walks again once open
        thumb(T * .9f, 0f)
        assertEquals(0f, GameState.moveX, 0f)
        MoveInput.closed = false; MoveInput.publish()
        assertTrue(GameState.moveX > .8f)
    }

    @Test fun heldUntilNeutralAfterADoor() {
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(0f, -T)
        MoveInput.holdUntilNeutral()
        assertEquals(0f, GameState.moveLane, 0f)
        thumb(0f, -T * .8f)
        assertEquals(0f, GameState.moveLane, 0f)
        thumb(0f, -T * .2f)
        thumb(T, 0f)
        assertEquals(1f, GameState.moveX, 1e-6f)
    }

    @Test fun aHeldKeyOrPadIsHeldToo() {
        // an arrow key held through a door: its repeats do not bring the push back
        MoveInput.pad(0f, .7f)
        assertEquals(.7f, GameState.moveLane, 0f)
        MoveInput.holdUntilNeutral()
        assertEquals(0f, GameState.moveLane, 0f)
        MoveInput.pad(0f, .7f); MoveInput.pad(0f, .7f)
        assertEquals(0f, GameState.moveLane, 0f)
        // let go, then pressed again: it walks
        MoveInput.pad(0f, 0f)
        MoveInput.pad(.7f, 0f)
        assertEquals(.7f, GameState.moveX, 0f)
        // a game pad's stick only relaxed to a slow walk's push counts again from there
        MoveInput.holdUntilNeutral()
        MoveInput.pad(.6f, 0f); assertEquals(0f, GameState.moveX, 0f)
        MoveInput.pad(.2f, 0f); assertEquals(.2f, GameState.moveX, 0f)
        // a pad already near the middle is not held
        MoveInput.holdUntilNeutral()
        assertEquals(.2f, GameState.moveX, 0f)
    }

    @Test fun aThumbNearTheMiddleIsNotHeld() {
        // resting at a slow walk's push when a conversation opens: its next push out counts at once
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(T * .25f, 0f)
        MoveInput.holdUntilNeutral()
        assertFalse(MoveInput.stick.holding)
        thumb(T, 0f)
        assertEquals(1f, GameState.moveX, 1e-6f)
    }

    @Test fun aScriptedWalkIsNotOverwritten() {
        // the game walking the player itself: every frame's publish keeps its push, even while the stick is closed
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(T, 0f)
        MoveInput.scripted = floatArrayOf(0f, -1f)
        MoveInput.closed = true
        MoveInput.publish(); thumb(-T, 0f)
        assertEquals(0f, GameState.moveX, 0f); assertEquals(-1f, GameState.moveLane, 0f)
        MoveInput.scripted = null; MoveInput.closed = false; MoveInput.publish()
        assertEquals(-1f, GameState.moveX, 1e-6f)
    }

    @Test fun lettingGoLeavesNothingHeld() {
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(T, 0f)
        MoveInput.pad(-.7f, 0f)
        MoveInput.releaseAll()
        assertEquals(0f, GameState.moveX, 0f); assertEquals(0f, GameState.moveLane, 0f)
        assertFalse(MoveInput.stick.active)
        // a move after that is not a touch the stick follows
        thumb(T, 0f)
        assertEquals(0f, GameState.moveX, 0f)
    }

    @Test fun whicheverIsPushedFurther() {
        MoveInput.stick.down(1L, 500f, 300f)
        thumb(T * .4f, 0f)
        MoveInput.pad(-.7f, 0f)
        assertEquals(-.7f, GameState.moveX, 0f)
        thumb(T, 0f)
        assertEquals(1f, GameState.moveX, 1e-6f)
        MoveInput.pad(0f, 0f)
        assertEquals(1f, GameState.moveX, 1e-6f)
    }

    private fun steps(seconds: Float, mx: Float, my: Float, furniture: List<Furniture> = emptyList(), laneMax: Float = 13f) {
        GameState.moveX = mx; GameState.moveLane = my
        repeat((seconds * 120f).toInt()) {
            val before = GameState.x; val laneBefore = GameState.lane
            GameState.step(1f / 120f, 0f, 3000f, laneMax)
            GameState.avoid(furniture, before, laneBefore, 1f / 120f)
            GameState.settle()
        }
    }

    @Test fun turningRoundOnTheStreet() {
        // running right, then pushed left: still facing right while sliding right, facing left once nearly stopped
        steps(1.5f, 1f, 0f)
        assertEquals(1f, GameState.dir, 0f)
        GameState.moveX = -1f; GameState.moveLane = 0f
        var turned = false
        repeat(60) {
            val before = GameState.x; val laneBefore = GameState.lane
            GameState.step(1f / 120f, 0f, 3000f, 13f)
            GameState.avoid(emptyList(), before, laneBefore, 1f / 120f)
            GameState.settle()
            if (GameState.dir < 0f) turned = true
            if (GameState.dir < 0f) assertTrue("facing left while sliding right at ${GameState.vx}", GameState.vx < 12f)
            if (GameState.vx > 20f) assertEquals(1f, GameState.dir, 0f)
        }
        assertTrue(turned)
    }

    @Test fun acrossThePavement() {
        // pushed in: to the back of the pavement and held there, with no speed stored against the wall
        steps(2f, 0f, -1f)
        assertEquals(GameState.LANE_MIN, GameState.lane, 0f)
        assertEquals(0f, GameState.motion.vy, 0f)
        assertEquals(1, GameState.facing)
        // out: to the kerb in well under a second running (11 lanes)
        GameState.lane = 2f; steps(.8f, 0f, 1f, laneMax = 13f)
        assertEquals(13f, GameState.lane, 0f)
        assertEquals(0f, GameState.motion.vy, 0f)
        assertEquals(3, GameState.facing)
        // and along the street, an end stops them dead
        GameState.x = 2900f; steps(2f, 1f, 0f)
        assertEquals(2980f, GameState.x, 0f)
        assertEquals(0f, GameState.vx, 0f)
        assertEquals(1, GameState.edge)
    }

    @Test fun roundTheLamps() {
        val lamp = Furniture(FurnitureKind.LAMP, 1100f, 0)
        // a diagonal run in towards the back of the pavement, into a lamp: they step out round it and get past
        GameState.x = 1000f; GameState.lane = 7f
        steps(1.5f, .7071f, -.7071f, listOf(lamp))
        assertTrue("stuck at ${GameState.x}", GameState.x > 1100f + GameState.reach(lamp))
        // walking along the inner band straight at it: round it, never through it
        GameState.x = 1000f; GameState.lane = 4f; GameState.vx = 0f
        var inside = false
        GameState.moveX = 1f; GameState.moveLane = 0f
        repeat(240) {
            val before = GameState.x; val laneBefore = GameState.lane
            GameState.step(1f / 120f, 0f, 3000f, 13f)
            GameState.avoid(listOf(lamp), before, laneBefore, 1f / 120f)
            GameState.settle()
            if (GameState.lane < GameState.FRONT && kotlin.math.abs(GameState.x - lamp.x) < GameState.reach(lamp) - .01f) inside = true
        }
        assertFalse("walked through the lamp", inside)
        assertTrue(GameState.x > lamp.x)
        // standing beside it, a step in is refused
        GameState.x = 1100f; GameState.lane = 7f; GameState.vx = 0f
        steps(.5f, 0f, -1f, listOf(lamp))
        assertEquals(GameState.FRONT, GameState.lane, 0f)
    }
}
