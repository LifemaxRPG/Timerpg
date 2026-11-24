import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.SwapChainFlags
import java.io.File
import java.nio.ByteBuffer

/**
 * The churn behind the S25's freeze of 0.1.805 (FilamentHandleTagTest): [count] material instances made, drawn once
 * (Filament commits each one's descriptor set in beginFrame) and destroyed, [rounds] times over, the same handle slots
 * reused each round with the next age. Prints the process's resident memory after each round ("round N rss M"), then
 * "done". Run in a child JVM: with Filament's handle debug tags unpatched, a round never returns (the tag map rehashes
 * without end) and the resident memory climbs by gigabytes.
 */
object HandleTagChurn {
    fun rssMb(): Long = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter(Char::isDigit).toLong() / 1024 }.getOrDefault(-1L)

    @JvmStatic fun main(args: Array<String>) {
        val count = args.getOrNull(0)?.toInt() ?: 12000
        val rounds = args.getOrNull(1)?.toInt() ?: 6
        val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }
        Filament.init()
        val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine.config()).build()
        val bytes = File(assets, "filament/street_lit.filamat").readBytes()
        val mat = Material.Builder().payload(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip(), bytes.size).build(engine)
        val swap = engine.createSwapChain(64, 64, SwapChainFlags.CONFIG_READABLE)
        val renderer = engine.createRenderer()
        val scene = engine.createScene()
        val view = engine.createView().also { it.scene = scene; it.camera = engine.createCamera(com.google.android.filament.EntityManager.get().create()) }
        view.viewport = com.google.android.filament.Viewport(0, 0, 64, 64)
        fun frame() { if (renderer.beginFrame(swap, 0L)) { renderer.render(view); renderer.endFrame() }; engine.flushAndWait() }
        println("start rss ${rssMb()}")
        for (r in 0 until rounds) {
            val mis = ArrayList<MaterialInstance>(count)
            repeat(count) { mis += mat.createInstance() }
            frame()
            for (m in mis) engine.destroyMaterialInstance(m)
            frame()
            println("round $r rss ${rssMb()}")
            System.out.flush()
        }
        engine.destroyMaterial(mat)
        println("done")
    }
}
