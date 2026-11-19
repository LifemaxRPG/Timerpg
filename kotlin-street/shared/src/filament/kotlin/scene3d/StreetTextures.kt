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
    val white: Texture = solid(0xFFFFFFFF.toInt())
    val black: Texture = solid(0x00000000)

    /** The texture of [img] as drawn. */
    fun of(img: ImageBitmap): Texture = byImage[img] ?: make(warmed.remove(img) ?: prepare(argb(img), img.width, img.height, mips = true)).also { byImage[img] = it }

    /**
     * Reads and packs [img] for [of] (any thread: no engine call), so the engine's thread only makes the texture. Nothing
     * if it is made or warmed already.
     */
    fun warm(img: ImageBitmap) {
        if (byImage.containsKey(img) || warmed.containsKey(img)) return
        warmed[img] = prepare(argb(img), img.width, img.height, mips = true)
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
        val key = p.key ?: return build(p.buf, p.w, p.h, p.linear, false)
        byContent[key]?.let { shared++; return it }
        return build(p.buf, p.w, p.h, p.linear, key.mips).also { byContent[key] = it }
    }

    /** A picture ready for the GPU: its bytes as Filament takes them and, for one never updated, its content's key. */
    class Prepared internal constructor(internal val buf: ByteBuffer, val w: Int, val h: Int, val linear: Boolean, internal val key: ContentKey?)


    /** The [n] sizes holding the most texels: "w x h x count = M texels". */
    fun biggest(n: Int = 8): String = bySize.entries.sortedByDescending { e -> e.key.split("x").let { it[0].toLong() * it[1].toLong() } * e.value }.take(n)
        .joinToString(", ") { e -> val (w, h) = e.key.split("x").map { it.toLong() }; "${e.key} x${e.value} = %.1fM".format(java.util.Locale.US, w * h * e.value / 1e6) }

    private fun build(buf: ByteBuffer, w: Int, h: Int, linear: Boolean, mips: Boolean): Texture {
        madeCount++; texels += w.toLong() * h
        bySize.merge("${w}x$h", 1, Int::plus)
        val format = if (srgb && !linear) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8
        // (a texture whose mipmaps the engine makes must say so, or Filament aborts the process)
        val levels = if (mips && Texture.isTextureFormatMipmappable(engine, format)) 32 - Integer.numberOfLeadingZeros(maxOf(w, h)) else 1
        val t = Texture.Builder().width(w).height(h).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(format)
            .usage(if (levels > 1) Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE else Texture.Usage.DEFAULT).build(engine)
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        if (levels > 1) t.generateMipmaps(engine)
        made += t
        var bytes = 0L
        for (l in 0 until levels) bytes += maxOf(1, w shr l).toLong() * maxOf(1, h shr l) * 4
        gpu.textures.addAndGet(bytes)
        return t
    }

    /** Replaces the pixels of [t] (made here, same size). */
    fun update(t: Texture, px: IntArray) {
        val buf = pack(px, t.getWidth(0), t.getHeight(0))
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
    }


    /** Replaces the pixels of [t] (made here, same size) with [buf], already as Filament takes them ([offset]). */
    fun update(t: Texture, buf: ByteBuffer) {
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
    }

    fun solid(argb: Int): Texture = make(intArrayOf(argb), 1, 1, linear = true)

    /** Frees what was made from the street's pictures; [all] also the plain white and black. */
    fun destroy(all: Boolean = false) {
        for (t in made) if (all || (t !== white && t !== black)) engine.destroyTexture(t)
        made.clear(); byImage.clear(); warmed.clear(); byContent.clear()
        gpu.textures.set(0L)
        if (!all) { made += white; made += black; gpu.textures.set(8L) }
    }

    companion object {
        /**
         * Packs [px] for [make] (any thread: no engine call): [mips] for a picture never updated, which is then shared by
         * its content.
         */
        fun prepare(px: IntArray, w: Int, h: Int, linear: Boolean = false, mips: Boolean = false): Prepared =
            Prepared(pack(px, w, h), w, h, linear, if (mips) ContentKey(w, h, linear, px.contentHashCode(), hash64(px)) else null)

        /**
         * [px] packed for [make] as a picture never updated, shared by its content, but without mipmaps: for one only ever
         * sampled with a nearest filter (the props' and yards' glow masks), whose smaller levels nothing would read.
         */
        fun prepareFlat(px: IntArray, w: Int, h: Int, linear: Boolean): Prepared =
            Prepared(pack(px, w, h), w, h, linear, ContentKey(w, h, linear, px.contentHashCode(), hash64(px), mips = false))

        /**
         * A blank (transparent black) picture of [w] x [h] for [make], never updated through [prepare]'s path: its bytes
         * are written in place ([offset]), with no int array of the pixels on the Java heap.
         */
        fun blank(w: Int, h: Int, linear: Boolean = false): Prepared =
            Prepared(ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()), w, h, linear, null)

        /** Where pixel [i] of a [w] x [h] picture (rows top first, as ARGB arrays run) starts in a buffer Filament takes. */
        fun offset(i: Int, w: Int, h: Int): Int { val y = i / w; return ((h - 1 - y) * w + (i - y * w)) * 4 }

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
        private fun pack(px: IntArray, w: Int, h: Int): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            for (y in h - 1 downTo 0) for (x in 0 until w) {
                val c = px[y * w + x]
                buf.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
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
