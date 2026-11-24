package com.timerpg.street

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.loop.LoopItems
import com.lifemax.rpg.feature.habits.loop.LoopPlace

/*
 * The loop's HUD (kotlin-street/docs/LOOP.md), placeholder UI in the game's own style: the cash readout, the shop's
 * panel (buy, take, sell, work a shift), the line an action gives, the arrest card, the debug panel.
 */

private val BG = Color(0x99101420)
private val PANEL_BG = Color(0xE0101420)
private val ON = Color(0xCC3A6FD8)
private val DIM_TEXT = Color(0xFFB8C2D6)
private val HOT = Color(0xFFFF8A65)

@Composable
private fun T(s: String, size: TextUnit = 13.sp, color: Color = Color.White, modifier: Modifier = Modifier) =
    androidx.compose.foundation.text.BasicText(s, modifier, style = androidx.compose.ui.text.TextStyle(color = color, fontSize = size, fontWeight = FontWeight.SemiBold))

@Composable
fun LoopButton(label: String, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier.background(if (on) ON else if (enabled) BG else Color(0x55101420), RoundedCornerShape(10.dp)).pointerInput(enabled) {
            awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { if (enabled) click() } }
        }.padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, 13.sp, if (enabled) Color.White else DIM_TEXT) }
}

private fun Modifier.loopOpaque(): Modifier = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

/** The cash readout (and the debug panel's switch). */
@Composable
fun LoopWallet(debugOpen: Boolean, onDebug: () -> Unit) {
    val v = LoopSession.view
    val err = LoopSession.error
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(BG, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            T(when { err != null -> "loop: off"; v == null -> "…"; else -> v.cashText + if (v.items.isNotEmpty()) "  ·  ${v.items.size} item${if (v.items.size == 1) "" else "s"}" else "" },
                14.sp, if (v != null && v.items.any { it.stolen }) HOT else Color.White)
        }
        LoopButton("Loop", on = debugOpen, onClick = onDebug)
    }
}

/**
 * The shop the player stands at (or is inside): what it sells, to buy or to take; at the pawn shop what the player
 * carries, to sell; a shift to work; and waiting or sleeping. [sceneOf] makes the scene at the moment of the action.
 */
@Composable
fun LoopShopPanel(place: LoopPlace, year: Int, hour: Float, onAction: (String, Any?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v = LoopSession.view
    val busy = LoopSession.busy
    val hrs = v?.hours?.get(place.id)
    val open = hrs?.open ?: true
    Column(modifier.widthIn(max = 460.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(place.name, 15.sp)
        if (hrs != null) T(hrs.today + if (open) "" else "  -  ${hrs.closedLine}", 12.sp, if (open) DIM_TEXT else HOT)
        if (place.kind != "police_station") {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in LoopSession.stockFor(place)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val out = s.quantity <= 0
                    T("${s.label}  ${s.priceText}" + if (out) "  sold out" else "  x${s.quantity}", 13.sp, if (out) DIM_TEXT else Color.White, Modifier.widthIn(min = 200.dp))
                    if (!out) {
                        LoopButton("Buy", enabled = !busy && open && (v?.cash ?: 0) >= s.priceCents) { onAction("buy", s) }
                        LoopButton("Take", enabled = !busy && open) { onAction("take", s) }
                    }
                }
            }
            if (place.kind == "pawn_shop") {
                T("Sell:", 13.sp, DIM_TEXT)
                val items = v?.items.orEmpty()
                if (items.isEmpty()) T("(you carry nothing to sell)", 12.sp, DIM_TEXT)
                for (i in items) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val p = LoopSession.pawnPrice(i)
                    T(i.label + if (i.stolen) " (hot)" else "", 13.sp, if (i.stolen) HOT else Color.White, Modifier.widthIn(min = 200.dp))
                    LoopButton(if (p > 0) "Sell ${LoopSession.pawnPriceText(i)}" else "won't buy", enabled = !busy && open && p > 0) { onAction("sell", i) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (place.isShop) LoopButton("Work a shift", enabled = !busy && open) { onAction("work", null) }
            LoopButton("Wait 1 h", enabled = !busy) { onAction("wait", null) }
            LoopButton("Sleep till 7", enabled = !busy) { onAction("sleep", null) }
            LoopButton("Close") { onClose() }
        }
    }
}

/** A fence, met at night: what they would pay for each thing the player carries (or why not), in cents of the town's year. */
@Composable
fun LoopFencePanel(fence: com.lifemax.rpg.feature.habits.loop.LoopFence, hour: Float, onSell: (com.lifemax.rpg.feature.habits.loop.LoopItem) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v = LoopSession.view
    val busy = LoopSession.busy
    val here = fence.atHours(hour.toDouble())
    Column(modifier.widthIn(max = 460.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(fence.name, 15.sp)
        T("Sell:", 13.sp, DIM_TEXT)
        val items = v?.items.orEmpty()
        if (items.isEmpty()) T("(you carry nothing to sell)", 12.sp, DIM_TEXT)
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in items) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val q = v?.fenceQuotes?.get("${fence.personId}|${i.id}")
                T(i.label + if (i.stolen) " (hot)" else "", 13.sp, if (i.stolen) HOT else Color.White, Modifier.widthIn(min = 200.dp))
                LoopButton(if (q != null && q.accepted) "Sell ${q.text}" else q?.text ?: "...", enabled = !busy && here && q?.accepted == true) { onSell(i) }
            }
        }
        LoopButton("Close") { onClose() }
    }
}

/** The arrest card: what happened; closing it takes the player to the station. */
@Composable
fun LoopArrestCard(modifier: Modifier = Modifier) {
    val s = LoopSession.scene ?: return
    Column(modifier.widthIn(max = 480.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T("Police", 16.sp, HOT)
        T(s.line, 14.sp)
        s.moveTo?.let { T("You are taken to ${it.name}.", 12.sp, DIM_TEXT) }
        LoopButton("OK") { LoopSession.sceneDone() }
    }
}

/** The loop's state for the owner: cash, inventory, the last act's witnesses, heat, cases, the last advance, timings. */
@Composable
fun LoopDebugPanel(modifier: Modifier = Modifier) {
    val v = LoopSession.view
    Column(modifier.widthIn(max = 520.dp).heightIn(max = 420.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque()
        .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        T("The loop (Jeff's facade in QuickJS)", 14.sp)
        LoopSession.error?.let { T(it, 12.sp, HOT) }
        if (v == null) T(if (LoopSession.busy) "starting…" else "not running (the walkable town only)", 12.sp, DIM_TEXT)
        else {
            for (l in v.debug) T(l, 11.sp, if (l.startsWith("  ")) DIM_TEXT else Color.White)
            T("Recent:", 12.sp)
            for (m in v.messages.takeLast(8)) T(m, 11.sp, DIM_TEXT)
        }
    }
}

/** "POLICE" over each officer's head while they are near and on the screen (recomposed with the frame, alone). */
@Composable
fun LoopOfficerLabels(frameTick: androidx.compose.runtime.LongState, hour: Float) {
    if (frameTick.longValue <= 0L) return
    val w = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.walk ?: return
    for ((_, x, z) in LoopSession.officers(System.nanoTime())) {
        if (kotlin.math.hypot(x - w.wx, z - w.wz) > 1500f) continue
        val at = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.headOnScreen(x, z) ?: continue
        Box(Modifier.layout { m, c -> val pl = m.measure(c); layout(pl.width, pl.height) { pl.place(at.x.toInt() - pl.width / 2, at.y.toInt() - pl.height - 4) } }) {
            T("POLICE", 10.sp, Color.White, Modifier.background(Color(0xCC1A3D8F), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
    // (the fences, by their name only)
    for ((name, x, z) in LoopSession.fenceSpots(hour)) {
        if (kotlin.math.hypot(x - w.wx, z - w.wz) > 1500f) continue
        val at = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.headOnScreen(x, z) ?: continue
        Box(Modifier.layout { m, c -> val pl = m.measure(c); layout(pl.width, pl.height) { pl.place(at.x.toInt() - pl.width / 2, at.y.toInt() - pl.height - 4) } }) {
            T(name, 10.sp, Color.White, Modifier.background(Color(0xAA101420), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
    }
}
