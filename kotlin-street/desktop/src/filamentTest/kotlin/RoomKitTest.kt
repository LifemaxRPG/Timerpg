import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.RoomKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** The interiors trial's pieces (docs/INTERIORS_3D.md): built from the shipped pictures, standing where their footprints say. */
class RoomKitTest {
    private val dir = listOf(File("app/src/main/assets/room3d/diner"), File("../app/src/main/assets/room3d/diner")).first { it.isDirectory }
    private val art: (String) -> RoomArt = { n -> ImageIO.read(File(dir, "$n.png")).let { RoomArt(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) } }

    @Test fun rowsRepeatAPlainRowAndKeepTheOthers() {
        val s = RoomArt(2, 4, IntArray(8) { 0xFF000000.toInt() or (it / 2) })
        val o = RoomKit.rowsOf(s, 7, 1)
        assertEquals(7, o.h)
        // rows 0 and 1 as drawn, row 1 three times more, then rows 2 and 3
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 3), (0 until 7).map { o.argb[it * 2] and 255 })
    }

    @Test fun everyBoxAndPartsPieceFitsItsFootprint() {
        for (p in RoomKit.PIECES) {
            if (p.kind != RoomKit.Kind.BOX && p.kind != RoomKit.Kind.PARTS) continue
            val m = RoomKit.meshOf(p.name, art)
            assertTrue("${p.name} has no faces", m.idx.isNotEmpty() && m.idx.size % 3 == 0)
            val t = RoomKit.T
            val b = m.bounds
            // a pendant hangs from its cord's top (the origin); everything else stands on the floor
            if (p.name == "pendant") assertTrue("pendant top ${b[4]}", abs(b[4]) < .1f)
            else assertTrue("${p.name} bottom ${b[1]}", abs(b[1]) < .1f)
            assertTrue("${p.name} height ${(b[4] - b[1]) / t} against ${p.h}", abs((b[4] - b[1]) / t - p.h) <= 1.5f)
            // in front of its origin nothing (it stands back into -z), across within its width (the stove's pan handle aside)
            assertTrue("${p.name} reaches ${b[5] / t} in front", b[5] <= .1f + (if (p.name == "lamp" || p.name == "pendant") p.d * t / 2f else 0f))
            assertTrue("${p.name} ${(b[3] - b[0]) / t} wide against ${p.w}", (b[3] - b[0]) / t <= p.w + (if (p.name == "stove") 15f else 1.5f))
            for (u in m.uv) assertTrue("${p.name} uv $u", u in -1e-4f..1.0001f)
        }
    }
}
