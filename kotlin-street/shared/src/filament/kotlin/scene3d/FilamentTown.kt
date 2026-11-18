package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.LinearToneMapper
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.domain.Placement
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPlace
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import com.lifemax.rpg.feature.habits.presentation.scene.SceneInk
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalkView
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A pinhole camera for the town: standing at ([ex], [ey], [ez]) looking along the unit ([fx], [fy], [fz]) (never straight
 * up or down), a lens of [fl] pixels with its centre at ([cx], [cy]) on a [w] x [h] screen. The world is the town's: x
 * east, y up, z south (right-handed), so the screen's right is the look crossed with up.
 */
class TownPose(
    val ex: Float, val ey: Float, val ez: Float,
    val fx: Float, val fy: Float, val fz: Float,
    val fl: Float, val cx: Float, val cy: Float, val w: Float, val h: Float,
    /** The nearest the shadows need to start (street units) and how far they reach. */
    val near: Float, val far: Float,
    /** Haze: where it starts (from the eye) and how thick. */
    val hazeFrom: Float, val haze: Float,
    /**
     * The street view's cut: a building whose middle is nearer the eye than this (along the look on the ground) stands
     * between the camera and the street in view (the row across the road) and fades out over [CUT_FADE]; 0: no cut.
     */
    val cut: Float = 0f,
) {
    /** Right (unit), up (unit). */
    val rx: Float; val ry: Float; val rz: Float
    val ux: Float; val uy: Float; val uz: Float

    init {
        // right = look x up(0,1,0) = (-fz, 0, fx), normalised
        val l = hypot(fx, fz).coerceAtLeast(1e-6f)
        rx = -fz / l; ry = 0f; rz = fx / l
        // up = right x look
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx
    }

    /** The pinhole's 3x4 screen matrix (world to (x w, y w, w), screen y down; row-major), as [LookMatrix.pinhole] takes it. */
    fun screen(out: FloatArray = FloatArray(12)): FloatArray {
        val r = floatArrayOf(rx, ry, rz); val u = floatArrayOf(ux, uy, uz); val f = floatArrayOf(fx, fy, fz); val e = floatArrayOf(ex, ey, ez)
        for (i in 0..2) { out[i] = fl * r[i] + cx * f[i]; out[4 + i] = -fl * u[i] + cy * f[i]; out[8 + i] = f[i] }
        out[3] = -(out[0] * e[0] + out[1] * e[1] + out[2] * e[2])
        out[7] = -(out[4] * e[0] + out[5] * e[1] + out[6] * e[2])
        out[11] = -(f[0] * e[0] + f[1] * e[1] + f[2] * e[2])
        return out
    }

    /**
     * True if a sphere at ([x], [y], [z]) of radius [r] may be on screen (the four side planes of the view; nothing in
     * front of the eye is cut, nothing far either: the haze takes the far).
     */
    fun sees(x: Float, y: Float, z: Float, r: Float): Boolean {
        val dx = x - ex; val dy = y - ey; val dz = z - ez
        val xc = rx * dx + ry * dy + rz * dz
        val yc = ux * dx + uy * dy + uz * dz
        val zc = fx * dx + fy * dy + fz * dz
        if (zc < -r) return false
        // screen x = cx + fl xc / zc >= 0, <= w; screen y = cy - fl yc / zc >= 0, <= h
        if ((fl * xc + cx * zc) / hypot(fl, cx) < -r) return false
        if (((w - cx) * zc - fl * xc) / hypot(fl, w - cx) < -r) return false
        if ((cy * zc - fl * yc) / hypot(fl, cy) < -r) return false
        if (((h - cy) * zc + fl * yc) / hypot(fl, h - cy) < -r) return false
        return true
    }

    /** The look's direction on the ground (unit x, z). */
    val groundX: Float get() = fx / hypot(fx, fz).coerceAtLeast(1e-6f)
    val groundZ: Float get() = fz / hypot(fx, fz).coerceAtLeast(1e-6f)
}

/**
 * The town's cameras: the street view following the walk ([street], the true camera of [CameraRig]: level, at a
 * person's or a first floor's height, standing back across the road from the point the walk looks at), and a free
 * aerial camera for previews ([aerial]).
 */
object TownFrame {
    /** Street units shown per screen height at the building line (the crossroads' framing: 2.45 px a unit at 1080). */
    const val SPAN = 440f
    /** Where the building line's foot falls on the screen (a share of its height from the top). */
    const val GROUND = .82f

    /**
     * The street view for [walk] on a [w] x [h] screen: [CameraRig.eye] up, [CameraRig.back] behind the point the walk's
     * camera looks at (backing off to the stage camera's distance through a turn), level, the lens shifted so the building
     * line's foot sits at [GROUND] of the screen's height.
     */
    fun street(walk: TownWalkView, w: Float, h: Float, eye: Float = CameraRig.eye, back: Float = CameraRig.back): TownPose {
        val c = walk.camera
        val t = walk.transit
        val turning = if (t != null && t.kind == TransitionKind.TURN_IN) {
            val e = t.ease.coerceIn(0f, 1f)
            val u = (min(e, 1f - e) * 90f / CameraRig.EASE).coerceIn(0f, 1f)
            u * u * (3f - 2f * u)
        } else 0f
        val b = back + (max(back, com.lifemax.rpg.feature.habits.presentation.scene.TurnTuning.R) - back) * turning
        val unit = h / SPAN
        val fl = unit * b
        val groundY = h * GROUND
        val cy = groundY - eye * unit
        val l = hypot(c.fx, c.fz).coerceAtLeast(1e-6f)
        val fx = c.fx / l; val fz = c.fz / l
        val below = (h - cy).coerceAtLeast(1f)
        val near = (fl * eye / below * .7f).coerceAtLeast(CameraRig.NEAR)
        return TownPose(c.tx - fx * b, eye, c.tz - fz * b, fx, 0f, fz, fl, w / 2f, cy, w, h, near, near + 3200f, b + 700f, HAZE, cut = b - CUT_BACK)
    }

    /**
     * An aerial camera over ([tx], [tz]): from [yaw] degrees (0: looking north, clockwise), [pitch] degrees down, [dist]
     * street units from that point, [fovY] degrees tall.
     */
    fun aerial(tx: Float, tz: Float, yaw: Float, pitch: Float, dist: Float, fovY: Float, w: Float, h: Float): TownPose {
        val ya = Math.toRadians(yaw.toDouble()); val pa = Math.toRadians(pitch.toDouble())
        val gx = sin(ya).toFloat(); val gz = -cos(ya).toFloat()
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        val fx = gx * cp; val fy = -sp; val fz = gz * cp
        val fl = (h / 2f) / tan(Math.toRadians(fovY / 2.0)).toFloat()
        val ex = tx - fx * dist; val ey = -fy * dist; val ez = tz - fz * dist
        return TownPose(ex, ey, ez, fx, fy, fz, fl, w / 2f, h / 2f, w, h, max(20f, dist * .2f), dist * 2.2f, dist * 1.1f, .00025f * 2000f / max(dist, 2000f))
    }

    /** The haze's thickness at street level (per street unit). */
    const val HAZE = .0006f
    /** The street view cuts away what stands nearer than this short of the building line it looks at (past the road's middle). */
    const val CUT_BACK = 110f
    /** Over how many street units a cut building fades. */
    const val CUT_FADE = 70f
}

/** What the town drew last frame: of [buildings] built, how many in view; renderables and their draws (primitives), shadow casters. */
data class TownStats(
    val buildings: Int, val built: Int, val visible: Int,
    val renderables: Int, val draws: Int, val triangles: Int, val casters: Int,
)

/**
 * The whole town drawn by Filament (TOWN.md T8): every building of every row built once in its own frame
 * ([BuildingMeshes]) and placed by one transform ([TownPlace]: the same rigid box on its front's middle that the walk and
 * the yards use), the ground, river and bridges, greenery and furniture ([TownGround]), the street props by
 * [com.lifemax.rpg.feature.habits.domain.PropRules] along every row ([PropLayer]) and the yards ([yardBuilder]).
 *
 * Built in two steps so a phone never stalls: [buildGround] (the ground and everything static but the buildings, at once),
 * then [buildNearest] each frame with a time budget, nearest the player first. Each frame [frame] aims the true camera
 * ([TownPose]: [TownFrame.street] from the walk, or an aerial one), lights the hour, culls the buildings to the view (a
 * sphere against its four sides) and brings the materials to it. [stats] says what was drawn.
 *
 * Only `com.google.android.filament` is used, so the same code runs on the phone and the desktop.
 */
class FilamentTown(
    val engine: Engine,
    materialBytes: (String) -> ByteArray,
    val town: Town,
    val yards: TownYards,
    private val art: TownArt,
    /** The building drawn as a sprite (bN), or null if its art is missing (it is then left out). */
    private val building: (String) -> SpriteBuilding?,
    shadowMap: Int = 4096,
) {
    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val lit: Material = load("street_lit", materialBytes)
    private val proxyMat: Material = load("shadow_proxy", materialBytes)
    private val textures = StreetTextures(engine, srgb = true)
    private val meshes = BuildingMeshes(engine, lit, proxyMat, textures)
    private val sun: Int = EntityManager.get().create()
    private var ibl: IndirectLight? = null
    private val iblColor = FloatArray(3) { -1f }
    private val iblRot = FloatArray(9)
    private val sh = FloatArray(12)
    private val fog = View.FogOptions()
    private val grading: ColorGrading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)

    /** Where the yards come from: the yard kit's builders, or flat placeholders until it lands. */
    var yardBuilder: YardBuilder = PlaceholderYards

    private val ground = PropLayer(engine, scene, lit, proxyMat)
    private val props = PropLayer(engine, scene, lit, proxyMat)
    private val yardLayer = PropLayer(engine, scene, lit, proxyMat)
    private var groundBuilt = false

    /** One building of the town: where it stands and, once built, its renderables. */
    inner class Slot(val row: Row, val b: Placement, val place: TownPlace) {
        internal var built: BuiltBuilding? = null
        /** A sphere round it in the world (its footprint's middle, a guess at its height until built). */
        var cx = 0f; var cy = 40f; var cz = 0f; var r = 0f
        var missing = false
        var shown = false

        init {
            val c = place.world(0f, b.setback + b.depth / 2f)
            cx = c.x; cz = c.z
            r = hypot(b.width / 2f, b.depth / 2f + b.setback / 2f) + 40f
        }
    }

    val slots: List<Slot> = town.rows.flatMap { row -> row.buildings.map { Slot(row, it, TownPlace.of(row, it)) } }

    /** How long the ground took (ms), the buildings so far (ms), and how many are left to build. */
    var groundMs = 0f; private set
    var buildingsMs = 0f; private set
    val pending: Int get() = slots.count { it.built == null && !it.missing }
    var stats = TownStats(slots.size, 0, 0, 0, 0, 0, 0); private set
    /** Each batch [buildNearest] made: how many it built and how long it took (ms). */
    val batches = ArrayList<Pair<Int, Float>>()

    private val xf = FloatArray(16)
    private val look = FloatArray(16)
    private val m12 = FloatArray(12)
    private val tmp = FloatArray(16)

    init {
        view.scene = scene
        view.camera = camera
        view.dithering = View.Dithering.NONE
        view.antiAliasing = View.AntiAliasing.NONE
        view.sampleCount = 1
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = true
        view.shadowType = View.ShadowType.PCF
        camera.setExposure(1f)
        val shadow = LightManager.ShadowOptions().apply {
            mapSize = shadowMap
            shadowCascades = 4
            cascadeSplitPositions = floatArrayOf(.08f, .2f, .45f)
            stable = true
            lispsm = false
            constantBias = .05f
            normalBias = .4f
            polygonOffsetConstant = .5f
            polygonOffsetSlope = .2f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(1f).direction(0f, -1f, 0f).castShadows(true).shadowOptions(shadow)
            .build(engine, sun)
        scene.addEntity(sun)
    }

    private fun load(name: String, bytes: (String) -> ByteArray): Material {
        val b = bytes(name)
        val buf = ByteBuffer.allocateDirect(b.size).put(b).flip()
        return Material.Builder().payload(buf, b.size).build(engine)
    }

    // ------------------------------------------------------------------ building

    /** Builds the ground, river, bridges, greenery, furniture, the street props and the yards (once). */
    fun buildGround() {
        if (groundBuilt) return
        val t0 = System.nanoTime()
        val tg = TownGround(town, art)
        for (p in tg.pieces()) ground.addMesh(p.mesh, p.at, p.casts, p.tiled)
        props.add(tg.props())
        val sink = object : YardSink {
            override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean) { yardLayer.addMesh(mesh, at, casts, tiled) }
            override fun props(list: List<WorldProp>) { yardLayer.add(list) }
        }
        yardBuilder.build(town, yards, sink)
        groundBuilt = true
        groundMs = (System.nanoTime() - t0) / 1e6f
    }

    /**
     * Builds buildings not yet built, nearest ([x], [z]) first, until [budgetMs] is spent (at least one); returns how many.
     * Call each frame until [pending] is 0.
     */
    fun buildNearest(x: Float, z: Float, budgetMs: Float): Int {
        val t0 = System.nanoTime()
        val todo = slots.filter { it.built == null && !it.missing }.sortedBy { hypot(it.cx - x, it.cz - z) }
        var n = 0
        for (s in todo) {
            if (n > 0 && (System.nanoTime() - t0) / 1e6f >= budgetMs) break
            buildSlot(s)
            n++
        }
        val ms = (System.nanoTime() - t0) / 1e6f
        buildingsMs += ms
        if (n > 0) batches += n to ms
        return n
    }

    /** The slowest single building so far: its sprite and how long it took (ms, its art's decoding included). */
    var slowest: Pair<String, Float> = "" to 0f; private set

    private fun buildSlot(s: Slot) {
        val t0 = System.nanoTime()
        try { buildOne(s) } finally {
            val ms = (System.nanoTime() - t0) / 1e6f
            if (ms > slowest.second) slowest = s.b.sprite to ms
        }
    }

    private fun buildOne(s: Slot) {
        val sb = building(s.b.sprite)
        if (sb == null) { s.missing = true; return }
        // the yard kit draws a lot's ground and fences: the house's and the model's own are left off there (not the
        // landmarks', whose grounds the yards do not cover yet)
        val yarded = s.b.kind != PlacementKind.LANDMARK && yards.of(s.b.sprite) != null
        val built = meshes.build(s.b.sprite, sb, s.b.setback, yarded, s.b.sprite.hashCode())
        if (built == null) { s.missing = true; return }
        val tm = engine.transformManager
        s.place.matrix(xf)
        for (e in listOf(built.entity, built.proxy)) {
            if (e == 0) continue
            tm.create(e)
            tm.setTransform(tm.getInstance(e), xf)
            scene.addEntity(e)
        }
        s.built = built
        // its sphere from its real bounds
        val bb = built.bounds
        val lx = (bb[0] + bb[3]) / 2f; val lz = (bb[2] + bb[5]) / 2f
        val c = s.place.world(lx, lz)
        s.cx = c.x; s.cz = c.z; s.cy = (bb[1] + bb[4]) / 2f
        s.r = sqrt((bb[3] - bb[0]) * (bb[3] - bb[0]) + (bb[4] - bb[1]) * (bb[4] - bb[1]) + (bb[5] - bb[2]) * (bb[5] - bb[2])) / 2f
    }

    // ------------------------------------------------------------------ each frame

    /** Brings the scene to camera [pose] at [hour]. */
    fun frame(pose: TownPose, hour: Float) {
        view.viewport = Viewport(0, 0, pose.w.toInt(), pose.h.toInt())
        LookMatrix.pinhole(pose.screen(m12), pose.w, pose.h, CameraRig.NEAR, look)
        aim(pose)
        val sky = skyColors(hour)
        light(pose, hour, sky)
        haze(pose, sky)
        val dark = sky.dark
        val wl = SceneInk.WindowLit
        val g0 = lin(wl.red); val g1 = lin(wl.green) * .9f; val g2 = lin(wl.blue) * .7f
        val ga = .75f * smooth((dark - .35f) / .5f)
        val tone = sky.sky.lastOrNull() ?: Color.White
        val m = max(tone.red, max(tone.green, tone.blue)).coerceAtLeast(.05f)
        val gl0 = mix(1f, tone.red / m * 1.15f, .7f); val gl1 = mix(1f, tone.green / m * 1.15f, .7f); val gl2 = mix(1f, tone.blue / m * 1.15f, .7f)
        val rm = engine.renderableManager
        var visible = 0; var draws = 0; var tris = 0; var renderables = 0; var casters = 0
        for (s in slots) {
            val b = s.built ?: continue
            // the street view: what stands between the camera and the street in view fades out
            val fade = if (pose.cut > 0f) smooth((pose.groundX * (s.cx - pose.ex) + pose.groundZ * (s.cz - pose.ez) - pose.cut + TownFrame.CUT_FADE) / TownFrame.CUT_FADE) else 1f
            val on = fade > .02f && pose.sees(s.cx, s.cy, s.cz, s.r)
            s.shown = on
            rm.getInstance(b.entity).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (on) 1 else 0) }
            // (its shadow may fall into view from beside it: the twin stays while it is near the view)
            val near = on || pose.sees(s.cx, s.cy, s.cz, s.r + 300f)
            if (b.proxy != 0) rm.getInstance(b.proxy).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (near) 1 else 0) }
            if (near && b.proxy != 0) casters++
            if (!on) continue
            visible++; renderables++; draws += b.primitives; tris += b.triangles
            for (mi in b.mis) {
                mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
                mi.setParameter("cell", 2f)
                mi.setParameter("fade", fade)
                mi.setParameter("glow", g0, g1, g2, ga)
                mi.setParameter("glass", gl0, gl1, gl2, 1f)
            }
            b.night?.invoke(hour)
        }
        for (layer in listOf(ground, props, yardLayer)) {
            layer.update(look, dark)
            renderables += layer.draws; draws += layer.draws
            casters += layer.casters
            tris += layer.triangles
        }
        stats = TownStats(slots.size, slots.count { it.built != null }, visible, renderables, draws, tris, casters)
    }

    private fun aim(p: TownPose) {
        val k = (p.near / p.fl).toDouble()
        camera.setProjection(Camera.Projection.PERSPECTIVE, -p.cx * k, (p.w - p.cx) * k, -(p.h - p.cy) * k, p.cy * k, p.near.toDouble(), p.far.toDouble())
        camera.lookAt(p.ex.toDouble(), p.ey.toDouble(), p.ez.toDouble(), (p.ex + p.fx).toDouble(), (p.ey + p.fy).toDouble(), (p.ez + p.fz).toDouble(),
            p.ux.toDouble(), p.uy.toDouble(), p.uz.toDouble())
    }

    /**
     * The sun (or moon) as the street lights its view (the classic's key: across the view, down, out of the fronts
     * towards the viewer, turned with the camera), and the sky light from above and the camera's side.
     */
    private fun light(p: TownPose, hour: Float, sky: com.lifemax.rpg.feature.habits.presentation.scene.SkyColors) {
        val key = SceneLight.key(hour)
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        val gx = p.groundX; val gz = p.groundZ
        // the view's right on the ground (-gz, gx); out of the fronts towards the viewer is -look
        val dx = key.across * -gz - key.out * gx
        val dy = -key.down
        val dz = key.across * gx - key.out * gz
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        lm.setDirection(li, dx / dl, dy / dl, dz / dl)
        val warm = (1f - key.down).coerceIn(0f, 1f)
        val sc = if (key.cool > .5f) Color(0xFF9DB4FF) else lerp(Color(0xFFFFF6E8), Color(0xFFFFB070), warm * warm)
        lm.setColor(li, lin(sc.red), lin(sc.green), lin(sc.blue))
        lm.setIntensity(li, SUN * key.strength * Math.PI.toFloat())
        lm.setShadowCaster(li, key.cool < .5f && key.strength > .05f)
        val tone = sky.sky.lastOrNull() ?: Color.White
        val night = sqrt(sky.dark.coerceIn(0f, 1f))
        var level = lerp(Color(AMB, AMB, AMB), Color(.16f, .19f, .30f), night)
        val low = smooth((.75f - key.down) / .35f)
        val back = (low * (1f - key.cool) * (key.out * 2.5f).coerceIn(0f, 1f) * (key.strength * 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        level = lerp(level, Color(level.red * .42f, level.green * .36f, level.blue * .44f), back)
        val dusk = 1f - abs(sky.dark - .5f) * 2f
        val t = lerp(level, Color(tone.red * level.red * 1.1f, tone.green * level.green * 1.1f, tone.blue * level.blue * 1.1f), .25f + .3f * dusk)
        val r = t.red; val g = t.green; val b = t.blue
        if (ibl == null || abs(r - iblColor[0]) + abs(g - iblColor[1]) + abs(b - iblColor[2]) > 2e-3f) {
            val toward = .18f; val up = -.06f
            sh[0] = r; sh[1] = g; sh[2] = b
            sh[3] = r * up; sh[4] = g * up; sh[5] = b * up
            sh[6] = -toward * r; sh[7] = -toward * g; sh[8] = -toward * b
            sh[9] = 0f; sh[10] = 0f; sh[11] = 0f
            ibl?.let { engine.destroyIndirectLight(it) }
            ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine).also { scene.indirectLight = it }
            iblColor[0] = r; iblColor[1] = g; iblColor[2] = b
        }
        iblRot[0] = gz; iblRot[1] = 0f; iblRot[2] = -gx
        iblRot[3] = 0f; iblRot[4] = 1f; iblRot[5] = 0f
        iblRot[6] = gx; iblRot[7] = 0f; iblRot[8] = gz
        ibl?.setRotation(iblRot)
    }

    private fun haze(p: TownPose, sky: com.lifemax.rpg.feature.habits.presentation.scene.SkyColors) {
        val tone = sky.sky.lastOrNull() ?: return
        fog.enabled = true
        fog.distance = p.hazeFrom
        fog.density = p.haze * (1f - .4f * sky.dark)
        fog.height = 0f
        fog.heightFalloff = 1e-4f
        fog.maximumOpacity = .85f
        fog.inScatteringStart = 0f
        fog.inScatteringSize = -1f
        fog.fogColorFromIbl = false
        fog.color = floatArrayOf(lin(tone.red), lin(tone.green), lin(tone.blue))
        view.fogOptions = fog
    }

    private fun lin(c: Float): Float = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    // ------------------------------------------------------------------ teardown

    fun destroy() {
        val em = EntityManager.get()
        for (s in slots) {
            val b = s.built ?: continue
            for (e in listOf(b.entity, b.proxy)) if (e != 0) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
            for (mi in b.mis) engine.destroyMaterialInstance(mi)
            for (mi in b.proxyMis) engine.destroyMaterialInstance(mi)
            engine.destroyVertexBuffer(b.vb); engine.destroyIndexBuffer(b.ib)
            s.built = null
        }
        ground.destroy(); props.destroy(); yardLayer.destroy()
        textures.destroy(all = true)
        scene.remove(sun); engine.destroyEntity(sun); em.destroy(sun)
        ibl?.let { engine.destroyIndirectLight(it) }; ibl = null
        engine.destroyMaterial(lit); engine.destroyMaterial(proxyMat)
        engine.destroyColorGrading(grading)
        val ce = camera.entity
        engine.destroyCameraComponent(ce); em.destroy(ce)
        engine.destroyView(view)
        engine.destroyScene(scene)
    }

    companion object {
        /** The sun's and the sky light's strength by day (the street's). */
        private const val SUN = .45f
        private const val AMB = .8f
    }
}
