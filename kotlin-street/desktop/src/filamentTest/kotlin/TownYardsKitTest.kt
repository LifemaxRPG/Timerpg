import com.google.android.filament.MaterialInstance
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import com.lifemax.rpg.feature.habits.presentation.scene3d.WorldProp
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardKitBuilder
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardPatch
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardSink
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The yard kit in the Filament town ([YardKitBuilder] through the town's yard hook): every lot and lane is baked as its
 * own patch (and each of a lot's tall things a patch of its own that follows its lot), in a few draws, and every vertex lies inside the patch's circle (the town culls and cuts by that circle, so
 * nothing may stick out of it and pop), and each lot's patch goes with its own building.
 */
class TownYardsKitTest {
    private fun gamma(): String? = listOf("../build-town/gamma/street.json", "build-town/gamma/street.json").map { File(it) }.firstOrNull { it.exists() }?.readText()

    /** A baked mesh, kept small: its patch, how far its farthest vertex lies outside the patch's circle, its texels. */
    private class Got(val name: String, val patch: YardPatch?, val outside: Float, val texels: Long, val triangles: Int = 0)

    @Test fun everyLotIsAPatchInAFewDrawsWithEverythingInsideIt() {
        val json = gamma() ?: return
        val town = Town.parse(json)!!
        val yards = TownYards.parse(json)
        val got = ArrayList<Got>()
        YardKitBuilder.build(town, yards, object : YardSink {
            override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean, patch: YardPatch?, water: Boolean): MaterialInstance? {
                var worst = -Float.MAX_VALUE
                if (patch != null) for (i in 0 until mesh.vertexCount) worst = maxOf(worst, hypot(mesh.pos[i * 3] - patch.x, mesh.pos[i * 3 + 2] - patch.z) - patch.r)
                got += Got(name, patch, worst, mesh.texW.toLong() * mesh.texH, mesh.idx.size / 3); return null
            }
            override fun props(list: List<WorldProp>) {}
        })
        assertTrue("every mesh belongs to a patch", got.all { it.patch != null })
        // (each lot and lane twice: near by, and its far version for the open world's distance)
        val byPatch = got.groupBy { it.patch!!.id }
        val near = byPatch.filterValues { !it.first().patch!!.far }
        val far = byPatch.filterValues { it.first().patch!!.far }
        val lots = yards.yards.count { it.items.isNotEmpty() }
        val lanes = yards.lanes.count { it.items.isNotEmpty() }
        // (a lot's tall things are patches of their own beside it, each following its lot: the open world's camera cuts
        // only the one in its way)
        val own = near.filterValues { it.first().patch!!.follows == null }
        val tallOnes = near.filterValues { it.first().patch!!.follows != null }
        // (a lane whose only things are tall ones, a verge's trees, has no patch of its own left)
        val lowLanes = yards.lanes.count { l -> l.items.any { com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown.tallHeight(it.kind, it.zone) <= 0f } }
        assertTrue("${own.size} patches for $lots lots and $lanes lanes ($lowLanes with low things)", own.size == lots + lowLanes)
        val ids = yards.yards.map { it.lot }.toSet() + yards.lanes.map { it.id }
        assertTrue("every tall thing follows a lot or lane", tallOnes.values.all { it.first().patch!!.follows in ids })
        val tall = yards.yards.sumOf { y -> y.items.count { com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown.tallHeight(it.kind, it.zone) > 0f } } +
            yards.lanes.sumOf { l -> l.items.count { com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown.tallHeight(it.kind, it.zone) > 0f } }
        assertTrue("${tallOnes.size} tall things' patches for $tall tall things", tallOnes.size == tall)
        println("yards: ${own.size} lots and lanes, ${tallOnes.size} tall things of their own")
        if (YardKitBuilder.far) {
            // (a lane of tall things only has its far version, standing in for its tall things' patches past the line)
            assertTrue("every far version is a lot's or lane's", far.keys.all { it.removeSuffix(YardPatch.FAR) in ids })
            val nearTris = near.values.sumOf { l -> l.sumOf { it.triangles } }; val farTris = far.values.sumOf { l -> l.sumOf { it.triangles } }
            assertTrue("the far yards ($farTris triangles) are lighter than the near ($nearTris)", farTris * 2 < nearTris)
            println("yards' triangles: near $nearTris, far $farTris")
        }
        val sprites = town.rows.flatMap { r -> r.buildings.map { it.sprite } }.toSet()
        val bad = ArrayList<String>()
        for ((id, list) in byPatch) {
            val p = list.first().patch!!
            if (list.size > 14) bad += "$id: ${list.size} draws"
            if (p.sprite != null && p.sprite !in sprites) bad += "$id: its building ${p.sprite} is not in the town"
            val worst = list.maxOf { it.outside }
            if (worst > 1f) bad += "$id: a vertex %.1f units outside its circle".format(worst)
        }
        assertTrue(bad.take(12).joinToString("\n"), bad.isEmpty())
        println("yards in the town: ${byPatch.size} patches, ${got.size} draws in all (most ${byPatch.values.maxOf { it.size }} a patch), " +
            "${got.count { it.name.endsWith("water") }} water batches, ${got.sumOf { it.texels } / 1_000_000} M texels of pictures")
    }
}
