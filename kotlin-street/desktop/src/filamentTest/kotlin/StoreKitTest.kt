import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaPart
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreNames
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreReuse
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreState
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSiteInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSitePlaceholder
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreTraffic
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreCars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The stores: every kind and state stands on the ground inside its plot, at real scale, with small pictures; names are made up. */
class StoreKitTest {
    private val variants = listOf(
        StoreInfo.sample(StoreKind.GENERAL, 1925, seed = 4),
        StoreInfo.sample(StoreKind.GENERAL, 1955, seed = 5),
        StoreInfo.sample(StoreKind.GENERAL, 1940, seed = 2),
        StoreInfo.sample(StoreKind.GENERAL, 1995, seed = 4, state = StoreState.CLOSED),
        StoreInfo.sample(StoreKind.GENERAL, 2010, seed = 4, state = StoreState.REUSED, reuse = StoreReuse.ANTIQUES),
        StoreInfo.sample(StoreKind.GENERAL, 2000, seed = 7, state = StoreState.REUSED, reuse = StoreReuse.CHURCH),
        StoreInfo.sample(StoreKind.GENERAL, 2022, seed = 9, state = StoreState.REUSED, reuse = StoreReuse.CAFE),
        StoreInfo.sample(StoreKind.MARKET, 1956, seed = 3),
        StoreInfo.sample(StoreKind.MARKET, 1966, seed = 6),
        StoreInfo.sample(StoreKind.MARKET, 1984, seed = 3, state = StoreState.CLOSED),
        StoreInfo.sample(StoreKind.MARKET, 1995, seed = 3, state = StoreState.REUSED, reuse = StoreReuse.THRIFT),
        StoreInfo.sample(StoreKind.SUPERMARKET, 2020, seed = 5, state = StoreState.REUSED, reuse = StoreReuse.GYM),
        StoreInfo.sample(StoreKind.GENERAL, 2030, seed = 6, state = StoreState.REUSED, reuse = StoreReuse.FEED).copy(style = "brick"),
        StoreInfo.sample(StoreKind.SUPERMARKET, 1975, seed = 3),
        StoreInfo.sample(StoreKind.SUPERMARKET, 1986, seed = 8),
        StoreInfo.sample(StoreKind.SUPERSTORE, 1998, seed = 3),
        StoreInfo.sample(StoreKind.SUPERSTORE, 2016, seed = 4),
        StoreInfo.sample(StoreKind.SUPERSTORE, 2038, seed = 4, state = StoreState.CLOSED).copy(closed = 2030),
        StoreInfo.sample(StoreKind.SUPERMARKET, 2025, seed = 3, state = StoreState.CLOSED).copy(closed = 2016),
        StoreInfo.sample(StoreKind.MARKET, 1995, seed = 4, state = StoreState.REUSED, reuse = StoreReuse.CHURCH),
        StoreInfo.sample(StoreKind.SUPERSTORE, 1996, seed = 3).copy(features = StoreSiteInfo.featuresOf(StoreKind.SUPERSTORE, StoreState.OPEN, 1996, null, null, true, false).toSet()),
        StoreInfo.sample(StoreKind.SUPERSTORE, 2022, seed = 41).copy(features = StoreSiteInfo.featuresOf(StoreKind.SUPERSTORE, StoreState.OPEN, 2022, null, null, false, false).toSet()),
        StoreInfo.sample(StoreKind.GENERAL, 1996, seed = 5).copy(features = StoreSiteInfo.featuresOf(StoreKind.GENERAL, StoreState.OPEN, 1996, null, "bait", false, true).toSet()),
        // a town's own smaller plots
        StoreInfo.sample(StoreKind.GENERAL, 1930, seed = 11).copy(width = 300f, depth = 560f),
        StoreInfo.sample(StoreKind.SUPERMARKET, 1978, seed = 2).copy(width = 1200f, depth = 1500f),
        StoreInfo.sample(StoreKind.SUPERSTORE, 2005, seed = 6).copy(width = 2500f, depth = 3000f),
    )

    @Test fun everyStoreStandsOnItsPlot() {
        for (info in variants) {
            val tag = "${info.kind} ${info.year} ${info.state} ${info.reuse ?: ""}"
            val built = StoreKit.buildWithSite(info)
            val m = built.model
            assertTrue("$tag has no parts", m.parts.isNotEmpty())
            val L = info.lot; val D = info.deep
            var lowest = Float.MAX_VALUE
            for (p in m.parts) {
                val mesh = p.mesh
                assertTrue("$tag ${p.role}: no faces", mesh.idx.isNotEmpty() && mesh.idx.size % 3 == 0)
                for (i in mesh.idx) assertTrue(i in 0 until mesh.vertexCount)
                for (i in 0 until mesh.vertexCount) {
                    val x = mesh.pos[i * 3]; val y = mesh.pos[i * 3 + 1]; val z = mesh.pos[i * 3 + 2]
                    lowest = minOf(lowest, y)
                    assertTrue("$tag ${p.role} below the ground at ($x, $y, $z)", y >= -1.01f)
                    assertTrue("$tag ${p.role} outside its plot ${L}x$D at ($x, $y, $z)", x >= -L / 2f - .01f && x <= L / 2f + .01f && z >= -D - .01f && z <= .01f)
                    assertTrue("$tag ${p.role} not finite", x.isFinite() && y.isFinite() && z.isFinite())
                }
                if (!p.tiled) for (u in mesh.uv) assertTrue("$tag ${p.role} uv $u", u in -1e-4f..1.0001f)
                assertTrue("$tag ${p.role} atlas ${mesh.texW}x${mesh.texH}", mesh.texW in 1..1024 && mesh.texH in 1..1024)
                assertEquals(mesh.texW * mesh.texH, mesh.tex.size)
                if (p.role != CinemaPart.Role.BODY && info.state == StoreState.OPEN) assertTrue("$tag ${p.role} glows but has no glow mask", mesh.mask != null)
            }
            // on the ground (paving tops at y 0.1, nothing floating or sunk)
            assertTrue("$tag floats: lowest $lowest", abs(lowest) < 1.05f)
            // real scale: a figure is 36 units (1.7 m)
            val b = built.block
            val w = b[2] - b[0]; val d = b[1] - b[3]
            when (info.kind) {
                StoreKind.GENERAL -> { assertTrue("$tag $w wide", w in 165f..290f); assertTrue("$tag height ${m.height}", m.height in 180f..320f) }
                StoreKind.MARKET -> { assertTrue("$tag $w wide", w in 420f..660f); assertTrue("$tag $d deep", d >= 380f) }
                StoreKind.SUPERMARKET -> { assertTrue("$tag $w wide", w >= 800f); assertTrue("$tag $d deep", d >= 580f) }
                // (the 1990s discount store about 60 x 75 m, the supercenter it grows into about 100+ x 70+ m)
                StoreKind.SUPERSTORE -> if (info.has("discountStore")) { assertTrue("$tag $w wide", w in 1100f..1500f); assertTrue("$tag $d deep", d >= 800f) }
                    else { assertTrue("$tag $w wide", w >= 1500f); assertTrue("$tag $d deep", d >= 1000f) }
            }
            // a car park (or a yard) in front: the building stands back from the road
            assertTrue("$tag front at ${b[1]}", b[1] < -60f)
            // pictures kept small for the phone's heap (ARGB on the Java heap while building; the model is dropped after)
            val limit = when (info.kind) { StoreKind.GENERAL -> 3L; StoreKind.MARKET -> 4L; StoreKind.SUPERMARKET -> 5L; StoreKind.SUPERSTORE -> 8L } * 1024 * 1024
            // (the store as the town builds it, its parked cars a layer of their own)
            val store = StoreKit.buildWithSite(info, cars = false).model
            assertTrue("$tag pictures ${store.bytes / 1024} KiB", store.bytes < limit)
            val cars = StoreCars.build(info.copy(weekday = 6, hour = 13f), built.bays)
            assertTrue("$tag cars' pictures ${cars.bytes / 1024} KiB", cars.bytes < 1536L * 1024)
            assertTrue("$tag ${m.triangles} triangles", m.triangles < 200_000)
            // lights at night when it trades, a handful at most (Filament's light budget)
            if (info.state == StoreState.OPEN) assertTrue("$tag lights ${m.lights.size}", m.lights.size in 1..40)
            // a shut general store is dark; a shut big store keeps its security lights (a wall-pack or two, one lamp)
            if (info.state == StoreState.CLOSED && info.kind == StoreKind.GENERAL) assertTrue("$tag is shut but lit: ${m.parts.filter { it.role != CinemaPart.Role.BODY }.map { it.role }}", m.lights.isEmpty())
            if (info.state == StoreState.CLOSED && info.kind != StoreKind.GENERAL) assertTrue("$tag security lights ${m.lights.size}", m.lights.size in 1..3)
            for (l in m.lights) assertTrue("$tag light outside the plot", l.x >= -L / 2f && l.x <= L / 2f && l.z >= -D && l.z <= 0f)
            println("$tag: ${m.parts.size} parts, ${m.triangles} triangles, ${m.lights.size} lights, ${m.texels} texels = ${m.bytes / 1024} KiB")
        }
    }

    @Test fun namesAreMadeUp() {
        for (k in StoreKind.entries) for (seed in 0 until 200) {
            val n = StoreNames.name(k, seed, "Gamma")
            assertFalse("$k $seed: '$n' is a real chain", StoreNames.isReal(n))
            assertTrue(n.isNotBlank())
        }
        for (r in StoreReuse.entries) for (seed in 0 until 100) assertFalse(StoreNames.isReal(StoreNames.reuseName(r, seed, "Gamma")))
        // the blocklist catches the real ones, whole words, any case and punctuation
        for (real in listOf("Walmart", "WAL-MART Supercenter", "Kroger", "Safeway", "Piggly Wiggly", "A&P", "Target", "Costco", "kmart", "Publix", "IGA Foodliner", "Red Owl", "Food Fair", "Thriftway"))
            assertTrue(real, StoreNames.isReal(real))
        assertFalse(StoreNames.isReal("Brightwell Supercenter"))
        // seeded
        assertEquals(StoreNames.name(StoreKind.SUPERSTORE, 5), StoreNames.name(StoreKind.SUPERSTORE, 5))
    }

    @Test fun readsTheTownsBlock() {
        val g = org.json.JSONObject("""{"town":"gamma","stores":[
            {"id":"store:generalstore","kind":"general","style":"frontier","name":"Pruett Mercantile","sign":"Old Pruett Antiques","year":1990,"state":"reused","reuse":{"kind":"antiques","name":"Old Pruett Antiques"},"opened":1898,"closed":1979,"seed":12,"features":["porch","bench","reuse:antiques"],"footprint":{"width":340,"depth":600}},
            {"id":"landmark:store:1","kind":"superstore","name":"Openfield","year":1990,"state":"open","opened":1988,"seed":3,"footprint":{"width":2800,"depth":3300}}]}""")
        val s = StoreInfo.fromGraph(g)
        assertEquals(2, s.size)
        assertEquals(StoreState.REUSED, s[0].state); assertEquals(StoreReuse.ANTIQUES, s[0].reuse); assertEquals("Old Pruett Antiques", s[0].signName)
        assertEquals(340f, s[0].lot); assertEquals(1979, s[0].closed); assertEquals("frontier", s[0].style); assertTrue("bench" in s[0].features)
        assertEquals(StoreKind.SUPERSTORE, s[1].kind); assertEquals(3300f, s[1].deep)
        assertNotNull(StoreKit.build(s[0]))
    }

    @Test fun noSuperstoreWearsARealChainsTradeDress() {
        val looks = HashSet<String>()
        for (seed in 0 until 60) for (year in listOf(1996, 2010, 2024)) {
            val info = StoreInfo.sample(StoreKind.SUPERSTORE, year, seed = seed).copy(features = StoreSiteInfo.featuresOf(StoreKind.SUPERSTORE, StoreState.OPEN, year, null, null, year < 2005 && seed % 2 == 0, false).toSet())
            val built = StoreKit.buildWithSite(info, cars = false)
            for (t in built.texts + info.name) {
                assertFalse("$seed $year: '$t' is a real chain's", StoreNames.isReal(t))
                assertFalse("$seed $year: '$t' is a real chain's trade dress", StoreNames.looksLikeChain(t))
            }
            looks += StoreKit.facadeLook(info)
        }
        // the chains' storefronts vary: entrances, their words, the emblem, the line under the name, the tower tops
        assertTrue("only ${looks.size} superstore facades", looks.size >= 12)
        assertTrue(StoreNames.looksLikeChain("Brightwell Supercenter")); assertTrue(StoreNames.looksLikeChain("Allday Depot"))
        for (k in StoreKind.entries) for (seed in 0 until 200) assertFalse(StoreNames.looksLikeChain(StoreNames.name(k, seed, "Gamma")))
    }

    @Test fun lettersAdvanceEvenly() {
        // one font pixel between letters at every scale: each letter's cell is 5 x scale, then scale empty columns
        for ((w, h) in listOf(400 to 40, 600 to 60, 180 to 20, 900 to 80)) {
            val word = "LARKSPUR"
            val (px, sc) = StoreKit.lettering(word, w, h)
            val ink = BooleanArray(w) { x -> (0 until h).any { y -> px[y * w + x] != 0 } }
            val x0 = ink.indexOfFirst { it }
            for (k in 0 until word.length) {
                val cell = x0 + k * 6 * sc
                assertTrue("$w x $h scale $sc: letter $k has no ink", (cell until cell + 5 * sc).any { ink[it] })
                if (k < word.length - 1) for (x in cell + 5 * sc until cell + 6 * sc) assertFalse("$w x $h scale $sc: column $x between letters $k and ${k + 1} is inked", ink[x])
            }
        }
    }

    @Test fun featuresTheGeneratorEmitsAreAllKnown() {
        for (k in StoreKind.entries) for (st in StoreState.entries) for (y in listOf(1920, 1960, 1980, 1999, 2003, 2015, 2024)) for (t in listOf(null, "deli", "bait")) for (d in listOf(false, true))
            for (f in StoreSiteInfo.featuresOf(k, st, y, if (st == StoreState.REUSED) StoreReuse.CHURCH else null, t, d, d))
                assertTrue("$k $st $y: '$f' unknown to StoreKit", f in StoreKit.FEATURES || f.startsWith("reuse:"))
    }

    /** gamma's sites year by year (tools/stores-fixture.ts, from the generator): StoreSiteInfo.at mirrors storeState / storeFeatures. */
    @Test fun sitesThroughTheYearsMatchTheGenerator() {
        val fx = org.json.JSONObject(java.io.File("src/filamentTest/resources/stores-gamma-years.json").readText())
        val sites = StoreSiteInfo.fromGraph(fx.put("town", "gamma"))
        assertEquals(4, sites.size)
        val years = fx.getJSONObject("years")
        for (site in sites) {
            val rows = years.getJSONArray(site.site)
            for (y in 1900..2040) {
                var row: org.json.JSONObject? = null
                for (i in 0 until rows.length()) if (rows.getJSONObject(i).getInt("year") <= y) row = rows.getJSONObject(i)
                val st = site.stateAt(y)
                if (!row!!.has("kind")) { assertEquals("${site.site} $y", null, st); assertEquals(null, site.at(y)); continue }
                assertNotNull("${site.site} $y", st)
                assertEquals("${site.site} $y kind", row.getString("kind"), st!!.kind)
                assertEquals("${site.site} $y state", row.getString("state"), st.state)
                assertEquals("${site.site} $y name", row.getString("name"), st.name)
                assertEquals("${site.site} $y sign", row.getString("sign"), st.sign)
                val f = row.getJSONArray("features")
                assertEquals("${site.site} $y features", (0 until f.length()).map { f.getString(it) }, st.features)
            }
        }
        // gamma in 2025 at Saturday noon: the superstore from the sites, the town's people of the year
        val ss = sites.first { it.site == "superstore" }.at(2025, 12f, 6)!!
        assertEquals(StoreKind.SUPERSTORE, ss.kind); assertTrue(ss.people > 200)
        // before it opens the site is a field, the year before a building site; both on the plot
        for (y in listOf(1990, ss.opened - 1)) {
            val m = StoreSitePlaceholder.build(sites.first { it.site == "superstore" }, y)
            assertTrue(m.parts.isNotEmpty())
            for (p in m.parts) for (i in 0 until p.mesh.vertexCount) assertTrue(p.mesh.pos[i * 3 + 2] in -ss.deep - .01f..0.01f)
        }
    }

    @Test fun carParksFillByTheHourAndTheTown() {
        // gamma's superstore plot (159 x 206 m) and its people
        val big = StoreInfo.sample(StoreKind.SUPERSTORE, 2016, seed = 4).copy(people = 215, width = 3364.571f, depth = 4361f)
        val built = StoreKit.buildWithSite(big, cars = false)
        val bays = built.bays.count { !it.staff }
        assertTrue("$bays bays", bays > 400)
        fun share(hour: Float, wd: Int) = StoreTraffic.cars(big.copy(hour = hour, weekday = wd), bays) / bays.toFloat()
        assertTrue("Saturday afternoon ${share(15f, 6)}", share(15f, 6) in .2f..0.42f)
        assertTrue("weekday lunch ${share(12.5f, 3)}", share(12.5f, 3) in .12f..0.32f)
        assertTrue("small hours ${share(3f, 3)}", share(3f, 3) < .04f)
        // a store that shuts at night has only its staff's cars an hour after closing, then none
        val old = big.copy(year = 1995)
        assertEquals(0, StoreTraffic.shoppers(old.copy(hour = 23.5f)))
        assertEquals(0, StoreTraffic.cars(old.copy(hour = 2f), bays))
        // a church fills on Sunday morning, not on a Tuesday
        val church = StoreInfo.sample(StoreKind.MARKET, 1995, seed = 4, state = StoreState.REUSED, reuse = StoreReuse.CHURCH).copy(people = 200)
        assertTrue(StoreTraffic.shoppers(church.copy(hour = 10f, weekday = 0)) > 3 * maxOf(1, StoreTraffic.shoppers(church.copy(hour = 10f, weekday = 2))))
        // the cars are their own layer, rebuilt for an hour without the store
        val cars = StoreCars.build(big.copy(hour = 15f, weekday = 6), built.bays)
        assertTrue(cars.parts.isNotEmpty())
        assertTrue(StoreCars.build(big.copy(hour = 3f, weekday = 3), built.bays).triangles < cars.triangles / 4)
    }
}
