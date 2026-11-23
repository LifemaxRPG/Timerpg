import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.lifemax.rpg.feature.habits.presentation.scene3d.AnimatedNodePart
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAnimKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAnimRule
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAnimator
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LandmarkAnimationTest {
    private val U = LandmarkKit.UNITS_PER_METRE
    private val assetsRoot = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }
    private val landmarksRoot = File(assetsRoot, "landmarks")

    private fun testSpec(
        animations: List<LandmarkAnimRule> = emptyList(),
        clockStopped: String? = null,
        file: String = "test.glb",
    ) = LandmarkSpec(
        kind = "landmark_glb_test", name = "Test Landmark", file = file, info = null, role = "civic",
        from = 1890, to = 2025, built = 1920, lotW = 20f, lotD = 20f, buildingW = 12f, buildingD = 12f,
        animations = animations, clockStopped = clockStopped,
    )

    // Minimal valid .glb builder for synthetic unit tests (supports both TRS and 4x4 matrix transforms)
    private class SyntheticGlbBuilder {
        private val bin = ByteArrayOutputStream()
        private val bufferViews = JSONArray()
        private val accessors = JSONArray()
        private val meshes = JSONArray()
        private val nodes = JSONArray()
        private val materials = JSONArray()

        fun addMaterial(name: String, r: Float = 0.8f, g: Float = 0.8f, b: Float = 0.8f): Int {
            val idx = materials.length()
            materials.put(JSONObject()
                .put("name", name)
                .put("pbrMetallicRoughness", JSONObject().put("baseColorFactor", JSONArray(listOf(r, g, b, 1.0)))))
            return idx
        }

        private fun pad4() { while (bin.size() % 4 != 0) bin.write(0) }

        fun addBoxMesh(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float, matIdx: Int): Int {
            // Simple 2-triangle quad/box representation (12 triangles for a full box)
            val pos = floatArrayOf(
                // Front (+Z)
                minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ,
                // Back (-Z)
                maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ,
                // Top (+Y)
                minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ,
            )
            val nrm = floatArrayOf(
                0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
                0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f,
                0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
            )
            val idx = intArrayOf(
                0, 1, 2, 0, 2, 3,
                4, 5, 6, 4, 6, 7,
                8, 9, 10, 8, 10, 11,
            )
            pad4()
            val posOff = bin.size()
            val pb = ByteBuffer.allocate(pos.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in pos) pb.putFloat(f)
            bin.write(pb.array())
            val posBv = bufferViews.length()
            bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", posOff).put("byteLength", pos.size * 4))
            val posAcc = accessors.length()
            accessors.put(JSONObject().put("bufferView", posBv).put("componentType", 5126).put("count", pos.size / 3).put("type", "VEC3"))

            pad4()
            val nrmOff = bin.size()
            val nb = ByteBuffer.allocate(nrm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in nrm) nb.putFloat(f)
            bin.write(nb.array())
            val nrmBv = bufferViews.length()
            bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", nrmOff).put("byteLength", nrm.size * 4))
            val nrmAcc = accessors.length()
            accessors.put(JSONObject().put("bufferView", nrmBv).put("componentType", 5126).put("count", nrm.size / 3).put("type", "VEC3"))

            pad4()
            val idxOff = bin.size()
            val ib = ByteBuffer.allocate(idx.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in idx) ib.putShort(k.toShort())
            bin.write(ib.array())
            val idxBv = bufferViews.length()
            bufferViews.put(JSONObject().put("buffer", 0).put("byteOffset", idxOff).put("byteLength", idx.size * 2))
            val idxAcc = accessors.length()
            accessors.put(JSONObject().put("bufferView", idxBv).put("componentType", 5123).put("count", idx.size).put("type", "SCALAR"))

            val meshIdx = meshes.length()
            val prim = JSONObject()
                .put("attributes", JSONObject().put("POSITION", posAcc).put("NORMAL", nrmAcc))
                .put("indices", idxAcc)
                .put("material", matIdx)
            meshes.put(JSONObject().put("primitives", JSONArray().put(prim)))
            return meshIdx
        }

        fun addNode(
            name: String,
            mesh: Int = -1,
            translation: FloatArray? = null,
            rotation: FloatArray? = null,
            scale: FloatArray? = null,
            matrix: FloatArray? = null,
            children: IntArray? = null,
            extras: JSONObject? = null,
        ): Int {
            val idx = nodes.length()
            val o = JSONObject().put("name", name)
            if (mesh >= 0) o.put("mesh", mesh)
            if (translation != null) o.put("translation", JSONArray(translation.map { it.toDouble() }))
            if (rotation != null) o.put("rotation", JSONArray(rotation.map { it.toDouble() }))
            if (scale != null) o.put("scale", JSONArray(scale.map { it.toDouble() }))
            if (matrix != null) o.put("matrix", JSONArray(matrix.map { it.toDouble() }))
            if (children != null) o.put("children", JSONArray(children.toList()))
            if (extras != null) o.put("extras", extras)
            nodes.put(o)
            return idx
        }

        fun build(): GlbFile {
            pad4()
            val binBytes = bin.toByteArray()
            val root = JSONObject()
                .put("asset", JSONObject().put("version", "2.0"))
                .put("buffers", JSONArray().put(JSONObject().put("byteLength", binBytes.size)))
                .put("bufferViews", bufferViews)
                .put("accessors", accessors)
                .put("materials", materials)
                .put("meshes", meshes)
                .put("nodes", nodes)
            var jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
            val pad = (4 - jsonBytes.size % 4) % 4
            if (pad > 0) jsonBytes += ByteArray(pad) { 0x20 }
            val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67).putInt(2).putInt(total)
            out.putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
            out.putInt(binBytes.size).putInt(0x004E4942).put(binBytes)
            return GlbFile.read(out.array())
        }
    }

    /** Measures clockwise angle in degrees from 12 o'clock on the dial face using the transformed hand tip vector. */
    private fun dialAngleDeg(part: AnimatedNodePart, hour: Float): Float {
        val m = FloatArray(16)
        part.computeModelMatrix(hour, 0.0, 0f, 0f, m)
        val cb = part.clockBasis!!
        // Hand local +Y vector transformed to model space (column 1, normalized)
        val hx = m[4]; val hy = m[5]; val hz = m[6]
        val hl = sqrt(hx * hx + hy * hy + hz * hz)
        val nx = hx / hl; val ny = hy / hl; val nz = hz / hl
        val dot3 = nx * cb[0] + ny * cb[1] + nz * cb[2] // projection onto 3 o'clock (+x0)
        val dot12 = nx * cb[3] + ny * cb[4] + nz * cb[5] // projection onto 12 o'clock (+y0)
        var deg = Math.toDegrees(atan2(dot3.toDouble(), dot12.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        if (abs(deg - 360f) < 1e-3f) deg = 0f
        return deg
    }

    /** Transforms the local bounding box corners by [m] and returns their world-space center. */
    private fun transformedBboxCenter(lb: FloatArray, m: FloatArray): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val tmp = FloatArray(3)
        for (ix in 0..1) for (iy in 0..1) for (iz in 0..1) {
            val x = if (ix == 0) lb[0] else lb[3]
            val y = if (iy == 0) lb[1] else lb[4]
            val z = if (iz == 0) lb[2] else lb[5]
            GlbFile.point(m, x, y, z, tmp, 0)
            if (tmp[0] < minX) minX = tmp[0]; if (tmp[0] > maxX) maxX = tmp[0]
            if (tmp[1] < minY) minY = tmp[1]; if (tmp[1] > maxY) maxY = tmp[1]
            if (tmp[2] < minZ) minZ = tmp[2]; if (tmp[2] > maxZ) maxZ = tmp[2]
        }
        return floatArrayOf((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
    }

    @Test fun clockHandAnglesMatch300And430AndStopped417() {
        val b = SyntheticGlbBuilder()
        val bodyMat = b.addMaterial("wall_stone")
        val handMat = b.addMaterial("metal_dark")
        val tower = b.addBoxMesh(-2f, 0f, -2f, 2f, 20f, 2f, bodyMat)
        val hourMesh = b.addBoxMesh(-0.05f, -0.1f, -0.02f, 0.05f, 0.5f, 0.02f, handMat)
        val minMesh = b.addBoxMesh(-0.04f, -0.1f, -0.02f, 0.04f, 0.7f, 0.02f, handMat)
        b.addNode("tower", mesh = tower)
        // South dial (+Z) with a baked static 10:10 pose (should be ignored by live animation!)
        val qBaked = floatArrayOf(0f, 0f, sin(Math.toRadians(30.0)).toFloat(), cos(Math.toRadians(30.0)).toFloat())
        b.addNode("clock_hour", mesh = hourMesh, translation = floatArrayOf(0f, 15f, 2.1f), rotation = qBaked)
        b.addNode("clock_minute", mesh = minMesh, translation = floatArrayOf(0f, 15f, 2.15f), rotation = qBaked)
        // East dial (+X) rotated 90° about Y
        val qEast = floatArrayOf(0f, sin(Math.toRadians(45.0)).toFloat(), 0f, cos(Math.toRadians(45.0)).toFloat())
        b.addNode("clock_hour_east", mesh = hourMesh, translation = floatArrayOf(2.1f, 15f, 0f), rotation = qEast)
        b.addNode("clock_minute_east", mesh = minMesh, translation = floatArrayOf(2.15f, 15f, 0f), rotation = qEast)

        val glb = b.build()
        val live = LandmarkKit.build(glb, testSpec(), year = 1920)
        assertEquals(4, live.animated.size)
        for (suffix in listOf("", "_east")) {
            val hh = live.animated.first { it.nodeName == "clock_hour$suffix" }
            val mm = live.animated.first { it.nodeName == "clock_minute$suffix" }
            // 3:00 -> hour 90°, minute 0°
            assertEquals("3:00 hour$suffix", 90f, dialAngleDeg(hh, 3.0f), 0.1f)
            assertEquals("3:00 minute$suffix", 0f, dialAngleDeg(mm, 3.0f), 0.1f)
            // 4:30 -> hour 135°, minute 180°
            assertEquals("4:30 hour$suffix", 135f, dialAngleDeg(hh, 4.5f), 0.1f)
            assertEquals("4:30 minute$suffix", 180f, dialAngleDeg(mm, 4.5f), 0.1f)
        }

        // Stopped clock override ("4:17") always shows 4:17 regardless of game hour
        val stopped = LandmarkKit.build(glb, testSpec(clockStopped = "4:17"), year = 1988)
        val sh = stopped.animated.first { it.nodeName == "clock_hour" }
        val sm = stopped.animated.first { it.nodeName == "clock_minute" }
        val expectedH = (4f + 17f / 60f) * 30f // 128.5°
        val expectedM = 17f * 6f               // 102.0°
        for (testHour in listOf(0f, 3f, 9.75f, 18.2f)) {
            assertEquals("stopped hour at $testHour", expectedH, dialAngleDeg(sh, testHour), 0.1f)
            assertEquals("stopped minute at $testHour", expectedM, dialAngleDeg(sm, testHour), 0.1f)
        }
    }

    @Test fun pivotsStayFixedUnderBothTrsAndMatrixTransforms() {
        val b = SyntheticGlbBuilder()
        val mat = b.addMaterial("metal_bronze")
        val ringMesh = b.addBoxMesh(-2f, -2f, -2f, 2f, 2f, 2f, mat)
        val bodyMesh = b.addBoxMesh(-4f, 0f, -4f, 4f, 4f, 4f, mat)
        b.addNode("base", mesh = bodyMesh)
        // Node 1: TRS transform
        b.addNode(
            "armillary_ecliptic", mesh = ringMesh,
            translation = floatArrayOf(1.5f, 12.0f, -5.0f),
            rotation = floatArrayOf(0f, 0f, 0f, 1f),
            extras = JSONObject().put("rotation_axis", JSONArray(listOf(-0.39715, 0.91775, 0.0))),
        )
        // Node 2: 4x4 column-major matrix transform (rotation 30° around Y + translation (-3, 18, -7))
        val c = cos(Math.toRadians(30.0)).toFloat(); val s = sin(Math.toRadians(30.0)).toFloat()
        val mat4 = floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            -3f, 18f, -7f, 1f,
        )
        b.addNode("armillary_globe", mesh = ringMesh, matrix = mat4)

        val lm = LandmarkKit.build(b.build(), testSpec(), year = 1926)
        assertEquals(2, lm.animated.size)
        val out = FloatArray(16)
        for (part in lm.animated) {
            val pivotX = part.restModel[12]
            val pivotY = part.restModel[13]
            val pivotZ = part.restModel[14]
            for (h in listOf(0f, 4.2f, 11.7f, 18.9f, 23.5f)) {
                part.computeModelMatrix(h, 0.0, 0f, 0f, out)
                // Origin (pivot) unchanged
                assertEquals("${part.nodeName} pivot x", pivotX, out[12], 1e-3f)
                assertEquals("${part.nodeName} pivot y", pivotY, out[13], 1e-3f)
                assertEquals("${part.nodeName} pivot z", pivotZ, out[14], 1e-3f)
                // Symmetric bounding-box center remains at pivot after arbitrary rotation
                val center = transformedBboxCenter(part.localBounds, out)
                assertEquals("${part.nodeName} bbox cx", pivotX, center[0], 1e-2f)
                assertEquals("${part.nodeName} bbox cy", pivotY, center[1], 1e-2f)
                assertEquals("${part.nodeName} bbox cz", pivotZ, center[2], 1e-2f)
            }
        }
    }

    @Test fun allFiveAnimationKindsBehaveCorrectly() {
        val b = SyntheticGlbBuilder()
        val mWall = b.addMaterial("wall_stone")
        val mMetal = b.addMaterial("metal_bronze")
        val mGlass = b.addMaterial("glass_cathedral")
        b.addNode("plinth", mesh = b.addBoxMesh(-5f, 0f, -5f, 5f, 5f, 5f, mWall))
        // Fixed outer ring stays static!
        b.addNode("armillary_outer", mesh = b.addBoxMesh(-3f, -3f, -3f, 3f, 3f, 3f, mMetal),
            translation = floatArrayOf(0f, 10f, 0f), extras = JSONObject().put("motion", "fixed"))
        // Slow spin
        b.addNode("armillary_ecliptic", mesh = b.addBoxMesh(-2f, -1f, -2f, 2f, 1f, 2f, mMetal),
            translation = floatArrayOf(0f, 10f, 0f))
        // Empty sweep beam locator (gets synthetic glowing cone)
        b.addNode("beacon_beam", translation = floatArrayOf(0f, 30f, 0f),
            extras = JSONObject().put("rotation_period_s", 10.0).put("active", true))
        // Multiple bells out of phase
        val bellMesh = b.addBoxMesh(-0.4f, -1.2f, -0.4f, 0.4f, 0.1f, 0.4f, mMetal)
        b.addNode("bell_0", mesh = bellMesh, translation = floatArrayOf(-1f, 20f, 0f))
        b.addNode("bell_1", mesh = bellMesh, translation = floatArrayOf(1f, 20f, 0f))
        // Lift car with stops
        b.addNode("lift_car", mesh = b.addBoxMesh(-1f, 0f, -1f, 1f, 2.4f, 1f, mGlass),
            translation = floatArrayOf(4f, 5f, 0f),
            extras = JSONObject().put("stops_y_m", JSONArray(listOf(5.0, 15.0, 25.0))))

        val lm = LandmarkKit.build(b.build(), testSpec(), year = 1926)
        assertFalse("armillary_outer stays static", lm.animated.any { it.nodeName == "armillary_outer" })
        val beam = lm.animated.first { it.nodeName == "beacon_beam" }
        assertTrue("empty beacon_beam gets synthetic cone", beam.parts.isNotEmpty() && beam.triangles > 0)
        assertFalse("beacon hidden by day", beam.isVisible(0.0f))
        assertTrue("beacon visible at night", beam.isVisible(0.9f))

        // Lift dwells at stop 0 for 6 seconds, then rises towards higher stops
        val lift = lm.animated.first { it.nodeName == "lift_car" }
        assertEquals("lift waits at first stop at t=0", 0f, lift.liftOffsetY(0.0), 1e-2f)
        assertEquals("lift still waiting at t=5.5s", 0f, lift.liftOffsetY(5.5), 1e-2f)
        assertTrue("lift has risen by t=12s", lift.liftOffsetY(12.0) > 5f * U)
        assertTrue("lift reaches top stop", (0..60).any { t -> abs(lift.liftOffsetY(t.toDouble()) - 20f * U) < 1f })

        // Multiple bells have distinct phase offsets
        val b0 = lm.animated.first { it.nodeName == "bell_0" }
        val b1 = lm.animated.first { it.nodeName == "bell_1" }
        assertTrue("bells are out of phase", abs(b0.swingPhaseRad - b1.swingPhaseRad) > 0.5f)
    }

    @Test fun staticOnlyModelsAreUnchangedAndZeroExistingLandmarksBreak() {
        val b = SyntheticGlbBuilder()
        val mWall = b.addMaterial("wall_brick")
        val mRoof = b.addMaterial("roof_slate")
        b.addNode("walls", mesh = b.addBoxMesh(-4f, 0f, -4f, 4f, 6f, 4f, mWall))
        b.addNode("roof", mesh = b.addBoxMesh(-4.5f, 6f, -4.5f, 4.5f, 7f, 4.5f, mRoof))
        val lm = LandmarkKit.build(b.build(), testSpec(), year = 1950)
        assertTrue("no animated parts", lm.animated.isEmpty())
        assertEquals("exact 2 static parts", 2, lm.model.parts.size)
        assertEquals("exact 12 triangles (6 per box)", 12, lm.model.triangles)

        // Verify every existing catalogue landmark loads cleanly with zero animated nodes
        val specs = LandmarkSpec.catalogue(File(landmarksRoot, "landmarks.json").readText())
        for (s in specs) {
            val built = LandmarkKit.build(
                GlbFile.read(File(landmarksRoot, s.file).readBytes()),
                s, s.built, seed = 7, info = s.info?.let { File(landmarksRoot, it).readText() },
            )
            assertTrue("${s.kind} stays purely static", built.animated.isEmpty())
            assertTrue("${s.kind} has static triangles", built.model.triangles > 0)
        }
    }

    @Test fun perFrameUpdateAllocatesZeroBytes() {
        val b = SyntheticGlbBuilder()
        val mStone = b.addMaterial("wall_stone")
        val mMetal = b.addMaterial("metal_bronze")
        b.addNode("tower", mesh = b.addBoxMesh(-2f, 0f, -2f, 2f, 20f, 2f, mStone))
        b.addNode("clock_hour", mesh = b.addBoxMesh(-0.05f, -0.1f, -0.02f, 0.05f, 0.5f, 0.02f, mMetal), translation = floatArrayOf(0f, 15f, 2.1f))
        b.addNode("clock_minute", mesh = b.addBoxMesh(-0.04f, -0.1f, -0.02f, 0.04f, 0.7f, 0.02f, mMetal), translation = floatArrayOf(0f, 15f, 2.15f))
        b.addNode("armillary_ecliptic", mesh = b.addBoxMesh(-2f, -1f, -2f, 2f, 1f, 2f, mMetal), translation = floatArrayOf(0f, 10f, 0f))
        b.addNode("beacon_beam", translation = floatArrayOf(0f, 25f, 0f))
        b.addNode("bell_0", mesh = b.addBoxMesh(-0.3f, -1f, -0.3f, 0.3f, 0.1f, 0.3f, mMetal), translation = floatArrayOf(0f, 18f, 0f))
        b.addNode("lift_car", mesh = b.addBoxMesh(-1f, 0f, -1f, 1f, 2.4f, 1f, mMetal),
            translation = floatArrayOf(3f, 5f, 0f), extras = JSONObject().put("stops_y_m", JSONArray(listOf(5.0, 15.0))))
        val lm = LandmarkKit.build(b.build(), testSpec(), year = 1926)

        Filament.init()
        val engine = Engine.create(Engine.Backend.OPENGL)
        val scene = engine.createScene()
        val filDir = File(assetsRoot, "filament")
        fun loadMat(n: String): Material {
            val bytes = File(filDir, "$n.filamat").readBytes()
            return Material.Builder().payload(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip(), bytes.size).build(engine)
        }
        val lit = loadMat("street_lit")
        val proxy = loadMat("shadow_proxy")
        val props = PropLayer(engine, scene, lit, proxy)
        val cinema = CinemaLayer(props)
        val at = floatArrayOf(0f, lm.front, 1f, 0f, 0f, -1f, 0f)
        cinema.add(lm.model, at)
        val animator = LandmarkAnimator.add(props, cinema, lm, at)!!
        animator.ringBells(0.0, swings = 5)

        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val look = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        // Warm up HotSpot tiered JIT (past Tier-3/Tier-4 thresholds) and every per-frame code path:
        // full lift dwell+ride cycles, bell active/ease-out/rest phases, hour crossings, and night/day toggles,
        // ending in the steady-state night regime so C2 does not deoptimize mid-measurement.
        for (i in 0 until 5000) {
            val t = i * 0.05
            val dark = if (i < 4000 && i % 50 == 0) 0.0f else 0.9f
            animator.update(12f + i * 0.003f, t, dark, active = true)
            props.update(look, 0.9f, shown = true)
            cinema.update(0.9f, t)
        }
        // Prime MXBean lookup before the measured window and re-trigger bells so bells swing during measurement
        animator.ringBells(4.0, swings = 5)
        bean.getThreadAllocatedBytes(tid)

        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until 500) {
            val t = 4.0 + i * 0.016
            animator.update(14f + i * 0.005f, t, 0.9f, active = true)
            props.update(look, 0.9f, shown = true)
            cinema.update(0.9f, t)
        }
        val after = bean.getThreadAllocatedBytes(tid)
        val allocBytes = after - before

        props.destroy()
        engine.destroyMaterial(lit)
        engine.destroyMaterial(proxy)
        engine.destroyScene(scene)
        engine.destroy()

        assertEquals("per-frame update must allocate 0 bytes across 500 frames", 0L, allocBytes)
    }

    @Test fun realPrototypeCourthouseAndGreatMeridianIfPresent() {
        val chDir = File("/tmp/test-models/county-courthouse")
        val gmDir = File("/tmp/test-models/great-meridian-monument")
        if (!chDir.isDirectory || !gmDir.isDirectory) return

        val chInfo = File(chDir, "model-info.json").readText()
        val ch1894Spec = testSpec(file = "courthouse-1894.glb")
        val ch1894 = LandmarkKit.build(GlbFile.read(File(chDir, "courthouse-1894.glb").readBytes()), ch1894Spec, year = 1894, info = chInfo)
        val chHands = ch1894.animated.filter { it.kind == LandmarkAnimKind.CLOCK_HOUR || it.kind == LandmarkAnimKind.CLOCK_MINUTE }
        assertEquals("courthouse has 8 clock hands across 4 faces", 8, chHands.size)
        assertTrue("courthouse has swinging bell", ch1894.animated.any { it.nodeName == "bell" && it.kind == LandmarkAnimKind.SWING })
        for (face in listOf("", "_north", "_east", "_west")) {
            val h = ch1894.animated.first { it.nodeName == "clock_hour$face" }
            val m = ch1894.animated.first { it.nodeName == "clock_minute$face" }
            assertEquals("1894 3:00 hour$face", 90f, dialAngleDeg(h, 3.0f), 0.1f)
            assertEquals("1894 3:00 min$face", 0f, dialAngleDeg(m, 3.0f), 0.1f)
            assertEquals("1894 4:30 hour$face", 135f, dialAngleDeg(h, 4.5f), 0.1f)
            assertEquals("1894 4:30 min$face", 180f, dialAngleDeg(m, 4.5f), 0.1f)
        }

        // 1988 courthouse: clock stopped at 4:17
        val ch1988Spec = testSpec(file = "courthouse-1988.glb")
        val ch1988 = LandmarkKit.build(GlbFile.read(File(chDir, "courthouse-1988.glb").readBytes()), ch1988Spec, year = 1988, info = chInfo)
        val h88 = ch1988.animated.first { it.nodeName == "clock_hour" }
        val m88 = ch1988.animated.first { it.nodeName == "clock_minute" }
        assertEquals("1988 stopped hour at 9:00", 128.5f, dialAngleDeg(h88, 9.0f), 0.1f)
        assertEquals("1988 stopped minute at 9:00", 102.0f, dialAngleDeg(m88, 9.0f), 0.1f)

        // Great Meridian 1926: outer ring static, ecliptic & globe spin, beacon active at night, lift moves
        val gmInfo = File(gmDir, "model-info.json").readText()
        val gm1926 = LandmarkKit.build(GlbFile.read(File(gmDir, "great-meridian-1926.glb").readBytes()), testSpec(file = "great-meridian-1926.glb"), year = 1926, info = gmInfo)
        assertFalse("armillary_outer stays static", gm1926.animated.any { it.nodeName == "armillary_outer" })
        assertTrue("armillary_ecliptic animates", gm1926.animated.any { it.nodeName == "armillary_ecliptic" && it.kind == LandmarkAnimKind.SLOW_SPIN })
        assertTrue("armillary_globe animates", gm1926.animated.any { it.nodeName == "armillary_globe" && it.kind == LandmarkAnimKind.SLOW_SPIN })
        val gmBeam26 = gm1926.animated.first { it.nodeName == "beacon_beam" }
        assertTrue("1926 beacon active at night", gmBeam26.isVisible(0.9f))
        assertFalse("1926 beacon off by day", gmBeam26.isVisible(0.0f))
        assertTrue("lift_car animates", gm1926.animated.any { it.nodeName == "lift_car" && it.kind == LandmarkAnimKind.LIFT })

        // Great Meridian 1976: beacon inactive even at night
        val gm1976 = LandmarkKit.build(GlbFile.read(File(gmDir, "great-meridian-1976.glb").readBytes()), testSpec(file = "great-meridian-1976.glb"), year = 1976, info = gmInfo)
        val gmBeam76 = gm1976.animated.first { it.nodeName == "beacon_beam" }
        assertFalse("1976 beacon inactive at night", gmBeam76.isVisible(0.9f))
    }
}
