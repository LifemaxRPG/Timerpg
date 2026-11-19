package com.lifemax.rpg.feature.habits.presentation.scene

/**
 * What the game's own thread did this frame, part by part, for the frame-spike profiler (the app's `FrameSpikes`, the
 * desktop's bench): the time each part took (ns) and the uploads and builds it made. The engine's thread adds to it as it
 * works ([add], [count]); whoever closes the frame reads it and calls [reset]. One thread only (the main thread, which
 * runs the game loop and Filament's frame), so no locks; plain arrays, nothing allocated.
 */
object FrameWork {
    /** The game loop's step (the walk, the bike, the camera's easing, the people). */
    const val STEP = 0
    /** The town's build in the frame's budget: the ground's uploads, the buildings nearest the player. */
    const val BUILD = 1
    /** The camera against what could hide the player ([FilamentTown.occlude]) and the see-through cone's test. */
    const val OCCLUDE = 2
    /** The far boxes: their cells' uploads and the crossover. */
    const val FAR = 3
    /** The buildings' per-frame parameters (and their culling). */
    const val BUILDINGS = 4
    /** The windows' night light worked out and uploaded. */
    const val NIGHT = 5
    /** The ground's and the yards' layers and patches. */
    const val YARDS = 6
    /** The chunks' props and greenery, the trees' cells (their detail's uploads). */
    const val GREEN = 7
    /** The cinema, the stores (their cars), the lamps and the animations. */
    const val STORES = 8
    /** The people: who is where this frame (the street life worked out, [com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.people]). */
    const val PEOPLE = 9
    /** Filament's own frame on this thread (beginFrame, render, endFrame). */
    const val RENDER = 10
    /** The people's cards stood in the scene ([PEOPLE] is who is where: the street life worked out). */
    const val CARDS = 11
    val NAMES = arrayOf("step", "build", "occlude", "far", "buildings", "night", "yards", "green", "stores", "people", "render", "cards")

    /** Uploads this frame by kind: the trees' detailed cells, the far boxes' cells, buildings finished, people's sheets, ground jobs. */
    const val UP_TREES = 0
    const val UP_FAR = 1
    const val UP_BUILDINGS = 2
    const val UP_SHEETS = 3
    const val UP_GROUND = 4
    val UP_NAMES = arrayOf("tree cells", "far cells", "buildings", "people sheets", "ground jobs")

    @JvmField val ns = LongArray(NAMES.size)
    @JvmField val uploads = IntArray(UP_NAMES.size)
    /** Bytes handed to the GPU this frame (vertex, index and texture data), where the uploader knows them. */
    @JvmField var bytes = 0L

    fun add(part: Int, nanos: Long) { ns[part] += nanos }
    fun count(kind: Int, n: Int = 1) { uploads[kind] += n }

    fun reset() { java.util.Arrays.fill(ns, 0L); java.util.Arrays.fill(uploads, 0); bytes = 0L }

    /** The parts' sum (ms). */
    fun totalMs(): Float { var t = 0L; for (v in ns) t += v; return t / 1e6f }
}
