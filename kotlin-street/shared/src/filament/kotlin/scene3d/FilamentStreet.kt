package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.toArgb
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.LinearToneMapper
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.TransformManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.DEPTH
import com.lifemax.rpg.feature.habits.domain.BoxPart
import com.lifemax.rpg.feature.habits.domain.BoxPoly
import com.lifemax.rpg.feature.habits.domain.BoxShell
import com.lifemax.rpg.feature.habits.domain.HouseShell
import com.lifemax.rpg.feature.habits.domain.ShellPart
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.KERB
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.MOUTH
import com.lifemax.rpg.feature.habits.domain.Crossroads.Companion.PAVE
import com.lifemax.rpg.feature.habits.domain.Dusk
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.Passer
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWorld
import com.lifemax.rpg.feature.habits.presentation.scene.CrossSide
import com.lifemax.rpg.feature.habits.presentation.scene.Frame3D
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.SceneInk
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.StageTuning
import com.lifemax.rpg.feature.habits.presentation.scene.cornerFade
import com.lifemax.rpg.feature.habits.presentation.scene.cornerNearSide
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The street drawn by Filament: the crossroads the stage builds (both streets, their buildings, lamps, trees, benches and
 * people) as real geometry wearing the same sprites, a real depth buffer, a sun with shadow maps and a sky light. Every
 * visible thing goes to the screen through the classic street's own matrix for it (CornerWorld's, via [LookMatrix]), so
 * at rest the street stands exactly where the classic draws it and mid-turn it swings exactly as the classic does; light
 * and shadow come from where each thing truly is. The sky stays the stage's (drawn under this, see [Frame3D]).
 *
 * Make one per engine; [update] it with each frame the stage hands over, then render [view]. [albedo] draws the sprites'
 * own colours unlit (the exact comparison with the classic's placement). Only `com.google.android.filament` is used, so
 * the same code runs on the phone (the AAR) and on the desktop (the headless build).
 */
class FilamentStreet(
    val engine: Engine,
    materialBytes: (String) -> ByteArray,
    val albedo: Boolean = false,
    /** The sun's shadow map's size (texels a side). */
    shadowMap: Int = 4096,
) {
    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    private val visibleMat: Material = load(if (albedo) "street_albedo" else "street_lit", materialBytes)
    private val proxyMat: Material = load("shadow_proxy", materialBytes)
    private val textures = StreetTextures(engine, srgb = !albedo)
    private val nearest = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    // the pictures: crisp where a picture pixel is a screen pixel or more, blended from their mipmaps where a face is
    // seen small or edge on (nearest there turns siding and stripes into moire)
    private val crisp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE).also { it.anisotropy = 16f }
    // the ground's tiles, repeated along their street
    private val tiled = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT).also {
        it.anisotropy = 8f
    }
    private val sun: Int = EntityManager.get().create()
    private var ibl: IndirectLight? = null
    /** The sky light's colour it was last built with (it is rebuilt only when that changes; it turns with the camera). */
    private val iblColor = FloatArray(3) { -1f }
    private val sh = FloatArray(12)
    private val iblRot = FloatArray(9)
    private val fog = View.FogOptions()
    private val colorGrading: ColorGrading? = if (albedo) null else ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)

    private val things = ArrayList<Thing>()
    /** How many person cards each street has (more are made when its street life has more people than that). */
    private val personCards = IntArray(2)
    private val cards = ArrayList<Card>()
    private val vbs = ArrayList<VertexBuffer>()
    private val ibs = ArrayList<IndexBuffer>()
    private var builtFor: List<CrossSide>? = null
    /** The street props (hydrants, mailboxes, signs...: [PropLayer]), placed by the rules along both streets. */
    private var props: PropLayer? = null
    private lateinit var quad: Pair<VertexBuffer, IndexBuffer>

    /** How long the last [update] took to build the street (ms; 0 when nothing was built), for the frame line. */
    var buildMs = 0f; private set

    init {
        view.scene = scene
        view.camera = camera
        view.dithering = View.Dithering.NONE
        view.antiAliasing = View.AntiAliasing.NONE
        view.sampleCount = 1
        view.blendMode = View.BlendMode.TRANSLUCENT
        if (albedo) view.isPostProcessingEnabled = false else view.colorGrading = colorGrading
        view.isShadowingEnabled = !albedo
        view.shadowType = View.ShadowType.PCF
        camera.setExposure(1f)
        val shadow = LightManager.ShadowOptions().apply {
            mapSize = shadowMap
            shadowCascades = 3
            cascadeSplitPositions = floatArrayOf(.15f, .35f, .65f)
            stable = true
            lispsm = false
            // (walls stand nearly edge on to a high sun: a slope-scaled offset pushes them out of their own shadow)
            constantBias = .05f
            normalBias = .4f
            polygonOffsetConstant = .5f
            polygonOffsetSlope = .2f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(1f).direction(0f, -1f, 0f)
            .castShadows(!albedo).shadowOptions(shadow)
            .build(engine, sun)
        scene.addEntity(sun)
    }

    private fun load(name: String, bytes: (String) -> ByteArray): Material {
        val b = bytes(name)
        val buf = TownMemory.direct(b.size).put(b).flip()
        return Material.Builder().payload(buf, b.size).build(engine)
    }

    // ------------------------------------------------------------------ per frame

    private val m12 = FloatArray(12)
    private val look = FloatArray(16)
    /** The true camera's clip-space matrix this frame, the same for every thing. */
    private val trueLook = FloatArray(16)
    private val xf = FloatArray(16)
    private val glow = FloatArray(4)
    private val glass = FloatArray(3)
    private val groundTint = FloatArray(3)

    /** Brings the scene to the stage's frame [f], building the street the first time (or when the stage rebuilt its own). */
    fun update(f: Frame3D) {
        val world = f.world ?: return
        val cr = f.cr ?: return
        val sides = f.sides ?: return
        if (builtFor !== sides) {
            val t0 = System.nanoTime()
            clear()
            build(cr, sides, world)
            builtFor = sides
            buildMs = (System.nanoTime() - t0) / 1e6f
        } else buildMs = 0f
        view.viewport = Viewport(0, 0, f.width.toInt(), f.height.toInt())
        val cam = world.cam
        val classic = CameraRig.classic
        // the one true camera (the classic's leaned looks only behind -Dlook=classic)
        val eyeCam = if (classic) cam else { CameraRig.aim(cam, world.oy, f.width); CameraRig.view }
        if (classic) world.grounds() else LookMatrix.pinhole(eyeCam.m, f.width, f.height, CameraRig.NEAR, trueLook)
        aimCamera(eyeCam, f.width, f.height, classic)
        light(f, cam)
        haze(f, eyeCam)
        val rest = world.restStreet
        val rm = engine.renderableManager
        val wl = SceneInk.WindowLit
        glow[0] = lin(wl.red); glow[1] = lin(wl.green) * .9f; glow[2] = lin(wl.blue) * .7f
        glow[3] = .75f * smooth((f.dark - .35f) / .5f)
        // the glass mirrors the sky low down (a sunset at dusk, the night's blue); the ground takes its hue as the classic's does
        f.sky?.let { sk ->
            val tone = sk.sky.lastOrNull() ?: Color.White
            val m = max(tone.red, max(tone.green, tone.blue)).coerceAtLeast(.05f)
            glass[0] = lerp(1f, tone.red / m * 1.15f, .7f); glass[1] = lerp(1f, tone.green / m * 1.15f, .7f); glass[2] = lerp(1f, tone.blue / m * 1.15f, .7f)
            val day = dayPave
            val r = lin(sk.pavement.red) / lin(day.red).coerceAtLeast(1e-3f)
            val g = lin(sk.pavement.green) / lin(day.green).coerceAtLeast(1e-3f)
            val b = lin(sk.pavement.blue) / lin(day.blue).coerceAtLeast(1e-3f)
            val mm = max(r, max(g, b)).coerceAtLeast(1e-3f)
            groundTint[0] = lerp(1f, r / mm, .6f); groundTint[1] = lerp(1f, g / mm, .6f); groundTint[2] = lerp(1f, b / mm, .6f)
        }
        for (t in things) {
            var fade = 1f
            if (t.kind == BOX) {
                val bx = t.box!!
                fade = if (bx.street == rest) 1f else cornerFade(cam, bx)
            }
            val shown = fade > .02f
            show(rm, t.entity, shown)
            if (t.proxy != 0) show(rm, t.proxy, shown && fade > .5f)
            if (!shown) continue
            if (classic) {
                if (t.kind == BOX) world.boxMatrix(cr, t.side!!, t.box!!, m12) else world.ground(t.lookX, t.lookZ).copyInto(m12)
                var (s, o) = band(t.kind, t.street, rest, t.level)
                // (pulled forward: n / d grows by n / d^2 a unit nearer)
                if (t.pull > 0f) { val d0 = max(cam.depth(t.refX, t.refZ), LookMatrix.N); o += s * LookMatrix.N * t.pull / (d0 * d0) }
                LookMatrix.of(m12, cam, f.width, f.height, t.refX, t.refY, t.refZ, s, o, look)
            } else trueLook.copyInto(look)
            for (mi in t.mis) {
                mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
                mi.setParameter("fade", fade)
                mi.setParameter("cell", f.unit * SpriteArt.PIXEL)
            }
            if (t.kind == GROUND) for (mi in t.mis) mi.setParameter("tint", t.tr * groundTint[0], t.tg * groundTint[1], t.tb * groundTint[2], 1f)
            if (t.kind == BOX) {
                // the windows' lamps: warm, as strong as it is dark
                for (mi in t.mis) { mi.setParameter("glow", glow[0], glow[1], glow[2], glow[3]); mi.setParameter("glass", glass[0], glass[1], glass[2], 1f) }
                t.windows?.update(f.hour)
                t.hourly?.invoke(f.hour)
            }
        }
        props?.update(trueLook, f.dark, shown = !classic)
        placeCards(f, world, cr, sides, eyeCam, classic)
    }

    private fun show(rm: RenderableManager, e: Int, on: Boolean) {
        val i = rm.getInstance(e)
        if (i != 0) rm.setLayerMask(i, 0xff, if (on) 1 else 0)
    }

    /**
     * Which band of the depth buffer a thing is drawn in (scale, offset; reversed, 1 nearest): as the classic draws, the
     * ground under everything that stands, and at rest the street in view in front of the rest of the crossroads (it is,
     * in the world; the bands only keep two different looks from disagreeing where they meet).
     */
    private fun band(kind: Int, street: Int, rest: Int, level: Int): Pair<Float, Float> {
        val inView = rest >= 0 && (street == rest || (rest == 1 && street == 2))
        if (kind == GROUND) {
            val base = if (rest < 0) .05f else if (inView) .1f else 0f
            return .02f to base + .015f * level
        }
        return if (rest < 0) .78f to .2f else if (inView) .4f to .6f else .4f to .2f
    }

    private fun aimCamera(cam: com.lifemax.rpg.feature.habits.presentation.scene.StageCamera, w: Float, h: Float, classic: Boolean) {
        // the camera everything is seen from (the true camera; with the classic's looks, the plain pinhole they lean
        // from): for view-dependent light and to fit the sun's shadow map to. Its near plane starts a little before the
        // nearest ground in view, so the shadow map's cascades spend their texels on the street, not on the air before it
        val near = if (classic) {
            val front = min(cam.depth(cam.camX, 0f), cam.depth(0f, cam.camZ)).let { if (it < 0f) cam.depth(cam.camX, 0f) else it }
            (front - 320f).coerceAtLeast(10f).toDouble()
        } else {
            // the ground at the screen's bottom edge
            val below = (h - cam.cy).coerceAtLeast(1f)
            (cam.f * cam.eye / below * .7f).coerceAtLeast(CameraRig.NEAR).toDouble()
        }
        val k = near / cam.f
        camera.setProjection(Camera.Projection.PERSPECTIVE, -cam.cx * k, (w - cam.cx) * k, -(h - cam.cy) * k, cam.cy * k, near, near + 3000.0)
        camera.lookAt(cam.camX.toDouble(), cam.eye.toDouble(), cam.camZ.toDouble(),
            (cam.camX + cam.dx).toDouble(), cam.eye.toDouble(), (cam.camZ + cam.dz).toDouble(), 0.0, 1.0, 0.0)
    }

    /**
     * The sun (or moon) where the classic's key light comes from (its travel across the main street's view, down and out
     * towards the viewer), and a sky light: from above and, a little more, from the camera's side, so fronts read as the
     * classic draws them while the sun's shadows fall on the ground and the walls facing it are lit.
     */
    private fun light(f: Frame3D, cam: com.lifemax.rpg.feature.habits.presentation.scene.StageCamera) {
        val key = f.light ?: return
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        // the classic's sun stands at a point along the street (where its orb is drawn): shadows fan out from under it,
        // leaning by how far the view's middle is from it, as the classic's SceneLight.lean does
        val sd = f.sides?.getOrNull(f.world?.nearRest?.coerceAtLeast(0) ?: 0)
        val span = (f.width / f.unit).coerceAtLeast(1f)
        val off = if (sd != null) ((sd.viewX - sd.lightX) / span).coerceIn(-1.5f, 1.5f) else 0f
        val across = key.across + com.lifemax.rpg.feature.habits.domain.SceneLight.SPLAY * off * key.down
        // the key is the view's (across it, down, out of it towards the camera), as the classic lights every street it
        // looks down: turned with the camera, so the shadows fall towards the viewer round the corner too
        val dx = across * cam.dz - key.out * cam.dx
        val dy = -key.down
        val dz = -across * cam.dx - key.out * cam.dz
        val dl = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        lm.setDirection(li, dx / dl, dy / dl, dz / dl)
        val warm = (1f - key.down).coerceIn(0f, 1f)
        val sc = if (key.cool > .5f) Color(0xFF9DB4FF) else lerp(Color(0xFFFFF6E8), Color(0xFFFFB070), warm * warm)
        lm.setColor(li, lin(sc.red), lin(sc.green), lin(sc.blue))
        // (Filament's diffuse is albedo / pi times illuminance)
        lm.setIntensity(li, SUN * key.strength * Math.PI.toFloat())
        // the moon throws no hard shadows (the classic's are a faint shade; a shadow map's are all or nothing)
        if (!albedo) lm.setShadowCaster(li, key.cool < .5f && key.strength > .05f)
        val sky = f.sky ?: return
        val tone = sky.sky.lastOrNull() ?: Color.White
        // (the classic's dusk is already deep at half dark: the light falls off faster than the sky's darkness)
        val night = sqrt(f.dark.coerceIn(0f, 1f))
        var level = lerp(Color(AMB, AMB, AMB), Color(.16f, .19f, .30f), night)
        // facing a low sun the fronts are backlit, in their own shade: the sky light they get falls with the sun's height
        // (the classic darkens them so, towards the sun's glare)
        // (only a low sun: none at noon, all of it once the sun is under about 40 degrees)
        val low = smooth((.75f - key.down) / .35f)
        val back = (low * (1f - key.cool) * (key.out * 2.5f).coerceIn(0f, 1f) * (key.strength * 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        level = lerp(level, Color(level.red * .42f, level.green * .36f, level.blue * .44f), back)
        // tinted by the sky low down: warm at dusk, blue at night
        val dusk = 1f - abs(f.dark - .5f) * 2f
        val tinted = lerp(level, Color(tone.red * level.red * 1.1f, tone.green * level.green * 1.1f, tone.blue * level.blue * 1.1f), .25f + .3f * dusk)
        val r = tinted.red; val g = tinted.green; val b = tinted.blue
        if (ibl == null || abs(r - iblColor[0]) + abs(g - iblColor[1]) + abs(b - iblColor[2]) > 2e-3f) {
            // two bands: a constant, then the normal's y, z, x (Filament's pre-scaled form): a little less from above,
            // more from the camera's side (-z here; turned to the camera below)
            val toward = .18f
            val up = -.06f
            sh[0] = r; sh[1] = g; sh[2] = b
            sh[3] = r * up; sh[4] = g * up; sh[5] = b * up
            sh[6] = -toward * r; sh[7] = -toward * g; sh[8] = -toward * b
            sh[9] = 0f; sh[10] = 0f; sh[11] = 0f
            ibl?.let { engine.destroyIndirectLight(it) }
            ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine).also { scene.indirectLight = it }
            iblColor[0] = r; iblColor[1] = g; iblColor[2] = b
        }
        // turned with the camera: the shader's world is the user's turned by this rotation's inverse, so its third column
        // is where the light's -z (the bright side) points back at: the camera's look
        iblRot[0] = cam.dz; iblRot[1] = 0f; iblRot[2] = -cam.dx
        iblRot[3] = 0f; iblRot[4] = 1f; iblRot[5] = 0f
        iblRot[6] = cam.dx; iblRot[7] = 0f; iblRot[8] = cam.dz
        ibl?.setRotation(iblRot)
    }

    /**
     * The air between the camera and what is far: things past the street in view fade towards the sky's colour low down,
     * as the classic hazes the side street and the far blocks (the street in view itself stays clear).
     */
    private fun haze(f: Frame3D, cam: com.lifemax.rpg.feature.habits.presentation.scene.StageCamera) {
        if (albedo) return
        val sky = f.sky ?: return
        val tone = sky.sky.lastOrNull() ?: return
        // where the street in view stands from the camera (its fronts), a little past it
        val rest = f.world?.restStreet ?: 0
        val front = when (rest) { 1 -> cam.depth(0f, cam.camZ); 0 -> cam.depth(cam.camX, 0f); else -> max(cam.depth(cam.camX, 0f), cam.depth(0f, cam.camZ)) }
        // (fog counts the distance from the camera, so the street in view stays clear out to the screen's corners)
        val edge = sqrt(1f + (cam.cx / cam.f) * (cam.cx / cam.f))
        // mid-turn the camera looks across the corner: the blocks round the junction stay clear, as the classic's do
        val turned = (2f * abs(cam.dx * cam.dz)).coerceIn(0f, 1f)
        val corner = sqrt((cam.camX - MOUTH / 2f) * (cam.camX - MOUTH / 2f) + cam.camZ * cam.camZ)
        fog.enabled = true
        fog.distance = max(front * edge + 120f, corner + 800f * turned).coerceAtLeast(40f)
        fog.density = HAZE * (1f - .4f * f.dark)
        fog.height = 0f
        fog.heightFalloff = 1e-4f
        // (the true camera sees the ground out to its horizon: far off it goes into the haze, as far land does)
        fog.maximumOpacity = if (CameraRig.classic) .4f else .85f
        fog.inScatteringStart = 0f
        fog.inScatteringSize = -1f
        fog.fogColorFromIbl = false
        fog.color = floatArrayOf(lin(tone.red), lin(tone.green), lin(tone.blue))
        view.fogOptions = fog
    }

    /** A colour channel as the material takes it: linear when lit (its pictures are sRGB), as is for the albedo comparison. */
    private fun col(c: Float): Float = if (albedo) c else lin(c)

    private fun lin(c: Float): Float = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()

    // ------------------------------------------------------------------ building it

    private fun build(cr: Crossroads, sides: List<CrossSide>, world: CornerWorld) {
        quad = unitQuad()
        for (bx in cr.boxes) {
            val s = sides.getOrNull(bx.street) ?: continue
            val l = s.layouts.getOrNull(bx.index) ?: continue
            val sb = SpriteArt.buildings[s.arts[bx.index].stack.id] ?: continue
            // a villa or a landmark: its own parts (FilamentModels)
            val ma = sb.model
            if (ma != null) { modelThing(world, cr, s, bx, ma, l.height); continue }
            val faces = ArrayList<Face>()
            val boards = ArrayList<Face>()
            val win = if (sb.house != null) houseFaces(world, cr, s, bx, sb, faces) else boxFaces(world, cr, s, bx, sb, l.height, faces, boards)
            if (faces.isEmpty()) continue
            val cx = (bx.x0 + bx.x1) / 2f; val cz = (bx.z0 + bx.z1) / 2f
            addThing(BOX, bx.street, faces, cx, l.height / 3f, cz, bx, s, 0, casts = true, windows = win)
            // the rooftop billboard stands halfway back, where the classic stands it, but is drawn over its own roof as
            // the classic draws it: its depth pulled forward to its front's (no further: what stands before the
            // building still hides it)
            if (boards.isNotEmpty()) addThing(BOX, bx.street, boards, cx, l.height, cz, bx, s, 0, casts = true, windows = null).also { things.last().pull = DEPTH / 2f + 4f }
        }
        groundFaces(cr)
        props = PropLayer(engine, scene, visibleMat, if (albedo) null else proxyMat, srgb = !albedo).also { it.add(PropLayer.crossroads(cr, sides)) }
        for (k in 0..1) {
            val s = sides.getOrNull(k) ?: continue
            for (fu in s.street.furniture) {
                val img = when (fu.kind) {
                    FurnitureKind.LAMP -> SpriteArt.lamp
                    FurnitureKind.BENCH -> SpriteArt.bench
                    FurnitureKind.TREE -> SpriteArt.trees.takeIf { it.isNotEmpty() }?.let { it[((fu.seed % it.size) + it.size) % it.size] }
                } ?: continue
                val (fx, fy) = when (fu.kind) { FurnitureKind.LAMP -> 15f to 127f; FurnitureKind.BENCH -> 31f to 61f; else -> 31f to 95f }
                val x = if (k == 0) cr.mainX(fu.x) else 0f
                val z = if (k == 0) 0f else cr.crossZ(fu.x)
                // a little in front of the fronts it stands by (the classic draws them after the buildings)
                val (nx, nz) = world.rowN(k)
                cards += newCard(k, img, fx, fy, x + nx * 3f, z + nz * 3f, person = false).also { c ->
                    // a lamp's lantern glass glows at night
                    if (fu.kind == FurnitureKind.LAMP) { c.mi.setParameter("mask", lampMask(img), nearest); c.lamp = true }
                }
            }
            val life = s.life ?: continue
            repeat(life.passers.size) { cards += newCard(k, null, 16f, 63f, 0f, 0f, person = true) }
            personCards[k] = life.passers.size
        }
    }

    /** One box of the stage's (a shop or a stack of flats): its front, sign, sides, back, roof, as CornerWorld builds them. */
    private fun boxFaces(world: CornerWorld, cr: Crossroads, s: CrossSide, bx: Crossroads.Box, sb: com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding, top: Float, out: MutableList<Face>, boards: MutableList<Face>): Windows? {
        val i = bx.index
        val (ax, az) = world.rowA(s.k)
        val (nx, nz) = world.rowN(s.k)
        val ox = world.frontX(cr, s, i)
        val oz = world.frontZ(cr, s, i)
        val kk = B.W / SpriteArt.SRC_W
        val hf = sb.front.height * kk
        val hw = B.W / 2f
        // the front: its windows' glass filled in (the classic draws rooms and reflections behind the cut-out art)
        val win = Windows(sb, s.arts[i].stack.id.hashCode())
        out += face(ox - ax * hw, hf, oz - az * hw, ax * B.W / sb.front.width, 0f, az * B.W / sb.front.width, 0f, -kk, 0f,
            sb.front.width.toFloat(), sb.front.height.toFloat(), win.albedo, win.mask, nx, 0f, nz)
        // the part of the sign above the roofline continues the front
        sb.sign?.let { sg ->
            val h = sg.height * kk
            out += face(ox - ax * hw, hf + h, oz - az * hw, ax * B.W / sg.width, 0f, az * B.W / sg.width, 0f, -kk, 0f,
                sg.width.toFloat(), sg.height.toFloat(), textures.of(sg), null, nx, 0f, nz)
            // the board's thickness: its edge layers in the art's outline colour, its back in the frame's grey
            if (!CameraRig.classic) {
                val px = StreetTextures.argb(sg)
                fun fill(rgb: Int) = IntArray(px.size) { j -> if ((px[j] ushr 24) > 0) (px[j] and 0xFF000000.toInt()) or rgb else 0 }
                val edge = textures.make(fill(0x3A3A50), sg.width, sg.height, mips = true)
                val back = textures.make(fill(0x5C6070), sg.width, sg.height, mips = true)
                for (k in 1..4) {
                    val d = k * .75f * kk; val last = k == 4
                    out += face(ox - ax * hw - nx * d, hf + h, oz - az * hw - nz * d, ax * B.W / sg.width, 0f, az * B.W / sg.width, 0f, -kk, 0f,
                        sg.width.toFloat(), sg.height.toFloat(), if (last) back else edge, null, if (last) -nx else nx, 0f, if (last) -nz else nz)
                }
            }
        }
        val fx0 = ox - ax * hw; val fz0 = oz - az * hw
        fun poly(q: BoxPoly, tex: Texture): Face {
            val n = q.p.size / 3
            val p = FloatArray(n * 3)
            for (c in 0 until n) {
                val x = q.p[c * 3]; val y = q.p[c * 3 + 1]; val d = q.p[c * 3 + 2]
                p[c * 3] = fx0 + (ax * x - nx * d) * kk; p[c * 3 + 1] = y * kk; p[c * 3 + 2] = fz0 + (az * x - nz * d) * kk
            }
            val tx = p[3] - p[0]; val ty = p[4] - p[1]; val tz = p[5] - p[2]
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            return Face(p, q.uv.copyOf(n * 2), tex, null, ax * q.n[0] - nx * q.n[2], q.n[1], az * q.n[0] - nz * q.n[2], tx / tl, ty / tl, tz / tl)
        }
        fun colour(rgb: Int) = textures.make(intArrayOf(0xFF000000.toInt() or (rgb and 0xFFFFFF)), 1, 1, mips = true)
        // what the art draws flat but stands out, built (BoxShell): the stone ledges, the balconies (not in the classic's looks)
        if (!CameraRig.classic) {
            if (sb.ledges.isNotEmpty()) for (q in BoxShell.ledges(sb.ledges, sb.front.width.toFloat(), sb.front.height.toFloat())) out += poly(q, win.albedo)
            val par = sb.parts["balp"]; val slab = sb.parts["bals"]; val floor = sb.parts["balf"]
            if (par != null && slab != null && floor != null) for (b in sb.balconies) {
                val cap = colour(b[7].toInt()); val under = colour(b[8].toInt()); val bracket = colour(b[9].toInt())
                for (q in BoxShell.balcony(b, sb.front.height.toFloat())) out += poly(q, when (q.part) {
                    BoxPart.BAL_PARAPET -> textures.of(par)
                    BoxPart.BAL_SLAB -> textures.of(slab)
                    BoxPart.BAL_FLOOR -> textures.of(floor)
                    BoxPart.BAL_CAP -> cap
                    BoxPart.BAL_UNDER -> under
                    else -> bracket
                })
            }
        }
        val side = sb.side
        val sw = side.width.toFloat(); val sh = side.height.toFloat()
        // the back wall, then either side (the left one's picture runs back from its front edge, mirrored as seen)
        out += face(ox + ax * hw - nx * DEPTH, hf, oz + az * hw - nz * DEPTH, -ax * B.W / sw, 0f, -az * B.W / sw, 0f, -hf / sh, 0f, sw, sh, textures.of(side), null, -nx, 0f, -nz)
        out += face(ox - ax * hw, hf, oz - az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, textures.of(side), null, -ax, 0f, -az)
        out += face(ox + ax * hw, hf, oz + az * hw, -nx * DEPTH / sw, 0f, -nz * DEPTH / sw, 0f, -hf / sh, 0f, sw, sh, textures.of(side), null, ax, 0f, az)
        val pitch = sb.pitch
        if (pitch != null) {
            val rows = pitch.height.toFloat(); val pw = pitch.width.toFloat()
            val rise = (rows * kk - B.DY / 2f).coerceAtLeast(2f)
            val rx = ox - ax * hw - nx * DEPTH / 2f; val rz = oz - az * hw - nz * DEPTH / 2f
            val slope = sqrt((DEPTH / 2f) * (DEPTH / 2f) + rise * rise)
            val cy = DEPTH / 2f / slope; val sy = rise / slope
            // both slopes from the ridge: the near one down to the front's eave, the far one down to the back's
            out += face(rx, hf + rise, rz, ax * B.W / pw, 0f, az * B.W / pw, nx * DEPTH / 2f / rows, -rise / rows, nz * DEPTH / 2f / rows, pw, rows, textures.of(pitch), null, nx * sy, cy, nz * sy)
            out += face(rx, hf + rise, rz, ax * B.W / pw, 0f, az * B.W / pw, -nx * DEPTH / 2f / rows, -rise / rows, -nz * DEPTH / 2f / rows, pw, rows, textures.of(pitch), null, -nx * sy, cy, -nz * sy)
            // the gable ends: the side wall's top storey carried up into the triangle
            val gp = sb.gablePic
            val posts = sb.pitchInfo?.getOrNull(2) ?: 0f
            val wcap = sb.parts["wcap"]; val pierPic = sb.parts["pier"]
            if (posts > 0f && wcap != null && pierPic != null && !CameraRig.classic) {
                // the stone end walls as real walls following the slope, capped, with a pier at each end (BoxShell)
                val gable = textures.of(gp ?: sb.side)
                for (q in BoxShell.endWalls(sb.front.width.toFloat(), sb.front.height.toFloat(), DEPTH / kk, rise / kk, posts))
                    out += poly(q, when (q.part) {
                        BoxPart.GABLE -> gable
                        BoxPart.CAP_TOP, BoxPart.CAP_EDGE, BoxPart.PIER_TOP -> textures.of(wcap)
                        else -> textures.of(pierPic)
                    })
            } else {
                // (with stone end walls the pitch's art is clear over the posts: the gables stand inside them, not past them)
                val inset = if (sb.postL != null && sb.postR != null) posts * kk else 0f
                if (gp != null) for (end in 0..1) {
                    val e = if (end == 0) -(hw - inset) else hw - inset
                    val bx0 = ox + ax * e; val bz0 = oz + az * e
                    out += Face(
                        floatArrayOf(bx0, hf, bz0, bx0 - nx * DEPTH, hf, bz0 - nz * DEPTH, bx0 - nx * DEPTH / 2f, hf + rise, bz0 - nz * DEPTH / 2f),
                        floatArrayOf(0f, 1f, 1f, 1f, .5f, 0f), textures.of(gp), null,
                        if (end == 0) -ax else ax, 0f, if (end == 0) -az else az, -nx, 0f, -nz,
                    )
                }
                // the stone end walls' columns, as the street shows them on the front (the classic's looks)
                val pl = sb.postL; val pr = sb.postR; val pk = posts * kk
                if (pl != null && pr != null && pk > 0f) {
                    val ph = pl.height * kk
                    out += face(ox - ax * hw + nx * .3f, hf - 3f * kk + ph, oz - az * hw + nz * .3f, ax * pk / pl.width, 0f, az * pk / pl.width, 0f, -kk, 0f, pl.width.toFloat(), pl.height.toFloat(), textures.of(pl), null, nx, 0f, nz)
                    out += face(ox + ax * (hw - pk) + nx * .3f, hf - 3f * kk + ph, oz + az * (hw - pk) + nz * .3f, ax * pk / pr.width, 0f, az * pk / pr.width, 0f, -kk, 0f, pr.width.toFloat(), pr.height.toFloat(), textures.of(pr), null, nx, 0f, nz)
                }
                if (pk <= 0f && !CameraRig.classic) {
                    // the rake boards along both gables (BoxShell)
                    val rc = sb.pitchInfo?.getOrNull(0)?.toInt() ?: 0x6A4A40
                    val top = colour(rc); val edge = colour(BoxShell.shade(rc, .8f)); val under = colour(BoxShell.shade(rc, .55f))
                    for (q in BoxShell.verges(sb.front.width.toFloat(), sb.front.height.toFloat(), DEPTH / kk, rise / kk))
                        out += poly(q, when (q.part) { BoxPart.RAKE_TOP -> top; BoxPart.RAKE_UNDER -> under; else -> edge })
                }
            }
        } else sb.roof?.let { roof ->
            out += face(ox - ax * hw - nx * DEPTH, hf, oz - az * hw - nz * DEPTH, ax * B.W / roof.width, 0f, az * B.W / roof.width,
                nx * DEPTH / roof.height, 0f, nz * DEPTH / roof.height, roof.width.toFloat(), roof.height.toFloat(), textures.of(roof), null, 0f, 1f, 0f)
        }
        // a rooftop billboard standing halfway back
        sb.prop?.let { bill ->
            if (sb.sign == null) {
                val bw = 160f * kk
                val bh = bill.height * kk
                // standing halfway back on the roof, where the classic stands it (the same point in the world, so it goes
                // through the same look at rest and through the turn). Seen through the true camera it stands on its legs
                // on a pitched roof's ridge (from the street the ridge would hide all but its top); in the classic's
                // looks the near slope hides its legs
                val back = DEPTH / 2f
                val ridge = if (CameraRig.classic) 0f else sb.pitch?.let { (it.height * kk - B.DY / 2f).coerceAtLeast(2f) } ?: 0f
                // (the picture's empty rows under the legs go below the ridge, and the feet a little into it: the legs
                // stand on it rather than over a strip of sky)
                val (empty, backPic) = boardBack(bill)
                val foot = if (CameraRig.classic) 0f else (empty + 1) * kk
                val bx0 = ox - ax * bw / 2f - nx * back; val bz0 = oz - az * bw / 2f - nz * back
                boards += face(bx0, hf + ridge - foot + bh, bz0,
                    ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), textures.of(bill), null, nx, 0f, nz)
                // its back: the frame's plain grey (from behind, the art does not show through mirrored)
                if (!CameraRig.classic) boards += face(bx0 - nx * .4f, hf + ridge - foot + bh, bz0 - nz * .4f,
                    ax * bw / bill.width, 0f, az * bw / bill.width, 0f, -kk, 0f, bill.width.toFloat(), bill.height.toFloat(), backPic, null, -nx, 0f, -nz)
            }
        }
        return win
    }

    /** A billboard picture's empty rows under its legs, and its back: the same outline in the frame's grey. */
    private fun boardBack(bill: ImageBitmap): Pair<Int, Texture> {
        val px = StreetTextures.argb(bill)
        val w = bill.width; val h = bill.height
        var empty = 0
        while (empty < h - 1 && (0 until w).none { (px[(h - 1 - empty) * w + it] ushr 24) > 0 }) empty++
        val grey = Color(0xFF5C6070).toArgb() and 0xFFFFFF
        val back = IntArray(px.size) { i -> if ((px[i] ushr 24) > 0) (px[i] and 0xFF000000.toInt()) or grey else 0 }
        return empty to textures.make(back, w, h, mips = true)
    }

    /** A villa or a landmark ([FilamentModels]): standing back in its yard in its lot, its model's x along the row. */
    private fun modelThing(world: CornerWorld, cr: Crossroads, s: CrossSide, bx: Crossroads.Box, ma: com.lifemax.rpg.feature.habits.presentation.scene.ModelArt, height: Float) {
        val (ax, az) = world.rowA(s.k)
        val (nx, nz) = world.rowN(s.k)
        val kk = B.W / SpriteArt.SRC_W
        val sh = ma.shape
        val left = -B.W / 2f + sh.frontX * kk
        val ox = world.frontX(cr, s, bx.index) + ax * left - nx * sh.setback * kk
        val oz = world.frontZ(cr, s, bx.index) + az * left - nz * sh.setback * kk
        val m = FilamentModels.build(engine, visibleMat, if (albedo) null else proxyMat, textures, ma, ModelPlace(ox, oz, ax, az, nx, nz, kk),
            s.arts[bx.index].stack.id.hashCode(), linear = !albedo)
        vbs += m.vb; ibs += m.ib
        scene.addEntity(m.entity)
        if (m.proxy != 0) scene.addEntity(m.proxy)
        things += Thing(BOX, bx.street, m.entity, m.mis, m.proxy, m.proxyMis, (bx.x0 + bx.x1) / 2f, height / 3f, (bx.z0 + bx.z1) / 2f, bx, s, 0, null)
            .also { it.hourly = m.night }
    }

    /** One of the mobile homes: its walls, its gabled ends (the side picture carries the triangle), the roof as a slab over them ([HouseShell]), its yard. */
    private fun houseFaces(world: CornerWorld, cr: Crossroads, s: CrossSide, bx: Crossroads.Box, sb: com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding, out: MutableList<Face>): Windows? {
        val house = sb.house ?: return null
        val sh = house.shape
        val i = bx.index
        val (ax, az) = world.rowA(s.k)
        val (nx, nz) = world.rowN(s.k)
        val ox = world.frontX(cr, s, i)
        val oz = world.frontZ(cr, s, i)
        val kk = B.W / SpriteArt.SRC_W
        val hw = B.W / 2f
        val depth = min(sh.depth * kk, DEPTH)
        val back = min(sh.setback * kk, (DEPTH - depth).coerceAtLeast(0f))
        val left = -hw + sh.frontX * kk
        // the house's front wall: its left end and the way back into the lot
        val fx = ox + ax * left - nx * back; val fz = oz + az * left - nz * back
        val win = Windows(sb, s.arts[i].stack.id.hashCode())
        // the walls, the gable ends and the roof as a slab (HouseShell): the roof meets the walls from every side
        val rake = textures.make(intArrayOf(0xFF000000.toInt() or sh.rake), 1, 1, mips = true)
        val soffit = textures.make(intArrayOf(0xFF000000.toInt() or sh.rakeEdge), 1, 1, mips = true)
        val sc = depth / (sh.depth * kk)
        for (q in HouseShell.quads(sh, sb.front.width.toFloat(), sb.side.height.toFloat())) {
            val (tex, mask) = when (q.part) {
                ShellPart.FRONT -> win.albedo to win.mask
                // the back wall: the front's own siding (no windows taken from it)
                ShellPart.BACK, ShellPart.EAVE_BACK -> textures.of(sb.front) to null
                ShellPart.EAVE_FRONT -> win.albedo to null
                ShellPart.GABLE -> textures.of(sb.side) to null
                ShellPart.SLOPE -> textures.of(house.slope) to null
                ShellPart.RAKE -> rake to null
                ShellPart.SOFFIT -> soffit to null
            }
            // (depth along the lot, squeezed only if the house is deeper than the crossroads' lots)
            fun px(x: Float, d: Float) = (ax * x - nx * d * sc) * kk
            fun pz(x: Float, d: Float) = (az * x - nz * d * sc) * kk
            val p = FloatArray(12)
            for (c in 0..3) {
                val ua = if (c == 1 || c == 2) 1f else 0f; val vb = if (c >= 2) 1f else 0f
                val x = q.o[0] + q.u[0] * ua + q.v[0] * vb; val y = q.o[1] + q.u[1] * ua + q.v[1] * vb; val d = q.o[2] + q.u[2] * ua + q.v[2] * vb
                p[c * 3] = fx + px(x, d); p[c * 3 + 1] = y * kk; p[c * 3 + 2] = fz + pz(x, d)
            }
            val (u0, v0, u1, v1) = q.uv
            val tx = px(q.u[0], q.u[2]); val ty = q.u[1] * kk; val tz = pz(q.u[0], q.u[2])
            val tl = sqrt(tx * tx + ty * ty + tz * tz)
            out += Face(p, floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), tex, mask, ax * q.n[0] - nx * q.n[2], q.n[1], az * q.n[0] - nz * q.n[2], tx / tl, ty / tl, tz / tl)
        }
        // its yard: the lawn along the lot's front, and the fence or hedge at its edge
        house.lawn?.let { lawn ->
            out += face(ox - ax * hw, .15f, oz - az * hw, ax * B.W / lawn.width, 0f, az * B.W / lawn.width, -nx * back / lawn.height, 0f, -nz * back / lawn.height,
                lawn.width.toFloat(), lawn.height.toFloat(), textures.of(lawn), null, 0f, 1f, 0f)
        }
        house.fence?.let { fence ->
            val d = sh.fenceD * kk
            val top = sh.fenceFoot * kk
            out += face(ox - ax * hw - nx * d, top, oz - az * hw - nz * d, ax * B.W / fence.width, 0f, az * B.W / fence.width, 0f, -kk, 0f,
                fence.width.toFloat(), fence.height.toFloat(), textures.of(fence), null, nx, 0f, nz)
        }
        return win
    }

    /**
     * The ground: each street's band (pavement, kerb, road, far kerb, far pavement) as one picture painted as the
     * classic paints its pavement (the slabs' gradient and joints, the kerb's lit edge and its shadow on the road, the
     * lane's dashes, the asphalt's specks), repeated along the street; the mouth's road, dropped kerbs and zebra laid on
     * the main street's pavement as the classic's mouth draws them; and paving beyond.
     */
    private fun groundFaces(cr: Crossroads) {
        // the classic's own ground colours by day (the light does the rest)
        val day = com.lifemax.rpg.feature.habits.presentation.scene.skyColors(13f)
        val verge = lerp(day.pavement, Color.Black, .1f)
        // (the true camera sees the ground out towards its horizon)
        val far = if (CameraRig.classic) 7000f else 30000f
        val side = if (CameraRig.classic) 3000f else 30000f
        val farEnd = CornerWorld.MAIN_WIDTH
        val mainTex = groundTile(day, CornerWorld.MAIN_ROAD, farEnd, along = true)
        // the ground behind and beyond the blocks: paving in big slabs (a plain colour there reads as nothing at all)
        val plaza = if (CameraRig.classic) null else pavingTile(verge)
        fun openGround(street: Int, x0: Float, z0: Float, x1: Float, z1: Float) =
            if (plaza == null) flat(street, x0, z0, x1, z1, verge, 0)
            else flat(street, x0, z0, x1, z1, Color.White, 0, plaza, floatArrayOf(x0 / PLAZA, z0 / PLAZA, (x1 - x0) / PLAZA, (z1 - z0) / PLAZA))
        val crossTex = groundTile(day, MOUTH - PAVE - KERB, MOUTH, along = false)
        // the main street (z < 0), in two halves either side of x = 0 (the ground's look changes there)
        for ((a, b) in listOf(-far to 0f, 0f to far)) {
            flat(0, a, -farEnd, b, 0f, Color.White, 0, mainTex, floatArrayOf(a / TILE_L, 0f, (b - a) / TILE_L, 1f))
            openGround(0, a, -side, b, -farEnd)
        }
        // the mouth: the cross street's road carried across the main pavement between dropped kerbs, and a zebra on it
        val pv = PAVE + KERB
        val r0 = pv; val r1 = MOUTH - pv
        val mouthRoad = lerp(day.road, Color.White, .04f)
        // the cross street's own picture (its pavements, flush kerbs and road, shade for shade) carried on across the main
        // pavement to the main road, so the two meet as one level surface: no seam, line or change of tone at the
        // building line that reads as a step up into the side street
        flat(0, 0f, -pv, MOUTH, 0f, Color.White, 1, crossTex, floatArrayOf(0f, -pv / TILE_L, 1f, pv / TILE_L))
        // (white paint in the pavement's light: the hour's light does the rest)
        val zebra = over(lerp(day.pavement, Color.White, .55f), mouthRoad, .85f)
        var zx = r0 + 4f
        while (zx + 7f < r1 - 2f) { flat(0, zx, -(PAVE - 2f * Crossroads.LANE), zx + 7f, -3f * Crossroads.LANE, zebra, 2); zx += 13f }
        // the cross street (z > 0) between its rows, and paving behind the main street's blocks
        flat(1, 0f, 0f, MOUTH, far, Color.White, 0, crossTex, floatArrayOf(0f, 0f, 1f, far / TILE_L))
        openGround(1, -side, 0f, 0f, far)
        openGround(1, MOUTH, 0f, side, far)
    }

    /**
     * Paving for the open ground: [PLAZA] street units square, slabs of 24 with darker joints, each slab a shade of its
     * own and a scatter of specks, in [base]'s colour.
     */
    private fun pavingTile(base: Color): Texture {
        val n = (PLAZA * TEX).toInt()
        val px = IntArray(n * n)
        val rnd = java.util.Random(11L)
        val slabs = (PLAZA / 24f).toInt()
        val shade = FloatArray(slabs * slabs) { (rnd.nextFloat() - .5f) * .06f }
        val joint = lerp(base, Color.Black, .16f)
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + .5f) / TEX; val v = (y + .5f) / TEX
            val sx = (u / 24f).toInt().coerceIn(0, slabs - 1); val sy = (v / 24f).toInt().coerceIn(0, slabs - 1)
            val t = shade[sy * slabs + sx]
            var c = if (t >= 0f) lerp(base, Color.White, t) else lerp(base, Color.Black, -t)
            if (u % 24f < .9f || v % 24f < .9f) c = joint
            px[y * n + x] = c.toArgb()
        }
        repeat(n * n / 40) {
            val i = rnd.nextInt(px.size)
            px[i] = lerp(Color(px[i]), Color.Black, .12f + .1f * rnd.nextFloat()).toArgb()
        }
        return textures.make(px, n, n, mips = true)
    }

    /** [top] laid over [under] at [a] (both opaque results). */
    private fun over(top: Color, under: Color, a: Float): Color = lerp(under, top.copy(alpha = 1f), a * top.alpha)

    /**
     * One street's band as a picture, [TILE_L] street units along it at [TEX] texels a unit: from its fronts out, the
     * pavement, the kerb, the road to [roadEnd], the far kerb and the far pavement to [width] (as the classic's
     * pavement() and beyond() paint them, in day colours). [along]: rows run out from the fronts (the main street, its
     * picture's x along it); else columns do (the cross street, whose quads run along z).
     */
    private fun groundTile(day: com.lifemax.rpg.feature.habits.presentation.scene.SkyColors, roadEnd: Float, width: Float, along: Boolean): Texture {
        val nl = (TILE_L * TEX).toInt(); val nd = (width * TEX).toInt()
        val px = IntArray(nl * nd)
        val lane = Crossroads.LANE
        val pv = PAVE
        val ink = com.lifemax.rpg.feature.habits.presentation.scene.SceneInk
        val roadNear = lerp(day.road, Color.White, .04f); val roadFar = lerp(day.road, Color.Black, .22f)
        val farPave = lerp(day.pavement, Color.Black, .06f)
        val dashAt = (com.lifemax.rpg.feature.habits.presentation.scene.BuildingPainter.PAVEMENT + 30f) * lane
        fun at(l: Int, d: Int, c: Color) { val i = if (along) d * nl + l else l * nd + d; px[i] = c.toArgb() }
        fun get(l: Int, d: Int): Color { val i = if (along) d * nl + l else l * nd + d; return Color(px[i]) }
        for (d in 0 until nd) {
            val y = (d + .5f) / TEX
            val base = when {
                y < pv -> lerp(lerp(day.pavement, Color.White, .07f), lerp(day.pavement, Color.Black, .12f), y / pv)
                y < pv + KERB -> if (y < pv + .9f * lane) over(ink.Highlight, day.kerb, .8f) else day.kerb
                y < roadEnd -> {
                    var c = lerp(roadNear, roadFar, ((y - pv - KERB) / (roadEnd - pv - KERB)).coerceIn(0f, 1f))
                    if (y < pv + KERB + 2.2f * lane) c = over(ink.Occlusion, c, .6f)
                    if (y >= dashAt && y < dashAt + 2f * lane) c = Color(-1) // the dashes, filled in below
                    c
                }
                y < roadEnd + KERB -> if (y > roadEnd + KERB - .9f * lane) over(ink.Highlight, day.kerb, .6f) else day.kerb
                else -> farPave
            }
            for (l in 0 until nl) at(l, d, base)
            // the wall's foot: a line of shade along it
            if (y < 1.2f * lane) for (l in 0 until nl) at(l, d, over(ink.Occlusion, get(l, d), 1f))
        }
        // the lane's dashes: 22 long every 46
        for (l in 0 until nl) {
            val x = (l + .5f) / TEX
            val dash = (x % 46f) < 22f
            for (d in 0 until nd) {
                val y = (d + .5f) / TEX
                if (y >= dashAt && y < dashAt + 2f * lane && y < roadEnd) {
                    val c0 = lerp(roadNear, roadFar, ((y - pv - KERB) / (roadEnd - pv - KERB)).coerceIn(0f, 1f))
                    at(l, d, if (dash) over(day.kerb, c0, .6f) else c0)
                }
            }
        }
        // the paving's joints: a slab every 24 along, slanting 5 back across the pavement (both pavements)
        for (side in 0..1) {
            val y0 = if (side == 0) 0f else roadEnd + KERB
            val y1 = if (side == 0) pv else width
            var j = 0f
            while (j < TILE_L) {
                for (d in (y0 * TEX).toInt() until (y1 * TEX).toInt().coerceAtMost(nd)) {
                    val y = (d + .5f) / TEX
                    val x = j - 5f * (y - y0) / pv
                    val l = (((x * TEX).toInt() % nl) + nl) % nl
                    at(l, d, over(day.line, get(l, d), 1f))
                }
                j += 24f
            }
        }
        // the asphalt's and the slabs' specks (a fixed scatter of the tile's own)
        val rnd = java.util.Random(7L)
        fun speck(n: Int, y0: Float, y1: Float, c: Color, a: Float) = repeat(n) {
            val l = rnd.nextInt(nl); val d = ((y0 + rnd.nextFloat() * (y1 - y0)) * TEX).toInt().coerceIn(0, nd - 1)
            at(l, d, over(c, get(l, d), a))
        }
        speck((TILE_L / 120f * 46f * 2f).toInt(), pv + KERB + 2f, roadEnd - 1f, ink.Occlusion, .55f)
        speck((TILE_L / 120f * 18f * 2f).toInt(), pv + KERB + 2f, roadEnd - 1f, ink.Highlight, .35f)
        speck((TILE_L / 120f * 26f).toInt(), 2f, pv - 1f, ink.Occlusion, .3f)
        return textures.make(px, if (along) nl else nd, if (along) nd else nl, mips = true)
    }

    /**
     * One flat piece of ground ([street] 0 the main street's band, 1 the cross street's): a plain colour, or picture
     * [tex] where [uv] (offset, size) says. [level]: laid over the pieces of lower levels (0 the bands, 1 the mouth's
     * road and kerbs, 2 its markings).
     */
    private fun flat(street: Int, x0: Float, z0: Float, x1: Float, z1: Float, c: Color, level: Int, tex: Texture? = null, uv: FloatArray? = null) {
        // (laid a hair over the lower levels: one level plane seen through one camera, the depth buffer keeps them apart)
        val y = LEVEL_LIFT * level
        val f = Face(floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            tex ?: textures.white, null, 0f, 1f, 0f, 1f, 0f, 0f, tint = c, uvRect = uv)
        // (its look is the one the classic uses where it lies: one corner well inside it)
        val rx = if (x1 <= 0f) min(x1 - 1f, -1f) else max(x0 + 1f, 1f)
        val rz = if (z1 <= 0f) min(z1 - 1f, -1f) else max(z0 + 1f, 1f)
        addThing(GROUND, street, listOf(f), rx, 0f, rz, null, null, level, casts = false, windows = null, ref = floatArrayOf((x0 + x1) / 2f, 0f, (z0 + z1) / 2f))
    }

    /** A face of a box: the plane through ([ox], [oy], [oz]) whose picture runs [tw] px along u and [th] px along v. */
    private fun face(
        ox: Float, oy: Float, oz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
        tw: Float, th: Float, tex: Texture, mask: Texture?, nx: Float, ny: Float, nz: Float,
    ): Face {
        val p = FloatArray(12)
        for (c in 0..3) {
            val a = if (c == 1 || c == 2) tw else 0f
            val b = if (c >= 2) th else 0f
            p[c * 3] = ox + ux * a + vx * b; p[c * 3 + 1] = oy + uy * a + vy * b; p[c * 3 + 2] = oz + uz * a + vz * b
        }
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        return Face(p, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), tex, mask, nx, ny, nz, ux / ul, uy / ul, uz / ul)
    }

    private fun addThing(
        kind: Int, street: Int, faces: List<Face>, rx: Float, ry: Float, rz: Float, box: Crossroads.Box?, side: CrossSide?,
        level: Int, casts: Boolean, windows: Windows?, ref: FloatArray? = null,
    ) {
        val nv = faces.fold(0) { a, f -> a + f.p.size / 3 }
        val vb = TownMemory.direct(nv * STRIDE).order(ByteOrder.nativeOrder())
        val ni = faces.fold(0) { a, f -> a + if (f.p.size == 9) 3 else 6 }
        val ib = TownMemory.direct(ni * 2).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var base = 0
        val ranges = ArrayList<IntArray>()
        var io = 0
        for (f in faces) {
            // tangent along the picture's x, the normal outwards, the bitangent completing them
            val nx = f.nx; val ny = f.ny; val nz = f.nz
            var tx = f.tx; var ty = f.ty; var tz = f.tz
            val dot = tx * nx + ty * ny + tz * nz
            tx -= dot * nx; ty -= dot * ny; tz -= dot * nz
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            tx /= tl; ty /= tl; tz /= tl
            val bxv = ny * tz - nz * ty; val byv = nz * tx - nx * tz; val bzv = nx * ty - ny * tx
            MathUtils.packTangentFrame(tx, ty, tz, bxv, byv, bzv, nx, ny, nz, q)
            val n = f.p.size / 3
            for (v in 0 until n) {
                val x = f.p[v * 3]; val y = f.p[v * 3 + 1]; val z = f.p[v * 3 + 2]
                vb.putFloat(x).putFloat(y).putFloat(z).putFloat(f.uv[v * 2]).putFloat(f.uv[v * 2 + 1])
                vb.putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
                minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z); maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
            }
            val idx = if (n == 3) shortArrayOf(0, 1, 2) else shortArrayOf(0, 1, 2, 0, 2, 3)
            for (s in idx) ib.putShort((base + s).toShort())
            ranges += intArrayOf(io, idx.size)
            io += idx.size
            base += n
        }
        vb.flip(); ib.flip()
        val vertices = VertexBuffer.Builder().bufferCount(1).vertexCount(nv)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, STRIDE)
            .build(engine)
        vertices.setBufferAt(engine, 0, vb)
        val indices = IndexBuffer.Builder().indexCount(ni).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        indices.setBuffer(engine, ib)
        vbs += vertices; ibs += indices
        val bbox = Box((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f, (maxX - minX) / 2f + .1f, (maxY - minY) / 2f + .1f, (maxZ - minZ) / 2f + .1f)
        val mis = Array(faces.size) { j ->
            val f = faces[j]
            visibleMat.createInstance().also { mi ->
                val uv = f.uvRect
                mi.setParameter("albedo", f.tex, if (uv != null) tiled else crisp)
                mi.setParameter("mask", f.mask ?: textures.black, nearest)
                if (uv != null) mi.setParameter("uvRect", uv[0], uv[1], uv[2], uv[3]) else mi.setParameter("uvRect", 0f, 0f, 1f, 1f)
                mi.setParameter("tint", col(f.tint.red), col(f.tint.green), col(f.tint.blue), 1f)
                mi.setParameter("glow", 0f, 0f, 0f, 0f)
                mi.setParameter("glass", 1f, 1f, 1f, 1f)
                mi.setParameter("fade", 1f)
                mi.setParameter("cell", 2f)
            }
        }
        val e = EntityManager.get().create()
        val rb = RenderableManager.Builder(faces.size).boundingBox(bbox).culling(false).castShadows(false).receiveShadows(true)
            .priority(if (kind == GROUND) level else 4)
        for (j in faces.indices) rb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, ranges[j][0], ranges[j][1]).material(j, mis[j])
        rb.build(engine, e)
        scene.addEntity(e)
        var proxy = 0
        var pmis: Array<MaterialInstance> = emptyArray()
        if (casts && !albedo) {
            pmis = Array(faces.size) { j -> proxyMat.createInstance().also { mi -> mi.setParameter("albedo", faces[j].tex, crisp); mi.setParameter("uvRect", 0f, 0f, 1f, 1f) } }
            proxy = EntityManager.get().create()
            val pb = RenderableManager.Builder(faces.size).boundingBox(bbox).castShadows(true).receiveShadows(false)
            for (j in faces.indices) pb.geometry(j, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices, ranges[j][0], ranges[j][1]).material(j, pmis[j])
            pb.build(engine, proxy)
            scene.addEntity(proxy)
        }
        val r = ref ?: floatArrayOf(rx, ry, rz)
        things += Thing(kind, street, e, mis, proxy, pmis, r[0], r[1], r[2], box, side, level, windows).also {
            // the ground's look is picked at a point well inside the piece, its depth matched at its middle
            if (kind == GROUND) { it.lookX = rx; it.lookZ = rz; val c = faces[0].tint; it.tr = col(c.red); it.tg = col(c.green); it.tb = col(c.blue) }
        }
    }

    // ------------------------------------------------------------------ cards: lamps, trees, benches, people

    private fun unitQuad(): Pair<VertexBuffer, IndexBuffer> {
        val vb = TownMemory.direct(4 * STRIDE).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        MathUtils.packTangentFrame(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, q)
        val pos = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val uv = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)
        for (v in 0..3) vb.putFloat(pos[v * 2]).putFloat(pos[v * 2 + 1]).putFloat(0f).putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]).putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
        vb.flip()
        val vertices = VertexBuffer.Builder().bufferCount(1).vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, STRIDE)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, STRIDE)
            .build(engine)
        vertices.setBufferAt(engine, 0, vb)
        val ib = TownMemory.direct(12).order(ByteOrder.nativeOrder())
        for (s in shortArrayOf(0, 1, 2, 0, 2, 3)) ib.putShort(s)
        ib.flip()
        val indices = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        indices.setBuffer(engine, ib)
        vbs += vertices; ibs += indices
        return vertices to indices
    }

    private fun newCard(street: Int, img: ImageBitmap?, footX: Float, footY: Float, x: Float, z: Float, person: Boolean): Card {
        val (vb, ib) = quad
        val tex = img?.let { textures.of(it) } ?: textures.white
        val mi = visibleMat.createInstance().also { m ->
            m.setParameter("albedo", tex, nearest); m.setParameter("mask", textures.black, nearest)
            m.setParameter("uvRect", 0f, 0f, 1f, 1f); m.setParameter("tint", 1f, 1f, 1f, 1f)
            m.setParameter("glow", 0f, 0f, 0f, 0f); m.setParameter("glass", 1f, 1f, 1f, 1f); m.setParameter("fade", 1f); m.setParameter("cell", 2f)
        }
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box(.5f, .5f, 0f, .5f, .5f, .1f)).culling(false).castShadows(false).receiveShadows(true).priority(5)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, mi).build(engine, e)
        engine.transformManager.create(e)
        scene.addEntity(e)
        var proxy = 0
        var pmi: MaterialInstance? = null
        if (!albedo) {
            pmi = proxyMat.createInstance().also { m -> m.setParameter("albedo", tex, nearest); m.setParameter("uvRect", 0f, 0f, 1f, 1f) }
            proxy = EntityManager.get().create()
            RenderableManager.Builder(1).boundingBox(Box(.5f, .5f, 0f, .5f, .5f, .1f)).castShadows(true).receiveShadows(false)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, pmi).build(engine, proxy)
            engine.transformManager.create(proxy)
            scene.addEntity(proxy)
        }
        return Card(street, e, mi, proxy, pmi, img, footX, footY, x, z, person)
    }

    private fun placeCards(
        f: Frame3D, world: CornerWorld, cr: Crossroads, sides: List<CrossSide>,
        eyeCam: com.lifemax.rpg.feature.habits.presentation.scene.StageCamera, classic: Boolean,
    ) {
        val cam = world.cam
        val rm = engine.renderableManager
        val k = SpriteArt.PIXEL
        val lm = engine.lightManager
        val sd = FloatArray(3).also { lm.getDirection(lm.getInstance(sun), it) }
        // the shadow twin stands across the sun's way, so it throws its whole outline
        var px = -sd[2]; var pz = sd[0]
        val pl = sqrt(px * px + pz * pz)
        if (pl < 1e-3f) { px = 1f; pz = 0f } else { px /= pl; pz /= pl }
        val rest = world.restStreet
        val sheets = SpriteSheets.sheets
        val people = IntArray(2)
        for (k in 0..1) {
            val n = sides.getOrNull(k)?.life?.count ?: continue
            while (personCards[k] < n) { cards += newCard(k, null, 16f, 63f, 0f, 0f, person = true); personCards[k]++ }
        }
        for (c in cards) {
            var img = c.img
            var x = c.x; var z = c.z
            var u0 = 0f; var v0 = 0f; var du = 1f; var dv = 1f
            if (c.person) {
                val life = sides.getOrNull(c.street)?.life
                val i = people[c.street]++
                val p = if (life != null && i < life.count) life.passers[i] else null
                if (p == null || sheets.isEmpty()) { show(rm, c.entity, false); if (c.proxy != 0) show(rm, c.proxy, false); continue }
                val sheet = sheetFor(p, sheets)
                img = sheet
                x = if (c.street == 0) cr.mainX(p.x) else cr.crossX(p.lane)
                z = if (c.street == 0) cr.mainZ(p.lane) else cr.crossZ(p.x)
                val (view, flip, frame) = pose(p, c.street, cam.yaw, f.seconds)
                val sx = (view * 6 + frame) * 32f
                val sy = (if (p.still > .5f) 1 else 2) * 64f
                // (textures are stored bottom row first, so a row band counts from the bottom)
                u0 = sx / sheet.width; du = 32f / sheet.width; dv = 64f / sheet.height; v0 = 1f - sy / sheet.height - dv
                if (flip < 0f) { u0 += du; du = -du }
                val tex = textures.of(sheet)
                c.mi.setParameter("albedo", tex, nearest)
                c.pmi?.setParameter("albedo", tex, nearest)
            }
            val im = img ?: continue
            // one on the camera's side of the corner fades as the cut passes it, as the classic's do
            val fade = if (rest < 0 && cornerNearSide(x, x, z, z)) 1f - smooth((cam.cutDepth - cam.depth(x, z)) / CornerWorld.FADE_POINT) else 1f
            val on = fade > .02f && (if (classic) cam.depth(x, z) > CornerWorld.NEAR else eyeCam.depth(x, z) > CameraRig.NEAR)
            show(rm, c.entity, on)
            if (c.proxy != 0) show(rm, c.proxy, on && fade > .5f)
            if (!on) continue
            val w = abs(du) * im.width * k
            val h = dv * im.height * k
            val left = -c.footX * k
            val bottom = -(dv * im.height - c.footY - 1f) * k
            // facing the camera: across its right, up, towards it
            card(c.entity, x, z, cam.rx, cam.rz, -cam.dx, -cam.dz, left, bottom, w, h)
            if (c.proxy != 0) card(c.proxy, x, z, px, pz, -pz, px, left, bottom, w, h)
            if (classic) {
                world.ground(x, z).copyInto(m12)
                val (s, o) = band(CARD, c.street, rest, 0)
                LookMatrix.of(m12, cam, f.width, f.height, x, h / 2f, z, s, o, look)
            } else trueLook.copyInto(look)
            c.mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
            c.mi.setParameter("uvRect", u0, v0, du, dv)
            c.mi.setParameter("fade", fade)
            c.mi.setParameter("cell", f.unit * k)
            if (c.lamp) c.mi.setParameter("glow", glow[0] * 2.5f, glow[1] * 2.5f, glow[2] * 2.5f, smooth((f.dark - .3f) / .4f))
            c.pmi?.setParameter("uvRect", u0, v0, du, dv)
        }
    }

    /** Places card [e] standing at ([x], [z]): its picture across ([ax], [az]), facing ([nx], [nz]). */
    private fun card(e: Int, x: Float, z: Float, ax: Float, az: Float, nx: Float, nz: Float, left: Float, bottom: Float, w: Float, h: Float) {
        val tm = engine.transformManager
        // columns: the picture's x (w long), up (h long), its normal; the corner at (left, bottom)
        xf[0] = ax * w; xf[1] = 0f; xf[2] = az * w; xf[3] = 0f
        xf[4] = 0f; xf[5] = h; xf[6] = 0f; xf[7] = 0f
        xf[8] = nx; xf[9] = 0f; xf[10] = nz; xf[11] = 0f
        xf[12] = x + ax * left; xf[13] = bottom; xf[14] = z + az * left; xf[15] = 1f
        tm.setTransform(tm.getInstance(e), xf)
    }

    /** Which character sheet a passer wears (as the classic picks it: from who they are, never the player's own). */
    private fun sheetFor(p: Passer, sheets: List<ImageBitmap>): ImageBitmap {
        val mine = GameState.look.mod(sheets.size)
        if (p.slot == 9999) return sheets[mine]
        val others = sheets.indices.filter { it != mine }
        if (others.isEmpty()) return sheets[mine]
        return if (p.slot >= KEEPER) sheets[others[p.look.mod(others.size)]] else sheets[others[(p.slot * 7 + 3).mod(others.size)]]
    }

    /** The sheet's view (0 right, 1 back, 2 left, 3 front), mirrored or not, and the frame, as the classic's pixel walker. */
    private fun pose(p: Passer, street: Int, yaw: Float, seconds: Float): Triple<Int, Float, Int> {
        val me = p.slot == 9999
        val standing = p.still > .5f
        val frame = if (standing) ((seconds * 5f).toInt() + p.slot * 3).mod(6) else (p.frame % com.lifemax.rpg.feature.habits.domain.StreetGait.FRAMES) * 6 / com.lifemax.rpg.feature.habits.domain.StreetGait.FRAMES
        val keeper = p.slot >= KEEPER
        var view = if (keeper) 3 else if (!me) 0 else if (GameState.facing == 0 && p.dir < 0f) 2 else GameState.facing
        var flip = if (me || keeper) 1f else p.dir
        val turn = (if (street == 0) 0f else (Math.PI / 2).toFloat()) - yaw
        if (!me && abs(turn) > 1e-4f) {
            val a = (if (keeper) -Math.PI.toFloat() / 2f else if (p.dir >= 0f) 0f else Math.PI.toFloat()) + turn
            val across = kotlin.math.cos(a); val away = kotlin.math.sin(a)
            if (abs(away) > abs(across)) { view = if (away > 0f) 1 else 3; flip = 1f } else { view = 0; flip = if (across >= 0f) 1f else -1f }
        }
        if (me && view == 0 && p.dir < 0f) flip = -1f
        return Triple(view, flip, frame)
    }

    private var lampMaskTex: Texture? = null

    /** The lamp's glow mask: its lantern glass (props/lamp_1.png, about x 42-58, y 44-52). */
    private fun lampMask(img: ImageBitmap): Texture = lampMaskTex ?: run {
        val px = StreetTextures.argb(img)
        val m = IntArray(px.size)
        for (y in 44..52) for (x in 42..58) {
            if (x >= img.width || y >= img.height) continue
            if ((px[y * img.width + x] ushr 24) > 128) m[y * img.width + x] = (255 shl 24) or (255 shl 16)
        }
        textures.make(m, img.width, img.height, linear = true).also { lampMaskTex = it }
    }

    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    // ------------------------------------------------------------------ the windows' glass and night light

    /**
     * A front's pictures for Filament: its art with the windows' cut-out glass filled (glass that mirrors the sky above
     * and the far city's blocks below, as the classic's mirrorSky does), and a mask (red: where it glows at night; green:
     * glass, smooth and reflective; blue: a lit picture that keeps its own colours, the shop's display). As the classic
     * does, the upper windows are plain glass (lit rooms only with its interiors on, about seven in ten, on a home's
     * hours), the houses' panes light on their hours, the shop's display glows, and the mirrored city has a few lights.
     */
    private inner class Windows(val sb: com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding, val seed: Int) {
        private val w = sb.front.width
        private val h = sb.front.height
        private val art = StreetTextures.argb(sb.front)
        private val emis = runCatching { StreetTextures.argb(sb.emis) }.getOrNull()?.takeIf { sb.emis.width == w && sb.emis.height == h }
        private val glass = sb.glass?.let { g -> runCatching { StreetTextures.argb(g) }.getOrNull()?.takeIf { g.width == w && g.height == h } }
        private val panes = sb.house?.shape?.panes ?: emptyList()
        private val rects = sb.windows + panes
        /** Which upper windows can be lit rooms (the classic's choice: about seven in ten, in a stable order). */
        private val rooms: Set<Int> = run {
            val n = sb.windows.size
            val on = if (n == 0) 0 else maxOf(1, kotlin.math.ceil(n * .7f).toInt())
            (0 until n).sortedBy { com.lifemax.rpg.feature.habits.domain.Ambient.hash(seed, it, 301) }.take(on).toSet()
        }
        /** The mirrored city's lights: the glass pixels that show one at night. */
        private val cityLights = IntArray(w * h)
        private var lit = -1L
        private val maskPx = IntArray(w * h)
        val albedo: Texture
        val mask: Texture

        init {
            val px = art.copyOf()
            val skyHi = Color(0xFF8FA9C8); val skyLo = SceneInkGlass
            val city = Color(0xFF2E3B52)
            for ((j, r) in rects.withIndex()) {
                val x0 = r[0].toInt(); val y0 = r[1].toInt(); val x1 = (r[0] + r[2]).toInt().coerceAtMost(w); val y1 = (r[1] + r[3]).toInt().coerceAtMost(h)
                val rh = (y1 - y0).coerceAtLeast(1)
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (x < 0 || y < 0) continue
                    val i = y * w + x
                    if (px[i] ushr 24 >= 250) continue
                    val t = (y - y0).toFloat() / rh
                    var g = lerp(skyHi, skyLo, (t * .8f + .2f).coerceIn(0f, 1f))
                    // the far blocks mirrored in the lower part: a skyline of the pane's own, in steps of two pixels
                    val col = (x - x0) / 2
                    val top = .45f + .3f * com.lifemax.rpg.feature.habits.domain.Ambient.hash(seed + j, col, 77)
                    if (t > top) {
                        g = lerp(g, city, .65f)
                        // a few of its windows lit (seen at night)
                        if ((x - x0) % 2 == 0 && (y - y0) % 2 == 0 && com.lifemax.rpg.feature.habits.domain.Ambient.hash(seed + j, x * 31 + y, 78) > .86f) cityLights[i] = 1
                    }
                    px[i] = blend(g.toArgb(), px[i])
                }
            }
            albedo = textures.make(px, w, h, mips = true)
            mask = textures.make(IntArray(w * h), w, h, linear = true)
        }

        /** Rebuilds the mask when the set of lit windows changes with the hour. */
        fun update(hour: Float) {
            var bits = 0L
            val nw = sb.windows.size
            for (j in rects.indices) {
                if (j >= 63) break
                val room = if (j < nw) StageTuning.interiors && j in rooms else true
                if (room && com.lifemax.rpg.feature.habits.domain.Dusk.lightsOn(seed * 31 + j, hour) > .5f) bits = bits or (1L shl j)
            }
            if (bits == lit) return
            lit = bits
            for (i in maskPx.indices) {
                // the shop's display glows as a lit picture (its own colours warmed, not a wash over them)
                val e = emis?.let { (it[i] ushr 24) * 3 / 5 } ?: 0
                val g = glass?.let { (it[i] ushr 24) * 2 / 5 } ?: 0
                maskPx[i] = (255 shl 24) or (e shl 16) or (g shl 8) or (if (e > 0) 255 else 0)
            }
            for ((j, r) in rects.withIndex()) {
                val on = j < 63 && (bits shr j) and 1L == 1L
                for (y in r[1].toInt() until (r[1] + r[3]).toInt().coerceAtMost(h)) for (x in r[0].toInt() until (r[0] + r[2]).toInt().coerceAtMost(w)) {
                    if (x < 0 || y < 0) continue
                    val i = y * w + x
                    if ((art[i] ushr 24) >= 250) continue
                    val red = if (on) 255 else if (cityLights[i] != 0) 150 else 0
                    maskPx[i] = (255 shl 24) or (red shl 16) or (255 shl 8)
                }
            }
            textures.update(mask, maskPx)
        }

        private fun blend(under: Int, over: Int): Int {
            val a = (over ushr 24) / 255f
            fun ch(s: Int) = (((under shr s) and 255) * (1f - a) + ((over shr s) and 255) * a).toInt().coerceIn(0, 255)
            return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    // ------------------------------------------------------------------ teardown

    private fun clear() {
        val em = EntityManager.get()
        for (t in things) {
            scene.remove(t.entity); engine.destroyEntity(t.entity); em.destroy(t.entity)
            if (t.proxy != 0) { scene.remove(t.proxy); engine.destroyEntity(t.proxy); em.destroy(t.proxy) }
        }
        for (c in cards) {
            scene.remove(c.entity); engine.destroyEntity(c.entity); em.destroy(c.entity)
            if (c.proxy != 0) { scene.remove(c.proxy); engine.destroyEntity(c.proxy); em.destroy(c.proxy) }
        }
        for (t in things) { for (mi in t.mis) engine.destroyMaterialInstance(mi); for (mi in t.proxyMis) engine.destroyMaterialInstance(mi) }
        for (c in cards) { engine.destroyMaterialInstance(c.mi); c.pmi?.let { engine.destroyMaterialInstance(it) } }
        props?.destroy(); props = null
        things.clear(); cards.clear(); personCards.fill(0)
        lampMaskTex = null
        for (v in vbs) engine.destroyVertexBuffer(v)
        for (i in ibs) engine.destroyIndexBuffer(i)
        vbs.clear(); ibs.clear()
        textures.destroy()
        builtFor = null
    }

    /** Frees everything this made (in the order Filament needs: entities, instances, buffers, textures, materials). */
    fun destroy() {
        clear()
        textures.destroy(all = true)
        scene.remove(sun); engine.destroyEntity(sun); EntityManager.get().destroy(sun)
        ibl?.let { engine.destroyIndirectLight(it) }; ibl = null
        engine.destroyMaterial(visibleMat)
        engine.destroyMaterial(proxyMat)
        colorGrading?.let { engine.destroyColorGrading(it) }
        val ce = camera.entity
        engine.destroyCameraComponent(ce); EntityManager.get().destroy(ce)
        engine.destroyView(view)
        engine.destroyScene(scene)
    }

    private class Face(
        val p: FloatArray, val uv: FloatArray, val tex: Texture, val mask: Texture?,
        val nx: Float, val ny: Float, val nz: Float, val tx: Float, val ty: Float, val tz: Float,
        val tint: Color = Color.White,
        /** The part of the picture it wears (offset, size; a tiled ground's repeats), all of it if null. */
        val uvRect: FloatArray? = null,
    )

    private class Thing(
        val kind: Int, val street: Int, val entity: Int, val mis: Array<MaterialInstance>, val proxy: Int,
        val proxyMis: Array<MaterialInstance>, val refX: Float, val refY: Float, val refZ: Float,
        val box: Crossroads.Box?, val side: CrossSide?, val level: Int, val windows: Windows?,
    ) {
        /** A modelled building's night windows, brought to the hour. */
        var hourly: ((Float) -> Unit)? = null
        /** How far forward its depth is drawn (street units): a billboard over its own roof. */
        var pull = 0f
        var lookX = refX
        var lookZ = refZ
        /** A flat ground piece's own colour (linear), hued by the hour. */
        var tr = 1f
        var tg = 1f
        var tb = 1f
    }

    private class Card(
        val street: Int, val entity: Int, val mi: MaterialInstance, val proxy: Int, val pmi: MaterialInstance?,
        val img: ImageBitmap?, val footX: Float, val footY: Float, val x: Float, val z: Float, val person: Boolean,
    ) {
        var lamp = false
    }

    /** The glass low down, mirroring the street's shade (the classic's day glass). */
    private val SceneInkGlass = Color(0xFF34435C)

    private val dayPave = com.lifemax.rpg.feature.habits.presentation.scene.skyColors(13f).pavement

    companion object {
        const val BOX = 0
        const val GROUND = 1
        const val CARD = 2
        private const val STRIDE = 36
        /** The ground tiles' length along their street (the paving's 24 and the dashes' 46 both go into it) and texels a unit. */
        const val TILE_L = 552f
        const val TEX = 2f
        /** The open ground's paving tile (street units a side, a whole number of its 24-unit slabs). */
        const val PLAZA = 96f
        /** How far above the ground below it each level of ground is laid (street units). */
        private const val LEVEL_LIFT = .05f
        /** How thick the air is past the street in view (the fog's density, per street unit). */
        const val HAZE = .0006f
        /** The sun's and the sky light's strength by day (tuned by eye against the classic street). */
        private const val SUN = .45f
        private const val AMB = .8f
        /** Passer slots at and past this are the people standing at their doors (StreetLifePainter.KEEPER). */
        private const val KEEPER = 10000
    }
}
