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
import kotlin.math.max

/*
 * Our pitched-roof houses in the corner turn (CornerTurn.kt) and the street crossing: a box of its own size (its front
 * picture's width, not stretched to the slot's 224 pixels), standing back in its slot by its front yard (as far as the
 * slot is deep), as tall as its wall up to the eave's edge, its gable end cut down to the wall's rows, and its pitched
 * roof on top (TurnRoof.kt). Round it what the street shows of it, so nothing pops in or out as the view turns: its yard's
 * ground, its fence or hedge along the slot's front, its porch or steps and its flue as lit boxes.
 */

/**
 * The turn's pictures for one of our buildings. For a pitched-roof house ([house], with its near and far slopes'
 * pictures): its gable end cut to the wall's rows (the roof's triangles are drawn by the turn), its roof, and its yard's
 * fence (with where snow lies on it) and its porch's pictures.
 */
fun turnFacade(
    front: ImageBitmap, side: ImageBitmap, emis: ImageBitmap?, prop: ImageBitmap?, house: HouseShape?,
    slope: ImageBitmap? = null, back: ImageBitmap? = null,
    fence: ImageBitmap? = null, fenceSnow: ImageBitmap? = null, porch: ImageBitmap? = null,
): TurnFacade {
    if (house == null) return TurnFacade(front, side, emis, prop)
    val rows = minOf(side.height, house.h.toInt())
    val px = IntArray(side.width * rows)
    side.readPixels(px, 0, side.height - rows, side.width, rows)
    // the gable's colour, should its picture not do: the siding's, a quarter of the way along the end wall (clear of
    // its window). The gable itself wears the side picture's rows above the wall's (the triangle, its trim and vent)
    val gable = Color(px[minOf(rows - 1, 24) * side.width + side.width / 5] or (0xFF shl 24))
    val roof = slope?.let { TurnRoofArt(house, it, back, gable, fence, fenceSnow, porch, side.takeIf { side.height > rows }, side.height - rows) }
    return TurnFacade(front, PlatformImages.create(px, side.width, rows, false), emis, prop, house, roof)
}

/**
 * Where a house stands in the slot ([x0], [x1], [z0], [z1]) a building facing [frontN] would fill: across the front only
 * its picture's width, in the middle; back from the slot's front by its yard, as far as the slot's depth leaves room.
 * The fifth number is that yard's depth (from the slot's front to the house's).
 */
internal fun houseSlot(h: HouseShape, picW: Float, x0: Float, x1: Float, z0: Float, z1: Float, frontN: Int): FloatArray {
    val alongX = frontN == 0 || frontN == 3
    val across = if (alongX) x1 - x0 else z1 - z0
    val deep = if (alongX) z1 - z0 else x1 - x0
    val pad = (across - picW) / 2f
    val back = minOf(h.setback, (deep - h.depth).coerceAtLeast(0f))
    val d = minOf(h.depth, deep)
    return when (frontN) {
        0 -> floatArrayOf(x0 + pad, x1 - pad, z0 + back, z0 + back + d, back)          // faces -Z: its front at z0
        3 -> floatArrayOf(x0 + pad, x1 - pad, z1 - back - d, z1 - back, back)          // faces +Z: its front at z1
        1 -> floatArrayOf(x1 - back - d, x1 - back, z0 + pad, z1 - pad, back)          // faces +X: its front at x1
        else -> floatArrayOf(x0 + back, x0 + back + d, z0 + pad, z1 - pad, back)       // faces -X: its front at x0
    }
}

/** The lot's width (the street's 224 pixels), which the yard and its fence take up. */
private const val LOT = 224f

/** The house's front line as seen from outside: its left end (ax, az), its direction along (tx, tz), the way in (ix, iz). */
private class HouseFrame {
    var ax = 0f; var az = 0f; var tx = 0f; var tz = 0f; var ix = 0f; var iz = 0f

    fun of(b: TurnBuilding): HouseFrame {
        when (b.frontN) {
            1 -> { ax = b.x1; az = b.z0; tx = 0f; tz = 1f; ix = -1f; iz = 0f }
            2 -> { ax = b.x0; az = b.z1; tx = 0f; tz = -1f; ix = 1f; iz = 0f }
            3 -> { ax = b.x1; az = b.z1; tx = -1f; tz = 0f; ix = 0f; iz = -1f }
            else -> { ax = b.x0; az = b.z0; tx = 1f; tz = 0f; ix = 0f; iz = 1f }
        }
        return this
    }

    /** World x and z of the point [along] the front picture's pixels, [out] in front of the wall. */
    fun x(along: Float, out: Float) = ax + tx * along - ix * out
    fun z(along: Float, out: Float) = az + tz * along - iz * out
}

/** What the drawing below reuses (drawing happens on one thread). */
private val frame = HouseFrame()
private val housePath = Path()
private val cardM = Matrix()
private val pt = FloatArray(2)
private val boxOrder = IntArray(32)
private val boxDist = FloatArray(32)

/** The screen point of (x, y, z) into [pt]; false in front of the near plane. */
private fun at(cam: Cam, x: Float, y: Float, z: Float): Boolean {
    val d = cam.v(x, z) + cam.dc
    if (d < 40f) return false
    pt[0] = cam.sx(cam.u(x, z), d); pt[1] = cam.sy(y, d)
    return true
}

/** How hazy the air makes a thing [d] from the camera, as the turn's walls are. */
private fun hazeAt(d: Float): Float {
    val m = TurnLight.current.mist
    return ((d - 900f * (1f - .45f * m)) / (1900f * (1f - .25f * m))).coerceIn(0f, .62f + .1f * m)
}

/** The sun's direction on the ground (x, z), as the turn's walls are lit. */
private const val SUN_X = .5f
private const val SUN_Z = -.85f

/** How far a house's porch reaches in the turn: its yard here is shallower than the street's (the slots are 120 deep). */
private fun porchScale(b: TurnBuilding, sh: HouseShape): Float = if (sh.setback <= 0f) 1f else (b.yard / sh.setback).coerceIn(0f, 1f)

/**
 * The yard of house [b]: its lawn (or bare earth, or snow) over the whole of its slot, from the slot's front to the
 * house's back, under everything that stands on it. [alpha]: the house's own fade.
 */
internal fun DrawScope.turnHouseYard(cam: Cam, b: TurnBuilding, alpha: Float) {
    val r = b.roof ?: return
    if (alpha <= .02f) return
    val sh = r.shape
    val f = frame.of(b)
    val w = b.front.width.toFloat()
    val pad = (LOT - w) / 2f
    val base = if (TurnLight.season == 3) Color(0xFFdde5ee) else if (sh.grass) Color(0xFF3a8551) else Color(0xFF7a6854)
    val dMid = cam.v(f.x(w / 2f, -sh.depth / 2f), f.z(w / 2f, -sh.depth / 2f)) + cam.dc
    val hz = hazeAt(dMid)
    val c = turnLit(base, (1f - hz) * if (TurnLight.season == 3) 1f else .92f, hz)
    // corners: the slot's front left and right, then back past the house
    housePath.rewind()
    var n = 0
    for ((along, out) in YARD) {
        val a = if (along < 0f) -pad else w + pad
        if (!at(cam, f.x(a, if (out > 0f) b.yard else -sh.depth), 0f, f.z(a, if (out > 0f) b.yard else -sh.depth))) return
        if (n++ == 0) housePath.moveTo(pt[0], pt[1]) else housePath.lineTo(pt[0], pt[1])
    }
    housePath.close()
    drawPath(housePath, c, alpha = alpha)
}

/** The yard's corners: (left or right of the house, in front or behind), in drawing order. */
private val YARD = arrayOf(-1f to 1f, 1f to 1f, 1f to -1f, -1f to -1f)

/**
 * House [b]'s flue, standing on its far slope: the roof draws it between its slopes ([turnHouseRoof]), so the ridge and
 * the near slope hide its foot when the camera is on the front's side.
 */
internal fun DrawScope.turnHouseFlue(cam: Cam, b: TurnBuilding, camX: Float, camZ: Float) {
    val r = b.roof ?: return
    val sh = r.shape
    val f = frame.of(b)
    turnBox(cam, f, sh.flueX0, sh.flueX1, sh.roofTop(sh.flueD1) - 2f, sh.flueTop, -sh.flueD1, -sh.flueD0,
        Color(0xFF000000.toInt() or sh.flueCap), Color(0xFF000000.toInt() or sh.flueBody), camX, camZ)
}

/**
 * What stands in front of house [b]: its porch or steps and its fence. Called twice from the house's drawing: before
 * its walls with [front] false (when the camera is behind the house, its front yard is hidden by it, so drawn first)
 * and after its roof with [front] true; each time drawn only when that is the side the camera is on.
 */
internal fun DrawScope.turnHouseParts(cam: Cam, b: TurnBuilding, front: Boolean, camX: Float, camZ: Float) {
    val r = b.roof ?: return
    val sh = r.shape
    val f = frame.of(b)
    val w = b.front.width.toFloat()
    // the camera on the yard's side of the front wall, or behind the house
    val seesFront = -(f.ix * (camX - f.ax) + f.iz * (camZ - f.az)) > 1f
    if (front != seesFront) return
    // the porch or the steps: back to front for where the camera stands (farthest first)
    val k = porchScale(b, sh)
    val boxes = sh.boxes
    val n = minOf(boxes.size, boxOrder.size)
    for (i in 0 until n) {
        val bx = boxes[i]
        val mx = f.x((bx.x0 + bx.x1) / 2f, (bx.z0 + bx.z1) / 2f * k); val mz = f.z((bx.x0 + bx.x1) / 2f, (bx.z0 + bx.z1) / 2f * k)
        boxDist[i] = (mx - camX) * (mx - camX) + (mz - camZ) * (mz - camZ)
        boxOrder[i] = i
    }
    for (i in 1 until n) { var j = i; while (j > 0 && boxDist[boxOrder[j]] > boxDist[boxOrder[j - 1]]) { val t = boxOrder[j]; boxOrder[j] = boxOrder[j - 1]; boxOrder[j - 1] = t; j-- } }
    for (i in 0 until n) {
        val bx = boxes[boxOrder[i]]
        val ft = bx.frontTex
        turnBox(cam, f, bx.x0, bx.x1, bx.y0, bx.y1, bx.z0 * k, maxOf(bx.z1 * k, bx.z0 * k + .5f),
            Color(0xFF000000.toInt() or bx.top), Color(0xFF000000.toInt() or bx.front), camX, camZ,
            side = Color(0xFF000000.toInt() or bx.side), tex = if (ft != null) r.porch else null, ft = ft)
    }
    // the fence or hedge along the slot's front, open at its gate, as the street's (its foot row on the ground)
    val fe = r.fence ?: return
    val out = b.yard - (sh.fenceD.coerceAtLeast(0f))
    val pad = (LOT - w) / 2f
    val foot = (if (sh.fenceFoot > 0f) sh.fenceFoot else fe.height.toFloat()).coerceAtMost(fe.height.toFloat())
    card(cam, f, fe, -pad, -pad + fe.width, foot, out, camX, camZ)
    if (TurnLight.season == 3) r.fenceSnow?.let { card(cam, f, it, -pad, -pad + fe.width, foot, out, camX, camZ, snow = true) }
}

/** A picture standing upright along the front line [out] in front of the wall, from [a0] to [a1], its row [rows] on the ground. */
private fun DrawScope.card(cam: Cam, f: HouseFrame, img: ImageBitmap, a0: Float, a1: Float, rows: Float, out: Float, camX: Float, camZ: Float, snow: Boolean = false) {
    val h = rows
    if (!at(cam, f.x(a0, out), h, f.z(a0, out))) return
    val x0 = pt[0]; val y0 = pt[1]
    if (!at(cam, f.x(a1, out), h, f.z(a1, out))) return
    val x1 = pt[0]; val y1 = pt[1]
    if (!at(cam, f.x(a1, out), 0f, f.z(a1, out))) return
    val x2 = pt[0]; val y2 = pt[1]
    if (!at(cam, f.x(a0, out), 0f, f.z(a0, out))) return
    val x3 = pt[0]; val y3 = pt[1]
    val ih = rows.toInt().coerceIn(1, img.height)
    if (!quadMatrix(cardM, img.width.toFloat(), ih.toFloat(), x0, y0, x1, y1, x2, y2, x3, y3)) return
    val hz = hazeAt(cam.v(f.x((a0 + a1) / 2f, out), f.z((a0 + a1) / 2f, out)) + cam.dc)
    // seen from behind it is the same picture (a fence is the same from both sides)
    val toward = -(f.ix * (camX - f.x(0f, out)) + f.iz * (camZ - f.z(0f, out)))
    val k = if (toward > 0f) .9f else .7f
    withTransform({ transform(cardM) }) {
        drawImage(img, IntOffset.Zero, IntSize(img.width, ih), IntOffset.Zero, IntSize(img.width, ih),
            alpha = if (snow) .9f else 1f, colorFilter = roofFilter(if (snow) 1f - hz else k * (1f - hz), hz), filterQuality = FilterQuality.None)
    }
}

/**
 * One box in front of or on a house ([turnHouseParts]): [x0]..[x1] along the front picture, [y0]..[y1] up, [o0]..[o1]
 * out from the wall (negative: behind it, as the flue is). Each face the camera sees, lit as the turn's walls are (the
 * top by the sky, white under snow in winter); its front face wears [tex]'s ([ft]: x, y, w, h) part when it has one.
 */
private fun DrawScope.turnBox(
    cam: Cam, f: HouseFrame, x0: Float, x1: Float, y0: Float, y1: Float, o0: Float, o1: Float, top: Color, front: Color,
    camX: Float, camZ: Float, side: Color = front, tex: ImageBitmap? = null, ft: IntArray? = null,
) {
    val cx = f.x((x0 + x1) / 2f, (o0 + o1) / 2f); val cz = f.z((x0 + x1) / 2f, (o0 + o1) / 2f)
    val hz = hazeAt(cam.v(cx, cz) + cam.dc)
    val keep = 1f - hz
    // the four upright faces: (along a, out a) to (along b, out b), and the outward normal
    for (face in 0..3) {
        val ax: Float; val ao: Float; val bx: Float; val bo: Float; val nx: Float; val nz: Float
        when (face) {
            0 -> { ax = x0; ao = o1; bx = x1; bo = o1; nx = -f.ix; nz = -f.iz }      // the front, out in the yard
            1 -> { ax = x1; ao = o0; bx = x0; bo = o0; nx = f.ix; nz = f.iz }        // the back
            2 -> { ax = x0; ao = o0; bx = x0; bo = o1; nx = -f.tx; nz = -f.tz }      // the left end
            else -> { ax = x1; ao = o1; bx = x1; bo = o0; nx = f.tx; nz = f.tz }     // the right end
        }
        val mx = f.x((ax + bx) / 2f, (ao + bo) / 2f); val mz = f.z((ax + bx) / 2f, (ao + bo) / 2f)
        if (nx * (camX - mx) + nz * (camZ - mz) <= .01f) continue
        val lit = .66f + .34f * max(0f, nx * SUN_X + nz * SUN_Z)
        if (!at(cam, f.x(ax, ao), y1, f.z(ax, ao))) return
        val p0x = pt[0]; val p0y = pt[1]
        if (!at(cam, f.x(bx, bo), y1, f.z(bx, bo))) return
        val p1x = pt[0]; val p1y = pt[1]
        if (!at(cam, f.x(bx, bo), y0, f.z(bx, bo))) return
        val p2x = pt[0]; val p2y = pt[1]
        if (!at(cam, f.x(ax, ao), y0, f.z(ax, ao))) return
        val p3x = pt[0]; val p3y = pt[1]
        if (face == 0 && tex != null && ft != null && quadMatrix(cardM, ft[2].toFloat(), ft[3].toFloat(), p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y)) {
            withTransform({ transform(cardM) }) {
                drawImage(tex, IntOffset(ft[0], ft[1]), IntSize(ft[2], ft[3]), IntOffset.Zero, IntSize(ft[2], ft[3]),
                    colorFilter = roofFilter(lit * keep, hz), filterQuality = FilterQuality.None)
            }
            continue
        }
        housePath.rewind(); housePath.moveTo(p0x, p0y); housePath.lineTo(p1x, p1y); housePath.lineTo(p2x, p2y); housePath.lineTo(p3x, p3y); housePath.close()
        drawPath(housePath, turnLit(if (face == 0) front else side, lit * keep, hz))
    }
    // the top, seen from above
    if (y1 < cam.eye - 1f) {
        housePath.rewind()
        for (i in 0..3) {
            val a = if (i == 0 || i == 3) x0 else x1; val o = if (i < 2) o0 else o1
            if (!at(cam, f.x(a, o), y1, f.z(a, o))) return
            if (i == 0) housePath.moveTo(pt[0], pt[1]) else housePath.lineTo(pt[0], pt[1])
        }
        housePath.close()
        drawPath(housePath, turnLit(if (TurnLight.season == 3) Color(0xFFdde5ee) else top, keep, hz))
    }
}
