import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.hud.HudButton
import com.lifemax.rpg.feature.habits.hud.HudLocation
import com.lifemax.rpg.feature.habits.hud.HudMapCorner
import com.lifemax.rpg.feature.habits.hud.HudMenu
import com.lifemax.rpg.feature.habits.hud.HudMic
import com.lifemax.rpg.feature.habits.hud.HudNote
import com.lifemax.rpg.feature.habits.hud.HudRow
import com.lifemax.rpg.feature.habits.hud.HudSection
import com.lifemax.rpg.feature.habits.hud.HudStatus
import com.lifemax.rpg.feature.habits.hud.HudTags
import com.lifemax.rpg.feature.habits.hud.HudText
import com.lifemax.rpg.feature.habits.hud.HudTopRight
import com.lifemax.rpg.feature.habits.hud.MicDot
import com.lifemax.rpg.feature.habits.hud.hudPanel
import com.lifemax.rpg.feature.habits.maps.MapAccess
import com.lifemax.rpg.feature.habits.maps.MapLayer
import com.lifemax.rpg.feature.habits.maps.MapNav
import com.lifemax.rpg.feature.habits.maps.MapPlayer
import com.lifemax.rpg.feature.habits.maps.MapPoi
import com.lifemax.rpg.feature.habits.maps.Minimap
import com.lifemax.rpg.feature.habits.maps.TownMapModel

/** Which HUD state the mock shows. */
enum class HudMock { WALK, DRIVE, MENU }

/**
 * The HUD as GameActivity lays it out (docs/HUD.md), with the shared pieces the app uses (hud/GameHud.kt, the real
 * minimap) and stand-ins for the app-only ones (the stick, the bike button, the action chips, the car's pedals, drawn
 * where and as big as the app draws them), over a plain painted street: a layout check from the desktop, not a capture.
 */
@Composable
fun hudMock(mode: HudMock, layerFor: (Float) -> MapLayer?, access: MapAccess, tick: LongState, player: MapPlayer, wp: FloatArray, nav: MapNav,
            model: TownMapModel, extra: List<MapPoi>) {
    val driving = mode == HudMock.DRIVE
    Box(Modifier.fillMaxSize()) {
        // the backdrop: a sky, the far houses, the street
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF8FB8E0), Color(0xFFCFE0EC)), 0f, size.height * .45f), size = Size(size.width, size.height * .45f))
            for (i in 0 until 9) drawRect(Color(if (i % 2 == 0) 0xFF9A4A3A else 0xFFB0A080), Offset(i * size.width / 9f, size.height * .2f), Size(size.width / 9f - 12f, size.height * .25f))
            drawRect(Color(0xFF5E8A4A), Offset(0f, size.height * .45f), Size(size.width, size.height * .55f))
            drawRect(Color(0xFFB8B4AA), Offset(0f, size.height * .5f), Size(size.width, size.height * .06f))
            drawRect(Color(0xFF3A3C42), Offset(0f, size.height * .56f), Size(size.width, size.height * .3f))
            for (i in 0 until 12) drawRect(Color(0xFFE8E2C0), Offset(i * size.width / 12f, size.height * .705f), Size(size.width / 24f, 6f))
        }
        // the player (a stand-in figure) in the middle
        Box(Modifier.align(Alignment.Center).size(26.dp, 52.dp).background(Color(0xFF6A4A8A), RoundedCornerShape(8.dp)))
        // the stick (bottom left, where the left thumb rests) and, on foot, the bike button beside it
        Canvas(Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 26.dp).size(120.dp)) {
            drawCircle(Color(0x33FFFFFF)); drawCircle(Color(0x66FFFFFF), style = Stroke(3f)); drawCircle(Color(0x88FFFFFF), size.minDimension / 4.5f)
        }
        if (!driving) Box(Modifier.align(Alignment.BottomStart).padding(start = 278.dp, bottom = 22.dp).size(58.dp).background(Color(0x99101420), CircleShape), contentAlignment = Alignment.Center) { HudText("🚲", 22.sp) }
        // the contextual actions, bottom right: only what applies here (on foot by a shop and a person)
        if (!driving) Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in listOf("Talk to Ray", "🛒 Brennan's Gym", "Go inside")) Box(Modifier.background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) { HudText(l, 13.sp) }
        }
        // the car's controls, bottom right and large (DriveHud)
        if (driving) Column(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 26.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(Color(0xCC101420), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) { HudText("⟲", 18.sp) }
                Box(Modifier.background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) { HudText("45 mph", 18.sp) }
                Box(Modifier.background(Color(0x55101420), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) { HudText("Exit", 15.sp, Color(0x88FFFFFF)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                for ((l, s, c) in listOf(Triple("Hand", 56, 0x99404858), Triple("Brake", 74, 0xAA8A2A2A), Triple("Gas", 92, 0xAA2A7A3A)))
                    Box(Modifier.size(s.dp).background(Color(c), CircleShape), contentAlignment = Alignment.Center) { HudText(l, 15.sp) }
            }
        }
        // the minimap (top left) and where the player is
        HudMapCorner {
            Minimap(layerFor, access, tick, { it.set(player.x, player.z, player.heading, if (driving) Math.toDegrees(player.heading.toDouble()).toFloat() else player.yaw, if (driving) 20f else 0f); true },
                wp, nav, model, onOpen = {}, size = 150.dp, extra = extra, nanos = { 1_000_000_000L })
            HudLocation("Juniper Way", "Oak Gardens · gamma")
        }
        // the status (top right): the time, the date, the money, the menu; the condition and the mic under it
        HudTopRight {
            HudStatus("5:36 PM", "Tue 6 Bloommonth", "$0.60", moneyHot = false, onMenu = {}, menuBadge = true, weather = "Clear")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                HudTags(listOf("sprain" to false, "hungry" to true))
                HudMic(MicDot.LISTENING, 0f) {}
            }
            if (driving) HudNote("Sedan: fuel 6.2 of 14 gal · 41,022 mi")
        }
        // the building in front (top centre, while it applies)
        if (!driving) Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(Color(0x99101420), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            HudText("Brennan's Gym  ·  Ray Kowalski", 15.sp)
        }
        if (mode == HudMock.MENU) HudMenu(listOf("Map", "You", "Camera", "Settings", "Debug"), 4, {}, {}, badges = setOf(3)) {
            HudText("Debug", 16.sp)
            HudText("Test controls: they change the world for a look, not the game's own clock and sky.", 12.sp, Color(0xFFB8C2D6), maxLines = 2)
            HudSection("Time of day")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudText("5:36 PM", 14.sp, mono = true)
                Box(Modifier.width(320.dp).height(4.dp).background(Color(0x66FFFFFF))) { Box(Modifier.fillMaxWidth(.73f).height(4.dp).background(Color(0xFF6FA8FF))) }
            }
            HudSection("Season")
            HudRow("Season") { for ((l, on) in listOf("🌸" to true, "☀" to false, "🍂" to false, "❄" to false)) HudButton(l, on) {} }
            HudSection("Weather")
            HudRow("Sky") { for ((l, on) in listOf("Auto: clear" to true, "Clear" to false, "Overcast" to false, "Rain" to false, "Mist" to false)) HudButton(l, on) {} }
            HudSection("Cars")
            HudRow("Test car") { HudButton("🚗 Spawn a car beside me") {} }
            HudSection("The loop")
            HudRow("Loop panel") { HudButton("Show the loop's debug panel") {} }
        }
    }
}

