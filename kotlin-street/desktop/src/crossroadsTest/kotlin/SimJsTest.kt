import com.lifemax.rpg.feature.habits.simjs.SimJs
import com.lifemax.rpg.feature.habits.simjs.SimJsException
import com.lifemax.rpg.feature.habits.simjs.SimJsThread
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The JavaScript bridge (docs/LOOP.md): assets/sim/sim.js in QuickJS-ng through SimJs gives what the same calls give
 * in Node (tools/sim-smoke.mjs), JS errors arrive as SimJsException with their stack, and the memory limit holds.
 * gradle :desktop:crossroadsTest --tests SimJsTest
 */
class SimJsTest {
    private val bundle: String by lazy { File(System.getProperty("timerpg.simjs.bundle") ?: error("-Dtimerpg.simjs.bundle")).readText() }

    /** Node's run of tools/sim-smoke.mjs (null where there is no node). */
    private fun nodeRun(): JSONObject? {
        val script = System.getProperty("timerpg.simjs.smoke") ?: return null
        val p = runCatching {
            ProcessBuilder(listOf("node", script, System.getProperty("timerpg.simjs.bundle")) +
                // (-Dtimerpg.simjs.smokeArgs="--people 3000 --days 60": a bigger town for timing)
                (System.getProperty("timerpg.simjs.smokeArgs") ?: "").split(' ').filter(String::isNotBlank)).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        }.getOrNull() ?: return null
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "sim-smoke.mjs failed" }
        return JSONObject(out)
    }

    @Test fun sameResultsAsNode() {
        val node = nodeRun()
        assumeTrue("node is not installed", node != null)
        node!!
        val calls = node.getJSONArray("calls")
        val people = node.getInt("people")
        val benchDays = node.getInt("benchDays")
        SimJsThread().use { sim ->
            sim.load(bundle).get()
            val times = ArrayList<Double>()
            var benchCreated = false
            var memory = 0L
            var exact = 0
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.getString("fn")
                val arg = c.getString("arg")
                if (fn == "simCreate" && arg.contains("bridge-bench")) benchCreated = true
                val t0 = System.nanoTime()
                val got = sim.call(fn, arg).get()
                if (benchCreated && fn == "simAdvance") times += (System.nanoTime() - t0) / 1e6
                val want = c.getString("out")
                if (want == got) exact++ else assertSameJson("call $i: $fn($arg)", JSONTokener(want).nextValue(), JSONTokener(got).nextValue(), "$")
                if (fn == "simKnowers") memory = maxOf(memory, sim.run { it.memoryUsed }.get())
            }
            // the smoke part's last answer: three days on, word has spread and reports are in
            val k = JSONObject(calls.getJSONObject(8).getString("out"))
            assertEquals(3, k.getInt("day"))
            assertTrue("knowers grew", k.getJSONArray("knowers").length() > 3)
            assertTrue("reports", k.getJSONArray("reports").length() + k.getJSONArray("pending").length() > 0)
            assertEquals(benchDays, times.size)
            println("SimJsTest: ${calls.length()} calls, $exact with byte-identical JSON, the rest equal within 1e-12 (last-digit libm differences)")
            println("SimJsTest: QuickJS advanceDay, $people people, $benchDays days: mean %.2f ms, max %.2f ms; heap %.1f MB (this computer, not the S25)"
                .format(times.average(), times.max(), memory / 1048576.0))
            println("SimJsTest: Node (${node.getString("engine")}) advanceDay: mean %.2f ms, max %.2f ms"
                .format(node.getDouble("advanceDayMsMean"), node.getDouble("advanceDayMsMax")))
        }
    }

    /**
     * The same JSON, numbers equal within a relative 1e-12: QuickJS's Math.exp/pow/log come from the C library, V8's
     * from its own fdlibm port, and they can differ in the last bit. Everything else (keys, strings, who knows, which
     * reports) must be identical.
     */
    private fun assertSameJson(what: String, a: Any?, b: Any?, at: String) {
        when {
            a is JSONObject && b is JSONObject -> {
                assertEquals("$what: keys at $at", a.keySet().sorted(), b.keySet().sorted())
                for (k in a.keySet()) assertSameJson(what, a.get(k), b.get(k), "$at.$k")
            }
            a is JSONArray && b is JSONArray -> {
                assertEquals("$what: length at $at", a.length(), b.length())
                for (i in 0 until a.length()) assertSameJson(what, a.get(i), b.get(i), "$at[$i]")
            }
            a is Number && b is Number -> {
                val x = a.toDouble(); val y = b.toDouble()
                assertTrue("$what: $at $x vs $y", x == y || Math.abs(x - y) <= 1e-12 * maxOf(Math.abs(x), Math.abs(y)))
            }
            else -> assertEquals("$what: at $at", a, b)
        }
    }

    @Test fun errorsCarryTheirStack() {
        SimJs().use { sim ->
            sim.load("function boom(j) { return inner(JSON.parse(j)); }\nfunction inner(a) { throw new TypeError('bad ' + a.x); }", "t.js")
            val e = runCatching { sim.call("boom", """{"x":"ü→😀"}""") }.exceptionOrNull()
            assertTrue("$e", e is SimJsException)
            val m = e!!.message!!
            assertTrue(m, m.contains("TypeError: bad ü→😀") && m.contains("inner") && m.contains("t.js"))
            // a syntax error in a bundle, a missing function, the unloaded smoke API
            assertTrue(runCatching { sim.load("function (") }.exceptionOrNull() is SimJsException)
            assertTrue(runCatching { sim.call("nope", "{}") }.exceptionOrNull() is SimJsException)
            // strings round-trip as real UTF-8, and a non-string result is JSON
            sim.load("function echo(j) { return j; } function obj(j) { return { n: JSON.parse(j).length }; }")
            assertEquals("""["ü","😀","\u0000"]""", sim.call("echo", """["ü","😀","\u0000"]"""))
            assertEquals("""{"n":2}""", sim.call("obj", "[1,2]"))
        }
        SimJs().use { sim ->
            sim.load(bundle)
            val e = runCatching { sim.call("simAdvance", """{"days":1}""") }.exceptionOrNull()
            assertTrue("$e", e is SimJsException && e.message!!.contains("simCreate first"))
        }
    }

    @Test fun memoryLimitHolds() {
        SimJs(memoryLimitBytes = 16L shl 20).use { sim ->
            sim.load("function grow(j) { const a = []; for (;;) a.push(new Array(1000).fill(1)); }")
            val e = runCatching { sim.call("grow", "{}") }.exceptionOrNull()
            assertTrue("$e", e is SimJsException && e.message!!.contains("out of memory"))
            assertTrue(sim.memoryUsed <= 16L shl 20)
        }
        // (and the executor reports it as the cause)
        SimJsThread(memoryLimitBytes = 16L shl 20).use { sim ->
            sim.load("function grow(j) { const a = []; for (;;) a.push(new Array(1000).fill(1)); }").get()
            val e = runCatching { sim.call("grow", "{}").get() }.exceptionOrNull()
            assertTrue("$e", e is ExecutionException && e.cause is SimJsException)
        }
    }
}
