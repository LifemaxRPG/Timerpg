package com.timerpg.street

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.domain.RailNet
import com.lifemax.rpg.feature.habits.domain.RailRide
import com.lifemax.rpg.feature.habits.domain.RailRoute
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import kotlin.math.hypot

/**
 * Taking the train (docs/RAIL.md "Riding"): the ticket window at the depot ([TicketWindow]: search a town by name, the
 * route with its legs, changes, times and fare), boarding ([request]), the ride on the engine's thread ([frame]: the
 * Filament town shows the train leaving through the countryside, [FilamentTown.startRide]) and the arrival.
 *
 * The hook still open: the app has one town (gamma). Arriving elsewhere should load that town's assets at its depot
 * (TownSession.load(seed) with the player placed at its depot); until other towns ship, the ride ends with a card and the
 * player steps off back at the home town's depot, the clock moved on by the trip.
 */
object RailTrip {
    /** A ride asked for by the UI, started by the engine's thread at its next frame. */
    @Volatile var request: RailRide? = null
    /** The ride under way (set once it has started), and how far it is (0..1). */
    @Volatile var ride: RailRide? = null; private set
    @Volatile var progress = 0f; private set
    /** The UI asks the ride to end (skipped, or the arrival card closed). */
    @Volatile var stop = false

    /** The engine's thread, each town frame: starts what was asked, follows the ride, ends it when asked. */
    fun frame(ft: FilamentTown) {
        request?.let { r -> request = null; stop = false; ft.startRide(r); ride = r; progress = 0f
            com.timerpg.street.shot.Crumbs.add("rail: riding ${r.from} -> ${r.to}, ${r.route.legs?.size} legs, %.0f units".format(java.util.Locale.US, r.path.length)) }
        if (ride != null) {
            progress = if (ft.rideSeconds > 0f) (ft.rideT / ft.rideSeconds).coerceIn(0f, 1f) else 1f
            if (stop) { ft.stopRide(); ride = null; stop = false }
        }
    }

    /** Is the player at the depot (within [reach] of it, in the town frame)? */
    fun atDepot(net: RailNet, x: Float, z: Float, reach: Float = 420f): Boolean {
        val d = net.rail.depot ?: return false
        val cx = (d.rect[0] + d.rect[2]) / 2f; val cz = (d.rect[1] + d.rect[3]) / 2f
        return hypot(x - cx, z - cz) < reach
    }
}

private val PANEL = Color(0xE6101420)
private val DIM = Color(0xFFB8C2D6)
private val LINK = Color(0xFF6FA8FF)

@Composable
private fun T(s: String, size: TextUnit = 14.sp, color: Color = Color.White, bold: Boolean = false, modifier: Modifier = Modifier) =
    BasicText(s, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))

@Composable
private fun Button(label: String, on: Boolean = false, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(Modifier.background(if (on) Color(0xCC3A6FD8) else Color(0x99303A50), RoundedCornerShape(10.dp))
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { click() } } }
        .padding(horizontal = 12.dp, vertical = 7.dp)) { T(label, 13.sp, bold = true) }
}

/**
 * The ticket window: [net]'s towns searched by name (and the ones the player has [heard] of, first), the quickest route
 * from the home town in [year] leaving at [hour], its legs (train or road), changes, times and fare; "Board" takes the
 * first train ([onBoard]) when the first leg is a train from here.
 */
@Composable
fun TicketWindow(net: RailNet, year: Int, hour: Float, heard: List<String>, onBoard: (RailRoute) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, fareText: (Double) -> String? = { null }) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val here = net.home
    val station = net.status(here.id, year)
    val service = if (year >= net.serviceFrom) net.serviceFull else net.linksAt(here.id).firstOrNull { it.passengersOn(year) }?.company ?: "the railroad"
    Column(modifier.widthIn(max = 560.dp).background(PANEL, RoundedCornerShape(14.dp)).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("${here.name} depot", 17.sp, bold = true)
            T(if (station == 'p') service else "no passenger trains stop here in $year", 12.sp, DIM)
        }
        // the search: a town's name, any part of a word
        Box(Modifier.fillMaxWidth().background(Color(0x55FFFFFF), RoundedCornerShape(8.dp)).padding(8.dp)) {
            if (query.isEmpty()) T("Where to? Type a town's name", 14.sp, DIM)
            BasicTextField(query, { query = it; picked = null }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth())
        }
        val pick = picked
        if (pick == null) {
            val heardTowns = heard.mapNotNull { net.find(it) }.distinctBy { it.id }
            val list = if (query.isBlank()) (heardTowns + net.linksAt(here.id).map { net.townOf(it.other(here.id))!! } + net.towns.filter { it.cx in -2..2 && it.cy in -1..1 && it.id != here.id }).distinctBy { it.id }.take(10)
                else (heardTowns.filter { it.name.lowercase().contains(query.trim().lowercase()) } + net.search(query)).distinctBy { it.id }
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (list.isEmpty()) T("No town of that name within reach.", 13.sp, DIM)
                for (t in list) {
                    val st = net.status(t.id, year)
                    Row(Modifier.fillMaxWidth().background(Color(0x22FFFFFF), RoundedCornerShape(8.dp)).pointerInput(t.id) { awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { picked = t.id } } }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(t.name, 15.sp, bold = true)
                        T("${t.kind} · ${if (st == 'p') "station" else "no trains: nearest by road"}${if (t.name in heard) " · heard of" else ""}", 12.sp, DIM)
                    }
                }
            }
        } else {
            val route = remember(pick, year, hour) { net.planRoute(here.id, pick, year, hour * 60.0) }
            RouteCard(net, route, fareText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val first = route.legs?.firstOrNull()
                if (route.ok && first?.mode == "train" && first.from == here.id) Button("Board the ${RailNet.clock(first.depart)} train", on = true) { onBoard(route) }
                Button("Another town") { picked = null }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button("Close", onClick = onClose) }
    }
}

/** A route as the ticket window shows it: each leg with its times, the changes, the fare, the notes. */
@Composable
fun RouteCard(net: RailNet, route: RailRoute, fareText: (Double) -> String? = { null }) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!route.ok) { T(route.reason ?: "No route.", 14.sp); route.nearest?.let { T("Nearest station: $it", 12.sp, DIM) }; return@Column }
        val dest = net.townOf(route.to)?.name ?: route.to
        T("To $dest: ${RailNet.clock(route.depart)} - ${RailNet.clock(route.arrive)}${if (route.arrive >= 1440) " (next day)" else ""}, ${route.changes} change${if (route.changes == 1) "" else "s"}, ${RailNet.fmt1(route.km)} km, fare ${if (route.fare > 0.0) fareText(route.km) ?: "$%.2f".format(route.fare) else "$%.2f".format(route.fare)}", 14.sp, bold = true)
        for (l in route.legs!!) {
            if (l.mode == "train") {
                T("${RailNet.clock(l.depart)}  ${l.fromName} → ${l.toName}  ${RailNet.clock(l.arrive)}", 13.sp, LINK)
                T("   ${l.operator} · ${RailNet.fmt1(l.km)} km${if (l.stops.isNotEmpty()) " · calls at ${l.stops.joinToString(", ")}" else ""}", 12.sp, DIM)
            } else T("${RailNet.clock(l.depart)}  ${l.fromName} → ${l.toName} by road (bus or a lift), ${RailNet.fmt1(l.km)} km  ${RailNet.clock(l.arrive)}", 13.sp)
        }
        for (n in route.notes) T(n, 12.sp, DIM)
    }
}

/** Over the ride: where to, and how far; then the arrival. [onDone] when the player closes it (or skips). */
@Composable
fun RideOverlay(net: RailNet, ride: RailRide, progress: Float, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val dest = net.townOf(ride.to)?.name ?: ride.to
    val legs = ride.route.legs.orEmpty()
    Column(modifier.widthIn(max = 520.dp).background(PANEL, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress < 1f) {
            T("On the train to $dest", 15.sp, bold = true)
            T("Arriving ${RailNet.clock(legs.lastOrNull { it.mode == "train" }?.arrive ?: 0.0)} · ${(progress * 100).toInt()}%", 12.sp, DIM)
            Button("Skip ahead", onClick = onDone)
        } else {
            T("Arrived at $dest, ${RailNet.clock(legs.lastOrNull()?.arrive ?: 0.0)}", 16.sp, bold = true)
            if (ride.route.to != ride.to) T("From here by road to ${net.townOf(ride.route.to)?.name}.", 13.sp, DIM)
            T("Other towns are not in the app yet: you step off back at ${net.home.name}'s depot, the clock moved on.", 12.sp, DIM)
            Button("Step off", on = true, onClick = onDone)
        }
    }
}
