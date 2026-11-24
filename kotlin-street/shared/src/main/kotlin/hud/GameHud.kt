package com.lifemax.rpg.feature.habits.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The in-game HUD's layout and its pieces (kotlin-street/docs/HUD.md), platform-free so the desktop can render the same
 * layout as the phone: the minimap's corner (top left, the street and the district under it), the status cluster (top
 * right: the time and the date, the money, the menu button), the player's condition as small tags under it, the mic's
 * dot, and the menu (one button: tabs for the map, the player, the camera, the settings and the debug controls).
 * Contextual buttons (talk, shop, go inside, the car's pedals) are the app's, bottom right, only when they apply.
 * The look: dark glass panels with a thin light edge and square-ish corners, the game's pixel art on the map.
 */

object HudStyle {
    val PANEL = Color(0xD90E1320)
    val PANEL_SOLID = Color(0xF20E1320)
    val EDGE = Color(0x709FB4D8)
    val TEXT = Color.White
    val DIM = Color(0xFFB8C2D6)
    val HOT = Color(0xFFFF8A7A)
    val MONEY = Color(0xFF9BE8A4)
    val ACCENT = Color(0xFF3A6FD8)
    val SHAPE = RoundedCornerShape(5.dp)
    val MONO = FontFamily.Monospace
    /** The gap from the screen's edges (the S25's rounded corners and its camera hole stay clear of it in landscape). */
    val EDGE_GAP = 14.dp
}

@Composable
fun HudText(s: String, size: TextUnit = 13.sp, color: Color = HudStyle.TEXT, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.SemiBold,
            mono: Boolean = false, maxLines: Int = 1) =
    BasicText(s, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) HudStyle.MONO else null),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** A HUD panel: the dark glass, a thin edge. */
fun Modifier.hudPanel(solid: Boolean = false): Modifier =
    background(if (solid) HudStyle.PANEL_SOLID else HudStyle.PANEL, HudStyle.SHAPE).border(1.dp, HudStyle.EDGE, HudStyle.SHAPE)

/** A tap that takes the touch (the stick under it never sees it). */
fun Modifier.hudTap(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { onTap() } }
}

/** The top-left corner: the minimap, and where the player is under it. */
@Composable
fun BoxScope.HudMapCorner(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.align(Alignment.TopStart).padding(start = HudStyle.EDGE_GAP, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp), content = content)

/** The top-right corner: the status cluster with the menu button, the condition tags and the mic under it. */
@Composable
fun BoxScope.HudTopRight(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.align(Alignment.TopEnd).padding(end = HudStyle.EDGE_GAP, top = 8.dp), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)

/** Where the player is, under the minimap: the street (or the place), and the district and town, [width] wide. */
@Composable
fun HudLocation(street: String, area: String, width: Dp = 150.dp) {
    Column(Modifier.widthIn(max = width + 30.dp).hudPanel().padding(horizontal = 8.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HudText(street, 12.sp, weight = FontWeight.Bold)
        if (area.isNotEmpty()) HudText(area, 10.sp, HudStyle.DIM, weight = FontWeight.Medium)
    }
}

/**
 * The status cluster: the time (and the weather's word), the date, the money (red when the player carries stolen goods),
 * then [extra] (the small screenshot buttons when they are on) and the menu button ([menuBadge]: something waits in it,
 * an update).
 */
@Composable
fun HudStatus(time: String, date: String, money: String, moneyHot: Boolean, onMenu: () -> Unit, menuBadge: Boolean = false, weather: String = "",
              extra: @Composable RowScope.() -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        extra()
        Row(Modifier.hudPanel().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (weather.isNotEmpty()) HudText(weather, 11.sp, HudStyle.DIM, Modifier.padding(end = 6.dp), FontWeight.Medium)
                    HudText(time, 16.sp, weight = FontWeight.Bold, mono = true)
                }
                if (date.isNotEmpty()) HudText(date, 10.sp, HudStyle.DIM, weight = FontWeight.Medium)
            }
            if (money.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 9.dp).width(1.dp).height(28.dp).background(HudStyle.EDGE))
                HudText(money, 16.sp, if (moneyHot) HudStyle.HOT else HudStyle.MONEY, weight = FontWeight.Bold, mono = true)
            }
        }
        HudMenuButton(menuBadge, onMenu)
    }
}

/** The menu button: three pixel bars on a panel, 42 dp; a red dot when something waits in the menu. */
@Composable
fun HudMenuButton(badge: Boolean, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(Modifier.size(42.dp).hudPanel().hudTap { click() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp, 16.dp)) {
            val u = size.height / 8f
            for (i in 0 until 3) drawRect(Color.White, Offset(0f, (i * 3f) * u), Size(size.width, 2f * u))
        }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(Color(0xFFFF5A4A), CircleShape))
    }
}

/** A small round button (the screenshot and the bookmark when the owner has them on). */
@Composable
fun HudRoundButton(label: String, onClick: () -> Unit, size: Dp = 34.dp) {
    val click by rememberUpdatedState(onClick)
    Box(Modifier.size(size).background(Color(0x99101420), CircleShape).hudTap { click() }, contentAlignment = Alignment.Center) { HudText(label, 14.sp) }
}

/** The player's condition and other short notices as small tags (a sprain, hungry, the rent due), [hot] ones in red. */
@Composable
fun HudTags(tags: List<Pair<String, Boolean>>) {
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((t, hot) in tags) Box(Modifier.hudPanel().padding(horizontal = 7.dp, vertical = 2.dp)) { HudText(t, 11.sp, if (hot) HudStyle.HOT else HudStyle.DIM, weight = FontWeight.Medium) }
    }
}

/** A line of the HUD's notices under the tags (the car's fuel, a job's news), [hot] in red, at most [width] wide. */
@Composable
fun HudNote(text: String, hot: Boolean = false, width: Dp = 260.dp) {
    Box(Modifier.widthIn(max = width).hudPanel().padding(horizontal = 8.dp, vertical = 3.dp)) { HudText(text, 11.sp, if (hot) HudStyle.HOT else HudStyle.DIM, weight = FontWeight.Medium, maxLines = 2) }
}

/** The mic's state at a glance: listening, hearing (red, brighter with the voice's [level]), writing, paused. */
enum class MicDot { OFF, LISTENING, HEARING, WRITING, PAUSED, PROBLEM }

/** The always-listening mic as a small dot with the mic in it (28 dp), next to the tags; a tap does [onClick] (retry, allow). */
@Composable
fun HudMic(state: MicDot, level: Float, onClick: () -> Unit) {
    if (state == MicDot.OFF) return
    val click by rememberUpdatedState(onClick)
    val bg = when (state) {
        MicDot.HEARING -> Color(0xFF8A2E2E).copy(alpha = .55f + .45f * level.coerceIn(0f, 1f))
        MicDot.PROBLEM -> Color(0xCC8A5A2E)
        else -> HudStyle.PANEL
    }
    Box(Modifier.size(28.dp).background(bg, CircleShape).border(1.dp, HudStyle.EDGE, CircleShape).hudTap { click() }, contentAlignment = Alignment.Center) {
        HudText(when (state) { MicDot.WRITING -> "…"; MicDot.PAUSED -> "‖"; MicDot.PROBLEM -> "!"; else -> "🎤" }, 11.sp,
            if (state == MicDot.PAUSED) HudStyle.DIM else HudStyle.TEXT)
    }
}

/** A HUD button in the menu's style: a panel, highlighted when [on]. */
@Composable
fun HudButton(label: String, on: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(modifier.background(if (on) HudStyle.ACCENT else Color(0xFF1A2130), HudStyle.SHAPE).border(1.dp, if (on) Color(0xFF8FB4FF) else HudStyle.EDGE, HudStyle.SHAPE)
        .hudTap { if (enabled) click() }.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        HudText(label, 13.sp, if (enabled) HudStyle.TEXT else HudStyle.DIM)
    }
}

/** A heading in a menu page. */
@Composable
fun HudSection(title: String) = HudText(title, 12.sp, HudStyle.DIM, Modifier.padding(top = 6.dp), FontWeight.Bold)

/** A row of a menu page: a [label] and its choices. */
@Composable
fun HudRow(label: String, content: @Composable RowScope.() -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudText(label, 12.sp, HudStyle.DIM, Modifier.width(110.dp), FontWeight.Medium); content()
    }

/**
 * The menu over the game (the one button's): a dim veil, a rail of [tabs] on the left (each with a [badges] dot when
 * something waits in it), the page of the [selected] tab on the right, and "Back to the game". Takes every touch on it.
 */
@Composable
fun HudMenu(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, onClose: () -> Unit, badges: Set<Int> = emptySet(), title: String = "Menu",
            page: @Composable ColumnScope.() -> Unit) {
    val close by rememberUpdatedState(onClose)
    val pick by rememberUpdatedState(onSelect)
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(168.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudText(title, 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                tabs.forEachIndexed { i, t ->
                    Box(Modifier.fillMaxWidth().background(if (i == selected) HudStyle.ACCENT else HudStyle.PANEL, HudStyle.SHAPE)
                        .border(1.dp, if (i == selected) Color(0xFF8FB4FF) else HudStyle.EDGE, HudStyle.SHAPE).hudTap { pick(i) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        HudText(t, 14.sp, weight = FontWeight.Bold)
                        if (i in badges) Box(Modifier.align(Alignment.CenterEnd).size(8.dp).background(Color(0xFFFF5A4A), CircleShape))
                    }
                }
                Spacer(Modifier.weight(1f))
                HudButton("Back to the game", modifier = Modifier.fillMaxWidth()) { close() }
            }
            Column(Modifier.weight(1f).fillMaxHeight().hudPanel(solid = true).verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = page)
        }
    }
}
