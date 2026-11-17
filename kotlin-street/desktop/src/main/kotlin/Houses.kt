import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteArt
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Image as SkiaImage

/*
 * The preview's houses mode (-Dmode=houses): our pitched-roof houses as the street draws them, beside the sprites they
 * were built from. Run it on the app's own street (-Dstreet=1 is the cross street, where the homes are) or on a showcase
 * of every house sprite (python3 tools/make_buildings.py --showcase DIR, then -Dassets=DIR -Dstreet=0 for every design,
 * -Dstreet=1 for every colour of one). Writes:
 *  - houses_sheet.png: each building of the street, its sprite on the left, then the engine's render of it at the left of
 *    the view, in the middle and at the right (its side faces coming round), at one in the afternoon;
 *  - houses_<day|dusk|night|snow>_N.png: the street at those hours (and in January's snow), a few frames along it.
 */
fun renderHouses(out: String, publicAssets: String) {
    if (System.getProperty("street") == null) System.setProperty("street", "0")
    val shops = streetOfShops()
    val first = shops.first().id
    GameState.enabled = true; GameState.look = 1; GameState.lane = 7f; GameState.dir = 1f
    GameState.keepers = emptyList()
    val w = 780; val h = 360
    // one frame to lay the street out
    GameState.x = 0f
    renderImage(w, h, 13f, "2026-06-14", first, false, bottom = 40f, ambient = 100f)
    val centers = GameState.centers
    if (centers.isEmpty()) { println("houses: the street did not lay out"); return }
    val unit = GameState.unit
    val ground = GameState.groundPx
    val k = SpriteArt.PIXEL * unit                 // device pixels per sprite pixel
    val viewW = w * 2f / unit                       // the view's width in street units
    // the sprites the street's buildings were built from (street.json's parts), by building id
    val dir = assetsDir()
    val root = org.json.JSONObject(File(dir, "street.json").readText())
    val arr = root.getJSONArray("shops")
    val partOf = (0 until arr.length()).associate { i -> val o = arr.getJSONObject(i); o.getString("id") to o.getJSONArray("parts").getJSONObject(0).getString("key") }
    fun original(key: String): SkiaImage? = File(publicAssets).listFiles()?.firstNotNullOfOrNull { d -> File(d, "$key.png").takeIf { it.exists() } }
        ?.let { SkiaImage.makeFromEncoded(it.readBytes()) }

    // --- the sheet: sprite | left | middle | right
    val cellW = ((SpriteArt.SRC_W + 120f) * k).toInt(); val cellH = (300f * k).toInt()
    val n = shops.size
    val sheet = Surface.makeRasterN32Premul(cellW * 4, cellH * n)
    val c = sheet.canvas
    c.clear(0xFF202028.toInt())
    val nearest = FilterMipmap(FilterMode.NEAREST, MipmapMode.NONE)
    for ((col, off) in listOf(1 to .32f, 2 to 0f, 3 to -.32f)) {
        for (i in 0 until n) {
            if (i >= centers.size) break
            GameState.x = centers[i] + off * viewW
            val img = renderImage(w, h, 13f, "2026-06-14", first, false, bottom = 40f, ambient = 100f)
            // where the building stands in this frame
            val sx = img.width / 2f + (centers[i] - GameState.viewMiddle) * unit
            val src = Rect.makeXYWH(sx - cellW / 2f, GameState.groundPx - cellH + 30f * k, cellW.toFloat(), cellH.toFloat())
            c.drawImageRect(img, src, Rect.makeXYWH(col * cellW.toFloat(), i * cellH.toFloat(), cellW.toFloat(), cellH.toFloat()), nearest, Paint(), true)
        }
    }
    for (i in 0 until n) {
        val key = partOf[shops[i].id] ?: continue
        val o = original(key) ?: continue
        val dw = o.width * k; val dh = o.height * k
        c.drawImageRect(o, Rect.makeWH(o.width.toFloat(), o.height.toFloat()),
            Rect.makeXYWH((cellW - dw) / 2f, i * cellH + cellH - 30f * k - dh, dw, dh), nearest, Paint(), true)
    }
    File(out).mkdirs()
    File(out, "houses_sheet.png").writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    println("wrote $out/houses_sheet.png (${n} buildings)")

    // --- the street at the hours that matter to a house: day, dusk, night, snow
    val day = System.getProperty("day") ?: "2026-06-14"
    val span = (centers.last() - centers.first()).coerceAtLeast(1f)
    val frames = ((span / (viewW * .8f)).toInt() + 1).coerceIn(1, 6)
    for ((tag, hour, date) in listOf(Triple("day", 13f, day), Triple("dusk", 19.2f, day), Triple("night", 22.3f, day), Triple("snow", 13f, "2026-01-20"))) {
        for (f in 0 until frames) {
            GameState.x = centers.first() + viewW * .3f + f * viewW * .8f
            render("$out/houses_${tag}_$f.png", w, h, hour, date, first, false, bottom = 40f, ambient = if (hour > 20f) 160f else if (hour > 18f) 130f else 100f)
        }
    }
    println("ground $ground unit $unit")
}
