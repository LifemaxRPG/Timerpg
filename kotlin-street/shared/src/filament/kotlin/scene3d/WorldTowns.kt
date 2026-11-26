package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.RenderOrigin
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPack
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldTownSet
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the generated towns come from (planet step 2): the baker's packs by their manifest path ([pack]: the app's storage,
 * the desktop's build-towns folder), a picture decoder for their buildings' PNGs ([image]), and the shared assets their
 * landmarks, stores and farms use (null: drawn without them).
 */
class TownsSource(
    val pack: (file: String) -> ByteArray?,
    val image: (ByteArray) -> ImageBitmap?,
    /** The hand-made landmark models' catalogue (assets/landmarks/landmarks.json) and files (by their path in it). */
    val landmarkCatalogue: String? = null,
    val landmarkFile: ((String) -> ByteArray?)? = null,
    /** The farm pack's art for the towns' farm rings (the country roads in, the fields and farmsteads). */
    val farmArt: FarmArt? = null,
)

/**
 * The generated towns streamed in round the player (planet step 2, docs/TOWNS_EVERYWHERE.md "Streaming", WORLD_STREAM.md
 * section 10): a [TownExtra] of the capital's [FilamentTown] (the host). Every baked town ([WorldTownSet], the manifest) is
 * on the far view as a stand-in of a few blocks in its own footprint ([WorldFarPlan]'s boxes, a part a town: [WorldFar]);
 * the [MAX_GUESTS] nearest within [IN_M] of the eye are loaded whole as guest towns:
 *
 *  - the pack read and its street.json, yards, buildings' shapes parsed on a worker ([Loaded]);
 *  - a [FilamentTown] made in guest mode ([TownGuest]: the host's scene and workers, every entity under the town's root,
 *    whose transform is the town's frame on the planet in the capital's street units) with its stores, cinema, landmark
 *    models and farm ring ([TownFarms]: the roads in from the world's crossings), streaming as the capital does
 *    ([TownStream]: full detail near, its far cells and districts out to [FAR_REACH_M], the night's lights as dots);
 *  - built within [STEP_MS] a frame on the engine's thread (ground first, buildings nearest the eye first);
 *  - its stand-in blocks hidden once its own far boxes are all up;
 *  - let go past [OUT_M] (the whole town freed in one frame: see [destroyGuest]).
 *
 * The town's code runs unchanged in its own street units: each frame it is given the host's camera turned into its frame
 * (for culling, distances and streaming) and the host's look (for the screen). People: none yet (a hook, [onGuest]).
 */
internal class WorldTowns(
    private val kit: TownExtra.Kit, private val host: FilamentTown, private val origin: RenderOrigin, private val site: WorldSite,
    val set: WorldTownSet, private val src: TownsSource, private val year: Int, private val far: WorldFar,
) : TownExtra {
    private val u = origin.unitsPerMetre

    /** A town's frame in the host's street units: local (x, z) at host (ox + k (c x - s z), oy + k y, oz + k (s x + c z)). */
    class Frame(val c: Float, val s: Float, val k: Float, val ox: Double, val oy: Double, val oz: Double) {
        fun hostX(x: Float, z: Float): Float = (ox + k * (c * x - s * z)).toFloat()
        fun hostZ(x: Float, z: Float): Float = (oz + k * (s * x + c * z)).toFloat()
        fun localX(hx: Float, hz: Float): Float = ((c * (hx - ox) + s * (hz - oz)) / k).toFloat()
        fun localZ(hx: Float, hz: Float): Float = ((-s * (hx - ox) + c * (hz - oz)) / k).toFloat()
        fun localY(hy: Float): Float = ((hy - oy) / k).toFloat()

        /** The host's camera [p] in this frame. */
        fun pose(p: TownPose): TownPose = TownPose(
            localX(p.ex, p.ez), localY(p.ey), localZ(p.ex, p.ez),
            c * p.fx + s * p.fz, p.fy, -s * p.fx + c * p.fz,
            p.fl, p.cx, p.cy, p.w, p.h, p.near / k, p.far / k, p.hazeFrom / k, p.haze * k,
            cut = 0f, range = if (p.range == Float.MAX_VALUE) p.range else p.range / k, open = p.open,
            lodFar = if (p.lodFar == Float.MAX_VALUE) p.lodFar else p.lodFar / k, shadowFar = p.shadowFar / k)
    }

    fun frameOf(t: WorldTownSet.Town, scale: Double): Frame {
        val f = site.frame
        val p0 = t.world(0.0, 0.0)
        val k = (u * Planet.TILE_M / scale).toFloat()
        val a = Math.toRadians(t.deg)
        return Frame(cos(a).toFloat(), sin(a).toFloat(), k, f.streetX(p0), (t.heightM - f.elevM) * u, f.streetZ(p0))
    }

    /** What a worker reads of a town's pack before its [FilamentTown] can be made. */
    class Loaded(val town: Town, val yards: TownYards, val root: org.json.JSONObject, val building: (String) -> SpriteBuilding?, val ms: Float)

    inner class Guest(val t: WorldTownSet.Town) {
        var job: java.util.concurrent.Future<Loaded>? = null
        var ft: FilamentTown? = null
        var tg: TownGuest? = null
        var frame: Frame? = null
        var groundDone = false
        var shownFar = false
        val startNs = System.nanoTime()
        var loadMs = 0f; var makeMs = 0f; var groundMs = 0f; var builtMs = 0f
        var d = 0.0
        override fun toString(): String = "${t.name} (${t.rank}): " + when {
            ft == null -> "loading"
            !groundDone -> "ground"
            else -> "%d of %d buildings resident, far %s".format(ft!!.slots.count { it.isBuilt }, ft!!.slots.size, if (shownFar) "up" else "coming")
        }
    }

    private val guests = LinkedHashMap<String, Guest>()

    /** A roadside site near the eye ([WorldRoadside]): its landmark model under a root at its pad, facing the road. */
    inner class Stop(val s: com.lifemax.rpg.feature.habits.domain.WorldRoadside.Site, val spec: LandmarkSpec) {
        val tg = TownGuest(kit.engine, kit.scene, host.workers)
        /** Its own pictures (freed with it: the capital's set would keep every site's the drive ever passed). */
        val textures = StreetTextures(kit.engine, srgb = true)
        val frame: Frame
        val lm: TownLandmark
        var d = 0.0
        init {
            val f = site.frame
            val p = com.lifemax.rpg.feature.habits.domain.WorldPos(s.x, s.z)
            // (local +z, the model's front, along (fx, fz): a turn whose local x is (fz, -fx), its cos and sin)
            frame = Frame(s.fz.toFloat(), (-s.fx).toFloat(), 1f, f.streetX(p), (s.heightM - f.elevM) * u, f.streetZ(p))
            tg.setFrame(frame.c, frame.s, 1f, frame.ox.toFloat(), frame.oy.toFloat(), frame.oz.toFloat())
            val file = src.landmarkFile!!
            lm = TownRoot.with(tg.root) {
                TownLandmark(kit.engine, kit.scene, kit.lit, kit.proxy, textures, spec, { file(spec.file) ?: ByteArray(0) }, { spec.info?.let { file(it)?.decodeToString() } },
                    "road:${s.id}", "", TownLandmark.facing(0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, spec.width / 2f + 60f),
                    year, s.id.hashCode(), null, farBytes = spec.far?.let { fp -> { file(fp) } })
            }
        }
        override fun toString(): String = "${s.kind} ${spec.name} %.1f km".format(java.util.Locale.US, d / 1000)
    }
    private val stops = LinkedHashMap<String, Stop>()
    /** Every roadside site of the world's year ([WorldSite.roadside]). */
    val roadside: List<com.lifemax.rpg.feature.habits.domain.WorldRoadside.Site> get() = site.roadside
    private val specs: Map<String, LandmarkSpec> by lazy { src.landmarkCatalogue?.let { LandmarkSpec.catalogue(it).associateBy { s -> s.kind } } ?: emptyMap() }
    /** The roadside sites drawn now, and how many have been. */
    val stopList: Collection<Stop> get() = stops.values
    var stopsMade = 0; private set

    private fun dropStop(st: Stop) {
        TownRoot.with(st.tg.root) { val was = PropLayer.deferDestroy; PropLayer.deferDestroy = true; try { st.lm.destroy() } finally { PropLayer.deferDestroy = was } }
        val t = st.textures
        Graveyard.later { t.destroy(all = true) }
        st.tg.destroy()
    }

    /** Picks the roadside sites within [STOP_IN_M] of the eye ([x], [z] planet m), lets go of those past [STOP_OUT_M]. */
    private fun chooseStops(x: Double, z: Double) {
        if (src.landmarkFile == null) return
        for (st in stops.values.toList()) {
            st.d = hypot(Planet.wrapDx(st.s.x - x), st.s.z - z)
            if (st.d > STOP_OUT_M) { dropStop(st); stops.remove(st.s.id) }
        }
        for (s in site.roadside) {
            if (s.id in stops) continue
            val d = hypot(Planet.wrapDx(s.x - x), s.z - z)
            if (d > STOP_IN_M) continue
            val spec = specs[s.model] ?: continue
            stops[s.id] = Stop(s, spec).also { it.d = d; stopsMade++ }
        }
    }
    /** The times each town took (reports): read, made, ground, all built (ms from asking). */
    val log = ArrayList<String>()
    /** Each frame's time spent on the guests' building (ms), and on their drawing (ms), for the frames that did any. */
    val stepFrames = ArrayList<Float>()
    val drawFrames = ArrayList<Float>()
    var loaded = 0; private set
    var dropped = 0; private set
    /** The worst frame a guest was made or let go in (ms): the hitches that are not budgeted. */
    var worstMakeMs = 0f; private set
    var worstDropMs = 0f; private set
    /** The worst single guest step (ms) and what it was doing. */
    var worstStepMs = 0f; private set
    var worstStep = ""; private set

    /** Called once a guest town is made, before its first frame (the loop's hook: its people, its police, its records). */
    var onGuest: ((WorldTownSet.Town, FilamentTown) -> Unit)? = null

    override val pending: Boolean get() = false
    override val report: String get() = "world towns: ${set.towns.count { it.baked }} baked; guests ${guests.values.joinToString("; ").ifEmpty { "none" }}; loaded $loaded, dropped $dropped; " +
        "roadside: ${site.roadside.size} sites, drawn now ${stops.values.joinToString().ifEmpty { "none" }}"
    val guestList: Collection<Guest> get() = guests.values

    private var lastPose: TownPose? = null
    private var chooseAt = -1e9
    private var eyeX = Double.NaN; private var eyeZ = 0.0

    /** Picks the guests (every [CHOOSE_M] of the eye's travel): the nearest within [IN_M], let go past [OUT_M]. */
    private fun choose(p: TownPose) {
        val e = origin.origin.plus(p.ex / u, p.ez / u)
        if (!eyeX.isNaN() && hypot(Planet.wrapDx(e.x - eyeX), e.z - eyeZ) < CHOOSE_M) return
        eyeX = e.x; eyeZ = e.z
        chooseStops(e.x, e.z)
        val near = set.towns.filter { it.baked }.map { it to it.outside(e.x, e.z) }.filter { it.second < OUT_M }.sortedBy { it.second }
        for (g in guests.values) g.d = near.firstOrNull { it.first === g.t }?.second ?: Double.MAX_VALUE
        // (let go: past OUT_M, or no longer among the nearest)
        val keep = near.take(MAX_GUESTS).map { it.first.id }.toSet()
        for (g in guests.values.toList()) if (g.d > OUT_M || (g.t.id !in keep && g.d > IN_M)) destroyGuest(g)
        for ((t, d) in near) {
            if (guests.size >= MAX_GUESTS) break
            if (d < IN_M && t.id !in guests) guests[t.id] = Guest(t).also { start(it); it.d = d }
        }
    }

    private fun start(g: Guest) {
        val t = g.t
        g.job = kit.pool.submit(java.util.concurrent.Callable {
            val t0 = System.nanoTime()
            val bytes = src.pack(t.file) ?: error("no pack ${t.file} for ${t.name}")
            val pack = TownPack(bytes)
            val json = pack.text("street.json") ?: error("${t.file} has no street.json")
            val town = Town.parse(json) ?: error("${t.file}: street.json has no graph")
            val yards = TownYards.parse(json)
            val root = org.json.JSONObject(json)
            val windows = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.windows(pack.text("buildings/windows.json"))
            val houses = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.houses(pack.text("buildings/houses.json"))
            val models = com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.models(pack.text("buildings/models.json"))
            val mids = HashMap<String, Int>()
            root.optJSONArray("shops")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val parts = o.optJSONArray("parts")
                    mids[o.getString("sprite")] = if (parts == null) 0 else (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
                }
            }
            val building: (String) -> SpriteBuilding? = { sp ->
                com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad.load(sp, mids[sp] ?: 0, windows, houses, models) { n -> pack.file("buildings/$n.png")?.let(src.image) }
            }
            Loaded(town, yards, root, building, (System.nanoTime() - t0) / 1e6f)
        })
    }

    /** Makes [g]'s town once its pack is read (the engine's thread: Filament objects). */
    private fun make(g: Guest, l: Loaded) {
        val t0 = System.nanoTime()
        val tg = TownGuest(kit.engine, kit.scene, host.workers)
        val fr = frameOf(g.t, l.town.scale.toDouble())
        tg.setFrame(fr.c, fr.s, fr.k, fr.ox.toFloat(), fr.oy.toFloat(), fr.oz.toFloat())
        val root = l.root
        val lm = src.landmarkCatalogue?.let { cat -> src.landmarkFile?.let { file ->
            LandmarkAssets(LandmarkSpec.catalogue(cat), file, com.lifemax.rpg.feature.habits.domain.GlbLandmarks.parse(root)) } }
        val ft = TownRoot.with(tg.root) {
            FilamentTown(kit.engine, host.materialBytesOf, l.town, l.yards, kit.art ?: TownArt { null }, l.building,
                cinema = CinemaSite.parse(root), stores = StoreSite.parse(root), rail = null, landmarks = lm, guest = tg)
        }
        ft.yardBuilder = YardKitGuestBuilder
        // (the world's ground runs on past its grass; its far boxes out to where it is let go)
        ft.farFields = false
        ft.worldReach = host.worldReach
        ft.townFarReach = (FAR_REACH_M * u / fr.k).toFloat()
        ft.nightBudgetNs = host.nightBudgetNs
        ft.weather = host.weather; ft.season = host.season; ft.day = host.day
        src.farmArt?.let { fa ->
            val farms = com.lifemax.rpg.feature.habits.domain.TownFarmsData.parse(root)
            if (!farms.isEmpty) TownRoot.with(tg.root) { TownFarms.attach(ft, farms, fa, null, day = 200, roadsOnly = !FARMS_WHOLE) }
        }
        g.ft = ft; g.tg = tg; g.frame = fr
        g.loadMs = l.ms
        g.makeMs = (System.nanoTime() - t0) / 1e6f
        worstMakeMs = max(worstMakeMs, g.makeMs)
        loaded++
        onGuest?.invoke(g.t, ft)
    }

    private fun destroyGuest(g: Guest) {
        val t0 = System.nanoTime()
        g.job?.cancel(true)
        val ft = g.ft
        if (ft != null) TownRoot.with(g.tg!!.root) { ft.destroy(later = true) }
        g.tg?.destroy()
        far.showPlace(g.t.id)
        guests.remove(g.t.id)
        dropped++
        if (ft != null) { val ms = (System.nanoTime() - t0) / 1e6f; worstDropMs = max(worstDropMs, ms); log += "%s let go %.1f km out in %.1f ms".format(java.util.Locale.US, g.t.name, g.d / 1000, ms) }
    }

    override fun step() {
        val p = lastPose ?: return
        val t0 = System.nanoTime()
        var did = false
        var left = STEP_MS
        for (g in guests.values.sortedBy { it.d }) {
            val j = g.job
            if (g.ft == null && j != null && j.isDone) {
                val l = try { j.get() } catch (e: java.util.concurrent.ExecutionException) { throw BuildFailure("reading ${g.t.name}'s pack", e.cause ?: e) }
                g.job = null
                make(g, l)
                did = true
                continue
            }
            val ft = g.ft ?: continue
            val fr = g.frame!!
            if (left <= .2f) break
            val s0 = System.nanoTime()
            TownRoot.with(g.tg!!.root) {
                if (!g.groundDone) {
                    if (ft.uploadGround(left)) {
                        g.groundDone = true
                        g.groundMs = (System.nanoTime() - g.startNs) / 1e6f
                    }
                } else {
                    // (the eye's point on the ground, as the drive tests stand the player: nearest it first)
                    ft.buildNearest(fr.localX(p.ex, p.ez), fr.localZ(p.ex, p.ez), left)
                    if (g.builtMs == 0f && ft.pending == 0) {
                        g.builtMs = (System.nanoTime() - g.startNs) / 1e6f
                        log += "%s (%s, %d buildings): read %.0f ms (a worker), made %.1f ms, ground in %.0f ms, settled in %.0f ms (from asking)".format(java.util.Locale.US,
                            g.t.name, g.t.rank, ft.slots.size, g.loadMs, g.makeMs, g.groundMs, g.builtMs)
                    }
                }
            }
            did = true
            val ms = (System.nanoTime() - s0) / 1e6f
            if (ms > worstStepMs) { worstStepMs = ms; worstStep = "%s %s (%.1f ms)".format(java.util.Locale.US, g.t.name, if (g.groundDone) "building, ${ft.slots.count { it.isBuilt }} up" else "its ground", ms) }
            left -= ms
        }
        // the roadside sites' models: made on the workers, uploaded here (the nearest first)
        for (st in stops.values.sortedBy { it.d }) if (!st.lm.done) TownRoot.with(st.tg.root) {
            st.lm.start(host.workers, TownWorkers.LATER + st.d.toFloat() / 100f)
            if (st.lm.step()) did = true
        }
        if (did) synchronized(stepFrames) { stepFrames += (System.nanoTime() - t0) / 1e6f }
    }

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        lastPose = f.pose
        choose(f.pose)
        var draws = 0; var tris = 0; var casters = 0
        val t0 = System.nanoTime()
        for (g in guests.values) {
            val ft = g.ft ?: continue
            val fr = g.frame!!
            val lp = fr.pose(f.pose)
            TownRoot.with(g.tg!!.root) { ft.frame(lp, f.hour, f.seconds, hostLook = f.look) }
            draws += ft.stats.draws; tris += ft.stats.triangles; casters += ft.stats.casters
            // (its stand-in blocks go once its own far boxes are all up)
            if (!g.shownFar && g.groundDone && ft.farReady) { g.shownFar = true; far.hidePlace(g.t.id) }
        }
        for (st in stops.values) {
            if (!st.lm.done) continue
            val o = TownRoot.with(st.tg.root) { st.lm.frame(st.frame.pose(f.pose), f.look, f.dark, f.hour, f.seconds, { _, _ -> 1f }, stopOut) }
            draws += o[0]; tris += o[1]; casters += o[2]
        }
        if (guests.isNotEmpty()) synchronized(drawFrames) { drawFrames += (System.nanoTime() - t0) / 1e6f }
        out[0] = draws; out[1] = tris; out[2] = casters
        return out
    }

    private val stopOut = IntArray(3)

    override fun destroy() {
        for (g in guests.values.toList()) destroyGuest(g)
        for (st in stops.values) dropStop(st)
        stops.clear()
    }

    companion object {
        /** A town is loaded once the eye is this near its ground (m), let go past [OUT_M]; at most [MAX_GUESTS] at once. */
        const val IN_M = 3500.0
        const val OUT_M = 5000.0
        @JvmStatic var MAX_GUESTS = 2
        /** The guests' far boxes reach this far from the eye (m). */
        const val FAR_REACH_M = 6000.0
        /** The guests' building budget a frame (ms, engine's thread), the world's share with the far view's and the near terrain's. */
        @JvmStatic var STEP_MS = 2.5f
        /** The guests are chosen again each time the eye has moved this far (m). */
        const val CHOOSE_M = 50.0
        /** A roadside site's model is made once the eye is this near it (m), let go past [STOP_OUT_M]. */
        const val STOP_IN_M = 1500.0
        const val STOP_OUT_M = 2200.0

        /** Whether a guest's farm ring is drawn whole (its farmsteads, fields and crops), or only its roads in (the default). */
        @JvmStatic var FARMS_WHOLE = false
        /** Whether the generated towns are drawn (previews: -DworldTowns=0, the far view's stand-ins only). */
        @JvmStatic var on = true

        /**
         * Adds the generated towns of [set] round [town] (the capital, with the world [far] round it); [src] reads their
         * packs. Their frames are the capital's street units (the render origin stays the capital's: WORLD_STREAM.md 5).
         */
        fun attach(town: FilamentTown, far: WorldFar, origin: RenderOrigin, site: WorldSite, set: WorldTownSet, src: TownsSource, year: Int): WorldTowns? {
            if (!on || set.towns.isEmpty()) return null
            return town.addExtra { WorldTowns(it, town, origin, site, set, src, year, far) }
        }
    }
}
