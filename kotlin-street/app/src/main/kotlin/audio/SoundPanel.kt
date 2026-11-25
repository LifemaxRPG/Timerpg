package com.timerpg.street.audio

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.hud.HudButton
import com.lifemax.rpg.feature.habits.hud.HudRow
import com.lifemax.rpg.feature.habits.hud.HudSection
import com.lifemax.rpg.feature.habits.hud.HudStyle
import com.lifemax.rpg.feature.habits.hud.HudText

/** The Settings tab's sound rows (docs/SOUND.md): the footstep set to try, the engines' kind, the volumes. */
@Composable
fun SoundPanel(ctx: Context) {
    val s = SoundSettings
    HudSection("Sound")
    HudRow("Footsteps") {
        for (set in Footsteps.SETS) HudButton(set.id.uppercase(), s.footsteps == set.id) { s.footsteps = set.id; s.save(ctx) }
    }
    HudText(Footsteps.SETS.firstOrNull { it.id == s.footsteps }?.let { "${it.label}: ${it.source}" } ?: "", 11.sp, HudStyle.DIM, maxLines = 2)
    HudRow("Engines") {
        HudButton("Made live", !s.recordedEngines) { s.recordedEngines = false; s.save(ctx) }
        HudButton("Recorded loops", s.recordedEngines) { s.recordedEngines = true; s.save(ctx) }
    }
    volume("All sound", s.master) { s.master = it; s.save(ctx) }
    volume("Ambience", s.ambience) { s.ambience = it; s.save(ctx) }
    volume("Effects", s.effects) { s.effects = it; s.save(ctx) }
    volume("Vehicles", s.vehicles) { s.vehicles = it; s.save(ctx) }
    volume("Music", s.music) { s.music = it; s.save(ctx) }
}

@Composable
private fun volume(label: String, v: Float, set: (Float) -> Unit) {
    HudRow(label) {
        HudText("${(v * 100).toInt()}%", 12.sp, HudStyle.DIM, Modifier.width(44.dp), mono = true)
        // a plain slider (as the Debug tab's): drag across the track, 0 to 150 %
        Box(Modifier.width(240.dp).height(32.dp).pointerInput(Unit) {
            awaitEachGesture {
                val d = awaitFirstDown()
                set(1.5f * (d.position.x / size.width).coerceIn(0f, 1f))
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    set(1.5f * (c.position.x / size.width).coerceIn(0f, 1f)); c.consume()
                }
            }
        }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth((v / 1.5f).coerceIn(.02f, 1f)).height(4.dp).background(Color(0xFF6FA8FF), RoundedCornerShape(2.dp)))
        }
    }
}
