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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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

/**
 * The round minimap, [size] across (docs/MAPS.md, "The minimap's look"): [layerFor] gives the map to draw at a number of
 * metres a picture pixel (in town [MapData.cityFor]: the city map drawn for the zoom; on the road the road map), round
 * the player ([player] fills a [MapPlayer] each frame; false: the player is not known), redrawn with [tick] (the frame
 * clock); the waypoint as a pin or a rim arrow, its distance on the bezel; the GPS route; the camera's view cone; the
 * places' icons ([MapLayer.pois] and [extra]: the player's room, the station). Tap to open the full map ([onOpen]);
 * pinch to zoom, a long press steps the zoom out (round to the closest again). Without a map: the compass alone.
 */
@Composable
fun Minimap(layerFor: (Float) -> MapLayer?, access: MapAccess, tick: LongState, player: (MapPlayer) -> Boolean, waypoint: FloatArray?, nav: MapNav,
            city: TownMapModel?, onOpen: () -> Unit, modifier: Modifier = Modifier, size: Dp = 150.dp, extra: List<MapPoi> = emptyList(),
            nanos: () -> Long = { System.nanoTime() }) {
    val painter = remember { MiniPainter() }
    val p = remember { MapPlayer() }
    val last = remember { MapPlayer() }
    val known = remember { floatArrayOf(0f) }
    val tm = rememberTextMeasurer(64)
    val open by rememberUpdatedState(onOpen)
    val source by rememberUpdatedState(layerFor)
    val more by rememberUpdatedState(extra)
    val steps = MapSettings.MINIMAP_RADIUS_M.size
    Canvas(modifier.size(size).pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ ->
            if (zoom > 1.15f) MapSettings.minimapZoom = (MapSettings.minimapZoom - 1).coerceAtLeast(0)
            else if (zoom < .87f) MapSettings.minimapZoom = (MapSettings.minimapZoom + 1).coerceAtMost(steps - 1)
        }
    }.pointerInput(Unit) { detectTapGestures(onTap = { open() }, onLongPress = { MapSettings.minimapZoom = (MapSettings.minimapZoom + 1) % steps }) }) {
        if (tick.longValue < 0) return@Canvas
        val have = player(p)
        if (have && access.showPosition) { last.set(p.x, p.z, p.heading, p.yaw, p.speed); known[0] = 1f }
        if (access.compassOnly) {
            painter.drawCompass(this, p.yaw, paper = !access.gps)
        } else {
            // (on paper the player's spot is known only where they can read a street sign: else the map stays where they last knew it)
            val show = have && access.showPosition
            val at = if (show || known[0] == 0f) p else last.also { it.yaw = p.yaw; it.speed = 0f }
            nav.update(city, access.gps, at.x, at.z, waypoint)
            painter.draw(this, source, at, MapSettings.minimapZoom, MapSettings.headingUp, MapSettings.autoZoom, show, waypoint,
                if (access.route) nav.route else null, nav.distanceM, more, nanos(), tm)
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
            onWaypoint: (Float?, Float?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, start: FullMapView? = null,
            extra: List<MapPoi> = emptyList(), legendOpen: Boolean = false, nanos: () -> Long = { System.nanoTime() }) {
    val tm = rememberTextMeasurer(256)
    val first = access.opens ?: MapTier.CITY
    val view = remember { start ?: FullMapView(first, player?.x ?: (data.city.x0 + data.city.x1) / 2f, player?.z ?: (data.city.z0 + data.city.z1) / 2f, 0f) }
    @Suppress("UNUSED_VARIABLE") val v = data.version
    val style = if (access.gps) MapStyle.GPS else MapStyle.PAPER
    fun layerOf(t: MapTier): MapLayer? = (if (access.gps) townYear else access.printYear(t))?.let { data.layer(t, style, it) }
    val layer = layerOf(view.tier)
    val routePath = remember { Path() }
    val paint = remember { Paint().apply { isAntiAlias = false; this.style = PaintingStyle.Stroke; strokeCap = StrokeCap.Square } }
    val roundPaint = remember { Paint().apply { isAntiAlias = false; this.style = PaintingStyle.Stroke; strokeCap = StrokeCap.Round; strokeJoin = androidx.compose.ui.graphics.StrokeJoin.Round } }
    val fillPaint = remember { Paint().apply { isAntiAlias = false } }
    val shapes = remember { FullMapShapes() }
    var legend by remember { mutableStateOf(legendOpen) }
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
            val ap = MiniPainter.artPx(density)
            shapes.build(ap)
            val mppScreen = 1f / (view.zoom * VehicleWorld.U)
            // the city map: the picture drawn for this zoom (each zoom step has its own, crisp at its scale)
            val shown = if (view.tier == MapTier.CITY) data.cityFor(mppScreen * ap, style) ?: layer else layer
            drawLayer(shown, view)
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
                        roundPaint.color = MiniPainter.ROUTE_EDGE; roundPaint.strokeWidth = 7f * ap; c.drawPath(routePath, roundPaint)
                        roundPaint.color = MiniPainter.ROUTE; roundPaint.strokeWidth = 4f * ap; c.drawPath(routePath, roundPaint)
                        routeChevrons(c, shapes.chevron, roundPaint, r.pts, size.width / 2f, size.height / 2f, view.cx, view.cz, view.zoom, ap, nanos(), size.width / 2f, size.height / 2f, size.width)
                    } else if (pk) {
                        paint.color = Color(0xFFFF4FA3); paint.strokeWidth = 3f
                        val ax = sx(player.x); val ay = sy(player.z); val bx = sx(w[0]); val by = sy(w[1])
                        val len = kotlin.math.hypot(bx - ax, by - ay); var s = 0f
                        while (s < len) { val e = min(len, s + 14f); c.drawLine(Offset(ax + (bx - ax) * s / len, ay + (by - ay) * s / len), Offset(ax + (bx - ax) * e / len, ay + (by - ay) * e / len), paint); s += 24f }
                    }
                }
            }
            // (no names under the buttons and captions: the top band, the left column, the legend)
            val band = 64.dp.toPx()
            val reserved = arrayListOf(floatArrayOf(0f, 0f, size.width, band), floatArrayOf(0f, 0f, 200.dp.toPx(), 96.dp.toPx()))
            if (legend) reserved += floatArrayOf(size.width - 230.dp.toPx(), 0f, size.width, size.height)
            // the places' icons (city tier), upright; the minor ones only close in; the names keep clear of them
            if (view.tier == MapTier.CITY) {
                val ip = MiniPainter.iconPx(density)
                val half = MapIcons.BADGE * ip / 2f
                val minRank = when { mppScreen < .7f -> 0; mppScreen < 1.6f -> 30; else -> 45 }
                for (q in extra + shown.pois) {
                    if (q.kind.rank < minRank) continue
                    val x = sx(q.x).roundToInt().toFloat(); val y = sy(q.z).roundToInt().toFloat()
                    if (x < -half || y < -half || x > size.width + half || y > size.height + half) continue
                    val box = floatArrayOf(x - half - 2f, y - half - 2f, x + half + 2f, y + half + 2f)
                    if (reserved.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
                    reserved += box
                    val img = MapIcons.badges.getValue(q.kind)
                    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((x - half).roundToInt(), (y - half).roundToInt()), IntSize(img.width * ip, img.height * ip), filterQuality = FilterQuality.None)
                }
            }
            drawLabels(shown, view, tm, style == MapStyle.PAPER, reserved)
            // the waypoint's pin
            if (w != null) {
                val ip = MiniPainter.iconPx(density) + 1
                val x = sx(w[0]).roundToInt(); val y = sy(w[1]).roundToInt()
                val pin = MapIcons.pin
                drawImage(pin, IntOffset.Zero, IntSize(pin.width, pin.height), IntOffset(x - (5.5f * ip).roundToInt(), y - 13 * ip), IntSize(pin.width * ip, pin.height * ip), filterQuality = FilterQuality.None)
            }
            // the player's view cone and arrow (where they can tell where they are)
            if (pk) {
                val x = sx(player!!.x); val y = sy(player.z)
                withTransform({ translate(x, y); rotate(player.yaw, Offset.Zero) }) {
                    drawIntoCanvas { c -> fillPaint.color = Color(0x38FFFFFF); c.drawPath(shapes.cone, fillPaint) }
                }
                withTransform({ translate(x, y); rotate(Math.toDegrees(player.heading.toDouble()).toFloat(), Offset.Zero) }) {
                    drawIntoCanvas { c ->
                        paint.color = Color(0xFF0E1118); paint.strokeWidth = 3f * ap; c.drawPath(shapes.arrow, paint)
                        fillPaint.color = if (style == MapStyle.PAPER) Color(0xFFB0281C) else Color.White; c.drawPath(shapes.arrow, fillPaint)
                    }
                }
            }
            // the scale bar (bottom left, over the compass's right): a round length near 90 dp long
            scaleBar(tm, mppScreen, Offset(84.dp.toPx(), size.height - 22.dp.toPx()), 90.dp.toPx(), ap, style == MapStyle.PAPER || view.tier != MapTier.CITY)
        }
        // north, bottom left (the full map is always north up)
        CompassRose(androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(1L) }, { 0f }, Modifier.align(Alignment.BottomStart).padding(12.dp), 64.dp, paper = !access.gps)
        if (legend && layer != null) MapLegend(layer, extra, style == MapStyle.PAPER, Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp))
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
            MapButton("Legend", on = legend) { legend = !legend }
            MapButton("Close") { onClose() }
        }
    }
}

/** The full map's shapes, made once for the art pixel's size: the player's arrow, the view cone, a route chevron. */
private class FullMapShapes {
    val arrow = Path(); val cone = Path(); val chevron = Path()
    private var at = -1f
    fun build(ap: Float) {
        if (at == ap) return
        at = ap
        arrow.reset(); arrow.moveTo(0f, -9f * ap); arrow.lineTo(7f * ap, 8f * ap); arrow.lineTo(0f, 4f * ap); arrow.lineTo(-7f * ap, 8f * ap); arrow.close()
        cone.reset(); cone.moveTo(0f, 0f)
        val half = Math.toRadians(32.0); val len = 70f * ap
        for (i in 0..12) { val a = -half + 2 * half * i / 12.0; cone.lineTo((kotlin.math.sin(a) * len).toFloat(), (-kotlin.math.cos(a) * len).toFloat()) }
        cone.close()
        chevron.reset(); chevron.moveTo(-3f * ap, 2f * ap); chevron.lineTo(0f, -2f * ap); chevron.lineTo(3f * ap, 2f * ap)
    }
}

/**
 * Chevrons every so far along a route ([pts], street units), pointing the way to go and moving slowly toward its end:
 * on screen at ([ox], [oy]) + (point - ([cx], [cz])) x [ku]; only within [limit] of ([lx], [ly]). Allocates nothing.
 */
internal fun routeChevrons(cv: androidx.compose.ui.graphics.Canvas, chevron: Path, paint: Paint, pts: FloatArray, ox: Float, oy: Float, cx: Float, cz: Float,
                           ku: Float, ap: Float, nanos: Long, lx: Float, ly: Float, limit: Float) {
    val gap = 22f * ap
    var phase = gap - ((nanos / 1_000_000L) % 2000L) / 2000f * gap // one gap every 2 s
    paint.color = MiniPainter.CHEVRON; paint.strokeWidth = 1.4f * ap
    for (i in 0 until pts.size / 2 - 1) {
        val ax = ox + (pts[i * 2] - cx) * ku; val ay = oy + (pts[i * 2 + 1] - cz) * ku
        val bx = ox + (pts[i * 2 + 2] - cx) * ku; val by = oy + (pts[i * 2 + 3] - cz) * ku
        val len = kotlin.math.hypot(bx - ax, by - ay)
        if (len <= 0f) continue
        var at = phase
        while (at < len) {
            val x = ax + (bx - ax) * at / len; val y = ay + (by - ay) * at / len
            if (kotlin.math.hypot(x - lx, y - ly) < limit) {
                cv.save(); cv.translate(x, y); cv.rotate(Math.toDegrees(kotlin.math.atan2((bx - ax).toDouble(), -(by - ay).toDouble())).toFloat())
                cv.drawPath(chevron, paint); cv.restore()
            }
            at += gap
        }
        phase = at - len
    }
}

/** A scale bar at [at] (its left end): a round length about [want] pixels long, black and white blocks, its length written. */
private fun DrawScope.scaleBar(tm: TextMeasurer, metresPerPx: Float, at: Offset, want: Float, ap: Float, light: Boolean) {
    val raw = want * metresPerPx
    val nice = floatArrayOf(5f, 10f, 20f, 25f, 50f, 100f, 200f, 250f, 500f, 1000f, 2000f, 5000f, 10_000f, 20_000f, 50_000f, 100_000f, 200_000f, 500_000f, 1_000_000f)
    val m = nice.lastOrNull { it <= raw } ?: nice[0]
    val len = (m / metresPerPx).roundToInt().toFloat()
    val h = 3f * ap
    val ink = if (light) Color(0xFF2A1E12) else Color(0xFF0E1118)
    val paperC = if (light) Color(0xFFF4ECD8) else Color.White
    drawRect(ink, Offset(at.x - ap, at.y - ap), androidx.compose.ui.geometry.Size(len + 2 * ap, h + 2 * ap))
    for (i in 0 until 4) drawRect(if (i % 2 == 0) paperC else ink, Offset(at.x + len * i / 4f, at.y), androidx.compose.ui.geometry.Size(len / 4f, h))
    val t = tm.measure(distanceText(m), TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = MONO))
    for ((dx, dy) in HALO) drawText(t, color = paperC, topLeft = Offset(at.x + dx, at.y - t.size.height - 2f * ap + dy))
    drawText(t, topLeft = Offset(at.x, at.y - t.size.height - 2f * ap))
}

/**
 * The full map's legend: what the colours are (the buildings by kind, the parks, the water, the roads, the route, the
 * waypoint), and each kind of place the map marks here, with its icon.
 */
@Composable
fun MapLegend(layer: MapLayer, extra: List<MapPoi>, paper: Boolean, modifier: Modifier = Modifier) {
    val pal = if (paper) TownMapModel.PAPER else TownMapModel.GPS
    val kinds = (extra + layer.pois).map { it.kind }.distinct().sortedByDescending { it.rank }
    Column(modifier.width(218.dp).heightIn(max = 290.dp).background(PANEL).border(2.dp, EDGE).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PixelText("Legend", 12)
        if (layer.tier == MapTier.CITY) {
            @Composable fun swatch(c: Int, edge: Int, label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp, 10.dp).background(Color(edge)).padding(2.dp).background(Color(c)))
                PixelText(label, 10, DIM)
            }
            swatch(pal.home, MapRaster.shade(pal.home, .6f), "Homes")
            swatch(pal.shop, MapRaster.shade(pal.shop, .6f), "Shops")
            swatch(pal.landmark, MapRaster.shade(pal.landmark, .6f), "Landmarks")
            swatch(pal.civic, MapRaster.shade(pal.civic, .6f), "Civic buildings")
            swatch(pal.tree, pal.treeDark, "Trees, parks")
            swatch(pal.water, pal.shore, "Water")
            swatch(pal.mainRoad, pal.mainEdge, "Main road")
            swatch(pal.road, pal.roadEdge, "Street")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(14.dp, 6.dp).background(MiniPainter.ROUTE_EDGE).padding(1.dp).background(MiniPainter.ROUTE)); PixelText("GPS route", 10, DIM)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { IconImage(MapIcons.pin, 14.dp); PixelText("Waypoint", 10, DIM) }
        for (k in kinds) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconImage(MapIcons.badges.getValue(k), 14.dp); PixelText(k.word, 10, DIM)
        }
    }
}

/** One of the maps' pixel pictures, [size] tall, nearest filtered. */
@Composable
fun IconImage(img: androidx.compose.ui.graphics.ImageBitmap, size: Dp) {
    Canvas(Modifier.size(size * img.width / img.height, size)) {
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.None)
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
private fun DrawScope.drawLabels(layer: MapLayer, view: FullMapView, tm: TextMeasurer, paper: Boolean, reserved: List<FloatArray> = emptyList()) {
    val st = styles(paper, layer.tier != MapTier.CITY)
    val z = view.zoom
    val metresPerPx = 1f / (z * VehicleWorld.U)
    val taken = ArrayList<FloatArray>(reserved)
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
        val under = (l.kind == LabelKind.BUSINESS || l.kind == LabelKind.LANDMARK || l.kind == LabelKind.CIVIC) && layer.tier == MapTier.CITY
        val ox = if (dotted) x + 7f else x - bw / 2f
        val oy = if (under) y + 4f + MapIcons.BADGE * MiniPainter.iconPx(density) / 2f else y - bh / 2f
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
        val place = l.kind == LabelKind.BUSINESS || l.kind == LabelKind.LANDMARK || l.kind == LabelKind.CIVIC
        // (on the city map the place's icon marks the building and the name goes under it; elsewhere a square dot)
        val iconHalf = if (layer.tier == MapTier.CITY) MapIcons.BADGE * MiniPainter.iconPx(density) / 2f else 0f
        if (place && iconHalf == 0f) {
            drawRect(st.halo, Offset(x - 3f, y - 3f), androidx.compose.ui.geometry.Size(6f, 6f))
            drawRect(style.color, Offset(x - 2f, y - 2f), androidx.compose.ui.geometry.Size(4f, 4f))
        }
        val tx = if (dotted) x + 7f else x - tw / 2f
        val ty = if (place) y + 4f + iconHalf else y - th / 2f
        rotate(l.angle, Offset(x, y)) {
            // a hard one-pixel halo (four offsets), then the text
            for ((dx, dy) in HALO) drawText(t, color = st.halo, topLeft = Offset(tx + dx, ty + dy))
            drawText(t, topLeft = Offset(tx, ty))
        }
    }
}

private val HALO = listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f)

/** The compass alone, [size] across, turned with the camera's [yaw] (the corner without a map; the full map's north). */
@Composable
fun CompassRose(tick: LongState, yaw: () -> Float, modifier: Modifier = Modifier, size: Dp = 56.dp, paper: Boolean = false) {
    val painter = remember { MiniPainter() }
    Canvas(modifier.size(size)) {
        if (tick.longValue < 0) return@Canvas
        painter.drawCompass(this, yaw(), paper)
    }
}
