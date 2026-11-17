package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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
 * left). Nearer than [StageCamera.cutBack] in front of the target nothing is drawn: the block between the camera and the
 * corner is cut away mid-turn, its cut faces capped.
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
    private val clip = Path()
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
        if (cam.cutBack > 0f && !cam.kept(bx.x0, bx.z0) && !cam.kept(bx.x1, bx.z0) && !cam.kept(bx.x0, bx.z1) && !cam.kept(bx.x1, bx.z1)) return false
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
    fun edges(cr: Crossroads, sides: List<CrossSide>, edge: com.lifemax.rpg.feature.habits.domain.SunEdge, w: Float, own: Boolean) {
        if (!own) edge.clear()
        for (bx in cr.boxes) {
            if (own && bx.street == restStreet) continue
            val s = sides.getOrNull(bx.street) ?: continue
            val l = s.layouts.getOrNull(bx.index) ?: continue
            if (cam.cutBack > 0f && !cam.kept(bx.x0, bx.z0) && !cam.kept(bx.x1, bx.z0) && !cam.kept(bx.x0, bx.z1) && !cam.kept(bx.x1, bx.z1)) continue
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
            shadows(light, sky, s.layouts, s.street, a, b, min(reach, depth + 18.5f), seconds)
            val life = s.life
            if (life != null) with(life) { lampShadows(sky.dark, s.street.furniture, a, b) }
        }
    }

    /** The far side of a street's band, past what the stage's own band shows: the road on, the far kerb and pavement, then the verge. */
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
        // past the far pavement: on the cross street the east row's lots (paving between them), on the main street a verge
        val verge = if (k == 0) lerp(lerp(sky.pavement, Color(0xFF4F6B3C), .55f), Color.Black, .12f) else lerp(sky.pavement, Color.Black, .1f)
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
            if (cam.cutBack > 0f && bx.street != restStreet) {
                // wholly in front of the cut: not drawn
                if (!cam.kept(bx.x0, bx.z0) && !cam.kept(bx.x1, bx.z0) && !cam.kept(bx.x0, bx.z1) && !cam.kept(bx.x1, bx.z1)) continue
            }
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
                if (!billboard(n, x, z, w, 160f)) continue
                kind[n] = FURNITURE; sideOf[n] = k; idx[n] = i
                n++
            }
            val life = s.life ?: continue
            for (i in 0 until life.count) {
                if (n >= MAX) break
                val p = life.passers[i]
                val x = if (k == 0) cr.mainX(p.x) else cr.crossX(p.lane)
                val z = if (k == 0) cr.mainZ(p.lane) else cr.crossZ(p.x)
                if (!billboard(n, x, z, w, 120f)) continue
                kind[n] = PERSON; sideOf[n] = k; idx[n] = i
                n++
            }
        }
    }

    /** Places a billboard standing at world ([x], [z]) as item [q]: its foot on screen and its scale; false if out of view or cut. */
    private fun billboard(q: Int, x: Float, z: Float, w: Float, reach: Float): Boolean {
        if (!cam.kept(x, z) && restStreet < 0) return false
        cam.project(ground(x, z), x, 0f, z, pt)
        if (pt[2] < NEAR) return false
        val k = cam.r / pt[2]
        if (pt[0] < -reach * cam.unit * k || pt[0] > w + reach * cam.unit * k) return false
        ix0[q] = x; ix1[q] = x; iz0[q] = z; iz1[q] = z
        deep[q] = cam.depth(x, z)
        sx[q] = pt[0]; sy[q] = pt[1]; sk[q] = k
        return true
    }

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
        when (kind[q]) {
            BOX -> box(cr, s, idx[q], painter, sky, seconds, live, accent, detailed, lod, motionOf)
            FURNITURE -> {
                val f = s.street.furniture[idx[q]]
                stand(q, f.x, 0f) {
                    with(painter) {
                        if (f.kind == FurnitureKind.LAMP) { one.clear(); one += f; lampAir(one, sky, f.x - 1f, f.x + 1f, seconds) }
                        furniture(f, sky, seconds)
                    }
                }
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
        val cut = cam.cutBack > 0f && !(cam.kept(bx.x0, bx.z0) && cam.kept(bx.x1, bx.z0) && cam.kept(bx.x0, bx.z1) && cam.kept(bx.x1, bx.z1))
        // a street's own row keeps its crisp pixels while it still has most of its own look; otherwise each face is
        // sampled crisp where it is a screen pixel or more to a picture pixel, smoothed where it is seen small or edge on
        val lean = when (s.k) { 0 -> leanMain; 1 -> leanCross; else -> 0f }
        crisp = lean > .5f
        if (lean > NEAR_REST && !cut) {
            // all but at rest: drawn as the stage draws it (its side face and roof as the cabinet's strips), through its
            // front's transform, so the street's own drawing and the turn's meet without a step between them
            cam.plane(bm, ox, 0f, oz, ax, 0f, az, 0f, -1f, 0f, hm)
            withTransform({ transform(hm) }) { with(painter) { building(art, motionOf(s.k, i), null, accent, live, detailed, sky, lod, x) } }
            return
        }
        quality = painter
        painter.prepare(art, x, sky, seconds)
        if (cut) inside(bx, sb, ox, oz, hf, sky, painter)
        with(painter) {
            // the roof (seen only from above), then a rooftop billboard on it
            sb.roof?.let { roof ->
                face(bx, cut, ox - ax * hw - nx * DEPTH, hf, oz - az * hw - nz * DEPTH, ax * B.W / roof.width, 0f, az * B.W / roof.width,
                    nx * DEPTH / roof.height, 0f, nz * DEPTH / roof.height, roof.width.toFloat(), roof.height.toFloat(), false) { roofFace(sb, sky) }
            }
            sb.prop?.let { bill ->
                if (sb.sign == null) {
                    val bw = 160f * kk
                    val bh = bill.height * kk
                    face(bx, false, ox - ax * bw / 2f - nx * DEPTH / 2f, hf + bh, oz - az * bw / 2f - nz * DEPTH / 2f,
                        ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), false) { roofBoard(sb) }
                }
            }
            val side = sb.side
            val sw = side.width.toFloat()
            val sh = side.height.toFloat()
            // the back wall, then either side (the left one's picture runs back from its front edge, mirrored as seen)
            face(bx, cut, ox + ax * hw - nx * DEPTH, hf, oz + az * hw - nz * DEPTH, -ax * B.W / sw, 0f, -az * B.W / sw,
                0f, -hf / sh, 0f, sw, sh, false) { sideFace(sb, right = false, back = true) }
            face(bx, cut, ox - ax * hw, hf, oz - az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, true) { sideFace(sb, right = false) }
            face(bx, cut, ox + ax * hw, hf, oz + az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, false) { sideFace(sb, right = true) }
            // the front, with all it carries (glass, signs, relief, balcony)
            val top = s.layouts[i].height
            face(bx, cut, ox - ax * hw, top, oz - az * hw, ax, 0f, az, 0f, -1f, 0f, B.W, top, false, frontOrigin = true) {
                painter.planeFaces = true
                translate(hw, top) { building(art, motionOf(s.k, i), null, accent, live, detailed, sky, lod, x) }
                painter.planeFaces = false
            }
        }
        painter.faceQuality = FilterQuality.None
    }

    /**
     * A building cut open by the camera's cut: what is left of it seen from inside, as a doll's house is (its far walls
     * from within, dim, their windows lit by the day, and a floor at every storey), drawn before its outside faces.
     */
    private fun DrawScope.inside(bx: Crossroads.Box, sb: SpriteBuilding, ox: Float, oz: Float, hf: Float, sky: SkyColors, painter: BuildingPainter) {
        val side = sb.side
        val sw = side.width.toFloat()
        val sh = side.height.toFloat()
        val room = lerp(Color(0xFF2A2630), sky.sky[2], .12f)
        // the four walls from within: each wall's inner face, the side picture mirrored as one sees a wall's inside
        val xs = floatArrayOf(bx.x0, bx.x1, bx.x1, bx.x0)
        val zs = floatArrayOf(bx.z0, bx.z0, bx.z1, bx.z1)
        for (c in 0..3) {
            val d = (c + 1) % 4
            val ex = xs[d] - xs[c]
            val ez = zs[d] - zs[c]
            // walking round the footprint anticlockwise seen from above, the inner face's picture runs along the edge
            face(bx, true, xs[c], hf, zs[c], ex / sw, 0f, ez / sw, 0f, -hf / sh, 0f, sw, sh, true) {
                with(painter) { sideFace(sb, right = true) }
                drawRect(room, Offset.Zero, Size(sw, sh), alpha = .62f)
            }
        }
        // the floors, the ones farthest above or below the eye first
        val storey = SpriteArt.MID_H * B.W / SpriteArt.SRC_W
        val body = hf - storey * sb.middles
        val levels = ArrayList<Float>()
        levels += 0f
        for (j in 0 until sb.middles) levels += body + j * storey
        levels += hf - 2f
        levels.sortByDescending { kotlin.math.abs(it - cam.eye) }
        val slab = lerp(Color(0xFF8B857C), sky.sky[2], .1f)
        val under = lerp(slab, Color.Black, .45f)
        for (y in levels) {
            if (!cutPolygon(bx.x0, y, bx.z0, bx.x1 - bx.x0, 0f, 0f, 0f, 0f, bx.z1 - bx.z0, 1f, 1f)) continue
            drawPath(clip, if (y < cam.eye) slab else under)
            drawPath(clip, lerp(slab, Color.Black, .6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        }
    }

    /**
     * One face of a box: the plane through world point ([ox], [oy], [oz]) whose picture runs [tw] along ([ux], [uy],
     * [uz]) and [th] along ([vx], [vy], [vz]), drawn with [block] (in the picture's own units) if it faces the camera
     * ([mirrored]: its picture is seen from behind when it does). Cut at the camera's cut when [cut].
     */
    private inline fun DrawScope.face(
        bx: Crossroads.Box, cut: Boolean,
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
        if (!cut) withTransform({ transform(hm) }) { block() }
        else {
            // the face's part beyond the cut, as a polygon on screen
            if (!cutPolygon(ox, oy, oz, ux, uy, uz, vx, vy, vz, tw, th)) return
            clipPath(clip) { withTransform({ transform(hm) }) { block() } }
        }
    }

    /** While a box is drawn: whether its faces keep their crisp pixels regardless, and the painter whose sampling is set. */
    private var crisp = false
    private var quality: BuildingPainter? = null

    private val poly = FloatArray(30)
    private val poly2 = FloatArray(30)

    /** [clip] = the quad's part beyond the camera's cut, on screen; false if none of it is. */
    private fun cutPolygon(ox: Float, oy: Float, oz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float, tw: Float, th: Float): Boolean {
        // the corners in the world, then clipped against the plane (depth >= r - cut)
        var m = 0
        for (c in 0..3) {
            val a = if (c == 1 || c == 2) tw else 0f
            val b = if (c >= 2) th else 0f
            poly[m++] = ox + ux * a + vx * b; poly[m++] = oy + uy * a + vy * b; poly[m++] = oz + uz * a + vz * b
        }
        val cnt = clipPlane(poly, 4, poly2)
        if (cnt < 3) return false
        clip.rewind()
        for (c in 0 until cnt) {
            cam.project(bm, poly2[c * 3], poly2[c * 3 + 1], poly2[c * 3 + 2], pt)
            if (c == 0) clip.moveTo(pt[0], pt[1]) else clip.lineTo(pt[0], pt[1])
        }
        clip.close()
        return true
    }

    /** Sutherland-Hodgman against the cut: [inp] [count] points (x, y, z) to [out]; returns how many are left. */
    private fun clipPlane(inp: FloatArray, count: Int, out: FloatArray): Int {
        val lim = cam.cutDepth
        var m = 0
        for (c in 0 until count) {
            val a = c * 3
            val b = ((c + 1) % count) * 3
            val da = cam.depth(inp[a], inp[a + 2]) - lim
            val db = cam.depth(inp[b], inp[b + 2]) - lim
            if (da >= 0f) { out[m++] = inp[a]; out[m++] = inp[a + 1]; out[m++] = inp[a + 2] }
            if ((da >= 0f) != (db >= 0f)) {
                val t = da / (da - db)
                out[m++] = inp[a] + (inp[b] - inp[a]) * t; out[m++] = inp[a + 1] + (inp[b + 1] - inp[a + 1]) * t; out[m++] = inp[a + 2] + (inp[b + 2] - inp[a + 2]) * t
            }
        }
        return m / 3
    }

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
        /** A row this close to its own look is drawn as the stage draws it (see [box]). */
        const val NEAR_REST = .99f
        /** The main street's road ends this far out from its fronts (world units), and its far pavement here. */
        const val MAIN_ROAD = 152f
        const val MAIN_WIDTH = 190f
    }
}

/** The view's width in street units at the target's depth, for the stage's side-face rule. */
internal val StageCamera.unitSpan: Float get() = 2f * cx / unit
