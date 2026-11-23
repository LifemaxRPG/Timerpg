package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The street's pictures as Filament textures, made once from the same ImageBitmaps the classic street draws (read with
 * Compose's own readPixels, so the same code serves the phone and the desktop). [srgb] for the lit materials (the
 * pictures' colours are sRGB; lighting wants them linear); plain RGBA8 for the exact albedo comparison.
 */
internal class StreetTextures(private val engine: Engine, private val srgb: Boolean) {
    /**
     * The textures made from pictures, by picture. Weakly held (an ImageBitmap's equality is its identity): once a
     * building's art is let go after its upload, its decoded pictures can be collected (the textures stay, in [made]).
     */
    private val byImage: MutableMap<ImageBitmap, Texture> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    /** Pictures read and packed ahead on a worker ([warm]), waiting for [of] on the engine's thread. */
    private val warmed: MutableMap<ImageBitmap, Prepared> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    private val made = ArrayList<Texture>()
    /** Textures made by size ("w x h" to how many): what the GPU memory goes to (the preview's report, the breadcrumbs). */
    val bySize = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** What this set's owner has put on the GPU: these textures (with their mipmaps), and the buffers its users count in. */
    val gpu = GpuBytes()
    init {
        // (ASTC where the GPU reads it and the quality setting asks for it: [TownQuality.astc])
        useAstc = TownQuality.astc && Texture.isTextureFormatSupported(engine, Texture.InternalFormat.SRGB8_ALPHA8_ASTC_4x4) &&
            Texture.isTextureFormatSupported(engine, Texture.InternalFormat.RGBA_ASTC_4x4)
    }
    val white: Texture = solid(0xFFFFFFFF.toInt())
    val black: Texture = solid(0x00000000)

    /** The texture of [img] as drawn. */
    fun of(img: ImageBitmap): Texture = byImage[img] ?: run {
        // (a picture drawn for the first time, a person's sheet: made here; read and packed here too if it was not warmed)
        com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.count(com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.UP_SHEETS)
        val w = warmed.remove(img)
        if (w == null) coldSheets++
        make(w ?: prepare(argb(img), img.width, img.height, mips = true)).also { byImage[img] = it }
    }
    /** Pictures [of] had to read and pack on the engine's thread (not warmed on a worker first). */
    var coldSheets = 0; private set

    /**
     * Reads and packs [img] for [of] (any thread: no engine call), so the engine's thread only makes the texture. Nothing
     * if it is made or warmed already.
     */
    fun warm(img: ImageBitmap) {
        if (byImage.containsKey(img) || warmed.containsKey(img)) return
        warmed[img] = prepare(argb(img), img.width, img.height, mips = true)
    }

    /** Whether [img]'s texture is to be had at once: made already, or read and packed by [warm]. */
    fun ready(img: ImageBitmap): Boolean = byImage.containsKey(img) || warmed.containsKey(img)
    private val warming: MutableSet<ImageBitmap> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * [warm]s [img] on [pool] unless it is [ready] or on its way: a townsperson's sheet seen for the first time is read and
     * packed on a worker (a frame or two), not on the engine's thread in the middle of a frame (a spike: half a MB read,
     * packed and mipmapped, then collected).
     */
    fun warmLater(img: ImageBitmap, pool: java.util.concurrent.Executor) {
        if (ready(img) || !warming.add(img)) return
        pool.execute { try { warm(img) } catch (t: Throwable) { } finally { warming.remove(img) } }
    }

    /**
     * Lets go what [warm] packed for [imgs] and [of] never took (a building's pictures read ahead for faces it did not
     * draw in the end): kept, they waited for their picture to be collected, tens of MB of them while the town built.
     */
    fun drop(imgs: Collection<ImageBitmap>) { for (i in imgs) warmed.remove(i) }

    /**
     * Pictures never updated ([mips]) by their content: the same pixels make one texture however many batches or
     * buildings wear them (repeated sprites, yard kit pictures baked lot by lot, windows' glass of repeated fronts).
     */
    private val byContent = HashMap<ContentKey, Texture>()
    internal data class ContentKey(val w: Int, val h: Int, val linear: Boolean, val a: Int, val b: Long, val mips: Boolean = true)

    /** Textures made, texels in them, and how many asks were answered by one already made. */
    var madeCount = 0; private set
    var texels = 0L; private set
    var shared = 0; private set

    /**
     * A texture from ARGB pixels (rows top first), owned here; [mips]: with its mipmaps (for a picture never updated:
     * such pictures are shared by content, so never [update] one).
     */
    fun make(px: IntArray, w: Int, h: Int, linear: Boolean = false, mips: Boolean = false): Texture = make(prepare(px, w, h, linear, mips))

    /** A picture [prepare]d on any thread (packed, its content's key worked out), made a texture here (or shared). */
    fun make(p: Prepared): Texture {
        // (a mask with nothing in it reads as the plain black one: no light, no glass, no own colours)
        if (p.empty) { shared++; return black }
        val key = p.key ?: return build(p.buf, p.w, p.h, p.linear, false, p.fmt)
        byContent[key]?.let { shared++; return it }
        // (one [prepare] left unencoded, its picture made already, but by another set of textures: encoded now)
        val q = if (p.src != null) prepare(p.src, p.w, p.h, p.linear, mips = true, again = true) else p
        lastExact = q.exact
        return build(q.buf, q.w, q.h, q.linear, key.mips, q.fmt).also { byContent[key] = it; madeKeys += key }
    }
    private var lastExact = -1.0

    /**
     * A picture ready for the GPU: its bytes as Filament takes them ([fmt]: its channels) and, for one never updated, its
     * content's key; [empty]: a mask with nothing in it (made as the plain black texture).
     */
    class Prepared internal constructor(internal val buf: ByteBuffer, val w: Int, val h: Int, val linear: Boolean, internal val key: ContentKey?,
        internal val fmt: Fmt = Fmt.RGBA8, internal val empty: Boolean = false,
        /** Left unencoded (its picture is made already, [madeKeys]): the pixels, should it need making after all. */
        internal val src: IntArray? = null,
        /** For ASTC, the share of its level 0's blocks stored exactly ([Astc]); -1 for any other. */
        internal val exact: Double = -1.0)

    /**
     * A texture's channels. A mask (street_lit.mat's `mask`: red a lamp's light, green glass, blue a lit display, alpha
     * between a quarter and three quarters a light in its own colour) samples missing channels as 0 and a missing alpha as
     * 1, which reads exactly as an opaque or clear alpha does: a mask with no blue and no own-colour light needs two
     * channels ([RG8]), one with no glass either one ([R8]), the same pixels on screen for a half or a quarter of the bytes.
     */
    enum class Fmt(val bytes: Int, val pixels: Texture.Format) {
        RGBA8(4, Texture.Format.RGBA), RG8(2, Texture.Format.RG), R8(1, Texture.Format.R),
        /** ASTC 4x4 ([Astc]): a byte a texel, its mip levels (if any) encoded with it; never updated. */
        ASTC(1, Texture.Format.RGBA),
    }
    /** The format of each texture made here that is not RGBA ([update] writes it in that format). */
    private val fmts = java.util.IdentityHashMap<Texture, Fmt>()


    /** The [n] sizes holding the most texels: "w x h x count = M texels". */
    fun biggest(n: Int = 8): String = bySize.entries.sortedByDescending { e -> e.key.split("x").let { it[0].toLong() * it[1].toLong() } * e.value }.take(n)
        .joinToString(", ") { e -> val (w, h) = e.key.split("x").map { it.toLong() }; "${e.key} x${e.value} = %.1fM".format(java.util.Locale.US, w * h * e.value / 1e6) }

    private fun build(buf: ByteBuffer, w: Int, h: Int, linear: Boolean, mips: Boolean, fmt: Fmt = Fmt.RGBA8): Texture {
        madeCount++; texels += w.toLong() * h
        bySize.merge("${w}x$h", 1, Int::plus)
        if (fmt == Fmt.ASTC) return buildAstc(buf, w, h, linear, mips)
        val format = when (fmt) {
            Fmt.ASTC -> error("ASTC")
            Fmt.R8 -> Texture.InternalFormat.R8
            Fmt.RG8 -> Texture.InternalFormat.RG8
            Fmt.RGBA8 -> if (srgb && !linear) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8
        }
        // (a texture whose mipmaps the engine makes must say so, or Filament aborts the process)
        val levels = if (mips && Texture.isTextureFormatMipmappable(engine, format)) 32 - Integer.numberOfLeadingZeros(maxOf(w, h)) else 1
        val t = Texture.Builder().width(w).height(h).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(format)
            .usage(if (levels > 1) Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE else Texture.Usage.DEFAULT).build(engine)
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, fmt.pixels, Texture.Type.UBYTE))
        if (levels > 1) t.generateMipmaps(engine)
        made += t
        if (fmt != Fmt.RGBA8) fmts[t] = fmt
        var bytes = 0L
        for (l in 0 until levels) bytes += maxOf(1, w shr l).toLong() * maxOf(1, h shr l) * fmt.bytes
        gpu.textures.addAndGet(bytes)
        GpuAccount.add(t, w, h, if (fmt == Fmt.RGBA8 && srgb && !linear) "SRGB8_A8" else fmt.name, levels, bytes)
        GpuAccount.study(buf, w, h, if (fmt == Fmt.RGBA8 && srgb && !linear) "SRGB8_A8" else fmt.name)
        com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.bytes += w.toLong() * h * fmt.bytes
        return t
    }

    /** An ASTC 4x4 texture from [buf] ([Astc.encodeChain]: every level, level 0 first). */
    private fun buildAstc(buf: ByteBuffer, w: Int, h: Int, linear: Boolean, mips: Boolean): Texture {
        val levels = if (mips) levelsOf(w, h) else 1
        val s = srgb && !linear
        val t = Texture.Builder().width(w).height(h).levels(levels).sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (s) Texture.InternalFormat.SRGB8_ALPHA8_ASTC_4x4 else Texture.InternalFormat.RGBA_ASTC_4x4).usage(Texture.Usage.DEFAULT).build(engine)
        val type = if (s) Texture.CompressedType.SRGB8_ALPHA8_ASTC_4x4 else Texture.CompressedType.RGBA_ASTC_4x4
        var off = 0
        for (l in 0 until levels) {
            val n = Astc.levelBytes(maxOf(1, w shr l), maxOf(1, h shr l))
            val slice = buf.duplicate().also { it.position(off); it.limit(off + n) }.slice().order(ByteOrder.nativeOrder())
            t.setImage(engine, l, Texture.PixelBufferDescriptor(slice, type, n))
            off += n
        }
        made += t
        gpu.textures.addAndGet(off.toLong())
        GpuAccount.add(t, w, h, if (s) "SRGB_ASTC_4x4" else "ASTC_4x4", levels, off.toLong(), lastExact)
        com.lifemax.rpg.feature.habits.presentation.scene.FrameWork.bytes += off
        return t
    }

    /** Replaces the pixels of [t] (made here, same size, RGBA). */
    fun update(t: Texture, px: IntArray) {
        val buf = pack(px, t.getWidth(0), t.getHeight(0))
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
    }


    /** Replaces the pixels of [t] (made here, same size) with [buf], already as Filament takes them ([offset], in [t]'s format). */
    fun update(t: Texture, buf: ByteBuffer) {
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, (fmts[t] ?: Fmt.RGBA8).pixels, Texture.Type.UBYTE))
    }

    fun solid(argb: Int): Texture = make(intArrayOf(argb), 1, 1, linear = true)

    /** Frees what was made from the street's pictures; [all] also the plain white and black. */
    fun destroy(all: Boolean = false) {
        for (t in made) if (all || (t !== white && t !== black)) { engine.destroyTexture(t); GpuAccount.remove(t); fmts.remove(t) }
        madeKeys.removeAll(byContent.keys)
        made.clear(); byImage.clear(); warmed.clear(); byContent.clear()
        gpu.textures.set(0L)
        if (!all) { made += white; made += black; gpu.textures.set(8L) }
    }

    companion object {
        /**
         * Packs [px] for [make] (any thread: no engine call): [mips] for a picture never updated, which is then shared by
         * its content.
         */
        fun prepare(px: IntArray, w: Int, h: Int, linear: Boolean = false, mips: Boolean = false): Prepared = prepare(px, w, h, linear, mips, false)

        internal fun prepare(px: IntArray, w: Int, h: Int, linear: Boolean, mips: Boolean, again: Boolean): Prepared {
            if (!mips) return Prepared(pack(px, w, h), w, h, linear, null)
            val key = ContentKey(w, h, linear, px.contentHashCode(), hash64(px))
            if (useAstc) {
                // (a picture made already: its texture is shared by its content, so it is not encoded again)
                if (!again && key in madeKeys) return Prepared(EMPTY, w, h, linear, key, Fmt.ASTC, src = px)
                // a picture never updated, on a GPU that reads ASTC: encoded here, with its mip levels, on the caller's
                // thread; kept RGBA8 if fewer of its blocks than [TownQuality.astcExact] would be exact
                val st = Astc.Stats()
                val buf = Astc.encodeChain(px, w, h, levelsOf(w, h), srgb = !linear, stats = st, level0Only = true)
                val share = st.exact.toDouble() / (st.exact + st.near).coerceAtLeast(1)
                if (share >= TownQuality.astcExact) return Prepared(buf, w, h, linear, key, Fmt.ASTC, exact = share)
            }
            return Prepared(pack(px, w, h), w, h, linear, key)
        }

        /** The content keys of the pictures made (by any set of textures): [prepare] leaves these unencoded. */
        private val madeKeys: MutableSet<ContentKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** A full mip chain's levels for [w] x [h]. */
        fun levelsOf(w: Int, h: Int): Int = 32 - Integer.numberOfLeadingZeros(maxOf(w, h))

        /**
         * Whether pictures are made ASTC 4x4 ([Astc]; [TownQuality.astc] and a GPU that reads it): set when the town's
         * textures are made, before anything is prepared for them.
         */
        @Volatile var useAstc = false

        /**
         * [px] packed for [make] as a picture never updated, shared by its content, but without mipmaps: for one only ever
         * sampled with a nearest filter (the props' and yards' glow masks), whose smaller levels nothing would read.
         */
        fun prepareFlat(px: IntArray, w: Int, h: Int, linear: Boolean): Prepared {
            // (a linear picture without mipmaps is a mask: only the channels it uses, or none at all)
            val fmt = if (linear) maskFormat(px) else Fmt.RGBA8
            if (fmt == null) return Prepared(EMPTY, w, h, linear, null, Fmt.R8, empty = true)
            val key = ContentKey(w, h, linear, px.contentHashCode(), hash64(px), mips = false)
            // (a mask stored exactly in ASTC, every block of it one or two colours: a byte a texel, the same values)
            if (useAstc && fmt != Fmt.R8 && Astc.exact(px, w, h)) return Prepared(Astc.encodeChain(px, w, h, 1, srgb = false), w, h, linear, key, Fmt.ASTC, exact = 1.0)
            return Prepared(pack(px, w, h, fmt), w, h, linear, key, fmt)
        }

        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /**
         * The fewest channels mask [px] (ARGB) needs to read the same in street_lit.mat ([Fmt]); null: it holds nothing
         * (reads as the plain black mask).
         */
        fun maskFormat(px: IntArray): Fmt? {
            var g = false; var b = false; var r = false
            for (c in px) {
                val a = c ushr 24
                // (an alpha from 64 to 191 is a light in its own colour: street_lit.mat's step(0.25) and step(0.75))
                if (a in 64..191) return Fmt.RGBA8
                if (c and 0xFF != 0) b = true
                if (c and 0xFF00 != 0) g = true
                if (c and 0xFF0000 != 0) r = true
            }
            return if (b) Fmt.RGBA8 else if (g) Fmt.RG8 else if (r) Fmt.R8 else null
        }

        /**
         * A blank (transparent black) picture of [w] x [h] for [make], never updated through [prepare]'s path: its bytes
         * are written in place ([offset], [fmt]'s bytes a pixel), with no int array of the pixels on the Java heap.
         */
        fun blank(w: Int, h: Int, linear: Boolean = false, fmt: Fmt = Fmt.RGBA8): Prepared =
            Prepared(TownMemory.direct(w * h * fmt.bytes).order(ByteOrder.nativeOrder()), w, h, linear, null, fmt)

        /** Where pixel [i] of a [w] x [h] picture (rows top first, as ARGB arrays run) starts in a buffer Filament takes ([stride] bytes a pixel). */
        fun offset(i: Int, w: Int, h: Int, stride: Int = 4): Int { val y = i / w; return ((h - 1 - y) * w + (i - y * w)) * stride }

        /**
         * [px] packed as [prepare] with mips (a picture never updated), once per [share] (by the pixel array's identity)
         * however many batches wear it (a mesh packed for many chunks of the town); the second: whether it was packed now.
         * [share] is the caller's, for one build: a global one would keep every packed picture as long as its mesh lives.
         */
        fun prepareShared(px: IntArray, w: Int, h: Int, linear: Boolean, share: MutableMap<IntArray, Prepared>?, mips: Boolean = true): Pair<Prepared, Boolean> {
            fun make() = if (mips) prepare(px, w, h, linear, mips = true) else prepareFlat(px, w, h, linear)
            if (share == null) return make() to true
            synchronized(share) { share[px]?.let { if (it.w == w && it.h == h && it.linear == linear && it.key?.mips == mips) return it to false } }
            val p = make()
            synchronized(share) { share[px] = p }
            return p to true
        }

        /** A second, independent hash of the pixels (FNV-1a over the ints), so two pictures are one only if both agree. */
        fun hash64(px: IntArray): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in px) { h = (h xor (c.toLong() and 0xffffffffL)) * 0x100000001b3L }
            return h
        }

        /** RGBA bytes, the picture's bottom row first: Filament's textures (as GL's) start at the bottom, v = 0 there. */
        private fun pack(px: IntArray, w: Int, h: Int, fmt: Fmt = Fmt.RGBA8): ByteBuffer {
            val buf = TownMemory.direct(w * h * fmt.bytes).order(ByteOrder.nativeOrder())
            for (y in h - 1 downTo 0) for (x in 0 until w) {
                val c = px[y * w + x]
                when (fmt) {
                    Fmt.RGBA8 -> buf.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
                    Fmt.RG8 -> buf.put((c shr 16).toByte()).put((c shr 8).toByte())
                    Fmt.R8 -> buf.put((c shr 16).toByte())
                    Fmt.ASTC -> error("ASTC is encoded, not packed")
                }
            }
            buf.flip()
            return buf
        }

        fun argb(img: ImageBitmap): IntArray {
            val px = IntArray(img.width * img.height)
            img.readPixels(px, 0, 0, img.width, img.height)
            return px
        }
    }
}

/**
 * The bytes a town (or a street) has put on the GPU, as made: textures with their mip chains (RGBA8 or SRGB8_A8, 4 bytes
 * a texel), vertex and index buffers. The driver's own padding, the render targets and the shadow maps are not in it
 * ([FilamentTown.gpuEstimate] adds those).
 */
class GpuBytes {
    val textures = java.util.concurrent.atomic.AtomicLong(0)
    val vertices = java.util.concurrent.atomic.AtomicLong(0)
    val indices = java.util.concurrent.atomic.AtomicLong(0)
    fun buffers(vb: Int, ib: Int) { vertices.addAndGet(vb.toLong()); indices.addAndGet(ib.toLong()) }
}
