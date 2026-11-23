package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.lifemax.rpg.feature.habits.domain.CropBook
import com.lifemax.rpg.feature.habits.domain.Farm
import com.lifemax.rpg.feature.habits.domain.FarmFieldData
import com.lifemax.rpg.feature.habits.domain.FarmSolids
import com.lifemax.rpg.feature.habits.domain.FarmThing
import com.lifemax.rpg.feature.habits.domain.FieldLook
import com.lifemax.rpg.feature.habits.domain.FieldNow
import com.lifemax.rpg.feature.habits.domain.ModelPart
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownDoor
import com.lifemax.rpg.feature.habits.domain.TownDoors
import com.lifemax.rpg.feature.habits.domain.TownFarmsData
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the farms' pictures and models come from (assets/farm: crops.json, crops_atlas.png, buildings/models.json and each
 * model's atlas): [image] and [text] take a path inside it and give null when it is missing; [townImage] one of the town's
 * ground pictures (assets/town/art: the asphalt).
 */
class FarmArt(val image: (String) -> ImageBitmap?, val text: (String) -> String?, val townImage: (String) -> ImageBitmap? = { null })

/**
 * The farms round the town in [FilamentTown] (docs/TOWN.md "Farms"), a [TownExtra]: street.json's graph.farms
 * ([TownFarmsData]) drawn in the farm pack's pixels on real geometry.
 *
 *  - Country roads: asphalt with white edge lines and a dashed yellow centre, gravel verges; lanes of gravel into each
 *    farmyard, its beaten dirt between the buildings.
 *  - Farmsteads: the farm models (assets/farm, [FilamentModels]) where the export stands them: the farmhouse with its front
 *    lawn and fence, the barn, silos, machine shed, corral, tractor, hay, troughs, well, crates and barrels; the roadside
 *    stand only in summer. The farmhouse and barn have doors ([TownDoors]); every model is solid where it stands on the
 *    ground ([TownCollision.setExtra]); the tall ones are cut away in the open world camera's way, as buildings are.
 *  - Fields, as they are on the day ([day] of [year], crops.json's Midwest calendar: [FieldNow]): tilled soil as real low
 *    ridges along the rows (the pack's furrowed soil on their slopes, snow on it in winter), the crop on the ridges as
 *    crossed cards of its growth frame (sprout to ripe; corn and wheat dense enough to read as a crop you look over), stubble
 *    after the harvest, a hay meadow mown in June and August with round bales lying, bare frozen ground in winter; a
 *    post-and-rail fence round each with its gate standing open. All of a farm's fields are one soil batch and one batch of
 *    plants (a few thousand cards), rebuilt on a worker when the day changes what they show.
 *
 * Each farm (its yard, lane, fields and models) is built on the town's workers as one job, nearest the player first, and
 * uploaded a farm a frame; culled by its sphere and the view's range, its plants drawn only within [PLANT_RANGE].
 */
class TownFarms internal constructor(
    private val kit: TownExtra.Kit,
    val data: TownFarmsData,
    private val art: FarmArt,
    private val collision: TownCollision?,
    /** The year the farms stand as (which fields are farmed, which crop). */
    val year: Int,
    day: Int,
) : TownExtra {
    private val engine = kit.engine

    /** The day of the year (1..366) the fields show; set another and they are rebuilt where it changes what they show. */
    var day: Int = day.coerceIn(1, 366)
        set(v) { val d = v.coerceIn(1, 366); if (d != field) { field = d; for (g in groups) g.dirty = true } }

    private val book: CropBook? = art.text("crops.json")?.let { runCatching { CropBook.parse(it) }.getOrNull() }
    private val shapes: Map<String, ModelShape> = ModelShape.parseAll(art.text("buildings/models.json"))
    private val atlasPx: PixImg? by lazy { art.image("crops_atlas.png")?.let { im -> PixImg(im.width, im.height).also { StreetTextures.argb(im).copyInto(it.px) } } }
    private val pics: FarmPics by lazy { FarmPics(atlasPx, book, art) }

    /** A model standing in the world: its place, the ready geometry and, once uploaded, its mesh. */
    private inner class Model(val thing: FarmThing, val key: String, val farm: String) {
        val shape: ModelShape? = shapes[thing.model]
        val place: ModelPlace?
        var mesh: ModelMesh? = null
        var cx = thing.x; var cy = 40f; var cz = thing.z; var r = 200f
        var occT = Float.MAX_VALUE
        var occluded = false
        var occFade = 1f
        /** Only in the summer months (a stand). */
        val summer = thing.summer
        /** A hay field's bales (shown while bales lie: [FieldNow.bales]). */
        var bales: FarmFieldData? = null
        init {
            place = shape?.let { sh -> FarmSolids.place(thing, sh).let { p -> ModelPlace(p[0], p[1], p[2], p[3], p[4], p[5], K) } }
        }
        val tall: Boolean get() = (shape?.parts?.maxOfOrNull { it.y1 } ?: 0f) * K > 60f
    }

    /** One farm: its yard, lane, fields and fences, models; culled by its sphere. */
    private inner class Group(val farm: Farm, val fields: List<FarmFieldData>) {
        val models = ArrayList<Model>()
        var stat: PropLayer? = null
        /** Its fields' fences (built again without what the cars broke: [cuts]). */
        var fence: PropLayer? = null
        var fenceJob: java.util.concurrent.Future<Batch?>? = null
        var soil: PropLayer? = null
        var plants: PropLayer? = null
        var job: java.util.concurrent.Future<Built>? = null
        var fieldJob: java.util.concurrent.Future<List<Batch>>? = null
        var built = false
        /** Its upload is queued ([uploads]). */
        var queued = false
        var dirty = false
        /** Its fences changed (a car broke through one): built again. */
        var fenceDirty = false
        var cx = 0f; var cz = 0f; var r = 0f
        /** What its fields showed when last built (by field). */
        var keys: List<String> = emptyList()
        init {
            var x0 = farm.rect[0]; var z0 = farm.rect[1]; var x1 = farm.rect[2]; var z1 = farm.rect[3]
            for (f in fields) { x0 = min(x0, f.rect[0]); z0 = min(z0, f.rect[1]); x1 = max(x1, f.rect[2]); z1 = max(z1, f.rect[3]) }
            cx = (x0 + x1) / 2f; cz = (z0 + z1) / 2f; r = hypot(x1 - x0, z1 - z0) / 2f + 80f
            for ((i, t) in farm.things.withIndex()) models += Model(t, "${farm.id}#$i", farm.id)
            // a hay field's round bales: a few lying about it, where the baler dropped them
            for (f in fields) if (f.use == "hay") {
                val (fx0, fz0, fx1, fz1) = f.rect.toList()
                for (k in 0 until 3) {
                    val h1 = hash(f.id.hashCode(), k, 1); val h2 = hash(f.id.hashCode(), k, 2)
                    val x = fx0 + 160f + (fx1 - fx0 - 320f) * h1; val z = fz0 + 160f + (fz1 - fz0 - 320f) * h2
                    val face = if (hash(f.id.hashCode(), k, 3) < .5f) floatArrayOf(0f, 1f) else floatArrayOf(1f, 0f)
                    models += Model(FarmThing("fm_roundbales", x, z, face[0], face[1], false, false), "${f.id}#bales$k", farm.id).also { it.bales = f }
                }
            }
        }
    }

    private class Batch(val packed: PropLayer.Packed, val casts: Boolean, val tiled: Boolean, val layer: Int)
    private class Built(val batches: List<Batch>, val models: List<Pair<Model, FilamentModels.Ready?>>, val keys: List<String>)

    private val groups: List<Group> = data.farms.map { f -> Group(f, data.fields.filter { it.farm == f.id && it.farmedIn(year) }) }
    private var roads: PropLayer? = null
    private var roadJob: java.util.concurrent.Future<List<Batch>>? = null
    private var roadsDone = false
    private var lastX = 0f; private var lastZ = 0f

    /** What was built (for reports): triangles of ground, soil, plants, models; draws last frame. */
    var triangles = 0; private set
    override val pending: Boolean get() = !roadsDone || uploads.isNotEmpty() || groups.any { !it.built || it.dirty || it.fieldJob != null || it.fenceDirty || it.fenceJob != null }
    override val report: String get() =
        "farms: ${groups.size} farms, ${groups.sumOf { it.fields.size }} fields farmed in $year (day $day), ${groups.count { it.built }} built, " +
            "${groups.sumOf { g -> g.models.count { it.mesh != null } }} models, ${data.roads.size} roads; " +
            "triangles: ground ${groups.sumOf { (it.stat?.triangles ?: 0) + (it.fence?.triangles ?: 0) } + (roads?.triangles ?: 0)}, soil ${groups.sumOf { it.soil?.triangles ?: 0 }}, " +
            "plants ${groups.sumOf { it.plants?.triangles ?: 0 }}, models ${groups.sumOf { g -> g.models.sumOf { m -> m.mesh?.ib?.indexCount?.div(3) ?: 0 } }}"

    init {
        collision?.let { col ->
            col.setExtra("farms:fences", FarmSolids.fences(data, year), SolidKind.FENCE, "farms", FarmSolids.DETAIL)
            col.setExtra("farms:buildings", solids(), SolidKind.BUILDING, "farms")
            col.setExtraDoors("farms", doors(col))
        }
    }

    // ------------------------------------------------------------------ what a walker meets

    /** Every model's parts standing on the ground below head height, as world boxes ([FarmSolids.models]). */
    private fun solids(): FloatArray = FarmSolids.models(data, shapes)

    /** The farmhouses' and barns' front doors ([FarmSolids.doors]), walked out to where a walker stands clear. */
    private fun doors(col: TownCollision): List<TownDoor> = FarmSolids.doors(data, shapes).map { (id, d) ->
        TownDoors.standing(id, "", PlacementKind.LANDMARK, d[0], d[1], d[2], d[3], col)
    }

    // ------------------------------------------------------------------ building

    /** Uploads waiting for the engine's thread (a farm's batches, then its models one at a time), done a few ms a frame. */
    private val uploads = ArrayDeque<() -> Unit>()

    override fun step() {
        if (roadJob == null && !roadsDone) roadJob = kit.pool.submit(java.util.concurrent.Callable { roadBatches() })
        roadJob?.let { j ->
            if (j.isDone) {
                val l = layer(); for (b in j.get()) l.upload(b.packed, b.casts, b.tiled)
                roads = l; roadJob = null; roadsDone = true; engine.flush()
            }
        }
        watchCuts()
        for (g in groups) {
            val fj = g.fenceJob
            if (fj != null && fj.isDone) { val b = runCatching { fj.get() }.getOrNull(); g.fenceJob = null; uploads.addLast { swapFence(g, b) } }
            else if (fj == null && g.fenceDirty && g.built) { g.fenceDirty = false; g.fenceJob = kit.pool.submit(java.util.concurrent.Callable { fenceBatch(g) }) }
        }
        // the nearest farms first: at most three jobs on the workers at a time
        val order = groups.sortedBy { hypot(it.cx - lastX, it.cz - lastZ) - it.r }
        var running = groups.count { it.job != null || it.fieldJob != null }
        for (g in order) {
            val j = g.job
            if (j != null && j.isDone) { queue(g, j.get()); g.job = null; running--; continue }
            val fj = g.fieldJob
            if (fj != null && fj.isDone) { val bs = fj.get(); uploads.addLast { swapFields(g, bs) }; g.fieldJob = null; running--; continue }
            if (running >= 3) continue
            if (!g.built && g.job == null && !g.queued) { g.job = kit.pool.submit(java.util.concurrent.Callable { buildFarm(g) }); running++ }
            else if (g.built && g.dirty && g.fieldJob == null) {
                g.dirty = false
                val now = fieldKeys(g)
                if (now != g.keys) { g.keys = now; g.fieldJob = kit.pool.submit(java.util.concurrent.Callable { fieldBatches(g) }); running++ }
            }
        }
        // (a few milliseconds of uploads a frame, at least one)
        val t0 = System.nanoTime()
        var any = false
        while (uploads.isNotEmpty() && (!any || System.nanoTime() - t0 < UPLOAD_NS)) { uploads.removeFirst()(); any = true }
        if (any) engine.flush()
    }

    private fun layer() = PropLayer(engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = true)

    /** Queues a farm's upload: its ground, soil and plants, then each model, then it counts as built. */
    private fun queue(g: Group, b: Built) {
        g.queued = true
        uploads.addLast {
            val st = layer(); val so = layer(); val pl = layer(); val fe = layer()
            for (x in b.batches) (when (x.layer) { 0 -> st; 1 -> so; 3 -> fe; else -> pl }).upload(x.packed, x.casts, x.tiled)
            g.stat = st; g.soil = so; g.plants = pl; g.fence = fe; g.keys = b.keys
        }
        for ((m, r) in b.models) {
            if (r == null) continue
            uploads.addLast {
                val mm = try { FilamentModels.finish(engine, kit.lit, kit.proxy, kit.textures, r) } finally { kit.textures.drop(r.warmed) }
                val rm = engine.renderableManager
                rm.getInstance(mm.entity).takeIf { it != 0 }?.let { rm.setCulling(it, true) }
                kit.scene.addEntity(mm.entity); if (mm.proxy != 0) kit.scene.addEntity(mm.proxy)
                m.mesh = mm
                val bb = mm.bounds
                m.cx = (bb[0] + bb[3]) / 2f; m.cy = (bb[1] + bb[4]) / 2f; m.cz = (bb[2] + bb[5]) / 2f
                m.r = sqrt((bb[3] - bb[0]) * (bb[3] - bb[0]) + (bb[4] - bb[1]) * (bb[4] - bb[1]) + (bb[5] - bb[2]) * (bb[5] - bb[2])) / 2f
            }
        }
        uploads.addLast { g.built = true }
    }

    private fun swapFence(g: Group, b: Batch?) {
        val fe = layer()
        b?.let { fe.upload(it.packed, it.casts, it.tiled) }
        g.fence?.destroy()
        g.fence = fe
    }

    private fun swapFields(g: Group, bs: List<Batch>) {
        val so = layer(); val pl = layer()
        for (x in bs) (if (x.layer == 1) so else pl).upload(x.packed, x.casts, x.tiled)
        g.soil?.destroy(); g.plants?.destroy()
        g.soil = so; g.plants = pl
    }

    private fun nowOf(f: FarmFieldData): FieldNow? {
        val b = book ?: return null
        return FieldNow.of(f, b, year, day, Season.atDay(day.toFloat(), year).snow)
    }
    private fun fieldKeys(g: Group): List<String> = g.fields.map { nowOf(it)?.key ?: "" }

    /** A farm's whole build on a worker: its yard, lane and fences, its fields as they are today, its models made ready. */
    private fun buildFarm(g: Group): Built {
        val out = ArrayList<Batch>()
        val f = g.farm
        // the farmyard's beaten dirt and the lane's gravel
        val dirt = FarmMesh(pics.dirt)
        f.ground?.let { r -> dirt.level(r[0], r[1], r[2], r[3], .35f, TILE_UNITS) }
        val gravel = FarmMesh(pics.gravel)
        strip(gravel, f.laneA[0], f.laneA[1], f.laneB[0], f.laneB[1], f.laneW, .5f, TILE_UNITS)
        for ((m, casts) in listOf(dirt to false, gravel to false)) m.pack()?.let { out += Batch(it, casts, true, 0) }
        fenceBatch(g)?.let { out += it }
        out += fieldBatches(g)
        val ready = g.models.map { m -> m to prepareModel(m) }
        return Built(out, ready, fieldKeys(g))
    }

    /** Each farm model's art, read once and shared by every farm that stands one (one texture each, not one a farm). */
    private val arts = HashMap<String, ModelArt?>()

    private fun artOf(key: String): ModelArt? = synchronized(arts) {
        arts.getOrPut(key) {
            val sh = shapes[key] ?: return@getOrPut null
            val atlas = art.image("buildings/${key}_atlas.png") ?: return@getOrPut null
            // only the farmhouse keeps its own front lawn and fence (the rest stand on the farmyard), and only the farmhouse
            // has windows lit at night (a glow mask is a texture as big as the atlas, one per model: the barns' were 4 MB each)
            val house = key.startsWith("fm_house")
            val lit = house
            ModelArt(sh, atlas, if (lit) art.image("buildings/${key}_glow.png") else null,
                if (house) art.image("buildings/${key}_lawn.png") else null, if (house) art.image("buildings/${key}_fence.png") else null, null)
        }
    }

    private fun prepareModel(m: Model): FilamentModels.Ready? {
        val pl = m.place ?: return null
        val a = artOf(m.thing.model) ?: return null
        return runCatching { FilamentModels.prepare(kit.textures, a, pl, m.key.hashCode(), linear = true) }.getOrNull()
    }

    // ------------------------------------------------------------------ fields

    private fun fieldBatches(g: Group): List<Batch> {
        val out = ArrayList<Batch>()
        val atlas = atlasPx
        val b = book
        val soilMeshes = HashMap<PixImg, FarmMesh>()
        val plantMeshes = HashMap<PixImg, FarmMesh>()
        fun soilOf(p: PixImg) = soilMeshes.getOrPut(p) { FarmMesh(p) }
        fun plantsOf(p: PixImg) = plantMeshes.getOrPut(p) { FarmMesh(p) }
        val canopyMeshes = HashMap<PixImg, FarmMesh>()
        for (f in g.fields) {
            val now = nowOf(f) ?: continue
            val snowy = now.snow > .35f
            val (x0, z0, x1, z1) = f.rect.toList()
            val m = 26f
            val sx0 = x0 + m; val sz0 = z0 + m; val sx1 = x1 - m; val sz1 = z1 - m
            if (now.look == FieldLook.MEADOW || now.look == FieldLook.MOWN || (f.use == "hay" && now.look == FieldLook.WINTER)) {
                val pic = if (snowy) pics.snowMeadow else when (now.look) { FieldLook.MOWN -> pics.mown; FieldLook.WINTER -> pics.meadowWinter; else -> pics.meadow }
                soilOf(pic).level(sx0, sz0, sx1, sz1, .45f, TILE_UNITS)
                if (now.look == FieldLook.MEADOW && atlas != null && b != null && !snowy) grass(plantsOf(atlas), f, b, sx0, sz0, sx1, sz1)
                continue
            }
            val crop = now.crop ?: now.stubbleOf ?: f.cropIn(year)
            val sp = spacing(crop)
            val ridge = when (now.look) {
                FieldLook.WINTER -> if (now.stubbleOf == null) 2.5f else sp.ridge * .6f
                FieldLook.PLOUGHED -> max(4f, sp.ridge)
                else -> sp.ridge
            }
            val pic = when {
                snowy -> pics.snowSoil
                now.look == FieldLook.WINTER -> pics.soilDark
                now.look == FieldLook.PLOUGHED -> pics.soilFurrows
                else -> pics.soil
            }
            val rows = rowsOf(f, sp.row, sx0, sz0, sx1, sz1)
            ridges(soilOf(pic), f.rowsX, rows, sp.row, ridge, sx0, sz0, sx1, sz1)
            soilOf(pic).level(sx0, sz0, sx1, sz1, .4f, TILE_UNITS)
            when (now.look) {
                FieldLook.GROWING, FieldLook.RIPE -> if (atlas != null && b != null) b.crops[now.crop]?.let { info ->
                    // (sprouts are small; under snow only the odd tuft shows)
                    val small = if (now.stage <= 1) Spacing(sp.row, sp.plant * (if (snowy) 2.2f else 1f), sp.ridge, sp.scale * (if (snowy) .55f else .75f)) else sp
                    plantRows(plantsOf(atlas), atlas, f, info.name, info.frameW, info.variants, now.stage, rows, small, ridge, sx0, sz0, sx1, sz1)
                    if (now.stage >= 1 && !(snowy && now.stage <= 1)) {
                        val v = info.variants[0]
                        val frame = frameOf(info.name, now.stage, .3f)
                        // (the plant's own height in the frame: its opaque rows)
                        val ht = opaqueHeight(atlas, frame * info.frameW, v[0], info.frameW, v[1]) * TEXEL * sp.scale
                        val cover = when (info.name) { "corn", "wheat" -> .95f; "pumpkin", "watermelon", "strawberry" -> .8f; else -> .65f }
                        val open = if (now.stage <= 1) .7f else if (info.name == "wheat" || info.name == "corn") .25f else .45f
                        val cp = pics.canopy(frame * info.frameW, v[0], info.frameW, v[1], open)
                        canopy(canopyMeshes.getOrPut(cp) { FarmMesh(cp) }, f, rows, sp, ridge + .3f + ht * (if (now.stage <= 1) .3f else .62f), cover, sx0, sz0, sx1, sz1)
                    }
                }
                FieldLook.STUBBLE -> stubble(plantsOf(if (now.stubbleOf == "corn") pics.cornStubble else pics.wheatStubble), f, rows, sp, ridge, now.stubbleOf == "corn", sx0, sz0, sx1, sz1)
                FieldLook.WINTER -> if (now.stubbleOf == "corn" || (f.cropIn(year - 1) == "corn" && day < 80)) stubble(plantsOf(pics.cornStubble), f, rows, sp, ridge, true, sx0, sz0, sx1, sz1)
                else -> {}
            }
        }
        for (m in soilMeshes.values) m.pack()?.let { out += Batch(it, false, true, 1) }
        for (m in plantMeshes.values) m.pack()?.let { out += Batch(it, true, false, 2) }
        for (m in canopyMeshes.values) m.pack()?.let { out += Batch(it, false, true, 1) }
        return out
    }

    /** How many rows from its bottom a frame's plant fills (its opaque rows). */
    private fun opaqueHeight(img: PixImg, u: Int, v: Int, w: Int, h: Int): Int {
        for (y in v until v + h) for (x in u until u + w) if ((img[x, y] ushr 24) > 100) return v + h - y
        return h / 2
    }

    /** Row and plant spacing (street units), the ridge's height, and the card's scale for a crop. */
    private class Spacing(val row: Float, val plant: Float, val ridge: Float, val scale: Float)
    private fun spacing(crop: String): Spacing = when (crop) {
        "corn" -> Spacing(26f, 15f, 5f, 1.05f)
        "wheat" -> Spacing(15f, 12f, 1.5f, 1f)
        "pumpkin", "watermelon" -> Spacing(46f, 38f, 6f, 1f)
        "strawberry" -> Spacing(20f, 12f, 6f, 1f)
        "cabbage", "cauliflower" -> Spacing(30f, 24f, 6f, 1f)
        "tomato", "pepper", "zucchini" -> Spacing(36f, 26f, 6f, 1f)
        "grape" -> Spacing(52f, 26f, 2f, 1f)
        else -> Spacing(22f, 15f, 5f, 1f)
    }

    /** The rows' centre lines across the field (their offsets: z for rows along x, else x). */
    private fun rowsOf(f: FarmFieldData, sp: Float, sx0: Float, sz0: Float, sx1: Float, sz1: Float): FloatArray {
        val lo = if (f.rowsX) sz0 else sx0; val hi = if (f.rowsX) sz1 else sx1
        val n = max(1, ((hi - lo) / sp).toInt())
        val gap = (hi - lo - n * sp) / 2f
        return FloatArray(n) { lo + gap + sp * (it + .5f) }
    }

    /** Low ridges along the rows, troughs between: each ridge two sloped faces and its two ends. */
    private fun ridges(m: FarmMesh, rowsX: Boolean, rows: FloatArray, sp: Float, h: Float, sx0: Float, sz0: Float, sx1: Float, sz1: Float) {
        val y0 = .45f; val top = y0 + h
        val a0 = if (rowsX) sx0 else sz0; val a1 = if (rowsX) sx1 else sz1
        val s = TILE_UNITS
        val half = sp / 2f
        val sl = hypot(half, h)
        fun P(along: Float, across: Float, y: Float) = if (rowsX) floatArrayOf(along, y, across) else floatArrayOf(across, y, along)
        for (c in rows) {
            for (side in intArrayOf(-1, 1)) {
                val foot = c + side * half
                // across from the foot up to the crest; the picture's v runs along the slope
                val n = if (rowsX) floatArrayOf(0f, half, side * h) else floatArrayOf(side * h, half, 0f)
                val t = if (rowsX) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
                val vf = (foot / s); val vc = vf + side * sl / s
                m.quad(P(a0, foot, y0), P(a1, foot, y0), P(a1, c, top), P(a0, c, top), floatArrayOf(a0 / s, vf, a1 / s, vf, a1 / s, vc, a0 / s, vc), n, t)
            }
            // the ends: a low triangle each
            for (e in floatArrayOf(a0, a1)) {
                val n = if (rowsX) floatArrayOf(if (e == a0) -1f else 1f, .3f, 0f) else floatArrayOf(0f, .3f, if (e == a0) -1f else 1f)
                val t = if (rowsX) floatArrayOf(0f, 0f, 1f) else floatArrayOf(1f, 0f, 0f)
                val q = P(e, c, top)
                m.quad(P(e, c - half, y0), P(e, c + half, y0), q, q, floatArrayOf((c - half) / s, 0f, (c + half) / s, 0f, c / s, h / s, c / s, h / s), n, t)
            }
        }
    }

    /** The crop on the ridges' crests: crossed cards of its growth frame, a plant every [Spacing.plant] along each row. */
    private fun plantRows(m: FarmMesh, atlas: PixImg, f: FarmFieldData, crop: String, fw: Int, variants: List<IntArray>, stage: Int, rows: FloatArray,
                          sp: Spacing, ridge: Float, sx0: Float, sz0: Float, sx1: Float, sz1: Float) {
        val a0 = if (f.rowsX) sx0 else sz0; val a1 = if (f.rowsX) sx1 else sz1
        val seed = f.id.hashCode()
        for ((ri, c) in rows.withIndex()) {
            var a = a0 + sp.plant * (.3f + hash(seed, ri, 0) * .5f)
            var k = 0
            while (a < a1 - 4f) {
                val h0 = hash(seed, ri, k * 5 + 1); val h1 = hash(seed, ri, k * 5 + 2); val h2 = hash(seed, ri, k * 5 + 3)
                val frame = frameOf(crop, stage, h0)
                val v = variants[if (variants.size > 1) (h1 * variants.size).toInt().coerceAtMost(variants.size - 1) else 0]
                val jit = (h2 - .5f) * sp.row * .2f
                val x = if (f.rowsX) a else c + jit; val z = if (f.rowsX) c + jit else a
                val scale = sp.scale * (.88f + .24f * h1)
                cards(m, atlas, x, ridge + .3f, z, frame * fw, v[0], fw, v[1], scale, h0 * 3.1416f)
                a += sp.plant * (.8f + .4f * h2)
                k++
            }
        }
    }

    /** The sheet's frame for [stage] (two sways for growing and ripe; wheat's budding frame is already gold, so it stays green). */
    private fun frameOf(crop: String, stage: Int, h: Float): Int = when (stage) {
        0 -> 0; 1 -> 1; 2 -> if (h < .5f) 2 else 3
        3 -> if (crop == "wheat") (if (h < .5f) 2 else 3) else 4
        else -> if (h < .5f) 5 else 6
    }

    /**
     * The crop's canopy: a level band along each row at about two thirds of the plants' height, wearing the crop's own
     * colours with gaps ([FarmPics.canopy]): from above and from afar (past [PLANT_RANGE], where the cards are not drawn)
     * a field reads as its crop, green or gold, not as bare soil.
     */
    private fun canopy(m: FarmMesh, f: FarmFieldData, rows: FloatArray, sp: Spacing, y: Float, cover: Float, sx0: Float, sz0: Float, sx1: Float, sz1: Float) {
        val a0 = if (f.rowsX) sx0 else sz0; val a1 = if (f.rowsX) sx1 else sz1
        val half = sp.row * cover / 2f
        val s = 16f * TEXEL
        for (c in rows) {
            if (f.rowsX) m.quad(floatArrayOf(a0, y, c - half), floatArrayOf(a1, y, c - half), floatArrayOf(a1, y, c + half), floatArrayOf(a0, y, c + half),
                floatArrayOf(a0 / s, (c - half) / s, a1 / s, (c - half) / s, a1 / s, (c + half) / s, a0 / s, (c + half) / s), floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f))
            else m.quad(floatArrayOf(c - half, y, a0), floatArrayOf(c + half, y, a0), floatArrayOf(c + half, y, a1), floatArrayOf(c - half, y, a1),
                floatArrayOf((c - half) / s, a0 / s, (c + half) / s, a0 / s, (c + half) / s, a1 / s, (c - half) / s, a1 / s), floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f))
        }
    }

    /** A hay meadow's tufts: young grass of the crop sheets' greens, sparse, so the meadow has a top you see. */
    private fun grass(m: FarmMesh, f: FarmFieldData, b: CropBook, sx0: Float, sz0: Float, sx1: Float, sz1: Float) {
        val info = b.crops["wheat"] ?: return
        val atlas = atlasPx ?: return
        val v = info.variants[0]
        val seed = f.id.hashCode()
        var z = sz0 + 10f; var j = 0
        while (z < sz1 - 6f) {
            var x = sx0 + 10f + hash(seed, j, 0) * 20f; var i = 0
            while (x < sx1 - 6f) {
                val h = hash(seed, j, i + 7)
                if (h < .55f) cards(m, atlas, x + (h - .3f) * 18f, .5f, z + (hash(seed, i, j) - .5f) * 16f, (1 + (h * 3).toInt().coerceAtMost(2)) * info.frameW, v[0], info.frameW, v[1], .8f + h * .3f, h * 6f)
                x += 34f; i++
            }
            z += 30f; j++
        }
    }

    /** Stubble: short cut stalks along the rows (corn's in clumps where the plants stood, wheat's a dense short brush). */
    private fun stubble(m: FarmMesh, f: FarmFieldData, rows: FloatArray, sp: Spacing, ridge: Float, corn: Boolean, sx0: Float, sz0: Float, sx1: Float, sz1: Float) {
        val a0 = if (f.rowsX) sx0 else sz0; val a1 = if (f.rowsX) sx1 else sz1
        val img = m.img
        val step = if (corn) sp.plant * 1.6f else 20f
        val seed = f.id.hashCode() + 9
        for ((ri, c) in rows.withIndex()) {
            if (!corn && ri % 2 == 1) continue
            var a = a0 + hash(seed, ri, 0) * step
            var k = 0
            while (a < a1 - 4f) {
                val h = hash(seed, ri, k)
                val x = if (f.rowsX) a else c; val z = if (f.rowsX) c else a
                cards(m, img, x, ridge + .2f, z, 0, 0, img.w, img.h, .8f + .4f * h, h * 3.1416f)
                a += step * (.8f + .4f * h); k++
            }
        }
    }

    /** Two upright cards crossed at right angles, [w] x [h] texels of [img] from ([u], [v]), feet at ([x], [y], [z]). */
    private fun cards(m: FarmMesh, img: PixImg, x: Float, y: Float, z: Float, u: Int, v: Int, w: Int, h: Int, scale: Float, turn: Float) {
        val hw = w * TEXEL * scale / 2f; val ht = h * TEXEL * scale
        val u0 = u.toFloat() / img.w; val u1 = (u + w).toFloat() / img.w; val v0 = v.toFloat() / img.h; val v1 = (v + h).toFloat() / img.h
        for (k in 0..1) {
            val a = turn + k * 1.5708f
            val dx = cos(a) * hw; val dz = sin(a) * hw
            // (lit as if facing up a little more than out: a crop reads as a mass, not as cards turning dark edge on)
            val n = floatArrayOf(-dz / hw * .45f, 1f, dx / hw * .45f)
            m.quad(floatArrayOf(x - dx, y + ht, z - dz), floatArrayOf(x + dx, y + ht, z + dz), floatArrayOf(x + dx, y, z + dz), floatArrayOf(x - dx, y, z - dz),
                floatArrayOf(u0, v0, u1, v0, u1, v1, u0, v1), n, floatArrayOf(dx, 0f, dz))
        }
    }

    /** A farm's fields' fences as one batch (layer 3), without the stretches the cars broke ([cuts]). */
    private fun fenceBatch(g: Group): Batch? {
        val fence = FarmMesh(pics.wood)
        val broken = cuts
        for (fd in g.fields) fences(fence, fd, broken)
        return fence.pack()?.let { Batch(it, true, true, 3) }
    }

    /** The fences' stretches the cars broke ([com.lifemax.rpg.feature.habits.vehicle.Wreckage.cuts]), as last seen. */
    @Volatile private var cuts: List<FloatArray> = emptyList()
    private var cutsSeen = 0

    /** New breaks in the fences: the farms they are in build their fences again without them. */
    private fun watchCuts() {
        val w = com.lifemax.rpg.feature.habits.presentation.scene.TownPlay.cars?.wreck ?: return
        if (w.cutsVersion == cutsSeen) return
        cutsSeen = w.cutsVersion
        val before = cuts.size
        cuts = w.cuts.toList()
        for (c in cuts.drop(before)) for (g in groups) {
            val mx = (c[0] + c[2]) / 2f; val mz = (c[1] + c[3]) / 2f
            if (hypot(mx - g.cx, mz - g.cz) < g.r && g.built) g.fenceDirty = true
        }
    }

    /**
     * A field's post-and-rail fence (posts every [POST] units, two rails), its gate's leaf standing open into the field;
     * the stretches in [broken] (x0, z0, x1, z1 along a run's line) left out, a post standing at each broken end.
     */
    private fun fences(m: FarmMesh, f: FarmFieldData, broken: List<FloatArray> = emptyList()) {
        for (run in FarmSolids.fenceRuns(f)) {
            val (ax, az, bx, bz) = run.toList()
            val l = hypot(bx - ax, bz - az)
            if (l < 4f) continue
            val n = max(1, (l / POST).toInt())
            val ux = (bx - ax) / l; val uz = (bz - az) / l
            // the broken stretches on this run, as distances along it
            val gaps = ArrayList<FloatArray>()
            for (c in broken) {
                val t0 = (c[0] - ax) * ux + (c[1] - az) * uz; val t1 = (c[2] - ax) * ux + (c[3] - az) * uz
                val off0 = abs((c[0] - ax) * -uz + (c[1] - az) * ux); val off1 = abs((c[2] - ax) * -uz + (c[3] - az) * ux)
                if (off0 > 8f || off1 > 8f) continue
                val g0 = min(t0, t1).coerceAtLeast(0f); val g1 = max(t0, t1).coerceAtMost(l)
                if (g1 > g0) gaps += floatArrayOf(g0, g1)
            }
            fun open(t: Float) = gaps.any { t > it[0] + .5f && t < it[1] - .5f }
            fun post(t: Float) { val px = ax + ux * t; val pz = az + uz * t; box(m, px - 3f, 0f, pz - 3f, px + 3f, 28f, pz + 3f) }
            val posts = (0..n).map { l * it / n }
            for (t in posts) if (!open(t)) post(t)
            // the rails over what stands, a post at each end of a gap
            val stands = ArrayList<FloatArray>()
            var from = 0f
            for (gp in gaps.sortedBy { it[0] }) { if (gp[0] > from) stands += floatArrayOf(from, gp[0]); from = max(from, gp[1]) }
            if (from < l) stands += floatArrayOf(from, l)
            for (st in stands) {
                if (st[1] - st[0] < 2f) continue
                // (a broken end's post: unless one of the run's own stands there)
                if (st[0] > 0f && posts.none { abs(it - st[0] - 3f) < 12f }) post(st[0] + 3f)
                if (st[1] < l && posts.none { abs(it - st[1] + 3f) < 12f }) post(st[1] - 3f)
                for (y in floatArrayOf(10f, 21f)) beam(m, ax + ux * st[0], az + uz * st[0], ax + ux * st[1], az + uz * st[1], y, y + 4f, 1.6f)
            }
        }
        // the gate: a five-bar leaf on its hinge post, swung 90 degrees into the field
        val gate = FarmSolids.gate(f)
        val gx = gate[0]; val gz = gate[1]; val ox = gate[2]; val oz = gate[3]
        val ax = abs(oz); val az = abs(ox)
        val hx = gx - ax * FarmSolids.GATE / 2f; val hz = gz - az * FarmSolids.GATE / 2f
        val ex = hx - ox * (FarmSolids.GATE - 8f); val ez = hz - oz * (FarmSolids.GATE - 8f)
        for (k in 0 until 5) { val y = 5f + k * 5f; beam(m, hx, hz, ex, ez, y, y + 2.4f, 1.2f) }
        box(m, ex - 1.5f, 3f, ez - 1.5f, ex + 1.5f, 29f, ez + 1.5f)
        box(m, hx - 1.5f, 3f, hz - 1.5f, hx + 1.5f, 29f, hz + 1.5f)
        // a diagonal brace
        val bxm = (hx + ex) / 2f; val bzm = (hz + ez) / 2f
        beam(m, hx, hz, bxm, bzm, 6f, 8f, 1.2f, rise = 18f)
    }

    /** A box in the wood picture (its texels at the art's scale, repeating). */
    private fun box(m: FarmMesh, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        val s = 16f * TEXEL
        m.quad(floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1), floatArrayOf(x0 / s, y1 / s, x1 / s, y1 / s, x1 / s, y0 / s, x0 / s, y0 / s), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f))
        m.quad(floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1 / s, y1 / s, x0 / s, y1 / s, x0 / s, y0 / s, x1 / s, y0 / s), floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f))
        m.quad(floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(z1 / s, y1 / s, z0 / s, y1 / s, z0 / s, y0 / s, z1 / s, y0 / s), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f))
        m.quad(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y0, z0), floatArrayOf(z0 / s, y1 / s, z1 / s, y1 / s, z1 / s, y0 / s, z0 / s, y0 / s), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
        m.quad(floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0 / s, z0 / s, x1 / s, z0 / s, x1 / s, z1 / s, x0 / s, z1 / s), floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f))
    }

    /** A rail from ([ax], [az]) to ([bx], [bz]) between heights [y0] and [y1] (rising [rise] over its length), [hw] half thick. */
    private fun beam(m: FarmMesh, ax: Float, az: Float, bx: Float, bz: Float, y0: Float, y1: Float, hw: Float, rise: Float = 0f) {
        val l = hypot(bx - ax, bz - az); if (l < .5f) return
        val ux = (bx - ax) / l; val uz = (bz - az) / l
        val px = -uz * hw; val pz = ux * hw
        val s = 16f * TEXEL
        fun q(o: Float, n: FloatArray) {
            m.quad(floatArrayOf(ax + px * o, y1, az + pz * o), floatArrayOf(bx + px * o, y1 + rise, bz + pz * o), floatArrayOf(bx + px * o, y0 + rise, bz + pz * o), floatArrayOf(ax + px * o, y0, az + pz * o),
                floatArrayOf(0f, y1 / s, l / s, (y1 + rise) / s, l / s, (y0 + rise) / s, 0f, y0 / s), n, floatArrayOf(ux, 0f, uz))
        }
        q(1f, floatArrayOf(-uz, 0f, ux)); q(-1f, floatArrayOf(uz, 0f, -ux))
        m.quad(floatArrayOf(ax - px, y1, az - pz), floatArrayOf(bx - px, y1 + rise, bz - pz), floatArrayOf(bx + px, y1 + rise, bz + pz), floatArrayOf(ax + px, y1, az + pz),
            floatArrayOf(0f, 0f, l / s, 0f, l / s, .2f, 0f, .2f), floatArrayOf(0f, 1f, 0f), floatArrayOf(ux, 0f, uz))
    }

    /** A level strip along ([ax], [az]) to ([bx], [bz]), [w] wide at [y], its picture repeating every [s] units. */
    private fun strip(m: FarmMesh, ax: Float, az: Float, bx: Float, bz: Float, w: Float, y: Float, s: Float, u0: Float = -1f, u1: Float = -1f) {
        val l = hypot(bx - ax, bz - az); if (l < .5f) return
        val ux = (bx - ax) / l; val uz = (bz - az) / l
        val px = -uz * w / 2f; val pz = ux * w / 2f
        val a0 = if (u0 >= 0f) u0 else 0f; val a1 = if (u1 >= 0f) u1 else w / s
        m.quad(floatArrayOf(ax + px, y, az + pz), floatArrayOf(ax - px, y, az - pz), floatArrayOf(bx - px, y, bz - pz), floatArrayOf(bx + px, y, bz + pz),
            floatArrayOf(a0, 0f, a1, 0f, a1, l / s, a0, l / s), floatArrayOf(0f, 1f, 0f), floatArrayOf(-px, 0f, -pz))
    }

    /** The country roads: asphalt (its picture across the whole width, repeating along) on gravel verges. */
    private fun roadBatches(): List<Batch> {
        val out = ArrayList<Batch>()
        for (r in data.roads) {
            val asphalt = FarmMesh(pics.road)
            val verge = FarmMesh(pics.gravel)
            // (the section road a little above the spokes it crosses; both above the town's own roads where they meet)
            val y = TownGround.LIFT_ROAD + (if (r.kind == "ring") .3f else .2f)
            strip(asphalt, r.ax, r.az, r.bx, r.bz, r.width, y, ROAD_LEN, 0f, 1f)
            strip(verge, r.ax, r.az, r.bx, r.bz, r.width + 2f * r.verge, y - .08f, TILE_UNITS)
            verge.pack()?.let { out += Batch(it, false, true, 0) }
            asphalt.pack()?.let { out += Batch(it, false, true, 0) }
        }
        return out
    }

    // ------------------------------------------------------------------ each frame

    override fun frame(f: TownExtra.Frame, out: IntArray): IntArray {
        out.fill(0)
        val pose = f.pose
        lastX = pose.ex + pose.fx * 400f; lastZ = pose.ez + pose.fz * 400f
        roads?.let { l -> l.update(f.look, f.dark, true, 1f, false); out[0] += l.draws; out[1] += l.triangles }
        val month = ((day - 1) / 30.5f).toInt() + 1
        val standOut = month in data.standFrom..data.standTo
        val rm = engine.renderableManager
        for (g in groups) {
            if (!g.built) continue
            val fade = pose.rangeFade(g.cx, 40f, g.cz, g.r)
            val on = fade > .02f && pose.sees(g.cx, 40f, g.cz, g.r)
            val near = on && pose.distance(g.cx, 40f, g.cz) - g.r < SHADOW_RANGE
            // (the crops' cards near by only, faded out over [PLANT_FADE]: past it the field's canopy picture stands for them;
            // drawn to 2600 they were thousands of cut-out cards a few pixels each, the GPU's worst overdraw in 0.1.402)
            val plantD = pose.distance(g.cx, 20f, g.cz) - g.r
            val plantFade = min(fade, ((PLANT_RANGE - plantD) / PLANT_FADE).coerceIn(0f, 1f))
            val plantsOn = on && plantFade > .02f
            g.stat?.let { it.update(f.look, f.dark, on, fade, near); if (on) { out[0] += it.draws; out[1] += it.triangles }; if (near) out[2] += it.casters }
            g.fence?.let { it.update(f.look, f.dark, on, fade, near); if (on) { out[0] += it.draws; out[1] += it.triangles }; if (near) out[2] += it.casters }
            g.soil?.let { it.update(f.look, f.dark, on, fade, false); if (on) { out[0] += it.draws; out[1] += it.triangles } }
            val plantShadows = plantsOn && plantD < PLANT_SHADOWS
            g.plants?.let { it.update(f.look, f.dark, plantsOn, plantFade, plantShadows); if (plantsOn) { out[0] += it.draws; out[1] += it.triangles }; if (plantShadows) out[2] += it.casters }
            for (m in g.models) {
                val mm = m.mesh ?: continue
                m.occFade = if (pose.open && m.occluded) max(0f, m.occFade - f.occStep) else min(1f, m.occFade + f.occStep)
                val shownToday = (!m.summer || standOut) && (m.bales?.let { nowOf(it)?.bales == true } ?: true)
                val mf = min(pose.rangeFade(m.cx, m.cy, m.cz, m.r), m.occFade)
                val mOn = shownToday && on && mf > .02f && pose.sees(m.cx, m.cy, m.cz, m.r)
                rm.getInstance(mm.entity).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (mOn) 1 else 0) }
                val mNear = shownToday && near && pose.distance(m.cx, m.cy, m.cz) - m.r < SHADOW_RANGE
                if (mm.proxy != 0) rm.getInstance(mm.proxy).takeIf { it != 0 }?.let { rm.setLayerMask(it, 0xff, if (mNear) 1 else 0) }
                if (mNear && mm.proxy != 0) out[2]++
                if (!mOn) continue
                for (mi in mm.mis) {
                    mi.setParameter("look", MaterialInstance.FloatElement.MAT4, f.look, 0, 1)
                    mi.setParameter("fade", mf)
                    mi.setParameter("glow", f.glow[0], f.glow[1], f.glow[2], f.glow[3])
                    mi.setParameter("glass", f.glass[0], f.glass[1], f.glass[2], 1f)
                }
                mm.night?.invoke(f.hour)
                out[0] += mm.mis.size; out[1] += mm.ib.indexCount / 3
            }
        }
        return out
    }

    // ------------------------------------------------------------------ the open world camera's cut

    private val oo = FloatArray(3); private val od = FloatArray(3); private val olo = FloatArray(3); private val ohi = FloatArray(3)

    override fun occlude(starts: FloatArray, ex: Float, ey: Float, ez: Float): Boolean {
        val o = oo; val d = od; val lo = olo; val hi = ohi
        var hides = false
        for (g in groups) {
            if (!g.built) continue
            // (quick: the farm's circle against the line's ground box)
            val tx = starts[0]; val tz = starts[2]
            if (g.cx + g.r < min(tx, ex) || g.cx - g.r > max(tx, ex) || g.cz + g.r < min(tz, ez) || g.cz - g.r > max(tz, ez)) { for (m in g.models) m.occT = Float.MAX_VALUE; continue }
            for (m in g.models) {
                m.occT = Float.MAX_VALUE
                val bb = m.mesh?.bounds ?: continue
                if (!m.tall) continue
                lo[0] = bb[0]; lo[1] = bb[1]; lo[2] = bb[2]; hi[0] = bb[3]; hi[1] = bb[4]; hi[2] = bb[5]
                if (ex > lo[0] - 40f && ex < hi[0] + 40f && ez > lo[2] - 40f && ez < hi[2] + 40f && ey < hi[1] + 40f) { m.occT = .999f; continue }
                // (cut only when it hides the player: the chest's and the head's lines both through it; one that only
                // covers their feet or a side stays)
                var tc = -1f; var th = -1f
                for (j in 0..1) {
                    val k = j * 2
                    o[0] = starts[k * 3]; o[1] = starts[k * 3 + 1]; o[2] = starts[k * 3 + 2]
                    d[0] = ex - o[0]; d[1] = ey - o[1]; d[2] = ez - o[2]
                    val t0 = slab(o, d, lo, hi)
                    if (j == 0) tc = t0 else th = t0
                }
                if (tc in 0f..1f && th in 0f..1f) { m.occT = max(tc, th); hides = true }
            }
        }
        return hides
    }

    override fun fadeBetween(t: Float) { for (g in groups) for (m in g.models) m.occluded = m.occT < t }

    /** Where the line from [o] along [d] (0..1) first enters the box [lo]..[hi], or -1. */
    private fun slab(o: FloatArray, d: FloatArray, lo: FloatArray, hi: FloatArray): Float {
        var t0 = 0f; var t1 = 1f
        for (i in 0..2) {
            if (abs(d[i]) < 1e-6f) { if (o[i] < lo[i] || o[i] > hi[i]) return -1f; continue }
            var a = (lo[i] - o[i]) / d[i]; var b = (hi[i] - o[i]) / d[i]
            if (a > b) { val c = a; a = b; b = c }
            t0 = max(t0, a); t1 = min(t1, b)
            if (t0 > t1) return -1f
        }
        return t0
    }

    override fun destroy() {
        roadJob?.cancel(true)
        roads?.destroy()
        val em = EntityManager.get()
        for (g in groups) {
            g.job?.cancel(true); g.fieldJob?.cancel(true); g.fenceJob?.cancel(true)
            g.stat?.destroy(); g.fence?.destroy(); g.soil?.destroy(); g.plants?.destroy()
            for (m in g.models) {
                val mm = m.mesh ?: continue
                for (e in listOf(mm.entity, mm.proxy)) if (e != 0) { kit.scene.remove(e); engine.destroyEntity(e); em.destroy(e) }
                for (mi in mm.mis) engine.destroyMaterialInstance(mi)
                for (mi in mm.proxyMis) engine.destroyMaterialInstance(mi)
                engine.destroyVertexBuffer(mm.vb); engine.destroyIndexBuffer(mm.ib)
                m.mesh = null
            }
        }
        collision?.let { it.setExtra("farms:fences", null); it.setExtra("farms:buildings", null); it.setExtraDoors("farms", null) }
    }

    companion object {
        /** Street units per art pixel (the farm models and the crops are at the town's art scale). */
        const val K = TEXEL
        /** A soil tile (32 texels) in street units. */
        const val TILE_UNITS = 32f * TEXEL
        /** The road picture's length along (texels as units). */
        const val ROAD_LEN = 64f * TEXEL
        const val POST = 92f
        /** How far past a farm's edge its crops are drawn, and its things throw shadows. */
        const val PLANT_RANGE = 1100f
        /** Over how many street units the crops' cards fade out short of [PLANT_RANGE] (screen door). */
        const val PLANT_FADE = 250f
        /** The crops throw shadows only this near (street units past the farm's edge): small, many, in four cascades. */
        const val PLANT_SHADOWS = 500f
        const val SHADOW_RANGE = 1600f
        /** The engine thread's uploads a frame (ns). */
        const val UPLOAD_NS = 3_000_000L

        /** Adds the farms of [data] to [town] (nothing for a town without): built for [year] (the town's) on [day] of it. */
        fun attach(town: FilamentTown, data: TownFarmsData, art: FarmArt, collision: TownCollision?, year: Int = town.town.year, day: Int = 200): TownFarms? =
            if (data.isEmpty) null else town.addExtra { TownFarms(it, data, art, collision, year, day) }

        internal fun hash(a: Int, b: Int, c: Int): Float {
            var h = a * 374761393 + b * 668265263 + c * 1274126177
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777216f
        }
    }
}

/** A mesh in world coordinates wearing one picture (its uv in pictures: 1 the picture's width), for a [PropLayer] batch. */
internal class FarmMesh(val img: PixImg) {
    private var pos = FloatArray(1024); private var uv = FloatArray(1024); private var nrm = FloatArray(1024); private var tan = FloatArray(1024)
    private var idx = IntArray(1024)
    private var nv = 0; private var ni = 0
    private val bmin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    private val bmax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, t: FloatArray, n: FloatArray, tg: FloatArray) {
        if ((nv + 4) * 3 > pos.size) { val k = pos.size * 2; pos = pos.copyOf(k); nrm = nrm.copyOf(k); tan = tan.copyOf(k) }
        if ((nv + 4) * 2 > uv.size) uv = uv.copyOf(uv.size * 2)
        if (ni + 6 > idx.size) idx = idx.copyOf(idx.size * 2)
        val nl = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-6f)
        val tl = sqrt(tg[0] * tg[0] + tg[1] * tg[1] + tg[2] * tg[2]).coerceAtLeast(1e-6f)
        val base = nv
        for ((k, p) in arrayOf(a, b, c, d).withIndex()) {
            for (i in 0..2) { pos[nv * 3 + i] = p[i]; nrm[nv * 3 + i] = n[i] / nl; tan[nv * 3 + i] = tg[i] / tl; bmin[i] = min(bmin[i], p[i]); bmax[i] = max(bmax[i], p[i]) }
            uv[nv * 2] = t[k * 2]; uv[nv * 2 + 1] = t[k * 2 + 1]
            nv++
        }
        idx[ni++] = base; idx[ni++] = base + 1; idx[ni++] = base + 2; idx[ni++] = base; idx[ni++] = base + 2; idx[ni++] = base + 3
    }

    /** A level rect at [y], its picture repeating every [s] units from the world's origin. */
    fun level(x0: Float, z0: Float, x1: Float, z1: Float, y: Float, s: Float) =
        quad(floatArrayOf(x0, y, z0), floatArrayOf(x1, y, z0), floatArrayOf(x1, y, z1), floatArrayOf(x0, y, z1),
            floatArrayOf(x0 / s, z0 / s, x1 / s, z0 / s, x1 / s, z1 / s, x0 / s, z1 / s), floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f))

    fun mesh(): PropMesh? = if (ni == 0) null else PropMesh(pos.copyOf(nv * 3), uv.copyOf(nv * 2), nrm.copyOf(nv * 3), tan.copyOf(nv * 3), idx.copyOf(ni),
        img.w, img.h, img.px, null, floatArrayOf(bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]))

    /** Packed for a [PropLayer] (in the world: no transform), or null if empty. */
    fun pack(): PropLayer.Packed? = mesh()?.let { PropLayer.Packing.pack(it, IDENTITY) }

    companion object { private val IDENTITY = listOf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f)) }
}
