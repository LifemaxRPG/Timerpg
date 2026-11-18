package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap

/**
 * The street's pictures as Filament textures, made once from the same ImageBitmaps the classic street draws (read with
 * Compose's own readPixels, so the same code serves the phone and the desktop). [srgb] for the lit materials (the
 * pictures' colours are sRGB; lighting wants them linear); plain RGBA8 for the exact albedo comparison.
 */
internal class StreetTextures(private val engine: Engine, private val srgb: Boolean) {
    private val byImage = IdentityHashMap<ImageBitmap, Texture>()
    private val made = ArrayList<Texture>()
    val white: Texture = solid(0xFFFFFFFF.toInt())
    val black: Texture = solid(0x00000000)

    /** The texture of [img] as drawn. */
    fun of(img: ImageBitmap): Texture = byImage.getOrPut(img) { make(argb(img), img.width, img.height, mips = true) }

    /** A texture from ARGB pixels (rows top first), owned here; [mips]: with its mipmaps (for a picture never updated). */
    fun make(px: IntArray, w: Int, h: Int, linear: Boolean = false, mips: Boolean = false): Texture {
        val buf = pack(px, w, h)
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

    fun solid(argb: Int): Texture = make(intArrayOf(argb), 1, 1, linear = true)

    /** Frees what was made from the street's pictures; [all] also the plain white and black. */
    fun destroy(all: Boolean = false) {
        for (t in made) if (all || (t !== white && t !== black)) engine.destroyTexture(t)
        made.clear(); byImage.clear()
        if (!all) { made += white; made += black }
    }

    companion object {
        fun argb(img: ImageBitmap): IntArray {
            val px = IntArray(img.width * img.height)
            img.readPixels(px, 0, 0, img.width, img.height)
            return px
        }
    }
}
