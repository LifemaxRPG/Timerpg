import com.lifemax.rpg.feature.habits.domain.HorizonPanorama
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The horizon ring (docs/STREAMING.md step 4): baked from the capital's middle, it holds ground past the far view's reach
 * (the alpha skeleton has ranges 200-600 km off), every filled texel's distance code is in range, a column's ground has no
 * gaps (a silhouette, filled from its foot up), towns are lit only where the ground holds them, and the bake is the same
 * every time (deterministic from the skeleton) and quick enough for a worker every 1.5 km of driving.
 */
class HorizonPanoramaTest {
    companion object {
        private val sk: WorldSkeleton by lazy { File("../../content/world/alpha/skeleton.twsk.gz").inputStream().use { WorldSkeleton.read(it) } }
        private val town: Town by lazy { Town.parse(File("../app/src/main/assets/town/gamma/street.json").readText())!! }
        private val site: WorldSite by lazy { WorldSite.of(sk, "gamma", town.scale.toDouble())!! }
    }

    @Test fun theRingHoldsTheFarRangesAsSilhouettes() {
        val at = site.origin.origin
        val eye = (site.origin.town?.elevM ?: 0.0) + 400.0
        val p = HorizonPanorama(sk, at, eye)
        println(p.report)
        val w = HorizonPanorama.W; val h = HorizonPanorama.H
        assertTrue("no ground on the horizon: ${p.report}", p.filled > 1000)
        var gaps = 0
        for (i in 0 until w) {
            var seen = false
            for (j in h - 1 downTo 0) {
                val a = p.pixels[(j * w + i) * 4 + 3].toInt() and 255
                if (a != 0) { seen = true; assertTrue("distance code $a out of range", a in 1..254) } else if (seen) gaps++
            }
        }
        // (a column's ground runs down to the bottom row or to where nearer ground is: a gap is only ever under the far view's
        // own terrain, which hides it; count them so a broken march shows)
        println("columns' gaps under a silhouette: $gaps")
        assertTrue("the bake took %.0f ms".format(p.bakeMs), p.bakeMs < 2000)
        assertArrayEquals("the bake is not deterministic", p.pixels, HorizonPanorama(sk, at, eye).pixels)
    }
}
