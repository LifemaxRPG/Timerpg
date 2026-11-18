package com.timerpg.street.shot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ShotRequestsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // 2026-11-18 14:05:09.120 UTC
    private val t = 1795010709120L

    @Test
    fun pathsAreDatedAndSlugged() {
        assertEquals("screenshots/2026-11-18/140509-the-gym-s-balcony-looks-flat.png", ShotRequests.shotPath(t, utc, "The gym's balcony looks FLAT!"))
        assertEquals("screenshots/2026-11-18/140509-shot.png", ShotRequests.shotPath(t, utc, null))
        assertEquals("screenshots/2026-11-18/140509-shot.png", ShotRequests.shotPath(t, utc, "  ✨ ✨  "))
        assertEquals("screenshots/2026-11-18/140509-rain-2.webp", ShotRequests.shotPath(t, utc, "rain", "webp", attempt = 1))
        assertEquals("screenshots/2026-11-18/140509-rain.json", ShotRequests.siblingJson(ShotRequests.shotPath(t, utc, "rain")))
        // the phone's own zone sets the date and time
        assertEquals("screenshots/2026-11-19/010509-x.png", ShotRequests.shotPath(t, TimeZone.getTimeZone("GMT+11"), "x"))
        assertEquals("crashes/2026-11-18/140509-low-memory.json", ShotRequests.crashPath(t, utc, "low-memory"))
        assertEquals("crashes/2026-11-18/140509-crash.json", ShotRequests.crashPath(t, utc, "!!"))
        assertTrue(ShotRequests.slug("a".repeat(100)).length <= 32)
        assertFalse(ShotRequests.slug("abc -- ").endsWith("-"))
    }

    @Test
    fun urlsAndHeaders() {
        assertEquals("https://api.github.com/repos/lifemaxrpg/timerpg/contents/screenshots/2026-11-18/140509-x.png",
            ShotRequests.contentsUrl("screenshots/2026-11-18/140509-x.png"))
        assertEquals("https://api.github.com/repos/lifemaxrpg/timerpg/git/ref/heads/screenshots", ShotRequests.refUrl("screenshots"))
        assertEquals("https://api.github.com/repos/lifemaxrpg/timerpg/git/refs", ShotRequests.refsUrl())
        val h = ShotRequests.headers("tok")
        assertEquals("Bearer tok", h["Authorization"])
        assertEquals("application/vnd.github+json", h["Accept"])
        assertEquals("2022-11-28", h["X-GitHub-Api-Version"])
        val ref = JSONObject(ShotRequests.createRefBody("reports", "abc123"))
        assertEquals("refs/heads/reports", ref.getString("ref"))
        assertEquals("abc123", ref.getString("sha"))
    }

    @Test
    fun base64MatchesTheJdk() {
        val r = java.util.Random(7)
        for (n in listOf(0, 1, 2, 3, 4, 5, 255, 256, 1000, 4097)) {
            val b = ByteArray(n).also { r.nextBytes(it) }
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), ShotRequests.base64(b).toString())
        }
    }

    @Test
    fun putBodyCarriesTheFile() {
        val bytes = ByteArray(5000) { (it * 31).toByte() }
        val body = JSONObject(String(ShotRequests.putBody("Screenshot \"quoted\"\nline", bytes, "screenshots"), Charsets.UTF_8))
        assertEquals("Screenshot \"quoted\"\nline", body.getString("message"))
        assertEquals("screenshots", body.getString("branch"))
        assertTrue(java.util.Base64.getDecoder().decode(body.getString("content")).contentEquals(bytes))
    }

    @Test
    fun jsonWriterEscapesAndOrders() {
        val m = linkedMapOf<String, Any?>("b" to 1, "a" to "x\"\\\n\t\u0001é", "n" to null, "f" to 17.5f, "i" to 3f, "nan" to Float.NaN,
            "l" to listOf(1, true, "z"), "m" to emptyMap<String, Any>(), "raw" to Json.Raw("{\"k\": [1]}"))
        val s = Json.write(m)
        val o = JSONObject(s)
        assertEquals("x\"\\\n\t\u0001é", o.getString("a"))
        assertTrue(o.isNull("n") && o.isNull("nan"))
        assertEquals(17.5, o.getDouble("f"), 1e-9)
        assertEquals("3", o.get("i").toString())
        assertEquals(3, o.getJSONArray("l").length())
        assertEquals(1, o.getJSONObject("raw").getJSONArray("k").getInt(0))
        assertTrue(s.indexOf("\"b\"") < s.indexOf("\"a\""))
        // pretty output is the same JSON
        assertEquals(o.toString(), JSONObject(Json.write(m, pretty = true)).toString())
    }

    @Test
    fun shotPayloadHasTheGameState() {
        val s = ShotRequests.shotPayload(t, utc, "  look here ", mapOf("versionCode" to 412, "git" to "abc1234"),
            mapOf("format" to "png", "scale" to 1f, "bytes" to 123),
            linkedMapOf("screen" to mapOf("widthPx" to 3120), "game" to mapOf("renderer" to "Filament", "player" to mapOf("x" to 12.5f))))
        val o = JSONObject(s)
        assertEquals("screenshot", o.getString("kind"))
        assertEquals("2026-11-18T14:05:09.120+0000", o.getString("time"))
        assertEquals("look here", o.getString("note"))
        assertEquals(412, o.getJSONObject("app").getInt("versionCode"))
        assertEquals("png", o.getJSONObject("image").getString("format"))
        assertEquals(3120, o.getJSONObject("screen").getInt("widthPx"))
        assertEquals("Filament", o.getJSONObject("game").getString("renderer"))
        assertEquals(12.5, o.getJSONObject("game").getJSONObject("player").getDouble("x"), 1e-9)
        assertTrue(JSONObject(ShotRequests.shotPayload(t, utc, "  ", emptyMap(), emptyMap(), emptyMap())).isNull("note"))
    }

    @Test
    fun crashPayloadHasStackStateCrumbsAndLog() {
        val e = IllegalStateException("outer", RuntimeException("inner cause"))
        val crumbs = Breadcrumbs(4).apply { add(t - 2000, "corner turn start"); add(t - 1000, "corner turn end") }
        val s = ShotRequests.crashPayload("jvm", t, utc, mapOf("git" to "abc"), linkedMapOf("thread" to "main"), e.stackTraceToString(),
            mapOf("renderer" to "Filament"), crumbs.snapshot(), listOf("E/AndroidRuntime: FATAL"))
        val o = JSONObject(s)
        assertEquals("jvm", o.getString("kind"))
        assertEquals("main", o.getString("thread"))
        assertTrue(o.getString("stack").contains("inner cause"))
        assertEquals("Filament", o.getJSONObject("state").getString("renderer"))
        assertEquals("14:05:07.120 corner turn start", o.getJSONArray("breadcrumbs").getString(0))
        assertEquals(1, o.getJSONArray("logcat").length())
        // an exit record from Android: no stack lines, no log
        val x = JSONObject(ShotRequests.crashPayload("native", t, utc, emptyMap(), mapOf("reason" to 5), null, null, emptyList(), null))
        assertTrue(x.isNull("stack") && x.isNull("logcat") && x.isNull("state"))
    }

    @Test
    fun breadcrumbsKeepTheNewestInOrder() {
        val b = Breadcrumbs(3)
        assertEquals(0, b.snapshot().size)
        for (i in 1..5) b.add(i.toLong(), "e$i")
        assertEquals(listOf(3L to "e3", 4L to "e4", 5L to "e5"), b.snapshot())
        assertEquals(3, b.size())
        b.add(6, "with\nnewline")
        assertEquals(listOf(4L to "e4", 5L to "e5", 6L to "with newline"), Breadcrumbs.parse(b.serialize()))
        assertEquals(emptyList<Pair<Long, String>>(), Breadcrumbs.parse("junk\n\tx\n"))
    }

    @Test
    fun errorsReadForThePlayer() {
        assertEquals("No network", ShotRequests.explain(-1))
        assertEquals("Token can't write to the repo", ShotRequests.explain(403, "{\"message\":\"Resource not accessible by personal access token\"}"))
        assertTrue(ShotRequests.explain(401).startsWith("Token rejected"))
        assertEquals("Token can't see lifemaxrpg/timerpg", ShotRequests.explain(404))
        assertEquals("GitHub refused the file: Invalid request.", ShotRequests.explain(422, "{\"message\":\"Invalid request.\",\"x\":1}"))
        assertTrue(ShotRequests.explain(403, "API rate limit exceeded").contains("rate limit"))
    }
}
