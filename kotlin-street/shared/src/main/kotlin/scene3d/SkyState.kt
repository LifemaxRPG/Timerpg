package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.domain.MoonPhase
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SeasonState
import com.lifemax.rpg.feature.habits.domain.SkyPhase
import com.lifemax.rpg.feature.habits.domain.WeatherState
import com.lifemax.rpg.feature.habits.domain.skyPhase
import com.lifemax.rpg.feature.habits.domain.skyTone
import com.lifemax.rpg.feature.habits.domain.starsShown
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-Kotlin, zero-allocation-per-frame sky state for the Filament pixel-art sky dome (`sky.mat`),
 * town lighting, and ocean reflections (`OceanSurface`).
 *
 * Matches the game's existing sky grammar (`ScenePalette.SkyColors`, `Backdrop.kt`, `SkyDome.kt`,
 * `OrbitCamera.sunAzimuth`, `SceneLight.key`, `MoonPhase`, `StreetWeather`, `Season`):
 *  - Sun and moon directions are 3D world unit vectors (`+X` east, `+Y` up, `-Z` north, `+Z` south)
 *    matching `OrbitCamera.sunAzimuth` and `SceneLight.key(hour)` elevation at every hour.
 *  - During dusk (18:00..20:30), the setting sun stays at its exact west/west-northwest arc while
 *    the phased moon rises cleanly in the east so both can coexist at twilight and connect seamlessly
 *    into the night moon arc at 20:30 (`SceneLight.LOW = 10 deg`) with zero discontinuity.
 *  - Every sky colour is stored both as packed sRGB ARGB (`0xAARRGGBB`) and as pre-converted LINEAR
 *    RGB (`FloatArray(3)`) ready for Filament's `unlit` material + `LinearToneMapper`.
 *  - Calling [update] mutates pre-allocated fields in place and allocates **0 bytes per frame**.
 */
class SkyState {
    var hour: Float = 12f
        private set
    var timeSec: Float = 0f
        private set
    var phase: SkyPhase = SkyPhase.DAY
        private set

    /** 0 = night, 1 = dusk/dawn, 2 = day (matching [skyTone]). */
    var tone: Float = 2f
        private set

    /** 0 = day, ~0.55 = dusk/dawn, 1 = night (matching `SkyColors.dark`). */
    var dark: Float = 0f
        private set

    /** Dusk/dawn peak weight in 0..1 (matching `SkyDome.kt`). */
    var duskWeight: Float = 0f
        private set

    /** Star brightness in 0..1 after weather (cloudiness, mist, rain) attenuation. */
    var starBrightness: Float = 0f
        private set

    /** Moon phase fraction in 0..1 (0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter). */
    var moonPhase: Float = 0.5f
        private set

    /** Discretised moon phase step in 0..31 (matching [MoonPhase.step]). */
    var moonStep: Int = 16
        private set

    /** Moon illuminated fraction in 0..1 (matching [MoonPhase.illumination]). */
    var moonIllumination: Float = 1f
        private set

    /** Effective cloud coverage in 0..1 (fair-weather puffs on clear days up to full overcast deck). */
    var cloudCoverage: Float = 0.24f
        private set

    /** Overcast grey veil strength in 0..1 (matching `Backdrop.kt`). */
    var overcast: Float = 0f
        private set

    /** Low horizon mist density in 0..1 (from [WeatherState.mist]). */
    var mist: Float = 0f
        private set

    /** Rain dimming intensity in 0..1 (from [WeatherState.rain]). */
    var rain: Float = 0f
        private set

    /** Subtle winter sky crispness in 0..1 (from [SeasonState.snow]). */
    var winterCrisp: Float = 0f
        private set

    /** Sun visibility in 0..1 (1 by day/dusk, fades out past sunset). */
    var sunVisibility: Float = 1f
        private set

    /** Moon visibility in 0..1 (rises during dusk, 1 through night, fades out at dawn). */
    var moonVisibility: Float = 0f
        private set

    /** Sun/moon angular radii in radians (crisp pixel-art discs readable on desktop & phone). */
    var sunRadiusRad: Float = 0.052f
        private set
    var moonRadiusRad: Float = 0.054f
        private set

    /** Horizon glow alpha (0..1, matching `SkyColors.glow.alpha`). */
    var glowAlpha: Float = 0.5f
        private set

    /** Sun halo alpha (0..1, matching `SkyColors.halo.alpha`). */
    var haloAlpha: Float = 0.4f
        private set

    /** Warm under-belly tint strength on clouds during dusk/dawn (0..1). */
    var cloudUnderWarm: Float = 0f
        private set

    /** Maximum cloud opacity multiplier (0..1). */
    var cloudAlphaMax: Float = 1f
        private set

    /** World-space wind drift UV offsets for main & high cloud decks (updated from [timeSec]). */
    var cloudDriftX: Float = 0f
        private set
    var cloudDriftZ: Float = 0f
        private set
    var highCloudDriftX: Float = 0f
        private set
    var highCloudDriftZ: Float = 0f
        private set

    /** Unit vector pointing TOWARDS the sun in world space (+X east, +Y up, -Z north, +Z south). */
    val sunDir: FloatArray = FloatArray(3)

    /** Unit vector pointing TOWARDS the moon in world space (+X east, +Y up, -Z north, +Z south). */
    val moonDir: FloatArray = FloatArray(3)

    /**
     * Primary key-light direction pointing TOWARDS the active orb (sun by day/dusk, moon by night),
     * matching `OrbitCamera.sunAzimuth` and `SceneLight.key(hour).down` for direct lighting / ocean use.
     */
    val keyOrbDir: FloatArray = FloatArray(3)

    var sunAzimuthDeg: Float = 180f
        private set
    var sunElevationDeg: Float = 52f
        private set
    var moonAzimuthDeg: Float = 0f
        private set
    var moonElevationDeg: Float = -10f
        private set

    // Packed sRGB ARGB colours (0xAARRGGBB)
    var zenithArgb: Int = DAY_SKY_0
        private set
    var midArgb: Int = DAY_SKY_1
        private set
    var horizonArgb: Int = DAY_SKY_2
        private set
    var glowArgb: Int = DAY_GLOW
        private set
    var haloArgb: Int = DAY_HALO
        private set
    var orbArgb: Int = DAY_ORB
        private set
    var moonArgb: Int = NIGHT_ORB
        private set
    var belowHorizonArgb: Int = DAY_SKY_2
        private set
    var cloudLitArgb: Int = DAY_CLOUD_LIT
        private set
    var cloudBodyArgb: Int = DAY_CLOUD_BODY
        private set
    var cloudShadeArgb: Int = DAY_CLOUD_SHADE
        private set
    var overcastHiArgb: Int = 0xFF70757E.toInt()
        private set
    var overcastLoArgb: Int = 0xFFA5ABB5.toInt()
        private set
    var mistArgb: Int = 0xFFE2EFF9.toInt()
        private set

    // Pre-converted LINEAR RGB (for Filament unlit + LinearToneMapper)
    val zenithLin: FloatArray = FloatArray(3)
    val midLin: FloatArray = FloatArray(3)
    val horizonLin: FloatArray = FloatArray(3)
    val glowLin: FloatArray = FloatArray(3)
    val haloLin: FloatArray = FloatArray(3)
    val sunLin: FloatArray = FloatArray(3)
    val moonLin: FloatArray = FloatArray(3)
    val moonEarthLin: FloatArray = FloatArray(3)
    val belowHorizonLin: FloatArray = FloatArray(3)
    val duskEmberLin: FloatArray = FloatArray(3)
    val duskBeltLin: FloatArray = FloatArray(3)
    val overcastHiLin: FloatArray = FloatArray(3)
    val overcastLoLin: FloatArray = FloatArray(3)
    val mistLin: FloatArray = FloatArray(3)
    val cloudLitLin: FloatArray = FloatArray(3)
    val cloudBodyLin: FloatArray = FloatArray(3)
    val cloudShadeLin: FloatArray = FloatArray(3)

    // Ocean & lighting convenience outputs (linear RGB)
    val oceanSkyLin: FloatArray = FloatArray(3)
    val oceanGlintLin: FloatArray = FloatArray(3)
    var oceanGlintStrength: Float = 0.85f
        private set

    private var cachedDate: String? = null
    private var cachedMoonFraction: Float = 0.5f

    init {
        argbToLinear(EMBER_ARGB, duskEmberLin)
        argbToLinear(ROSE_ARGB, duskBeltLin)
        argbToLinear(MOON_EARTHSHINE_ARGB, moonEarthLin)
        update(12f, 0.5f, WeatherState.CLEAR, Season.SUMMER, 0f)
    }

    /**
     * Updates the sky state in place for [hour] (0..24), game [date] (`"yyyy-MM-dd"`), [weather],
     * [season], and elapsed [timeSec]. Caches parsed moon phase for [date] -> **0 bytes/frame**.
     */
    fun update(
        hour: Float,
        date: String,
        weather: WeatherState = WeatherState.CLEAR,
        season: SeasonState = Season.SUMMER,
        timeSec: Float = 0f,
    ) {
        if (date != cachedDate) {
            cachedDate = date
            cachedMoonFraction = MoonPhase.fraction(date)
        }
        update(hour, cachedMoonFraction, weather, season, timeSec)
    }

    /**
     * Primitive overload taking [moonPhaseFraction] directly (0 new, 0.25 first quarter, 0.5 full,
     * 0.75 last quarter). Strictly **0 bytes allocated** on every call.
     */
    fun update(
        hour: Float,
        moonPhaseFraction: Float,
        weather: WeatherState = WeatherState.CLEAR,
        season: SeasonState = Season.SUMMER,
        timeSec: Float = 0f,
    ) {
        val h = wrap24(hour)
        this.hour = h
        this.timeSec = timeSec
        this.phase = skyPhase(h)
        val t = skyTone(h)
        this.tone = t

        if (t <= 1f) {
            zenithArgb = lerpArgb(NIGHT_SKY_0, DUSK_SKY_0, t)
            midArgb = lerpArgb(NIGHT_SKY_1, DUSK_SKY_1, t)
            horizonArgb = lerpArgb(NIGHT_SKY_2, DUSK_SKY_2, t)
            glowArgb = lerpArgb(NIGHT_GLOW, DUSK_GLOW, t)
            haloArgb = lerpArgb(NIGHT_HALO, DUSK_HALO, t)
            orbArgb = lerpArgb(NIGHT_ORB, DUSK_ORB, t)
            cloudLitArgb = lerpArgb(NIGHT_CLOUD_LIT, DUSK_CLOUD_LIT, t)
            cloudBodyArgb = lerpArgb(NIGHT_CLOUD_BODY, DUSK_CLOUD_BODY, t)
            cloudShadeArgb = lerpArgb(NIGHT_CLOUD_SHADE, DUSK_CLOUD_SHADE, t)
            dark = lerpF(NIGHT_DARK, DUSK_DARK, t)
        } else {
            val u = t - 1f
            zenithArgb = lerpArgb(DUSK_SKY_0, DAY_SKY_0, u)
            midArgb = lerpArgb(DUSK_SKY_1, DAY_SKY_1, u)
            horizonArgb = lerpArgb(DUSK_SKY_2, DAY_SKY_2, u)
            glowArgb = lerpArgb(DUSK_GLOW, DAY_GLOW, u)
            haloArgb = lerpArgb(DUSK_HALO, DAY_HALO, u)
            orbArgb = lerpArgb(DUSK_ORB, DAY_ORB, u)
            cloudLitArgb = lerpArgb(DUSK_CLOUD_LIT, DAY_CLOUD_LIT, u)
            cloudBodyArgb = lerpArgb(DUSK_CLOUD_BODY, DAY_CLOUD_BODY, u)
            cloudShadeArgb = lerpArgb(DUSK_CLOUD_SHADE, DAY_CLOUD_SHADE, u)
            dark = lerpF(DUSK_DARK, DAY_DARK, u)
        }

        val autumn = season.autumn.coerceIn(0f, 1f)
        if (autumn > 0.01f && dark < 0.85f) {
            val aMix = autumn * 0.12f * (1f - dark)
            horizonArgb = lerpArgb(horizonArgb, 0xFFE8C8A2.toInt(), aMix)
            cloudLitArgb = lerpArgb(cloudLitArgb, 0xFFFFF0D8.toInt(), aMix * 0.8f)
        }

        glowAlpha = ((glowArgb ushr 24) and 0xFF) / 255f
        haloAlpha = ((haloArgb ushr 24) and 0xFF) / 255f
        duskWeight = smooth(0.04f, 0.42f, dark) * (1f - smooth(0.68f, 0.97f, dark))
        moonArgb = lerpArgb(NIGHT_ORB, 0xFFF5ECE2.toInt(), duskWeight * 0.45f)

        val wCloud = weather.cloudiness.coerceIn(0f, 1f)
        val wRain = weather.rain.coerceIn(0f, 1f)
        val wMist = weather.mist.coerceIn(0f, 1f)
        this.rain = wRain
        this.mist = wMist
        this.winterCrisp = season.snow.coerceIn(0f, 1f)
        this.overcast = clamp01(wCloud * 0.92f + wRain * 0.20f)
        this.cloudCoverage = clamp01(0.24f + 0.74f * max(wCloud, wRain * 0.9f))
        this.cloudAlphaMax = clamp01(1f - wMist * 0.25f)
        this.cloudUnderWarm = duskWeight * (1f - overcast * 0.75f) * (1f - wRain * 0.85f)

        val midDark = lerpArgb(midArgb, zenithArgb, 0.5f)
        val greyBase = lerpArgb(OVERCAST_BASE_ARGB, midDark, dark * 0.85f)
        overcastHiArgb = scaleRgbArgb(greyBase, 0.72f)
        overcastLoArgb = lerpArgb(greyBase, 0xFFFFFFFF.toInt(), 0.14f * (1f - dark))
        mistArgb = lerpArgb(horizonArgb, 0xFFFFFFFF.toInt(), 0.55f * (1f - dark * 0.55f))

        val openHaze = lerpArgb(horizonArgb, glowArgb or (0xFF shl 24), glowAlpha)
        belowHorizonArgb = lerpArgb(openHaze, overcastLoArgb, overcast * 0.82f)
        if (wRain > 0.01f) {
            belowHorizonArgb = scaleRgbArgb(belowHorizonArgb, 1f - wRain * 0.28f)
        }

        if (overcast > 0.01f) {
            val ovCloud = overcast * 0.75f
            cloudLitArgb = lerpArgb(cloudLitArgb, lerpArgb(overcastLoArgb, 0xFFFFFFFF.toInt(), 0.25f * (1f - dark)), ovCloud)
            cloudBodyArgb = lerpArgb(cloudBodyArgb, overcastLoArgb, ovCloud)
            cloudShadeArgb = lerpArgb(cloudShadeArgb, scaleRgbArgb(overcastHiArgb, 0.84f), ovCloud)
        }

        val baseStars = starsShown(t)
        val weatherClear = (1f - wCloud * 0.86f) * (1f - wMist * 0.82f) * (1f - wRain * 0.92f)
        this.starBrightness = clamp01(baseStars * clamp01(weatherClear))

        val ph = ((moonPhaseFraction % 1f) + 1f) % 1f
        this.moonPhase = ph
        this.moonStep = MoonPhase.step(ph)
        this.moonIllumination = MoonPhase.illumination(ph)

        val sunAz = sunAzimuthDegOf(h, moon = false)
        val moonAz = sunAzimuthDegOf(h, moon = true)
        this.sunAzimuthDeg = sunAz
        this.moonAzimuthDeg = moonAz

        val sunEl: Float
        val sunVis: Float
        if (h >= 5.5f && h < 20.5f) {
            val uSun = ((h - 5.5f) / 15f).coerceIn(0f, 1f)
            val upSun = sin(uSun * PI).toFloat()
            sunEl = SceneLight.LOW + (SceneLight.HIGH - SceneLight.LOW) * upSun
            sunVis = smooth(5.5f, 5.85f, h) * (1f - smooth(19.9f, 20.5f, h))
        } else {
            val nightMid = if (h >= 20.5f) (h - 20.5f) / 9f else (h + 3.5f) / 9f
            sunEl = -4f - 28f * sin(nightMid.coerceIn(0f, 1f) * PI).toFloat()
            sunVis = 0f
        }
        this.sunElevationDeg = sunEl
        this.sunVisibility = sunVis
        dirFromAzEl(sunAz, sunEl, sunDir)

        val moonEl: Float
        val moonVis: Float
        if (h < 5.5f || h >= 20.5f) {
            val uMoon = (((h + 3.5f) % 24f) / 9f).coerceIn(0f, 1f)
            val upMoon = sin(uMoon * PI).toFloat()
            moonEl = SceneLight.LOW + (SceneLight.HIGH - SceneLight.LOW) * upMoon
            moonVis = 1f
        } else if (h >= 17.8f) {
            val uRise = ((h - 17.8f) / (20.5f - 17.8f)).coerceIn(0f, 1f)
            moonEl = lerpF(3.5f, SceneLight.LOW, uRise)
            moonVis = smooth(17.8f, 19.1f, h) * 0.92f + 0.08f * smooth(19.1f, 20.5f, h)
        } else if (h <= 7.0f) {
            val uSet = ((h - 5.5f) / 1.5f).coerceIn(0f, 1f)
            moonEl = lerpF(SceneLight.LOW, 2.5f, uSet)
            moonVis = 1f - smooth(5.5f, 6.9f, h)
        } else {
            moonEl = -20f
            moonVis = 0f
        }
        this.moonElevationDeg = moonEl
        this.moonVisibility = moonVis
        dirFromAzEl(moonAz, moonEl, moonDir)

        val isNight = phase == SkyPhase.NIGHT
        if (isNight) {
            keyOrbDir[0] = moonDir[0]; keyOrbDir[1] = moonDir[1]; keyOrbDir[2] = moonDir[2]
        } else {
            keyOrbDir[0] = sunDir[0]; keyOrbDir[1] = sunDir[1]; keyOrbDir[2] = sunDir[2]
        }

        val windBoost = 1f + 0.65f * wCloud + 0.75f * wRain
        cloudDriftX = ((timeSec * 0.0042f * windBoost) % 1f + 1f) % 1f
        cloudDriftZ = ((timeSec * 0.0014f * windBoost) % 1f + 1f) % 1f
        highCloudDriftX = ((timeSec * 0.0021f * windBoost) % 1f + 1f) % 1f
        highCloudDriftZ = ((timeSec * 0.0007f * windBoost) % 1f + 1f) % 1f

        argbToLinear(zenithArgb, zenithLin)
        argbToLinear(midArgb, midLin)
        argbToLinear(horizonArgb, horizonLin)
        argbToLinear(glowArgb, glowLin)
        argbToLinear(haloArgb, haloLin)
        argbToLinear(orbArgb, sunLin)
        argbToLinear(moonArgb, moonLin)
        argbToLinear(belowHorizonArgb, belowHorizonLin)
        argbToLinear(overcastHiArgb, overcastHiLin)
        argbToLinear(overcastLoArgb, overcastLoLin)
        argbToLinear(mistArgb, mistLin)
        argbToLinear(cloudLitArgb, cloudLitLin)
        argbToLinear(cloudBodyArgb, cloudBodyLin)
        argbToLinear(cloudShadeArgb, cloudShadeLin)

        val oceanSkyArgb = lerpArgb(lerpArgb(midArgb, horizonArgb, 0.45f), overcastLoArgb, overcast * 0.85f)
        argbToLinear(oceanSkyArgb, oceanSkyLin)
        argbToLinear(if (isNight) moonArgb else orbArgb, oceanGlintLin)
        val clearFactor = (1f - wCloud * 0.82f) * (1f - wMist * 0.65f) * (1f - wRain * 0.90f)
        val moonScale = if (isNight) (0.25f + 0.75f * moonIllumination) else 1f
        oceanGlintStrength = clamp01((0.85f + 0.15f * duskWeight) * clamp01(clearFactor) * moonScale)
    }

    companion object {
        const val NIGHT_SKY_0 = 0xFF050915.toInt()
        const val NIGHT_SKY_1 = 0xFF0B1330.toInt()
        const val NIGHT_SKY_2 = 0xFF1F2C5E.toInt()
        const val NIGHT_GLOW = 0x66FF9E5E.toInt()
        const val NIGHT_HALO = 0x2EE9EDFF.toInt()
        const val NIGHT_ORB = 0xFFE9EDFF.toInt()
        const val NIGHT_CLOUD_LIT = 0xFF6676A2.toInt()
        const val NIGHT_CLOUD_BODY = 0xFF29324F.toInt()
        const val NIGHT_CLOUD_SHADE = 0xFF10162C.toInt()
        const val NIGHT_DARK = 1f

        const val DUSK_SKY_0 = 0xFF1D2350.toInt()
        const val DUSK_SKY_1 = 0xFF6B4B7C.toInt()
        const val DUSK_SKY_2 = 0xFFE39A74.toInt()
        const val DUSK_GLOW = 0x80FFB27A.toInt()
        const val DUSK_HALO = 0x66FFC896.toInt()
        const val DUSK_ORB = 0xFFFFD9A8.toInt()
        const val DUSK_CLOUD_LIT = 0xFFFFE0C2.toInt()
        const val DUSK_CLOUD_BODY = 0xFFF0A58E.toInt()
        const val DUSK_CLOUD_SHADE = 0xFF8C5A86.toInt()
        const val DUSK_DARK = 0.55f

        const val DAY_SKY_0 = 0xFF2F66B8.toInt()
        const val DAY_SKY_1 = 0xFF6FA5DD.toInt()
        const val DAY_SKY_2 = 0xFFC4E2F5.toInt()
        const val DAY_GLOW = 0x80FFFFFF.toInt()
        const val DAY_HALO = 0x66FFF3C4.toInt()
        const val DAY_ORB = 0xFFFFF3C4.toInt()
        const val DAY_CLOUD_LIT = 0xFFFFFFFF.toInt()
        const val DAY_CLOUD_BODY = 0xFFF3F7FC.toInt()
        const val DAY_CLOUD_SHADE = 0xFFA9BCD8.toInt()
        const val DAY_DARK = 0f

        private const val EMBER_ARGB = 0xFFFF7A3C.toInt()
        private const val ROSE_ARGB = 0xFFD98A9E.toInt()
        private const val OVERCAST_BASE_ARGB = 0xFF9CA3AF.toInt()
        private const val MOON_EARTHSHINE_ARGB = 0xFF233056.toInt()

        fun sunAzimuthDegOf(hour: Float, moon: Boolean): Float {
            val shifted = if (moon) hour - 18f else hour - 6f
            val mod24 = ((shifted % 24f) + 24f) % 24f
            return 90f + mod24 * 15f
        }

        fun dirFromAzEl(azDeg: Float, elDeg: Float, out: FloatArray) {
            val az = azDeg * (PI / 180.0)
            val el = elDeg * (PI / 180.0)
            val cosEl = cos(el)
            out[0] = (sin(az) * cosEl).toFloat()
            out[1] = sin(el).toFloat()
            out[2] = (-cos(az) * cosEl).toFloat()
        }

        fun argbToLinear(argb: Int, out: FloatArray) {
            out[0] = srgbByteToLinear((argb ushr 16) and 0xFF)
            out[1] = srgbByteToLinear((argb ushr 8) and 0xFF)
            out[2] = srgbByteToLinear(argb and 0xFF)
        }

        fun srgbByteToLinear(v: Int): Float {
            val c = (v and 0xFF) / 255.0
            return (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
        }

        fun lerpArgb(a: Int, b: Int, t: Float): Int {
            val u = clamp01(t)
            val aa = (a ushr 24) and 0xFF; val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
            val ba = (b ushr 24) and 0xFF; val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
            val aOut = (aa + (ba - aa) * u + 0.5f).toInt().coerceIn(0, 255)
            val rOut = (ar + (br - ar) * u + 0.5f).toInt().coerceIn(0, 255)
            val gOut = (ag + (bg - ag) * u + 0.5f).toInt().coerceIn(0, 255)
            val bOut = (ab + (bb - ab) * u + 0.5f).toInt().coerceIn(0, 255)
            return (aOut shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }

        fun scaleRgbArgb(argb: Int, k: Float): Int {
            val a = (argb ushr 24) and 0xFF
            val r = (((argb ushr 16) and 0xFF) * k + 0.5f).toInt().coerceIn(0, 255)
            val g = (((argb ushr 8) and 0xFF) * k + 0.5f).toInt().coerceIn(0, 255)
            val b = ((argb and 0xFF) * k + 0.5f).toInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun wrap24(hour: Float): Float = ((hour % 24f) + 24f) % 24f
        private fun clamp01(v: Float): Float = max(0f, min(1f, v))
        private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp01(t)
        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val u = clamp01((x - e0) / (e1 - e0))
            return u * u * (3f - 2f * u)
        }
    }
}
