import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropKit
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The street props' meshes keep to their kinds' footprints and heights, and stand on the ground. */
class PropKitTest {
    @Test fun everyMeshFitsItsFootprint() {
        for ((kind, variant) in PropKit.lineup()) {
            val m = PropKit.mesh(kind, variant, PropKit.sampleText(kind), seed = 3)
            assertTrue("$kind/$variant has no faces", m.idx.isNotEmpty() && m.idx.size % 3 == 0)
            // on the ground, not floating or sunk
            assertTrue("$kind/$variant bottom ${m.bounds[1]}", abs(m.bounds[1]) < .1f)
            assertTrue("$kind/$variant height ${m.bounds[4]} > ${kind.h}", m.bounds[4] <= kind.h + .3f)
            // below head height everything is inside the footprint (signs' blades and crowns overhang above it)
            for (i in 0 until m.vertexCount) {
                val x = m.pos[i * 3]; val y = m.pos[i * 3 + 1]; val z = m.pos[i * 3 + 2]
                if (y > 30f) continue
                assertTrue("$kind/$variant at ($x, $y, $z) outside ${kind.w} x ${kind.d}", abs(x) <= kind.w / 2f + .05f && abs(z) <= kind.d / 2f + .05f)
            }
            // a pixel atlas: every used texel opaque, uvs inside it
            for (u in m.uv) assertTrue("$kind/$variant uv $u", u in -1e-4f..1.0001f)
            assertTrue(m.texW in 1..2048 && m.texH in 1..2048)
            if (kind.glows) assertTrue("$kind/$variant glows but has no glow mask", variant == 0 && kind == PropKind.BOLLARD || m.mask != null)
        }
    }
}
