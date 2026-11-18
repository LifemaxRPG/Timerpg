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
     * Pictures never updated ([mips]) by their content: the same pixels make one texture however many batches or
     * buildings wear them (repeated sprites, yard kit pictures baked lot by lot, windows' glass of repeated fronts).
     */
    private val byContent = HashMap<ContentKey, Texture>()
    internal data class ContentKey(val w: Int, val h: Int, val linear: Boolean, val a: Int, val b: Long)

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
        return build(p.buf, p.w, p.h, p.linear, true).also { byContent[key] = it }
    }

    /** A picture ready for the GPU: its bytes as Filament takes them and, for one never updated, its content's key. */
    class Prepared internal constructor(internal val buf: ByteBuffer, val w: Int, val h: Int, val linear: Boolean, internal val key: ContentKey?)

    private fun build(buf: ByteBuffer, w: Int, h: Int, linear: Boolean, mips: Boolean): Texture {
        madeCount++; texels += w.toLong() * h
        val format = if (srgb && !linear) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8
        // (a texture whose mipmaps the engine makes must say so, or Filament aborts the process)
        val levels = if (mips && Texture.isTextureFormatMipmappable(engine, format)) 32 - Integer.numberOfLeadingZeros(maxOf(w, h)) else 1
        val t = Texture.Builder().width(w).height(h).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(format)
            .usage(if (levels > 1) Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE else Texture.Usage.DEFAULT).build(engine)
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        if (levels > 1) t.generateMipmaps(engine)
        made += t
        return t
    }

    /** Replaces the pixels of [t] (made here, same size). */
    fun update(t: Texture, px: IntArray) {
        val buf = pack(px, t.getWidth(0), t.getHeight(0))
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
    }


    fun solid(argb: Int): Texture = make(intArrayOf(argb), 1, 1, linear = true)

    /** Frees what was made from the street's pictures; [all] also the plain white and black. */
    fun destroy(all: Boolean = false) {
        for (t in made) if (all || (t !== white && t !== black)) engine.destroyTexture(t)
        made.clear(); byImage.clear(); warmed.clear(); byContent.clear()
        if (!all) { made += white; made += black }
    }

    companion object {
        /**
         * Packs [px] for [make] (any thread: no engine call): [mips] for a picture never updated, which is then shared by
         * its content.
         */
        fun prepare(px: IntArray, w: Int, h: Int, linear: Boolean = false, mips: Boolean = false): Prepared =
            Prepared(pack(px, w, h), w, h, linear, if (mips) ContentKey(w, h, linear, px.contentHashCode(), hash64(px)) else null)

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
