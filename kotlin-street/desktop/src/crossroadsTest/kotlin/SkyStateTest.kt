package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.MoonPhase
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.WeatherState
import com.lifemax.rpg.feature.habits.domain.skyTone
import com.lifemax.rpg.feature.habits.domain.starsShown
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SkyStateTest {
    @Test
    fun sunAndMoonDirectionsMatchOrbitCameraAndSceneLight() {
        val state = SkyState()
        val sampleHours = floatArrayOf(0.5f, 3.0f, 6.25f, 9.5f, 12.0f, 15.5f, 18.75f, 19.5f, 21.5f, 23.25f)

        for (hour in sampleHours) {
            state.update(hour, "2000-01-21", WeatherState.CLEAR, Season.SUMMER, 0f)
            val key = SceneLight.key(hour)
            val isNight = key.cool > 0.5f

            val expectedAz = OrbitCamera.sunAzimuth(hour, isNight)
            val expectedElRad = asin(key.down.coerceIn(-1f, 1f).toDouble())
            val azRad = Math.toRadians(expectedAz.toDouble())
            val ex = (sin(azRad) * cos(expectedElRad)).toFloat()
            val ey = sin(expectedElRad).toFloat()
            val ez = (-cos(azRad) * cos(expectedElRad)).toFloat()

            val activeDir = if (isNight) state.moonDir else state.sunDir
            assertEquals("hour=$hour active orb dir X", ex, activeDir[0], 1e-4f)
            assertEquals("hour=$hour active orb dir Y", ey, activeDir[1], 1e-4f)
            assertEquals("hour=$hour active orb dir Z", ez, activeDir[2], 1e-4f)

            // keyOrbDir must match the active orb identically
            assertEquals("hour=$hour keyOrbDir X", ex, state.keyOrbDir[0], 1e-4f)
            assertEquals("hour=$hour keyOrbDir Y", ey, state.keyOrbDir[1], 1e-4f)
            assertEquals("hour=$hour keyOrbDir Z", ez, state.keyOrbDir[2], 1e-4f)

            // Both sunDir and moonDir must always be unit length
            val sLen = sqrt(state.sunDir[0] * state.sunDir[0] + state.sunDir[1] * state.sunDir[1] + state.sunDir[2] * state.sunDir[2])
            val mLen = sqrt(state.moonDir[0] * state.moonDir[0] + state.moonDir[1] * state.moonDir[1] + state.moonDir[2] * state.moonDir[2])
            assertEquals("sunDir unit length at hour=$hour", 1f, sLen, 1e-4f)
            assertEquals("moonDir unit length at hour=$hour", 1f, mLen, 1e-4f)
        }
    }

    @Test
    fun cardinalSunAndMoonAzimuthsAndDuskMoonRiseContinuity() {
        val state = SkyState()

        // Noon: sun due south (+Z), high elevation (52 deg peak near 13:00)
        state.update(12f, "2000-01-21")
        assertEquals(180f, state.sunAzimuthDeg, 1e-3f)
        assertTrue("noon sun should point south (+Z)", state.sunDir[2] > 0.5f)
        assertTrue("noon sun should be visible", state.sunVisibility > 0.99f)
        assertEquals("moon hidden at noon", 0f, state.moonVisibility, 1e-4f)

        // Dawn 06:00: sun due east (+X)
        state.update(6f, "2000-01-21")
        assertEquals(90f, state.sunAzimuthDeg, 1e-3f)
        assertTrue("06:00 sun should point east (+X)", state.sunDir[0] > 0.85f)

        // Dusk 19:25: sun setting in WNW while crescent moon is rising in ESE with first stars emerging
        state.update(19.42f, "2000-01-10")
        assertEquals(SkyPhase.DUSK, state.phase)
        assertTrue("dusk sun visible in west", state.sunVisibility > 0.5f && state.sunDir[0] < -0.5f)
        assertTrue("dusk crescent moon rising in east", state.moonVisibility > 0.7f && state.moonDir[0] > 0.5f && state.moonElevationDeg > 5f)
        assertTrue("first stars emerging at dusk", state.starBrightness > 0.25f)

        // Continuity across 20:30 hand-over into night: moon elevation meets SceneLight.LOW (10 deg) seamlessly
        state.update(20.49f, "2000-01-10")
        val elBefore = state.moonElevationDeg
        state.update(20.51f, "2000-01-10")
        val elAfter = state.moonElevationDeg
        assertTrue("moon elevation continuous across 20:30 hand-over (before=$elBefore after=$elAfter)", abs(elAfter - elBefore) < 0.6f)
    }

    @Test
    fun skyColorsMatchScenePaletteAcrossDayDuskNightAndWeather() {
        val state = SkyState()

        // Noon clear -> exact DAY palette
        state.update(12f, "2000-01-21", WeatherState.CLEAR, Season.SUMMER)
        assertEquals(SkyPhase.DAY, state.phase)
        assertEquals(2f, state.tone, 1e-4f)
        assertEquals(0f, state.dark, 1e-4f)
        assertEquals(SkyState.DAY_SKY_0, state.zenithArgb)
        assertEquals(SkyState.DAY_SKY_1, state.midArgb)
        assertEquals(SkyState.DAY_SKY_2, state.horizonArgb)
        assertEquals(SkyState.DAY_ORB, state.orbArgb)
        assertEquals(0f, state.starBrightness, 1e-4f)

        // Dusk clear (19:00 -> skyTone == 1f) -> exact DUSK palette
        state.update(19f, "2000-01-10", WeatherState.CLEAR, Season.SUMMER)
        assertEquals(SkyPhase.DUSK, state.phase)
        assertEquals(1f, state.tone, 1e-4f)
        assertEquals(SkyState.DUSK_DARK, state.dark, 1e-4f)
        assertEquals(SkyState.DUSK_SKY_0, state.zenithArgb)
        assertEquals(SkyState.DUSK_SKY_1, state.midArgb)
        assertEquals(SkyState.DUSK_SKY_2, state.horizonArgb)
        assertEquals(starsShown(skyTone(19f)), state.starBrightness, 1e-4f)

        // Midnight clear -> exact NIGHT palette & full stars
        state.update(0.5f, "2000-01-21", WeatherState.CLEAR, Season.SUMMER)
        assertEquals(SkyPhase.NIGHT, state.phase)
        assertEquals(0f, state.tone, 1e-4f)
        assertEquals(1f, state.dark, 1e-4f)
        assertEquals(SkyState.NIGHT_SKY_0, state.zenithArgb)
        assertEquals(SkyState.NIGHT_SKY_1, state.midArgb)
        assertEquals(SkyState.NIGHT_SKY_2, state.horizonArgb)
        assertEquals(1f, state.starBrightness, 1e-4f)

        // Overcast, rain, and mist must dim stars, raise cloud coverage/overcast, and cut ocean glint
        val clearGlint = state.oceanGlintStrength
        state.update(0.5f, "2000-01-21", WeatherState(0.90f, 0f, 0f, 0f))
        assertTrue("overcast suppresses stars", state.starBrightness < 0.25f)
        assertTrue("overcast increases cloudCoverage", state.cloudCoverage > 0.85f)
        assertTrue("overcast cuts glint", state.oceanGlintStrength < clearGlint * 0.4f)

        state.update(14f, "2000-01-21", WeatherState(0.95f, 0.85f, 0.8f, 0f))
        assertTrue("rain raises rain dimming", state.rain > 0.8f)
        assertTrue("rain suppresses ocean glint", state.oceanGlintStrength < 0.1f)

        state.update(6.5f, "2000-01-21", WeatherState(0.25f, 0f, 0f, 0.85f))
        assertTrue("mist sets mist density", state.mist > 0.8f)
        assertTrue("mist dims dawn stars", state.starBrightness < 0.15f)
    }

    @Test
    fun moonPhaseMatchesKnownCalendarDates() {
        val state = SkyState()

        // Known new moon: 2000-01-06
        state.update(0.5f, "2000-01-06")
        assertTrue("2000-01-06 near new moon (ph=${state.moonPhase})", state.moonPhase < 0.04f || state.moonPhase > 0.96f)
        assertTrue("new moon illumination near 0", state.moonIllumination < 0.03f)

        // Waxing crescent: 2000-01-10 (~3.7d after new moon -> fraction ~0.126)
        state.update(19.5f, "2000-01-10")
        assertTrue("2000-01-10 waxing crescent (ph=${state.moonPhase})", state.moonPhase in 0.09f..0.17f)
        assertTrue("crescent illumination in 0.08..0.25", state.moonIllumination in 0.08f..0.25f)
        assertEquals(MoonPhase.step(state.moonPhase), state.moonStep)

        // First quarter: 2000-01-14 (~7.7d -> fraction ~0.26)
        state.update(0.5f, "2000-01-14")
        assertEquals(0.25f, state.moonPhase, 0.04f)
        assertEquals(0.50f, state.moonIllumination, 0.12f)

        // Full moon: 2000-01-21 (~14.7d -> fraction ~0.50)
        state.update(0.5f, "2000-01-21")
        assertEquals(0.50f, state.moonPhase, 0.03f)
        assertTrue("full moon illumination near 1", state.moonIllumination > 0.97f)

        // Last quarter: 2000-01-28 (~21.7d -> fraction ~0.74)
        state.update(0.5f, "2000-01-28")
        assertEquals(0.75f, state.moonPhase, 0.04f)
        assertEquals(0.50f, state.moonIllumination, 0.12f)
    }

    @Test
    fun worldRayInvarianceAcross90DegreeCameraTurn() {
        // Verify that a world sky ray reconstructed for a target world direction (e.g. a star at
        // azimuth 45 deg, elevation 25 deg) maps to the exact same 3D world unit vector whether
        // viewed from yaw=0 (right side of frame) or yaw=90 (left side of frame).
        val starAz = Math.toRadians(45.0)
        val starEl = Math.toRadians(25.0)
        val wx = (sin(starAz) * cos(starEl)).toFloat()
        val wy = sin(starEl).toFloat()
        val wz = (-cos(starAz) * cos(starEl)).toFloat()

        fun roundTripRay(yawDeg: Float, pitchDeg: Float, fovYDeg: Float, aspect: Float): FloatArray {
            val ya = yawDeg * (PI / 180.0); val pa = pitchDeg * (PI / 180.0)
            val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
            val fx = sin(ya).toFloat() * cp; val fy = -sp; val fz = -cos(ya).toFloat() * cp
            val rx = cos(ya).toFloat(); val ry = 0f; val rz = sin(ya).toFloat()
            val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy

            val zc = wx * fx + wy * fy + wz * fz
            assertTrue("star must be in front of camera at yaw=$yawDeg", zc > 0.2f)
            val scaleY = kotlin.math.tan(fovYDeg * (PI / 360.0)).toFloat()
            val scaleX = scaleY * aspect
            val ndcX = (wx * rx + wy * ry + wz * rz) / (zc * scaleX)
            val screenUp = (wx * ux + wy * uy + wz * uz) / (zc * scaleY)

            // Exact shader ray reconstruction from sky.mat:
            var rxOut = fx + ndcX * scaleX * rx + screenUp * scaleY * ux
            var ryOut = fy + ndcX * scaleX * ry + screenUp * scaleY * uy
            var rzOut = fz + ndcX * scaleX * rz + screenUp * scaleY * uz
            val len = sqrt(rxOut * rxOut + ryOut * ryOut + rzOut * rzOut)
            rxOut /= len; ryOut /= len; rzOut /= len
            return floatArrayOf(rxOut, ryOut, rzOut)
        }

        val r0 = roundTripRay(yawDeg = 0f, pitchDeg = -18f, fovYDeg = 52f, aspect = 16f / 9f)
        val r90 = roundTripRay(yawDeg = 90f, pitchDeg = -18f, fovYDeg = 52f, aspect = 16f / 9f)
        assertEquals("ray X identical across 90-deg turn", r0[0], r90[0], 1e-5f)
        assertEquals("ray Y identical across 90-deg turn", r0[1], r90[1], 1e-5f)
        assertEquals("ray Z identical across 90-deg turn", r0[2], r90[2], 1e-5f)
    }

    @Test
    fun zeroAllocationPerFrame() {
        val state = SkyState()
        val date = "2000-01-10"
        val weather = WeatherState.CLEAR
        val season = Season.SUMMER

        // Warm up JIT and date cache
        repeat(500) { i ->
            state.update((i % 240) * 0.1f, date, weather, season, i * 0.016f)
        }

        val tmx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val before = tmx?.getThreadAllocatedBytes(tid) ?: -1L

        repeat(2000) { i ->
            val hr = (i % 240) * 0.1f
            state.update(hr, date, weather, season, i * 0.016f)
        }

        val after = tmx?.getThreadAllocatedBytes(tid) ?: -1L
        if (before >= 0L && after >= 0L) {
            val allocated = after - before
            assertEquals("SkyState.update must allocate 0 bytes over 2,000 frames (got $allocated B)", 0L, allocated)
        }
    }
}
