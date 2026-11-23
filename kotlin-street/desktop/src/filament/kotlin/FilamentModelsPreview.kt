import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.LinearToneMapper
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.domain.BuildingSpec
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentModels
import com.lifemax.rpg.feature.habits.presentation.scene3d.ModelMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.ModelPlace
import com.lifemax.rpg.feature.habits.presentation.scene3d.StreetTextures
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.jetbrains.skia.Image as SkiaImage

/**
 * The judge for our modelled buildings in Filament (-Dmode=filament-models -Dstreet=N, or a range 2-10 or a list 4,7):
 * showcase street N's villas and landmarks (tools/make_buildings.py --showcase, built into build/showcase when no
 * -Dassets= is given) drawn by [FilamentModels] with a plain perspective camera, nothing of the classic's leaned looks:
 * each building alone in its lot, straight on, three-quarter from the left and the right, from behind at a corner and
 * from higher up onto its roofs at 13:00, then three-quarter at dusk and straight on at night, closer at a person's
 * height, and its long side at night; and the whole street.
 * Writes build/preview/filament-models/street<N>_<building>_<view>.png and street<N>_sheet.png, a contact sheet (a row
 * a building, a column a view).
 */
fun renderFilamentModels(out: String) {
    val dir = File(out, "filament-models").also { it.mkdirs() }
    val assets = System.getProperty("assets")?.let { File(it) } ?: File("build/showcase").also { sc ->
        if (!File(sc, "street.json").exists()) {
            println("building the showcase streets into $sc")
            val p = ProcessBuilder("python3", File("../tools/make_buildings.py").absolutePath, "--showcase", sc.absolutePath).inheritIO().start()
            check(p.waitFor() == 0) { "make_buildings.py --showcase failed" }
        }
    }
    val spec = System.getProperty("street") ?: "2-10"
    val streets = spec.split(",").flatMap { s ->
        if ("-" in s) { val (a, b) = s.split("-").map { it.trim().toInt() }; (a..b).toList() } else listOf(s.trim().toInt())
    }
    val root = org.json.JSONObject(File(assets, "street.json").readText())
    val b = File(assets, "buildings")
    val windows = SpriteBuildingLoad.windows(File(b, "windows.json").takeIf { it.exists() }?.readText())
    val houses = SpriteBuildingLoad.houses(File(b, "houses.json").takeIf { it.exists() }?.readText())
    val models = SpriteBuildingLoad.models(File(b, "models.json").takeIf { it.exists() }?.readText())
    fun img(n: String) = File(b, "$n.png").takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    val names = run {
        val shops = root.getJSONArray("shops")
        (0 until shops.length()).associate { val o = shops.getJSONObject(it); o.getString("sprite") to o.getString("name") }
    }

    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val w = (System.getProperty("w")?.toIntOrNull() ?: 1200); val h = w * 3 / 4
    val swap = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
    val renderer = engine.createRenderer()
    renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) }
    val mats = File("../app/src/main/assets/filament")
    fun mat(n: String): Material { val by = File(mats, "$n.filamat").readBytes(); return Material.Builder().payload(ByteBuffer.allocateDirect(by.size).put(by).flip(), by.size).build(engine) }
    val lit = mat("street_lit"); val proxyMat = mat("shadow_proxy")
    val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

    for (n in streets) {
        val st = root.getJSONArray("streets").optJSONObject(n) ?: continue
        val sprites = st.getJSONArray("buildings").let { a -> (0 until a.length()).map { a.getString(it) } }
        val built = sprites.mapNotNull { sp ->
            val sb = SpriteBuildingLoad.load(sp, 0, windows, houses, models) { img(it) }
            if (sb?.model == null) { println("street $n: $sp has no model, left out"); null } else sp to sb
        }
        if (built.isEmpty()) continue
        println("street $n (${st.optString("name")}): ${built.size} buildings")
        val stage = ModelStage(engine, lit, proxyMat, w, h)
        stage.build(built)
        val tiles = ArrayList<List<IntArray>>()
        val labels = ArrayList<String>()
        val views = listOf(
            View3("front", 13f, 0f, 0f), View3("left", 13f, -38f, 0f), View3("right", 13f, 38f, 0f),
            View3("behind", 13f, 140f, 0f), View3("high", 13f, 25f, 38f),
            View3("dusk", 19.3f, -38f, 0f), View3("night", 22.5f, 0f, 0f),
            // closer at a person's height, and the long side at night (its windows lit)
            View3("near", 13f, -24f, 0f), View3("nightside", 22.5f, -38f, 0f),
        )
        for ((i, pair) in built.withIndex()) {
            val (sp, _) = pair
            val row = ArrayList<IntArray>()
            for (v in views) {
                stage.only(i)
                stage.aim(i, v)
                stage.light(v.hour)
                val px = stage.draw(renderer, swap, pixels)
                val name = "street${n}_${sp}_${v.name}.png"
                savePng(File(dir, name), px, null, w, h)
                row += px
            }
            tiles += row
            labels += "$sp ${names[sp] ?: ""}"
            println("  $sp ${names[sp]}: ${views.size} views")
        }
        // the whole street, straight on and from above, by day
        stage.only(-1)
        for (v in listOf(View3("street", 13f, 0f, 12f), View3("streethigh", 13f, 20f, 35f))) {
            stage.aimAll(v); stage.light(v.hour)
            savePng(File(dir, "street${n}_${v.name}.png"), stage.draw(renderer, swap, pixels), null, w, h)
        }
        // the contact sheet: a row a building, a column a view, each a third of its size
        val tw = w / 3; val th = h / 3
        val sheet = IntArray(tw * views.size * th * tiles.size) { 0xFF202028.toInt() }
        val sw = tw * views.size
        for ((r, row) in tiles.withIndex()) for ((c, px) in row.withIndex()) {
            for (y in 0 until th) for (x in 0 until tw) {
                // a 3x3 box average
                var rr = 0; var gg = 0; var bb = 0
                for (yy in 0..2) for (xx in 0..2) { val p = px[(y * 3 + yy) * w + x * 3 + xx]; rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255 }
                sheet[(r * th + y) * sw + c * tw + x] = (255 shl 24) or ((rr / 9) shl 16) or ((gg / 9) shl 8) or (bb / 9)
            }
        }
        savePng(File(dir, "street${n}_sheet.png"), sheet, null, sw, th * tiles.size)
        File(dir, "street${n}_sheet.txt").writeText("columns: ${views.joinToString(" ") { it.name }}\n" + labels.withIndex().joinToString("\n") { "row ${it.index}: ${it.value}" } + "\n")
        println("wrote ${File(dir, "street${n}_sheet.png").absolutePath}")
        stage.destroy()
    }
    engine.destroyMaterial(lit); engine.destroyMaterial(proxyMat)
    engine.destroyRenderer(renderer)
    engine.destroySwapChain(swap)
    com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena.release(engine)
    engine.destroy()
}

/** A view of a building: its hour, its bearing from straight in front (degrees, + to the right) and its elevation. */
private class View3(val name: String, val hour: Float, val bearing: Float, val elevation: Float)

/** A judge's stage: the street's buildings in their lots along x, their fronts facing -z, a ground, a sun and a sky light. */
private class ModelStage(val engine: Engine, val lit: Material, val proxyMat: Material, val w: Int, val h: Int) {
    val scene = engine.createScene()
    val view: View = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    val textures = StreetTextures(engine, srgb = true)
    val sun = EntityManager.get().create()
    var ibl: IndirectLight? = null
    val grading: ColorGrading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)
    val meshes = ArrayList<ModelMesh>()
    /** Each building's world bounds. */
    val bounds = ArrayList<FloatArray>()
    val allMis = ArrayList<MaterialInstance>()
    val modelMis = ArrayList<MaterialInstance>()
    val nights = ArrayList<((Float) -> Unit)?>()
    val extra = ArrayList<Int>()
    val vbs = ArrayList<VertexBuffer>(); val ibs = ArrayList<IndexBuffer>()
    val look = FloatArray(16)
    var hour = 13f

    init {
        view.scene = scene; view.camera = camera
        view.viewport = Viewport(0, 0, w, h)
        view.dithering = View.Dithering.NONE
        view.antiAliasing = View.AntiAliasing.NONE
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.colorGrading = grading
        view.isShadowingEnabled = true
        view.shadowType = View.ShadowType.PCF
        camera.setExposure(1f)
        val so = LightManager.ShadowOptions().apply {
            mapSize = 4096; shadowCascades = 2; stable = false; lispsm = false
            constantBias = .05f; normalBias = .4f; polygonOffsetConstant = .5f; polygonOffsetSlope = .2f
        }
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, 1f, 1f).intensity(1f).direction(0f, -1f, 0f)
            .castShadows(true).shadowOptions(so).build(engine, sun)
        scene.addEntity(sun)
    }

    fun build(list: List<Pair<String, SpriteBuilding>>) {
        val k = BuildingSpec.W / SpriteArt.SRC_W
        var x = 0f
        for ((i, pair) in list.withIndex()) {
            val ma = pair.second.model!!
            val sh = ma.shape
            val lot = max(sh.width, SpriteArt.SRC_W) + 40f
            // the model's x 0 at its lot's left plus its margin; its front line its setback back from the pavement (z 0)
            val place = ModelPlace(x + (lot - sh.width) / 2f * k, sh.setback * k, 1f, 0f, 0f, -1f, k)
            val m = FilamentModels.build(engine, lit, proxyMat, textures, ma, place, pair.first.hashCode() + i, linear = true)
            meshes += m; bounds += m.body
            scene.addEntity(m.entity); scene.addEntity(m.proxy)
            allMis += m.mis; modelMis += m.mis; nights += m.night
            x += lot * k
        }
        // the ground: a pavement along the fronts, the road in front, grass round the lots
        val day = skyColors(13f)
        fun rgb(c: androidx.compose.ui.graphics.Color) = (((c.red * 255).toInt()) shl 16) or (((c.green * 255).toInt()) shl 8) or (c.blue * 255).toInt()
        flat(-2000f, -40f, x + 2000f, 3000f, 0x6E7F57, 0f)
        flat(-2000f, -36f, x + 2000f, 0f, rgb(day.pavement), .02f)
        flat(-2000f, -4000f, x + 2000f, -40f, rgb(day.road), .01f)
    }

    private fun flat(x0: Float, z0: Float, x1: Float, z1: Float, rgb: Int, y: Float) {
        val vb = ByteBuffer.allocateDirect(4 * 36).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        MathUtils.packTangentFrame(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f, q)
        for ((px, pz) in listOf(x0 to z0, x1 to z0, x1 to z1, x0 to z1)) vb.putFloat(px).putFloat(y).putFloat(pz).putFloat(0f).putFloat(0f).putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
        vb.flip()
        val v = VertexBuffer.Builder().bufferCount(1).vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 36)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 36)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 20, 36).build(engine)
        v.setBufferAt(engine, 0, vb)
        val ib = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()); for (s in shortArrayOf(0, 1, 2, 0, 2, 3)) ib.putShort(s); ib.flip()
        val i = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine); i.setBuffer(engine, ib)
        vbs += v; ibs += i
        fun lin(c: Float) = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
        val mi = lit.createInstance().also { m ->
            val s = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE)
            m.setParameter("albedo", textures.white, s); m.setParameter("mask", textures.black, s); m.setParameter("uvRect", 0f, 0f, 1f, 1f)
            m.setParameter("tint", lin(((rgb shr 16) and 255) / 255f), lin(((rgb shr 8) and 255) / 255f), lin((rgb and 255) / 255f), 1f)
            m.setParameter("glow", 0f, 0f, 0f, 0f); m.setParameter("glass", 1f, 1f, 1f, 1f); m.setParameter("fade", 1f); m.setParameter("cell", 2f)
        }
        allMis += mi
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box((x0 + x1) / 2f, y, (z0 + z1) / 2f, (x1 - x0) / 2f, .1f, (z1 - z0) / 2f)).culling(false)
            .castShadows(false).receiveShadows(true).priority(0).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, v, i).material(0, mi).build(engine, e)
        scene.addEntity(e); extra += e
    }

    /** Shows only building [i] (and the ground), or every one (-1). */
    fun only(i: Int) {
        val rm = engine.renderableManager
        for ((j, m) in meshes.withIndex()) {
            val on = i < 0 || i == j
            rm.setLayerMask(rm.getInstance(m.entity), 0xff, if (on) 1 else 0)
            if (m.proxy != 0) rm.setLayerMask(rm.getInstance(m.proxy), 0xff, if (on) 1 else 0)
        }
    }

    fun aim(i: Int, v: View3) = aimAt(bounds[i], v)

    fun aimAll(v: View3) {
        val a = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (b in bounds) for (j in 0..2) { a[j] = minOf(a[j], b[j]); a[j + 3] = maxOf(a[j + 3], b[j + 3]) }
        // (the yards reach out to the pavement: frame the buildings' height, the street's length)
        aimAt(a, v)
    }

    /** A plain pinhole looking at the bounds [b] from bearing and elevation [v], far enough back to hold them. */
    private fun aimAt(b: FloatArray, v: View3) {
        val cx = (b[0] + b[3]) / 2f; val cy = (b[1] + b[4]) / 2f; val cz = (b[2] + b[5]) / 2f
        val r = sqrt((b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])) / 2f
        val fov = 40.0
        val dist = r / sin(Math.toRadians(fov / 2)).toFloat() * (if (v.name.startsWith("street")) .9f else if (v.name == "near") .55f else 1f)
        val be = Math.toRadians(v.bearing.toDouble()); val el = Math.toRadians(v.elevation.toDouble())
        // in front is -z; a bearing to the right is +x
        val dx = (sin(be) * cos(el)).toFloat(); val dz = (-cos(be) * cos(el)).toFloat(); val dy = sin(el).toFloat()
        var ex = cx + dx * dist; var ey = cy + dy * dist; var ez = cz + dz * dist
        // at eye level the camera is a person's height up (about 38 pixels), looking at the middle
        if (v.elevation < 1f) ey = 38f * BuildingSpec.W / SpriteArt.SRC_W + (if (v.elevation > 0f) dist * sin(el).toFloat() else 0f)
        if (v.name.startsWith("street")) { ey = cy + dist * sin(el).toFloat() }
        pinhole(ex, ey, ez, cx, cy, cz, fov)
    }

    private fun pinhole(ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float, fovY: Double) {
        var fx = tx - ex; var fy = ty - ey; var fz = tz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // right = up x forward, up = forward x right (x across the screen to the right, y up)
        var rx = fz; val ry = 0f; var rz = -fx
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        val ux = fy * rz - fz * ry; val uy = fz * rx - fx * rz; val uz = fx * ry - fy * rx
        val f = (h / 2f) / kotlin.math.tan(Math.toRadians(fovY / 2)).toFloat()
        val cxp = w / 2f; val cyp = h / 2f
        // the screen matrix (rows: x w, y w, w; y down the screen), as the classic's StageCamera makes them
        val m = FloatArray(12)
        fun row(o: Int, ax: Float, ay: Float, az: Float) { m[o] = ax; m[o + 1] = ay; m[o + 2] = az; m[o + 3] = -(ax * ex + ay * ey + az * ez) }
        row(0, f * rx + cxp * fx, f * ry + cxp * fy, f * rz + cxp * fz)
        row(4, -f * ux + cyp * fx, -f * uy + cyp * fy, -f * uz + cyp * fz)
        row(8, fx, fy, fz)
        // into the device-domain clip matrix (LookMatrix's form): its depth the plain pinhole's N / d
        val n = com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix.N
        val sx = 2f / w; val sy = 2f / h
        for (c in 0..3) {
            val mx = m[c]; val my = m[4 + c]; val mw = m[8 + c]
            look[c * 4] = sx * mx - mw
            look[c * 4 + 1] = sy * my - mw
            look[c * 4 + 2] = if (c == 3) 1f - 2f * n else 0f
            look[c * 4 + 3] = mw
        }
        for (mi in allMis) mi.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
        camera.setProjection(fovY, w.toDouble() / h, 20.0, 20000.0, Camera.Fov.VERTICAL)
        camera.lookAt(ex.toDouble(), ey.toDouble(), ez.toDouble(), tx.toDouble(), ty.toDouble(), tz.toDouble(), 0.0, 1.0, 0.0)
    }

    /** The light at [hour]: a high sun from the front left by day, a low warm one from the front right at dusk, the moon at night. */
    fun light(hour: Float) {
        this.hour = hour
        val sky = skyColors(hour)
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        fun lin(c: Float) = if (c <= .04045f) c / 12.92f else Math.pow(((c + .055f) / 1.055f).toDouble(), 2.4).toFloat()
        val (dir, col, amb) = when {
            sky.dark < .2f -> Triple(floatArrayOf(.45f, -.75f, .5f), floatArrayOf(1f, .97f, .92f, 1.45f), floatArrayOf(.62f, .66f, .74f))
            sky.dark < .75f -> Triple(floatArrayOf(-.7f, -.22f, .68f), floatArrayOf(1f, .62f, .36f, 1.1f), floatArrayOf(.30f, .26f, .32f))
            else -> Triple(floatArrayOf(.3f, -.8f, .5f), floatArrayOf(.6f, .7f, 1f, .1f), floatArrayOf(.05f, .06f, .11f))
        }
        val dl = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        lm.setDirection(li, dir[0] / dl, dir[1] / dl, dir[2] / dl)
        lm.setColor(li, col[0], col[1], col[2])
        lm.setIntensity(li, col[3] * Math.PI.toFloat())
        lm.setShadowCaster(li, sky.dark < .75f)
        val sh = FloatArray(12)
        sh[0] = amb[0]; sh[1] = amb[1]; sh[2] = amb[2]
        sh[3] = amb[0] * -.06f; sh[4] = amb[1] * -.06f; sh[5] = amb[2] * -.06f
        sh[6] = -.18f * amb[0]; sh[7] = -.18f * amb[1]; sh[8] = -.18f * amb[2]
        ibl?.let { engine.destroyIndirectLight(it) }
        ibl = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine).also { scene.indirectLight = it }
        // the windows' lamps (as FilamentStreet: warm, as strong as it is dark) and what the glass mirrors
        val wl = com.lifemax.rpg.feature.habits.presentation.scene.SceneInk.WindowLit
        val a = .75f * smooth((sky.dark - .35f) / .5f)
        val tone = sky.sky.lastOrNull() ?: androidx.compose.ui.graphics.Color.White
        val mm = maxOf(tone.red, tone.green, tone.blue).coerceAtLeast(.05f)
        for (mi in modelMis) {
            mi.setParameter("glow", lin(wl.red), lin(wl.green) * .9f, lin(wl.blue) * .7f, a)
            mi.setParameter("glass", mix(1f, tone.red / mm * 1.15f, .7f), mix(1f, tone.green / mm * 1.15f, .7f), mix(1f, tone.blue / mm * 1.15f, .7f), 1f)
        }
        for (nt in nights) nt?.invoke(hour)
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

    /** Renders the view and reads it back over the hour's sky (rows top first, opaque ARGB). */
    fun draw(renderer: Renderer, swap: com.google.android.filament.SwapChain, pixels: ByteBuffer): IntArray {
        val done = AtomicBoolean(false)
        for (frame in 0..1) {
            if (renderer.beginFrame(swap, 0L)) {
                renderer.render(view)
                if (frame == 1) renderer.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, w, Executor { it.run() }, Runnable { done.set(true) }))
                renderer.endFrame()
            }
            engine.flushAndWait()
        }
        var guard = 0
        while (!done.get() && guard++ < 400) { engine.flushAndWait(); Thread.sleep(5) }
        val sky = skyColors(hour).sky
        val px = IntArray(w * h)
        for (y in 0 until h) {
            // the sky behind: its colours top to bottom
            val t = y.toFloat() / (h - 1) * (sky.size - 1)
            val c0 = sky[t.toInt().coerceAtMost(sky.size - 1)]; val c1 = sky[(t.toInt() + 1).coerceAtMost(sky.size - 1)]; val f = t - t.toInt()
            val sr = ((c0.red + (c1.red - c0.red) * f) * 255).toInt(); val sg = ((c0.green + (c1.green - c0.green) * f) * 255).toInt(); val sb = ((c0.blue + (c1.blue - c0.blue) * f) * 255).toInt()
            for (x in 0 until w) {
                val i = ((h - 1 - y) * w + x) * 4
                val r = pixels.get(i).toInt() and 255; val g = pixels.get(i + 1).toInt() and 255; val b = pixels.get(i + 2).toInt() and 255; val a = pixels.get(i + 3).toInt() and 255
                fun ch(fg: Int, bg: Int) = (fg + bg * (255 - a) / 255).coerceAtMost(255)
                px[y * w + x] = (255 shl 24) or (ch(r, sr) shl 16) or (ch(g, sg) shl 8) or ch(b, sb)
            }
        }
        return px
    }

    fun destroy() {
        val em = EntityManager.get()
        for (m in meshes) {
            scene.remove(m.entity); engine.destroyEntity(m.entity); em.destroy(m.entity)
            if (m.proxy != 0) { scene.remove(m.proxy); engine.destroyEntity(m.proxy); em.destroy(m.proxy) }
        }
        for (e in extra) { scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
        for (m in meshes) { for (mi in m.mis) engine.destroyMaterialInstance(mi); for (mi in m.proxyMis) engine.destroyMaterialInstance(mi) }
        for (mi in allMis) if (meshes.none { mi in it.mis }) engine.destroyMaterialInstance(mi)
        for (v in vbs) engine.destroyVertexBuffer(v)
        for (i in ibs) engine.destroyIndexBuffer(i)
        for (m in meshes) m.geo.free(engine)
        textures.destroy(all = true)
        scene.remove(sun); engine.destroyEntity(sun); em.destroy(sun)
        ibl?.let { engine.destroyIndirectLight(it) }
        engine.destroyColorGrading(grading)
        val ce = camera.entity
        engine.destroyCameraComponent(ce); em.destroy(ce)
        engine.destroyView(view)
        engine.destroyScene(scene)
    }
}
