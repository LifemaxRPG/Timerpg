import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.VehicleKit
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The race Torrel's models as the renderer builds them (VehicleKit): every colour of its palette builds at every
 * distance; a livery's colour draws that livery's decals only, plain paint none of them.
 */
class RaceTorrelKitTest {
    private val dir = File("../app/src/main/assets/vehicles/torrel-1988-rs")
    private val spec = VehicleSpec.parse(File(dir, "model-info.json").readText())

    @Test fun eachColourBuildsWithItsOwnLivery() {
        com.google.android.filament.Filament.init()
        val tris = HashMap<Int, Int>()
        for (c in spec.colours) for (f in listOf(spec.near, spec.mid!!, spec.far!!)) {
            val m = VehicleKit.build(GlbFile.read(File(dir, f).readBytes()), spec, c, animated = f == spec.near)
            assertTrue("$f in ${Integer.toHexString(c)}: parts", m.parts.isNotEmpty())
            for (p in m.parts) for ((_, mesh) in p.meshes) assertTrue("$f ${p.name}: packs", PropLayer.Packing.packLocal(mesh) != null)
            if (f == spec.near) tris[c] = m.triangles
        }
        val plain = tris[VehicleSpec.colour("#b84b47")]!!
        val a = tris[spec.liveries[0].colour]!!; val b = tris[spec.liveries[1].colour]!!
        println("race Torrel near triangles: plain $plain, livery a $a, livery b $b")
        assertTrue("livery a adds its decals ($a vs $plain)", a > plain + 50)
        assertTrue("livery b adds its decals ($b vs $plain)", b > plain + 50)
        // (the whole file holds both: one livery's car draws fewer than all)
        val all = GlbFile.read(File(dir, spec.near).readBytes()).let { g -> (0 until g.nodes.size).sumOf { i -> g.nodes[i].takeIf { it.mesh >= 0 }?.let { n -> g.primitives(n.mesh).sumOf { it.idx.size / 3 } } ?: 0 } }
        assertEquals(all, plain + (a - plain) + (b - plain))
    }
}
