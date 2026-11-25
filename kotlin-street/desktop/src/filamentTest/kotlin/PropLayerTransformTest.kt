import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropMesh
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The car that "stayed where it was" (0.1.909 on the S25, 021540): a moved layer (a car's parts, the train, a crossing
 * gate) kept each batch's TransformManager instance from its first [PropLayer.setBatchTransform]. Filament's component
 * managers keep their arrays packed: destroying any entity with a transform moves the last one into its slot, so a kept
 * instance can name another entity once streaming lets anything moved go. The car's parts then stood where they were
 * when that happened, the frames' transforms landing on someone else's entity.
 */
class PropLayerTransformTest {
    private val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }

    private fun tri() = PropMesh(
        pos = floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f), uv = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
        nrm = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), tan = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f),
        idx = intArrayOf(0, 1, 2), texW = 1, texH = 1, tex = intArrayOf(-1), mask = null, bounds = floatArrayOf(0f, 0f, 0f, 10f, 10f, 0f),
    )

    private fun move(x: Float) = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f; it[12] = x }

    @Test fun movedLayerStillMovesAfterOthersAreDestroyed() {
        Filament.init()
        val engine = Engine.create(Engine.Backend.OPENGL)
        val scene = engine.createScene()
        fun mat(n: String): Material {
            val b = File(assets, "filament/$n.filamat").readBytes()
            return Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine)
        }
        val lit = mat("street_lit"); val proxy = mat("shadow_proxy")
        fun layer(x: Float) = PropLayer(engine, scene, lit, proxy).also { l -> l.upload(PropLayer.Packing.packLocal(tri())!!, casts = true); l.setTransform(move(x)) }
        // three moved things standing about, then the car
        val others = List(3) { layer(100f * it) }
        val car = layer(500f)
        val tm = engine.transformManager
        val kept = tm.getInstance(car.batchEntity(0))
        // streaming lets the first of the others go; the car drives on
        others[0].destroy()
        // (the premise: Filament moved the car's component; an instance kept from before now names another entity)
        org.junit.Assert.assertNotEquals("Filament keeps its transforms packed", kept, tm.getInstance(car.batchEntity(0)))
        car.setTransform(move(900f))
        val out = FloatArray(16)
        tm.getTransform(tm.getInstance(car.batchEntity(0)), out)
        assertArrayEquals("the car's part stands where the frame put it", move(900f), out, 1e-4f)
        tm.getTransform(tm.getInstance(others[2].batchEntity(0)), out)
        assertArrayEquals("another moved thing is not moved by the car's frames", move(200f), out, 1e-4f)
        car.destroy(); others[1].destroy(); others[2].destroy()
        engine.destroyMaterial(lit); engine.destroyMaterial(proxy); engine.destroyScene(scene); engine.destroy()
    }
}
