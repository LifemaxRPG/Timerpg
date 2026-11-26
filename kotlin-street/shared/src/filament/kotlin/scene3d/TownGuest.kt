package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene

/**
 * A town drawn inside another town's world (planet step 2, docs/TOWNS_EVERYWHERE.md "Streaming"): a generated town on the
 * planet, in its own street units, drawn in the host's [scene] under [root], an entity whose transform is the town's frame
 * in the host's (a turn about y and a move: [setFrame]). Every entity the guest [FilamentTown] adds to the scene is made a
 * child of [root] ([TownRoot.adopt]), so the guest's code keeps its own coordinates, culls with the host's camera turned
 * into them, and Filament puts its vertices, lights and shadows where they stand on the planet. [workers]: the host's.
 */
class TownGuest internal constructor(val engine: Engine, val scene: Scene, internal val workers: TownWorkers?) {
    val root: Int = EntityManager.get().create().also { engine.transformManager.create(it) }
    private val m = FloatArray(16)

    /**
     * The town's frame in the host's: a local point (x, y, z) stands at host (ox + k (x c - z s), oy + k y, oz + k (x s + z c))
     * ([c], [s]: the cosine and sine of the turn, x east and z south, clockwise seen from above; [k]: host units a local one).
     */
    fun setFrame(c: Float, s: Float, k: Float, ox: Float, oy: Float, oz: Float) {
        m.fill(0f)
        m[0] = c * k; m[2] = s * k; m[5] = k; m[8] = -s * k; m[10] = c * k; m[15] = 1f
        m[12] = ox; m[13] = oy; m[14] = oz
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(root), m)
    }

    fun destroy() {
        engine.transformManager.destroy(root)
        EntityManager.get().destroy(root)
    }
}

/**
 * The root the entities made now belong to ([TownGuest.root]): set round every call into a guest town ([with]); 0 (none)
 * otherwise, when nothing is changed. The engine's thread only.
 */
object TownRoot {
    @JvmField var current = 0

    /** Makes entity [e] (just added to a scene) a child of [current], giving it a transform first if it has none. */
    fun adopt(engine: Engine, e: Int) {
        val r = current
        if (r == 0 || e == 0) return
        val tm = engine.transformManager
        var ti = tm.getInstance(e)
        if (ti == 0) { tm.create(e); ti = tm.getInstance(e) }
        tm.setParent(ti, tm.getInstance(r))
    }

    /** Runs [f] with [root] as the current root. */
    inline fun <T> with(root: Int, f: () -> T): T {
        val o = current
        current = root
        try { return f() } finally { current = o }
    }
}
