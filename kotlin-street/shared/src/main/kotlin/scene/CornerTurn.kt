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
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
    val front: ImageBitmap, val side: ImageBitmap, val emis: ImageBitmap? = null,
    val x0: Float, val x1: Float, val z0: Float, val z1: Float,
    /** The way the front faces: 0 = -Z (towards the first camera), 1 = +X, 2 = -X. */
    val frontN: Int,
    /** A billboard standing on the roof, if it has one. */
    val prop: ImageBitmap? = null,
    /** On the camera's side of the junction (east of it): it ends up behind the camera, so it is gone by the end of the turn. */
    val nearSide: Boolean = false,
    /** Behind the camera at the start and in view at the end (the far side of a street crossing): it comes in late. */
    val fadeInLate: Boolean = false,
) {
    val h: Float get() = front.height.toFloat()
    val cx: Float get() = (x0 + x1) / 2f
    val cz: Float get() = (z0 + z1) / 2f
}

/** The light of an hour: sky colours, and how much of each colour the walls keep. */
internal class TurnLight(val top: Color, val bottom: Color, val ground: Color, val r: Float, val g: Float, val b: Float, val hazeR: Float, val hazeG: Float, val hazeB: Float) {
    /** How misty the air is (0..1): the distance fades sooner. */
    var mist = 0f

    /** 0 by day, 1 at night: windows and lamps glow. */
    val night: Float get() = ((.5f - (r + g) / 2f) / .3f).coerceIn(0f, 1f)

    /** The same light under cloud, rain and mist: greyer sky, duller walls. */
    fun weathered(cloud: Float, rain: Float, mist: Float): TurnLight {
        val c = (cloud * .8f + rain * .2f).coerceIn(0f, 1f)
        if (c < .01f && mist < .01f) return this
        val grey = Color(0xFF7d8896)
        val dim = 1f - .22f * c - .1f * rain
        val t = TurnLight(androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.lerp(top, grey, .75f * c), bottom, .6f * mist), androidx.compose.ui.graphics.lerp(bottom, Color(0xFFa6b0bd), .7f * c + .25f * mist),
            androidx.compose.ui.graphics.lerp(ground, Color(0xFF2a2d34), .4f * rain), r * dim, g * dim, b * (dim + .03f), hazeR, hazeG, hazeB)
        t.mist = mist
        return t
    }

    companion object {
        var current = noon()
        /** The turn played backwards (coming back from the cross street): the player walks the other way. */
        var reverse = false
        /** 0 spring, 1 summer, 2 autumn, 3 winter. */
        var season = 1
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

/**
 * The street view's framing, so the turn starts exactly where the street is: its ground line and horizon (screen pixels)
 * and how many screen pixels one sprite pixel takes.
 */
class TurnFrame(val ground: Float, val horizon: Float, val px: Float)

internal class Cam(val phi: Float, val pz: Float, val w: Float, val h: Float, frame: TurnFrame? = null) {
    val c = cos(phi); val s = sin(phi)
    val dc = 820f          // how far behind the middle of the junction the camera stands
    // with the street's framing: the main street's fronts (at the first frame's distance) are as big as in the street, their
    // foot on its ground line, the horizon its horizon; without it, a fixed framing scaled to the view's height
    val f = if (frame != null) (dc + 100f) * frame.px else dc * 1.15f * h / 480f
    val eye = 205f   // eye height above the street (the street view's own "horizon" is only its far city's foot)
    // the horizon where the main street's foot lands on the street's ground line at the first frame's distance
    val hy = if (frame != null) frame.ground - eye * frame.px else h * .27f
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
private fun haze(d: Float): Float {
    val m = TurnLight.current.mist
    return ((d - 900f * (1f - .45f * m)) / (1900f * (1f - .25f * m))).coerceIn(0f, .62f + .1f * m)
}

/** The face's perspective (drawing happens on one thread). */
private val faceM = androidx.compose.ui.graphics.Matrix()

/** The night glow's warm tint, made once. */
private val glowFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, .82f, 0f, 0f, 0f, 0f, 0f, .52f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

/** The walls' tints by (light kept, haze), remade only when the light changes (it holds through a turn). */
private val faceFilters = HashMap<Int, ColorFilter>()
private var faceLight: TurnLight? = null

private fun wallFilter(keep: Float, hz: Float): ColorFilter {
    val l = TurnLight.current
    val was = faceLight
    if (was == null || was.r != l.r || was.g != l.g || was.b != l.b || was.hazeR != l.hazeR || was.hazeG != l.hazeG || was.hazeB != l.hazeB) { faceFilters.clear(); faceLight = l }
    val key = ((keep * 255f).toInt() shl 8) or (hz * 255f).toInt()
    return faceFilters.getOrPut(key) {
        ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            keep * l.r, 0f, 0f, 0f, l.hazeR * hz,
            0f, keep * l.g, 0f, 0f, l.hazeG * hz,
            0f, 0f, keep * 1.04f * l.b, 0f, l.hazeB * hz,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
}

private fun DrawScope.face(cam: Cam, img: ImageBitmap, h: Float, ax: Float, az: Float, bx: Float, bz: Float, flip: Boolean, tint: Float, alpha: Float, glow: Boolean = false, base: Float = 0f, plate: Boolean = true) {
    val n = img.width
    // one filter per face, hazed by the distance of its middle
    val dMid = cam.v((ax + bx) / 2f, (az + bz) / 2f) + cam.dc
    val hz = haze(dMid); val keep = tint * (1f - hz)
    val filter = if (glow) glowFilter else wallFilter(keep, hz)
    val dA = cam.v(ax, az) + cam.dc; val dB = cam.v(bx, bz) + cam.dc
    // behind the picture: the window openings cut into it show a dark room, not whatever stands behind the building
    if (plate && !glow && dA >= 40f && dB >= 40f) {
        val plate = Path()
        val ua = cam.sx(cam.u(ax, az), dA); val ub = cam.sx(cam.u(bx, bz), dB)
        plate.moveTo(ua, cam.sy(h, dA)); plate.lineTo(ub, cam.sy(h, dB)); plate.lineTo(ub, cam.sy(0f, dB)); plate.lineTo(ua, cam.sy(0f, dA)); plate.close()
        val l = TurnLight.current
        val glassC = androidx.compose.ui.graphics.lerp(Color(0xFF2b3550), l.bottom, .38f * (1f - l.night))
        drawPath(plate, Color(glassC.red * keep * l.r, glassC.green * keep * l.g, glassC.blue * keep * l.b, alpha))
    }
    // one draw for the whole face: a face is a flat quad, so a single perspective matrix maps the picture onto it exactly
    // (x' = (w/2 d + f u) / d and y' = (hy d + f (eye - y)) / d, with u and d linear along the face; w = depth)
    if (dA < 40f || dB < 40f) return
    val uA = cam.u(ax, az); val uB = cam.u(bx, bz)
    // the picture's column 0 is at A (at B when flipped); X runs along the face, Y down from its top
    val u0 = if (flip) uB else uA; val d0 = if (flip) dB else dA
    val du = if (flip) uA - uB else uB - uA; val dd = if (flip) dA - dB else dB - dA
    val nf = img.width.toFloat(); val hh = img.height.toFloat()
    val m = faceM.values
    m.fill(0f)
    m[0] = (cam.w / 2f * dd + cam.f * du) / nf; m[12] = cam.w / 2f * d0 + cam.f * u0
    m[1] = cam.hy * dd / nf; m[5] = cam.f * h / hh; m[13] = cam.hy * d0 + cam.f * (cam.eye - base - h)
    m[3] = dd / nf; m[15] = d0
    m[10] = 1f
    withTransform({ transform(faceM) }) {
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height),
            alpha = alpha, colorFilter = filter, filterQuality = FilterQuality.None)
    }
}

private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/**
 * How much of a building is left: buildings that come between the camera and the junction fade out (they are behind
 * the player) gently, over a wide band, and those on the camera's side of the junction are gone by the end of the turn.
 */
private fun buildingFade(cam: Cam, b: TurnBuilding, e: Float): Float {
    val v = cam.v(b.cx, b.cz)
    val depth = 1f - smooth(0f, 1f, (-v - 10f) / 210f)
    if (b.fadeInLate) return smooth(.45f, .85f, e)
    return if (b.nearSide) minOf(depth, 1f - smooth(.5f, .9f, e)) else depth
}

/** Lamps, trees and benches on the near side: a short fade that is over before any of them comes to rest. */
private fun propFade(v: Float) = ((v + 45f) / 30f).coerceIn(0f, 1f)

private fun DrawScope.building(cam: Cam, b: TurnBuilding, e: Float) {
    val a = buildingFade(cam, b, e)
    if (a <= .02f) return
    // a fading building is drawn whole into a layer and then let through at [a]: a solid box going, not overlapping ghosts
    val layered = a < .98f
    if (layered) drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = a }) }
    buildingBody(cam, b)
    if (layered) drawIntoCanvas { it.restore() }
}

private fun DrawScope.buildingBody(cam: Cam, b: TurnBuilding) {
    val a = 1f
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
    if (b.h < cam.eye - 5f) ground(cam, listOf(b.x0 to b.z0, b.x1 to b.z0, b.x1 to b.z1, b.x0 to b.z1), b.h, (if (TurnLight.season == 3) Color(0xFFdde5ee) else Color(0xFF454852)).let { c -> val l = TurnLight.current; Color(c.red * (if (TurnLight.season == 3) l.r else 1f), c.green * (if (TurnLight.season == 3) l.g else 1f), c.blue * (if (TurnLight.season == 3) l.b else 1f), a) })
    val fnx = when (b.frontN) { 1 -> 1f; 2 -> -1f; else -> 0f }; val fnz = when (b.frontN) { 0 -> -1f; 3 -> 1f; else -> 0f }
    fun draw(f: F, k: Float) {
        val isFront = f.id == b.frontN
        val img = if (isFront) b.front else b.side
        val flip = !isFront && (f.ax * fnx + f.az * fnz) < (f.bx * fnx + f.bz * fnz)
        face(cam, img, b.h, f.ax, f.az, f.bx, f.bz, flip, k * (.66f + .34f * max(0f, f.nx * sun[0] + f.nz * sun[1])), a)
    }
    for (f in faces) if (toward(f)) draw(f, 1f)
    // a rooftop billboard: a panel parallel to the front, standing halfway back over the roof (one sprite pixel to a
    // building pixel, as in the street); seen from behind it is not drawn
    val pr = b.prop
    if (pr != null) {
        val w = pr.width.toFloat(); val hh = pr.height.toFloat()
        val (ax, az, bx, bz) = when (b.frontN) {
            1 -> listOf(b.cx, b.cz - w / 2f, b.cx, b.cz + w / 2f)
            2 -> listOf(b.cx, b.cz + w / 2f, b.cx, b.cz - w / 2f)
            3 -> listOf(b.cx + w / 2f, b.cz, b.cx - w / 2f, b.cz)
            else -> listOf(b.cx - w / 2f, b.cz, b.cx + w / 2f, b.cz)
        }
        if (fnx * (camX - b.cx) + fnz * (camZ - b.cz) > 1f)
            face(cam, pr, hh, ax, az, bx, bz, false, .95f, a, base = b.h, plate = false)
    }
    // lit windows at night, on the fronts
    val night = TurnLight.current.night
    val em = b.emis
    if (night > .02f && em != null)
        for (f in faces) if (toward(f) && f.id == b.frontN) face(cam, em, b.h, f.ax, f.az, f.bx, f.bz, false, 1f, night * .7f, glow = true)
}

private fun DrawScope.scene(cam: Cam, bs: List<TurnBuilding>, lamp: ImageBitmap?, walker: ImageBitmap?, progress: Float, trees: List<ImageBitmap> = emptyList(), bench: ImageBitmap? = null, skyBehind: Boolean = false) {
    val e = progress * progress * (3f - 2f * progress)
    // the street's own sky is behind (the stage draws it, panning as we turn): then no sky of our own
    if (!skyBehind) drawRect(Brush.verticalGradient(listOf(TurnLight.current.top, TurnLight.current.bottom), 0f, cam.hy), size = androidx.compose.ui.geometry.Size(size.width, cam.hy + 2f))
    // below the horizon, beyond where the ground is drawn: the far ground (own sky), or a thin strip of the sky's own low haze
    // over the street's sky (a dark band there would show above every low roof)
    if (skyBehind) drawRect(Brush.verticalGradient(listOf(TurnLight.current.bottom.copy(alpha = 0f), TurnLight.current.bottom), cam.hy - 10f, cam.hy + 30f), Offset(0f, cam.hy - 10f), androidx.compose.ui.geometry.Size(size.width, size.height - cam.hy + 10f))
    else drawRect(Brush.verticalGradient(listOf(TurnLight.current.bottom, TurnLight.current.ground), cam.hy, cam.hy + 150f), Offset(0f, cam.hy), androidx.compose.ui.geometry.Size(size.width, size.height - cam.hy))
    val far = 1400f
    // the two roads and their pavements
    val winter = TurnLight.season == 3
    // the road stays dark asphalt in every season (the street's does); snow lies on the pavements and roofs
    val roadC = if (winter) Color(0xFF434a5a) else Color(0xFF3b3e47)
    ground(cam, listOf(-far to -170f, far to -170f, far to -40f, -far to -40f), 0f, roadC)
    ground(cam, listOf(40f to -170f, 160f to -170f, 160f to far, 40f to far), 0f, roadC)
    val pave = if (winter) Color(0xFFd3dae4) else if (TurnLight.season == 2) Color(0xFF948b86) else Color(0xFF8d919c)
    ground(cam, listOf(-far to -40f, 40f to -40f, 40f to 0f, -far to 0f), 0f, pave)
    ground(cam, listOf(160f to -40f, far to -40f, far to 0f, 160f to 0f), 0f, pave)
    ground(cam, listOf(0f to 0f, 40f to 0f, 40f to far, 0f to far), 0f, pave)
    ground(cam, listOf(160f to 0f, 200f to 0f, 200f to far, 160f to far), 0f, pave)
    ground(cam, listOf(-far to -170f, far to -170f, far to -178f, -far to -178f), 0f, pave)
    // the near side of the main street (behind the first camera) and the lots east of the cross street (behind the last):
    // road, as the street view's foreground is, so neither end of the turn shows the bare backdrop
    ground(cam, listOf(-far to -900f, far to -900f, far to -178f, -far to -178f), 0f, roadC)
    ground(cam, listOf(240f to 0f, far to 0f, far to far, 240f to far), 0f, roadC)
    ground(cam, listOf(200f to 0f, 240f to 0f, 240f to far, 200f to far), 0f, pave)
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
    // the far ground fades into the haze like the walls do (on flat ground, a screen row is a distance)
    run {
        val l = TurnLight.current
        val hzFar = haze(4000f)
        if (hzFar > .01f) {
            val yNear = cam.sy(0f, 900f * (1f - .45f * l.mist))
            val hc = Color(l.hazeR / 255f, l.hazeG / 255f, l.hazeB / 255f)
            drawRect(Brush.verticalGradient(listOf(hc.copy(alpha = hzFar), hc.copy(alpha = 0f)), cam.hy, yNear), Offset(0f, cam.hy), androidx.compose.ui.geometry.Size(size.width, (yNear - cam.hy).coerceAtLeast(0f)))
        }
    }
    // far to near: buildings, lamps and trees together, so a lamp is hidden by a building that stands in front of it.
    // Sorted by depth (rounded), then by distance from the camera: in a row seen straight on, the farther box first.
    val camX = Cam.PX - cam.s * cam.dc; val camZ = cam.pz - cam.c * cam.dc
    class Item(x: Float, z: Float, val draw: DrawScope.() -> Unit) {
        val v = kotlin.math.round(cam.v(x, z) * 16f)
        val dist2 = (x - camX) * (x - camX) + (z - camZ) * (z - camZ)
    }
    val items = ArrayList<Item>()
    for (b in bs) items += Item(b.cx, b.cz) { building(cam, b, e) }
    val lt0 = TurnLight.current
    fun propFilter(x: Float, z: Float, m: FloatArray) = ColorFilter.colorMatrix(ColorMatrix(hazed(m, haze(cam.v(x, z) + cam.dc), lt0.hazeR, lt0.hazeG, lt0.hazeB)))
    val lightM = floatArrayOf(lt0.r, 0f, 0f, 0f, 0f, 0f, lt0.g, 0f, 0f, 0f, 0f, 0f, lt0.b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    val leafM = seasonTreeMatrix(if (TurnLight.season == 2) 1f else 0f, if (TurnLight.season == 3) 1f else 0f, if (TurnLight.season == 0) 1f else 0f, lt0.r, lt0.g, lt0.b)
    lamp?.let { l ->
        for ((x, z) in listOf(30f to -30f, 170f to -30f, 30f to 330f, 170f to 650f, -300f to -30f, 450f to -30f)) {
            items += Item(x, z) {
                val d = cam.v(x, z) + cam.dc
                val la = propFade(cam.v(x, z))
                if (d >= 60f && la > .05f) {
                    val k = cam.f / d
                    val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
                    val lt = TurnLight.current
                    withTransform({ translate(px - 15f * k, py - 127f * k); scale(k, k, Offset.Zero) }) {
                        drawImage(l, filterQuality = FilterQuality.None, alpha = la, colorFilter = propFilter(x, z, lightM))
                    }
                    val nt = lt.night
                    if (nt > .02f) {
                        // on the lantern glass (sprite pixel 50, 48; the foot is at 15, 127), and the lantern itself lit
                        withTransform({ translate(px - 15f * k, py - 127f * k); scale(k, k, Offset.Zero) }) {
                            drawImage(l, srcOffset = IntOffset(40, 30), srcSize = IntSize(20, 22), dstOffset = IntOffset(40, 30), dstSize = IntSize(20, 22), alpha = nt * la, filterQuality = FilterQuality.None,
                                colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1.3f, 0f, 0f, 0f, 60f, 0f, 1.1f, 0f, 0f, 40f, 0f, 0f, .6f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))))
                        }
                        val c = Offset(px + 35f * k, py - 79f * k); val rad = 60f * k
                        drawCircle(Brush.radialGradient(listOf(Color(0xFFffd9a0).copy(alpha = .75f * nt * la), Color.Transparent), c, rad), rad, c, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
                    }
                }
            }
        }
    }
    // trees at the back of the pavement, where two buildings meet (foot at (31, 95) of the sprite, at the street's 1x)
    if (trees.isNotEmpty()) {
        val spots = listOf(8f to 354f, 192f to 578f, 8f to 802f, 192f to 1026f, -224f to -8f, -448f to -8f, 424f to -8f)
        for ((n, sp) in spots.withIndex()) {
            val (x, z) = sp; val img = trees[n % trees.size]
            items += Item(x, z) {
                val d = cam.v(x, z) + cam.dc
                val ta = propFade(cam.v(x, z))
                if (d >= 60f && ta > .05f) {
                    val k = cam.f / d
                    val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
                    withTransform({ translate(px - 31f * k, py - 95f * k); scale(k, k, Offset.Zero) }) {
                        drawImage(img, filterQuality = FilterQuality.None, alpha = ta, colorFilter = propFilter(x, z, leafM))
                    }
                }
            }
        }
    }
    // benches in front of the buildings, facing the street (foot at (31, 61) of the sprite)
    bench?.let { img ->
        for ((x, z) in listOf(14f to 200f, 186f to 430f, -330f to -10f, 520f to -10f)) {
            items += Item(x, z) {
                val d = cam.v(x, z) + cam.dc
                val ba = propFade(cam.v(x, z))
                if (d >= 60f && ba > .05f) {
                    val k = 1.0f * cam.f / d
                    val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
                    withTransform({ translate(px - 31f * k, py - 61f * k); scale(k, k, Offset.Zero) }) {
                        drawImage(img, filterQuality = FilterQuality.None, alpha = ba, colorFilter = propFilter(x, z, lightM))
                    }
                }
            }
        }
    }
    for (it in items.sortedWith(compareByDescending<Item> { it.v }.thenByDescending { it.dist2 })) with(it) { draw() }
    // the player: along the pavement to the corner, then round it and away down the cross street
    walker?.let { w ->
        val p = progress.coerceIn(0f, 1f)
        val a = ((p - .02f) / .36f).coerceIn(0f, 1f)          // along the first street
        val b = ((p - .36f) / .62f).coerceIn(0f, 1f)          // down the cross street
        val x = -150f + 170f * a
        val z = -22f + 470f * b
        val sgn = if (TurnLight.reverse) -1f else 1f
        val dx = if (b > 0f) 0f else sgn; val dz = if (b > 0f) sgn else 0f
        val d = cam.v(x, z) + cam.dc
        if (d > 60f) {
            val right = dx * cam.c - dz * cam.s; val depth = dx * cam.s + dz * cam.c
            val group = if (abs(right) >= abs(depth)) 0 else if (depth > 0f) 1 else 3
            val flip = group == 0 && right < 0f
            val frame = ((p * 14f).toInt()) % 6
            val k = cam.f / d   // one sprite pixel to a world unit, as the street draws people
            val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
            withTransform({ translate(px, py - 61f * k); scale(if (flip) -k else k, k, Offset.Zero) }) {
                drawImage(w, srcOffset = IntOffset((group * 6 + frame) * 32, 128), srcSize = IntSize(32, 64), dstOffset = IntOffset(-16, 0), dstSize = IntSize(32, 64), filterQuality = FilterQuality.None)
            }
        }
    }
}


/** Our building art for the turn: the sprite pairs (front, side) and the lamp. */
class TurnArt(val fronts: List<ImageBitmap>, val sides: List<ImageBitmap>, val lamp: ImageBitmap?, val walker: ImageBitmap? = null, val emis: List<ImageBitmap?> = emptyList(), val props: List<ImageBitmap?> = emptyList(), val trees: List<ImageBitmap> = emptyList(), val bench: ImageBitmap? = null, val rows: TurnRows? = null)

/** One building's pictures, for the turn. */
class TurnFacade(val front: ImageBitmap, val side: ImageBitmap, val emis: ImageBitmap?, val prop: ImageBitmap?)

/**
 * The real crossroads (tools/export-street.ts): the main street's buildings west of the junction (west to east), the first
 * ones east of it, and the cross street's two rows (nearest the junction first). When given, the turn shows these.
 */
class TurnRows(val mainWest: List<TurnFacade>, val mainEast: List<TurnFacade>, val crossWest: List<TurnFacade>, val crossEast: List<TurnFacade>)

fun turnBuildings(art: TurnArt): List<TurnBuilding> {
    art.rows?.let { r ->
        val d = 120f
        fun b(f: TurnFacade, x0: Float, x1: Float, z0: Float, z1: Float, n: Int) = TurnBuilding(f.front, f.side, f.emis, x0, x1, z0, z1, n, f.prop, nearSide = x0 >= 200f)
        val out = ArrayList<TurnBuilding>()
        // the main street, ending at the corner (x = 0): the last three before the junction
        r.mainWest.takeLast(3).reversed().forEachIndexed { i, f -> out += b(f, -224f * (i + 1), -224f * i, 0f, d, 0) }
        r.mainEast.take(2).forEachIndexed { i, f -> out += b(f, 200f + 224f * i, 424f + 224f * i, 0f, d, 0) }
        // the cross street: its west row faces +X (the camera ends up looking at it), its east row faces -X
        r.crossWest.take(5).forEachIndexed { i, f -> out += b(f, -d, 0f, 130f + 224f * i, 354f + 224f * i, 1) }
        r.crossEast.take(5).forEachIndexed { i, f -> out += b(f, 200f, 200f + d, 130f + 224f * i, 354f + 224f * i, 2) }
        if (out.isNotEmpty()) return out
    }
    val n = art.fronts.size
    if (n == 0) return emptyList()
    fun f(i: Int) = art.fronts[i % n]
    fun s(i: Int) = art.sides[i % n]
    val d = 120f
    fun TurnBuilding(f: ImageBitmap, s: ImageBitmap, x0: Float, x1: Float, z0: Float, z1: Float, n: Int): TurnBuilding {
        val i = art.fronts.indexOf(f)
        return TurnBuilding(f, s, art.emis.getOrNull(i), x0, x1, z0, z1, n, art.props.getOrNull(i), nearSide = x0 >= 200f)
    }
    return listOf(
        TurnBuilding(f(1), s(1), -672f, -448f, 0f, d, 0), TurnBuilding(f(0), s(0), -448f, -224f, 0f, d, 0), TurnBuilding(f(2), s(2), -224f, 0f, 0f, d, 0),
        TurnBuilding(f(3), s(3), 200f, 424f, 0f, d, 0), TurnBuilding(f(4), s(4), 424f, 648f, 0f, d, 0),
        TurnBuilding(f(5), s(5), -d, 0f, 130f, 354f, 1), TurnBuilding(f(6), s(6), -d, 0f, 354f, 578f, 1), TurnBuilding(f(7), s(7), -d, 0f, 578f, 802f, 1),
        TurnBuilding(f(4), s(4), -d, 0f, 802f, 1026f, 1), TurnBuilding(f(0), s(0), -d, 0f, 1026f, 1250f, 1),
        TurnBuilding(f(8), s(8), 200f, 200f + d, 130f, 354f, 2), TurnBuilding(f(1), s(1), 200f, 200f + d, 354f, 578f, 2), TurnBuilding(f(2), s(2), 200f, 200f + d, 578f, 802f, 2),
        TurnBuilding(f(3), s(3), 200f, 200f + d, 802f, 1026f, 2), TurnBuilding(f(5), s(5), 200f, 200f + d, 1026f, 1250f, 2),
    )
}

/** A well-mixed 0..1 value for particle [i] and [salt] (no visible rows or cycles). */
private fun hash01(i: Int, salt: Int): Float {
    var h = i * 0x27d4eb2d + salt * 0x165667b1
    h = (h xor (h ushr 15)) * 0x2c1b3c6d
    h = (h xor (h ushr 12)) * 0x297a2d39
    h = h xor (h ushr 15)
    return (h ushr 8) / 16777216f
}

/**
 * Draws the turn at [progress] (0 = looking along the street we came down, 1 = down the cross street). [clock] is seconds
 * since the turn began, for the rain and snow, which keep falling whichever way the turn plays and while it holds.
 */
fun DrawScope.drawCornerTurn(buildings: List<TurnBuilding>, lamp: ImageBitmap?, walker: ImageBitmap?, progress: Float, hour: Float = 14f, season: Int = 1, trees: List<ImageBitmap> = emptyList(), bench: ImageBitmap? = null, reverse: Boolean = false, clock: Float = progress * 2.4f, frame: TurnFrame? = null, skyBehind: Boolean = false) {
    TurnLight.reverse = reverse
    TurnLight.season = season
    val wx = com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced
    TurnLight.current = TurnLight.at(hour).weathered(wx?.cloudiness ?: 0f, wx?.rain ?: 0f, wx?.mist ?: 0f)
    val e = progress * progress * (3f - 2f * progress)
    val cam = Cam(-(Math.PI / 2).toFloat() * e, -100f + 420f * e, size.width, size.height, frame)
    scene(cam, buildings, lamp, walker, progress, trees, bench, skyBehind)
    val wrapW = size.width + 60f; val wrapH = size.height + 60f
    // the particles pan with the camera as it turns (about half the speed of the street, as if a little nearer)
    val pan = cam.phi * cam.f * .6f
    fun wrap(v: Float, m: Float) = ((v % m) + m) % m
    if (season == 3) {
        // snow: slow, drifting flakes
        val t = clock * 1.25f
        for (i in 0 until 140) {
            val hx = hash01(i, 1); val hy = hash01(i, 2); val sp = .5f + hash01(i, 3) * .45f
            val x = wrap(hx * wrapW + pan + sin((t + i) * 1.3f) * 14f, wrapW) - 30f
            val y = wrap(hy * wrapH + t * 130f * sp, wrapH) - 30f
            drawCircle(Color.White.copy(alpha = .75f), 1.3f + sp, Offset(x, y))
        }
    }
    val rain = wx?.rain ?: 0f
    if (rain > .02f) {
        // streaks falling and slanting along their own direction
        val t = clock * 1.25f
        val n = (260 * rain).toInt()
        for (i in 0 until n) {
            val hx = hash01(i, 4); val hy = hash01(i, 5); val sp = .8f + hash01(i, 6) * .6f
            val fall = t * 900f * sp
            val y = wrap(hy * wrapH + fall, wrapH) - 30f
            val x = wrap(hx * wrapW + pan - fall * 6f / 22f, wrapW) - 30f
            drawLine(Color(0xFFdbe6f5).copy(alpha = .22f + .2f * rain), Offset(x, y), Offset(x - 6f, y + 22f * sp), 1.2f)
        }
    }
}

@Composable
fun CornerTurn(art: TurnArt, progress: Float, modifier: Modifier = Modifier, hour: Float = 14f, season: Int = 1, reverse: Boolean = false, clock: Float = progress * 2.4f, frame: TurnFrame? = null, skyBehind: Boolean = false) {
    val b = androidx.compose.runtime.remember(art) { turnBuildings(art) }
    Canvas(modifier.fillMaxSize()) {
        // over the stage's sky: take the stage's framing as it has just drawn (it draws first, every frame)
        val fr = frame ?: if (skyBehind && GameState.framePx > 0f) TurnFrame(GameState.frameGround, GameState.frameHorizon, GameState.framePx) else null
        drawCornerTurn(b, art.lamp, art.walker, progress, hour, season, art.trees, art.bench, reverse, clock, fr, skyBehind)
    }
}

/**
 * How far (street units) the street's sky should pan for the turn at [progress] so its clouds and distant city swing with
 * the camera: a far thing moves by the focal length times the angle, and the stage's clouds move about 0.03 dp per unit.
 */
fun turnSkyPan(progress: Float, frame: TurnFrame, density: Float): Float {
    val e = progress * progress * (3f - 2f * progress)
    val phi = -(Math.PI / 2).toFloat() * e
    return phi * (920f * frame.px) / (.03f * density)
}


// ------------------------------------------------------------------------------------------------ crossing the street

/**
 * Crossing to the other side of the street: the camera swings half a turn about the middle of the road while the player
 * walks across, so the row that was behind the camera comes round into view and the one we left goes behind us. [north]
 * is the row we start facing (west to east), [south] the row across the road (west to east). Coordinates as the corner
 * turn: the north fronts on z = 0 facing -Z, the road z -170..-40, the south fronts on z = -218 facing +Z.
 */
fun crossingBuildings(north: List<TurnFacade>, south: List<TurnFacade>): List<TurnBuilding> {
    val d = 120f
    val out = ArrayList<TurnBuilding>()
    north.takeLast(5).forEachIndexed { i, f -> val x0 = -460f + 224f * i; out += TurnBuilding(f.front, f.side, f.emis, x0, x0 + 224f, 0f, d, 0, f.prop, nearSide = true) }
    south.take(5).forEachIndexed { i, f -> val x0 = -460f + 224f * i; out += TurnBuilding(f.front, f.side, f.emis, x0, x0 + 224f, -218f - d, -218f, 3, f.prop, fadeInLate = true) }
    return out
}

/** Draws the crossing at [progress] (0 = facing the north row, 1 = facing the south row). */
fun DrawScope.drawCrossing(buildings: List<TurnBuilding>, lamp: ImageBitmap?, walker: ImageBitmap?, progress: Float, hour: Float = 14f, season: Int = 1, trees: List<ImageBitmap> = emptyList(), frame: TurnFrame? = null, skyBehind: Boolean = false) {
    TurnLight.reverse = false
    TurnLight.season = season
    val wx = com.lifemax.rpg.feature.habits.domain.WeatherOverride.forced
    TurnLight.current = TurnLight.at(hour).weathered(wx?.cloudiness ?: 0f, wx?.rain ?: 0f, wx?.mist ?: 0f)
    val e = progress * progress * (3f - 2f * progress)
    // the pivot: the middle of the road, in front of the player
    val cam = Cam(Math.PI.toFloat() * e, -105f, size.width, size.height, frame)
    if (!skyBehind) drawRect(Brush.verticalGradient(listOf(TurnLight.current.top, TurnLight.current.bottom), 0f, cam.hy), size = androidx.compose.ui.geometry.Size(size.width, cam.hy + 2f))
    // below the horizon, beyond where the ground is drawn: the far ground (own sky), or a thin strip of the sky's own low haze
    // over the street's sky (a dark band there would show above every low roof)
    if (skyBehind) drawRect(Brush.verticalGradient(listOf(TurnLight.current.bottom.copy(alpha = 0f), TurnLight.current.bottom), cam.hy - 10f, cam.hy + 30f), Offset(0f, cam.hy - 10f), androidx.compose.ui.geometry.Size(size.width, size.height - cam.hy + 10f))
    else drawRect(Brush.verticalGradient(listOf(TurnLight.current.bottom, TurnLight.current.ground), cam.hy, cam.hy + 150f), Offset(0f, cam.hy), androidx.compose.ui.geometry.Size(size.width, size.height - cam.hy))
    val far = 1600f
    val winter = season == 3
    val roadC = if (winter) Color(0xFF434a5a) else Color(0xFF3b3e47)
    val pave = if (winter) Color(0xFFd3dae4) else if (season == 2) Color(0xFF948b86) else Color(0xFF8d919c)
    ground(cam, listOf(-far to -170f, far to -170f, far to -40f, -far to -40f), 0f, roadC)
    ground(cam, listOf(-far to -40f, far to -40f, far to 0f, -far to 0f), 0f, pave)
    ground(cam, listOf(-far to -218f, far to -218f, far to -170f, -far to -170f), 0f, pave)
    ground(cam, listOf(-far to 0f, far to 0f, far to 700f, -far to 700f), 0f, roadC)
    ground(cam, listOf(-far to -900f, far to -900f, far to -218f, -far to -218f), 0f, roadC)
    val stripe = Color(0xFFd9dce3)
    for (i in -30..30) { val x = i * 60f; ground(cam, listOf(x to -107f, x + 25f to -107f, x + 25f to -103f, x to -103f), 0f, stripe) }
    // a crossing where the player crosses
    for (i in 0 until 7) { val x = 40f + i * 15f; ground(cam, listOf(x to -164f, x + 7f to -164f, x + 7f to -46f, x to -46f), 0f, stripe) }
    val camX = Cam.PX - cam.s * cam.dc; val camZ = cam.pz - cam.c * cam.dc
    class Item(x: Float, z: Float, val draw: DrawScope.() -> Unit) {
        val v = kotlin.math.round(cam.v(x, z) * 16f)
        val dist2 = (x - camX) * (x - camX) + (z - camZ) * (z - camZ)
    }
    val items = ArrayList<Item>()
    for (b in buildings) items += Item(b.cx, b.cz) { building(cam, b, e) }
    val lt0 = TurnLight.current
    val lightM = floatArrayOf(lt0.r, 0f, 0f, 0f, 0f, 0f, lt0.g, 0f, 0f, 0f, 0f, 0f, lt0.b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    val leafM = seasonTreeMatrix(if (season == 2) 1f else 0f, if (season == 3) 1f else 0f, if (season == 0) 1f else 0f, lt0.r, lt0.g, lt0.b)
    fun propFilter(x: Float, z: Float, m: FloatArray) = ColorFilter.colorMatrix(ColorMatrix(hazed(m, haze(cam.v(x, z) + cam.dc), lt0.hazeR, lt0.hazeG, lt0.hazeB)))
    fun prop(img: ImageBitmap, x: Float, z: Float, footX: Float, footY: Float, m: FloatArray, flip: Boolean) {
        items += Item(x, z) {
            val d = cam.v(x, z) + cam.dc
            val a = propFade(cam.v(x, z))
            if (d >= 60f && a > .05f) {
                val k = cam.f / d
                val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
                withTransform({ translate(px, py); scale(if (flip) -k else k, k, Offset.Zero) }) {
                    drawImage(img, dstOffset = IntOffset(-footX.toInt(), -footY.toInt()), dstSize = IntSize(img.width, img.height), srcSize = IntSize(img.width, img.height), srcOffset = IntOffset.Zero, alpha = a, filterQuality = FilterQuality.None, colorFilter = propFilter(x, z, m))
                }
            }
        }
    }
    // lamps and trees along both kerbs (the far side's are seen from behind at first: mirrored)
    lamp?.let { l -> for (x in listOf(-340f, 110f, 560f)) { prop(l, x, -30f, 15f, 127f, lightM, false); prop(l, x + 120f, -188f, 15f, 127f, lightM, true) } }
    if (trees.isNotEmpty()) for ((n, x) in listOf(-236f, -12f, 212f, 436f).withIndex()) { prop(trees[n % trees.size], x, -8f, 31f, 95f, leafM, false); prop(trees[(n + 1) % trees.size], x + 20f, -210f, 31f, 95f, leafM, false) }
    for (it in items.sortedWith(compareByDescending<Item> { it.v }.thenByDescending { it.dist2 })) with(it) { draw() }
    // the player: across the road, from the north pavement to the south one
    walker?.let { w ->
        val p = progress.coerceIn(0f, 1f)
        val z = -20f - 175f * smooth(.08f, .92f, p)
        val x = 100f
        val d = cam.v(x, z) + cam.dc
        if (d > 60f) {
            // walking -Z: towards the camera at first, away from it once it has come round
            val depth = -cam.c
            val group = if (depth > 0f) 1 else 3
            val frame = ((p * 14f).toInt()) % 6
            val k = cam.f / d
            val px = cam.sx(cam.u(x, z), d); val py = cam.sy(0f, d)
            withTransform({ translate(px, py - 61f * k); scale(k, k, Offset.Zero) }) {
                drawImage(w, srcOffset = IntOffset((group * 6 + frame) * 32, 128), srcSize = IntSize(32, 64), dstOffset = IntOffset(-16, 0), dstSize = IntSize(32, 64), filterQuality = FilterQuality.None)
            }
        }
    }
}

/** How far (street units) the street's sky pans for the crossing at [progress]: half a turn. */
fun crossingSkyPan(progress: Float, frame: TurnFrame, density: Float): Float {
    val e = progress * progress * (3f - 2f * progress)
    return -(Math.PI.toFloat() * e) * (920f * frame.px) / (.03f * density)
}

@Composable
fun StreetCrossing(art: TurnArt, north: List<TurnFacade>, south: List<TurnFacade>, progress: Float, modifier: Modifier = Modifier, hour: Float = 14f, season: Int = 1, skyBehind: Boolean = false) {
    val b = androidx.compose.runtime.remember(north, south) { crossingBuildings(north, south) }
    Canvas(modifier.fillMaxSize()) {
        val fr = if (skyBehind && GameState.framePx > 0f) TurnFrame(GameState.frameGround, GameState.frameHorizon, GameState.framePx) else null
        drawCrossing(b, art.lamp, art.walker, progress, hour, season, art.trees, fr, skyBehind)
    }
}
