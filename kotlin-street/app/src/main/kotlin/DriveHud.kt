package com.timerpg.street

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.abs

/**
 * The cars' controls (docs/VEHICLES.md V1). On foot by a parked car's door: "Enter". In a car: the left stick steers
 * (the game's own stick: [com.lifemax.rpg.feature.habits.presentation.scene.GameState.moveX]); on the right, under the
 * right thumb, the gas (held), the brake (held: reverses once stopped), the handbrake (held), the speed in the era's
 * own way (mph: the cars are American), and "Exit" once the car is (nearly) still. Polled from the town's game state.
 */
@Composable
fun BoxScope.DriveHud(show: Boolean) {
    var phase by remember { mutableStateOf(VehicleWorld.Phase.ON_FOOT) }
    var enterName by remember { mutableStateOf<String?>(null) }
    var canExit by remember { mutableStateOf(false) }
    var mph by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            val c = TownPlay.cars
            phase = c?.phase ?: VehicleWorld.Phase.ON_FOOT
            enterName = c?.enterable?.spec?.name?.takeIf { TownPlay.openWorld && !TownPlay.frozen }
            canExit = c?.canExit == true
            mph = ((c?.driven?.forwardSpeed ?: 0f) * 2.237f).let { abs(it).toInt() }
            kotlinx.coroutines.delay(80)
        }
    }
    if (!show) return
    val c = TownPlay.cars ?: return
    val walk = TownPlay.open
    if (phase == VehicleWorld.Phase.ON_FOOT) {
        val name = enterName ?: return
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 120.dp)
                .background(Color(0xCC101420), RoundedCornerShape(18.dp))
                .pointerInput(name) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (waitForUpOrCancellation() != null) {
                            val car = c.enterable
                            if (car != null && walk != null && c.enter(car, walk)) com.timerpg.street.shot.Crumbs.add("car: in the ${car.spec.name} (${car.spot})")
                        }
                    }
                }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) { Text("Enter  ·  $name", fontSize = 16.sp, color = Color.White) }
        return
    }
    if (phase != VehicleWorld.Phase.DRIVING) return
    Column(
        Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 26.dp).systemGestureExclusion(),
        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // the way out of a stuck or lost car: it is put back where it last stood on the ground (VehicleWorld.resetDriven)
        var putBackAt by remember { mutableStateOf(0L) }
        if (putBackAt != 0L) {
            LaunchedEffect(putBackAt) { kotlinx.coroutines.delay(2200); putBackAt = 0L }
            Text("Car put back on the road", fontSize = 14.sp, color = Color.White, modifier = Modifier.background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(Color(0xCC101420), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (waitForUpOrCancellation() != null) { c.resetDriven(); putBackAt = System.currentTimeMillis(); com.timerpg.street.shot.Crumbs.add("car: reset by the player") }
                        }
                    }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("⟲", fontSize = 18.sp, color = Color.White) }
            Text("$mph mph", fontSize = 18.sp, color = Color.White, modifier = Modifier.background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            Box(
                Modifier.background(if (canExit) Color(0xCC101420) else Color(0x55101420), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            if (waitForUpOrCancellation() != null && c.exit()) com.timerpg.street.shot.Crumbs.add("car: out")
                        }
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Exit", fontSize = 15.sp, color = if (canExit) Color.White else Color(0x88FFFFFF)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Pedal("Hand", 56, Color(0x99404858)) { c.handbrake = it }
            Pedal("Brake", 74, Color(0xAA8A2A2A)) { c.brakePedal = if (it) 1f else 0f }
            Pedal("Gas", 92, Color(0xAA2A7A3A)) { c.gas = if (it) 1f else 0f }
        }
    }
}

/** A pedal: held down while a finger is on it. */
@Composable
private fun Pedal(label: String, size: Int, tint: Color, onPress: (Boolean) -> Unit) {
    Box(
        Modifier.size(size.dp).background(tint, CircleShape).pointerInput(label) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onPress(true)
                waitForUpOrCancellation()
                onPress(false)
            }
        },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 15.sp, color = Color.White) }
}

@Composable
private fun Text(s: String, fontSize: androidx.compose.ui.unit.TextUnit, color: Color, modifier: Modifier = Modifier) =
    androidx.compose.foundation.text.BasicText(s, modifier, style = androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
