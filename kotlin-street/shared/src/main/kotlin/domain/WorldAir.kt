package com.lifemax.rpg.feature.habits.domain

import kotlin.math.exp

/**
 * The air's haze (WORLD_GEN.md W5a, the fix of W4's open problem 3; "Seeing land from the sea": visibility by weather): how
 * far one sees through it, as meteorology states it. [visibilityM] is the meteorological visibility at sea level (where
 * a dark object still shows against the sky at 2% contrast: Koschmieder's 3.912 / extinction), and the haze thins with
 * height over [scaleHeightM] (the aerosols' scale height), so a mountain 40 km off is hazed by the air along the way to
 * it, as the low ground at the same distance is, not crisp over a ground haze. The weather (phase W10) sets it; until
 * then a clear day.
 */
data class WorldAir(val visibilityM: Double = CLEAR_M, val scaleHeightM: Double = 1400.0) {
    /** The extinction at sea level (per metre). */
    val extinction: Double get() = 3.912 / visibilityM

    /** The extinction at [altitudeM] above sea level (per metre). */
    fun extinctionAt(altitudeM: Double): Double = extinction * exp(-altitudeM / scaleHeightM)

    /**
     * How much of a thing's own light comes through [distanceM] of air on a level line at [altitudeM] (1: none lost): the
     * share the haze leaves.
     */
    fun transmittance(distanceM: Double, altitudeM: Double): Double = exp(-extinctionAt(altitudeM) * distanceM)

    companion object {
        /** A clear day's visibility at sea level (m): land to the far view's horizon, the farthest hazed blue. */
        const val CLEAR_M = 90_000.0
        val CLEAR = WorldAir()
        val HAZY = WorldAir(25_000.0)
        val RAIN = WorldAir(6_000.0, 900.0)
        val FOG = WorldAir(400.0, 120.0)
    }
}
