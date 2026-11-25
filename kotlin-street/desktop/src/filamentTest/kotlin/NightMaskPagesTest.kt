import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.lifemax.rpg.feature.habits.domain.ModelShape
import com.lifemax.rpg.feature.habits.presentation.scene.ModelArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentModels
import com.lifemax.rpg.feature.habits.presentation.scene3d.StreetTextures
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * The night masks kept as window pages (MEMORY_AUDIT.md, G3, "Step 4 as built"): for every model of the capital and the
 * farms whose mask is paged, at any mix of its lights on and off, every texel of the atlas read the way street_lit.mat
 * reads the pages (the table's entry for its tile, then the texel in that page) holds exactly the bytes the atlas-sized
 * mask held there, so every window, pane of glass and sign lights as before. Also prints what the pages save.
 */
class NightMaskPagesTest {
    private val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }

    private fun open(f: File) = f.takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }

    @Test fun pagedMasksReadAsTheWholeMaskAtEveryHour() {
        Filament.init()
        val engine = Engine.Builder().backend(Engine.Backend.OPENGL).config(TownEngine.config()).build()
        val textures = StreetTextures(engine, srgb = true)
        val rnd = java.util.Random(11)
        var paged = 0; var checked = 0; var wholeBytes = 0L; var pagedBytes = 0L
        for (dir in listOf(File(assets, "farm/buildings"), File(assets, "town/gamma/buildings"))) {
            val shapes = ModelShape.parseAll(File(dir, "models.json").takeIf { it.exists() }?.readText())
            for ((key, shape) in shapes) {
                val atlas = open(File(dir, "${key}_atlas.png")) ?: continue
                val glow = open(File(dir, "${key}_glow.png")) ?: continue
                val night = FilamentModels.Night(ModelArt(shape, atlas, glow, null), key.hashCode(), textures, linear = true)
                checked++
                val pg = night.pages ?: continue
                paged++
                val w = atlas.width; val h = atlas.height; val st = night.maskBytes
                wholeBytes += w.toLong() * h * st; pagedBytes += pg.pw.toLong() * pg.ph * st
                repeat(5) { round ->
                    val now = BooleanArray(night.lights) { if (round == 0) false else if (round == 1) true else rnd.nextBoolean() }
                    val whole = night.packed(now, whole = true)
                    val pages = night.packed(now)
                    assertTrue("$key: paged buffer size", pages.capacity() >= pg.pw * pg.ph * st)
                    for (y in 0 until h) for (x in 0 until w) {
                        // (as the shader does: the tile's entry at the texture's start, then the texel in its page)
                        val e = (y shr 3) * pg.tw + (x shr 3)
                        val px = pages.get(e * st).toInt() and 255; val py = pages.get(e * st + 1).toInt() and 255
                        val t = ((py + pg.row0) * 8 + (y and 7)) * pg.pw + px * 8 + (x and 7)
                        val o = (y * w + x) * st
                        for (c in 0 until st) if (whole.get(o + c) != pages.get(t * st + c))
                            throw AssertionError("$key: texel $x,$y channel $c at round $round: ${whole.get(o + c)} paged ${pages.get(t * st + c)}")
                    }
                }
            }
        }
        textures.destroy(all = true)
        engine.destroy()
        println("night masks: %d models with a glow picture, %d paged: %.1f MB whole -> %.1f MB paged".format(java.util.Locale.US,
            checked, paged, wholeBytes / 1048576.0, pagedBytes / 1048576.0))
        assertTrue("some masks are paged ($paged of $checked)", paged > 0)
    }
}
