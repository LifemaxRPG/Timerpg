import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.CornerWalk
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuilding
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteBuildingLoad
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalk
import com.lifemax.rpg.feature.habits.presentation.scene.skyColors
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.PlaceholderYards
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownArt
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownFrame
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownPose
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.skia.Image as SkiaImage

/**
 * The whole town in Filament (-Dmode=filament-town): the town of build-town/gamma (or -Dtown=DIR: a folder with
 * street.json v2 and buildings/, as tools/build-town.sh writes it) built as the game would build it (the ground at once,
 * then the buildings nearest the spawn first in 4 ms batches), seen from:
 *  - aerial_<hour>: the whole town from high up, at 13:00 and at dusk;
 *  - civic_<hour>: lower, over the civic avenue and the plaza;
 *  - street_<where>_<hour>: the street view (the true camera following the walk) at the spawn (8L), on Main Street, on
 *    a Heights street and a Woodside street, and halfway through a corner turn; each at 13:00 and at night;
 *  - behind_<where>: low over the backs of a few rows (the world is built whole, for an open-world camera later);
 *  - yard_<which>_<view>: three yards (the Heights pool, a Woodside lot, a garden) from the street (13:00 and night),
 *    from above and from low behind.
 * -DtownYards=placeholder draws the yards as flat colours (the layout alone) instead of the yard kit.
 * -Dshots=name,name renders only those; -Dw= the width (1600; the street views 2340 x 1080). Writes build/preview/filament-town/
 * and town.txt (build times, draw counts per view).
 */
fun renderFilamentTown(out: String) {
    val dir = File(out, "filament-town").also { it.mkdirs() }
    val townDir = System.getProperty("town")?.let { File(it) } ?: File("../build-town/gamma")
    val assets = File(System.getProperty("assetsRoot") ?: "../../public/assets")
    val json = File(townDir, "street.json").readText()
    val town = Town.parse(json) ?: error("$townDir/street.json has no graph (a v1 file): run tools/build-town.sh gamma")
    val yards = TownYards.parse(json)
    val root = org.json.JSONObject(json)
    val bdir = File(townDir, "buildings")
    val windows = SpriteBuildingLoad.windows(File(bdir, "windows.json").takeIf { it.exists() }?.readText())
    val houses = SpriteBuildingLoad.houses(File(bdir, "houses.json").takeIf { it.exists() }?.readText())
    val models = SpriteBuildingLoad.models(File(bdir, "models.json").takeIf { it.exists() }?.readText())
    val mids = HashMap<String, Int>()
    root.optJSONArray("shops")?.let { a ->
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val parts = o.optJSONArray("parts")
            mids[o.getString("sprite")] = if (parts == null) 0 else (0 until parts.length()).count { parts.getJSONObject(it).getString("key").startsWith("middle_") }
        }
    }
    fun open(f: File) = f.takeIf { it.exists() }?.let { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
    val loadMs = System.nanoTime()
    val art = TownArt { path -> open(File(assets, path)) }
    val building: (String) -> SpriteBuilding? = { sp -> SpriteBuildingLoad.load(sp, mids[sp] ?: 0, windows, houses, models) { open(File(bdir, "$it.png")) } }

    Filament.init()
    val engine = Engine.create(Engine.Backend.OPENGL)
    val mats = File("../app/src/main/assets/filament")
    val ft = FilamentTown(engine, { n -> File(mats, "$n.filamat").readBytes() }, town, yards, art, building)
    if (System.getProperty("townYards") == "placeholder") ft.yardBuilder = PlaceholderYards
    val report = StringBuilder()
    report.appendLine("town ${town.seed} ${town.year}: ${town.rows.size} rows, ${ft.slots.size} buildings, ${town.roads.size} roads, ${town.nodes.size} nodes, ${town.props.size} edge props, ${yards.yards.size} yards")

    // the walk, standing at the spawn
    GameState.moveX = 0f; GameState.moveLane = 0f
    val walk = TownWalk(town)
    // the ground as the game makes it: the workers make it ready, the engine's thread uploads it in 4 ms steps
    ft.startGround()
    var groundFrames = 0
    while (!ft.uploadGround(4f)) { groundFrames++; Thread.sleep(1) }
    report.appendLine("ground, greenery, furniture, props, yards: %.0f ms from start to all in; the workers %.0f ms; the engine's thread %.0f ms of uploads over %d frames (4 ms budget; longest step %.1f ms)"
        .format(ft.groundMs, ft.groundPrepareMs, ft.groundUploadMs, groundFrames, ft.groundUploadWorst))
    run {
        // the yards' triangles by kind (where a view's triangles go)
        val all = com.lifemax.rpg.feature.habits.presentation.scene3d.YardData.yards(json).flatMap { it.items } +
            com.lifemax.rpg.feature.habits.presentation.scene3d.YardData.laneItems(json)
        val tris = com.lifemax.rpg.feature.habits.presentation.scene3d.YardKit.bake(all).groupBy { it.group }
            .mapValues { (_, b) -> b.sumOf { it.mesh.idx.size / 3 } }.entries.sortedByDescending { it.value }
        report.appendLine("yards: ${all.size} items, ${tris.sumOf { it.value }} triangles; most: " + tris.take(10).joinToString { "${it.key} ${it.value}" })
    }
    // the buildings as the game builds them: nearest the player first, a 4 ms budget a frame
    var frames = 0
    // (a frame whose art is not decoded yet builds nothing: as the game, it just draws on)
    while (ft.pending > 0) { if (ft.buildNearest(walk.wx, walk.wz, 4f) > 0) frames++ else Thread.sleep(1) }
    val missing = ft.slots.count { it.built == null }
    report.appendLine("buildings: %d built in %d batches (4 ms budget, at least one each), %.0f ms in all (largest batch %.1f ms; slowest building %s %.1f ms, its art decoded ahead on a worker); %d missing art"
        .format(ft.slots.size - missing, frames, ft.buildingsMs, ft.batches.maxOf { it.second }, ft.slowest.first, ft.slowest.second, missing))
    report.appendLine("(software GL on the build server, not the S25; preparing the art ${"%.0f".format((System.nanoTime() - loadMs) / 1e6)} ms in all)")

    ft.textureStats.let { (n, texels, shared) ->
        report.appendLine("textures: %d made, %.1fM texels (%.0f MB RGBA before mips); %d asks shared one already made (same pixels)".format(n, texels / 1e6, texels * 4 / 1e6, shared))
    }
    val only = System.getProperty("shots")?.split(",")?.map { it.trim() }?.toSet()
    val night = 22.5f; val dusk = 19.3f
    fun want(name: String) = only == null || name in only || only.any { name.startsWith(it) }

    // the street views: 2340 x 1080 as the phone; the aerials 1600 x 1000
    val sw = 2340; val shh = 1080
    val aw = System.getProperty("w")?.toIntOrNull() ?: 1600; val ah = aw * 5 / 8
    val shooter = Shooter(engine, ft)
    fun shoot(name: String, pose: TownPose, hour: Float) {
        if (!want(name)) return
        val t0 = System.nanoTime()
        ft.prepare(pose)
        ft.frame(pose, hour)
        shooter.save(File(dir, "$name.png"), pose.w.toInt(), pose.h.toInt(), hour)
        val s = ft.stats
        report.appendLine("%-28s %5.0f ms  buildings in view %3d of %d, renderables %4d, draws %5d, triangles %7d, shadow casters %4d, chunks %3d (%d in 3D), lamps lit %d".format(
            name, (System.nanoTime() - t0) / 1e6, s.visible, s.built, s.renderables, s.draws, s.triangles, s.casters, s.chunks, s.chunksNear, s.lamps))
        println("  $name")
    }

    // a free camera never stands inside a building: backed off along its look until its eye is clear of every one
    fun aerial(tx: Float, tz: Float, yaw: Float, pitch: Float, dist: Float, fov: Float): TownPose {
        var d = dist
        while (true) {
            val p = TownFrame.aerial(tx, tz, yaw, pitch, d, fov, aw.toFloat(), ah.toFloat())
            val inside = ft.slots.any { sl -> sl.isBuilt && kotlin.math.sqrt((p.ex - sl.cx) * (p.ex - sl.cx) + (p.ey - sl.cy) * (p.ey - sl.cy) + (p.ez - sl.cz) * (p.ez - sl.cz)) < sl.r + p.near }
            if (!inside || d > dist * 3f) return p
            d += 40f
        }
    }

    // --- aerials
    val tb = run {
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (r in town.roads) for (p in r.pts) { x0 = minOf(x0, p.x); z0 = minOf(z0, p.z); x1 = maxOf(x1, p.x); z1 = maxOf(z1, p.z) }
        floatArrayOf(x0, z0, x1, z1)
    }
    val mx = (tb[0] + tb[2]) / 2f; val mz = (tb[1] + tb[3]) / 2f
    shoot("aerial_13h", TownFrame.aerial(mx, mz, 0f, 52f, 13000f, 45f, aw.toFloat(), ah.toFloat()), 13f)
    shoot("aerial_dusk", TownFrame.aerial(mx, mz, 0f, 52f, 13000f, 45f, aw.toFloat(), ah.toFloat()), dusk)
    shoot("aerial_top", TownFrame.aerial(mx, mz, 0f, 89f, 11500f, 45f, aw.toFloat(), ah.toFloat()), 13f)
    val plaza = town.plaza
    val civic = town.road(town.civicRoad ?: 8)
    val cx = civic.pts.first().x
    val cz = plaza?.let { (it[1] + it[3]) / 2f } ?: civic.pts.first().z
    shoot("civic_13h", TownFrame.aerial(cx - 150f, cz - 700f, 200f, 34f, 2300f, 45f, aw.toFloat(), ah.toFloat()), 13f)
    shoot("civic_dusk", TownFrame.aerial(cx - 150f, cz - 700f, 200f, 34f, 2300f, 45f, aw.toFloat(), ah.toFloat()), dusk)

    // --- the street views
    fun majority(hood: String): Row = town.rows.maxByOrNull { r -> r.buildings.count { it.neighbourhood == hood } * 10 + r.buildings.size }!!
    fun stand(r: Row, x: Float) { CornerWalk.reset(); walk.placeOn(r, x, 7f) }
    val main = town.rows.filter { town.road(it.road).name == "Main Street" }.maxByOrNull { it.buildings.size }!!
    val places = listOf(
        "spawn" to (town.row(town.spawn.row) to town.spawn.x),
        "main" to (main to main.buildings.sortedBy { it.mid }.let { it[it.size / 2].mid }),
        "heights" to (majority("heights").let { r -> r to r.buildings.sortedBy { it.mid }.let { it[it.size / 2].mid } }),
        "woodside" to (majority("woodside").let { r -> r to r.buildings.sortedBy { it.mid }.let { it[it.size / 2].mid } }),
    )
    for ((name, at) in places) {
        val (r, x) = at
        stand(r, x)
        report.appendLine("street_$name: row ${r.id} (${town.road(r.road).name}) at x %.0f".format(x))
        val pose = TownFrame.street(walk, sw.toFloat(), shh.toFloat())
        shoot("street_${name}_13h", pose, 13f)
        shoot("street_${name}_night", pose, night)
        // from behind its buildings and above (the world is whole from every side)
        val n = r.normalAt(x)
        val p = r.pointAt(x, 0f)
        val yaw = Math.toDegrees(kotlin.math.atan2((-n.x).toDouble(), n.z.toDouble())).toFloat()
        shoot("behind_${name}", aerial(p.x + n.x * 120f, p.z + n.z * 120f, yaw, 24f, 900f, 50f), 13f)
    }
    // halfway round a corner: from the spawn's row into the side street at its end (or start)
    run {
        val r = town.rows.firstOrNull { it.id == town.spawn.row && it.end.turnIn != null } ?: town.rows.first { it.end.turnIn != null && it.buildings.isNotEmpty() }
        stand(r, r.length - 45f)
        GameState.moveX = 0f; GameState.moveLane = 0f
        walk.step(1f / 60f)
        GameState.moveLane = -1f
        var guard = 0
        while (guard++ < 4000) {
            walk.step(1f / 60f)
            val t = walk.transit ?: break
            if (t.progress >= .5f) break
        }
        report.appendLine("street_turn: ${r.id} -> ${walk.transit?.to?.id} at progress %.2f".format(walk.transit?.progress ?: -1f))
        val pose = TownFrame.street(walk, sw.toFloat(), shh.toFloat())
        shoot("street_turn_13h", pose, 13f)
        shoot("street_turn_night", pose, night)
        GameState.moveLane = 0f
        CornerWalk.reset()
    }
    // --- three yards close up: from the street, from above, from low behind (backyards are seen by an open-world camera)
    for ((name, sprite) in listOf("pool" to "b69", "woodside" to "b45", "garden" to "b79")) {
        val y = yards.of(sprite) ?: continue
        val r = town.rows.firstOrNull { row -> row.buildings.any { it.sprite == sprite } } ?: continue
        val b = r.buildings.first { it.sprite == sprite }
        stand(r, b.mid)
        report.appendLine("yard_$name: $sprite ${y.lot} (${y.neighbourhood}, ${y.style}), row ${r.id}, ${y.items.size} items")
        val pose = TownFrame.street(walk, sw.toFloat(), shh.toFloat())
        shoot("yard_${name}_street_13h", pose, 13f)
        shoot("yard_${name}_street_night", pose, night)
        val p = y.parcel
        val px = (p[0] + p[2]) / 2f; val pz = (p[1] + p[3]) / 2f
        val n = r.normalAt(b.mid)
        // from beyond its back fence: high (looking down onto the parcel), straight down, and low (at the house's back)
        val yaw = Math.toDegrees(kotlin.math.atan2((-n.x).toDouble(), n.z.toDouble())).toFloat()
        shoot("yard_${name}_above", TownFrame.aerial(px, pz, yaw, 62f, 1050f, 50f, aw.toFloat(), ah.toFloat()), 13f)
        shoot("yard_${name}_top", TownFrame.aerial(px, pz, yaw, 89f, 1100f, 50f, aw.toFloat(), ah.toFloat()), 13f)
        shoot("yard_${name}_back", aerial(px, pz, yaw, 20f, 760f, 55f), 13f)
        shoot("yard_${name}_back_dusk", aerial(px, pz, yaw, 20f, 760f, 55f), dusk)
    }
    // --- the landmarks' grounds: each landmark from low above its front (the paved path and apron)
    for (sp in listOf("b92", "b96", "b97")) {
        val r = town.rows.firstOrNull { row -> row.buildings.any { it.sprite == sp } } ?: continue
        val b = r.buildings.first { it.sprite == sp }
        val p = r.pointAt(b.mid, 0f); val n = r.normalAt(b.mid)
        // looking at its front: from the road's side, into the lot
        val yaw = Math.toDegrees(kotlin.math.atan2(n.x.toDouble(), (-n.z).toDouble())).toFloat()
        shoot("landmark_${sp}", aerial(p.x + n.x * 60f, p.z + n.z * 60f, yaw, 30f, 700f, 50f), 13f)
    }
    // --- a Heights row's backyards and pools from above (behind its houses, looking down onto the yards)
    run {
        val r = majority("heights")
        val x = r.buildings.sortedBy { it.mid }.let { it[it.size / 2].mid }
        val p = r.pointAt(x, 0f); val n = r.normalAt(x)
        val yaw = Math.toDegrees(kotlin.math.atan2((-n.x).toDouble(), n.z.toDouble())).toFloat()
        shoot("heights_backyards_above", aerial(p.x + n.x * 200f, p.z + n.z * 200f, yaw, 50f, 1400f, 50f), 13f)
    }
    File(dir, "town.txt").writeText(report.toString())
    print(report)
    shooter.destroy()
    ft.destroy()
    engine.destroy()
}

/** Renders the town's view to a readable swap chain and saves it over the hour's sky. */
private class Shooter(val engine: Engine, val town: FilamentTown) {
    private val renderer = engine.createRenderer().also { it.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) } }
    private var swap: com.google.android.filament.SwapChain? = null
    private var sw = 0; private var sh = 0
    private var pixels: ByteBuffer? = null

    fun save(file: File, w: Int, h: Int, hour: Float) {
        if (swap == null || sw != w || sh != h) {
            swap?.let { engine.destroySwapChain(it) }
            swap = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE or SwapChainFlags.CONFIG_TRANSPARENT)
            sw = w; sh = h
            pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        val buf = pixels!!
        val done = AtomicBoolean(false)
        for (frame in 0..1) {
            if (renderer.beginFrame(swap!!, 0L)) {
                renderer.render(town.view)
                if (frame == 1) renderer.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, w, Executor { it.run() }, Runnable { done.set(true) }))
                renderer.endFrame()
            }
            engine.flushAndWait()
        }
        var guard = 0
        while (!done.get() && guard++ < 400) { engine.flushAndWait(); Thread.sleep(5) }
        val sky = skyColors(hour).sky
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            val t = y / (h - 1f) * (sky.size - 1)
            val i = t.toInt().coerceIn(0, maxOf(0, sky.size - 2))
            val sc = if (sky.size < 2) sky.firstOrNull() ?: Color.Gray else lerp(sky[i], sky[i + 1], (t - i).coerceIn(0f, 1f))
            val sr = (sc.red * 255).toInt(); val sg = (sc.green * 255).toInt(); val sb = (sc.blue * 255).toInt()
            for (x in 0 until w) {
                val k = ((h - 1 - y) * w + x) * 4
                val r = buf.get(k).toInt() and 255; val g = buf.get(k + 1).toInt() and 255; val b = buf.get(k + 2).toInt() and 255; val a = buf.get(k + 3).toInt() and 255
                fun ch(c: Int, s: Int) = (c + s * (255 - a) / 255).coerceAtMost(255)
                img.setRGB(x, y, (ch(r, sr) shl 16) or (ch(g, sg) shl 8) or ch(b, sb))
            }
        }
        javax.imageio.ImageIO.write(img, "png", file)
    }

    fun destroy() {
        swap?.let { engine.destroySwapChain(it) }
        engine.destroyRenderer(renderer)
    }
}
