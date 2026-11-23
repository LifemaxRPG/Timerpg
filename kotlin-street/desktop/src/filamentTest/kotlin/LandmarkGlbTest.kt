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

    /** The roles of the intake models (docs/queue/INTAKE.md) whose doors may stand back from the lot's front. */
    private val INTAKE_ROLES = setOf("monument", "roadside_attraction", "rest_area", "lodge", "church", "school", "library", "bank", "drugstore", "carhop", "fire_station", "post_office")

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
        val bad = ArrayList<String>()
        fun chk(m: String, c: Boolean) { if (!c) bad += m }
        for (s in specs) {
            val lm = build(s)
            val m = lm.model
            chk("${s.kind} has parts", m.parts.isNotEmpty())
            // its whole site within its lot (give or take a metre), and not tiny: metres became street units
            val w = (m.footprint[2] - m.footprint[0]) / U; val d = (m.footprint[3] - m.footprint[1]) / U
            chk("${s.kind} width $w vs lot ${s.lotW}", w in s.lotW * .8f..s.lotW * 1.1f + 1f)
            chk("${s.kind} depth $d vs lot ${s.lotD}", d in s.lotD * .8f..s.lotD * 1.1f + 1f)
            // a storey is 3-8 m (no model 21 times too big or small): a figure is 36 units
            // (the courthouse's clock tower and spire: 38 m)
            // (the monuments: the Great Meridian's spire is 106 m, the Eyrie's wings 113 m; the school's tower 21 m, the Night Keeper 34 m)
            chk("${s.kind} height ${m.height / U} m", m.height / U in 3f..(if (s.kind.endsWith("water_tower")) 30f else if (s.role == "courthouse") 40f else if (s.role == "monument") 120f else if (s.role == "roadside_attraction" || s.role == "church") 36f else 22f))
            // the collision mesh is never drawn (no part carries its boxes' name)
            chk("${s.kind} draws its collision mesh", lm.names.none { it == "collision" })
            // windows lit by role, signs glowing (a water tower has no glass, only its beacon, and no door)
            val tower = s.kind.endsWith("water_tower")
            // (the Eyrie, the arch and the Rex have no glass: bronze wings, stone and a dinosaur; the closed carhop's windows are boarded)
            val noGlass = tower || s.kind.endsWith("carhop_drive_in_closed") || s.kind.endsWith("the_eyrie") || s.kind.endsWith("union_arch") || s.kind.endsWith("beach_blvd_rex")
            if (!noGlass) chk("${s.kind} glass", m.parts.any { it.role == CinemaPart.Role.WINDOWS && it.mesh.mask != null })
            chk("${s.kind} signs", m.parts.any { it.role == CinemaPart.Role.SIGN && it.mesh.mask != null })
            // the lot's ground stands on the town's (nothing sunk below zero: the loader lifts it)
            // (the diner's kerb dips below, as it was made before the guide)
            // (a thin slab laid at the town's ground, the Rex's asphalt 4 cm thick, has its underside below it: its top stands up)
            if (!s.kind.endsWith("junction_diner")) for ((n, p) in lm.names.zip(m.parts)) if (n.startsWith("ground_")) chk("${s.kind} $n bottom ${p.mesh.bounds[1]}", p.mesh.bounds[1] >= 0f || p.mesh.bounds[4] >= LandmarkKit.GROUND - .05f)
            // every door faces out along its node's +z (door_back to the rear, door_side sideways)
            for (d in lm.doors) chk("${s.kind} ${d.name} unit", abs(d.outX * d.outX + d.outZ * d.outZ - 1f) < .01f)
            lm.doors.firstOrNull { it.name == "door_back" }?.let { chk("${s.kind} back door faces back", it.outZ < -.9f) }
            if (tower || lm.doors.isEmpty()) { chk("${s.kind} collision", lm.solids.size / 4 >= 4); continue }
            // a main door at the front, facing out (+z), and boxes to walk round
            val main = lm.doors.first { it.name == "door_main" }
            // (the corner drugstore's door cuts its corner: it faces out at 45 degrees)
            chk("${s.kind} main door faces the road", main.outZ > (if (s.role == "drugstore") .6f else .9f))
            // (the courthouse stands back on its square, its lawn and walks before it)
            // (and the radio station's glazed entrance bay projects ~2.5 m before its brick front)
            // (the monuments and roadside models stand back in their lots: their door is at the building, up to the lot's depth)
            chk("${s.kind} main door on the front", main.z in (if (s.role == "courthouse") -40f * U..0f else if (s.role in INTAKE_ROLES) -s.lotD * U..3f * U else -40f..3f * U))
            chk("${s.kind} collision", lm.solids.size / 4 >= 4)
            // the main door is not inside a box (a walker can stand just outside it)
            val fx = main.x + main.outX * 12f; val fz = main.z + main.outZ * 12f
            var i = 0
            while (i + 3 < lm.solids.size) { val a = lm.solids; chk("${s.kind} door blocked", !(fx > a[i] && fx < a[i + 2] && fz > a[i + 1] && fz < a[i + 3])); i += 4 }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theNewLandmarksAreInTheCatalogue() {
        for (k in listOf("gazette_office", "feed_mill", "mirage_nightclub", "pulse_nightclub", "starlight_drive_in", "water_tower", "roadside_motel", "premier_railroad_depot",
            "coin_laundromat", "foundry_nightclub", "foundry_factory", "funeral_home",
            "reel_time_video_1984", "reel_time_video_1996", "reel_time_video_2011", "reel_time_video_2020",
            "county_courthouse_1894", "county_courthouse_1962", "county_courthouse_1988", "county_courthouse_2008", "radio_station"))
            assertTrue("$k listed", specs.any { it.kind == "landmark_glb_$k" })
        // a swatch re-skin replaces the picture at the same texel size
        val club = specs.first { it.kind.endsWith("mirage_nightclub") }
        assertEquals("ballast", club.swatches["roof_membrane"])
    }

    @Test fun theVideoStoreSharesOneShellInFourStates() {
        // the four states stand on one plot: same lot, building, origin and door nodes, each with its own boxes and signs
        val states = listOf(1984, 1996, 2011, 2020).map { y -> specs.first { it.kind.endsWith("reel_time_video_$y") } }
        val lms = states.map { build(it) }
        for (lm in lms) {
            assertEquals(lms[0].model.footprint.toList(), lm.model.footprint.toList())
            assertEquals(lms[0].doors.map { Triple(it.name, it.x, it.z) }, lm.doors.map { Triple(it.name, it.x, it.z) })
            assertEquals(setOf("door_main", "door_back"), lm.doors.map { it.name }.toSet())
            assertTrue("11 materials", lm.names.size <= 12)
        }
        assertEquals(listOf("Reel Time Video", "Reel Time Video", "Reel Time Video", "Cell Fix"), states.map { it.name })
        // door_main stands 0.155 m past the front edge, outside every box
        for ((s, lm) in states.zip(lms)) {
            val main = lm.doors.first { it.name == "door_main" }
            assertEquals(s.kind, .1552f * U, main.z, .01f)
        }
        // 1996 has the curb drop box (the 1984 has none), and the shelves differ between the stocked and the closing-down store
        assertTrue("states differ in boxes", lms.map { it.solids.size }.distinct().size >= 3)
    }

    @Test fun theVideoStoreGlowsOnlyItsSignsBulbsAndGlass() {
        // glow_signs has a separate glow mask (black but for the lit sign faces and the walkway bulbs, values 90-145 of 255): it is lit
        // wherever the mask is not dark, and the unlit signs and the rest of the picture stay dark (the old rule's bar of 150 lit nothing)
        for (y in listOf(1984, 1996, 2011, 2020)) {
            val s = specs.first { it.kind.endsWith("reel_time_video_$y") }
            val glb = GlbFile.read(File(root, s.file).readBytes())
            val lm = build(s)
            val mat = glb.materials.first { it.name == "glow_signs" }
            val base = glb.image(mat.baseImage)
            val em = glb.image(mat.emissiveImage)
            val g = lm.model.parts[lm.names.indexOf("glow_signs")].mesh
            val mask = g.mask!!
            fun lum(c: Int) = ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 4 + (c and 255)) / 8
            var want = 0; var darkLit = 0
            for (i in em.px.indices) {
                val lit = lum(em.px[i]) > 40 && base.px[i] ushr 24 >= 128
                if (lit) want++
                if (!lit && mask[i] != 0) darkLit++
            }
            val got = mask.count { it != 0 }
            assertTrue("$y: signs and bulbs glow (got $got)", got > 200)
            assertEquals("$y: exactly the mask's texels glow", want, got)
            assertEquals("$y: nothing dark glows", 0, darkLit)
            assertTrue("$y: only a small part of the picture glows ($got of ${mask.size})", got < mask.size / 6)
            // the glass glows as lit panes too, but not the whole atlas's frames
            val gl = lm.model.parts[lm.names.indexOf("glass_storefront")].mesh
            assertTrue("$y: glass is lit", gl.mask!!.any { it != 0 })
        }
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

    @Test fun theCourthouseStandsInFourStatesOnOneSquare() {
        val states = listOf(1894, 1962, 1988, 2008).map { y -> specs.first { it.kind == "landmark_glb_county_courthouse_$y" } }
        for (s in states) {
            assertEquals("the whole square", 79.46f, s.lotW, .01f); assertEquals(79.46f, s.lotD, .01f)
            assertEquals("its distant silhouette", "county-courthouse/courthouse-far.glb", s.far)
            val lm = build(s)
            // four entrances from outside; the rooms' inner doors are marked internal (no "Go inside" from the street)
            assertEquals(setOf("door_main", "door_north", "door_east", "door_west"), lm.doors.map { it.name }.toSet())
            assertTrue("door_main faces the road", lm.doors.first { it.name == "door_main" }.outZ > .99f)
            assertTrue("${s.kind} collision", lm.solids.size / 4 >= 40)
        }
        // the far model: the same origin and lot, a few hundred triangles
        val far = LandmarkKit.build(GlbFile.read(File(root, states[0].far!!).readBytes()), states[0], 1894, 7)
        assertTrue("far is cheap: ${far.model.triangles}", far.model.triangles < 400)
        val near = build(states[0]).model
        assertEquals("far shares the lot", near.footprint[0], far.model.footprint[0], 3f * U)
    }

    @Test fun theCourthouseClockTellsTheGamesTime() {
        val s = specs.first { it.kind == "landmark_glb_county_courthouse_2008" }
        val info = File(root, s.info!!).readText()
        val glb = GlbFile.read(File(root, s.file).readBytes())
        fun hands(h: Float?): List<Float> = LandmarkKit.build(glb, s, 2008, 7, info = info, clockHours = h).model.parts.flatMap { it.mesh.pos.toList() }
        fun moved(a: List<Float>, b: List<Float>): Float = a.zip(b).maxOf { (p, q) -> abs(p - q) }
        // at its own 10:10 nothing moves; at 3:00 the hands have turned
        assertTrue(moved(hands(null), hands(10f + 10f / 60f)) < .05f)
        assertTrue(moved(hands(null), hands(3f)) > 5f)
        // the stopped clock of the neglected years keeps its 4:17 whatever the time
        val s88 = specs.first { it.kind == "landmark_glb_county_courthouse_1988" }
        val g88 = GlbFile.read(File(root, s88.file).readBytes()); val i88 = File(root, s88.info!!).readText()
        assertTrue(moved(LandmarkKit.build(g88, s88, 1988, 7, info = i88).model.parts.flatMap { it.mesh.pos.toList() },
            LandmarkKit.build(g88, s88, 1988, 7, info = i88, clockHours = 3f).model.parts.flatMap { it.mesh.pos.toList() }) == 0f)
    }

    @Test fun theRadioStationHasSixNamesAndThreeInnerDoors() {
        val s = specs.first { it.kind == "landmark_glb_radio_station" }
        assertEquals(6, s.variants.size)
        val lm = build(s)
        // three entrances from outside (the control room's, the studio's and the booth's doors are inner ones)
        assertEquals(3, lm.doors.size)
        assertTrue(lm.doors.any { it.name == "door_main" })
        val signs = s.variants.map { f -> LandmarkKit.build(GlbFile.read(File(root, f).readBytes()), s, 1955, 7, info = s.info?.let { File(root, it).readText() }).let { it.model.parts[it.names.indexOf("sign_name")].mesh.tex.toList() } }
        assertEquals("each town's station its own nameplate", 6, signs.distinct().size)
    }
    // ------------------------------------------------------------------------------------------------ the intake models (docs/queue/INTAKE.md)
    private val INTAKE = listOf(
        "public_library_1912", "public_library_1970s", "corner_drugstore_family", "corner_drugstore_retail", "corner_drugstore_closed", "corner_drugstore_cafe",
        "small_town_bank_1920s", "small_town_bank_drive_through", "brick_church", "country_church",
        "lincoln_high_school_1925", "lincoln_high_school_1955", "lincoln_high_school_1975", "lincoln_high_school_2000",
        "carhop_drive_in_original", "carhop_drive_in_closed", "carhop_drive_in_restored", "fire_station_1924", "fire_station_1988", "post_office_1938", "post_office_1988",
        "highway_rest_area", "lantern_pine_lodge", "the_eyrie", "union_arch", "night_keeper", "beach_blvd_rex",
        "great_meridian_1926", "great_meridian_1941", "great_meridian_1976", "great_meridian_2000")

    @Test fun theIntakeModelsAreInTheCatalogue() {
        for (k in INTAKE) assertTrue("$k listed", specs.any { it.kind == "landmark_glb_$k" })
        // a chain of states that differ in size keeps one building place (the slot's middle is the biggest state's)
        for (chain in listOf(listOf("public_library_1912", "public_library_1970s"), listOf("small_town_bank_1920s", "small_town_bank_drive_through"))) {
            val c = chain.map { k -> specs.first { it.kind == "landmark_glb_$k" } }
            assertEquals(1, c.map { it.centre }.distinct().size)
            assertTrue("${chain[0]} has a centre", c[0].centre != null)
        }
        // the school and the Great Meridian draw a distant silhouette past the town's range
        for (k in listOf("lincoln_high_school_2000", "great_meridian_2000")) assertTrue(specs.first { it.kind == "landmark_glb_$k" }.far != null)
    }

    @Test fun theIntakeBoxesAreReadInTheirOwnAxes() {
        // glTF Y-up lists (`boxes_gltf_y_up`: the fire station, the library, the Great Meridian) are read as they are; Blender Z-up lists (the Rex's
        // `boxes_blender_z_up`, the rest area's and the lodge's) are turned: the Rex's info also lists the same boxes in glTF axes
        val rexInfo = File(root, "beach-blvd-rex/model-info.json").readText()
        val ref = org.json.JSONObject(rexInfo).getJSONArray("collision_boxes")
        val tags = LandmarkKit.collisionTags(rexInfo)
        assertTrue(tags.isNotEmpty())
        for (i in 0 until ref.length()) {
            val c = ref.getJSONObject(i).getJSONArray("center"); val t = tags[i]
            assertEquals("rex box $i x", c.getDouble(0).toFloat(), t.x, .001f); assertEquals("rex box $i y", c.getDouble(1).toFloat(), t.y, .001f); assertEquals("rex box $i z", c.getDouble(2).toFloat(), t.z, .001f)
        }
        val fire = specs.first { it.kind.endsWith("fire_station_1924") }
        val ft = LandmarkKit.collisionTags(File(root, fire.info!!).readText())
        assertTrue("the fire station's boxes are read", ft.size >= 8)
        // (the flagpole stands in front, +z, and its box is 9.9 m tall: read as glTF it is at z +4, not at -4)
        assertTrue(ft.any { it.source == "flagpole" && it.z > 3f && it.sy > 9f })
    }

    @Test fun theIntakeDoorsWorkAndTheInnerOnesAreMarked() {
        // the school's 2000 state marks door_main internal in its info (a security vestibule): the street still enters by it; its classrooms' doors are inner
        val s2000 = specs.first { it.kind.endsWith("lincoln_high_school_2000") }
        val lm = build(s2000)
        assertTrue(lm.doors.any { it.name == "door_main" })
        assertTrue(lm.doors.none { it.name.startsWith("door_room") })
        // the carhop's prep door, the Great Meridian's stair, gallery and lift doors are inner; its main door stands clear of every box in all four states
        assertTrue(build(specs.first { it.kind.endsWith("carhop_drive_in_original") }).doors.none { it.name == "door_prep_inside" })
        for (y in listOf(1926, 1941, 1976, 2000)) {
            val g = build(specs.first { it.kind.endsWith("great_meridian_$y") })
            assertEquals(setOf("door_main") + (if (y == 2000) setOf("door_visitor_center") else emptySet()), g.doors.map { it.name }.toSet())
        }
        // every closed door panel stays closed (none of the intake models has one: their entrances are open)
        for (k in INTAKE) assertTrue("$k has no door panel", build(specs.first { it.kind == "landmark_glb_$k" }).names.none { it.startsWith("door_panel") })
    }

    @Test fun theIntakeGlassGlowsOnlyItsPanes() {
        // pane-only emission: a glass picture's lit texels (its mask, or the see-through ones) never light a frame or a door
        val bad = ArrayList<String>()
        for (k in INTAKE) {
            val s = specs.first { it.kind == "landmark_glb_$k" }
            val lm = build(s)
            val glb = GlbFile.read(File(root, s.file).readBytes())
            for ((n, part) in lm.names.zip(lm.model.parts)) {
                if (!n.startsWith("glass_")) continue
                val mask = part.mesh.mask ?: continue
                val m = glb.materials.first { it.name == n }
                if (m.baseImage < 0) continue
                val base = glb.image(m.baseImage)
                if (base.px.none { it ushr 24 < 250 }) continue   // an opaque pane picture: its frame rule is the loader's
                var stray = 0; var lit = 0
                for (i in mask.indices) if (mask[i] != 0) { lit++; if (base.px[i] ushr 24 >= 250) stray++ }
                if (stray * 50 > lit) bad += "$k $n: $stray of $lit lit texels are not panes"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun aSunkenPatchOfGroundIsLifted() {
        // the Rex's asphalt strip lies under its pavement: after the lift nothing of its ground is below the town's
        val lm = build(specs.first { it.kind.endsWith("beach_blvd_rex") })
        for ((n, p) in lm.names.zip(lm.model.parts)) if (n.startsWith("ground_")) assertTrue("$n ${p.mesh.bounds[4]}", p.mesh.bounds[4] >= LandmarkKit.GROUND - .05f)
    }
}
