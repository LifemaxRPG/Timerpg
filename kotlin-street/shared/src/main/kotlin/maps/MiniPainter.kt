package com.lifemax.rpg.feature.habits.maps

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the player is for the maps: street ([x], [z]), the way they head ([heading], radians, 0 north, clockwise), the
 * camera's [yaw] (degrees, the same way), and their [speed] (metres a second: the minimap zooms out with it when driving).
 */
class MapPlayer {
    var x = 0f; var z = 0f; var heading = 0f; var yaw = 0f; var speed = 0f
    fun set(x: Float, z: Float, heading: Float, yaw: Float, speed: Float = 0f): MapPlayer { this.x = x; this.z = z; this.heading = heading; this.yaw = yaw; this.speed = speed; return this }
}

/**
 * The minimap (docs/MAPS.md, "The minimap's look"), drawn straight onto the HUD's canvas at the screen's own resolution:
 * the city map's picture for the zoom ([MapData.cityFor]: each zoom step has a picture of its own, drawn for its scale,
 * so the pixels stay crisp and whole at every zoom), turned smoothly with the camera in heading-up mode, under the GPS
 * route (with chevrons that flow toward the waypoint), the camera's view cone, the street names along their streets
 * (close in), the places' icons (upright whatever the turn), the waypoint's pin or an arrow at the rim, the player's
 * arrow; round it a bezel with ticks and the N / E / S / W letters on the ring. Before GPS the same in the paper look,
 * with a brass bezel. [drawCompass]: the compass alone, the corner without a map.
 *
 * Everything it uses is made once (paints, paths, the icons' pictures; a text's layout the first time it is drawn):
 * a frame allocates nothing.
 */
class MiniPainter {
    private val fill = Paint().apply { isAntiAlias = false }
    private val stroke = Paint().apply { isAntiAlias = false; style = PaintingStyle.Stroke; strokeCap = StrokeCap.Square; strokeJoin = StrokeJoin.Miter }
    private val roundStroke = Paint().apply { isAntiAlias = false; style = PaintingStyle.Stroke; strokeCap = StrokeCap.Round; strokeJoin = StrokeJoin.Round }
    private val clip = Path()
    private val routePath = Path()
    private val arrow = Path()
    private val cone = Path()
    private val chevron = Path()
    private val rimArrow = Path()
    private val needleN = Path()
    private val needleS = Path()
    private var builtFor = -1f
    /** The visible radius (metres), eased toward the zoom's (and the speed's) so zooming is smooth. */
    private var radiusM = Float.NaN
    private var lastNanos = 0L
    private val boxes = FloatArray(4 * MAX_BOXES)
    private var nBoxes = 0
    private val texts = HashMap<String, TextLayoutResult>()
    private val seenX = FloatArray(16); private val seenY = FloatArray(16); private val seenText = arrayOfNulls<String>(16); private var nSeen = 0
    private val letterN by lazy { MapIcons.letter('N', 0xFFFF5A4A.toInt()) }
    private val letterOthers by lazy { charArrayOf('E', 'S', 'W').map { MapIcons.letter(it, 0xFFE4E9F2.toInt()) } }
    private val letterNPaper by lazy { MapIcons.letter('N', 0xFFB0281C.toInt()) }
    private val letterOthersPaper by lazy { charArrayOf('E', 'S', 'W').map { MapIcons.letter(it, 0xFF3A2412.toInt()) } }

    /**
     * Where the waypoint's rim arrow was drawn (x, y in the canvas's pixels) and its distance (metres) when it is off the
     * map; [EDGE_NONE] in the third number when it is on the map or there is none.
     */
    val edgeAt = FloatArray(3)
    /** The metres a screen pixel the last frame was drawn at (the tests read it). */
    var lastMetresPerPx = 0f; private set

    private fun build(s: Float, ap: Float) {
        if (builtFor == s * 1000f + ap) return
        builtFor = s * 1000f + ap
        val c = s / 2f; val r = mapRadius(s)
        clip.reset(); clip.addOval(Rect(c - r, c - r, c + r, c + r))
        // the player's arrow, 15 x 14 art pixels round its middle
        arrow.reset(); arrow.moveTo(0f, -7f * ap); arrow.lineTo(6f * ap, 6f * ap); arrow.lineTo(0f, 3f * ap); arrow.lineTo(-6f * ap, 6f * ap); arrow.close()
        // the view cone, pointing up (north) from the middle: 64 degrees wide, 0.62 of the radius long
        cone.reset(); cone.moveTo(0f, 0f)
        val half = Math.toRadians(32.0); val len = r * .62f
        for (i in 0..12) { val a = -half + 2 * half * i / 12.0; cone.lineTo((sin(a) * len).toFloat(), (-cos(a) * len).toFloat()) }
        cone.close()
        // a route chevron (pointing up, the way along the route), and the rim arrow
        chevron.reset(); chevron.moveTo(-2.5f * ap, 1.5f * ap); chevron.lineTo(0f, -1.5f * ap); chevron.lineTo(2.5f * ap, 1.5f * ap)
        rimArrow.reset(); rimArrow.moveTo(0f, -6f * ap); rimArrow.lineTo(5f * ap, 4f * ap); rimArrow.lineTo(0f, 2f * ap); rimArrow.lineTo(-5f * ap, 4f * ap); rimArrow.close()
        needleN.reset(); needleN.moveTo(0f, -r * .72f); needleN.lineTo(r * .16f, 0f); needleN.lineTo(-r * .16f, 0f); needleN.close()
        needleS.reset(); needleS.moveTo(0f, r * .72f); needleS.lineTo(r * .16f, 0f); needleS.lineTo(-r * .16f, 0f); needleS.close()
    }

    /**
     * One frame of the minimap into [ds], [size] pixels across. [layerFor] gives the map for drawing at a number of metres
     * a picture pixel (the city map's closest zoom step; the road or world map as it is); null: nothing drawn yet. [p] the
     * player ([MapPlayer.speed] zooms out when [autoZoom]), [zoom] the zoom step, [headingUp] turns the map with the
     * camera, [showPlayer] (on paper only where the player can tell where they are), [waypoint] (a pin, or an arrow at the
     * rim), [route] (GPS) along the roads, [distanceM] to the waypoint, [extra] places the app marks (the player's room,
     * the station), [nanos] the frame's time (the zoom's easing and the route's chevrons), [tm] for the street names.
     */
    fun draw(ds: DrawScope, layerFor: (Float) -> MapLayer?, p: MapPlayer, zoom: Int, headingUp: Boolean, autoZoom: Boolean, showPlayer: Boolean,
             waypoint: FloatArray?, route: MapRoute?, distanceM: Float, extra: List<MapPoi>, nanos: Long, tm: TextMeasurer?) = with(ds) {
        val s = size.minDimension
        val ap = artPx(density)
        build(s, ap)
        val c = s / 2f; val r = mapRadius(s)
        val centre = Offset(c, c)
        // the radius shown: the zoom step's, wider with the speed when driving, eased (about 0.4 s to settle)
        val step = zoom.coerceIn(0, MapSettings.MINIMAP_RADIUS_M.size - 1)
        var target = MapSettings.MINIMAP_RADIUS_M[step]
        if (autoZoom && p.speed > 3f) target *= 1f + min(1.4f, (p.speed - 3f) / 13f)
        val dt = if (lastNanos == 0L) 1f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, .25f)
        lastNanos = nanos
        radiusM = if (radiusM.isNaN() || dt >= 1f) target else radiusM + (target - radiusM) * min(1f, dt * 6f)
        val settled = abs(radiusM - target) < target * .015f
        var mpp = radiusM / r // metres a screen pixel
        val layer = layerFor(mpp * ap)
        val paper = layer?.style == MapStyle.PAPER
        // a planet map: its own scale times the zoom (it has no zoom steps of its own)
        if (layer != null && layer.tier != MapTier.CITY) mpp = layer.unitsPerPx / VehicleWorld.U / (2f * ap) * (radiusM / MapSettings.MINIMAP_RADIUS_M[2])
        // a picture pixel is a whole number of screen pixels once the zoom has settled (crisp, even pixels)
        if (layer != null) {
            val lm = layer.unitsPerPx / VehicleWorld.U
            val k = lm / mpp
            if (settled || layer.tier != MapTier.CITY) mpp = lm / max(1f, k.roundToInt().toFloat())
        }
        lastMetresPerPx = mpp
        val ku = 1f / (VehicleWorld.U * mpp) // screen pixels a street unit
        val turn = if (headingUp) -p.yaw else 0f
        val ta = Math.toRadians(turn.toDouble()); val tc = cos(ta).toFloat(); val ts = sin(ta).toFloat()
        nBoxes = 0; nSeen = 0
        edgeAt[2] = EDGE_NONE
        clipPath(clip) {
            drawRect(Color(layer?.background ?: if (paper) PAPER_OUT else GPS_OUT))
            withTransform({ rotate(turn, centre) }) {
                if (layer != null) {
                    val sc = layer.unitsPerPx * ku // screen pixels a picture pixel
                    val pcx = (p.x - layer.x0) / layer.unitsPerPx; val pcz = (p.z - layer.z0) / layer.unitsPerPx
                    val half = r / sc + 2f
                    val sx0 = floor(pcx - half).toInt().coerceIn(0, layer.w); val sx1 = ceil(pcx + half).toInt().coerceIn(0, layer.w)
                    val sz0 = floor(pcz - half).toInt().coerceIn(0, layer.h); val sz1 = ceil(pcz + half).toInt().coerceIn(0, layer.h)
                    if (sx1 > sx0 && sz1 > sz0) {
                        val dl = (c + (sx0 - pcx) * sc).roundToInt(); val dt2 = (c + (sz0 - pcz) * sc).roundToInt()
                        drawImage(layer.image, IntOffset(sx0, sz0), IntSize(sx1 - sx0, sz1 - sz0), IntOffset(dl, dt2),
                            IntSize(((sx1 - sx0) * sc).roundToInt().coerceAtLeast(1), ((sz1 - sz0) * sc).roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.None)
                    }
                }
                // the route along the roads (GPS): a dark edge, the line, chevrons flowing toward the waypoint
                if (route != null && route.pts.size >= 4) drawIntoCanvas { cv ->
                    routePath.reset()
                    for (i in 0 until route.pts.size / 2) {
                        val x = c + (route.pts[i * 2] - p.x) * ku; val y = c + (route.pts[i * 2 + 1] - p.z) * ku
                        if (i == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y)
                    }
                    roundStroke.color = ROUTE_EDGE; roundStroke.strokeWidth = 6f * ap; cv.drawPath(routePath, roundStroke)
                    roundStroke.color = ROUTE; roundStroke.strokeWidth = 3.5f * ap; cv.drawPath(routePath, roundStroke)
                    routeChevrons(cv, chevron, roundStroke, route.pts, c, c, p.x, p.z, ku, ap, nanos, c, c, r * 1.45f)
                }
            }
            // the camera's view cone, and the street names and the places (upright, wherever the map has turned)
            if (showPlayer) withTransform({ translate(c, c); rotate(p.yaw + turn, Offset.Zero) }) {
                drawIntoCanvas { cv -> fill.color = if (paper) CONE_PAPER else CONE; cv.drawPath(cone, fill) }
            }
            if (layer != null && layer.tier == MapTier.CITY && tm != null && mpp <= .78f) streetNames(this, layer, p, ku, c, r, tc, ts, turn, paper, tm)
            if (layer != null && layer.tier == MapTier.CITY) places(this, layer.pois, extra, p, ku, c, r, tc, ts, radiusM)
            // the waypoint: a pin on the map, or an arrow at the rim toward it
            if (waypoint != null) {
                val wx = (waypoint[0] - p.x) * ku; val wz = (waypoint[1] - p.z) * ku
                val dx = wx * tc - wz * ts; val dz = wx * ts + wz * tc
                val d = hypot(dx, dz)
                val ip = iconPx(density)
                val rim = r - 7f * ap
                if (d <= rim - 4f * ip) {
                    val x = (c + dx).roundToInt(); val y = (c + dz).roundToInt()
                    val pin = MapIcons.pin
                    drawImage(pin, IntOffset.Zero, IntSize(pin.width, pin.height), IntOffset(x - (5.5f * ip).roundToInt(), y - (13 * ip)), IntSize(pin.width * ip, pin.height * ip), filterQuality = FilterQuality.None)
                } else {
                    val ux = dx / d; val uz = dz / d
                    val x = c + ux * rim; val y = c + uz * rim
                    withTransform({ translate(x.roundToInt().toFloat(), y.roundToInt().toFloat()); rotate(Math.toDegrees(atan2(ux.toDouble(), -uz.toDouble())).toFloat(), Offset.Zero) }) {
                        drawIntoCanvas { cv ->
                            stroke.color = OUTLINE; stroke.strokeWidth = 2f * ap; cv.drawPath(rimArrow, stroke)
                            fill.color = PIN; cv.drawPath(rimArrow, fill)
                        }
                    }
                    edgeAt[0] = x; edgeAt[1] = y; edgeAt[2] = distanceM
                }
            }
            // the player's arrow, the way they head (turned with the map when it turns with the camera)
            if (showPlayer) withTransform({ translate(c, c); rotate(Math.toDegrees(p.heading.toDouble()).toFloat() + turn, Offset.Zero) }) {
                drawIntoCanvas { cv ->
                    stroke.color = OUTLINE; stroke.strokeWidth = 2.5f * ap; cv.drawPath(arrow, stroke)
                    fill.color = if (paper) ARROW_PAPER else ARROW; cv.drawPath(arrow, fill)
                }
            }
        }
        bezel(this, s, ap, turn, paper)
        // the distance to the waypoint on the bezel's bottom
        if (waypoint != null && tm != null) {
            val t = text(tm, "d" + distanceText(distanceM), distanceText(distanceM), DIST)
            val tw = t.size.width.toFloat(); val th = t.size.height.toFloat()
            val x = (c - tw / 2f).roundToInt().toFloat(); val y = (s - th - ap).roundToInt().toFloat()
            drawRect(OUTLINE, Offset(x - 3f * ap, y - ap), androidx.compose.ui.geometry.Size(tw + 6f * ap, th + 2f * ap))
            drawRect(PIN, Offset(x - 2f * ap, y), androidx.compose.ui.geometry.Size(tw + 4f * ap, th))
            drawText(t, topLeft = Offset(x, y))
        }
    }

    /** The compass alone (no map), [size] across: a dark dial, the needle to the north, the bezel and its letters; turned with the camera's [yaw]. */
    fun drawCompass(ds: DrawScope, yaw: Float, paper: Boolean = false) = with(ds) {
        val s = size.minDimension
        val ap = artPx(density)
        build(s, ap)
        val c = s / 2f
        clipPath(clip) { drawRect(if (paper) Color(0xFFE9DDBD) else Color(0xE6101420)) }
        withTransform({ translate(c, c); rotate(-yaw, Offset.Zero) }) {
            drawIntoCanvas { cv ->
                fill.color = NEEDLE_N; cv.drawPath(needleN, fill)
                fill.color = if (paper) Color(0xFF6B5A48) else NEEDLE_S; cv.drawPath(needleS, fill)
            }
            drawRect(OUTLINE, Offset(-2f * ap, -2f * ap), androidx.compose.ui.geometry.Size(4f * ap, 4f * ap))
        }
        bezel(this, s, ap, -yaw, paper)
    }

    /** The ring round the map: a dark (or brass) band with a lit inner edge, ticks every 30 degrees, N / E / S / W upright on it. */
    private fun bezel(ds: DrawScope, s: Float, ap: Float, turn: Float, paper: Boolean) = with(ds) {
        val c = s / 2f; val r = mapRadius(s); val bw = c - r
        val centre = Offset(c, c)
        drawIntoCanvas { cv ->
            stroke.color = if (paper) BRASS else BEZEL; stroke.strokeWidth = bw; cv.drawCircle(centre, r + bw / 2f, stroke)
            stroke.color = if (paper) BRASS_LIGHT else BEZEL_LIGHT; stroke.strokeWidth = ap; cv.drawCircle(centre, r + ap / 2f, stroke)
            stroke.color = OUTLINE; stroke.strokeWidth = ap; cv.drawCircle(centre, c - ap / 2f, stroke)
            stroke.color = if (paper) BRASS_TICK else BEZEL_TICK; stroke.strokeWidth = ap
            for (i in 0 until 12) {
                if (i % 3 == 0) continue
                val a = Math.toRadians((i * 30 + turn).toDouble()); val sa = sin(a).toFloat(); val ca = cos(a).toFloat()
                cv.drawLine(Offset(c + sa * (r + bw * .3f), c - ca * (r + bw * .3f)), Offset(c + sa * (r + bw * .7f), c - ca * (r + bw * .7f)), stroke)
            }
        }
        val lp = max(1f, floor(bw / 8f))
        val n = if (paper) letterNPaper else letterN
        val others = if (paper) letterOthersPaper else letterOthers
        for (i in 0 until 4) {
            val img = if (i == 0) n else others[i - 1]
            val a = Math.toRadians((i * 90 + turn).toDouble())
            val x = (c + sin(a) * (r + bw / 2f) - 3.5f * lp).roundToInt(); val y = (c - cos(a) * (r + bw / 2f) - 3.5f * lp).roundToInt()
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(x, y), IntSize((img.width * lp).toInt(), (img.height * lp).toInt()), filterQuality = FilterQuality.None)
        }
    }

    /** The street names along their streets (close in): the most important first, where they fit and do not overlap. */
    private fun streetNames(ds: DrawScope, layer: MapLayer, p: MapPlayer, ku: Float, c: Float, r: Float, tc: Float, ts: Float, turn: Float, paper: Boolean, tm: TextMeasurer) = with(ds) {
        var drawn = 0
        val st = if (paper) STREET_PAPER else STREET
        val halo = if (paper) HALO_PAPER else HALO_GPS
        for (l in layer.byPriority) {
            if (l.kind != LabelKind.STREET) continue
            if (drawn >= 6) break
            val wx = (l.x - p.x) * ku; val wz = (l.z - p.z) * ku
            val dx = wx * tc - wz * ts; val dz = wx * ts + wz * tc
            if (hypot(dx, dz) > r * .8f) continue
            val t = text(tm, (if (paper) "p" else "g") + l.text, l.text, st)
            val tw = t.size.width.toFloat(); val th = t.size.height.toFloat()
            if (l.span * ku < tw + 10f) continue
            val x = c + dx; val y = c + dz
            var near = false
            for (i in 0 until nSeen) if (seenText[i] == l.text && hypot(seenX[i] - x, seenY[i] - y) < tw * 2.5f) { near = true; break }
            if (near) continue
            var ang = l.angle + turn
            while (ang > 90f) ang -= 180f
            while (ang <= -90f) ang += 180f
            val a = Math.toRadians(ang.toDouble()); val ca = abs(cos(a)).toFloat(); val sa = abs(sin(a)).toFloat()
            val bw = tw * ca + th * sa; val bh = tw * sa + th * ca
            if (!claim(x - bw / 2f, y - bh / 2f, x + bw / 2f, y + bh / 2f)) continue
            if (hypot(x - c, y - c) + max(bw, bh) / 2f > r - 2f) continue
            if (nSeen < seenX.size) { seenX[nSeen] = x; seenY[nSeen] = y; seenText[nSeen] = l.text; nSeen++ }
            drawn++
            rotate(ang, Offset(x, y)) {
                val tx = (x - tw / 2f).roundToInt().toFloat(); val ty = (y - th / 2f).roundToInt().toFloat()
                for (h in 0 until 8) drawText(t, color = halo, topLeft = Offset(tx + HALO_X[h], ty + HALO_Y[h]))
                drawText(t, topLeft = Offset(tx, ty))
            }
        }
    }

    /**
     * The places' icons, upright: the most important first ([PoiKind.rank]), the minor ones only close in, none over
     * another or a street name; [extra] (the player's room, the station) always first.
     */
    private fun places(ds: DrawScope, pois: List<MapPoi>, extra: List<MapPoi>, p: MapPlayer, ku: Float, c: Float, r: Float, tc: Float, ts: Float, radiusM: Float) = with(ds) {
        val ip = iconPx(density)
        val minRank = when { radiusM <= 90f -> 0; radiusM <= 170f -> 30; else -> 45 }
        val half = MapIcons.BADGE * ip / 2f
        fun one(q: MapPoi) {
            if (q.kind.rank < minRank) return
            val wx = (q.x - p.x) * ku; val wz = (q.z - p.z) * ku
            val dx = wx * tc - wz * ts; val dz = wx * ts + wz * tc
            if (hypot(dx, dz) > r - half * 1.5f) return
            val x = (c + dx).roundToInt(); val y = (c + dz).roundToInt()
            if (!claim(x - half, y - half, x + half, y + half)) return
            val img = MapIcons.badges.getValue(q.kind)
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((x - half).roundToInt(), (y - half).roundToInt()),
                IntSize(img.width * ip, img.height * ip), filterQuality = FilterQuality.None)
        }
        for (i in extra.indices) one(extra[i])
        for (i in pois.indices) one(pois[i])
    }

    /** Takes the box ([x0], [y0], [x1], [y1]) unless it overlaps one taken this frame. */
    private fun claim(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        for (i in 0 until nBoxes) {
            val o = i * 4
            if (boxes[o] < x1 && x0 < boxes[o + 2] && boxes[o + 1] < y1 && y0 < boxes[o + 3]) return false
        }
        if (nBoxes >= MAX_BOXES) return false
        val o = nBoxes * 4
        boxes[o] = x0 - 2f; boxes[o + 1] = y0 - 2f; boxes[o + 2] = x1 + 2f; boxes[o + 3] = y1 + 2f
        nBoxes++
        return true
    }

    /** A text's layout, measured the first time it is drawn and kept. */
    private fun text(tm: TextMeasurer, key: String, s: String, style: TextStyle): TextLayoutResult =
        texts[key] ?: tm.measure(s, style).also { if (texts.size < 512) texts[key] = it }

    companion object {
        const val EDGE_NONE = -1f
        private const val MAX_BOXES = 48
        /** The map's radius in a minimap [s] pixels across (the rest is the bezel). */
        fun mapRadius(s: Float): Float = s / 2f - max(6f, s * .075f)
        /** The HUD's art pixel at a [density]: about 0.8 dp, a whole number of screen pixels (2 on the S25). */
        fun artPx(density: Float): Float = max(1f, (density * .8f).roundToInt().toFloat())
        /** The icons' pixel: about 1 dp, whole (3 on the S25). */
        fun iconPx(density: Float): Int = max(1, density.roundToInt())

        private val OUTLINE = Color(0xFF0E1118)
        private val ARROW = Color(0xFFFFFFFF)
        private val ARROW_PAPER = Color(0xFFB0281C)
        private val PIN = Color(0xFFFF4FA3)
        val ROUTE = Color(0xFF39C8FF)
        val ROUTE_EDGE = Color(0xFF0B2A4A)
        val CHEVRON = Color(0xFFEFFBFF)
        private val CONE = Color(0x38FFFFFF)
        private val CONE_PAPER = Color(0x30B0281C)
        private val BEZEL = Color(0xFF161B26)
        private val BEZEL_LIGHT = Color(0xFF8FA6CF)
        private val BEZEL_TICK = Color(0xFF55627C)
        private val BRASS = Color(0xFF7A5A2E)
        private val BRASS_LIGHT = Color(0xFFE2C27A)
        private val BRASS_TICK = Color(0xFFC9A55C)
        private val NEEDLE_N = Color(0xFFE8473C)
        private val NEEDLE_S = Color(0xFFE9E9E4)
        private val GPS_OUT = MapRaster.argb(0x1F2C1B)
        private val PAPER_OUT = MapRaster.argb(0xE3D6B4)
        private val MONO = FontFamily.Monospace
        private val STREET = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = MONO)
        private val STREET_PAPER = TextStyle(color = Color(0xFF3A2412), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = MONO)
        private val DIST = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = MONO)
        private val HALO_GPS = Color(0xFF101420)
        private val HALO_PAPER = Color(0xFFF4ECD8)
        /** A hard halo: the text drawn at the eight one-pixel offsets first. */
        private val HALO_X = floatArrayOf(-1f, 0f, 1f, -1f, 1f, -1f, 0f, 1f)
        private val HALO_Y = floatArrayOf(-1f, -1f, -1f, 0f, 0f, 1f, 1f, 1f)
    }
}

/**
 * The route to the waypoint, kept between frames (docs/MAPS.md): with GPS and both ends in the town, A* along the town's
 * roads ([TownMapModel.route]), worked out again when the player has moved [MOVED] street units or the waypoint
 * changed; otherwise none (a straight bearing). [distanceM]: along the route, or straight.
 */
class MapNav {
    var route: MapRoute? = null; private set
    var distanceM = 0f; private set
    private var lx = Float.NaN; private var lz = Float.NaN; private var wx = Float.NaN; private var wz = Float.NaN; private var withRoute = false

    fun update(city: TownMapModel?, gps: Boolean, px: Float, pz: Float, wp: FloatArray?) {
        if (wp == null) { route = null; distanceM = 0f; wx = Float.NaN; return }
        val want = gps && city != null && city.inside(px, pz, 2000f) && city.inside(wp[0], wp[1], 2000f)
        val moved = lx.isNaN() || hypot(px - lx, pz - lz) > MOVED
        if (want != withRoute || wp[0] != wx || wp[1] != wz || (want && moved)) {
            route = if (want) city!!.route(px, pz, wp[0], wp[1]) else null
            lx = px; lz = pz; wx = wp[0]; wz = wp[1]; withRoute = want
        }
        // (the distance follows the player every frame: the route's length less what was walked since, or straight)
        distanceM = route?.let { r -> (r.length - hypot(px - lx, pz - lz)).coerceAtLeast(hypot(wp[0] - px, wp[1] - pz)) / VehicleWorld.U }
            ?: streetMetres(px, pz, wp[0], wp[1])
    }

    companion object { const val MOVED = 120f }
}
