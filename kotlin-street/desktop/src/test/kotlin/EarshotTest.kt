import com.lifemax.rpg.feature.habits.presentation.scene.Earshot
import com.lifemax.rpg.feature.habits.presentation.scene.Hearer
import com.lifemax.rpg.feature.habits.presentation.scene.TalkRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who hears the player in the town (Earshot): anyone within earshot (70 units), at their door or walking by, the one the
 * player faces and is nearest, and the one listening kept while still near. The player stands at the origin facing +x.
 * gradle :desktop:test --tests EarshotTest
 */
class EarshotTest {
    private val range = 70f
    private fun door(sprite: String, x: Float, z: Float) = Hearer(sprite, -1, x, z)
    private fun passer(who: Int, x: Float, z: Float) = Hearer(null, who, x, z)
    private fun choose(vararg h: Hearer, current: String? = null) = Earshot.choose(0f, 0f, 1f, 0f, h.toList(), range, current)

    @Test fun earshotIsSeventyUnits() = assertEquals(70f, TalkRange.distance)

    @Test fun nobodyInEarshotIsNobody() {
        assertNull(choose())
        assertNull(choose(door("b1", 71f, 0f), passer(5, 0f, -90f)))
    }

    @Test fun aPasserRightBesideThePlayerHearsWithNoDoorNear() {
        val h = choose(passer(7, 0f, 25f), door("b1", 200f, 0f))!!
        assertTrue(h.walking)
        assertEquals(7, h.who)
        assertEquals("who:7", h.key)
    }

    @Test fun ofTwoAsNearTheOneAheadHears() {
        assertEquals("ahead", choose(door("behind", -40f, 0f), door("ahead", 40f, 0f))!!.sprite)
        assertEquals(3, choose(passer(2, 0f, 40f), passer(3, 40f, 0f))!!.who)
        // (facing: ahead 1, beside 0, behind -1; beside counts at 1.5 times the distance, behind at twice)
        assertEquals(40f, Earshot.score(0f, 0f, 1f, 0f, door("a", 40f, 0f)), 1e-4f)
        assertEquals(60f, Earshot.score(0f, 0f, 1f, 0f, door("a", 0f, 40f)), 1e-4f)
        assertEquals(80f, Earshot.score(0f, 0f, 1f, 0f, door("a", -40f, 0f)), 1e-4f)
    }

    @Test fun someoneMuchNearerBesideBeatsSomeoneFarAhead() {
        assertEquals(1, choose(passer(1, 0f, 10f), door("far", 65f, 0f))!!.who)
        // but not someone a little nearer behind
        assertEquals("ahead", choose(passer(1, -30f, 0f), door("ahead", 45f, 0f))!!.sprite)
    }

    @Test fun beyondEarshotDoesNotCountEvenStraightAhead() {
        assertEquals(4, choose(door("far", 75f, 0f), passer(4, -60f, 0f))!!.who)
    }

    @Test fun theOneListeningKeepsTheTurnUnlessAnotherIsClearlyBetter() {
        val talking = passer(9, 30f, 20f)   // score ~ 36 * 1.08
        val other = passer(8, 32f, 0f)      // score 32: a little better, not clearly
        assertEquals(8, choose(talking, other)!!.who)
        assertEquals(9, choose(talking, other, current = "who:9")!!.who)
        // clearly better (by more than STICK): the other takes over
        assertEquals(8, choose(talking, passer(8, 5f, 0f), current = "who:9")!!.who)
        // the one listening walked out of earshot: the best one now
        assertEquals(8, choose(passer(9, 80f, 0f), other, current = "who:9")!!.who)
        // a door's person is kept by their building
        assertEquals("b2", choose(door("b2", 30f, 20f), passer(1, 32f, 0f), current = "b2")!!.sprite)
    }

    @Test fun aDoorAndAPasserAreToldApart() {
        assertFalse(door("b1", 0f, 0f).walking)
        assertEquals("b1", door("b1", 0f, 0f).key)
        // the same seed walking twice is the same person to keep
        assertEquals(passer(3, 0f, 0f).key, passer(3, 10f, 0f).key)
    }

    @Test fun theNearestForTheBreadcrumb() {
        val n = Earshot.nearest(0f, 0f, listOf(door("b1", 300f, 0f), passer(2, 0f, 120f)))!!
        assertEquals(2, n.first.who); assertEquals(120f, n.second, 1e-4f)
        assertNull(Earshot.nearest(0f, 0f, emptyList()))
    }

    @Test fun facingIsTheCosineToThem() {
        assertEquals(1f, Earshot.facing(0f, 0f, 1f, 0f, 10f, 0f), 1e-5f)
        assertEquals(0f, Earshot.facing(0f, 0f, 1f, 0f, 0f, 10f), 1e-5f)
        assertEquals(-1f, Earshot.facing(0f, 0f, 1f, 0f, -10f, 0f), 1e-5f)
        // (standing on the player, or no facing: counts as ahead)
        assertEquals(1f, Earshot.facing(0f, 0f, 1f, 0f, 0f, 0f), 1e-5f)
        assertEquals(1f, Earshot.facing(0f, 0f, 0f, 0f, 5f, 5f), 1e-5f)
    }
}
