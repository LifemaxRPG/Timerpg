import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.Astc
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentModels
import com.lifemax.rpg.feature.habits.presentation.scene3d.StreetTextures
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * The night masks kept in ASTC 4x4 (MEMORY_AUDIT.md, G4: the farmhouses' masks, 85.6 MB of RGBA8 before): every model
 * whose mask the town makes ASTC decodes, at any mix of its lights on and off, to exactly the pixels the RGBA8 mask held,
 * so the windows and signs light as before. The farms' models and the capital's own.
 */
class NightMaskAstcTest {
    private val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }

    private fun open(f: File) = f.takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }

    @Test fun astcMasksDecodeToTheSamePixelsAtEveryHour() {
        Filament.init()
        val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(TownEngine.config()).build()
        val textures = StreetTextures(engine, srgb = true)
        assertTrue("this GL reads ASTC", StreetTextures.useAstc)
        val rnd = java.util.Random(7)
        var astc = 0; var checked = 0
        for (dir in listOf(File(assets, "farm/buildings"), File(assets, "town/gamma/buildings"))) {
            val shapes = ModelShape.parseAll(File(dir, "models.json").takeIf { it.exists() }?.readText())
            for ((key, shape) in shapes) {
                val atlas = open(File(dir, "${key}_atlas.png")) ?: continue
                val glow = open(File(dir, "${key}_glow.png")) ?: continue
                val night = FilamentModels.Night(ModelArt(shape, atlas, glow, null), key.hashCode(), textures, linear = true)
                checked++
                if (!night.astc) continue
                astc++
                val w = atlas.width; val h = atlas.height
                repeat(6) { round ->
                    val now = BooleanArray(night.lights) { if (round == 0) false else if (round == 1) true else rnd.nextBoolean() }
                    val px = night.maskPixels(now)
                    // the pixels the same as the mask the town made before (RGBA8, or RG8: blue 0 and alpha opaque there)
                    val old = night.packed(now); val st = night.maskBytes
                    for (i in 0 until w * h) {
                        val o = StreetTextures.offset(i, w, h, st)
                        val c = px[i]
                        val same = (old.get(o).toInt() and 255) == (c shr 16 and 255) && (old.get(o + 1).toInt() and 255) == (c shr 8 and 255) &&
                            if (st == 4) (old.get(o + 2).toInt() and 255) == (c and 255) && (old.get(o + 3).toInt() and 255) == (c ushr 24) else c and 255 == 0 && c ushr 24 == 255
                        if (!same) throw AssertionError("$key: pixel $i differs from the RGBA8 mask")
                    }
                    val buf = night.encode(px)
                    // every block decoded against the pixels it was made of (blocks from the picture's last row up)
                    val b = ByteArray(16); val out = IntArray(16); val want = IntArray(16)
                    val bw = (w + 3) / 4; val bh = (h + 3) / 4
                    for (by in 0 until bh) for (bx in 0 until bw) {
                        buf.position((by * bw + bx) * 16); buf.get(b)
                        for (j in 0 until 16) want[j] = px[(h - 1 - minOf(by * 4 + (j shr 2), h - 1)) * w + minOf(bx * 4 + (j and 3), w - 1)]
                        assertTrue("$key: block $bx,$by decodes", Astc.decodeBlock(b, out))
                        assertArrayEquals("$key: block $bx,$by at round $round", want, out)
                    }
                }
            }
        }
        textures.destroy(all = true)
        engine.destroy()
        println("night masks: $checked models with a glow picture, $astc of them in ASTC")
        assertTrue("some masks are ASTC ($astc of $checked)", astc > 0)
    }
}
