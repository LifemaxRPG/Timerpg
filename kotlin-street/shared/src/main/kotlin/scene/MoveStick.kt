package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.exp
import kotlin.math.min

/**
 * The on-screen stick: one floating stick under the left thumb, in place of four arrow keys. It rests, faint, at its home
 * in the lower left; a thumb landing anywhere in the left part of the screen's lower two thirds brings it there at rest,
 * and pushing it walks the player (further: faster, to the rim: a run). It is drawn smooth like the chips, not in pixels.
 *
 * Lay it over the whole screen, under every button and panel: a touch it does not want (outside its zone, or a second
 * finger) is left alone, and buttons drawn over it get their taps first. With [enabled] false (the corner turn, a door,
 * a panel over the street) it is hidden and the player does not move, but a thumb still held keeps its place, so the walk
 * picks up again when it comes back.
 */
@Composable
fun MoveStick(enabled: Boolean, modifier: Modifier = Modifier) {
    val stick = MoveInput.stick
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val px = density.density
    val open by rememberUpdatedState(enabled)
    SideEffect {
        stick.travel = TRAVEL * px
        stick.follow = FOLLOW * px
        MoveInput.closed = !enabled
    }

    // what is drawn: the ring's centre, the knob's offset from it, how visible, and whether running
    var baseX by remember { mutableFloatStateOf(Float.NaN) }
    var baseY by remember { mutableFloatStateOf(0f) }
    var knobX by remember { mutableFloatStateOf(0f) }
    var knobY by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableFloatStateOf(IDLE) }
    var running by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }

    // every frame: the push goes into the game again (so a held stick picks up after a pause, and a closed one is still),
    // and the drawing follows the thumb, or springs back to rest at home
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var wasRunning = false
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, .05f); last = now
            MoveInput.publish()
            if (width <= 0f) continue
            val homeX = HOME * px; val homeY = height - HOME * px
            if (baseX.isNaN()) { baseX = homeX; baseY = homeY }
            if (stick.active) {
                baseX = stick.baseX; baseY = stick.baseY
                knobX = stick.knobX; knobY = stick.knobY
            } else {
                // let go: the knob springs back (~120 ms) and the stick glides home and fades (~200 ms)
                val k = 1f - exp(-dt / .03f)
                knobX -= knobX * k; knobY -= knobY * k
                val g = 1f - exp(-dt / .05f)
                baseX += (homeX - baseX) * g; baseY += (homeY - baseY) * g
            }
            val want = if (!open) 0f else if (stick.active) 1f else IDLE
            shown += (want - shown) * (1f - exp(-dt / .05f))
            // breaking into a run: one light tick under the thumb
            val run = open && stick.active && GameState.running
            if (run && !wasRunning) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            wasRunning = run
            running = run
        }
    }

    Canvas(
        modifier.onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() }.pointerInput(Unit) {
            fun inZone(o: Offset) = o.x < min(size.width * ZONE_W, ZONE_MAX * px) && o.y > size.height * ZONE_TOP
            awaitPointerEventScope {
                try {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (!stick.active) {
                            // a new thumb in the zone (not while closed: a touch then is not meant for walking)
                            if (open) {
                                val d = e.changes.firstOrNull { it.changedToDown() && !it.isConsumed && inZone(it.position) }
                                if (d != null) { stick.down(d.id.value, d.position.x, d.position.y); d.consume() }
                            }
                        } else {
                            // only the thumb it follows; other fingers are left for the rest of the screen
                            val c = e.changes.firstOrNull { it.id.value == stick.pointer }
                            if (c != null) {
                                if (c.pressed) stick.move(c.position.x, c.position.y) else stick.up()
                                c.consume()
                            }
                        }
                        // at once, not a frame later
                        MoveInput.publish()
                    }
                } finally {
                    // the stick went away (or the screen did): nothing stays held
                    stick.up()
                    MoveInput.publish()
                }
            }
        },
    ) {
        val a = shown
        if (a <= .01f || baseX.isNaN()) return@Canvas
        val r = RING * px
        // the ring stays whole on the screen (the stick's centre is still where the thumb landed)
        val cx = baseX.coerceIn(r + 2f * px, size.width - r - 2f * px)
        val cy = baseY.coerceIn(r + 2f * px, size.height - r - 2f * px)
        val c = Offset(cx, cy)
        drawCircle(Color(0x55101420), r, c, alpha = a)
        drawCircle(Color(0x59FFFFFF), r, c, alpha = a, style = Stroke(1.5f * px))
        // where a run starts
        drawCircle(Color(0x26FFFFFF), Stick.radiusFor(MoveTuning.runAt) * TRAVEL * px, c, alpha = a, style = Stroke(1f * px))
        val k = c + Offset(knobX, knobY)
        drawCircle(if (running) Color(0xCC3A6FD8) else Color(0x99101420), KNOB * px, k, alpha = a)
        drawCircle(Color(0xB3FFFFFF), KNOB * px, k, alpha = a, style = Stroke(2f * px))
    }
}

/** The stick's size and place (dp): about 0.19 mm a dp on the phone, so the knob's travel is about 9 mm. */
private const val TRAVEL = 48f
private const val FOLLOW = 16f
private const val RING = 60f
private const val KNOB = 24f
/** Its home: this far in from the left and up from the bottom. */
private const val HOME = 84f
/** Where a thumb picks it up: the left 45% of the width (at most 380 dp), below the top 30%. */
private const val ZONE_W = .45f
private const val ZONE_MAX = 380f
private const val ZONE_TOP = .3f
/** How visible it is at rest. */
private const val IDLE = .35f
