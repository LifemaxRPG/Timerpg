import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaBill
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaEra
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaPart
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaPosters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The movie theater: each era's meshes stand on the ground inside the footprint, its pictures stay small, the bill is seeded. */
class CinemaKitTest {
    private val years = mapOf(CinemaEra.PALACE to 1952, CinemaEra.MODERN to 1972, CinemaEra.TWIN to 1988, CinemaEra.MULTIPLEX to 2015)

    @Test fun everyEraStandsInsideItsFootprint() {
        for ((era, year) in years) {
            val info = CinemaKit.sample(year, "LYRIC", seed = 5)
            assertEquals(era, info.era)
            val m = CinemaKit.build(info)
            val fp = m.footprint
            assertTrue("$era has no parts", m.parts.isNotEmpty())
            var lowest = Float.MAX_VALUE
            for (p in m.parts) {
                val mesh = p.mesh
                assertTrue("$era ${p.role}: no faces", mesh.idx.isNotEmpty() && mesh.idx.size % 3 == 0)
                for (i in mesh.idx) assertTrue(i in 0 until mesh.vertexCount)
                for (i in 0 until mesh.vertexCount) {
                    val x = mesh.pos[i * 3]; val y = mesh.pos[i * 3 + 1]; val z = mesh.pos[i * 3 + 2]
                    lowest = minOf(lowest, y)
                    assertTrue("$era ${p.role} below the ground at ($x, $y, $z)", y >= -.01f)
                    assertTrue("$era ${p.role} above its height at ($x, $y, $z)", y <= m.height + .5f)
                    assertTrue("$era ${p.role} outside its footprint at ($x, $y, $z): ${fp.toList()}", x >= fp[0] - .01f && x <= fp[2] + .01f && z >= fp[1] - .01f && z <= fp[3] + .01f)
                }
                // an atlas's uvs stay inside it (tiles repeat)
                if (!p.tiled) for (u in mesh.uv) assertTrue("$era ${p.role} uv $u", u in -1e-4f..1.0001f)
                assertTrue("$era ${p.role} picture ${mesh.texW}x${mesh.texH}", mesh.texW in 1..1024 && mesh.texH in 1..1024)
                assertEquals(mesh.texW * mesh.texH, mesh.tex.size)
                if (p.role != CinemaPart.Role.BODY) assertTrue("$era ${p.role} glows but has no glow mask", mesh.mask != null)
            }
            assertTrue("$era floats: lowest $lowest", abs(lowest) < .2f)
            // the lot it needs: no more than a block's sides and steps beyond its main block
            assertTrue("$era footprint ${fp.toList()} for ${m.width}", fp[2] - fp[0] <= m.width + 60f && fp[3] - fp[1] <= m.depth + 80f)
            // real scale: a big building (wider than a shop's 184, deeper than the school's 228), with a marquee over the pavement
            assertTrue("$era ${m.width} wide", m.width >= 280f)
            assertTrue("$era ${m.depth} deep", m.depth >= 380f)
            assertTrue("$era reaches ${fp[3]} over the pavement", fp[3] >= 30f)
            // three chasing bulb phases, each a run of bulbs
            assertEquals("$era chase phases", 3, m.parts.count { it.role.name.startsWith("CHASE") })
            // kept small for the phone's heap: a few MiB of pictures at most
            assertTrue("$era pictures ${m.bytes / 1024} KiB", m.bytes < 5L * 1024 * 1024)
            println("$era: ${m.parts.size} parts, ${m.triangles} triangles, ${m.texels} texels = ${m.bytes / 1024} KiB")
        }
    }

    @Test fun billIsSeededAndChangesByYear() {
        val a = CinemaKit.sample(1972, seed = 9); val b = CinemaKit.sample(1972, seed = 9)
        assertEquals(a, b)
        assertNotEquals(a.nowShowing, CinemaKit.sample(1973, seed = 9).nowShowing)
        assertEquals(4, a.nowShowing.size); assertEquals(4, a.comingSoon.size)
        assertTrue("titles repeat", (a.nowShowing + a.comingSoon).map { it.title }.toSet().size == 8)
        for (f in a.nowShowing) assertEquals(1970, f.decade)
    }

    @Test fun everyDecadePaintsWholePosters() {
        for (decade in 1940..2020 step 10) for (seed in 0 until 12) {
            val f = CinemaBill.film(decade, seed)
            val px = CinemaPosters.argb(f)
            assertEquals(CinemaPosters.W * CinemaPosters.H, px.size)
            // opaque all over (a poster has no holes) and not one flat colour
            assertTrue("$decade/$seed has holes", px.all { it ushr 24 == 0xFF })
            assertTrue("$decade/$seed is blank", px.toSet().size > 3)
        }
    }
}
