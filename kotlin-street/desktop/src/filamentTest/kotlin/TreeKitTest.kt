import com.lifemax.rpg.feature.habits.presentation.scene3d.YardItem
import com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree kit (scene3d/TreeKit.kt) through the yards' trees: every look of a yard's tree and fruit tree is a real 3D tree
 * in full detail near by (hundreds to a couple of thousand triangles, not the old few hundred) and a much lighter one in
 * the yard's far version, inside its rect, on the ground.
 */
class TreeKitTest {
    private fun tris(kind: String, v: Int, far: Boolean): Int {
        val s = if (kind == "fruitTree") 56f else 64f
        val it = YardItem(kind, 0f, 0f, s, s, 0f, 1f, v, "object", null, "back")
        return YardKit.bake(listOf(it), far = far).sumOf { b ->
            val m = b.mesh
            for (i in 0 until m.vertexCount) {
                val x = m.pos[i * 3]; val y = m.pos[i * 3 + 1]; val z = m.pos[i * 3 + 2]
                assertTrue("$it: vertex ($x, $y, $z) outside", x >= -1f && x <= s + 1f && z >= -1f && z <= s + 1f && y >= -.01f)
            }
            m.idx.size / 3
        }
    }

    @Test fun treesHaveDetailNearAndLessFar() {
        val lines = ArrayList<String>()
        for ((kind, looks) in listOf("tree" to (0..9), "fruitTree" to (0..7))) for (v in looks) {
            val near = tris(kind, v, false); val far = tris(kind, v, true)
            lines += "$kind/$v: $near triangles near, $far far"
            assertTrue("$kind/$v near: $near", near in 500..2600)
            assertTrue("$kind/$v far: $far", far in 60..near / 2)
        }
        println(lines.joinToString("\n"))
    }
}
