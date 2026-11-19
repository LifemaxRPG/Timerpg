package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene

/**
 * Something more [FilamentTown] draws besides its rows, yards, cinema and stores (the farms round the town: [TownFarms]),
 * added with [FilamentTown.addExtra]: built on the town's workers ([step] each frame on the engine's thread, uploading what
 * is ready), drawn each frame ([frame]), cut away when in the open world camera's way ([occlude], [fadeBetween]), freed
 * with the town ([destroy]). The town's hook is these few calls; everything else stays in the extra.
 */
interface TownExtra {
    /** What the town shares with it. */
    class Kit internal constructor(
        internal val engine: Engine, internal val scene: Scene, internal val lit: Material, internal val proxy: Material,
        internal val textures: StreetTextures, internal val pool: java.util.concurrent.ExecutorService,
    )

    /**
     * One frame: the camera ([pose], [look] its matrix), how dark, the hour and clock, the windows' night glow (r, g, b,
     * amount) and the glass tint the buildings get, and how far cut-away things ease this frame ([occStep]).
     */
    class Frame(val pose: TownPose, val look: FloatArray, val dark: Float, val hour: Float, val seconds: Double, val glow: FloatArray, val glass: FloatArray, val occStep: Float)

    /** Still building (the town's [FilamentTown.pending] counts it). */
    val pending: Boolean
    /** A line for previews' reports. */
    val report: String
    /** Starts work on the workers and uploads what is ready (the engine's thread). */
    fun step()
    /** Draws this frame; returns its draws, triangles and shadow casters into [out]. */
    fun frame(f: Frame, out: IntArray): IntArray
    /** The open world camera's cone this frame: five starts (x, y, z each) whose lines run to the eye ([ex], [ey], [ez]). */
    fun occlude(starts: FloatArray, ex: Float, ey: Float, ez: Float) {}
    /** Cuts away what the last [occlude] found nearer the player than [t] (a share of the way). */
    fun fadeBetween(t: Float) {}
    fun destroy()
}
