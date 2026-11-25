package com.lifemax.rpg.feature.habits.presentation.scene3d

/**
 * The town's GPU memory budget by part (MEMORY_AUDIT.md, "The memory budget guard"): one place the desktop's
 * GpuBudgetTest and the phone's play log both read, so a new building, yard kit or landmark that grows a part past its
 * budget fails a test on the build server instead of taking the room the dialogue model's NPU copy needs on the phone.
 *
 * The parts are [GpuAccount.categories]' (Filament's own count, which the desktop matches to within ~2% of the phone)
 * gathered as the audit budgets them. The budgets are MB, set from the capital (gamma) as the open-world preview builds
 * it, after step 3 of the audit's plan (2026-11-24: far yards on their near atlases, the farms' night masks in exact
 * ASTC; lowered after step 4, 2026-11-25: the models' night masks as window pages): each part's measured figure and a little room (1-2%) for the capital's growth between budget reviews; the props
 * keep ~10 MB more because the phone brings tree cells into detail as the player walks (trees 23-40 MB measured there,
 * 32 on the desktop's build). Lower them as each later step lands; raise one only on purpose, with the reason here.
 */
object TownBudget {
    /** A part's budget: its name, MB, and how its figure is taken from [GpuAccount.categories]. */
    class Part(val name: String, val mb: Double, val of: (Map<String, Map<String, Any?>>) -> Double?)

    private fun mb(c: Map<String, Map<String, Any?>>, k: String, f: String = "mb"): Double = (c[k]?.get(f) as? Number)?.toDouble() ?: 0.0
    /** The parts with their own budget; everything else that is the town's geometry or pictures counts as props. */
    private val OWN = setOf("yards", "buildings and stores", "farms", "GLB landmarks")
    private val NOT_PROPS = OWN + setOf("buffer pages, free space", "shadow map", "render targets + surfaces", "total")

    val parts: List<Part> = listOf(
        // measured 2026-11-25 after step 4 (desktop, gamma): 206.0 (362.5 after step 3, 453.7 before; textures 5.1 on the shared yard atlas)
        Part("yards", 210.0) { mb(it, "yards") },
        // 153.1 after step 4 (was 334.2: the night masks' 181 MB of RG8 as window pages, 11 MB)
        Part("buildings", 156.0) { mb(it, "buildings and stores") },
        // 85.9 after step 4 (127.5 after step 3, 191.7 before: the farmhouses' masks paged, the vertices quantised)
        Part("farms", 88.0) { mb(it, "farms") },
        // the GLB landmarks (their models, through FilamentModels' kin and PropLayer): 39.1 (41.4 before step 4)
        Part("landmarks", 41.0) { mb(it, "GLB landmarks") },
        // trees, ground and terrain, street things and greenery, rail, the rest: 62.3 after step 4 (73.6 before)
        Part("props", 73.0) { c -> c.entries.filter { it.key !in NOT_PROPS }.sumOf { (it.value["mb"] as? Number)?.toDouble() ?: 0.0 } },
        // every part's vertex and index buffers and the shared pages' free space: 343.8 after step 4's quantised
        // vertices (451.2 before)
        Part("vertex/index buffers", 350.0) { c -> c.entries.filter { it.key !in setOf("total", "shadow map", "render targets + surfaces") }
            .sumOf { e -> (e.value["buffersMb"] as? Number)?.toDouble() ?: (if (e.key == "buffer pages, free space") (e.value["mb"] as? Number)?.toDouble() ?: 0.0 else 0.0) } },
        // every texture: 205.6 after step 4 (489.1 after step 3, 644.5 before)
        Part("textures", 210.0) { c -> c.entries.filter { it.key != "total" }.sumOf { (it.value["texturesMb"] as? Number)?.toDouble() ?: 0.0 } },
        // the shadow map (2048 x 4 cascades) and the render targets and surfaces of a 2340 x 1080 view: 118.8; unknown
        // where no view's estimate was given
        Part("render targets", 120.0) { c -> if (c["shadow map"] == null) null else mb(c, "shadow map") + mb(c, "render targets + surfaces") },
        // 668.1 after step 4 (window pages, quantised vertices, the shared yard atlas; 1059.2 after step 3, 1213.8 before)
        Part("total", 682.0) { c -> mb(c, "total") },
    )

    // ------------------------------------------------------------------ streaming (docs/STREAMING.md)

    /**
     * The streamed tiers' pools (MB, FilamentTown's own estimate: each resident building's textures counted whole and its
     * buffers; each near lot's buffers): past its pool a tier's streaming margin shrinks until it is back under.
     */
    @JvmStatic var streamBuildingsMb = 260.0
    @JvmStatic var streamYardsMb = 220.0

    /**
     * The radius budget: Filament's whole count (as [parts]' "total") at any frame of the fixed walk and drive through the
     * capital ([GpuRouteTest]) with streaming on. It must not grow as the capital grows: adding districts adds far boxes
     * and far yards only. The peak measured 2026-11-25 after step 4: 475-478 MB (704.2 with step 4 off, ROUTE_STEP4=0).
     */
    const val ROUTE_MB = 495.0

    // ------------------------------------------------------------------ the world streamer (docs/WORLD_STREAM.md)

    /**
     * The world's own GPU share (WorldMemory.gpu: the far view, both plans while one is swapped in, the near terrain's
     * nodes and pictures, the near ocean) at any frame of the drive out of the capital (GpuWorldDriveTest): the streamer's
     * fixed budget, wherever the player is. WorldMemory.GPU_BUDGET (250) is the design's cap; this is the measured
     * figure and a little room: 130.0 MB peak on the drive to Evanvale (2026-11-25, desktop), most of it the ground's
     * pictures (64 MB as RGBA8; 16 as ASTC) and 200-240 near nodes (31 MB).
     */
    const val WORLD_MB = 140.0
    /** Filament's whole count at any frame of that drive: the capital's streamed share plus the world's (557.7 MB peak, 0.3 km out). */
    const val WORLD_ROUTE_MB = 575.0

    /** One part's figure against its budget. */
    class Row(val part: String, val mb: Double?, val budget: Double) {
        val over: Boolean get() = mb != null && mb > budget
    }

    /** Every part's figure (MB; null where not counted) against its budget, from [GpuAccount.categories] ([cats]). */
    fun check(cats: Map<String, Any?>): List<Row> {
        @Suppress("UNCHECKED_CAST") val c = cats.mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to it } }.toMap()
        return parts.map { p -> Row(p.name, p.of(c)?.let { Math.round(it * 10) / 10.0 }, p.mb) }
    }

    /** The rows as a table: part, MB, budget, over by. */
    fun table(rows: List<Row>): String = buildString {
        appendLine("%-22s %9s %9s  %s".format("part", "MB", "budget", ""))
        for (r in rows) appendLine("%-22s %9s %9.1f  %s".format(java.util.Locale.US, r.part, r.mb?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-", r.budget,
            if (r.over) "OVER by %.1f MB".format(java.util.Locale.US, r.mb!! - r.budget) else ""))
    }

    /** The rows in one line for the phone's play log and breadcrumbs: "yards 362.5/368, ...; over: none". */
    fun line(rows: List<Row>): String = rows.filter { it.mb != null }.joinToString(", ") { "%s %.1f/%.0f".format(java.util.Locale.US, it.part, it.mb, it.budget) } +
        "; over budget: " + rows.filter { it.over }.joinToString(", ") { "%s by %.1f MB".format(java.util.Locale.US, it.part, it.mb!! - it.budget) }.ifEmpty { "none" }

    /**
     * The view's render targets and surfaces for the phone's budget lines ([FilamentTown.gpuEstimate] at the screen's size),
     * set when the town is built; null before (the render targets are then left out of the check).
     */
    @Volatile var estimate: GpuEstimate? = null
}
