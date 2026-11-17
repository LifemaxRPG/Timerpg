@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import com.lifemax.rpg.feature.habits.presentation.scene.CornerTurn
import com.lifemax.rpg.feature.habits.presentation.scene.TurnArt
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/** Renders frames of the faked corner turn (shared/scene/CornerTurn.kt) to PNG files. */
fun renderTurn(out: String, assets: String) {
    fun load(dir: String, name: String): ImageBitmap = SkiaImage.makeFromEncoded(File(File(assets, dir), name).readBytes()).toComposeImageBitmap()
    val art = TurnArt(
        (0..8).map { load("buildings", "b${it}_front.png") }, (0..8).map { load("buildings", "b${it}_side.png") },
        try { load("props", "lamp_1.png") } catch (e: Exception) { null },
    )
    File(out).mkdirs()
    val frames = 16
    for (i in 0 until frames) {
        val sc = ImageComposeScene(width = 960, height = 480, density = Density(1f)) { CornerTurn(art, i / (frames - 1f), Modifier.fillMaxSize()) }
        val img = sc.render(0L)
        File(out, "turn_%02d.png".format(i)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        sc.close()
        println("turn frame $i")
    }
}
