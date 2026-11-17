package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * The faked corner turn: a crossroads of boxes wearing our building art, and a camera that yaws a quarter turn
 * about the middle of the junction while travelling down the cross street. See desktop Turn.kt for the preview.
 */
class TurnBuilding(
    val front: ImageBitmap, val side: ImageBitmap,
    val x0: Float, val x1: Float, val z0: Float, val z1: Float,
    /** The way the front faces: 0 = -Z (towards the first camera), 1 = +X, 2 = -X. */
    val frontN: Int,
) {
    val h: Float get() = front.height.toFloat()
    val cx: Float get() = (x0 + x1) / 2f
    val cz: Float get() = (z0 + z1) / 2f
}

/** The light of an hour: sky colours, and how much of each colour the walls keep. */
internal class TurnLight(val top: Color, val bottom: Color, val ground: Color, val r: Float, val g: Float, val b: Float, val hazeR: Float, val hazeG: Float, val hazeB: Float) {
    companion object {
        var current = noon()
        fun noon() = TurnLight(Color(0xFF4f9bf2), Color(0xFFbfdcfb), Color(0xFF30333b), 1f, 1f, 1f, 168f, 190f, 222f)
        private fun mix(a: TurnLight, b: TurnLight, t: Float): TurnLight {
            fun f(x: Float, y: Float) = x + (y - x) * t
            return TurnLight(androidx.compose.ui.graphics.lerp(a.top, b.top, t), androidx.compose.ui.graphics.lerp(a.bottom, b.bottom, t), androidx.compose.ui.graphics.lerp(a.ground, b.ground, t),
                f(a.r, b.r), f(a.g, b.g), f(a.b, b.b), f(a.hazeR, b.hazeR), f(a.hazeG, b.hazeG), f(a.hazeB, b.hazeB))
        }
        fun at(hour: Float): TurnLight {
            val day = noon()
            val gold = TurnLight(Color(0xFF6a8fd8), Color(0xFFffc98a), Color(0xFF3a3436), 1.08f, .9f, .72f, 235f, 170f, 120f)
            val night = TurnLight(Color(0xFF070c26), Color(0xFF1a2447), Color(0xFF0c0e18), .32f, .38f, .62f, 30f, 38f, 70f)
            return when {
                hour < 5f || hour >= 21.5f -> night
                hour < 7f -> mix(night, gold, (hour - 5f) / 2f)
                hour < 9f -> mix(gold, day, (hour - 7f) / 2f)
                hour < 16f -> day
                hour < 18.5f -> mix(day, gold, (hour - 16f) / 2.5f)
                hour < 20f -> mix(gold, mix(gold, night, .6f), (hour - 18.5f) / 1.5f)
                else -> mix(mix(gold, night, .6f), night, (hour - 20f) / 1.5f)
            }
        }
    }
}

internal class Cam(val phi: Float, val pz: Float, val w: Float, val h: Float) {
    val c = cos(phi); val s = sin(phi)
    val dc = 820f          // how far behind the middle of the junction the camera stands
    val f = dc * 1.15f     // focal length: the middle of the junction is drawn at 1.15x
    val eye = 205f         // eye height above the street
    val hy = h * .27f      // horizon
    fun u(x: Float, z: Float) = (x - PX) * c - (z - pz) * s
    fun v(x: Float, z: Float) = (x - PX) * s + (z - pz) * c
    fun sx(u: Float, d: Float) = w / 2f + u * f / d
    fun sy(y: Float, d: Float) = hy + (eye - y) * f / d
    companion object { const val PX = 100f }
}

/** A ground polygon, clipped at the near plane and filled. */
private fun DrawScope.ground(cam: Cam, pts: List<Pair<Float, Float>>, y: Float, color: Color) {
    val near = 40f
    val cs = pts.map { cam.u(it.first, it.second) to (cam.v(it.first, it.second) + cam.dc) }
    val out = ArrayList<Pair<Float, Float>>()
    for (i in cs.indices) {
        val a = cs[i]; val b = cs[(i + 1) % cs.size]
        val ain = a.second >= near; val bin = b.second >= near
        if (ain) out += a
        if (ain != bin) { val t = (near - a.second) / (b.second - a.second); out += (a.first + (b.first - a.first) * t) to near }
    }
    if (out.size < 3) return
    val p = Path()
    out.forEachIndexed { i, q -> val o = Offset(cam.sx(q.first, q.second), cam.sy(y, q.second)); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
    p.close()
    drawPath(p, color)
}

/** One face of a box: a strip of the sprite per source column, each as tall as it is at its distance. */
private fun haze(d: Float) = ((d - 900f) / 1900f).coerceIn(0f, .62f)

private fun DrawScope.face(cam: Cam, img: ImageBitmap, h: Float, ax: Float, az: Float, bx: Float, bz: Float, flip: Boolean, tint: Float, alpha: Float) {
    val n = img.width
    // one filter per face, hazed by the distance of its middle
    val dMid = cam.v((ax + bx) / 2f, (az + bz) / 2f) + cam.dc
    val k = haze(dMid); val keep = tint * (1f - k)
    val filter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        keep * TurnLight.current.r, 0f, 0f, 0f, TurnLight.current.hazeR * k,
        0f, keep * TurnLight.current.g, 0f, 0f, TurnLight.current.hazeG * k,
        0f, 0f, keep * 1.04f * TurnLight.current.b, 0f, TurnLight.current.hazeB * k,
        0f, 0f, 0f, 1f, 0f,
    )))
    for (k in 0 until n) {
        val c = if (flip) n - 1 - k else k
        val t0 = k / n.toFloat(); val t1 = (k + 1) / n.toFloat()
        val x0 = ax + (bx - ax) * t0; val z0 = az + (bz - az) * t0
        val x1 = ax + (bx - ax) * t1; val z1 = az + (bz - az) * t1
        val d0 = cam.v(x0, z0) + cam.dc; val d1 = cam.v(x1, z1) + cam.dc
        if (d0 < 40f || d1 < 40f) continue
        val sx0 = cam.sx(cam.u(x0, z0), d0); val sx1 = cam.sx(cam.u(x1, z1), d1)
        val dm = (d0 + d1) / 2f
        val top = cam.sy(h, dm); val bot = cam.sy(0f, dm)
        val left = minOf(sx0, sx1); val wid = abs(sx1 - sx0) + .7f
        withTransform({ translate(left, top); scale(wid, (bot - top) / img.height, Offset.Zero) }) {
            drawImage(img, srcOffset = IntOffset(c, 0), srcSize = IntSize(1, img.height), dstOffset = IntOffset(0, 0), dstSize = IntSize(1, img.height),
                filterQuality = FilterQuality.None, colorFilter = filter, alpha = alpha)
        }
    }
}

/** Buildings that end up between the camera and the middle of the junction fade away (they are behind the player). */
private fun nearFade(v: Float) = (1f - (-v - 20f) / 60f).coerceIn(0f, 1f)

private fun DrawScope.building(cam: Cam, b: TurnBuilding) {
    val a = nearFade(cam.v(b.cx, b.cz))
    if (a <= .02f) return
    val sun = floatArrayOf(.5f, -.85f)
    // (a, b) run left to right as seen from outside; n is the outward normal
    class F(val ax: Float, val az: Float, val bx: Float, val bz: Float, val nx: Float, val nz: Float, val id: Int)
    val faces = listOf(
        F(b.x0, b.z0, b.x1, b.z0, 0f, -1f, 0),   // -Z
        F(b.x1, b.z1, b.x0, b.z1, 0f, 1f, 3),    // +Z
        F(b.x0, b.z1, b.x0, b.z0, -1f, 0f, 2),   // -X
        F(b.x1, b.z0, b.x1, b.z1, 1f, 0f, 1),    // +X
    )
    // a wall faces the camera when the camera is on its outer side (the camera's position, not just where it looks:
    // the walls of a street off to one side are still seen)
    val camX = Cam.PX - cam.s * cam.dc; val camZ = cam.pz - cam.c * cam.dc
    fun toward(f: F) = f.nx * (camX - (f.ax + f.bx) / 2f) + f.nz * (camZ - (f.az + f.bz) / 2f) > 1f
    // the roof, seen from above (not at all from below, where only walls show)
    if (b.h < cam.eye - 5f) ground(cam, listOf(b.x0 to b.z0, b.x1 to b.z0, b.x1 to b.z1, b.x0 to b.z1), b.h, Color(0xFF454852).copy(alpha = a))
    val fnx = when (b.frontN) { 1 -> 1f; 2 -> -1f; else -> 0f }; val fnz = if (b.frontN == 0) -1f else 0f
    fun draw(f: F, k: Float) {
        val isFront = f.id == b.frontN
        val img = if (isFront) b.front else b.side
        val flip = !isFront && (f.ax * fnx + f.az * fnz) < (f.bx * fnx + f.bz * fnz)
        face(cam, img, b.h, f.ax, f.az, f.bx, f.bz, flip, k * (.66f + .34f * max(0f, f.nx * sun[0] + f.nz * sun[1])), a)
    }
    // a building that is fading keeps its far walls, so it reads as a solid ghost and not an open box
    if (a < .98f) for (f in faces) if (!toward(f)) draw(f, .55f)
    for (f in faces) if (toward(f)) draw(f, 1f)
}

private fun DrawScope.scene(cam: Cam, bs: List<TurnBuilding>, lamp: ImageBitmap?, walker: ImageBitmap?, progress: Float) {
    drawRect(Brush.verticalGradient(listOf(TurnLight.current.top, TurnLight.current.bottom), 0f, cam.hy), size = androidx.compose.ui.geometry.Size(size.width, cam.hy + 2f))
    drawRect(Brush.verticalGradient(listOf(TurnLight.current.bottom, TurnLight.current.ground), cam.hy, cam.hy + 150f), Offset(0f, cam.hy), androidx.compose.ui.geometry.Size(size.width, size.height - cam.hy))
    val far = 1400f
    // the two roads and their pavements
    ground(cam, listOf(-far to -170f, far to -170f, far to -40f, -far to -40f), 0f, Color(0xFF3b3e47))
    ground(cam, listOf(40f to -170f, 160f to -170f, 160f to far, 40f to far), 0f, Color(0xFF3b3e47))
    val pave = Color(0xFF8d919c)
    ground(cam, listOf(-far to -40f, 40f to -40f, 40f to 0f, -far to 0f), 0f, pave)
    ground(cam, listOf(160f to -40f, far to -40f, far to 0f, 160f to 0f), 0f, pave)
    ground(cam, listOf(0f to 0f, 40f to 0f, 40f to far, 0f to far), 0f, pave)
    ground(cam, listOf(160f to 0f, 200f to 0f, 200f to far, 160f to far), 0f, pave)
    ground(cam, listOf(-far to -170f, far to -170f, far to -178f, -far to -178f), 0f, pave)
    // crossing stripes on all four arms
    val stripe = Color(0xFFd9dce3)
    for (i in 0 until 7) {
        val x = 52f + i * 15f
        ground(cam, listOf(x to -36f, x + 7f to -36f, x + 7f to -6f, x to -6f), 0f, stripe)
        ground(cam, listOf(x to -170f + 6f, x + 7f to -170f + 6f, x + 7f to -140f, x to -140f), 0f, stripe)
        val z = -160f + i * 15f + 100f
        ground(cam, listOf(10f to z, 38f to z, 38f to z + 7f, 10f to z + 7f), 0f, stripe)
    }
    // dashed centre lines
    for (i in -30..30) {
        val x = i * 60f
        if (x + 25f < 40f || x > 160f) ground(cam, listOf(x to -105f, x + 25f to -105f, x + 25f to -101f, x to -101f), 0f, stripe)
        val z = 80f + i * 60f
        if (z > 0f) ground(cam, listOf(98f to z, 102f to z, 102f to z + 25f, 98f to z + 25f), 0f, stripe)
    }
    // far to near
    for (b in bs.sortedByDescending { cam.v(it.cx, it.cz) }) building(cam, b)
    lamp?.let { l ->
        for ((x, z) in listOf(30f to -30f, 170f to -30f, 30f to 330f, 170f to 650f, -300f to -30f, 450f to -30f)) {
            val d = cam.v(x, z) + cam.dc
            val la = nearFade(cam.v(x, z))
            if (d < 60f || la <= .02f) continue
            val k = .9f * cam.f / d
            val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
            withTransform({ translate(px - 15f * k, py - 127f * k); scale(k, k, Offset.Zero) }) { drawImage(l, filterQuality = FilterQuality.None, alpha = la) }
        }
    }
    // the player: along the pavement to the corner, then round it and away down the cross street
    walker?.let { w ->
        val p = progress.coerceIn(0f, 1f)
        val a = ((p - .02f) / .36f).coerceIn(0f, 1f)          // along the first street
        val b = ((p - .36f) / .62f).coerceIn(0f, 1f)          // down the cross street
        val x = -150f + 170f * a
        val z = -22f + 470f * b
        val dx = if (b > 0f) 0f else 1f; val dz = if (b > 0f) 1f else 0f
        val d = cam.v(x, z) + cam.dc
        if (d > 60f) {
            val right = dx * cam.c - dz * cam.s; val depth = dx * cam.s + dz * cam.c
            val group = if (abs(right) >= abs(depth)) 0 else if (depth > 0f) 1 else 3
            val flip = group == 0 && right < 0f
            val frame = ((p * 14f).toInt()) % 6
            val k = 1.6f * cam.f / d
            val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
            withTransform({ translate(px, py - 61f * k); scale(if (flip) -k else k, k, Offset.Zero) }) {
                drawImage(w, srcOffset = IntOffset((group * 6 + frame) * 32, 128), srcSize = IntSize(32, 64), dstOffset = IntOffset(-16, 0), dstSize = IntSize(32, 64), filterQuality = FilterQuality.None)
            }
        }
    }
}


/** Our building art for the turn: the sprite pairs (front, side) and the lamp. */
class TurnArt(val fronts: List<ImageBitmap>, val sides: List<ImageBitmap>, val lamp: ImageBitmap?, val walker: ImageBitmap? = null)

fun turnBuildings(art: TurnArt): List<TurnBuilding> {
    val n = art.fronts.size
    fun f(i: Int) = art.fronts[i % n]
    fun s(i: Int) = art.sides[i % n]
    val d = 120f
    return listOf(
        TurnBuilding(f(1), s(1), -672f, -448f, 0f, d, 0), TurnBuilding(f(0), s(0), -448f, -224f, 0f, d, 0), TurnBuilding(f(2), s(2), -224f, 0f, 0f, d, 0),
        TurnBuilding(f(3), s(3), 200f, 424f, 0f, d, 0), TurnBuilding(f(4), s(4), 424f, 648f, 0f, d, 0),
        TurnBuilding(f(5), s(5), -d, 0f, 130f, 354f, 1), TurnBuilding(f(6), s(6), -d, 0f, 354f, 578f, 1), TurnBuilding(f(7), s(7), -d, 0f, 578f, 802f, 1),
        TurnBuilding(f(4), s(4), -d, 0f, 802f, 1026f, 1), TurnBuilding(f(0), s(0), -d, 0f, 1026f, 1250f, 1),
        TurnBuilding(f(8), s(8), 200f, 200f + d, 130f, 354f, 2), TurnBuilding(f(1), s(1), 200f, 200f + d, 354f, 578f, 2), TurnBuilding(f(2), s(2), 200f, 200f + d, 578f, 802f, 2),
        TurnBuilding(f(3), s(3), 200f, 200f + d, 802f, 1026f, 2), TurnBuilding(f(5), s(5), 200f, 200f + d, 1026f, 1250f, 2),
    )
}

/** Draws the turn at [progress] (0 = looking along the street we came down, 1 = down the cross street). */
fun DrawScope.drawCornerTurn(buildings: List<TurnBuilding>, lamp: ImageBitmap?, walker: ImageBitmap?, progress: Float, hour: Float = 14f) {
    TurnLight.current = TurnLight.at(hour)
    val e = progress * progress * (3f - 2f * progress)
    val cam = Cam(-(Math.PI / 2).toFloat() * e, -100f + 420f * e, size.width, size.height)
    scene(cam, buildings, lamp, walker, progress)
}

@Composable
fun CornerTurn(art: TurnArt, progress: Float, modifier: Modifier = Modifier, hour: Float = 14f) {
    val b = androidx.compose.runtime.remember(art) { turnBuildings(art) }
    Canvas(modifier.fillMaxSize()) { drawCornerTurn(b, art.lamp, art.walker, progress, hour) }
}
