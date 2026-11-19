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
            "coin_laundromat", "foundry_nightclub", "foundry_factory", "funeral_home"))
            assertTrue("$k listed", specs.any { it.kind == "landmark_glb_$k" })
        // a swatch re-skin replaces the picture at the same texel size
        val club = specs.first { it.kind.endsWith("mirage_nightclub") }
        assertEquals("ballast", club.swatches["roof_membrane"])
    }

    @Test fun theIronworksGlowsOnlyOutside() {
        // (the casting pit, hearth wall, tool wall and truss lamps inside are a plain picture: the stack's furnace louvres,
        // the signs and the yard lamps glow)
        val lm = build(specs.first { it.kind.endsWith("foundry_factory") })
        val i = lm.names.indexOf("detail_hall")
        assertTrue("the hall's glow pictures are built", i >= 0)
        assertTrue("and do not glow", lm.model.parts[i].role != CinemaPart.Role.SIGN && lm.model.parts[i].mesh.mask == null)
        assertTrue("the furnace glows", lm.model.parts[lm.names.indexOf("glow_furnace")].mesh.mask != null)
    }

    @Test fun theIronworksHasItsNamesAndItsChimney() {
        val s = specs.first { it.kind.endsWith("foundry_factory") }
        // four painted names, each file there and building to the same model
        assertEquals(4, s.variants.size)
        val tris = s.variants.map { f -> LandmarkKit.build(GlbFile.read(File(root, f).readBytes()), s, 1950, 7, info = s.info?.let { File(root, it).readText() }).model.triangles }
        assertTrue("same geometry", tris.distinct().size == 1)
        // its chimney stands over the club's square tower (what it was cut down to)
        val works = build(s).model.height / U; val club = build(specs.first { it.kind.endsWith("foundry_nightclub") }).model.height / U
        assertTrue("chimney $works m over the club's $club m", works > 15f && works > club + 3f)
    }

    @Test fun theFuneralHomeGlowsOnlyItsPanes() {
        // glass_home carries the doors' art as well as the panes: pane-emission.png (its emissive picture) limits the glow to the
        // panes, so the wooden doors stay dark (art-regions.json: home_window [0,0,24,30], front_leaf [0,40,12,34] in art pixels)
        val lm = build(specs.first { it.kind.endsWith("funeral_home") })
        val g = lm.model.parts[lm.names.indexOf("glass_home")].mesh
        val mask = g.mask!!
        fun lit(x0: Int, y0: Int, w: Int, h: Int): Int { var n = 0; for (y in y0 * 2 until (y0 + h) * 2) for (x in x0 * 2 until (x0 + w) * 2) if (mask[y * g.texW + x] != 0) n++; return n }
        assertTrue("the window's panes glow", lit(2, 2, 20, 26) > 20 * 26 * 4 / 2)
        for ((name, r) in mapOf("front_leaf" to intArrayOf(0, 40, 12, 34), "side_door" to intArrayOf(16, 40, 18, 32), "staff_door" to intArrayOf(38, 40, 16, 32),
            "chapel_leaf" to intArrayOf(58, 40, 12, 32), "garage_leaf" to intArrayOf(74, 40, 24, 38))) assertEquals("$name does not glow", 0, lit(r[0], r[1], r[2], r[3]))
        assertTrue("only a small part of the atlas glows", mask.count { it != 0 } < g.texW * g.texH / 8)
    }

    @Test fun theFuneralHomeHasItsDoorsAndBoxes() {
        val s = specs.first { it.kind.endsWith("funeral_home") }
        val lm = build(s)
        // five entrances from outside (the chapel's and the staff-room's inner doors are marked internal: no "Go inside" there yet)
        assertEquals(setOf("door_main", "door_arrival", "door_staff", "door_chapel_exit", "door_service_bay"), lm.doors.map { it.name }.toSet())
        val main = lm.doors.first { it.name == "door_main" }
        assertTrue("door_main faces the road", main.outZ > .99f)
        assertEquals("door_main stands 0.244 m past the front edge", .2444f * U, main.z, .01f)
        // the closed door panel's box (gate_node panel_door_main) stands in the doorway, behind the door's node
        fun box(cx: Float, cy: Float, sx: Float, sy: Float): Boolean {
            // (the info file's Blender centre and size, Z up, front -Y: glTF x = x, z = -y)
            var i = 0
            while (i + 3 < lm.solids.size) { val a = lm.solids; if (abs(a[i] - (cx - sx / 2) * U) < .05f && abs(a[i + 1] - (-cy - sy / 2) * U) < .05f && abs(a[i + 2] - (cx + sx / 2) * U) < .05f && abs(a[i + 3] - (-cy + sy / 2) * U) < .05f) return true; i += 4 }
            return false
        }
        assertTrue("closed main door", box(0f, -0.08924f, 1.8624f, 0.1552f))
        assertTrue("a portico column", box(-1.8624f, -1.8624f, 0.5432f, 0.5432f))
        assertTrue("the arrival lane's column", box(-10.864f, 2.1728f, 0.6208f, 0.6208f))
        assertTrue("the reception counter", box(2.328f, 2.1728f, 2.4832f, 1.0864f))
        assertTrue("the service bay's closed door", box(-2.4832f, 14.36764f, 3.7248f, 0.1552f))
        assertTrue("the garden bench (lot)", box(7.4496f, 11.1744f, 2.1728f, 0.776f))
        // the lot's boxes (shrubs, bench, bins) go with the lot
        val bare = build(s, keepLot = false)
        assertTrue("the lot's boxes go", bare.solids.size < lm.solids.size)
    }

    @Test fun theFuneralHomeHasSixPaintedFamilies() {
        val s = specs.first { it.kind.endsWith("funeral_home") }
        assertEquals(6, s.variants.size)
        val signs = s.variants.map { f ->
            val lm = LandmarkKit.build(GlbFile.read(File(root, f).readBytes()), s, 1950, 7, info = s.info?.let { File(root, it).readText() })
            lm.model.triangles to lm.model.parts[lm.names.indexOf("sign_name")].mesh.tex.toList()
        }
        assertTrue("same geometry", signs.map { it.first }.distinct().size == 1)
        assertEquals("each family has its own sign", 6, signs.map { it.second }.distinct().size)
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
