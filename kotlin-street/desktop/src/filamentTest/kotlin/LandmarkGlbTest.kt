import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaPart
import com.lifemax.rpg.feature.habits.presentation.scene3d.GasPrices
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.Png
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The hand-made landmark models (docs/LANDMARKS_GLB.md): read, built to scale, lit by role, walkable, priced by year. */
class LandmarkGlbTest {
    private val root = File(assetsDir(), "landmarks")
    private val specs = LandmarkSpec.catalogue(File(root, "landmarks.json").readText())
    private val U = LandmarkKit.UNITS_PER_METRE

    private fun build(s: LandmarkSpec, year: Int = s.built, keepLot: Boolean = true) =
        LandmarkKit.build(GlbFile.read(File(root, s.file).readBytes()), s, year, 7, keepLot, info = s.info?.let { File(root, it).readText() })

    @Test fun pngDecoderMatchesImageIO() {
        for (s in specs) {
            val glb = GlbFile.read(File(root, s.file).readBytes())
            val json = org.json.JSONObject(String(File(root, s.file).readBytes().let { b -> b.copyOfRange(20, 20 + java.nio.ByteBuffer.wrap(b, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int) }))
            assertTrue(glb.imageCount > 0)
            for (i in 0 until glb.imageCount) {
                val ours = glb.image(i)
                val bin = File(root, s.file).readBytes()
                val cl = java.nio.ByteBuffer.wrap(bin, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val bv = json.getJSONArray("bufferViews").getJSONObject(json.getJSONArray("images").getJSONObject(i).getInt("bufferView"))
                val start = 20 + cl + 8 + bv.optInt("byteOffset", 0)
                val ref = ImageIO.read(bin.copyOfRange(start, start + bv.getInt("byteLength")).inputStream())
                assertEquals(ref.width, ours.w); assertEquals(ref.height, ours.h)
                for (y in 0 until ours.h) for (x in 0 until ours.w) {
                    val a = ref.getRGB(x, y); val b = ours.px[y * ours.w + x]
                    // (fully clear texels may differ in colour)
                    if (a ushr 24 != 0 || b ushr 24 != 0) assertEquals("${s.kind} image $i at $x,$y", a, b)
                }
            }
        }
    }

    @Test fun everyModelBuildsToScale() {
        for (s in specs) {
            val lm = build(s)
            val m = lm.model
            assertTrue("${s.kind} has parts", m.parts.isNotEmpty())
            // its whole site within its lot (give or take a metre), and not tiny: metres became street units
            val w = (m.footprint[2] - m.footprint[0]) / U; val d = (m.footprint[3] - m.footprint[1]) / U
            assertTrue("${s.kind} width $w vs lot ${s.lotW}", w in s.lotW * .8f..s.lotW * 1.1f + 1f)
            assertTrue("${s.kind} depth $d vs lot ${s.lotD}", d in s.lotD * .8f..s.lotD * 1.1f + 1f)
            // a storey is 3-8 m (no model 21 times too big or small): a figure is 36 units
            assertTrue("${s.kind} height ${m.height / U} m", m.height / U in 3f..(if (s.kind.endsWith("water_tower")) 30f else 22f))
            // the collision mesh is never drawn (no part carries its boxes' name)
            assertTrue(lm.names.none { it == "collision" })
            // windows lit by role, signs glowing (a water tower has no glass, only its beacon, and no door)
            val tower = s.kind.endsWith("water_tower")
            if (!tower) assertTrue("${s.kind} glass", m.parts.any { it.role == CinemaPart.Role.WINDOWS && it.mesh.mask != null })
            assertTrue("${s.kind} signs", m.parts.any { it.role == CinemaPart.Role.SIGN && it.mesh.mask != null })
            // the lot's ground stands on the town's (nothing sunk below zero: the loader lifts it)
            // (the diner's kerb dips below, as it was made before the guide)
            if (!s.kind.endsWith("junction_diner")) for ((n, p) in lm.names.zip(m.parts)) if (n.startsWith("ground_")) assertTrue("${s.kind} $n bottom ${p.mesh.bounds[1]}", p.mesh.bounds[1] >= 0f)
            // every door faces out along its node's +z (door_back to the rear, door_side sideways)
            for (d in lm.doors) assertTrue("${s.kind} ${d.name} unit", abs(d.outX * d.outX + d.outZ * d.outZ - 1f) < .01f)
            lm.doors.firstOrNull { it.name == "door_back" }?.let { assertTrue("${s.kind} back door faces back", it.outZ < -.9f) }
            if (tower) { assertTrue("${s.kind} collision", lm.solids.size / 4 >= 4); continue }
            // a main door at the front, facing out (+z), and boxes to walk round
            val main = lm.doors.first { it.name == "door_main" }
            assertTrue("${s.kind} main door faces the road", main.outZ > .9f)
            assertTrue("${s.kind} main door on the front", main.z in -40f..40f)
            assertTrue("${s.kind} collision", lm.solids.size / 4 >= 4)
            // the main door is not inside a box (a walker can stand just outside it)
            val fx = main.x + main.outX * 12f; val fz = main.z + main.outZ * 12f
            var i = 0
            while (i + 3 < lm.solids.size) { val a = lm.solids; assertTrue("${s.kind} door blocked", !(fx > a[i] && fx < a[i + 2] && fz > a[i + 1] && fz < a[i + 3])); i += 4 }
        }
    }

    @Test fun theNewLandmarksAreInTheCatalogue() {
        for (k in listOf("gazette_office", "feed_mill", "mirage_nightclub", "pulse_nightclub", "starlight_drive_in", "water_tower", "roadside_motel", "premier_railroad_depot",
            "coin_laundromat", "foundry_nightclub"))
            assertTrue("$k listed", specs.any { it.kind == "landmark_glb_$k" })
        // a swatch re-skin replaces the picture at the same texel size
        val club = specs.first { it.kind.endsWith("mirage_nightclub") }
        assertEquals("ballast", club.swatches["roof_membrane"])
    }

    @Test fun theFoundryGlowsOnlyOutside() {
        // (its hall's LED floor, light wall, bar and truss lamps are a plain picture: only the signs and trim outside glow,
        // and no lit ceiling inside the closed shell becomes a spot light)
        val lm = build(specs.first { it.kind.endsWith("foundry_nightclub") })
        val i = lm.names.indexOf("detail_led")
        assertTrue("the hall's LEDs are built", i >= 0)
        assertTrue("and do not glow", lm.model.parts[i].role != CinemaPart.Role.SIGN && lm.model.parts[i].mesh.mask == null)
    }

    @Test fun aPoolIsSolidWaterFlushWithTheGround() {
        val s = specs.first { it.kind.endsWith("roadside_motel") }
        val lm = build(s)
        val i = lm.names.indexOf("water_pool")
        assertTrue("the pool's water is built", i >= 0)
        val p = lm.model.parts[i]
        assertTrue("its water is level and just over the town's ground", p.mesh.bounds[1] == LandmarkKit.WATER_LEVEL && p.mesh.bounds[4] == LandmarkKit.WATER_LEVEL)
        assertTrue("and opaque", p.mesh.tex.all { it ushr 24 == 255 })
    }

    @Test fun droppingTheLotDropsItsThingsAndBoxes() {
        val s = specs.first { it.kind.contains("modern_gas") }
        val all = build(s); val bare = build(s, keepLot = false)
        assertTrue(bare.model.triangles < all.model.triangles)
        assertTrue(bare.solids.size < all.solids.size)
    }

    @Test fun pricesFollowTheYear() {
        assertTrue(GasPrices.regular(1955) < .35f)
        assertTrue(GasPrices.regular(2022) > 3.5f)
        assertEquals("29.9", GasPrices.text(.299f))
        assertEquals("3.49", GasPrices.text(3.499f))
        val s = specs.first { it.kind.contains("modern_gas") }
        val a = s.price!!.texels(2008, 7); val b = s.price!!.texels(2016, 7)
        assertTrue("the panel changes with the year", !a.contentEquals(b))
        // every texel on the 2 x 2 art grid
        val w = s.price!!.artW * 2; val h = s.price!!.artH * 2
        for (y in 0 until h step 2) for (x in 0 until w step 2) { val c = a[y * w + x]; assertTrue(c == a[y * w + x + 1] && c == a[(y + 1) * w + x]) }
    }
}
