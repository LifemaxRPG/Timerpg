import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The freeze of 0.1.805 on the S25 (2026-11-24, 050341: walking up the Great Meridian's steps, frames to 0, native
 * memory 157 MB to 5.2 GB in ten seconds, "not responding"): Filament 1.81.0 keeps a debug tag for every GPU handle in
 * a tsl::robin_map keyed by the handle's id, whose two age bits sit at bits 27-28, above any table size it ever has.
 * A slot of the handle pool reused across ages adds keys that land in the same bucket; once a few thousand of the
 * smallest pool's slots (descriptor sets and uniform buffers: two of them a material instance) have been reused that
 * way, one insert's probe passes 8192 and the map rehashes, doubling, without end, on the driver's thread, while the
 * main thread waits in Renderer.beginFrame. The town's streaming (material instances made and destroyed as cells,
 * yards and details come and go) gets there after a minute or so.
 *
 * Fixed by not keeping those tags (tools/patch-filament.sh: the phone's AAR and the desktop build). Here the same churn
 * runs in a child JVM ([HandleTagChurn]: 12,000 material instances made, drawn and destroyed six times over); unfixed it
 * never finishes and its memory climbs by gigabytes, so the child is killed after its time.
 */
class FilamentHandleTagTest {
    @Test fun materialInstanceChurnStaysBounded() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx512m", "-Djava.awt.headless=true",
            "-Djava.library.path=" + System.getProperty("java.library.path"),
            "-cp", System.getProperty("java.class.path"), "HandleTagChurn", "12000", "6")
            .redirectErrorStream(true).also { it.environment()["EGL_PLATFORM"] = "surfaceless" }.start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }.also { it.start() }
        val finished = p.waitFor(120, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly().waitFor()
        reader.join(2000)
        val text = synchronized(out) { out.toString() }
        println(text.lines().filter { it.startsWith("start") || it.startsWith("round") || it == "done" }.joinToString("; "))
        val rss = Regex("rss (\\d+)").findAll(text).map { it.groupValues[1].toLong() }.toList()
        assertTrue("the churn never finished (the tag map's endless rehash?):\n$text", finished && text.contains("done"))
        // (each round makes and frees the same 12,000 instances: memory after the first round holds level)
        assertTrue("memory grew round on round: $rss\n$text", rss.size >= 7 && rss.last() - rss[1] < 200)
    }
}
