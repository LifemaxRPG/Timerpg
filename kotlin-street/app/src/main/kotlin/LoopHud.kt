package com.timerpg.street

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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

/** The cash readout (and the debug panel's switch), with the rent warning under it when the room's rent is due, late or locked out. */
@Composable
fun LoopWallet(debugOpen: Boolean, onDebug: () -> Unit) {
    val v = LoopSession.view
    val err = LoopSession.error
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(BG, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                T(when { err != null -> "loop: off"; v == null -> "…"; else -> v.cashText + if (v.items.isNotEmpty()) "  ·  ${v.items.size} item${if (v.items.size == 1) "" else "s"}" else "" },
                    14.sp, if (v != null && v.items.any { it.stolen }) HOT else Color.White)
            }
            LoopButton("Loop", on = debugOpen, onClick = onDebug)
        }
        // (how the player is: hungry, tired ...; and the fuel of the car they drive)
        v?.needs?.labels?.takeIf { it.isNotEmpty() }?.let { l -> Box(Modifier.widthIn(max = 300.dp).background(BG, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            T(l.joinToString(" · "), 12.sp, if (l.any { it == "starving" || it == "exhausted" || it == "ill" }) HOT else DIM_TEXT) } }
        LoopSession.drivenCar?.let { c -> Box(Modifier.widthIn(max = 300.dp).background(BG, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            T("${c.label}: fuel %.1f of %.0f gal · ${c.mileage} mi".format(c.fuel, c.tank), 12.sp, if (c.fuel < 1.0) HOT else DIM_TEXT) } }
        v?.rent?.line?.let { Box(Modifier.widthIn(max = 300.dp).background(BG, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) { T(it, 12.sp, HOT) } }
        v?.jobs?.news?.takeIf { it.isNotEmpty() }?.let { Box(Modifier.widthIn(max = 300.dp).background(BG, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) { T(it, 12.sp, DIM_TEXT) } }
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
    var asking by remember { mutableStateOf(false) }
    val jobs = v?.jobs
    val job = jobs?.job
    val mine = job != null && job.placeId == place.id
    val ops = jobs?.openings?.get(place.id).orEmpty()
    Column(modifier.widthIn(max = 460.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(place.name, 15.sp)
        // (on a holiday the shut door says why: "Closed for Hearth Day. Opens tomorrow at 8 a.m.")
        if (hrs != null) T(if (open) hrs.today else if (hrs.holiday.isNotEmpty()) hrs.closedLine else "${hrs.today}  -  ${hrs.closedLine}", 12.sp, if (open) DIM_TEXT else HOT)
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
        // employment (src/jobs): the job here, or the openings this week to ask about and apply for
        if (place.kind != "police_station") {
            if (mine && job != null) {
                T(job.line, 12.sp, DIM_TEXT)
                val can = !busy && (v?.day ?: 0) != job.lastWorkedDay && hour >= job.startHour && hour < job.windowEnd
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoopButton(if (hour > job.startHour + 0.25f) "Start shift (late)" else "Start shift", enabled = can) { onAction("shift", null) }
                    LoopButton("Quit job", enabled = !busy) { onAction("quit", null) }
                }
            } else if (job != null) T("You work at ${job.placeName}.", 12.sp, DIM_TEXT)
            else {
                LoopButton(if (asking) "Hide work" else "Ask about work", on = asking) { asking = !asking }
                if (asking) {
                    if (ops.isEmpty()) T("Nothing this week.", 12.sp, DIM_TEXT)
                    for (o in ops) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(o.line, 12.sp, Color.White, Modifier.widthIn(max = 300.dp))
                        LoopButton("Apply", enabled = !busy) { onAction("apply", o.id) }
                    }
                }
            }
            jobs?.referencesText?.takeIf { it.isNotEmpty() }?.let { T(it, 11.sp, DIM_TEXT) }
        }
        // food and care: a meal or a snack where they sell it, the pharmacist's treatment for each injury
        v?.eat?.get(place.id)?.let { e -> LoopButton("${e.label} ${e.text}", enabled = !busy && open && ((v?.cash ?: 0) >= e.cents)) { onAction("eat", null) } }
        if (place.shopKind == "pharmacy") for (inj in v?.needs?.injuries.orEmpty().filter { !it.treated })
            LoopButton("Treat your ${inj.kind} ${inj.costText}", enabled = !busy && open && (v?.cash ?: 0) >= inj.costCents) { onAction("treat", inj.kind) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // (the odd-job shift stays only where nothing is on offer: a shop that is hiring, or one the player works at, takes applications and shifts)
            if (place.isShop && !mine && ops.isEmpty()) LoopButton("Work a shift", enabled = !busy && open) { onAction("work", null) }
            LoopButton("Wait 1 h", enabled = !busy) { onAction("wait", null) }
            LoopButton("Sleep rough till 7", enabled = !busy) { onAction("sleep", null) }
            LoopButton("Close") { onClose() }
        }
    }
}

/** The boarding house: rent a room, pay the rent, sleep there, leave things in it (held while the rent is unpaid). */
@Composable
fun LoopLodgingPanel(lodging: com.lifemax.rpg.feature.habits.loop.LoopLodging, hour: Float, onAction: (String, Any?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v = LoopSession.view
    val busy = LoopSession.busy
    val rent = v?.rent
    val cash = v?.cash ?: 0
    Column(modifier.widthIn(max = 460.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(lodging.name, 15.sp)
        if (rent == null) {
            T(v?.lodgingOffer ?: "", 12.sp, DIM_TEXT)
            T("To take it now: ${v?.let { LoopSession.eraMoneyText(it.lodgingDueCents) } ?: ""}", 12.sp, DIM_TEXT)
            LoopButton("Rent a room", enabled = !busy && v != null && v.lodgingDueCents > 0 && cash >= v.lodgingDueCents) { onAction("rent", null) }
        } else {
            T("Your room: ${rent.rentText}; next ${rent.nextDue}; ${rent.nights} night${if (rent.nights == 1) "" else "s"} slept.", 12.sp, DIM_TEXT)
            rent.line?.let { T(it, 12.sp, HOT) }
            v.boardLine.takeIf { it.isNotEmpty() }?.let { T(it, 12.sp, DIM_TEXT) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (v.boardLine.isNotEmpty()) LoopButton("Take your board", enabled = !busy && !rent.lockedOut) { onAction("board", null) }
                LoopButton("Wash up", enabled = !busy && !rent.lockedOut) { onAction("wash", null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rent.owedCents > 0) LoopButton("Pay rent ${rent.owedText}", enabled = !busy && cash >= rent.owedCents) { onAction("pay", null) }
                LoopButton("Sleep in your room till 7", enabled = !busy && !rent.lockedOut) { onAction("sleepRoom", null) }
            }
            val carried = v.items
            if (rent.roomItems.isNotEmpty()) T(if (rent.lockedOut) "Held by the landlady:" else "In your room:", 12.sp, DIM_TEXT)
            for (i in rent.roomItems) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(i.label, 13.sp, Color.White, Modifier.widthIn(min = 200.dp))
                LoopButton("Take back", enabled = !busy && !rent.lockedOut) { onAction("retrieve", i) }
            }
            if (carried.isNotEmpty() && !rent.lockedOut) T("Leave in your room:", 12.sp, DIM_TEXT)
            if (!rent.lockedOut) for (i in carried) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                T(i.label + if (i.stolen) " (hot)" else "", 13.sp, if (i.stolen) HOT else Color.White, Modifier.widthIn(min = 200.dp))
                LoopButton("Leave", enabled = !busy) { onAction("stash", i) }
            }
        }
        LoopButton("Close") { onClose() }
    }
}

/** A streetcar or bus stop: the next departures and the fare to each other stop on the line. */
@Composable
fun LoopStopPanel(stop: com.lifemax.rpg.feature.habits.loop.LoopStop, onRide: (com.lifemax.rpg.feature.habits.loop.RideOption) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v = LoopSession.view
    val busy = LoopSession.busy
    val info = v?.transit?.get(stop.id)
    Column(modifier.widthIn(max = 460.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(stop.name, 15.sp)
        if (info == null) T("...", 12.sp, DIM_TEXT)
        else {
            info.noService?.let { T(it, 12.sp, HOT) }
            if (info.departures.isNotEmpty()) T("Next ${info.mode}: " + info.departures.joinToString(", "), 12.sp, DIM_TEXT)
            for (r in info.rides) LoopButton("Ride to ${r.toName}: ${r.text}, ${r.minutes} min", enabled = !busy && info.noService == null && (v.cash >= r.cents)) { onRide(r) }
        }
        LoopButton("Close") { onClose() }
    }
}

/** Black, fading out over a second after a ride's end. */
@Composable
fun LoopFade(modifier: Modifier = Modifier) {
    val at = LoopSession.fadeAt
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(0f, androidx.compose.animation.core.tween(1100))
    }
    if (alpha.value > 0.01f) Box(modifier.fillMaxSize().background(Color(0xFF000000).copy(alpha = alpha.value)))
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

/**
 * The used-car lot at the gas station: the cars for sale (round asking prices), the papers, and the player's own cars (fuel by the gallon,
 * a service, a repair, a tow), docs/LOOP.md "Needs, weather and cars".
 */
@Composable
fun LoopCarLotPanel(place: LoopPlace, ctx: android.content.Context, hour: Float, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v = LoopSession.view
    val busy = LoopSession.busy
    val cars = v?.cars
    val cash = v?.cash ?: 0
    Column(modifier.widthIn(max = 480.dp).heightIn(max = 420.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("${place.name}: used cars", 15.sp)
        if (cars == null) T("...", 12.sp, DIM_TEXT)
        else {
            T("For sale:", 13.sp, DIM_TEXT)
            for (c in cars.lot) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                T("${c.label}, ${c.mileage} mi, ${c.conditionPct}%", 12.sp, Color.White, Modifier.widthIn(min = 200.dp, max = 250.dp))
                LoopButton("Buy ${c.askingText}", enabled = !busy && cash >= c.askingCents) { LoopSession.buyCar(ctx, place, c.id, hour) }
            }
            T("Papers:", 13.sp, DIM_TEXT)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in cars.papers) LoopButton(if (p.have) "${p.label} ok" else "${p.label} ${p.text}", enabled = !busy && !p.have && cash >= p.cents) { LoopSession.buyPaper(ctx, place, p.kind, hour) }
            }
            if (cars.papersLine.isNotEmpty()) T(cars.papersLine, 12.sp, HOT)
            T("Fuel ${cars.fuelText} a gallon.", 12.sp, DIM_TEXT)
            for (o in cars.owned) {
                val near = LoopSession.carNear(o.id)
                T("${o.label}: %.1f of %.0f gal, ${o.mileage} mi, ${o.conditionPct}%%".format(o.fuel, o.tank) + (o.blocked?.let { " - $it" } ?: "") + if (near) "" else " (not here)", 12.sp, if (o.blocked != null) HOT else Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoopButton("5 gal", enabled = !busy && near && o.fuel + .05 < o.tank) { LoopSession.refuel(ctx, o.id, 5.0) }
                    LoopButton("Fill up", enabled = !busy && near && o.fuel + .05 < o.tank) { LoopSession.refuel(ctx, o.id, null) }
                    LoopButton("Service ${o.serviceText}", enabled = !busy && near && cash >= o.serviceCents) { LoopSession.serviceCar(ctx, o.id) }
                }
                if (o.breakdown != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LoopButton("Repair ${o.repairText}", enabled = !busy && (o.roadside || near) && cash >= o.repairCents) { LoopSession.repairCar(ctx, o.id) }
                    if (!near) LoopButton("Tow ${o.towText}", enabled = !busy && cash >= o.towCents) { LoopSession.callTow(ctx, o.id) }
                } else if (o.blocked != null && !near) LoopButton("Tow ${o.towText}", enabled = !busy && cash >= o.towCents) { LoopSession.callTow(ctx, o.id) }
            }
        }
        LoopButton("Close") { onClose() }
    }
}

/** The driven car stopped (a breakdown or an empty tank): what happened and the ways out, fixing it here (a flat, a dead battery) or a tow. */
@Composable
fun LoopBreakdownCard(ctx: android.content.Context, modifier: Modifier = Modifier) {
    val c = LoopSession.drivenCar ?: return
    val why = c.blocked ?: return
    val busy = LoopSession.busy
    val cash = LoopSession.view?.cash ?: 0
    Column(modifier.widthIn(max = 420.dp).background(PANEL_BG, RoundedCornerShape(14.dp)).loopOpaque().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T("Your ${c.label} has stopped: $why", 14.sp, HOT)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.breakdown != null && c.roadside) LoopButton("Fix it here ${c.repairText}", enabled = !busy && cash >= c.repairCents) { LoopSession.repairCar(ctx, c.id) }
            LoopButton("Call a tow ${c.towText}", enabled = !busy && cash >= c.towCents) { LoopSession.callTow(ctx, c.id) }
        }
        T(if (c.breakdown != null && !c.roadside) "This is a garage job: the tow takes it to the lot (get out when it has stopped)." else "A tow takes the car to the lot (get out when it has stopped).", 11.sp, DIM_TEXT)
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
    // (the transit stops and the boarding house: a sign over the spot while it is in view)
    LoopSession.town?.let { t ->
        for (st in t.lines.flatMap { it.stops }) {
            if (kotlin.math.hypot(st.x - w.wx, st.z - w.wz) > 1500f) continue
            val at = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.headOnScreen(st.x, st.z) ?: continue
            Box(Modifier.layout { m, c -> val pl = m.measure(c); layout(pl.width, pl.height) { pl.place(at.x.toInt() - pl.width / 2, at.y.toInt() - pl.height - 4) } }) {
                T("STOP", 10.sp, Color.White, Modifier.background(Color(0xCC2E7D32), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        t.lodging?.let { l ->
            if (kotlin.math.hypot(l.doorX - w.wx, l.doorZ - w.wz) <= 1500f) com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.headOnScreen(l.doorX, l.doorZ)?.let { at ->
                Box(Modifier.layout { m, c -> val pl = m.measure(c); layout(pl.width, pl.height) { pl.place(at.x.toInt() - pl.width / 2, at.y.toInt() - pl.height - 4) } }) {
                    T("ROOMS", 10.sp, Color.White, Modifier.background(Color(0xCC8D5A1F), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
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
