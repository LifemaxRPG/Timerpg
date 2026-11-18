package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.MaterialInstance
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownLane
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.TownYardItem

/**
 * Where the yards' builders plug into the Filament town ([FilamentTown.yardBuilder]): handed the town, every lot's yard and
 * the back lanes and verges (street.json v2 graph.yards and graph.lanes, [TownYards]), a builder adds what it makes to
 * [YardSink]. Everything it adds is static and drawn for the whole town (batch by look: a few dozen draws). The town uses
 * the yard kit ([YardKitBuilder]); [PlaceholderYards] lays each item as a flat colour (and objects as plain boxes) to check
 * the layout alone.
 *
 * The town leaves off the houses' and models' own front lawn and fence wherever a lot has a yard here.
 */
fun interface YardBuilder {
    fun build(town: Town, yards: TownYards, sink: YardSink)
}

/**
 * A patch of the town some yard meshes belong to (a lot, a lane): the ground circle round them ([x], [z], radius [r]) the
 * view culls by, and the building it goes with ([sprite]), whose street-view cut it shares (the camera's own side of the
 * road fades out, yards with their houses). Meshes without one are always drawn.
 */
class YardPatch(val id: String, val x: Float, val z: Float, val r: Float, val sprite: String? = null)

/** What a [YardBuilder] adds its work to: meshes in world coordinates (or placed at several spots), and street props. */
interface YardSink {
    /**
     * [mesh] at each of [at] (world x, z, along x, z, out x, z, turn: the mesh's +x along, +z out, turned about y), one
     * batch; [casts]: it throws a shadow; [tiled]: its picture repeats (uv past 1). Returns the batch's material instance
     * (null if nothing was drawn), so a builder can animate it through [animate]. [patch]: where it lies, so the view can
     * cull it and cut it with its house (null: drawn always, with the town). [water]: its picture is water, to shimmer
     * (a sink that uploads later returns null and steps it itself).
     */
    fun mesh(name: String, mesh: PropMesh, at: List<FloatArray>, casts: Boolean, tiled: Boolean = false, patch: YardPatch? = null, water: Boolean = false): MaterialInstance?

    /** [tick] is called every frame with the clock (seconds), e.g. to step the water's tile; by default nothing animates. */
    fun animate(tick: (seconds: Double) -> Unit) {}

    /** Props of the street kit ([PropKit]) standing in the world. */
    fun props(list: List<WorldProp>)
}

/** The yards as flat colours by kind (ground, then surfaces over it) and plain boxes for the things in them, by height. */
object PlaceholderYards : YardBuilder {
    override fun build(town: Town, yards: TownYards, sink: YardSink) {
        val m = MeshBuilder()
        val swatches = HashMap<Int, PixImg>()
        fun sw(c: Int) = swatches.getOrPut(c) { Paint.swatch(c) }
        fun item(i: TownYardItem) {
            val r = i.rect
            val (y0, y1) = when (i.layer) {
                "ground" -> .08f to .1f
                "surface" -> (if (i.zone == "street") .32f else .16f) to (if (i.zone == "street") .34f else .18f)
                "edge" -> 0f to height(i.kind)
                else -> (if (i.on != null) .18f else 0f) to height(i.kind)
            }
            val c = Paint.c(colour(i.kind).toLong())
            if (y1 - y0 < .5f) m.box(r[0], y0, r[1], r[2], y1, r[3]) { s, _, _ -> if (s == MeshBuilder.Side.TOP) sw(c) else null }
            else m.solidBox(r[0], y0, r[1], r[2], y1, r[3], sw(c))
        }
        val order = listOf("ground", "surface", "edge", "object")
        for (y in yards.yards) for (layer in order) for (i in y.items) if (i.layer == layer) item(i)
        for (l in yards.lanes) lane(l, m, ::sw)
        sink.mesh("yards", m.build(), listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)), casts = true)
    }

    private fun lane(l: TownLane, m: MeshBuilder, sw: (Int) -> PixImg) {
        val r = l.rect
        val c = Paint.c(if (l.kind == "lane") 0x9C9276L else 0x5E9A4EL)
        m.box(r[0], .06f, r[1], r[2], .08f, r[3]) { s, _, _ -> if (s == MeshBuilder.Side.TOP) sw(c) else null }
        for (i in l.items) m.solidBox(i.rect[0], 0f, i.rect[1], i.rect[2], height(i.kind), i.rect[3], sw(Paint.c(colour(i.kind).toLong())))
    }

    /** A placeholder's height by kind (street units; a person is 36). */
    private fun height(kind: String): Float = when (kind) {
        "garage" -> 40f; "carport" -> 32f; "shed", "bikeShed", "greenhouse", "gazebo" -> 30f
        "tree", "fruitTree" -> 46f; "hedge" -> 16f; "sideFence", "backFence", "frontFence", "gate", "sideGate" -> 14f
        "lowWall", "postRail" -> 8f; "playSet", "swing", "trampoline", "washingLine" -> 20f; "bush" -> 10f
        else -> 6f
    }

    /** A placeholder's colour by kind (0xRRGGBB). */
    private fun colour(kind: String): Int = when (kind) {
        "lawn" -> 0x5CA84A; "meadow" -> 0x9DB453; "gravel" -> 0xB5AC95
        "driveway", "kerbCut", "parkingPad" -> 0x8E8B88; "frontPath", "patio" -> 0xC9B79A; "deck", "poolDeck" -> 0xA87A50
        "pool", "pond" -> 0x3CA3D2; "hedge", "bush" -> 0x2E7A3A; "tree", "fruitTree" -> 0x237036
        "sideFence", "backFence", "frontFence", "gate", "sideGate", "postRail" -> 0xE8E0D0; "lowWall" -> 0xA09080
        "garage", "carport" -> 0xC06048; "shed", "bikeShed" -> 0x8A6A4A; "greenhouse" -> 0xBFE6E0
        "flowerBed", "planter" -> 0xE07AA0; "vegBed", "compost" -> 0x6B4A30
        else -> 0xE0B030
    }
}
