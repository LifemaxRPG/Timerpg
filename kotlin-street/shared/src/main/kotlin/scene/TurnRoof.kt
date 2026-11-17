package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lifemax.rpg.feature.habits.domain.HouseShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Our pitched-roof houses' roofs in the corner turn and the street crossing: a prism on the house's box, its ridge along
 * the front at mid-depth. The turn's camera stands higher than the ridges (its eye at 205), so it sees both slopes: the
 * near one wears the art's near slope, the far one the art's far slope (with its flue), each laid on its quad by one
 * perspective matrix; the gable ends wear the end wall's own picture above its wall (the triangle with its trim and
 * vent), laid as the slopes are, under a band of the rake boards' colour up to the slopes' edges.
 */

/**
 * What the turn builds a house's roof from (its shape, its slopes' pictures, its gable: the side picture's top
 * [gableRows] rows, the triangle over the wall with its trim and vent, else a plain colour), and what stands in its yard
 * as the street shows it (TurnHouse.kt): its fence or hedge (and where snow lies on it), its porch's pictures.
 */
class TurnRoofArt(
    val shape: HouseShape, val near: ImageBitmap, val far: ImageBitmap?, val gable: Color,
    val fence: ImageBitmap? = null, val fenceSnow: ImageBitmap? = null, val porch: ImageBitmap? = null,
    val gableArt: ImageBitmap? = null, val gableRows: Int = 0,
)

/** The perspective, the path, the corners (drawing happens on one thread). */
private val roofM = Matrix()
private val roofPath = Path()
private val roofPx = FloatArray(2)
private val roofQ = FloatArray(8)

/**
 * The roofs' tints by (light kept, haze), as the walls' ([wallFilter]), remade only when the light's values change: the
 * turn and the crossing hand [TurnLight.current] a new object every frame (TurnLight.at makes one each call), so the
 * cache is kept by what the light is, not by which object carries it. An open-addressed table of plain ints (a map's
 * boxed keys would make garbage on every look-up, for every slope, porch face and fence of every house each frame).
 */
private const val ROOF_SLOTS = 512
private val roofKeys = IntArray(ROOF_SLOTS) { -1 }
private val roofTints = arrayOfNulls<androidx.compose.ui.graphics.ColorFilter>(ROOF_SLOTS)
private var roofCount = 0
private var roofLight: TurnLight? = null

internal fun roofFilter(keep: Float, hz: Float): androidx.compose.ui.graphics.ColorFilter {
    val l = TurnLight.current
    val was = roofLight
    // (the keys follow the distance as the camera moves: a long walk under one light would otherwise fill the table)
    if (was == null || was.r != l.r || was.g != l.g || was.b != l.b || was.hazeR != l.hazeR || was.hazeG != l.hazeG || was.hazeB != l.hazeB ||
        roofCount > ROOF_SLOTS / 2) {
        roofKeys.fill(-1); roofTints.fill(null); roofCount = 0; roofLight = l
    }
    val key = ((keep.coerceIn(0f, 1.5f) * 255f).toInt() shl 9) or (hz.coerceIn(0f, 1f) * 255f).toInt()
    var i = (key * -0x61c88647 ushr 23) and (ROOF_SLOTS - 1)
    while (true) {
        val at = roofKeys[i]
        if (at == key) return roofTints[i]!!
        if (at == -1) break
        i = (i + 1) and (ROOF_SLOTS - 1)
    }
    val f = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        keep * l.r, 0f, 0f, 0f, l.hazeR * hz,
        0f, keep * l.g, 0f, 0f, l.hazeG * hz,
        0f, 0f, keep * 1.04f * l.b, 0f, l.hazeB * hz,
        0f, 0f, 0f, 1f, 0f,
    )))
    roofKeys[i] = key; roofTints[i] = f; roofCount++
    return f
}

/**
 * The matrix that lays a picture [w] by [h] on the screen quad (x0, y0) its top-left corner, (x1, y1) top-right,
 * (x2, y2) bottom-right, (x3, y3) bottom-left: the unit square to a quad (Heckbert), its corners scaled to the picture.
 * False when the quad is degenerate.
 */
internal fun quadMatrix(m: Matrix, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Boolean {
    val sx = x0 - x1 + x2 - x3; val sy = y0 - y1 + y2 - y3
    val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
    val det = dx1 * dy2 - dx2 * dy1
    if (abs(det) < 1e-6f) return false
    val g = (sx * dy2 - dx2 * sy) / det
    val hh = (dx1 * sy - sx * dy1) / det
    val a = x1 - x0 + g * x1; val b = x3 - x0 + hh * x3
    val d = y1 - y0 + g * y1; val e = y3 - y0 + hh * y3
    val v = m.values
    v.fill(0f)
    v[0] = a / w; v[4] = b / h; v[12] = x0
    v[1] = d / w; v[5] = e / h; v[13] = y0
    v[3] = g / w; v[7] = hh / h; v[15] = 1f
    v[10] = 1f
    return true
}

/** The colour [c] in the turn's light, [keep] of it kept and hazed by [hz], as its walls are ([wallFilter]). */
internal fun turnLit(c: Color, keep: Float, hz: Float): Color {
    val l = TurnLight.current
    return Color(
        (c.red * keep * l.r + l.hazeR / 255f * hz).coerceIn(0f, 1f),
        (c.green * keep * l.g + l.hazeG / 255f * hz).coerceIn(0f, 1f),
        (c.blue * keep * 1.04f * l.b + l.hazeB / 255f * hz).coerceIn(0f, 1f),
    )
}

/**
 * The roof of house [b] ([TurnBuilding.roof]): the gable triangles facing the camera, then the slopes it can see. Drawn
 * after the walls (it stands on them). [camX], [camZ]: where the camera stands.
 */
internal fun DrawScope.turnHouseRoof(cam: Cam, b: TurnBuilding, r: TurnRoofArt, camX: Float, camZ: Float) {
    val sh = r.shape
    // the front's left and right ends (as seen from outside) and the way into the house
    val ax: Float; val az: Float; val bx: Float; val bz: Float; val ix: Float; val iz: Float
    when (b.frontN) {
        1 -> { ax = b.x1; az = b.z0; bx = b.x1; bz = b.z1; ix = -1f; iz = 0f }
        2 -> { ax = b.x0; az = b.z1; bx = b.x0; bz = b.z0; ix = 1f; iz = 0f }
        3 -> { ax = b.x1; az = b.z1; bx = b.x0; bz = b.z1; ix = 0f; iz = -1f }
        else -> { ax = b.x0; az = b.z0; bx = b.x1; bz = b.z0; ix = 0f; iz = 1f }
    }
    val depth = if (b.frontN == 1 || b.frontN == 2) b.x1 - b.x0 else b.z1 - b.z0
    val eave = b.h
    val ridge = eave + sh.rise
    val half = depth / 2f
    // the screen point of (x, y, z), or null in front of the near plane
    val px = roofPx
    fun at(x: Float, y: Float, z: Float): Boolean {
        val d = cam.v(x, z) + cam.dc
        if (d < 40f) return false
        px[0] = cam.sx(cam.u(x, z), d); px[1] = cam.sy(y, d)
        return true
    }
    val dMid = cam.v((ax + bx) / 2f + ix * half, (az + bz) / 2f + iz * half) + cam.dc
    val hz = run { val m = TurnLight.current.mist; ((dMid - 900f * (1f - .45f * m)) / (1900f * (1f - .25f * m))).coerceIn(0f, .62f + .1f * m) }
    val sun0 = .5f; val sun1 = -.85f
    // the gable ends: triangles on the end walls, where the camera is outside them
    for (end in 0..1) {
        val ex = if (end == 0) ax else bx; val ez = if (end == 0) az else bz
        val nx = if (end == 0) ax - bx else bx - ax; val nz = if (end == 0) az - bz else bz - az
        val len = kotlin.math.sqrt(nx * nx + nz * nz).coerceAtLeast(1e-3f)
        if ((nx * (camX - ex) + nz * (camZ - ez)) / len <= 1f) continue
        if (!at(ex, eave, ez)) continue
        val p0x = px[0]; val p0y = px[1]
        if (!at(ex + ix * half, ridge, ez + iz * half)) continue
        val p1x = px[0]; val p1y = px[1]
        if (!at(ex + ix * depth, eave, ez + iz * depth)) continue
        val p2x = px[0]; val p2y = px[1]
        val k = .66f + .34f * max(0f, (nx * sun0 + nz * sun1) / len)
        // the side picture's gable rows on the end wall's rectangle from the eave up (its sky clear, so the triangle
        // shows), its first column at the front as the end wall below it is laid. Its wall stops a slab under the roof's
        // top, as the street's does: the triangle under it is the rake boards' (white in winter), which fill that band
        // up to the slopes' edges
        val art = r.gableArt
        val rows = r.gableRows
        val board = if (art == null) r.gable else if (TurnLight.season == 3) Color(0xFFdde5ee) else Color(0xFF000000.toInt() or sh.rake)
        roofPath.rewind(); roofPath.moveTo(p0x, p0y); roofPath.lineTo(p1x, p1y); roofPath.lineTo(p2x, p2y); roofPath.close()
        drawPath(roofPath, turnLit(board, k * (1f - hz), hz))
        if (art != null && rows > 0) {
            val q = roofQ
            val ok = at(ex, eave + rows, ez).also { q[0] = px[0]; q[1] = px[1] } &&
                at(ex + ix * depth, eave + rows, ez + iz * depth).also { q[2] = px[0]; q[3] = px[1] } &&
                at(ex + ix * depth, eave, ez + iz * depth).also { q[4] = px[0]; q[5] = px[1] } &&
                at(ex, eave, ez).also { q[6] = px[0]; q[7] = px[1] }
            if (ok && quadMatrix(roofM, art.width.toFloat(), rows.toFloat(), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])) {
                withTransform({ transform(roofM) }) {
                    drawImage(art, IntOffset.Zero, IntSize(art.width, rows), IntOffset.Zero, IntSize(art.width, rows),
                        colorFilter = roofFilter(k * (1f - hz), hz), filterQuality = FilterQuality.None)
                }
            }
        }
    }
    // the two slopes: the far one first. The flue stands on the slope behind the ridge: drawn after that slope, and
    // before the near one when the camera is on the front's side (the ridge and the near slope hide its foot)
    val p = kotlin.math.atan(sh.rise / half)
    val camY = cam.eye
    val flueFirst = run {
        val cx = (ax + bx) / 2f; val cz = (az + bz) / 2f
        val fx = cx + ix * half * 1.5f - camX; val fz = cz + iz * half * 1.5f - camZ
        val nx0 = cx + ix * half * .5f - camX; val nz0 = cz + iz * half * .5f - camZ
        fx * fx + fz * fz > nx0 * nx0 + nz0 * nz0
    }
    for (pass in 0..1) {
        if (pass == 1 && flueFirst) turnHouseFlue(cam, b, camX, camZ)
        for (side in 0..1) {
            // side 0 the near slope (towards the front), 1 the far one; the one nearer the camera drawn in the second pass
            val out = if (side == 0) -1f else 1f                         // which way along the house's depth it faces
            val nx = ix * out * sin(p); val nz = iz * out * sin(p); val ny = cos(p)
            val mx = (ax + bx) / 2f + ix * (half + out * half / 2f); val mz = (az + bz) / 2f + iz * (half + out * half / 2f)
            val my = (eave + ridge) / 2f
            val facing = nx * (camX - mx) + ny * (camY - my) + nz * (camZ - mz)
            if (facing <= 0f) continue
            val near = (mx - camX) * (mx - camX) + (mz - camZ) * (mz - camZ) < run { val ox = (ax + bx) / 2f + ix * (half - out * half / 2f); val oz = (az + bz) / 2f + iz * (half - out * half / 2f); (ox - camX) * (ox - camX) + (oz - camZ) * (oz - camZ) }
            if ((pass == 1) != near) continue
            val img = if (side == 0) r.near else (r.far ?: r.near)
            // corners: the picture's top-left, top-right, bottom-right, bottom-left
            val q = roofQ
            val ok = if (side == 0) {
                at(ax + ix * half, ridge, az + iz * half).also { q[0] = px[0]; q[1] = px[1] } &&
                    at(bx + ix * half, ridge, bz + iz * half).also { q[2] = px[0]; q[3] = px[1] } &&
                    at(bx, eave, bz).also { q[4] = px[0]; q[5] = px[1] } &&
                    at(ax, eave, az).also { q[6] = px[0]; q[7] = px[1] }
            } else {
                at(ax + ix * depth, eave, az + iz * depth).also { q[0] = px[0]; q[1] = px[1] } &&
                    at(bx + ix * depth, eave, bz + iz * depth).also { q[2] = px[0]; q[3] = px[1] } &&
                    at(bx + ix * half, ridge, bz + iz * half).also { q[4] = px[0]; q[5] = px[1] } &&
                    at(ax + ix * half, ridge, az + iz * half).also { q[6] = px[0]; q[7] = px[1] }
            }
            if (!ok) continue
            if (!quadMatrix(roofM, img.width.toFloat(), img.height.toFloat(), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])) continue
            // the sky's light on a roof, and the sun's where the slope faces it
            val k = .82f + .18f * max(0f, (nx * sun0 + nz * sun1)) + .1f * ny
            withTransform({ transform(roofM) }) {
                drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height),
                    colorFilter = roofFilter(k * (1f - hz), hz), filterQuality = FilterQuality.None)
            }
            if (TurnLight.season == 3) {
                roofPath.rewind(); roofPath.moveTo(q[0], q[1]); roofPath.lineTo(q[2], q[3]); roofPath.lineTo(q[4], q[5]); roofPath.lineTo(q[6], q[7]); roofPath.close()
                drawPath(roofPath, turnLit(Color(0xFFdde5ee), 1f - hz, hz), alpha = .85f)
            }
        }
    }
    if (!flueFirst) turnHouseFlue(cam, b, camX, camZ)
}
