package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Texture

/**
 * Every texture the town holds on the GPU (TOWN.md, "GPU memory"): its owner (the town's code that made it), size, format,
 * mip levels and bytes, from [StreetTextures]' one place where textures are made to the one where they are destroyed.
 * [table] groups them by owner and adds the shadow map and render targets ([GpuEstimate]); [line] is the breadcrumbs'
 * one-line summary, so the phone reports the same numbers the desktop preview prints.
 *
 * The bytes are what the formats take (a mip chain a third more; ASTC 4x4 one byte a texel), not what the driver pads to.
 */
object GpuAccount {
    /** [exact]: for an ASTC texture, the share of its level 0's blocks stored exactly; -1 for any other. */
    class Entry(val owner: String, val w: Int, val h: Int, val format: String, val levels: Int, val bytes: Long, val exact: Double = -1.0)

    private val live = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Texture, Entry>())

    internal fun add(t: Texture, w: Int, h: Int, format: String, levels: Int, bytes: Long, exact: Double = -1.0) {
        live[t] = Entry(owner(), w, h, format, levels, bytes, exact)
    }

    /**
     * What the textures would take if only the ASTC pictures with at least [min] of their blocks exact stayed ASTC (the
     * rest RGBA8 with their mip levels, as before): "min: MB" for each, to choose [TownQuality.astcExact].
     */
    fun policies(vararg min: Double): String {
        val e = entries()
        return min.joinToString(", ") { m ->
            val b = e.sumOf { x ->
                if (x.exact < 0 || x.exact >= m) x.bytes
                else { var s = 0L; for (l in 0 until x.levels) s += maxOf(1, x.w shr l).toLong() * maxOf(1, x.h shr l) * 4; s }
            }
            "%.0f%% exact: %s MB".format(java.util.Locale.US, m * 100, mb(b))
        }
    }

    internal fun remove(t: Texture) { live.remove(t) }

    /** The town's code that asked for a texture: the first two scene3d classes on the stack past the helpers. */
    private fun owner(): String {
        val skip = setOf("GpuAccount", "StreetTextures", "TownMemory", "TownVertex", "Lazy", "SynchronizedLazyImpl", "UnsafeLazyImpl")
        val names = ArrayList<String>(2)
        for (e in Thread.currentThread().stackTrace) {
            if (!e.className.contains(".scene3d.") && !e.className.contains(".scene.")) continue
            val c = e.className.substringAfterLast('.').substringBefore('$')
            if (c in skip || names.lastOrNull() == c) continue
            names += c
            if (names.size == 2) break
        }
        return names.joinToString("<").ifEmpty { "other" }
    }

    fun entries(): List<Entry> = synchronized(live) { live.values.toList() }

    private fun mb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1048576.0)

    /** One line for the breadcrumbs: textures by format and the biggest owners (MB). */
    fun line(): String {
        val e = entries()
        val byFmt = e.groupBy { it.format }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }
            .joinToString(", ") { g -> "${g.key} ${g.value.size} = ${mb(g.value.sumOf { it.bytes })}" }
        val byOwner = e.groupBy { it.owner.substringBefore('<') }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }.take(6)
            .joinToString(", ") { g -> "${g.key} ${mb(g.value.sumOf { it.bytes })}" }
        return "GPU textures ${mb(e.sumOf { it.bytes })} MB in ${e.size} ($byFmt); by owner: $byOwner"
    }

    /** The whole account: by owner (count, texels, bytes, formats), then the shadow map, targets and surfaces of [est]. */
    fun table(est: GpuEstimate?): String {
        val e = entries()
        val sb = StringBuilder()
        sb.appendLine("%-48s %6s %9s %9s  %s".format("owner", "count", "Mtexels", "MB", "formats (count: MB)"))
        for ((owner, list) in e.groupBy { it.owner }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }) {
            val fm = list.groupBy { "${it.format}${if (it.levels > 1) "+mips" else ""}" }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }
                .joinToString(", ") { g -> "${g.key} ${g.value.size}: ${mb(g.value.sumOf { it.bytes })}" }
            sb.appendLine("%-48s %6d %9.2f %9s  %s".format(owner.take(48), list.size, list.sumOf { it.w.toLong() * it.h } / 1e6, mb(list.sumOf { it.bytes }), fm))
        }
        sb.appendLine("%-48s %6d %9.2f %9s".format("textures, all", e.size, e.sumOf { it.w.toLong() * it.h } / 1e6, mb(e.sumOf { it.bytes })))
        if (est != null) {
            sb.appendLine("%-48s %6s %9s %9s".format("vertex + index buffers", "", "", mb(est.vertices + est.indices)))
            sb.appendLine("%-48s %6s %9s %9s".format("shadow map (DEPTH16, cascades as layers)", "", "", mb(est.shadows)))
            sb.appendLine("%-48s %6s %9s %9s".format("render targets (RGBA16F colour + depth32)", "", "", mb(est.targets)))
            sb.appendLine("%-48s %6s %9s %9s".format("surfaces (3 + 3 RGBA8, an estimate)", "", "", mb(est.surfaces)))
            sb.appendLine("%-48s %6s %9s %9s".format("total", "", "", mb(est.total - est.textures + e.sumOf { it.bytes })))
        }
        return sb.toString()
    }

    /**
     * -DtexStats (the desktop): for each RGBA texture as made, how many of its 4x4, 6x6 and 8x8 blocks hold one colour, two,
     * or more (texels counted as RGBA, see-through ones too), by owner: how much of the pictures a block compression
     * could keep exact. [statsReport] prints them.
     */
    @Volatile var stats = false
    private class Blocks { val n = LongArray(3 * 4); var texels = 0L }
    private val blockStats = java.util.concurrent.ConcurrentHashMap<String, Blocks>()

    internal fun study(buf: java.nio.ByteBuffer, w: Int, h: Int, format: String) {
        if (!stats || !format.startsWith("SRGB8_A8") && format != "RGBA8") return
        val b = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN); b.rewind()
        val px = IntArray(w * h); b.asIntBuffer().get(px)
        val st = blockStats.getOrPut(owner().substringBefore('<') + " " + format) { Blocks() }
        val set = HashSet<Int>()
        synchronized(st) {
            st.texels += w.toLong() * h
            for ((k, n) in intArrayOf(4, 6, 8).withIndex()) {
                var by = 0
                while (by < h) {
                    var bx = 0
                    while (bx < w) {
                        set.clear()
                        for (y in by until minOf(by + n, h)) for (x in bx until minOf(bx + n, w)) set += px[y * w + x]
                        st.n[k * 4 + minOf(set.size, 4) - 1]++
                        bx += n
                    }
                    by += n
                }
            }
        }
    }

    fun statsReport(): String = blockStats.entries.sortedByDescending { it.value.texels }.joinToString("\n") { (o, s) ->
        fun pc(k: Int) = (0 until 4).joinToString("/") { "%.0f".format(java.util.Locale.US, 100.0 * s.n[k * 4 + it] / s.n.slice(k * 4 until k * 4 + 4).sum().coerceAtLeast(1)) }
        "%-40s %7.1fM texels; blocks of 1/2/3/4+ colours (%%): 4x4 %s, 6x6 %s, 8x8 %s".format(o, s.texels / 1e6, pc(0), pc(1), pc(2))
    }
}
