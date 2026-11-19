package com.lifemax.rpg.feature.habits.presentation.scene3d

/**
 * The town's GPU quality settings and what they cost in GPU memory (TOWN.md T10). Set before a [FilamentTown] is made
 * (the app reads the AI panel's choice into it at start); a town keeps the settings it was made with.
 *
 * The sun's shadow map is a DEPTH16 texture array, one [shadowMap] x [shadowMap] layer per cascade, drawn again every
 * frame the sun casts. Filament clamps a light's map to 2048 texels (LightManager: `clamp(mapSize, 8, 2048)`), so the
 * 4096 the town once asked for was always 2048 x 4 cascades, 32 MB (the renders at 2048, 3072 and 4096 are the same
 * pixels). Filament allocates it from its frame graph (it is not ours to count), so [estimate] works it out.
 */
object TownQuality {
    /** Each shadow cascade's size (texels square): 2048, the most Filament draws (and what the renders are judged by). */
    @Volatile var shadowMap = MAX_MAP
    /** How many shadow cascades (1-4): 4 by default (3 visibly changes the shadows near the camera). */
    @Volatile var cascades = 4

    /**
     * The open world's drawing resolution (the AI panel's "Resolution"): [RES_AUTO] (the default) draws at the screen's
     * full resolution while the GPU keeps up and lowers it smoothly (to [AUTO_MIN] at most, upscaled with FSR's edge-aware
     * filter) only while it does not, so a heavy view drops a little sharpness instead of frames; [RES_FULL] always full;
     * a share (0.85, 0.75) a fixed scale. Untested on the S25 so far: there, Filament's own frame timing drives it.
     */
    @Volatile var resolution = RES_AUTO
    const val RES_AUTO = 0f
    const val RES_FULL = 1f
    const val AUTO_MIN = .72f

    /** [view]'s resolution options for [resolution]. */
    fun applyResolution(view: com.google.android.filament.View) {
        val r = resolution
        view.dynamicResolutionOptions = com.google.android.filament.View.DynamicResolutionOptions().apply {
            enabled = r != RES_FULL
            minScale = if (r == RES_AUTO) AUTO_MIN else r
            maxScale = if (r == RES_AUTO) 1f else r
            quality = com.google.android.filament.View.QualityLevel.HIGH
            sharpness = .9f
            homogeneousScaling = true
        }
    }

    /** The largest shadow map Filament makes (texels square). */
    const val MAX_MAP = 2048

    /**
     * Where the cascades split (a share of the shadowed depth): the town's own for 4, spread alike for fewer. Always three
     * (Filament reads three; only the first n - 1 count).
     */
    fun splits(n: Int): FloatArray = when (n.coerceIn(1, 4)) {
        4 -> floatArrayOf(.08f, .2f, .45f)
        3 -> floatArrayOf(.12f, .35f, 1f)
        2 -> floatArrayOf(.2f, 1f, 1f)
        else -> floatArrayOf(1f, 1f, 1f)
    }

    /**
     * What the town holds on the GPU for a [w] x [h] view: what it made ([gpu]: textures with their mipmaps, vertex and
     * index buffers), the shadow map ([map] x [map] DEPTH16, [cascades] layers), Filament's own targets (the HDR colour
     * buffer, RGBA16F because the view blends over the sky, and a 32-bit depth buffer), and the surfaces it is shown
     * through (the TextureView's and the window's buffers, three each, RGBA8). The last two are estimates from the
     * sizes: the driver's padding and Compose's own textures are not in it.
     */
    fun estimate(gpu: GpuBytes, map: Int, cascades: Int, w: Int, h: Int): GpuEstimate {
        val px = w.toLong() * h
        return GpuEstimate(
            textures = gpu.textures.get(), vertices = gpu.vertices.get(), indices = gpu.indices.get(),
            shadows = map.coerceAtMost(MAX_MAP).let { it.toLong() * it * 2 * cascades },
            targets = px * (8 + 4),
            surfaces = px * 4 * 6,
        )
    }
}

/** A town's GPU memory by kind (bytes): see [TownQuality.estimate]. */
data class GpuEstimate(val textures: Long, val vertices: Long, val indices: Long, val shadows: Long, val targets: Long, val surfaces: Long) {
    val total: Long get() = textures + vertices + indices + shadows + targets + surfaces

    /** One line in MB: "GPU est. 812 MB: textures 360, vertices 190, ...". */
    fun line(): String {
        fun mb(b: Long) = (b + (1L shl 19)) shr 20
        return "GPU est. ${mb(total)} MB: textures ${mb(textures)} (with mipmaps), vertices ${mb(vertices)}, indices ${mb(indices)}, " +
            "shadow map ${mb(shadows)}, render targets ${mb(targets)}, surfaces ${mb(surfaces)}"
    }
}
