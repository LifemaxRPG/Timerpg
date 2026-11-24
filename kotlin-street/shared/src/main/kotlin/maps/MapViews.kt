package com.lifemax.rpg.feature.habits.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The map's HUD (docs/MAPS.md): the round minimap (top right), the compass rose in its place without a map, and the
 * full-screen map with its waypoint. Platform-free Compose: the app shows them, the desktop renders them for checks.
 * The look is the HUD's pixel panels: square edges, a flat palette, nearest-filtered pixels, no blur.
 */

private val PANEL = Color(0xE6101420)
private val EDGE = Color(0xFF9FB4D8)
private val DIM = Color(0xFFB8C2D6)
private val MONO = FontFamily.Monospace

@Composable
private fun PixelText(s: String, size: Int = 12, color: Color = Color.White, modifier: Modifier = Modifier) =
    BasicText(s, modifier, style = TextStyle(color = color, fontSize = size.sp, fontWeight = FontWeight.Bold, fontFamily = MONO))

/** A square-edged HUD button in the map's style. */
@Composable
fun MapButton(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier.background(if (on) Color(0xFF3A6FD8) else PANEL).border(2.dp, if (enabled) EDGE else Color(0x669FB4D8)).pointerInput(enabled) {
            awaitEachGesture { awaitFirstDown(); waitForUpOrCancellation()?.let { if (enabled) click() } }
        }.padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) { PixelText(label, 12, if (enabled) Color.White else DIM) }
}

/** The street units a pixel of the minimap's own picture for [layer] at the zoom step [zoom] (MapSettings.ZOOMS). */
fun minimapUnits(layer: MapLayer, zoom: Int): Float {
    val base = if (layer.tier == MapTier.CITY) 72f else layer.unitsPerPx
    return base * MapSettings.ZOOMS[zoom.coerceIn(0, MapSettings.ZOOMS.size - 1)]
}

/**
 * The round minimap, [size] across: [layer] (the best map the player has here; null: the compass only) round the
 * player ([player] fills a [MapPlayer] each frame; false: the player is not known), redrawn with [tick] (the frame
 * clock); the waypoint as a pin or an edge arrow with its distance; the GPS route. Tap to open the full map ([onOpen]);
 * pinch or the +/- buttons to zoom.
 */
@Composable
fun Minimap(layer: MapLayer?, access: MapAccess, tick: LongState, player: (MapPlayer) -> Boolean, waypoint: FloatArray?, nav: MapNav,
            city: TownMapModel?, onOpen: () -> Unit, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    val painter = remember { MiniPainter() }
    val p = remember { MapPlayer() }
    val last = remember { MapPlayer() }
    val known = remember { floatArrayOf(0f) }
    val tm = rememberTextMeasurer(16)
    val open by rememberUpdatedState(onOpen)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(size).pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom > 1.15f) MapSettings.minimapZoom = (MapSettings.minimapZoom - 1).coerceAtLeast(0)
                else if (zoom < .87f) MapSettings.minimapZoom = (MapSettings.minimapZoom + 1).coerceAtMost(MapSettings.ZOOMS.size - 1)
            }
        }.pointerInput(Unit) { detectTapGestures(onTap = { open() }) }) {
            if (tick.longValue < 0) return@Canvas
            val have = player(p)
            if (have && access.showPosition) { last.set(p.x, p.z, p.heading, p.yaw); known[0] = 1f }
            if (layer == null || access.compassOnly) {
                painter.drawCompass(p.yaw)
            } else {
                // (on paper the player's spot is known only where they can read a street sign: else the map stays where they last knew it)
                val show = have && access.showPosition
                val at = if (show || known[0] == 0f) p else last.also { it.yaw = p.yaw }
                nav.update(city, access.gps, at.x, at.z, waypoint)
                painter.draw(layer, at, minimapUnits(layer, MapSettings.minimapZoom), MapSettings.headingUp, show, waypoint, if (access.route) nav.route else null, nav.distanceM)
            }
            drawImage(painter.image, IntOffset.Zero, IntSize(painter.n, painter.n), IntOffset.Zero, IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.None)
            val e = painter.edgeAt
            if (e[2] != MiniPainter.EDGE_NONE) {
                val k = this.size.width / painter.n
                val t = tm.measure(distanceText(e[2]), TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = MONO))
                val cx = this.size.width / 2f; val inward = .78f
                val x = cx + (e[0] * k - cx) * inward - t.size.width / 2f; val y = cx + (e[1] * k - cx) * inward - t.size.height / 2f
                drawRect(Color(0xE6101420), Offset(x - 3f, y - 1f), androidx.compose.ui.geometry.Size(t.size.width + 6f, t.size.height + 2f))
                drawText(t, topLeft = Offset(x, y))
            }
        }
        if (layer != null && !access.compassOnly) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MapButton(" - ") { MapSettings.minimapZoom = (MapSettings.minimapZoom + 1).coerceAtMost(MapSettings.ZOOMS.size - 1) }
            MapButton(" + ") { MapSettings.minimapZoom = (MapSettings.minimapZoom - 1).coerceAtLeast(0) }
        }
    }
}


// ---------------------------------------------------------------------------------------------- the full map

private class LabelStyles(val street: TextStyle, val place: TextStyle, val business: TextStyle, val town: TextStyle, val city: TextStyle, val capital: TextStyle, val route: TextStyle, val halo: Color)

private fun styles(paper: Boolean, planet: Boolean): LabelStyles {
    val ink = if (paper || planet) Color(0xFF2A1E12) else Color.White
    val halo = if (paper || planet) Color(0xFFF4ECD8) else Color(0xFF101420)
    fun st(sz: Int, c: Color = ink, w: FontWeight = FontWeight.Bold) = TextStyle(color = c, fontSize = sz.sp, fontWeight = w, fontFamily = MONO)
    return LabelStyles(st(11), st(11, if (paper || planet) Color(0xFF5A2E6E) else Color(0xFFF3D2FF)), st(10, if (paper || planet) Color(0xFF6E3A1A) else Color(0xFFFFD9B8), FontWeight.Medium),
        st(10), st(12), st(14), st(10, Color.White), halo)
}

/** The view of the full map: the tier shown, its centre (street units) and zoom (screen pixels a street unit). */
class FullMapView(tier: MapTier, cx: Float, cz: Float, zoom: Float) {
    var tier by mutableStateOf(tier)
    var cx by mutableFloatStateOf(cx)
    var cz by mutableFloatStateOf(cz)
    var zoom by mutableFloatStateOf(zoom)
}

/**
 * The full-screen map of the best tier the player has for here ([access]), [data]'s pictures: pan and pinch to zoom
 * (zooming out past the city map goes to the road map, then the world; zooming in goes back), tap to set the waypoint,
 * "Clear" to clear it. The city tier names the streets, the landmarks and (close in) the businesses; the road map names
 * the towns, the routes and the wonders; the world map the capitals and cities. The caption says "GPS" or the year the
 * paper map was printed.
 */
@Composable
fun FullMap(data: MapData, access: MapAccess, townYear: Int, player: MapPlayer?, waypoint: FloatArray?, nav: MapNav,
            onWaypoint: (Float?, Float?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, start: FullMapView? = null) {
    val tm = rememberTextMeasurer(256)
    val first = access.opens ?: MapTier.CITY
    val view = remember { start ?: FullMapView(first, player?.x ?: (data.city.x0 + data.city.x1) / 2f, player?.z ?: (data.city.z0 + data.city.z1) / 2f, 0f) }
    @Suppress("UNUSED_VARIABLE") val v = data.version
    val style = if (access.gps) MapStyle.GPS else MapStyle.PAPER
    fun layerOf(t: MapTier): MapLayer? = (if (access.gps) townYear else access.printYear(t))?.let { data.layer(t, style, it) }
    val layer = layerOf(view.tier)
    val routePath = remember { Path() }
    val paint = remember { Paint().apply { isAntiAlias = false; this.style = PaintingStyle.Stroke; strokeCap = StrokeCap.Square } }
    val fillPaint = remember { Paint().apply { isAntiAlias = false } }
    val wp by rememberUpdatedState(waypoint)
    val tierOf by rememberUpdatedState({ t: MapTier -> layerOf(t) })
    val accessNow by rememberUpdatedState(access)
    if (waypoint != null && player != null) nav.update(data.city, access.gps, player.x, player.z, waypoint)
    Box(modifier.fillMaxSize().background(layer?.let { Color(it.background) } ?: Color(0xFF0B0E16))) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoomBy, _ ->
                val z0 = view.zoom
                if (z0 <= 0f) return@detectTransformGestures
                val z1 = (z0 * zoomBy).coerceIn(1e-6f, 0.2f)
                // (the point under the fingers stays put)
                val wx = view.cx + (centroid.x - size.width / 2f) / z0; val wz = view.cz + (centroid.y - size.height / 2f) / z0
                view.zoom = z1
                view.cx = wx - (centroid.x - size.width / 2f) / z1 - pan.x / z1
                view.cz = wz - (centroid.y - size.height / 2f) / z1 - pan.y / z1
                autoTier(view, accessNow, tierOf, data.city, size.width.toFloat())
            }
        }.pointerInput(Unit) {
            detectTapGestures(onTap = { o ->
                if (view.zoom > 0f) onWaypoint(view.cx + (o.x - size.width / 2f) / view.zoom, view.cz + (o.y - size.height / 2f) / view.zoom)
            })
        }) {
            if (layer == null) return@Canvas
            if (view.zoom <= 0f) view.zoom = fitZoom(layer, data.city, size.width, size.height)
            drawLayer(layer, view)
            val sx = { x: Float -> size.width / 2f + (x - view.cx) * view.zoom }
            val sy = { z: Float -> size.height / 2f + (z - view.cz) * view.zoom }
            // the route (GPS, in town) or the bearing (a straight dashed line from the player, when their spot is known)
            val w = wp
            val pk = player != null && access.showPosition
            if (w != null && player != null) {
                nav.update(data.city, access.gps, player.x, player.z, w)
                val r = if (access.route) nav.route else null
                drawIntoCanvas { c ->
                    if (r != null) {
                        routePath.reset()
                        for (i in 0 until r.pts.size / 2) { val x = sx(r.pts[i * 2]); val y = sy(r.pts[i * 2 + 1]); if (i == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y) }
                        paint.color = MiniPainter.ROUTE_EDGE; paint.strokeWidth = 9f; c.drawPath(routePath, paint)
                        paint.color = MiniPainter.ROUTE; paint.strokeWidth = 5f; c.drawPath(routePath, paint)
                    } else if (pk) {
                        paint.color = Color(0xFFFF4FA3); paint.strokeWidth = 3f
                        val ax = sx(player.x); val ay = sy(player.z); val bx = sx(w[0]); val by = sy(w[1])
                        val len = kotlin.math.hypot(bx - ax, by - ay); var s = 0f
                        while (s < len) { val e = min(len, s + 14f); c.drawLine(Offset(ax + (bx - ax) * s / len, ay + (by - ay) * s / len), Offset(ax + (bx - ax) * e / len, ay + (by - ay) * e / len), paint); s += 24f }
                    }
                }
            }
            drawLabels(layer, view, tm, style == MapStyle.PAPER)
            // the waypoint's pin
            if (w != null) {
                val x = sx(w[0]).roundToInt().toFloat(); val y = sy(w[1]).roundToInt().toFloat()
                drawIntoCanvas { c ->
                    fillPaint.color = Color(0xFF101420); c.drawRect(x - 9f, y - 27f, x + 9f, y - 9f, fillPaint); c.drawRect(x - 3f, y - 11f, x + 3f, y + 1f, fillPaint)
                    fillPaint.color = Color(0xFFFF4FA3); c.drawRect(x - 6f, y - 24f, x + 6f, y - 12f, fillPaint); c.drawRect(x - 1f, y - 12f, x + 1f, y - 1f, fillPaint)
                }
            }
            // the player's arrow (where they can tell where they are)
            if (pk) {
                val x = sx(player!!.x); val y = sy(player.z)
                rotate(Math.toDegrees(player.heading.toDouble()).toFloat(), Offset(x, y)) {
                    val a = Path().apply { moveTo(x, y - 16f); lineTo(x + 12f, y + 12f); lineTo(x, y + 5f); lineTo(x - 12f, y + 12f); close() }
                    drawIntoCanvas { c ->
                        paint.color = Color(0xFF101420); paint.strokeWidth = 4f; c.drawPath(a, paint)
                        fillPaint.color = Color.White; c.drawPath(a, fillPaint)
                    }
                }
            }
        }
        // the top bar: the tier, the caption, the waypoint, the minimap's setting, close
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in MapTier.entries.reversed()) if (access.printYear(t) != null) MapButton(t.word, on = view.tier == t) { view.tier = t; view.zoom = 0f }
            }
            val caption = when {
                layer == null -> "Drawing the map…"
                access.gps -> "${view.tier.word} · GPS · live"
                else -> "${view.tier.word} · Printed ${access.printYear(view.tier)}" + if (!access.showPosition) " · you can't tell where you are" else ""
            }
            Box(Modifier.background(PANEL).border(2.dp, EDGE).padding(horizontal = 8.dp, vertical = 4.dp)) { PixelText(caption, 12) }
            if (waypoint != null) Box(Modifier.background(PANEL).border(2.dp, EDGE).padding(horizontal = 8.dp, vertical = 4.dp)) {
                PixelText("Waypoint: " + distanceText(nav.distanceM) + if (access.route) " by road" else " as the crow flies", 12, Color(0xFFFFB3D9))
            } else PixelText("Tap the map to set a waypoint", 11, DIM)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (waypoint != null) MapButton("Clear waypoint") { onWaypoint(null, null) }
            MapButton(if (MapSettings.headingUp) "Minimap: heading up" else "Minimap: north up") { MapSettings.headingUp = !MapSettings.headingUp }
            MapButton("Close") { onClose() }
        }
    }
}

/** The zoom that shows [layer] well: the city about 900 m across the screen's width, a planet map whole. */
private fun fitZoom(layer: MapLayer, city: TownMapModel, w: Float, h: Float): Float =
    if (layer.tier == MapTier.CITY) w / (900f * VehicleWorld.U) else min(w / (layer.x1 - layer.x0), h / (layer.z1 - layer.z0)) * .95f

/** Zooming out past a map's useful range moves to the next wider tier the player has, zooming in to the closer one. */
private fun autoTier(view: FullMapView, access: MapAccess, layerOf: (MapTier) -> MapLayer?, city: TownMapModel, w: Float) {
    val l = layerOf(view.tier) ?: return
    val wider = MapTier.entries.getOrNull(view.tier.ordinal - 1)?.takeIf { access.printYear(it) != null }
    val closer = MapTier.entries.getOrNull(view.tier.ordinal + 1)?.takeIf { access.printYear(it) != null }
    val shown = (l.x1 - l.x0) * view.zoom
    if (wider != null && shown < w * .45f) { view.tier = wider; return }
    if (closer != null && l.unitsPerPx * view.zoom > (if (view.tier == MapTier.WORLD) 7f else 22f)) {
        val c = layerOf(closer) ?: return
        if (view.cx in c.x0..c.x1 && view.cz in c.z0..c.z1) view.tier = closer
    }
}

/** The visible part of [layer]'s picture, scaled with nearest filtering. */
private fun DrawScope.drawLayer(layer: MapLayer, view: FullMapView) {
    val z = view.zoom
    val k = layer.unitsPerPx * z // screen pixels a map pixel
    val left = (view.cx - size.width / 2f / z - layer.x0) / layer.unitsPerPx; val top = (view.cz - size.height / 2f / z - layer.z0) / layer.unitsPerPx
    val sx0 = floor(left).toInt().coerceIn(0, layer.w); val sx1 = ceil(left + size.width / k).toInt().coerceIn(0, layer.w)
    val sz0 = floor(top).toInt().coerceIn(0, layer.h); val sz1 = ceil(top + size.height / k).toInt().coerceIn(0, layer.h)
    if (sx1 <= sx0 || sz1 <= sz0) return
    drawImage(layer.image, IntOffset(sx0, sz0), IntSize(sx1 - sx0, sz1 - sz0), IntOffset(((sx0 - left) * k).roundToInt(), ((sz0 - top) * k).roundToInt()),
        IntSize(((sx1 - sx0) * k).roundToInt().coerceAtLeast(1), ((sz1 - sz0) * k).roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.None)
}

/**
 * The names on the map, the most important first, each where it does not overlap one already drawn and only at the
 * zooms it belongs to: a street's name along its road when the road is long enough on screen to hold it, landmarks a
 * little closer in, businesses only close in; towns by rank on the road map.
 */
private fun DrawScope.drawLabels(layer: MapLayer, view: FullMapView, tm: TextMeasurer, paper: Boolean) {
    val st = styles(paper, layer.tier != MapTier.CITY)
    val z = view.zoom
    val metresPerPx = 1f / (z * VehicleWorld.U)
    val taken = ArrayList<FloatArray>()
    val seen = HashMap<String, MutableList<Offset>>()
    for (l in layer.byPriority) {
        val style = when (l.kind) {
            LabelKind.STREET -> if (metresPerPx < 4f) st.street else null
            LabelKind.LANDMARK, LabelKind.CIVIC -> if (metresPerPx < 3f) st.place else null
            LabelKind.BUSINESS -> if (metresPerPx < .9f) st.business else null
            LabelKind.CAPITAL -> st.capital
            LabelKind.CITY -> st.city
            LabelKind.TOWN -> if (metresPerPx < (if (l.priority >= 200f) 900f else 350f)) st.town else null
            LabelKind.WONDER -> if (metresPerPx < (if (l.priority >= 300f) 1400f else 450f)) st.place else null
            LabelKind.ROUTE -> if (metresPerPx < 700f) st.route else null
            LabelKind.SEA -> st.town
        } ?: continue
        val x = size.width / 2f + (l.x - view.cx) * z; val y = size.height / 2f + (l.z - view.cz) * z
        if (x < -200f || y < -50f || x > size.width + 200f || y > size.height + 50f) continue
        val t: TextLayoutResult = tm.measure(l.text, style)
        val tw = t.size.width.toFloat(); val th = t.size.height.toFloat()
        if (l.kind == LabelKind.STREET && l.span * z < tw + 12f) continue
        // (one street name a short way along the street is enough)
        if (l.kind == LabelKind.STREET) { val near = seen[l.text]; if (near != null && near.any { kotlin.math.hypot(it.x - x, it.y - y) < max(260f, tw * 2.2f) }) continue }
        // the box it takes (turned streets: the box round the turned text)
        val a = Math.toRadians(l.angle.toDouble()); val ca = kotlin.math.abs(kotlin.math.cos(a)).toFloat(); val sa = kotlin.math.abs(kotlin.math.sin(a)).toFloat()
        val bw = tw * ca + th * sa; val bh = tw * sa + th * ca
        val dotted = l.kind == LabelKind.TOWN || l.kind == LabelKind.CITY || l.kind == LabelKind.CAPITAL
        val ox = if (dotted) x + 7f else x - bw / 2f; val oy = if (dotted) y - bh / 2f else y - bh / 2f
        val box = floatArrayOf(ox - 2f, oy - 1f, ox + bw + 2f, oy + bh + 1f)
        if (taken.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
        taken += box
        if (l.kind == LabelKind.STREET) seen.getOrPut(l.text) { ArrayList() } += Offset(x, y)
        if (l.kind == LabelKind.ROUTE) {
            // a route number in a small square shield
            drawRect(Color(0xFF101420), Offset(x - tw / 2f - 4f, y - th / 2f - 2f), androidx.compose.ui.geometry.Size(tw + 8f, th + 4f))
            drawRect(Color(0xFF2E6B3A), Offset(x - tw / 2f - 2f, y - th / 2f), androidx.compose.ui.geometry.Size(tw + 4f, th))
            drawText(t, topLeft = Offset(x - tw / 2f, y - th / 2f))
            continue
        }
        if (l.kind == LabelKind.BUSINESS || l.kind == LabelKind.LANDMARK || l.kind == LabelKind.CIVIC) {
            // (a square dot on the building)
            drawRect(st.halo, Offset(x - 3f, y - 3f), androidx.compose.ui.geometry.Size(6f, 6f))
            drawRect(style.color, Offset(x - 2f, y - 2f), androidx.compose.ui.geometry.Size(4f, 4f))
        }
        val tx = if (dotted) x + 7f else x - tw / 2f
        val ty = if (l.kind == LabelKind.BUSINESS || l.kind == LabelKind.LANDMARK || l.kind == LabelKind.CIVIC) y + 4f else y - th / 2f
        rotate(l.angle, Offset(x, y)) {
            // a hard one-pixel halo (four offsets), then the text
            for ((dx, dy) in HALO) drawText(t, color = st.halo, topLeft = Offset(tx + dx, ty + dy))
            drawText(t, topLeft = Offset(tx, ty))
        }
    }
}

private val HALO = listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f)

/** The compass rose alone, [size] across, turned with the camera's [yaw] (the corner without a map). */
@Composable
fun CompassRose(tick: LongState, yaw: () -> Float, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val painter = remember { MiniPainter(40) }
    Canvas(modifier.size(size)) {
        if (tick.longValue < 0) return@Canvas
        painter.drawCompass(yaw())
        drawImage(painter.image, IntOffset.Zero, IntSize(painter.n, painter.n), IntOffset.Zero, IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.None)
    }
}
