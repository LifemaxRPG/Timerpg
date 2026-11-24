import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Viewport
import com.lifemax.rpg.feature.habits.presentation.scene3d.BufferArena
import com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.GlbFile
import com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkAnimator
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.LandmarkSpec
import com.lifemax.rpg.feature.habits.presentation.scene3d.PropLayer
import com.lifemax.rpg.feature.habits.presentation.scene3d.SeeThrough
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The per-frame path on the Great Meridian's steps (the S25's freeze of 0.1.805, FilamentHandleTagTest): the model drawn
 * as the town draws it (its layer, its animations, the see-through cone following the feet up its height field), walked
 * from its front up the steps for 300 frames, then stood on for 300: after the first few frames nothing makes a new
 * texture, buffer or shared buffer page, and the process's memory holds level.
 */
class MeridianStepsGpuTest {
    private val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }

    private fun rssMb(): Long = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter(Char::isDigit).toLong() / 1024

    @Test fun walkingUpTheStepsAndStandingMakeNoGpuObjects() {
        val root = File(assets, "landmarks")
        val spec = LandmarkSpec.catalogue(File(root, "landmarks.json").readText()).first { it.kind.endsWith("great_meridian_2000") }
        val lm = LandmarkKit.build(GlbFile.read(File(root, spec.file).readBytes()), spec, 2025, 7, info = spec.info?.let { File(root, it).readText() })
        val field = lm.levels!!.build(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))!!

        Filament.init()
        val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(TownEngine.config()).build()
        fun mat(n: String): Material = File(assets, "filament/$n.filamat").readBytes().let { b ->
            Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine) }
        val lit = mat("street_lit"); val proxy = mat("shadow_proxy")
        val scene = engine.createScene()
        val props = PropLayer(engine, scene, lit, proxy)
        val cinema = CinemaLayer(props)
        val at = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)
        cinema.add(lm.model, at)
        val animator = LandmarkAnimator.add(props, cinema, lm, at)
        val swap = engine.createSwapChain(320, 180, SwapChainFlags.CONFIG_READABLE)
        val renderer = engine.createRenderer()
        val camera = engine.createCamera(EntityManager.get().create())
        val view = engine.createView().also { it.scene = scene; it.camera = camera; it.viewport = Viewport(0, 0, 320, 180) }
        val look = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

        // the walk: from in front of the lot (its front edge at model z 0, the model towards -z) straight in along its
        // middle, the feet on the field: up its front steps (a riser at most 14 up a frame)
        val z0 = lm.front + 60f; val z1 = lm.front - 560f
        var feet = 0f
        var seconds = 0.0
        fun frame(z: Float, dt: Float) {
            val f = field.top(0f, z, feet + 14f)
            feet = if (f.isNaN()) 0f else kotlin.math.max(0f, f)
            // (the camera behind and above, as the open world's orbit stands it)
            SeeThrough.aim(0f, z, 0f, 200f + feet, z + 400f, hidden = true, dt = dt, feet = feet)
            camera.lookAt(0.0, 200.0 + feet, z + 400.0, 0.0, 26.0 + feet, z.toDouble(), 0.0, 1.0, 0.0)
            animator?.update(17.6f, seconds, .2f, active = true)
            props.update(look, .2f, shown = true)
            cinema.update(.2f, seconds)
            if (renderer.beginFrame(swap, 0L)) { renderer.render(view); renderer.endFrame() }
            seconds += dt
        }
        data class Held(val textures: Int, val buffers: Int, val pages: Int)
        fun held() = Held(GpuAccount.entries().size, GpuAccount.bufferEntries().size, BufferArena.stats().pages)

        val steps = 300
        for (i in 0 until 5) frame(z0, 1f / 60f)
        engine.flushAndWait()
        val first = held(); val rss0 = rssMb()
        var top = 0f
        for (i in 0 until steps) { frame(z0 + (z1 - z0) * i / steps, 1f / 60f); top = kotlin.math.max(top, feet) }
        engine.flushAndWait()
        val walked = held()
        for (i in 0 until steps) frame(z1, 1f / 60f)
        engine.flushAndWait()
        val stood = held(); val rss1 = rssMb()

        props.destroy()
        engine.destroyRenderer(renderer); engine.destroySwapChain(swap); engine.destroyView(view)
        engine.destroyCameraComponent(camera.entity)
        engine.destroyMaterial(lit); engine.destroyMaterial(proxy); engine.destroyScene(scene)
        engine.destroy()

        assertTrue("the walk climbed its first steps (feet up to $top)", top > 10f)
        assertEquals("walking up the steps made GPU objects", first, walked)
        assertEquals("standing on the steps made GPU objects", first, stood)
        assertTrue("memory grew ${rss1 - rss0} MB over ${2 * steps} frames", rss1 - rss0 < 150)
    }
}
