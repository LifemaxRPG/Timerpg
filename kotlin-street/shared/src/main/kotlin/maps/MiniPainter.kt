package com.lifemax.rpg.feature.habits.maps

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the player is for the maps: street ([x], [z]), the way they head ([heading], radians, 0 north, clockwise), the camera's [yaw] (degrees, the same way). */
class MapPlayer {
    var x = 0f; var z = 0f; var heading = 0f; var yaw = 0f
    fun set(x: Float, z: Float, heading: Float, yaw: Float): MapPlayer { this.x = x; this.z = z; this.heading = heading; this.yaw = yaw; return this }
}

/**
 * Draws the round minimap into a small picture of its own, [n] x [n] pixels, which the HUD scales up with nearest
 * filtering: every part of it (the map, the route, the arrow, the ring, the N) is in the game's chunky pixels, with no
 * anti-aliasing anywhere. Everything it uses is made once here: a frame draws a window of the map's picture into the
 * small one and a few shapes over it, allocating nothing.
 *
 * Also the compass rose ([drawCompass]: only the ring, a needle and the N), the HUD's corner without a map.
 */
class MiniPainter(val n: Int = 120) {
    val image = ImageBitmap(n, n)
    private val canvas = Canvas(image)
    private val c = n / 2f
    private val clear = Paint().apply { blendMode = BlendMode.Clear }
    private val img = Paint().apply { isAntiAlias = false; filterQuality = FilterQuality.None }
    private val fill = Paint().apply { isAntiAlias = false }
    private val stroke = Paint().apply { isAntiAlias = false; style = PaintingStyle.Stroke; strokeCap = StrokeCap.Square }
    private val clip = Path().apply { addOval(Rect(3f, 3f, n - 3f, n - 3f)) }
    private val route = Path()
    private val arrow = Path().apply { moveTo(0f, -6f); lineTo(5f, 5f); lineTo(0f, 2f); lineTo(-5f, 5f); close() }
    private val edge = Path().apply { moveTo(0f, -4f); lineTo(4f, 3f); lineTo(-4f, 3f); close() }

    /**
     * Where the waypoint's edge arrow was drawn (x, y in this picture's pixels) and the distance to it (metres) when it
     * is off the map's edge; [EDGE_NONE] in [edgeAt]'s third number when it is on the map or there is none.
     */
    val edgeAt = FloatArray(3)

    /**
     * One frame of the minimap: [layer] centred on [p] at [unitsPerPx] street units a pixel of this picture, north up or
     * turned with the camera ([headingUp]); the player's arrow when [showPlayer]; [route] (GPS) as a line along the roads;
     * [waypoint] as a pin, or an arrow at the rim when it is off the edge ([edgeAt]), [distanceM] away.
     */
    fun draw(layer: MapLayer?, p: MapPlayer, unitsPerPx: Float, headingUp: Boolean, showPlayer: Boolean,
             waypoint: FloatArray?, route: MapRoute?, distanceM: Float) {
        val pal = if (layer?.style == MapStyle.PAPER) PAPER else GPS
        canvas.drawRect(0f, 0f, n.toFloat(), n.toFloat(), clear)
        canvas.save()
        canvas.clipPath(clip)
        fill.color = pal.outside
        canvas.drawRect(0f, 0f, n.toFloat(), n.toFloat(), fill)
        val turn = if (headingUp) -p.yaw else 0f
        canvas.save()
        if (turn != 0f) { canvas.translate(c, c); canvas.rotate(turn); canvas.translate(-c, -c) }
        if (layer != null) {
            // the window of the map's picture round the player (wide enough for any turn), clipped to the picture
            val s = layer.unitsPerPx / unitsPerPx
            val pcx = (p.x - layer.x0) / layer.unitsPerPx; val pcz = (p.z - layer.z0) / layer.unitsPerPx
            val half = c * 1.45f / s
            val sx0 = floor(pcx - half).toInt().coerceIn(0, layer.w); val sx1 = ceil(pcx + half).toInt().coerceIn(0, layer.w)
            val sz0 = floor(pcz - half).toInt().coerceIn(0, layer.h); val sz1 = ceil(pcz + half).toInt().coerceIn(0, layer.h)
            if (sx1 > sx0 && sz1 > sz0) {
                val dl = c + (sx0 - pcx) * s; val dt = c + (sz0 - pcz) * s
                canvas.drawImageRect(layer.image, IntOffset(sx0, sz0), IntSize(sx1 - sx0, sz1 - sz0),
                    IntOffset(dl.roundToInt(), dt.roundToInt()), IntSize(((sx1 - sx0) * s).roundToInt().coerceAtLeast(1), ((sz1 - sz0) * s).roundToInt().coerceAtLeast(1)), img)
            }
        }
        // the route along the roads (GPS)
        if (route != null && route.pts.size >= 4) {
            route.reset(this.route, p, unitsPerPx, c)
            stroke.color = ROUTE_EDGE; stroke.strokeWidth = 4f; canvas.drawPath(this.route, stroke)
            stroke.color = ROUTE; stroke.strokeWidth = 2f; canvas.drawPath(this.route, stroke)
        }
        canvas.restore()
        // the waypoint: a pin on the map, or an arrow at the rim toward it
        edgeAt[2] = EDGE_NONE
        if (waypoint != null) {
            var dx = (waypoint[0] - p.x) / unitsPerPx; var dz = (waypoint[1] - p.z) / unitsPerPx
            if (turn != 0f) { val a = Math.toRadians(turn.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat(); val rx = dx * ca - dz * sa; dz = dx * sa + dz * ca; dx = rx }
            val d = hypot(dx, dz)
            val rim = c - 10f
            if (d <= rim) {
                val x = (c + dx).roundToInt().toFloat(); val y = (c + dz).roundToInt().toFloat()
                fill.color = OUTLINE; canvas.drawRect(x - 3f, y - 3f, x + 3f, y + 3f, fill)
                fill.color = PIN; canvas.drawRect(x - 2f, y - 2f, x + 2f, y + 2f, fill)
            } else {
                val ux = dx / d; val uz = dz / d
                val x = c + ux * rim; val y = c + uz * rim
                canvas.save(); canvas.translate(x.roundToInt().toFloat(), y.roundToInt().toFloat()); canvas.rotate(Math.toDegrees(kotlin.math.atan2(ux.toDouble(), -uz.toDouble())).toFloat())
                fill.color = OUTLINE; stroke.color = OUTLINE; stroke.strokeWidth = 2f
                canvas.drawPath(edge, stroke)
                fill.color = PIN; canvas.drawPath(edge, fill)
                canvas.restore()
                edgeAt[0] = x; edgeAt[1] = y; edgeAt[2] = distanceM
            }
        }
        canvas.restore()
        // the player's arrow, the way they head (turned with the map when it turns with the camera)
        if (showPlayer) {
            canvas.save(); canvas.translate(c, c); canvas.rotate(Math.toDegrees(p.heading.toDouble()).toFloat() + turn)
            stroke.color = OUTLINE; stroke.strokeWidth = 2f; canvas.drawPath(arrow, stroke)
            fill.color = ARROW; canvas.drawPath(arrow, fill)
            canvas.restore()
        }
        ring(pal)
        north(turn)
    }

    /** The compass rose alone (no map): the ring, a needle to the north, the N; turned with the camera. */
    fun drawCompass(yaw: Float) {
        canvas.drawRect(0f, 0f, n.toFloat(), n.toFloat(), clear)
        canvas.save(); canvas.clipPath(clip); fill.color = Color(0xCC101420); canvas.drawRect(0f, 0f, n.toFloat(), n.toFloat(), fill); canvas.restore()
        canvas.save(); canvas.translate(c, c); canvas.rotate(-yaw)
        fill.color = NEEDLE_N; canvas.drawPath(needleN, fill)
        fill.color = NEEDLE_S; canvas.drawPath(needleS, fill)
        canvas.restore()
        ring(GPS)
        north(-yaw)
    }

    private val needleN = Path().apply { moveTo(0f, -c * .58f); lineTo(c * .18f, 0f); lineTo(-c * .18f, 0f); close() }
    private val needleS = Path().apply { moveTo(0f, c * .58f); lineTo(c * .18f, 0f); lineTo(-c * .18f, 0f); close() }

    private fun ring(pal: Pal) {
        stroke.color = OUTLINE; stroke.strokeWidth = 3f
        canvas.drawCircle(Offset(c, c), c - 2.5f, stroke)
        stroke.color = pal.rim; stroke.strokeWidth = 1f
        canvas.drawCircle(Offset(c, c), c - 4.5f, stroke)
    }

    /** The N at the top of the ring (where north is when the map turns), a 5 x 5 pixel letter on a dark square. */
    private fun north(turn: Float) {
        val a = Math.toRadians(turn.toDouble())
        val r = c - 4f
        val x = (c + sin(a) * r).roundToInt(); val y = (c - cos(a) * r).roundToInt()
        fill.color = OUTLINE; canvas.drawRect(x - 4f, y - 4f, x + 5f, y + 5f, fill)
        fill.color = N_COL
        for (row in 0 until 5) for (col in 0 until 5) if (N_GLYPH[row][col] == 'X') canvas.drawRect((x - 2 + col).toFloat(), (y - 2 + row).toFloat(), (x - 1 + col).toFloat(), (y - 1 + row).toFloat(), fill)
    }

    private class Pal(val outside: Color, val rim: Color)

    companion object {
        const val EDGE_NONE = -1f
        private val GPS = Pal(Color(MapRaster.argb(0x24331F)), Color(0xFF9FB4D8))
        private val PAPER = Pal(Color(0xFFE3D6B4), Color(0xFFC9A877))
        private val OUTLINE = Color(0xFF101420)
        private val ARROW = Color(0xFFFFFFFF)
        private val PIN = Color(0xFFFF4FA3)
        val ROUTE = Color(0xFF3FD0FF)
        val ROUTE_EDGE = Color(0xFF0B3A66)
        private val N_COL = Color(0xFFFF6B5A)
        private val NEEDLE_N = Color(0xFFE8473C)
        private val NEEDLE_S = Color(0xFFE9E9E4)
        private val N_GLYPH = arrayOf("X...X", "XX..X", "X.X.X", "X..XX", "X...X")
    }
}

/** The route's points as a path in a minimap picture centred on [p] at [unitsPerPx] street units a pixel ([n] / 2 its centre). */
private fun MapRoute.reset(path: Path, p: MapPlayer, unitsPerPx: Float, centre: Float = -1f) {
    path.reset()
    val c = if (centre >= 0f) centre else 60f
    for (i in 0 until pts.size / 2) {
        val x = c + (pts[i * 2] - p.x) / unitsPerPx; val y = c + (pts[i * 2 + 1] - p.z) / unitsPerPx
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
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
