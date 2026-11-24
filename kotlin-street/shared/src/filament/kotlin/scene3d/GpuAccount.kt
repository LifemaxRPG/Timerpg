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
    class Entry(val owner: String, val w: Int, val h: Int, val format: String, val levels: Int, val bytes: Long, val exact: Double = -1.0,
        /** The picture's content hash for one shared by content ([StreetTextures]), else 0. */
        val content: Long = 0L)

    private val live = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Texture, Entry>())

    internal fun add(t: Texture, w: Int, h: Int, format: String, levels: Int, bytes: Long, exact: Double = -1.0, content: Long = 0L) {
        live[t] = Entry(withScope(owner()), w, h, format, levels, bytes, exact, content)
    }

    /**
     * Textures holding the same picture as another one held (the same pixels and size, made twice: by two sets of
     * textures, or once with mipmaps and once without): how many, and the bytes the extra copies take. Only pictures shared
     * by content are hashed (a mask rewritten by the hour is not).
     */
    fun duplicates(): Pair<Int, Long> {
        val e = entries().filter { it.content != 0L }
        var n = 0; var b = 0L
        for (g in e.groupBy { Triple(it.content, it.w, it.h) }.values) if (g.size > 1) { n += g.size - 1; b += g.sortedBy { it.bytes }.dropLast(1).sumOf { it.bytes } }
        return n to b
    }

    /**
     * What the town's code on this thread is uploading now ("yards", "ground", "chunks", "trees"): set around an upload
     * whose owner on the stack alone does not say (the town's own layers all upload through [PropLayer]), so the
     * category breakdown ([categories]) can tell the yards from the street things and the ground.
     */
    private val scope = ThreadLocal<String?>()
    internal inline fun <T> scoped(what: String, f: () -> T): T {
        val was = scopeNow(); setScope(what)
        try { return f() } finally { setScope(was) }
    }
    @PublishedApi internal fun scopeNow(): String? = scope.get()
    @PublishedApi internal fun setScope(s: String?) = scope.set(s)
    private fun withScope(owner: String): String = scope.get()?.let { "$owner[$it]" } ?: owner

    /**
     * The vertex and index buffers on the GPU, as [textures][add] are: each with its owner and bytes, from where it is
     * made ([TownVertex] and the few places that build their own). A buffer [com.google.android.filament.Engine] has
     * destroyed drops out by itself (its Java object's native handle is cleared), so no destroy site has to report it:
     * what is listed is what is still held.
     */
    /** [gpu]: a buffer object of its own; else a range of [BufferArena]'s shared pages ([GeomSlice]). */
    class Buf(val owner: String, val bytes: Long, val gpu: Boolean = true)
    private val buffers = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Any, Buf>())

    /** A vertex or index buffer of [bytes] just built (any of Filament's buffer objects), or ([gpu] false) a range of the shared pages. */
    fun addBuffer(b: Any, bytes: Long, gpu: Boolean = true) { buffers[b] = Buf(withScope(owner()), bytes, gpu) }

    private fun alive(o: Any): Boolean = try {
        when (o) {
            is com.google.android.filament.VertexBuffer -> o.nativeObject != 0L
            is com.google.android.filament.IndexBuffer -> o.nativeObject != 0L
            is Texture -> o.nativeObject != 0L
            is GeomSlice -> !o.freed
            else -> true
        }
    } catch (e: IllegalStateException) { false }

    /** The buffers still held (those destroyed since are dropped from the list here). */
    fun bufferEntries(): List<Buf> = synchronized(buffers) {
        buffers.keys.retainAll { alive(it) }
        buffers.values.toList()
    }

    /**
     * The GPU memory by what it is for (MB, textures and buffers together), the town's parts the owner asked for in one
     * line: the town's modelled buildings and stores, the GLB landmarks, the yards, the street things and greenery, the
     * trees' cards, the ground and terrain, the cars' debris, people, vehicles; then the shadow map and render targets
     * from [est]. Also how many textures and buffers each holds (many small allocations cost the driver more than
     * their bytes).
     */
    fun categories(est: GpuEstimate? = null): Map<String, Any?> {
        val tex = entries()
        val bufs = bufferEntries()
        class Sum { var tb = 0L; var tn = 0; var bb = 0L; var bn = 0 }
        val sums = LinkedHashMap<String, Sum>()
        for (e in tex) sums.getOrPut(category(e.owner)) { Sum() }.let { it.tb += e.bytes; it.tn++ }
        for (b in bufs) sums.getOrPut(category(b.owner)) { Sum() }.let { it.bb += b.bytes; it.bn++ }
        fun mb(b: Long) = Math.round(b / 104857.6) / 10.0
        val out = LinkedHashMap<String, Any?>()
        for ((k, s) in sums.entries.sortedByDescending { it.value.tb + it.value.bb })
            out[k] = linkedMapOf("mb" to mb(s.tb + s.bb), "texturesMb" to mb(s.tb), "textures" to s.tn, "buffersMb" to mb(s.bb), "buffers" to s.bn)
        // the shared buffer pages ([BufferArena]): the ranges in them are counted with their parts above, their free space here
        val arena = BufferArena.stats()
        if (arena.pages > 0) out["buffer pages, free space"] = linkedMapOf("mb" to mb(arena.bytes - arena.used), "pages" to arena.pages, "pagesMb" to mb(arena.bytes))
        if (est != null) {
            out["shadow map"] = linkedMapOf("mb" to mb(est.shadows))
            out["render targets + surfaces"] = linkedMapOf("mb" to mb(est.targets + est.surfaces))
        }
        val total = tex.sumOf { it.bytes } + bufs.sumOf { it.bytes } + (arena.bytes - arena.used) + (est?.let { it.shadows + it.targets + it.surfaces } ?: 0L)
        // (the buffer objects the driver holds: those of their own and the shared pages, not the ranges in them)
        val own = bufs.filter { it.gpu }
        val pageSizes = BufferArena.pageSizes()
        // (what the allocations come to if the driver rounds each one up, to a 4 KB page or a 64 KB block: tens of
        // thousands of small buffers cost the GPU driver more than their bytes, a lead for "graphics" beyond this count)
        fun up(b: Long, unit: Long) = (b + unit - 1) / unit * unit
        fun rounded(unit: Long) = own.sumOf { up(it.bytes, unit) } + pageSizes.sumOf { up(it, unit) } + tex.sumOf { up(it.bytes, unit) }
        out["total"] = linkedMapOf("mb" to mb(total), "textures" to tex.size, "buffers" to own.size + arena.pages,
            "bufferPages" to arena.pages, "bufferRanges" to bufs.size - own.size,
            "buffersUnder16k" to own.count { it.bytes < 16384 } + pageSizes.count { it < 16384 }, "texturesUnder64k" to tex.count { it.bytes < 65536 },
            "allocationsAt4kMb" to mb(rounded(4096)), "allocationsAt64kMb" to mb(rounded(65536)))
        return out
    }

    /** [categories] in one line for the breadcrumbs: "GPU by part: buildings 412 MB (tex 380 in 1200, buf 32 in 300), ...". */
    fun categoryLine(est: GpuEstimate? = null): String = "GPU by part (Filament's own count): " + categories(est).entries.joinToString(", ") { (k, v) ->
        @Suppress("UNCHECKED_CAST") val m = v as Map<String, Any?>
        if (m.containsKey("texturesMb")) "$k ${m["mb"]} MB (textures ${m["texturesMb"]} in ${m["textures"]}, buffers ${m["buffersMb"]} in ${m["buffers"]})"
        else if (m.containsKey("pages")) "$k ${m["mb"]} MB (${m["pages"]} pages of ${m["pagesMb"]} MB)"
        else if (k == "total") "total ${m["mb"]} MB in ${m["textures"]} textures and ${m["buffers"]} buffer objects (${m["bufferPages"]} of them shared pages holding ${m["bufferRanges"]} ranges; ${m["buffersUnder16k"]} buffers under 16 KB; " +
            "textures and buffers rounded up to 4 KB each ${m["allocationsAt4kMb"]} MB, to 64 KB each ${m["allocationsAt64kMb"]} MB)" else "$k ${m["mb"]} MB"
    }

    /** The buffers by raw owner (MB, count), largest first: the desktop's gpu.txt. */
    fun bufferTable(): String = bufferEntries().groupBy { it.owner }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }
        .joinToString("\n") { (o, l) -> "%-56s %6d buffers %6d ranges %9s MB  -> %s".format(o.take(56), l.count { it.gpu }, l.count { !it.gpu }, mb(l.sumOf { it.bytes }), category(o)) } +
        BufferArena.stats().let { a -> "\n%-56s %6d pages  %9s MB held, %s MB of it in use".format("BufferArena (shared pages)", a.pages, mb(a.bytes), mb(a.used)) }

    /** What an owner (as [owner] and [withScope] wrote it) is for. */
    internal fun category(owner: String): String {
        val s = owner.substringAfter('[', "").substringBefore(']')
        val o = owner.substringBefore('[')
        fun has(vararg c: String) = c.any { n -> o.split('<').any { it == n } }
        return when {
            has("TownWreck") -> "debris"
            s == "landmarks" || has("TownLandmark", "LandmarkKit", "LandmarkAnimation", "LandmarkAnimator", "GlbFile") -> "GLB landmarks"
            has("TownFarms", "FarmPics") -> "farms"
            has("TownCars", "VehicleKit", "TownBike", "RideLayer") -> "vehicles"
            has("TownPeople") -> "people"
            s == "yards" || has("YardLayer", "YardKit", "TownYardHook") -> "yards"
            has("WorldNear", "WorldFar", "TerrainPics", "TownGround", "OceanSurface", "TownFar") || s == "ground" -> "ground and terrain"
            s == "trees" || has("TreeCells", "TreeKit") -> "trees"
            s == "chunks" -> "street things and greenery"
            has("FilamentModels", "BuildingMeshes", "TownStores", "StoreKit", "StoreSolids", "StoreSigns", "TownCinema", "CinemaLayer", "RoomKit") -> "buildings and stores"
            has("TownRailDraw", "RailKit") -> "rail"
            has("FilamentTown") -> "town, other"
            else -> o.substringBefore('<').ifEmpty { "other" }
        }
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
        val skip = setOf("GpuAccount", "BufferArena", "GeomSlice", "StreetTextures", "TownMemory", "TownVertex", "Lazy", "SynchronizedLazyImpl", "UnsafeLazyImpl")
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

    fun entries(): List<Entry> = synchronized(live) { live.keys.retainAll { alive(it) }; live.values.toList() }

    private fun mb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1048576.0)

    /** One line for the breadcrumbs: textures by format and the biggest owners (MB). */
    fun line(): String {
        val e = entries()
        val byFmt = e.groupBy { it.format }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }
            .joinToString(", ") { g -> "${g.key} ${g.value.size} = ${mb(g.value.sumOf { it.bytes })}" }
        val byOwner = e.groupBy { it.owner.substringBefore('<') }.entries.sortedByDescending { g -> g.value.sumOf { it.bytes } }.take(6)
            .joinToString(", ") { g -> "${g.key} ${mb(g.value.sumOf { it.bytes })}" }
        // (the world layer's own numbers, WORLD_GEN.md 7's budget beside them, once it is there: its pictures are in the textures too, owner WorldFar)
        val world = com.lifemax.rpg.feature.habits.domain.WorldMemory.let { w -> if (w.ram > 0 || w.gpu > 0) "; " + w.line() else "" }
        return "GPU textures ${mb(e.sumOf { it.bytes })} MB in ${e.size} ($byFmt); by owner: $byOwner$world"
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
