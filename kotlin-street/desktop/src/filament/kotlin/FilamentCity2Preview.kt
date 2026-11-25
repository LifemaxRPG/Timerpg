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
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.presentation.scene3d.LookMatrix
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Dove Beach, the second city, as its phase-1 prototype stands (-Dmode=filament-city2, docs/CITY2.md): the site's ground
 * (the 10 m height grid of tools/city2-dump.ts, the roads benched into it) and every road as a ribbon at its own height
 * and crossfall, the viaduct and bridges on piers, the moles, the pier, the sea, drawn by Filament in the town's own lit
 * material (street_lit_opaque) with pixel-art ground pictures made here. No buildings, trees or cars yet: the roads and the
 * land are what phase 1 has. Writes <out>/city2/<shot>.png and city2.txt. Desktop renders (Mesa's software GL), not S25.
 *
 *   npx tsx kotlin-street/tools/city2-dump.ts build/city2    (from the repository root)
 *   gradle :desktop:preview -Dmode=filament-city2 -Dcity2=<that dir>
 */
fun renderCity2(out: String) {
    val data = File(System.getProperty("city2") ?: "../../build/city2")
    val dir = File(out, "city2").also { it.mkdirs() }
    val w = (System.getProperty("w") ?: "1280").toInt(); val h = (System.getProperty("h") ?: "720").toInt()
    val city = JSONObject(File(data, "city2.json").readText())
    val render = JSONObject(File(data, "render.json").readText())
    val fr = city.getJSONObject("frame")
    val gx0 = fr.getDouble("x0").toFloat(); val gz0 = fr.getDouble("z0").toFloat(); val g = fr.getDouble("grid").toFloat()
    val nx = fr.getInt("nx"); val nz = fr.getInt("nz")
    val hb = File(data, "ground.f32").readBytes()
    val hf = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    fun H(i: Int, j: Int) = hf.get(j.coerceIn(0, nz - 1) * nx + i.coerceIn(0, nx - 1))
    fun ground(x: Float, z: Float): Float {
        val fx = (x - gx0) / g; val fz = (z - gz0) / g
        val i = floor(fx).toInt(); val j = floor(fz).toInt(); val u = fx - i; val v = fz - j
        return (H(i, j) * (1 - u) + H(i + 1, j) * u) * (1 - v) + (H(i, j + 1) * (1 - u) + H(i + 1, j + 1) * u) * v
    }

    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val assets = File(assetsDir(), "filament")
    val bytes = File(assets, "street_lit_opaque.filamat").readBytes()
    val lit = Material.Builder().payload(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip(), bytes.size).build(engine)
    val scene = engine.createScene()
    val view = engine.createView()
    val camera: Camera = engine.createCamera(EntityManager.get().create())
    val swap = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
    val renderer = engine.createRenderer().also { it.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) } }
    val grading = ColorGrading.Builder().toneMapper(LinearToneMapper()).build(engine)
    view.scene = scene; view.camera = camera
    view.dithering = View.Dithering.NONE; view.antiAliasing = View.AntiAliasing.NONE; view.sampleCount = 1
    view.blendMode = View.BlendMode.TRANSLUCENT; view.colorGrading = grading
    view.viewport = Viewport(0, 0, w, h)
    camera.setExposure(1f)
    val sun = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, .93f, .82f).intensity(1.1f * Math.PI.toFloat()).direction(.78f, -.42f, -.46f).build(engine, sun)
    scene.addEntity(sun)
    val sh = floatArrayOf(.32f, .36f, .44f, 0f, -.03f, -.04f, -.06f, -.06f, -.06f, 0f, 0f, 0f)
    scene.indirectLight = IndirectLight.Builder().irradiance(2, sh).intensity(1f).build(engine)

    // ---- the pictures: small pixel-art tiles, crisp up close, mipmapped far off
    val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.REPEAT).also { it.anisotropy = 8f }
    val textures = ArrayList<Texture>()
    fun texture(tw: Int, th: Int, px: (Int, Int) -> Int): Texture {
        var levels = 1; while ((1 shl levels) <= max(tw, th)) levels++
        val t = Texture.Builder().width(tw).height(th).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        val b = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until th) for (x in 0 until tw) { val c = px(x, y); b.put((c shr 16 and 255).toByte()); b.put((c shr 8 and 255).toByte()); b.put((c and 255).toByte()); b.put(-1) }
        b.flip()
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(b, Texture.Format.RGBA, Texture.Type.UBYTE))
        t.generateMipmaps(engine)
        textures += t
        return t
    }
    fun hash(x: Int, y: Int, k: Int): Int { var v = x * 374761393 + y * 668265263 + k * 1442695041; v = (v xor (v ushr 13)) * 1274126177; return (v xor (v ushr 16)) and 0x7fffffff }
    fun rgb(r: Int, gg: Int, b: Int) = (r.coerceIn(0, 255) shl 16) or (gg.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun speckle(base: Int, spread: Int, k: Int) = texture(32, 32) { x, y ->
        val d = (hash(x / 2, y / 2, k) % (2 * spread + 1)) - spread
        rgb((base shr 16 and 255) + d, (base shr 8 and 255) + d, (base and 255) + d / 2)
    }
    val grass = speckle(0x6E9A48, 14, 1)
    val hill = speckle(0x5A7E3C, 14, 2)
    val sand = speckle(0xE2D2A2, 10, 3)
    val rock = texture(32, 32) { x, y -> val band = (y / 4 + hash(x / 8, y / 4, 4) % 2) % 3; rgb(132 - band * 14 + hash(x, y, 5) % 10, 124 - band * 14, 112 - band * 12) }
    val marsh = texture(32, 32) { x, y -> if (hash(x, y / 3, 6) % 5 == 0) rgb(150, 140, 80) else rgb(108 + hash(x, y, 7) % 14, 122, 66) }
    val water = texture(32, 32) { x, y -> if ((y % 8 == 0 && (x + y) % 16 < 6) || hash(x / 2, y, 8) % 37 == 0) rgb(120, 170, 205) else rgb(52, 108, 152) }
    val asphalt = texture(32, 64) { x, y ->
        when {
            x == 2 || x == 29 -> rgb(225, 225, 215)                       // edge lines
            (x == 15 || x == 16) && (y % 32) < 14 -> rgb(225, 190, 60)    // the dashed centre line
            else -> { val d = hash(x, y, 9) % 18; rgb(74 + d, 76 + d, 80 + d) }
        }
    }
    val cobbles = texture(32, 32) { x, y -> if (x % 8 == 0 || (y % 6 == 0)) rgb(90, 84, 78) else { val d = hash(x / 8, y / 6, 10) % 30; rgb(140 + d, 128 + d, 112 + d) } }
    val stone = speckle(0xB8B0A0, 12, 11)
    val timber = texture(32, 32) { x, y -> if (x % 6 == 0) rgb(80, 60, 44) else rgb(150 + hash(x / 6, y, 12) % 20, 116, 84) }
    val white = speckle(0xF0EEE8, 4, 13)
    val black = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine).also {
        val b = ByteBuffer.allocateDirect(4); b.put(0).put(0).put(0).put(0); b.flip()
        it.setImage(engine, 0, Texture.PixelBufferDescriptor(b, Texture.Format.RGBA, Texture.Type.UBYTE)); textures += it
    }
    val look = FloatArray(16)
    val instances = ArrayList<MaterialInstance>()
    fun mi(t: Texture): MaterialInstance = lit.createInstance().also {
        it.setParameter("albedo", t, sampler)
        it.setParameter("mask", black, TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST, TextureSampler.WrapMode.CLAMP_TO_EDGE))
        it.setParameter("uvRect", 0f, 0f, 1f, 1f)
        it.setParameter("tint", 1f, 1f, 1f, 1f)
        it.setParameter("fade", 1f); it.setParameter("cell", 1f)
        instances += it
    }
    val mats = HashMap<Texture, MaterialInstance>()
    fun matOf(t: Texture) = mats.getOrPut(t) { mi(t) }

    // ---- meshes: positions, normals (to tangent frames), uvs and triangles, uploaded as one renderable each
    class Mesh { val p = ArrayList<Float>(); val n = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float, nx_: Float, ny: Float, nz_: Float, u: Float, vv: Float): Int { p += x; p += y; p += z; n += nx_; n += ny; n += nz_; uv += u; uv += vv; return p.size / 3 - 1 }
        fun quad(a: Int, b: Int, c: Int, d: Int) { idx += a; idx += b; idx += c; idx += a; idx += c; idx += d }
    }
    val buffers = ArrayList<Any>()
    var triangles = 0
    fun upload(m: Mesh, t: Texture) {
        if (m.idx.isEmpty()) return
        val nv = m.p.size / 3
        fun fb(a: List<Float>) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { b -> a.forEach { b.put(it) }; b.flip() }
        val pos = fb(m.p); val nrm = fb(m.n); val uv = fb(m.uv)
        val so = SurfaceOrientation.Builder().vertexCount(nv).normals(nrm).build()
        val q = ByteBuffer.allocateDirect(nv * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        so.getQuatsAsFloat(q); so.destroy()
        val vb = VertexBuffer.Builder().vertexCount(nv).bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8).build(engine)
        vb.setBufferAt(engine, 0, pos); vb.setBufferAt(engine, 1, q); vb.setBufferAt(engine, 2, uv)
        val ib = IndexBuffer.Builder().indexCount(m.idx.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val ibb = ByteBuffer.allocateDirect(m.idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().also { b -> m.idx.forEach { b.put(it) }; b.flip() }
        ib.setBuffer(engine, ibb)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until nv) { x0 = min(x0, m.p[i * 3]); x1 = max(x1, m.p[i * 3]); y0 = min(y0, m.p[i * 3 + 1]); y1 = max(y1, m.p[i * 3 + 1]); z0 = min(z0, m.p[i * 3 + 2]); z1 = max(z1, m.p[i * 3 + 2]) }
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, (x1 - x0) / 2 + 1, (y1 - y0) / 2 + 1, (z1 - z0) / 2 + 1))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, matOf(t)).culling(false).castShadows(false).receiveShadows(false).build(engine, e)
        scene.addEntity(e)
        buffers += vb; buffers += ib
        triangles += m.idx.size / 3
    }

    // the ground: tiles of 64 x 64 cells, each triangle pair by what it is (sand, marsh, grass, hill grass, rock)
    val marshPoly = city.getJSONArray("marsh").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } } }
    fun inMarsh(x: Float, z: Float): Boolean {
        var c = false; var j = marshPoly.size - 1
        for (i in marshPoly.indices) { val a = marshPoly[i]; val b = marshPoly[j]; if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) c = !c; j = i }
        return c
    }
    val tile = 64
    for (tj in 0 until (nz - 1 + tile - 1) / tile) for (ti in 0 until (nx - 1 + tile - 1) / tile) {
        val meshes = HashMap<Texture, Mesh>()
        val i0 = ti * tile; val j0 = tj * tile
        val i1 = min(nx - 1, i0 + tile); val j1 = min(nz - 1, j0 + tile)
        val idx = HashMap<Texture, IntArray>()
        for (j in j0 until j1) for (i in i0 until i1) {
            val hs = floatArrayOf(H(i, j), H(i + 1, j), H(i + 1, j + 1), H(i, j + 1))
            if (hs.all { it < -.3f }) continue
            val x = gx0 + (i + .5f) * g; val z = gz0 + (j + .5f) * g
            val hm = hs.average().toFloat()
            val slope = max(abs(hs[1] - hs[0]), abs(hs[3] - hs[0])) / g
            val t = when {
                slope > .7f -> rock
                inMarsh(x, z) && hm < 1.5f -> marsh
                hm < 3.6f -> sand
                hm > 70f -> hill
                else -> grass
            }
            val m = meshes.getOrPut(t) { Mesh() }
            val ix = idx.getOrPut(t) { IntArray((tile + 1) * (tile + 1)) { -1 } }
            fun vert(a: Int, b: Int): Int {
                val k = (b - j0) * (tile + 1) + (a - i0)
                if (ix[k] >= 0) return ix[k]
                val nxv = H(a - 1, b) - H(a + 1, b); val nzv = H(a, b - 1) - H(a, b + 1)
                val l = sqrt(nxv * nxv + 4 * g * g + nzv * nzv)
                val px = gx0 + a * g; val pz = gz0 + b * g
                ix[k] = m.v(px, H(a, b), pz, nxv / l, 2 * g / l, nzv / l, px / 8f, pz / 8f)
                return ix[k]
            }
            m.quad(vert(i, j), vert(i, j + 1), vert(i + 1, j + 1), vert(i + 1, j))
        }
        for ((t, m) in meshes) upload(m, t)
    }
    // the sea: one big plane at the tide line
    Mesh().also { m ->
        val e = 30000f
        m.quad(m.v(-e, -.2f, -e, 0f, 1f, 0f, -e / 24, -e / 24), m.v(-e, -.2f, e, 0f, 1f, 0f, -e / 24, e / 24), m.v(e, -.2f, e, 0f, 1f, 0f, e / 24, e / 24), m.v(e, -.2f, -e, 0f, 1f, 0f, e / 24, -e / 24))
        upload(m, water)
    }

    // ---- the roads: ribbons at their own height and crossfall, skirts down their sides, piers under the bridges
    val report = StringBuilder()
    val roads = render.getJSONArray("roads")
    val box = Mesh()
    fun cube(cx: Float, cz: Float, y0: Float, y1: Float, hx: Float, hz: Float, yaw: Float, m: Mesh) {
        val c = cos(yaw); val s = sin(yaw)
        fun P(a: Float, b: Float) = floatArrayOf(cx + a * c - b * s, cz + a * s + b * c)
        val q = arrayOf(P(-hx, -hz), P(hx, -hz), P(hx, hz), P(-hx, hz))
        for (k in 0 until 4) {
            val a = q[k]; val b = q[(k + 1) % 4]
            val ex = b[0] - a[0]; val ez = b[1] - a[1]; val l = hypot(ex, ez)
            val nxv = ez / l; val nzv = -ex / l
            m.quad(m.v(a[0], y0, a[1], nxv, 0f, nzv, 0f, y0 / 4), m.v(a[0], y1, a[1], nxv, 0f, nzv, 0f, y1 / 4), m.v(b[0], y1, b[1], nxv, 0f, nzv, l / 4, y1 / 4), m.v(b[0], y0, b[1], nxv, 0f, nzv, l / 4, y0 / 4))
        }
        m.quad(m.v(q[0][0], y1, q[0][1], 0f, 1f, 0f, 0f, 0f), m.v(q[3][0], y1, q[3][1], 0f, 1f, 0f, 0f, 1f), m.v(q[2][0], y1, q[2][1], 0f, 1f, 0f, 1f, 1f), m.v(q[1][0], y1, q[1][1], 0f, 1f, 0f, 1f, 0f))
    }
    for (r in 0 until roads.length()) {
        val o = roads.getJSONObject(r)
        val p = o.getJSONArray("pts")
        val n = p.length() / 5
        val half = o.getDouble("width").toFloat() / 2f
        val cls = o.getString("cls")
        val spans = o.getJSONArray("spans").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun spanAt(s: Float): String? = spans.firstOrNull { s >= it.getDouble("from") && s <= it.getDouble("to") }?.getString("kind")
        val m = Mesh()
        var prevL = -1; var prevR = -1; var prevSl = -1; var prevSr = -1
        var lastPier = -1e9f
        for (k in 0 until n) {
            val x = p.getDouble(k * 5).toFloat(); val z = p.getDouble(k * 5 + 1).toFloat(); val y = p.getDouble(k * 5 + 2).toFloat()
            val bank = p.getDouble(k * 5 + 3).toFloat(); val hd = p.getDouble(k * 5 + 4).toFloat()
            val s = k * 2f
            val kind = spanAt(s)
            if (kind == "tunnel") { prevL = -1; continue }
            val nxv = -sin(hd); val nzv = cos(hd)
            val lift = .15f
            val yl = y - bank * half + lift; val yr = y + bank * half + lift
            val l = m.v(x - nxv * half, yl, z - nzv * half, 0f, 1f, 0f, 0f, s / 16f)
            val rr = m.v(x + nxv * half, yr, z + nzv * half, 0f, 1f, 0f, 1f, s / 16f)
            val drop = if (kind != null) 1.6f else 2.5f
            val sl = m.v(x - nxv * half, yl - drop, z - nzv * half, -nxv, 0f, -nzv, 0f, s / 16f)
            val sr = m.v(x + nxv * half, yr - drop, z + nzv * half, nxv, 0f, nzv, 1f, s / 16f)
            if (prevL >= 0) { m.quad(prevL, l, rr, prevR); m.quad(prevSl, sl, l, prevL); m.quad(prevR, rr, sr, prevSr) }
            prevL = l; prevR = rr; prevSl = sl; prevSr = sr
            // piers: every 32 m on a bridge or viaduct, down to the ground (or the river bed under the sea line)
            if ((kind == "bridge" || kind == "viaduct") && s - lastPier >= 32f) {
                lastPier = s
                val gy = ground(x, z)
                if (y - 1.6f - gy > 1f) cube(x, z, gy - 1f, y - 1.6f, 1.4f, half * .8f, hd, box)
            }
        }
        upload(m, if (cls == "lane" || cls == "quay") cobbles else asphalt)
        report.appendLine("road ${o.getString("id")}: $n points")
    }
    upload(box, stone)
    // the moles and the pier
    val moles = Mesh(); val pier = Mesh(); val pav = Mesh()
    city.getJSONArray("moles").let { a -> for (k in 0 until a.length()) { val l = a.getJSONObject(k).getJSONArray("line"); val p0 = l.getJSONArray(0); val p1 = l.getJSONArray(1)
        val ax = p0.getDouble(0).toFloat(); val az = p0.getDouble(1).toFloat(); val bx = p1.getDouble(0).toFloat(); val bz = p1.getDouble(1).toFloat()
        cube((ax + bx) / 2, (az + bz) / 2, -4f, 4f, hypot(bx - ax, bz - az) / 2, 9f, kotlin.math.atan2(bz - az, bx - ax), moles) } }
    city.getJSONArray("pier").let { l ->
        val ax = l.getJSONArray(0).getDouble(0).toFloat(); val az = l.getJSONArray(0).getDouble(1).toFloat(); val bx = l.getJSONArray(1).getDouble(0).toFloat(); val bz = l.getJSONArray(1).getDouble(1).toFloat()
        val yaw = kotlin.math.atan2(bz - az, bx - ax); val len = hypot(bx - ax, bz - az)
        cube((ax + bx) / 2, (az + bz) / 2, 5.2f, 5.8f, len / 2, 3f, yaw, pier)
        var t = 0f
        while (t <= len) { val px = ax + (bx - ax) * t / len; val pz = az + (bz - az) * t / len; cube(px, pz, -6f, 5.2f, .3f, 2.6f, yaw, pier); t += 12f }
        cube(bx, bz, 5.8f, 11f, 14f, 10f, yaw, pav)
    }
    upload(moles, stone); upload(pier, timber); upload(pav, white)
    // the Sallow: a ribbon of water a metre over its bed, 24 m wide
    Mesh().also { m ->
        val rv = city.getJSONArray("river")
        var pl = -1; var pr = -1
        for (k in 0 until rv.length() - 1) {
            val a = rv.getJSONObject(k); val b = rv.getJSONObject(k + 1)
            val ax = a.getJSONArray("at").getDouble(0).toFloat(); val az = a.getJSONArray("at").getDouble(1).toFloat()
            val bx = b.getJSONArray("at").getDouble(0).toFloat(); val bz = b.getJSONArray("at").getDouble(1).toFloat()
            val ay = a.getDouble("y").toFloat(); val by = b.getDouble("y").toFloat()
            val len = hypot(bx - ax, bz - az); val nxv = -(bz - az) / len; val nzv = (bx - ax) / len
            var t = if (k == 0) 0f else 10f
            while (t <= len) {
                val x = ax + (bx - ax) * t / len; val z = az + (bz - az) * t / len; val y = max(0f, ay + (by - ay) * t / len) + 1f
                val l = m.v(x - nxv * 12, y, z - nzv * 12, 0f, 1f, 0f, (x - nxv * 12) / 24, (z - nzv * 12) / 24)
                val r = m.v(x + nxv * 12, y, z + nzv * 12, 0f, 1f, 0f, (x + nxv * 12) / 24, (z + nzv * 12) / 24)
                if (pl >= 0) m.quad(pl, l, r, pr)
                pl = l; pr = r; t += 10f
            }
        }
        upload(m, water)
    }
    report.appendLine("triangles: $triangles")

    // ---- shots
    val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    fun draw(): IntArray {
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
        return IntArray(w * h) { k -> val y = k / w; val x = k % w; val i = ((h - 1 - y) * w + x) * 4
            ((pixels.get(i + 3).toInt() and 255) shl 24) or ((pixels.get(i).toInt() and 255) shl 16) or ((pixels.get(i + 1).toInt() and 255) shl 8) or (pixels.get(i + 2).toInt() and 255) }
    }
    fun norm(a: FloatArray): FloatArray { val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun shoot(name: String, eye: FloatArray, at: FloatArray, fov: Float, what: String) {
        val f = norm(floatArrayOf(at[0] - eye[0], at[1] - eye[1], at[2] - eye[2]))
        // (a right-handed world, x east, z south: facing south, east is on the left)
        val r = norm(cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = cross(r, f)
        val cx = w / 2f; val cy = h / 2f
        val fl = cx / tan(Math.toRadians(fov / 2.0)).toFloat()
        val m = FloatArray(12)
        for (i in 0..2) { m[i] = fl * r[i] + cx * f[i]; m[4 + i] = -fl * u[i] + cy * f[i]; m[8 + i] = f[i] }
        m[3] = -(m[0] * eye[0] + m[1] * eye[1] + m[2] * eye[2]); m[7] = -(m[4] * eye[0] + m[5] * eye[1] + m[6] * eye[2]); m[11] = -(f[0] * eye[0] + f[1] * eye[1] + f[2] * eye[2])
        LookMatrix.pinhole(m, w.toFloat(), h.toFloat(), 2f, look)
        for (x in instances) x.setParameter("look", MaterialInstance.FloatElement.MAT4, look, 0, 1)
        val near = 2.0; val k = near / fl
        camera.setProjection(Camera.Projection.PERSPECTIVE, -cx * k, (w - cx) * k, -(h - cy) * k, cy * k, near, 40000.0)
        camera.lookAt(eye[0].toDouble(), eye[1].toDouble(), eye[2].toDouble(), at[0].toDouble(), at[1].toDouble(), at[2].toDouble(), 0.0, 1.0, 0.0)
        val t0 = System.nanoTime()
        val px = draw()
        val ms = (System.nanoTime() - t0) / 1e6
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val sr = (96 + 120 * t).toInt(); val sg = (150 + 80 * t).toInt(); val sb = (214 + 30 * t).toInt()
            for (x in 0 until w) {
                val p = px[y * w + x]; val a = p ushr 24
                fun ch(s: Int, b: Int) = (((p shr s) and 255) + b * (255 - a) / 255).coerceAtMost(255)
                img.setRGB(x, y, (ch(16, sr) shl 16) or (ch(8, sg) shl 8) or ch(0, min(255, sb)))
            }
        }
        javax.imageio.ImageIO.write(img, "png", File(dir, "$name.png"))
        report.appendLine("%-14s %s (%.0f ms, llvmpipe)".format(name, what, ms))
        println("  $name")
    }
    fun gy(x: Float, z: Float) = ground(x, z)
    shoot("aerial", floatArrayOf(-250f, 6200f, 4300f), floatArrayOf(-250f, 0f, -150f), 50f, "from high over the bay, looking north: the whole dove (Strand End and Lighthouse Point the wingtips, the moles the beak, the Ridge loop the tail)")
    shoot("hairpins", floatArrayOf(-820f, gy(-820f, -1050f) + 230f, -1020f), floatArrayOf(-510f, gy(-510f, -1260f), -1270f), 48f, "the Crown Road's four stacked hairpins from the west, over the gorge")
    shoot("viaduct", floatArrayOf(-1250f, 120f, -650f), floatArrayOf(-990f, 50f, -965f), 55f, "Fellside Viaduct over the Cleft, from the gorge's west rim")
    shoot("belvedere", floatArrayOf(1650f, gy(1650f, -1050f) + 110f, -1050f), floatArrayOf(1150f, 30f, 150f), 60f, "Ridge Drive's Belvedere bend, out over the bay")
    shoot("esplanade", floatArrayOf(-1000f, 28f, 560f), floatArrayOf(-2000f, 4f, 1380f), 55f, "the Esplanade's sweepers along the Long Strand, the pier off Pavilion Bend")
    shoot("headroad", floatArrayOf(2350f, 160f, 1900f), floatArrayOf(1900f, 70f, 1300f), 55f, "Head Road on the cliff tops of the Head")
    shoot("causeway", floatArrayOf(-2850f, 45f, 2150f), floatArrayOf(-3800f, 2f, 1720f), 55f, "the Marsh Causeway across Heron Marsh to the drawbridge")
    report.appendLine("(desktop renders, Mesa software GL; not S25 captures)")
    File(dir, "city2.txt").writeText(report.toString())
    print(report)
    engine.destroyRenderer(renderer); engine.destroySwapChain(swap)
    kotlin.system.exitProcess(0)
}
