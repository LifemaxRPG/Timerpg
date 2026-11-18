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
 * own patch, in a few draws, and every vertex lies inside the patch's circle (the town culls and cuts by that circle, so
 * nothing may stick out of it and pop), and each lot's patch goes with its own building.
 */
class TownYardsKitTest {
    private fun gamma(): String? = listOf("../build-town/gamma/street.json", "build-town/gamma/street.json").map { File(it) }.firstOrNull { it.exists() }?.readText()

    /** A baked mesh, kept small: its patch, how far its farthest vertex lies outside the patch's circle, its texels. */
    private class Got(val name: String, val patch: YardPatch?, val outside: Float, val texels: Long)

    @Test fun everyLotIsAPatchInAFewDrawsWithEverythingInsideIt() {
        val json = gamma() ?: return
        val town = Town.parse(json)!!
        val yards = TownYards.parse(json)
        val got = ArrayList<Got>()
        YardKitBuilder.build(town, yards, object : YardSink {
            override fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean, patch: YardPatch?): MaterialInstance? {
                var worst = -Float.MAX_VALUE
                if (patch != null) for (i in 0 until mesh.vertexCount) worst = maxOf(worst, hypot(mesh.pos[i * 3] - patch.x, mesh.pos[i * 3 + 2] - patch.z) - patch.r)
                got += Got(name, patch, worst, mesh.texW.toLong() * mesh.texH); return null
            }
            override fun props(list: List<WorldProp>) {}
        })
        assertTrue("every mesh belongs to a patch", got.all { it.patch != null })
        val byPatch = got.groupBy { it.patch!!.id }
        val lots = yards.yards.count { it.items.isNotEmpty() }
        val lanes = yards.lanes.count { it.items.isNotEmpty() }
        assertTrue("${byPatch.size} patches for $lots lots and $lanes lanes", byPatch.size == lots + lanes)
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
