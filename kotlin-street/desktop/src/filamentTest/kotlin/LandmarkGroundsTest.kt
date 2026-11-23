import com.lifemax.rpg.feature.habits.domain.CinemaSiteShape
import com.lifemax.rpg.feature.habits.domain.GlbLandmarks
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.StoreLots
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownPlace
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreSolids
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownLandmark
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * The owner's report from 0.1.598 ("an invisible wall"): the game restored them where they last stood, (7546, 7177) in
 * the capital, inside Lincoln High School's fenced sports field. Walking towards the monuments (+z) they met the field's
 * front fence, but nothing of the school was drawn: the open world's camera cuts a landmark away whole while the player
 * stands in its building's box, and that box was the bounds of every wall, roof and glass material, the field's
 * chain-link fence and the baseball fence included (drawn in glass), so it covered nearly the whole lot. The box is now
 * the building's own (the nodes outside the model's lot group, [LandmarkKit] body): this plays the walk there with every
 * landmark's solids in, as the town adds them, and checks the spot is outside the school's box and can be walked out of.
 */
class LandmarkGroundsTest {
    private val dir = File("../app/src/main/assets/town/gamma")
    private val root = JSONObject(File(dir, "street.json").readText())
    private val town = Town.parse(root)!!
    private val lroot = File(assetsDir(), "landmarks")
    private val specs = LandmarkSpec.catalogue(File(lroot, "landmarks.json").readText())

    /** The owner's spot (the screenshot's game state) and the camera's yaw there. */
    private val PX = 7546.2734f
    private val PZ = 7177.1426f
    private val YAW = (-166.85884 * PI / 180).toFloat()

    private class Placed(val sprite: String, val kind: String, val lm: com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkModel, val at: FloatArray)

    private val placed: List<Placed> by lazy {
        GlbLandmarks.parse(root).mapNotNull { p ->
            val spec = specs.firstOrNull { it.kind == p.kind } ?: return@mapNotNull null
            val (r, b) = town.placement(p.sprite) ?: return@mapNotNull null
            val lm = LandmarkKit.build(GlbFile.read(File(lroot, spec.fileOf(p.variant)).readBytes()), spec, town.year, town.seed.hashCode(),
                hide = p.hide, info = spec.info?.let { File(lroot, it).readText() })
            val m = lm.model
            // (the frame as TownLandmark makes it)
            val at = TownLandmark.rowFrame(TownPlace.of(r, b), b.setback)(spec.centre?.let { it * LandmarkKit.UNITS_PER_METRE } ?: ((m.footprint[0] + m.footprint[2]) / 2f), lm.front)
            Placed(p.sprite, p.kind, lm, at)
        }
    }

    private fun collision(): TownCollision = TownCollision.build(town, TownYards.parse(root), ModelShape.parseAll(File(dir, "buildings/models.json").readText()),
        CinemaSiteShape.parse(root), StoreLots.parse(root).map { it.sprite }.toSet()).also { c ->
        for (p in placed) c.setExtra("landmark:" + p.sprite, StoreSolids.world(p.lm.solids, p.at), SolidKind.BUILDING, p.sprite)
    }

    private fun local(at: FloatArray, x: Float, z: Float) = floatArrayOf((x - at[0]) * at[2] + (z - at[1]) * at[3], (x - at[0]) * at[4] + (z - at[1]) * at[5])

    @Test fun theOwnersSpotWalkingToTheMonumentsMeetsTheSchoolsFieldFence() {
        val c = collision()
        val w = OpenWalk(town, c, TownYards.parse(root))
        GameState.motion.vx = 0f; GameState.motion.vy = 0f
        w.placeAt(PX, PZ, 1f, 0f)
        assertEquals(PX, w.wx, .01f); assertEquals(PZ, w.wz, .01f)
        // the stick pushed up, the camera as it was: towards the Great Meridian and the Night Keeper (+z)
        w.cameraYaw = YAW
        repeat(240) { w.step(1f / 60f, 0f, -1f) }
        // held at the field's front fence, a shape of Lincoln High School's (b321), about a walker's radius south of it
        val i = c.nearest(w.wx, w.wz + OpenWalk.RADIUS + 2f, 20f)
        assertTrue(i >= 0)
        assertEquals("b321", c.owner(i))
        assertTrue("held short of the road (${w.wz})", w.wz < 7190f)
        println("walked to (${w.wx}, ${w.wz}); held by ${c.owner(i)} ${c.shape(i, FloatArray(6)).joinToString()}")
        // the field has its gates: the spot is not shut in, nor the restore's to move
        assertFalse(c.boxedIn(PX, PZ, OpenWalk.RADIUS))
    }

    @Test fun theSchoolsBodyIsItsBuildingNotItsFields() {
        val p = placed.first { it.sprite == "b321" }
        val body = assertNotNullBody(p)
        val fp = p.lm.model.footprint
        val me = local(p.at, PX, PZ)
        println("b321 body ${body.joinToString()} of footprint ${fp.joinToString()}; the owner's spot at ${me.joinToString()}")
        // the owner's spot (in the field) is outside the building's box, so the camera keeps the school, its fences drawn
        assertFalse(me[0] in body[0]..body[2] && me[1] in body[1]..body[3])
        // and the box is the building's: well under the lot (the spec's building 140 x 96 m, the lot 233 x 160 m)
        val U = LandmarkKit.UNITS_PER_METRE
        assertTrue("body ${(body[2] - body[0]) / U} m wide", (body[2] - body[0]) / U < p.lm.spec.buildingW * 1.15f)
        assertTrue("body ${(body[3] - body[1]) / U} m deep", (body[3] - body[1]) / U < p.lm.spec.buildingD * 1.15f)
    }

    @Test fun everyLandmarkHasABodyWithinItsLot() {
        // (a statue with no walls, roofs or glass, the Rex, has none: the town takes its materials' bounds as before)
        assertTrue(placed.count { it.lm.body != null } >= placed.size - 2)
        for (p in placed) {
            val body = p.lm.body ?: continue
            val fp = p.lm.model.footprint
            assertTrue("${p.kind} body ${body.joinToString()} in ${fp.joinToString()}",
                body[0] >= fp[0] - 1f && body[1] >= fp[1] - 1f && body[2] <= fp[2] + 1f && body[3] <= fp[3] + 1f && body[4] > 0f)
        }
    }

    private fun assertNotNullBody(p: Placed): FloatArray { assertNotNull("${p.kind} has no body", p.lm.body); return p.lm.body!! }
}
