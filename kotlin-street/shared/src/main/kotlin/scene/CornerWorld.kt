package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Camera
import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.DEPTH
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.KERB
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.LANE
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.MOUTH
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.PAVE
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.HabitStack
import com.lifemax.rpg.feature.habits.domain.KeyLight
import com.lifemax.rpg.feature.habits.domain.PeopleLod
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import com.lifemax.rpg.feature.habits.domain.StreetView
import kotlin.math.max
import kotlin.math.min

/**
 * One street of the crossroads as the stage laid it out ([k] 0 the main street, 1 the cross street's west row, 2 its
 * east row), with its people ([life], none for the east row), and the view it is lit and turned by: its own resting
 * view's middle ([viewX], street x), where the light stands along it ([lightX]) and on which side ([lightFrom]).
 */
internal class CrossSide(
    val k: Int,
    val stacks: List<HabitStack>,
    val layouts: List<BuildingLayout>,
    val arts: List<BuildingArt>,
    val street: StreetLayout,
    val cameras: List<Camera>,
    val view: StreetView,
    val life: StreetLifePainter?,
) {
    var viewX = 0f
    var lightX = 0f
    var lightFrom = 1f
}

/**
 * The crossroads drawn through one [StageCamera]: the street the player is on, the other seen round the corner (through
 * the main street's gap, or past the cross street's start), and every view between as the camera swings round the corner.
 * Every building is drawn by the stage's own painter (its front pass with glass, signs and relief; its side faces and
 * roof each on their own plane), so the side street and the turn look like the street itself.
 *
 * At rest ([restStreet] 0 or 1) the street in view is drawn exactly as the stage always drew it (whole-pixel affine, its
 * own order), with the rest of the crossroads behind it in the camera's perspective; between, everything is sorted
 * front to back and drawn through the camera ([leanMain] and [leanCross] say how much of each street's cabinet look is
 * left). The block on the camera's side of the corner (the main street's buildings past the mouth and the cross street's
 * far row) stands between the camera and the corner mid-turn: as the camera's cut ([StageCamera.cutBack]) sweeps into one
 * of them it fades out whole, as a building between a game's camera and its player does, and is gone once the cut has
 * passed through it. Nothing is ever drawn cut open.
 */
internal class CornerWorld {
    val cam = StageCamera()
    var leanMain = 1f
    var leanCross = 0f
    var restStreet = 0
    /** The resting street's whole-pixel origin (screen), as the stage translates its street. */
    var ox = 0f
    var oy = 0f
    /** The resting street's view (its x), a gap's margin either side: what the stage draws of it. */
    var from = 0f
    var to = 0f
    /** The highest point of a building drawn this frame (screen y), for the clouds' ceiling and the fade under the heading. */
    var topY = Float.MAX_VALUE
        private set

    private val g = Array(4) { FloatArray(12) }
    private val bm = FloatArray(12)
    private val hm = Matrix()
    private val pt = FloatArray(3)
    private val one = ArrayList<com.lifemax.rpg.feature.habits.domain.Furniture>(1)

    // the things to sort: buildings, furniture, people
    private var n = 0
    private val kind = IntArray(MAX)
    private val sideOf = IntArray(MAX)
    private val idx = IntArray(MAX)
    private val ix0 = FloatArray(MAX)
    private val ix1 = FloatArray(MAX)
    private val iz0 = FloatArray(MAX)
    private val iz1 = FloatArray(MAX)
    private val deep = FloatArray(MAX)
    private val sx = FloatArray(MAX)
    private val sy = FloatArray(MAX)
    private val sk = FloatArray(MAX)
    /** Each item's opacity (below 1 while the cut sweeps through it) and, for the layer it then fades in, its screen bounds. */
    private val fa = FloatArray(MAX)
    private val bx0 = FloatArray(MAX)
    private val by0 = FloatArray(MAX)
    private val bx1 = FloatArray(MAX)
    private val by1 = FloatArray(MAX)
    private val fadePaint = androidx.compose.ui.graphics.Paint()
    private val drawn = IntArray(MAX)
    private val indeg = IntArray(MAX)
    private val after = BooleanArray(MAX * MAX)
    private val byDepth = IntArray(MAX)
    private val placed = BooleanArray(MAX)

    /** The ground matrix for world point ([x], [z]): the main street's band leans where it is in front of its row, the cross street's likewise. */
    private fun ground(x: Float, z: Float): FloatArray = if (z < 0f) (if (x > 0f) g[1] else g[0]) else (if (x > 0f) g[2] else g[3])

    /** Makes the ground matrices for this frame (after [StageCamera.update]). */
    private fun grounds() {
        val u = cam.unit
        cam.leaned(g[0], leanMain, 0f, -u / LANE, 0f, 0f, 0f)
        cam.leaned(g[1], leanMain, 0f, -u / LANE, leanCross, 0f, u / LANE)
        cam.leaned(g[2], 0f, 0f, 0f, leanCross, 0f, u / LANE)
        cam.m.copyInto(g[3])
    }

    private fun DrawScope.light(s: CrossSide, painter: BuildingPainter) {
        painter.lightX = s.lightX
        painter.lightFrom = s.lightFrom
        painter.viewX = s.viewX
        painter.lamps = s.street.furniture
        if (s.life != null) {
            painter.streetLife = s.life
            s.life.lightX = s.lightX
            s.life.lightFrom = s.lightFrom
        }
    }

    /**
     * Draws the crossroads. [from] and [to] are the resting street's view (street units, a gap's margin either side) and
     * [depth] its pavement band's depth (screen units), as the stage measures them; [motionOf] each building's motion.
     */
    fun DrawScope.draw(
        cr: Crossroads,
        sides: List<CrossSide>,
        painter: BuildingPainter,
        sky: SkyColors,
        light: KeyLight,
        seconds: Float,
        live: Boolean,
        accent: Color,
        detailed: Boolean,
        lod: PeopleLod,
        from: Float,
        to: Float,
        depth: Float,
        motionOf: (side: Int, i: Int) -> BuildingMotion,
    ) {
        grounds()
        val rest = restStreet
        val from = this@CornerWorld.from
        val to = this@CornerWorld.to
        // the ground: the other street first, the resting one last (its shade is what its people walk through)
        for (k in if (rest == 1) intArrayOf(0, 1) else intArrayOf(1, 0)) {
            val s = sides.getOrNull(k) ?: continue
            light(s, painter)
            if (k == rest) withTransform({ translate(ox, oy); scale(cam.unit, cam.unit, Offset.Zero) }) {
                groundAt(cr, s, painter, sky, light, seconds, from, to, depth, depth, exact = true)
            } else groundBand(cr, s, painter, sky, light, seconds, depth)
        }
        // what stands on it
        collect(cr, sides)
        sort()
        if (rest >= 0) {
            // at rest: the rest of the crossroads (all behind the street in view) in its order, then the street in view
            // exactly as the stage draws it
            for (o in 0 until n) { val q = drawn[o]; if (sideOf[q] != rest) item(q, cr, sides, painter, sky, seconds, live, accent, detailed, lod, motionOf) }
            drawResting(sides[rest], painter, sky, seconds, live, accent, detailed, lod, from, to, motionOf)
        } else for (o in 0 until n) item(drawn[o], cr, sides, painter, sky, seconds, live, accent, detailed, lod, motionOf)
    }

    /** The street in view at rest: its buildings, the lamps' glow, its furniture and people, as the stage always drew them. */
    private fun DrawScope.drawResting(
        s: CrossSide, painter: BuildingPainter, sky: SkyColors, seconds: Float, live: Boolean, accent: Color,
        detailed: Boolean, lod: PeopleLod, from: Float, to: Float, motionOf: (Int, Int) -> BuildingMotion,
    ) {
        light(s, painter)
        withTransform({ translate(ox, oy); scale(cam.unit, cam.unit, Offset.Zero) }) {
            with(painter) {
                for (i in s.layouts.indices) {
                    val l = s.street.lefts[i]
                    if (l + s.layouts[i].lot + B.DX < from || l - 20f > to) continue
                    translate(s.street.centers[i], 0f) {
                        building(s.arts[i], motionOf(s.k, i), null, accent, live, detailed, sky, lod, s.street.centers[i])
                    }
                }
                lampAir(s.street.furniture, sky, from, to, seconds)
                val life = s.life
                if (life != null) with(life) { sweep(sky, s.layouts, s.street, from, to, seconds) }
                for (f in s.street.furniture) if (f.x >= from - 30f && f.x <= to + 30f) furniture(f, sky, seconds)
                if (life != null) with(life) { people(sky, s.street.furniture, from, to, lod) }
            }
        }
    }

    /**
     * Finds [topY], the highest any building in view reaches on screen this frame: for the resting street as the stage
     * always measured it (its lots in [from]..[to], their full height), for the rest through the camera.
     */
    fun measure(cr: Crossroads, sides: List<CrossSide>, w: Float, groundY: Float) {
        grounds()
        topY = Float.MAX_VALUE
        val margin = B.GAP * cam.unit
        for (bx in cr.boxes) {
            val s = sides.getOrNull(bx.street) ?: continue
            val l = s.layouts.getOrNull(bx.index) ?: continue
            if (bx.street == restStreet) {
                val left = s.street.lefts[bx.index]
                if (left + l.lot >= from && left <= to) topY = min(topY, groundY - l.height * cam.unit)
                continue
            }
            if (!top(cr, s, bx, l.height, w, margin)) continue
            topY = min(topY, pt[1])
        }
    }

    /**
     * Projects the top edge of box [bx]'s front ([h] up, as the stage measures a building's reach): [pt] holds the
     * higher end; false if it is out of view (a gap's margin either side), behind the camera or cut.
     */
    private fun top(cr: Crossroads, s: CrossSide, bx: Crossroads.Box, h: Float, w: Float, margin: Float): Boolean {
        if (fadeOf(bx) <= 0f) return false
        boxMatrix(cr, s, bx, bm)
        val (ax, az) = rowA(s.k)
        val fx = frontX(cr, s, bx.index)
        val fz = frontZ(cr, s, bx.index)
        val hw = B.W / 2f
        cam.project(bm, fx - ax * hw, h, fz - az * hw, pt)
        if (pt[2] < NEAR) return false
        val x0 = pt[0]; val y0 = pt[1]
        cam.project(bm, fx + ax * hw, h, fz + az * hw, pt)
        if (pt[2] < NEAR) return false
        if (max(x0, pt[0]) < -margin || min(x0, pt[0]) > w + margin) return false
        pt[1] = min(y0, pt[1])
        return true
    }

    /**
     * The outlines of the buildings round the sun, for its glare where it peeks past an edge: the street at rest as the
     * stage measures them ([own], by its own frame); the rest of the crossroads, and everything mid-turn, as the
     * screen bounds of their fronts and roofs.
     */
    fun edges(cr: Crossroads, sides: List<CrossSide>, edge: com.lifemax.rpg.feature.habits.domain.SunEdge, w: Float, own: Int) {
        if (own < 0) edge.clear()
        for (bx in cr.boxes) {
            if (bx.street == own) continue
            val s = sides.getOrNull(bx.street) ?: continue
            val l = s.layouts.getOrNull(bx.index) ?: continue
            if (fadeOf(bx) < .5f) continue
            boxMatrix(cr, s, bx, bm)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var ok = true
            for (c in 0..7) {
                cam.project(bm, if (c and 1 == 0) bx.x0 else bx.x1, if (c and 2 == 0) 0f else l.height - B.DY, if (c and 4 == 0) bx.z0 else bx.z1, pt)
                if (pt[2] < NEAR) { ok = false; break }
                minX = min(minX, pt[0]); maxX = max(maxX, pt[0]); minY = min(minY, pt[1]); maxY = max(maxY, pt[1])
            }
            if (ok && maxX > 0f && minX < w) edge.box(minX, minY, maxX, maxY)
        }
    }

    // ------------------------------------------------------------------ the ground

    /**
     * Street [s]'s ground from [a] to [b] along it, in its own band's coordinates (x along it, y out from its fronts in
     * the stage's screen units): the stage's pavement, kerb and road ([depth] of road, at most [reach] of band in all),
     * what the crossroads adds (the mouth's asphalt and crossing; the far pavements, the verge), the lamps' pools, the
     * buildings' and people's shadows.
     */
    private fun DrawScope.groundAt(
        cr: Crossroads, s: CrossSide, painter: BuildingPainter, sky: SkyColors, light: KeyLight, seconds: Float,
        a: Float, b: Float, depth: Float, reach: Float, exact: Boolean,
    ) {
        with(painter) {
            pavement(sky, a, b, depth)
            if (!exact) beyond(s.k, sky, a, b, depth, reach)
            if (s.k == 0) mouth(cr, sky, a, b)
            pools(s.street.furniture, a, b, sky.dark)
            shadows(light, sky, s.layouts, s.street, a, b, min(reach, depth), seconds)
            val life = s.life
            if (life != null) with(life) { lampShadows(sky.dark, s.street.furniture, a, b) }
        }
    }

    /** The far side of a street's band, past what the stage's own band shows: the road on, the far kerb and pavement, then paving. */
    private fun DrawScope.beyond(k: Int, sky: SkyColors, a: Float, b: Float, depth: Float, reach: Float) {
        val start = BuildingPainter.PAVEMENT + 2.5f + depth
        // (the far kerb no nearer than the stage's own band reaches at rest, the bottom of its screen: what that shows
        // as road stays road in every view between)
        val roadEnd = max((if (k == 0) MAIN_ROAD else MOUTH - PAVE - KERB) / LANE, start)
        val far = roadEnd + (PAVE + KERB) / LANE
        if (reach <= start) return
        val w = b - a
        val road = lerp(sky.road, Color.Black, .3f)
        if (roadEnd > start) drawRect(road, Offset(a, start), Size(w, min(roadEnd, reach) - start))
        if (reach <= roadEnd) return
        val kerb = KERB / LANE
        drawRect(sky.kerb, Offset(a, roadEnd), Size(w, kerb))
        drawRect(SceneInk.Highlight, Offset(a, roadEnd + kerb - .9f), Size(w, .9f), alpha = .6f)
        drawRect(lerp(sky.pavement, Color.Black, .06f), Offset(a, roadEnd + kerb), Size(w, max(0f, min(far, reach) - roadEnd - kerb)))
        if (reach <= far) return
        // past the far pavement, paving: on the cross street between the east row's lots, on the main street a square
        // (seen mid-turn under the camera's swing, where a field would look like the world's edge)
        val verge = lerp(sky.pavement, Color.Black, .1f)
        drawRect(SceneInk.Occlusion, Offset(a, far), Size(w, 1.2f), alpha = .7f)
        drawRect(verge, Offset(a, far + 1.2f), Size(w, reach - far - 1.2f))
    }

    /**
     * Where the cross street meets the main street's pavement: its road carried across to the main road between dropped
     * kerbs, a zebra crossing on it along the main street's pavement line, and no wall line where no wall stands.
     */
    private fun DrawScope.mouth(cr: Crossroads, sky: SkyColors, a: Float, b: Float) {
        val x0 = cr.westEdge
        if (x0 + MOUTH < a || x0 > b) return
        val pave = lerp(sky.pavement, Color.White, .07f)
        val road = lerp(sky.road, Color.White, .04f)
        val pv = BuildingPainter.PAVEMENT
        drawRect(pave, Offset(x0, 0f), Size(MOUTH, 1.3f))
        val r0 = x0 + PAVE + KERB
        val rw = MOUTH - 2f * (PAVE + KERB)
        drawRect(road, Offset(r0, 0f), Size(rw, pv + 2.6f))
        // the dropped kerbs: the cross street's kerb lines, flush at the crossing
        drawRect(sky.kerb, Offset(x0 + PAVE, 0f), Size(KERB, pv + 2.5f))
        drawRect(sky.kerb, Offset(x0 + MOUTH - PAVE - KERB, 0f), Size(KERB, pv + 2.5f))
        drawRect(SceneInk.Occlusion, Offset(r0, 0f), Size(1.4f, pv + 2.5f), alpha = .4f)
        drawRect(SceneInk.Occlusion, Offset(r0 + rw - 1.4f, 0f), Size(1.4f, pv + 2.5f), alpha = .4f)
        // the zebra: bars along the cross street, spaced across it
        // (white paint in the pavement's own light: bright by day, dim at night)
        val zebra = lerp(sky.pavement, Color.White, .55f * (1f - .6f * sky.dark))
        var x = r0 + 4f
        while (x + 7f < r0 + rw - 2f) {
            drawRect(zebra, Offset(x, 3f), Size(7f, pv - 5f), alpha = .85f)
            x += 13f
        }
    }

    /**
     * Street [s]'s ground in the camera's perspective, in strips along it: only what is in front of the camera and in
     * view, each strip as deep as it can go without reaching behind the camera.
     */
    private fun DrawScope.groundBand(cr: Crossroads, s: CrossSide, painter: BuildingPainter, sky: SkyColors, light: KeyLight, seconds: Float, depth: Float) {
        val main = s.k == 0
        val reach = (if (main) MAIN_WIDTH + 400f else MOUTH + DEPTH + 60f) / LANE
        val lo: Float
        val hi: Float
        if (main) { lo = cr.westEdge - FAR_ALONG; hi = cr.westEdge + FAR_ALONG }
        else { lo = -cr.zOff; hi = -cr.zOff + FAR_ALONG }
        val w = size.width
        // all but at rest the band lies far in front of the camera: one piece, as the stage draws it (strips would show
        // their joins); otherwise in strips, each as deep as it can go
        val whole = (if (main) leanMain else leanCross) > NEAR_REST
        var first = Float.NaN
        var last = Float.NaN
        var shallow = reach
        var a = lo
        while (a < hi) {
            // tiles meet at the corner, where the main street's band changes its lean
            var b = a + TILE
            if (main && a < cr.westEdge && b > cr.westEdge) b = cr.westEdge
            val mid = (a + b) / 2f
            val wx = if (main) cr.mainX(mid) else cr.crossX(1f)
            val wz = if (main) cr.mainZ(1f) else cr.crossZ(mid)
            val mat = ground(wx, wz)
            // how deep this strip can go: every corner in front of the camera, and not past its own far edge
            var lim = reach
            var ok = true
            var left = true
            var right = true
            for (c in 0..1) {
                val x = if (c == 0) a else b
                val w0 = bandW(cr, main, mat, x, 0f)
                val w1 = bandW(cr, main, mat, x, reach)
                if (w0 < NEAR) { ok = false; break }
                if (w1 < NEAR) lim = min(lim, reach * (w0 - NEAR) / (w0 - w1))
                if (w0 > FAR && w1 > FAR) { ok = false; break }
                bandPoint(cr, main, mat, x, 0f)
                if (pt[0] > -w * .1f) left = false
                if (pt[0] < w * 1.1f) right = false
                bandPoint(cr, main, mat, x, lim)
                if (pt[0] > -w * .1f) left = false
                if (pt[0] < w * 1.1f) right = false
            }
            if (ok && !left && !right && lim > 1f && whole) {
                if (first.isNaN()) first = a
                last = b
                shallow = min(shallow, lim)
            } else if (ok && !left && !right && lim > 1f) {
                if (main) cam.plane(mat, -cr.westEdge, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -LANE, hm)
                else cam.plane(mat, 0f, 0f, cr.zOff, 0f, 0f, 1f, LANE, 0f, 0f, hm)
                // (each strip only its own stretch: what crosses into the next one, a dash or a shadow, is drawn once)
                withTransform({ transform(hm) }) {
                    clipRect(a, -4f, b, lim + 4f) {
                        groundAt(cr, s, painter, sky, light, seconds, a, b, min(depth, max(0f, lim - BuildingPainter.PAVEMENT - 2.5f)), lim, exact = false)
                    }
                }
            }
            a = b
        }
        if (whole && !first.isNaN()) {
            val mat = if (main) g[0] else g[2]
            if (main) cam.plane(mat, -cr.westEdge, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -LANE, hm)
            else cam.plane(mat, 0f, 0f, cr.zOff, 0f, 0f, 1f, LANE, 0f, 0f, hm)
            withTransform({ transform(hm) }) {
                groundAt(cr, s, painter, sky, light, seconds, first, last, min(depth, max(0f, shallow - BuildingPainter.PAVEMENT - 2.5f)), shallow, exact = false)
            }
        }
    }

    private fun bandW(cr: Crossroads, main: Boolean, mat: FloatArray, x: Float, y: Float): Float {
        bandPoint(cr, main, mat, x, y)
        return pt[2]
    }

    private fun bandPoint(cr: Crossroads, main: Boolean, mat: FloatArray, x: Float, y: Float) {
        if (main) cam.project(mat, cr.mainX(x), 0f, cr.mainZ(y), pt)
        else cam.project(mat, cr.crossX(y), 0f, cr.crossZ(x), pt)
    }

    // ------------------------------------------------------------------ what stands on it

    /** Fills the list of what is in view: every building, lamp, tree and bench and every person, with its footprint. */
    private fun DrawScope.collect(cr: Crossroads, sides: List<CrossSide>) {
        n = 0
        val w = size.width
        val h = size.height
        for (bx in cr.boxes) {
            if (n >= MAX) break
            val s = sides.getOrNull(bx.street) ?: continue
            val l = s.layouts.getOrNull(bx.index) ?: continue
            val a = (if (bx.street == restStreet) 1f else fadeOf(bx)) * farFade(bx.street, bx.x0, bx.x1)
            if (a <= 0f) continue
            boxMatrix(cr, s, bx, bm)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var behind = false
            for (c in 0..7) {
                cam.project(bm, if (c and 1 == 0) bx.x0 else bx.x1, if (c and 2 == 0) 0f else l.height, if (c and 4 == 0) bx.z0 else bx.z1, pt)
                if (pt[2] < NEAR) { behind = true; break }
                minX = min(minX, pt[0]); maxX = max(maxX, pt[0]); minY = min(minY, pt[1]); maxY = max(maxY, pt[1])
            }
            if (behind || maxX < -40f || minX > w + 40f || minY > h) continue
            kind[n] = BOX; sideOf[n] = bx.street; idx[n] = bx.index
            // (the layer a fading building is drawn in: up to the screen's top for its billboard, and a margin for its relief)
            fa[n] = a; bx0[n] = minX - 24f; by0[n] = 0f; bx1[n] = maxX + 24f; by1[n] = maxY + 24f
            ix0[n] = bx.x0; ix1[n] = bx.x1; iz0[n] = bx.z0; iz1[n] = bx.z1
            deep[n] = cam.depth((bx.x0 + bx.x1) / 2f, (bx.z0 + bx.z1) / 2f)
            n++
        }
        for (k in 0..1) {
            val s = sides.getOrNull(k) ?: continue
            for ((i, f) in s.street.furniture.withIndex()) {
                if (n >= MAX) break
                val x = if (k == 0) cr.mainX(f.x) else 0f
                val z = if (k == 0) 0f else cr.crossZ(f.x)
                if (!billboard(n, x, z, w, 160f, 300f, k)) continue
                kind[n] = FURNITURE; sideOf[n] = k; idx[n] = i
                n++
            }
            val life = s.life ?: continue
            for (i in 0 until life.count) {
                if (n >= MAX) break
                val p = life.passers[i]
                val x = if (k == 0) cr.mainX(p.x) else cr.crossX(p.lane)
                val z = if (k == 0) cr.mainZ(p.lane) else cr.crossZ(p.x)
                if (!billboard(n, x, z, w, 120f, 120f, k)) continue
                kind[n] = PERSON; sideOf[n] = k; idx[n] = i
                n++
            }
        }
    }

    /**
     * Places a billboard standing at world ([x], [z]) as item [q]: its foot on screen and its scale; false if out of view
     * or faded out. One on the camera's side of the corner fades as the cut passes it, as the buildings there do.
     */
    private fun billboard(q: Int, x: Float, z: Float, w: Float, reach: Float, tall: Float, street: Int): Boolean {
        val a = (if (restStreet < 0 && nearSide(x, x, z, z)) 1f - smooth((cam.cutDepth - cam.depth(x, z)) / FADE_POINT) else 1f) *
            farFade(street, x, x)
        if (a <= 0f) return false
        cam.project(ground(x, z), x, 0f, z, pt)
        if (pt[2] < NEAR) return false
        val k = cam.r / pt[2]
        val r = reach * cam.unit * k
        if (pt[0] < -r || pt[0] > w + r) return false
        ix0[q] = x; ix1[q] = x; iz0[q] = z; iz1[q] = z
        deep[q] = cam.depth(x, z)
        sx[q] = pt[0]; sy[q] = pt[1]; sk[q] = k
        fa[q] = a; bx0[q] = pt[0] - r; bx1[q] = pt[0] + r; by0[q] = pt[1] - tall * cam.unit * k; by1[q] = pt[1] + 20f * cam.unit * k
        return true
    }

    private fun nearSide(x0: Float, x1: Float, z0: Float, z1: Float): Boolean = cornerNearSide(x0, x1, z0, z1)

    private fun fadeOf(bx: Crossroads.Box): Float = cornerFade(cam, bx)

    private fun smooth(t: Float): Float = smoothUnit(t)

    /** How much is left of building [i] of street [k] this frame (1 for the street at rest, and for every one never faded). */
    fun shown(cr: Crossroads, k: Int, i: Int): Float {
        if (k == restStreet) return 1f
        for (bx in cr.boxes) if (bx.street == k && bx.index == i) return fadeOf(bx) * farFade(bx.street, bx.x0, bx.x1)
        return 1f
    }

    /**
     * How much is left of the main street's things between world x [x0] and [x1] as the camera settles into the cross
     * street's own look. That look is the stage's cabinet view, a pinhole far back with its horizon high on the screen:
     * fine for a row seen from the front, but a street running away from it climbs up the screen and hardly shrinks, so
     * the main street seen from the cross street stood in the sky like a stack of boxes. Mid-turn the camera is plain
     * perspective and the main street recedes as it should; as the cross street's look comes in ([leanCross]) everything
     * of the main street but its two corner buildings (and what stands by them) fades out.
     */
    private fun farFade(street: Int, x0: Float, x1: Float): Float {
        if (street != 0 || leanCross <= 0f) return 1f
        if (x1 >= -FAR_KEEP && x0 <= MOUTH + FAR_KEEP) return 1f
        return 1f - smooth(leanCross / FAR_BY)
    }

    /** Opens the layer item [q] fades in, if it is fading: true if one was opened (closed by [endFade]). */
    private fun DrawScope.beginFade(q: Int): Boolean {
        if (fa[q] >= 1f) return false
        fadePaint.alpha = fa[q]
        drawIntoCanvas { it.saveLayer(androidx.compose.ui.geometry.Rect(bx0[q], by0[q], bx1[q], by1[q]), fadePaint) }
        return true
    }

    private fun DrawScope.endFade() { drawIntoCanvas { it.restore() } }

    /** The order to draw in: what can hide something comes after it (each pair by a line that parts them), else far to near. */
    private fun sort() {
        for (i in 0 until n) { indeg[i] = 0; placed[i] = false }
        java.util.Arrays.fill(after, 0, n * MAX, false)
        for (i in 0 until n) for (j in i + 1 until n) {
            val c = order(i, j)
            if (c < 0) { after[i * MAX + j] = true; indeg[j]++ }
            else if (c > 0) { after[j * MAX + i] = true; indeg[i]++ }
        }
        for (i in 0 until n) byDepth[i] = i
        // far first (insertion sort: a few dozen things)
        for (i in 1 until n) {
            val v = byDepth[i]; var j = i - 1
            while (j >= 0 && deep[byDepth[j]] < deep[v]) { byDepth[j + 1] = byDepth[j]; j-- }
            byDepth[j + 1] = v
        }
        for (o in 0 until n) {
            var pick = -1
            for (c in 0 until n) { val q = byDepth[c]; if (!placed[q] && indeg[q] == 0) { pick = q; break } }
            // (a cycle cannot happen between parted footprints; if it ever did, the farthest goes first)
            if (pick < 0) for (c in 0 until n) { val q = byDepth[c]; if (!placed[q]) { pick = q; break } }
            placed[pick] = true
            drawn[o] = pick
            for (j in 0 until n) if (after[pick * MAX + j]) indeg[j]--
        }
    }

    /** -1 if [i] must be drawn before [j], 1 if after, 0 if either will do. */
    private fun order(i: Int, j: Int): Int {
        if (kind[i] != BOX && kind[j] != BOX) return if (deep[i] >= deep[j]) -1 else 1
        val cx = cam.camX
        val cz = cam.camZ
        if (ix1[i] <= ix0[j] + EPS) { if (cx <= ix1[i]) return 1; if (cx >= ix0[j]) return -1 }
        else if (ix1[j] <= ix0[i] + EPS) { if (cx <= ix1[j]) return -1; if (cx >= ix0[i]) return 1 }
        if (iz1[i] <= iz0[j] + EPS) { if (cz <= iz1[i]) return 1; if (cz >= iz0[j]) return -1 }
        else if (iz1[j] <= iz0[i] + EPS) { if (cz <= iz1[j]) return -1; if (cz >= iz0[i]) return 1 }
        return 0
    }

    private fun DrawScope.item(
        q: Int, cr: Crossroads, sides: List<CrossSide>, painter: BuildingPainter, sky: SkyColors, seconds: Float,
        live: Boolean, accent: Color, detailed: Boolean, lod: PeopleLod, motionOf: (Int, Int) -> BuildingMotion,
    ) {
        val s = sides[sideOf[q]]
        light(s, painter)
        val faded = beginFade(q)
        when (kind[q]) {
            BOX -> box(cr, s, idx[q], painter, sky, seconds, live, accent, detailed, lod, motionOf)
            FURNITURE -> {
                val f = s.street.furniture[idx[q]]
                // a lamp's arm reaches out along its street: across the picture as much as its street runs across it
                val (ax, az) = rowA(s.k)
                painter.lampReach = kotlin.math.abs(ax * cam.rx + az * cam.rz)
                stand(q, f.x, 0f) {
                    with(painter) {
                        if (f.kind == FurnitureKind.LAMP) { one.clear(); one += f; lampAir(one, sky, f.x - 1f, f.x + 1f, seconds) }
                        furniture(f, sky, seconds)
                    }
                }
                painter.lampReach = 1f
            }
            else -> {
                val life = s.life ?: return
                val p = life.passers[idx[q]]
                stand(q, p.x, p.lane) {
                    life.only = idx[q]
                    with(life) { people(sky, s.street.furniture, -1e9f, 1e9f, lod) }
                    life.only = -1
                }
            }
        }
        if (faded) endFade()
    }

    /** Draws [block] (in its street's own units, the thing standing at ([x], [y]) of them) upright where item [q] stands. */
    private inline fun DrawScope.stand(q: Int, x: Float, y: Float, block: DrawScope.() -> Unit) {
        val k = sk[q] * cam.unit
        withTransform({
            translate(sx[q], sy[q])
            scale(k, k, Offset.Zero)
            translate(-x, -y)
        }) { block() }
    }

    // ------------------------------------------------------------------ a building on its planes

    /** The row frame of street [k]: along its fronts (as one faces them) and out of them, on the ground. */
    private fun rowA(k: Int): Pair<Float, Float> = when (k) { 0 -> 1f to 0f; 1 -> 0f to 1f; else -> 0f to -1f }
    private fun rowN(k: Int): Pair<Float, Float> = when (k) { 0 -> 0f to -1f; 1 -> 1f to 0f; else -> -1f to 0f }

    /** The front's middle at the pavement (world X, Z) of building [i] of street [s]. */
    private fun frontX(cr: Crossroads, s: CrossSide, i: Int): Float = when (s.k) { 0 -> cr.mainX(s.street.centers[i]); 1 -> 0f; else -> MOUTH }
    private fun frontZ(cr: Crossroads, s: CrossSide, i: Int): Float = when (s.k) { 0 -> 0f; else -> cr.crossZ(s.street.centers[i]) }

    /** The camera leaned for box [bx] of street [s]: its own row's cabinet look, and the cross street's too for the corner. */
    private fun boxMatrix(cr: Crossroads, s: CrossSide, bx: Crossroads.Box, out: FloatArray) {
        val u = cam.unit
        val k = u / DEPTH
        when (s.k) {
            0 -> {
                val sh = shift(s, s.street.centers[bx.index])
                // the west corner is also the cross street's first lot: its east wall that street's front
                val corner = bx.x1 <= .01f
                val sc = if (corner) cornerShift(cr) else 0f
                cam.leaned(out, leanMain, sh * k, -B.DY * k, if (corner) leanCross else 0f, -sc * k, B.DY * k)
            }
            1 -> {
                val sh = shift(s, s.street.centers[bx.index])
                cam.leaned(out, 0f, 0f, 0f, leanCross, -sh * k, B.DY * k)
            }
            else -> cam.m.copyInto(out)
        }
    }

    /** The cabinet's side-face shift of a building at [x] along street [s], from that street's own view. */
    private fun shift(s: CrossSide, x: Float): Float =
        com.lifemax.rpg.feature.habits.domain.Perspective.shift(
            com.lifemax.rpg.feature.habits.domain.Perspective.sideAt(x, s.viewX, cam.unitSpan, perspectiveK))

    /**
     * The street still all but in its own look (0 or 1, -1 for neither): its buildings' outlines round the sun are
     * measured as the stage measures them, so the sun's glare does not change as the camera starts or ends its turn.
     */
    val nearRest: Int get() = if (leanMain > NEAR_REST) 0 else if (leanCross > NEAR_REST) 1 else -1

    /** That street's origin on screen: its resting origin, slid by as much as the camera has turned from its own view. */
    fun nearOrigin(viewX: Float): Float =
        kotlin.math.round(cam.cx - viewX * cam.unit) + cam.f * (cam.yaw - (if (nearRest == 0) 0f else (Math.PI / 2).toFloat()))

    /** The cross street's own view's middle (its x), for the west corner seen as its first lot; the stage's side-face rate. */
    var crossView = 0f
    var perspectiveK = com.lifemax.rpg.feature.habits.domain.Perspective.K_FOCUS
    private fun cornerShift(cr: Crossroads): Float =
        com.lifemax.rpg.feature.habits.domain.Perspective.shift(
            com.lifemax.rpg.feature.habits.domain.Perspective.sideAt(DEPTH / 2f - cr.zOff, crossView, cam.unitSpan, perspectiveK))

    private fun DrawScope.box(
        cr: Crossroads, s: CrossSide, i: Int, painter: BuildingPainter, sky: SkyColors, seconds: Float, live: Boolean,
        accent: Color, detailed: Boolean, lod: PeopleLod, motionOf: (Int, Int) -> BuildingMotion,
    ) {
        val art = s.arts[i]
        val sb = SpriteArt.buildings[art.stack.id] ?: return
        val bx = cr.boxes.firstOrNull { it.street == s.k && it.index == i } ?: return
        boxMatrix(cr, s, bx, bm)
        val (ax, az) = rowA(s.k)
        val (nx, nz) = rowN(s.k)
        val ox = frontX(cr, s, i)
        val oz = frontZ(cr, s, i)
        val kk = B.W / SpriteArt.SRC_W
        val hf = sb.front.height * kk
        val hw = B.W / 2f
        val x = s.street.centers[i]
        // a street's own row keeps its crisp pixels while it still has most of its own look; otherwise each face is
        // sampled crisp where it is a screen pixel or more to a picture pixel, smoothed where it is seen small or edge on
        val lean = when (s.k) { 0 -> leanMain; 1 -> leanCross; else -> 0f }
        crisp = lean > .5f
        if (lean > NEAR_REST) {
            // all but at rest: drawn as the stage draws it (its side face and roof as the cabinet's strips), through its
            // front's transform, so the street's own drawing and the turn's meet without a step between them
            cam.plane(bm, ox, 0f, oz, ax, 0f, az, 0f, -1f, 0f, hm)
            withTransform({ transform(hm) }) { with(painter) { building(art, motionOf(s.k, i), null, accent, live, detailed, sky, lod, x) } }
            return
        }
        quality = painter
        painter.prepare(art, x, sky, seconds)
        with(painter) {
            // the roof (seen only from above), then a rooftop billboard on it
            sb.roof?.let { roof ->
                face(ox - ax * hw - nx * DEPTH, hf, oz - az * hw - nz * DEPTH, ax * B.W / roof.width, 0f, az * B.W / roof.width,
                    nx * DEPTH / roof.height, 0f, nz * DEPTH / roof.height, roof.width.toFloat(), roof.height.toFloat(), false) { roofFace(sb, sky) }
            }
            sb.prop?.let { bill ->
                if (sb.sign == null) {
                    val bw = 160f * kk
                    val bh = bill.height * kk
                    face(ox - ax * bw / 2f - nx * DEPTH / 2f, hf + bh, oz - az * bw / 2f - nz * DEPTH / 2f,
                        ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), false) { roofBoard(sb) }
                }
            }
            val side = sb.side
            val sw = side.width.toFloat()
            val sh = side.height.toFloat()
            // the back wall, then either side (the left one's picture runs back from its front edge, mirrored as seen)
            face(ox + ax * hw - nx * DEPTH, hf, oz + az * hw - nz * DEPTH, -ax * B.W / sw, 0f, -az * B.W / sw,
                0f, -hf / sh, 0f, sw, sh, false) { sideFace(sb, right = false, back = true) }
            face(ox - ax * hw, hf, oz - az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, true) { sideFace(sb, right = false) }
            face(ox + ax * hw, hf, oz + az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, false) { sideFace(sb, right = true) }
            // the front, with all it carries (glass, signs, relief, balcony)
            val top = s.layouts[i].height
            face(ox - ax * hw, top, oz - az * hw, ax, 0f, az, 0f, -1f, 0f, B.W, top, false, frontOrigin = true) {
                painter.planeFaces = true
                translate(hw, top) { building(art, motionOf(s.k, i), null, accent, live, detailed, sky, lod, x) }
                painter.planeFaces = false
            }
        }
        painter.faceQuality = FilterQuality.None
    }

    /**
     * One face of a box: the plane through world point ([ox], [oy], [oz]) whose picture runs [tw] along ([ux], [uy],
     * [uz]) and [th] along ([vx], [vy], [vz]), drawn with [block] (in the picture's own units) if it faces the camera
     * ([mirrored]: its picture is seen from behind when it does).
     */
    private inline fun DrawScope.face(
        ox: Float, oy: Float, oz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        tw: Float, th: Float, mirrored: Boolean, frontOrigin: Boolean = false, block: DrawScope.() -> Unit,
    ) {
        // the four corners on screen: in front of the camera, and turned to it
        var area = 0f
        var px = 0f; var py = 0f; var fx = 0f; var fy = 0f
        for (c in 0..3) {
            val a = if (c == 1 || c == 2) tw else 0f
            val b = if (c >= 2) th else 0f
            cam.project(bm, ox + ux * a + vx * b, oy + uy * a + vy * b, oz + uz * a + vz * b, pt)
            if (pt[2] < NEAR) return
            if (c == 0) { fx = pt[0]; fy = pt[1] } else area += px * pt[1] - pt[0] * py
            px = pt[0]; py = pt[1]
        }
        area += px * fy - fx * py
        if (if (mirrored) area > -.5f else area < .5f) return
        // pixels of screen to a pixel of the picture along its top edge
        cam.project(bm, ox, oy, oz, pt)
        val ax0 = pt[0]; val ay0 = pt[1]
        cam.project(bm, ox + ux * tw, oy + uy * tw, oz + uz * tw, pt)
        val scale = kotlin.math.hypot(pt[0] - ax0, pt[1] - ay0) / tw
        quality?.faceQuality = if (crisp || scale >= .99f) FilterQuality.None else FilterQuality.Medium
        cam.plane(bm, ox, oy, oz, ux, uy, uz, vx, vy, vz, hm)
        withTransform({ transform(hm) }) { block() }
    }

    /** While a box is drawn: whether its faces keep their crisp pixels regardless, and the painter whose sampling is set. */
    private var crisp = false
    private var quality: BuildingPainter? = null

    companion object {
        const val MAX = 256
        const val BOX = 0
        const val FURNITURE = 1
        const val PERSON = 2
        const val EPS = .01f
        /** Nothing nearer than this in front of the camera is drawn (street units). */
        const val NEAR = 30f
        /** Nor farther: the far streets end here, a few pixels under the horizon. */
        const val FAR = 14000f
        const val FAR_ALONG = 9000f
        const val TILE = 220f
        /**
         * A building on the camera's side of the corner is gone once the cut is this share of its depth (as the camera
         * looks) into it; a lamp or a person once the cut is [FADE_POINT] street units past it.
         */
        const val FADE_SHARE = .55f
        /** The main street's corner buildings stay in the cross street's view: within this of the corner (street units). */
        const val FAR_KEEP = 1f
        /** ... and the rest of it is gone once the cross street's look is this far in. */
        const val FAR_BY = .6f
        const val FADE_POINT = 40f
        const val EAST_FROM = 12f
        const val EAST_TO = 28f
        /** A row this close to its own look is drawn as the stage draws it (see [box]). */
        const val NEAR_REST = .99f
        /** The main street's road ends this far out from its fronts (world units), and its far pavement here. */
        const val MAIN_ROAD = 152f
        const val MAIN_WIDTH = 190f
    }
}

/** The view's width in street units at the target's depth, for the stage's side-face rule. */
internal val StageCamera.unitSpan: Float get() = 2f * cx / unit

/**
 * True for what stands on the camera's side of the corner, the block it swings behind mid-turn: east of the mouth's west
 * kerb and south of the cross street's first fronts (the main street's buildings past the mouth and what stands before
 * them), or the cross street's far row. Nothing else is ever between the camera and the corner.
 */
internal fun cornerNearSide(x0: Float, x1: Float, z0: Float, z1: Float): Boolean =
    x0 >= MOUTH - PAVE - KERB && (z1 <= DEPTH + 1f || x0 >= MOUTH - CornerWorld.EPS)

/**
 * How much of box [bx] is left as [cam]'s cut sweeps into it: 1 while the cut is short of it, fading to 0 as the cut
 * passes [CornerWorld.FADE_SHARE] of its depth through it (the whole building at once, never cut open). Only the block
 * on the camera's side of the corner is ever faded. The cross street's far row, which its own view never shows, goes
 * early in the turn ([CornerWorld.EAST_FROM] to [CornerWorld.EAST_TO] degrees), while the main street's corner
 * building stands in front of it.
 */
fun cornerFade(cam: StageCamera, bx: Crossroads.Box): Float {
    if (cam.cutBack <= 0f || !cornerNearSide(bx.x0, bx.x1, bx.z0, bx.z1)) return 1f
    val early = if (bx.street == 2) {
        val deg = Math.toDegrees(kotlin.math.abs(cam.yaw).toDouble()).toFloat()
        1f - smoothUnit((deg - CornerWorld.EAST_FROM) / (CornerWorld.EAST_TO - CornerWorld.EAST_FROM))
    } else 1f
    if (early <= 0f) return 0f
    var dn = Float.MAX_VALUE
    var df = -Float.MAX_VALUE
    for (c in 0..3) {
        val d = cam.depth(if (c and 1 == 0) bx.x0 else bx.x1, if (c and 2 == 0) bx.z0 else bx.z1)
        dn = min(dn, d); df = max(df, d)
    }
    if (cam.cutDepth <= dn) return early
    if (cam.cutDepth >= df) return 0f
    return early * (1f - smoothUnit((cam.cutDepth - dn) / ((df - dn) * CornerWorld.FADE_SHARE)))
}

private fun smoothUnit(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }
