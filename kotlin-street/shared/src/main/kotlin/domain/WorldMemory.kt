package com.lifemax.rpg.feature.habits.domain

/**
 * The world layer's share of memory (WORLD_GEN.md 7, "Memory budget on the S25": 0.20 GB of RAM, 0.25 GB of GPU for the
 * world), for the breadcrumbs and the NPU fit test's report beside the town's numbers (TownMemory, GpuAccount): the
 * skeleton's grids and paths held, the far view's plan (its arrays on the heap until uploaded), and on the GPU the far
 * view's vertex and index buffers and its pictures. Set by whoever holds them ([skeleton] by the loader, the rest by the
 * far view); [line] is one line of it. Desktop numbers are what the arrays and buffers take, not what a driver pads to.
 *
 * With the world streamer (docs/WORLD_STREAM.md) the budgets are hard caps the streamer keeps to wherever the player is:
 * [GPU_BUDGET] over the far view (the plan drawn, the next uploading and the old one being freed), the near terrain (its
 * nodes, cache included, kept under [NEAR_GPU_BUDGET]), its pictures and the near ocean; [RAM_BUDGET] over the skeleton,
 * the plans' arrays and the terrain's line index.
 */
object WorldMemory {
    /** The skeleton's grids and paths (bytes), and an estimate of its vectors and facts on the heap. */
    @Volatile var skeleton = 0L
    @Volatile var facts = 0L
    /** The far view's arrays on the heap while it is built (its plan), then 0. */
    @Volatile var plan = 0L
    /** On the GPU: the far view's vertices and indices, its pictures (bytes); the parts uploaded (both plans while swapping). */
    @Volatile var gpuBuffers = 0L
    @Volatile var gpuTextures = 0L
    @Volatile var farParts = 0
    /** The far view's triangles and draws. */
    @Volatile var triangles = 0
    @Volatile var draws = 0
    /**
     * The near terrain (W5a): its nodes' vertex and index buffers on the GPU, their material maps, the ground's pictures
     * (the atlas), how many nodes are up, the triangles and draws of the last frame.
     */
    @Volatile var nearBuffers = 0L
    @Volatile var nearIds = 0L
    @Volatile var nearTextures = 0L
    @Volatile var nearNodes = 0
    @Volatile var nearTriangles = 0
    @Volatile var nearDraws = 0
    /** The near ocean's waves (WorldOcean): its mesh's buffers (bytes); whether it is drawn. */
    @Volatile var oceanBuffers = 0L
    @Volatile var oceanOn = false

    const val RAM_BUDGET = 200L shl 20
    const val GPU_BUDGET = 250L shl 20
    /** The near terrain's nodes (buffers and material maps, the cache included): the streamer evicts past it. */
    const val NEAR_GPU_BUDGET = 64L shl 20

    val ram: Long get() = skeleton + facts + plan
    val gpu: Long get() = gpuBuffers + gpuTextures + nearBuffers + nearIds + nearTextures + oceanBuffers

    private fun mb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1048576.0)

    fun line(): String = "world: RAM ${mb(ram)} MB of ${RAM_BUDGET shr 20} (skeleton grids ${mb(skeleton)}, facts ~${mb(facts)}, far plan ${mb(plan)}), " +
        "GPU ${mb(gpu)} MB of ${GPU_BUDGET shr 20} (far view $farParts parts: buffers ${mb(gpuBuffers)}, pictures ${mb(gpuTextures)}; $triangles triangles in $draws draws; " +
        "near terrain $nearNodes nodes: buffers ${mb(nearBuffers)}, material maps ${mb(nearIds)}, ground pictures ${mb(nearTextures)}; $nearTriangles triangles in $nearDraws draws; " +
        "ocean ${if (oceanOn) "on" else "off"} ${mb(oceanBuffers)})"

    /** Whether the world keeps within its budgets. */
    val withinBudget: Boolean get() = ram <= RAM_BUDGET && gpu <= GPU_BUDGET
}
