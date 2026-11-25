package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.vehicle.VehicleEra
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpec
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Where the stores' parked cars are read from (assets/vehicles/<path>; [LotCars.files]): for the previews and tests. */
fun lotCarFiles(f: ((String) -> ByteArray?)?) { LotCars.files = f }

/**
 * A car standing still in a model's frame (a store's car park, a store's drive): where its footprint's middle is
 * ([x], [z]), which way its nose points ([turn]: 0 to +z, π/2 to +x), and a [seed] for its model and paint.
 */
class LotCar(val x: Float, val z: Float, val turn: Float, val seed: Int)

/**
 * The parked cars of the stores' lots as our real cars (the owner's 2026-11-25 note "let's replace all fake cars in our
 * models with real cars"; docs/VEHICLES.md "Parked in the lots"): each [LotCar] gets a model of the town's year
 * ([pick]: the models still on the road that year, the newest likeliest; classics now and then) in one of its delivery's
 * colours, drawn from its mid or far .glb ([VehicleKit], whole and static: no physics, no lamps lit). One model in one
 * colour is built once and shared ([meshes]); a lot's cars are packed into a few batches per level of detail, one per
 * model, colour and material ([pack]). [files] reads assets/vehicles/<path> (set by the app and the previews); without
 * it the lots keep their old box cars.
 */
internal object LotCars {
    @Volatile var files: ((String) -> ByteArray?)? = null

    /** Which of a car's models: the mid (near the lot) or the far. */
    enum class Lod { MID, FAR }

    private val specCache = ConcurrentHashMap<String, VehicleSpec>()

    /** The parkable models (V1: not the race build), read once. */
    fun specs(): List<VehicleSpec> {
        val f = files ?: return emptyList()
        return VehicleSpec.V1.mapNotNull { k ->
            specCache[k] ?: f("$k/model-info.json")?.let { b -> runCatching { VehicleSpec.parse(String(b)) }.getOrNull() }?.also { specCache[k] = it }
        }.filter { it.race == null }
    }

    /**
     * How likely [s] is in a car park in [year]: built then or in its working life, 1; kept as a classic, less the
     * longer ago its life ended (in 2025 the late-80s Torrel is still the commonest of our cars, a 70s car now and then).
     */
    fun weight(s: VehicleSpec, year: Int): Float = when (s.era(year)) {
        VehicleEra.NONE -> 0f
        VehicleEra.COMMON -> 1f
        // (a collector's car out shopping, now and then: none past 35 years after its working life)
        VehicleEra.CLASSIC -> (year - s.to - VehicleEra.LIFE).let { past -> if (past > 35) 0f else .5f * exp(-past / 10f) }
    }

    /** The model and colour of the car [seed] in [year] (null: no model of ours is about yet). */
    fun pick(specs: List<VehicleSpec>, year: Int, seed: Int): Pair<VehicleSpec, Int>? {
        val ws = specs.map { weight(it, year) }
        val sum = ws.sum()
        if (sum <= 0f) return null
        var u = (Math.floorMod(seed * 1103515245 + 12345, 1 shl 30) / (1 shl 30).toFloat()) * sum
        var s = specs.last()
        for ((i, w) in ws.withIndex()) { if (u < w) { s = specs[i]; break }; u -= w }
        val colour = s.colours[Math.floorMod(seed * 31 + 7, s.colours.size)]
        return s to colour
    }

    private val meshCache = ConcurrentHashMap<String, List<PropMesh>>()
    val cachedCount: Int get() = meshCache.size

    /** [s]'s [lod] model painted [colour], whole (in street units, nose to +z, its origin between the axles), [mirror]ed in x. */
    fun meshes(s: VehicleSpec, colour: Int, lod: Lod, mirror: Boolean): List<PropMesh> = meshCache.getOrPut("${s.key}|$colour|$lod|$mirror") {
        val file = (if (lod == Lod.MID) s.mid else s.far) ?: s.near
        val bytes = files?.invoke("${s.folder}/$file") ?: return@getOrPut emptyList()
        val m = VehicleKit.build(GlbFile.read(bytes), s, colour, animated = false)
        m.parts.flatMap { p -> p.meshes.map { (mat, mesh) -> if (mat.startsWith("glow_")) unlit(mesh) else mesh } }.map { if (mirror) mirrored(it) else it }
    }

    /** (a parked car's lamps are off: its lenses drawn in their own colours, not glowing at night) */
    private fun unlit(m: PropMesh) = PropMesh(m.pos, m.uv, m.nrm, m.tan, m.idx, m.texW, m.texH, m.tex, null, m.bounds)

    /** [m] mirrored in its x (its triangles turned the other way round): stood in a mirrored frame it is the car again. */
    private fun mirrored(m: PropMesh): PropMesh {
        val pos = m.pos.copyOf(); val nrm = m.nrm.copyOf(); val tan = m.tan.copyOf()
        for (i in 0 until m.vertexCount) { pos[i * 3] = -pos[i * 3]; nrm[i * 3] = -nrm[i * 3]; tan[i * 3] = -tan[i * 3] }
        val idx = m.idx.copyOf()
        var t = 0
        while (t + 2 < idx.size) { val a = idx[t + 1]; idx[t + 1] = idx[t + 2]; idx[t + 2] = a; t += 3 }
        val b = m.bounds
        return PropMesh(pos, m.uv, nrm, tan, idx, m.texW, m.texH, m.tex, m.mask, floatArrayOf(-b[3], b[1], b[2], -b[0], b[4], b[5]))
    }

    /** What [pack] made: the batches, and how many cars and triangles they hold. */
    class Packs(val batches: List<PropLayer.Packed>, val cars: Int, val triangles: Int)

    /**
     * [cars] (in the model's frame [at]: world x, z, along x, z, out x, z, as a store stands) as [year]'s real cars at
     * [lod], packed for [PropLayer.upload] on any thread: one batch per model, colour and material, every car of that
     * look in it. Null when the cars cannot be read ([files] unset, no model about that year).
     */
    fun pack(cars: List<LotCar>, year: Int, at: FloatArray, lod: Lod): Packs? {
        if (cars.isEmpty()) return Packs(emptyList(), 0, 0)
        val specs = specs()
        if (specs.isEmpty()) return null
        // (a frame that mirrors (along x out < 0) would show a car's left on its right: its mesh mirrored back first)
        val mirror = at[2] * at[5] - at[4] * at[3] < 0f
        val groups = LinkedHashMap<Pair<VehicleSpec, Int>, MutableList<FloatArray>>()
        for (c in cars) {
            val pick = pick(specs, year, c.seed) ?: continue
            val s = pick.first
            // the model's origin is between its axles: its footprint's middle on the car's place
            val back = s.footprint[2] * VehicleWorld.U
            val mx = c.x - sin(c.turn) * back; val mz = c.z - cos(c.turn) * back
            val wx = at[0] + at[2] * mx + at[4] * mz; val wz = at[1] + at[3] * mx + at[5] * mz
            groups.getOrPut(pick) { ArrayList() } += floatArrayOf(wx, wz, at[2], at[3], at[4], at[5], c.turn)
        }
        if (groups.isEmpty()) return null
        val out = ArrayList<PropLayer.Packed>()
        var tris = 0
        for ((pk, list) in groups) for (mesh in meshes(pk.first, pk.second, lod, mirror)) {
            PropLayer.Packing.pack(mesh, list)?.let { out += it; tris += it.indices / 3 }
        }
        return Packs(out, groups.values.sumOf { it.size }, tris)
    }
}

/** What the tests read of [LotCars] (its own types are the renderer's). */
object LotCarsProbe {
    class Packs(val batchCount: Int, val cars: Int, val triangles: Int, val bytes: Long)

    fun pack(cars: List<LotCar>, year: Int, at: FloatArray, far: Boolean): Packs? =
        LotCars.pack(cars, year, at, if (far) LotCars.Lod.FAR else LotCars.Lod.MID)?.let { p -> Packs(p.batches.size, p.cars, p.triangles, p.batches.sumOf { it.bytes.toLong() }) }

    /** The models [cars] are in [year] (keys). */
    fun models(cars: List<LotCar>, year: Int): Set<String> { val s = LotCars.specs(); return cars.mapNotNull { LotCars.pick(s, year, it.seed)?.first?.key }.toSet() }

    /** How many colours they wear in [year]. */
    fun colours(cars: List<LotCar>, year: Int): Int { val s = LotCars.specs(); return cars.mapNotNull { LotCars.pick(s, year, it.seed)?.second }.toSet().size }

    /** How many model-in-a-colour meshes are built and shared. */
    fun cached(): Int = LotCars.cachedCount
}
