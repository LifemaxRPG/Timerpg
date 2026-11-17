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
import com.lifemax.rpg.feature.habits.domain.ModelPart
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.QuadMap
import kotlin.math.max

/*
 * Our villas in the corner turn and the street crossing. The street builds a villa with a front-facing gable (its ridge
 * running back from the street: BuildingPainter.frontGable); in the turn its box of two storeys wears the same gable, so
 * it keeps its shape as the view comes round the corner: a prism on the box, its ridge from the middle of the front to
 * the middle of the back, each slope its art from above laid on its quad by one perspective map from the art's own four
 * points (as the street lays it), and each gable end the art's own triangle over the upper storey (the round window, the
 * rake boards) on the roof's edge colour, which fills the art's stepped rake up to the slopes' straight edges.
 */

/**
 * What the turn builds a villa's roof from: its model's main front gable ([gable]: its slopes' atlas pieces and their
 * points), the atlas, and the face its upper storey's front carries ([face], an atlas piece whose top row stands at
 * [faceTop] and whose left edge at [faceX] across the model's front picture, [width] wide).
 */
class TurnGableArt(
    val atlas: ImageBitmap, val gable: ModelPart, val face: IntArray?, val faceTop: Float, val faceX: Float, val width: Float,
) {
    companion object {
        /** The tallest front gable of model [m] with the face of the box it stands on, or null when it has none. */
        fun of(m: ModelShape, atlas: ImageBitmap): TurnGableArt? {
            var gi = -1
            for (i in m.parts.indices) {
                val p = m.parts[i]
                if (p.type == ModelPart.GABLE && p.face == null && (gi < 0 || p.y1 > m.parts[gi].y1)) gi = i
            }
            if (gi < 0) return null
            val box = m.parts.indices.firstOrNull { m.eaveOn[it] == gi }?.let { m.parts[it] }
            val ft = box?.ft
            return TurnGableArt(atlas, m.parts[gi], ft, if (ft != null) box.y0 + ft[3] else 0f, box?.fx ?: 0f, m.width)
        }
    }
}

/** The maps and the corners (drawing happens on one thread). */
private val gableM = Matrix()
private val gableMap = DoubleArray(9)
private val gableA = DoubleArray(9)
private val gableB = DoubleArray(9)
private val gableFrom = FloatArray(8)
private val gableQ = FloatArray(8)
private val gablePx = FloatArray(2)
private val gablePath = Path()

/** [m] as a canvas matrix (3 by 3, row-major, as [QuadMap] writes it). */
private fun setMatrix(out: Matrix, m: DoubleArray) {
    val v = out.values
    v.fill(0f)
    v[0] = m[0].toFloat(); v[4] = m[1].toFloat(); v[12] = m[2].toFloat()
    v[1] = m[3].toFloat(); v[5] = m[4].toFloat(); v[13] = m[5].toFloat()
    v[3] = m[6].toFloat(); v[7] = m[7].toFloat(); v[15] = m[8].toFloat()
    v[10] = 1f
}

/**
 * The front gable roof of villa [b] ([TurnBuilding.gable]): its two slopes, the far one first, where the camera sees
 * them from above, then the gable ends it is outside of. Drawn after the walls (it stands on them). [camX], [camZ]:
 * where the camera stands.
 */
internal fun DrawScope.turnGableRoof(cam: Cam, b: TurnBuilding, g: TurnGableArt, camX: Float, camZ: Float) {
    val p = g.gable
    // the front's left and right ends (as seen from outside) and the way into the house
    val ax: Float; val az: Float; val bx: Float; val bz: Float; val ix: Float; val iz: Float
    when (b.frontN) {
        1 -> { ax = b.x1; az = b.z0; bx = b.x1; bz = b.z1; ix = -1f; iz = 0f }
        2 -> { ax = b.x0; az = b.z1; bx = b.x0; bz = b.z0; ix = 1f; iz = 0f }
        3 -> { ax = b.x1; az = b.z1; bx = b.x0; bz = b.z1; ix = 0f; iz = -1f }
        else -> { ax = b.x0; az = b.z0; bx = b.x1; bz = b.z0; ix = 0f; iz = 1f }
    }
    val depth = if (b.frontN == 1 || b.frontN == 2) b.x1 - b.x0 else b.z1 - b.z0
    // the front picture's pixels across the slot's front (a villa's 256 on the slot's 224), its heights as drawn
    val tx = (bx - ax) / g.width; val tz = (bz - az) / g.width
    val eave = b.h
    val ridge = eave + (p.y1 - p.y0)
    val px = gablePx
    fun at(x: Float, y: Float, z: Float): Boolean {
        val d = cam.v(x, z) + cam.dc
        if (d < 40f) return false
        px[0] = cam.sx(cam.u(x, z), d); px[1] = cam.sy(y, d)
        return true
    }
    // a point [along] the front picture, [back] behind the front, at height [y]
    fun pt(along: Float, back: Float, y: Float): Boolean = at(ax + tx * along + ix * back, y, az + tz * along + iz * back)
    val mx = ax + tx * p.apex + ix * depth / 2f; val mz = az + tz * p.apex + iz * depth / 2f
    val hz = run { val m = TurnLight.current.mist; ((cam.v(mx, mz) + cam.dc - 900f * (1f - .45f * m)) / (1900f * (1f - .25f * m))).coerceIn(0f, .62f + .1f * m) }
    val sun0 = .5f; val sun1 = -.85f
    val half = (p.x1 - p.x0) / 2f * kotlin.math.sqrt(tx * tx + tz * tz)
    val pitch = kotlin.math.atan2(p.y1 - p.y0, half)
    val lip = Color(0xFF000000.toInt() or (if (p.lip >= 0) p.lip else p.front))
    val snow = TurnLight.season == 3
    // --- the slopes: the one farther from the camera first
    val lx = ax + tx * (p.x0 + p.apex) / 2f + ix * depth / 2f; val lz = az + tz * (p.x0 + p.apex) / 2f + iz * depth / 2f
    val rx = ax + tx * (p.x1 + p.apex) / 2f + ix * depth / 2f; val rz = az + tz * (p.x1 + p.apex) / 2f + iz * depth / 2f
    val leftFar = (lx - camX) * (lx - camX) + (lz - camZ) * (lz - camZ) > (rx - camX) * (rx - camX) + (rz - camZ) * (rz - camZ)
    for (k in 0..1) {
        val left = (k == 0) == leftFar
        val r = (if (left) p.left else p.right) ?: continue
        val pts = (if (left) p.leftP else p.rightP) ?: continue
        // seen from above: the slope's outward normal (out along the front, and up) towards the camera
        val out = if (left) -1f else 1f
        val len = kotlin.math.sqrt(tx * tx + tz * tz).coerceAtLeast(1e-6f)
        val nx = out * tx / len * kotlin.math.sin(pitch); val nz = out * tz / len * kotlin.math.sin(pitch); val ny = kotlin.math.cos(pitch)
        val sx = if (left) lx else rx; val sz = if (left) lz else rz
        if (nx * (camX - sx) + ny * (cam.eye - (eave + ridge) / 2f) + nz * (camZ - sz) <= 0f) continue
        // the front eave, the apex, the ridge's back end, the back eave (the art's four points in the same order)
        val xe = if (left) p.x0 else p.x1
        val q = gableQ
        val ok = pt(xe, 0f, eave).also { q[0] = px[0]; q[1] = px[1] } &&
            pt(p.apex, 0f, ridge).also { q[2] = px[0]; q[3] = px[1] } &&
            pt(p.apex, depth, ridge).also { q[4] = px[0]; q[5] = px[1] } &&
            pt(xe, depth, eave).also { q[6] = px[0]; q[7] = px[1] }
        if (!ok) continue
        for (i in 0 until 8) gableFrom[i] = pts[i]
        if (!QuadMap.quadToQuad(gableFrom, q, gableMap, gableA, gableB)) continue
        val keep = .82f + .18f * max(0f, nx * sun0 + nz * sun1) + .1f * ny
        setMatrix(gableM, gableMap)
        withTransform({ transform(gableM) }) {
            drawImage(g.atlas, IntOffset(r[0], r[1]), IntSize(r[2], r[3]), IntOffset.Zero, IntSize(r[2], r[3]),
                colorFilter = roofFilter(keep * (1f - hz), hz), filterQuality = FilterQuality.None)
        }
        if (snow) {
            gablePath.rewind(); gablePath.moveTo(q[0], q[1]); gablePath.lineTo(q[2], q[3]); gablePath.lineTo(q[4], q[5]); gablePath.lineTo(q[6], q[7]); gablePath.close()
            drawPath(gablePath, turnLit(Color(0xFFdde5ee), 1f - hz, hz), alpha = .85f)
        }
    }
    // --- the gable ends the camera is outside of: the roof's edge colour over the triangle, then the art's face over it
    val face = g.face
    for (end in 0..1) {
        val back = if (end == 0) 0f else depth
        val nOut = if (end == 0) -1f else 1f                      // the end's outward normal, along the way in
        val ex = ax + ix * back + tx * p.apex; val ez = az + iz * back + tz * p.apex
        if ((nOut * ix) * (camX - ex) + (nOut * iz) * (camZ - ez) <= 1f) continue
        val sunK = .66f + .34f * max(0f, nOut * (ix * sun0 + iz * sun1))
        if (!pt(p.x0, back, eave)) continue
        val e0x = px[0]; val e0y = px[1]
        if (!pt(p.apex, back, ridge)) continue
        val apx = px[0]; val apy = px[1]
        if (!pt(p.x1, back, eave)) continue
        gablePath.rewind(); gablePath.moveTo(e0x, e0y); gablePath.lineTo(apx, apy); gablePath.lineTo(px[0], px[1]); gablePath.close()
        drawPath(gablePath, turnLit(if (snow) Color(0xFFdde5ee) else lip, sunK * (1f - hz), hz))
        if (face == null) continue
        val rows = (g.faceTop - p.y0).toInt().coerceIn(1, face[3])
        // the face's columns run left to right as seen from outside: from the front's left end at the front, from its
        // right end at the back
        val l = if (end == 0) g.faceX else g.faceX + face[2]
        val rr = if (end == 0) g.faceX + face[2] else g.faceX
        val top = eave + rows
        val q = gableQ
        val ok = pt(l, back, top).also { q[0] = px[0]; q[1] = px[1] } && pt(rr, back, top).also { q[2] = px[0]; q[3] = px[1] } &&
            pt(rr, back, eave).also { q[4] = px[0]; q[5] = px[1] } && pt(l, back, eave).also { q[6] = px[0]; q[7] = px[1] }
        if (!ok || !quadMatrix(gableM, face[2].toFloat(), rows.toFloat(), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])) continue
        withTransform({ transform(gableM) }) {
            drawImage(g.atlas, IntOffset(face[0], face[1]), IntSize(face[2], rows), IntOffset.Zero, IntSize(face[2], rows),
                colorFilter = roofFilter(sunK * (1f - hz), hz), filterQuality = FilterQuality.None)
        }
    }
}
