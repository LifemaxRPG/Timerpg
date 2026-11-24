import com.lifemax.rpg.feature.habits.domain.LevelField
import com.lifemax.rpg.feature.habits.domain.WalkHeight
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Great Meridian's flights of steps (the owner's 100436, 0.1.848: "got stuck on stairs before getting to the top"),
 * walked on its height field as the open walk climbs ([WalkHeight.RISE] a step, the way clear to the head round a
 * walker's edge): each flight from the ground to the terrace at its top (138 up). The model-info's boxes for the flights
 * (two treads a box, at the higher tread's height) buried every other tread and left a 22-unit riser at the north
 * flight's top; the drawn steps are the field's now.
 */
class MeridianStepsTest {
    private val field: LevelField by lazy {
        val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }
        val root = File(assets, "landmarks")
        val spec = LandmarkSpec.catalogue(File(root, "landmarks.json").readText()).first { it.kind.endsWith("great_meridian_2000") }
        val lm = LandmarkKit.build(GlbFile.read(File(root, spec.file).readBytes()), spec, 2025, 7, info = spec.info?.let { File(root, it).readText() })
        lm.levels!!.build(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))!!
    }

    private fun floor(x: Float, z: Float, below: Float) = kotlin.math.max(0f, field.top(x, z, below).takeUnless { it.isNaN() } ?: 0f)

    /** Walks from ([x0], [z0]) to ([x1], [z1]) a unit a step as the open walk does: the highest the feet got, and where it stopped (null: all the way). */
    private fun climb(x0: Float, z0: Float, x1: Float, z1: Float): Pair<Float, String?> {
        val r = 5f * .85f
        val n = kotlin.math.hypot(x1 - x0, z1 - z0).toInt()
        var feet = floor(x0, z0, WalkHeight.RISE)
        var top = feet
        for (k in 1..n) {
            val x = x0 + (x1 - x0) * k / n; val z = z0 + (z1 - z0) * k / n
            // (as OpenWalk.footFloor: the highest under the middle and four points half way to the edge)
            val h = 2.5f; val b = feet + WalkHeight.RISE
            val f = maxOf(floor(x, z, b), floor(x + h, z, b), floor(x - h, z, b), floor(x, z + h, b), floor(x, z - h, b))
            val y0 = kotlin.math.max(feet, f) + WalkHeight.RISE; val y1 = kotlin.math.max(feet, f) + WalkHeight.HEAD
            var blocked = field.hits(x, z, y0, y1)
            for (a in 0 until 8) {
                val t = a * Math.PI / 4
                if (field.hits(x + r * kotlin.math.cos(t).toFloat(), z + r * kotlin.math.sin(t).toFloat(), y0, y1)) blocked = true
            }
            if (blocked) return top to "stopped at (%.1f, %.1f), feet %.1f: %s".format(x, z, feet, field.describe(x, z))
            feet = f
            top = kotlin.math.max(top, feet)
        }
        return top to null
    }

    @Test fun everyFlightClimbsToTheTerrace() {
        // (the model's frame: the south flight from the lot's front towards -z, the north one from the back, east and west across)
        val flights = mapOf(
            "south" to floatArrayOf(0f, -600f, 0f, -880f),
            "north" to floatArrayOf(0f, -1960f, 0f, -1680f),
            "north, off the middle" to floatArrayOf(-80f, -1960f, -80f, -1680f),
            "east" to floatArrayOf(680f, -1270f, 410f, -1270f),
            "west" to floatArrayOf(-680f, -1270f, -410f, -1270f),
        )
        val fails = ArrayList<String>()
        for ((name, f) in flights) {
            val (top, stop) = climb(f[0], f[1], f[2], f[3])
            if (stop != null || top < 135f) fails += "the $name flight: up to %.1f; %s".format(top, stop ?: "")
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}
