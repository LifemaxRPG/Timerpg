package com.timerpg.street

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import kotlin.math.hypot

/**
 * The open world's camera under the player's fingers ([OrbitCamera]): one finger dragged anywhere the stick and the
 * buttons do not take turns the camera round the player (across) and up and down (pitch); two fingers pinch it nearer
 * or further; a double tap puts it behind the player ([heading], degrees, 0 north, clockwise).
 *
 * Chain it OUTSIDE the stick's own pointer handler on the same layer (`MoveStick(modifier = Modifier.orbitGestures(..))`):
 * the stick sees each touch first and takes the ones it wants (consumed), this sees the rest. Buttons drawn over the
 * layer get their taps before either. With [enabled] false (the street view, a room, a panel) it does nothing.
 */
fun Modifier.orbitGestures(enabled: () -> Boolean, heading: () -> Float?): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
    awaitPointerEventScope {
        // the fingers this follows: where each was last, where it came down, when, and how far it has wandered
        val at = HashMap<Long, Offset>()
        val downAt = HashMap<Long, Pair<Long, Offset>>()
        val wander = HashMap<Long, Float>()
        var lastTap = 0L
        var lastTapAt = Offset.Zero
        while (true) {
            val e = awaitPointerEvent()
            if (!enabled()) { at.clear(); downAt.clear(); wander.clear(); continue }
            for (c in e.changes) {
                val id = c.id.value
                if (c.changedToDown()) {
                    // (one the stick took, or a button's, is not ours)
                    if (!c.isConsumed) { at[id] = c.position; downAt[id] = c.uptimeMillis to c.position; wander[id] = 0f }
                } else if (!c.pressed && id in at) {
                    // let go: a short still touch is a tap, two of them close together put the camera behind the player
                    val (t0, p0) = downAt[id]!!
                    if (at.size == 1 && (wander[id] ?: 0f) < slop && c.uptimeMillis - t0 < 300) {
                        if (c.uptimeMillis - lastTap < doubleTapMs && hypot(p0.x - lastTapAt.x, p0.y - lastTapAt.y) < slop * 6f) {
                            heading()?.let { OrbitCamera.recentre(it) }
                            lastTap = 0L
                        } else { lastTap = c.uptimeMillis; lastTapAt = p0 }
                    }
                    at.remove(id); downAt.remove(id); wander.remove(id)
                }
            }
            val ours = e.changes.filter { it.pressed && it.id.value in at }
            val w = size.width.toFloat(); val h = size.height.toFloat()
            if (ours.size == 1) {
                // one finger: turn (once past the touch slop, so a tap does not nudge the camera)
                val c = ours[0]; val id = c.id.value
                val d = c.position - at[id]!!
                val wandered = (wander[id] ?: 0f) + d.getDistance()
                wander[id] = wandered
                if (wandered >= slop) { OrbitCamera.drag(d.x, d.y, w, h); at[id] = c.position }
                c.consume()
            } else if (ours.size >= 2) {
                // two: pinch (fingers apart: nearer)
                val a = ours[0]; val b = ours[1]
                val pa = at[a.id.value]!!; val pb = at[b.id.value]!!
                val before = hypot(pa.x - pb.x, pa.y - pb.y)
                val now = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
                if (before > 1f && now > 1f) OrbitCamera.pinch(now / before)
                for (c in ours) { at[c.id.value] = c.position; wander[c.id.value] = slop; c.consume() }
            }
        }
    }
}
