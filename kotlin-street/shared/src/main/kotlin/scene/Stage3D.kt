package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Crossroads
import com.lifemax.rpg.feature.habits.domain.KeyLight

/**
 * The hand-over from the stage to a 3D renderer of the street (the Filament trial, scene3d). While [listening], the stage
 * fills [frame] every frame it draws with the crossroads exactly as it would draw them (the camera, how much of each
 * street's cabinet look is left, both streets' layouts, people and light), so the 3D renderer draws the same frame; with
 * [StageTuning.skyOnly] the stage then draws only its sky under it. Nothing here depends on the 3D engine.
 */
object Stage3D {
    @Volatile var listening = false
    /**
     * A 3D renderer is building its scene (the walkable town's whole build): the stage leaves heavy work it can put off
     * (the far road's cars' art) until it is over, so the two never peak on the Java heap together.
     */
    @Volatile var townBuilding = false
    val frame = Frame3D()
    /**
     * Called on the stage's draw right after it filled [frame] (main thread): the 3D renderer draws then, so it shows the
     * same frame as the sky drawn under it, and draws nothing while the stage draws nothing (the app stopped, a room).
     */
    @Volatile var onFrame: (() -> Unit)? = null

    internal fun drawn() { onFrame?.invoke() }
}

/** One frame of the crossroads as the stage drew it. [n] counts frames, so a renderer can tell a new one. */
class Frame3D {
    var n = 0L; internal set
    internal var world: CornerWorld? = null
    internal var cr: Crossroads? = null
    internal var sides: List<CrossSide>? = null
    internal var sky: SkyColors? = null
    var light: KeyLight? = null; internal set
    var hour = 12f; internal set
    var seconds = 0f; internal set
    var width = 0f; internal set
    var height = 0f; internal set
    /** The ground line and the horizon (screen y), street units to pixels. */
    var ground = 0f; internal set
    var horizon = 0f; internal set
    var unit = 1f; internal set
    /** How dark it is (0 day, 1 night). */
    var dark = 0f; internal set
    /** The walkable town's frame ([fillTown]): no crossroads in it, only the hour, the clock, the sky and the screen. */
    var town = false; private set
    val ready: Boolean get() = (world != null && cr != null && sides != null) || (town && sky != null)

    /** Lets go of the stage's world (it left the street: a room), so nothing of it is drawn or kept from here. */
    fun clear() { world = null; cr = null; sides = null; sky = null; light = null; town = false }

    /** A frame of the walkable town (TOWN.md T10): the stage drew only its sky, the town's renderer draws the rest. */
    internal fun fillTown(
        sky: SkyColors, light: KeyLight, hour: Float, seconds: Float, width: Float, height: Float, ground: Float, horizon: Float, unit: Float,
    ) {
        world = null; cr = null; sides = null; town = true
        this.sky = sky; this.light = light
        this.hour = hour; this.seconds = seconds; this.width = width; this.height = height
        this.ground = ground; this.horizon = horizon; this.unit = unit; this.dark = sky.dark
        n++
    }

    internal fun fill(
        world: CornerWorld, cr: Crossroads, sides: List<CrossSide>, sky: SkyColors, light: KeyLight,
        hour: Float, seconds: Float, width: Float, height: Float, ground: Float, horizon: Float, unit: Float,
    ) {
        this.world = world; this.cr = cr; this.sides = sides; this.sky = sky; this.light = light; town = false
        this.hour = hour; this.seconds = seconds; this.width = width; this.height = height
        this.ground = ground; this.horizon = horizon; this.unit = unit; this.dark = sky.dark
        n++
    }
}
