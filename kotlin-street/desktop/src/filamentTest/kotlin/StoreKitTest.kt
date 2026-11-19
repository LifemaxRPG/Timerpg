import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaPart
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreNames
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreReuse
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreState
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
        StoreInfo.sample(StoreKind.SUPERMARKET, 1975, seed = 3),
        StoreInfo.sample(StoreKind.SUPERMARKET, 1986, seed = 8),
        StoreInfo.sample(StoreKind.SUPERSTORE, 1998, seed = 3),
        StoreInfo.sample(StoreKind.SUPERSTORE, 2016, seed = 4),
        StoreInfo.sample(StoreKind.SUPERSTORE, 2038, seed = 4, state = StoreState.CLOSED),
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
                StoreKind.SUPERSTORE -> { assertTrue("$tag $w wide", w >= 1500f); assertTrue("$tag $d deep", d >= 1000f) }
            }
            // a car park (or a yard) in front: the building stands back from the road
            assertTrue("$tag front at ${b[1]}", b[1] < -60f)
            // pictures kept small for the phone's heap (ARGB on the Java heap while building; the model is dropped after)
            val limit = when (info.kind) { StoreKind.GENERAL -> 3L; StoreKind.MARKET -> 4L; StoreKind.SUPERMARKET -> 5L; StoreKind.SUPERSTORE -> 8L } * 1024 * 1024
            assertTrue("$tag pictures ${m.bytes / 1024} KiB", m.bytes < limit)
            assertTrue("$tag ${m.triangles} triangles", m.triangles < 200_000)
            // lights at night when it trades, a handful at most (Filament's light budget)
            if (info.state == StoreState.OPEN) assertTrue("$tag lights ${m.lights.size}", m.lights.size in 1..40)
            if (info.state == StoreState.CLOSED) assertTrue("$tag is shut but lit: ${m.parts.filter { it.role != CinemaPart.Role.BODY }.map { it.role }}", m.lights.isEmpty())
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
            {"id":"landmark:store:0","kind":"general","name":"Pruett Mercantile","year":1990,"state":"reused","reuse":{"kind":"antiques","name":"Old Pruett Antiques"},"opened":1898,"closed":1979,"seed":12,"footprint":{"width":340,"depth":600}},
            {"id":"landmark:store:1","kind":"superstore","name":"Openfield","year":1990,"state":"open","opened":1988,"seed":3,"footprint":{"width":2800,"depth":3300}}]}""")
        val s = StoreInfo.fromGraph(g)
        assertEquals(2, s.size)
        assertEquals(StoreState.REUSED, s[0].state); assertEquals(StoreReuse.ANTIQUES, s[0].reuse); assertEquals("Old Pruett Antiques", s[0].signName)
        assertEquals(340f, s[0].lot); assertEquals(1979, s[0].closed)
        assertEquals(StoreKind.SUPERSTORE, s[1].kind); assertEquals(3300f, s[1].deep)
        assertNotNull(StoreKit.build(s[0]))
    }
}
