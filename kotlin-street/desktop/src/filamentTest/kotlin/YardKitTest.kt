import com.lifemax.rpg.feature.habits.presentation.scene3d.YardData
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardItem
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The yards' kit (scene3d/YardKit.kt): every item kind of the town gamma's yards has a builder, and every item's meshes
 * stand on the ground inside the item's own rect.
 */
class YardKitTest {
    /** The town gamma's street.json (build-town/gamma, made by tools/build-town.sh gamma), or null when not built here. */
    private fun gamma(): String? = listOf("../build-town/gamma/street.json", "build-town/gamma/street.json").map { File(it) }.firstOrNull { it.exists() }?.readText()

    private fun items(): List<YardItem> {
        val json = gamma()
        // without the built town, the lineup still covers every kind the kit knows
        return if (json == null) YardKit.lineup() else YardData.yards(json).flatMap { it.items } + YardData.laneItems(json)
    }

    @Test fun everyKindHasABuilder() {
        val kinds = items().map { it.kind }.toSet()
        val missing = kinds - YardKit.KINDS
        assertTrue("no builder for $missing", missing.isEmpty())
        if (gamma() != null) assertTrue("gamma has ${kinds.size} kinds", kinds.size >= 50)
        // and each builds something
        for (it in YardKit.lineup()) assertTrue("$it built nothing", YardKit.bake(listOf(it)).isNotEmpty())
    }

    @Test fun everyItemStandsOnTheGroundInsideItsRect() {
        val list = items()
        var checked = 0
        val bad = LinkedHashMap<String, String>()
        for (it in list) try {
            val batches = YardKit.bake(listOf(it))
            assertTrue("$it built nothing", batches.isNotEmpty())
            var minY = Float.MAX_VALUE
            // (a little slack: edging one texel wide, a post's cap)
            val slack = 1.0f
            for (b in batches) {
                val m = b.mesh
                assertTrue("$it ${b.group}: no triangles", m.idx.isNotEmpty() && m.idx.size % 3 == 0)
                for (u in m.uv) assertTrue("$it ${b.group}: uv $u", b.tiled || u in -1e-4f..1.0001f)
                for (i in 0 until m.vertexCount) {
                    val x = m.pos[i * 3]; val y = m.pos[i * 3 + 1]; val z = m.pos[i * 3 + 2]
                    assertTrue("$it ${b.group}: vertex ($x, $y, $z) outside its rect", x >= it.x0 - slack && x <= it.x1 + slack && z >= it.z0 - slack && z <= it.z1 + slack)
                    assertTrue("$it ${b.group}: vertex below the ground ($y)", y >= -.01f)
                    minY = minOf(minY, y)
                }
            }
            // standing on the ground, not floating
            assertTrue("$it floats ($minY)", minY < .8f)
            checked++
        } catch (e: AssertionError) { bad.putIfAbsent(it.kind + "/" + it.variant, e.message ?: "") }
        assertTrue("${bad.size} kinds/looks off:\n" + bad.values.joinToString("\n"), bad.isEmpty())
        assertEquals(list.size, checked)
    }

    @Test fun aYardBakesIntoFewDraws() {
        val json = gamma() ?: return
        val yards = YardData.yards(json)
        val all = yards.flatMap { it.items }
        val batches = YardKit.bake(all)
        // one a kind (or a few: atlases split), one a ground tile
        assertTrue("${batches.size} batches for ${all.size} items", batches.size < 160)
        for (b in batches) if (!b.tiled) assertTrue("${b.group} atlas ${b.mesh.texW}x${b.mesh.texH}", b.mesh.texW <= 2048 && b.mesh.texH <= 2048)
    }
}
