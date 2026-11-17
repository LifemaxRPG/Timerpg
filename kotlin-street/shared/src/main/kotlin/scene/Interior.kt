package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The inside of a building, top-down in 3/4 view like our LimeZu furniture: the room our game logic planned and furnished
 * (src/town/interiors.ts and its Wave Function Collapse furnisher, exported by tools/export-interiors.ts as
 * interiors.json). The furniture is our sprites; the walls, windows, shadows and light are drawn as geometry, lit by the
 * hour (daylight through the back windows, lamps at night).
 */
class InteriorRoom(
    val id: String, val title: String, val use: String, val template: String,
    val width: Int, val height: Int, val floor: String,
    val walls: List<IRect>, val areas: List<Area>, val pieces: List<Piece>,
    val spawnX: Float, val spawnY: Float, val exitX: Float, val exitY: Float,
) {
    class Area(val name: String, val rect: IRect, val floor: String?)
    class Piece(val key: String, val x: Float, val y: Float, val w: Int, val h: Int, val foot: IRect, val solid: Boolean)

    val home get() = template == "home" || template == "apartment"

    /** The player's feet at ([x], [y]) would be in a wall or a piece of furniture (the same box as the game's feetHit). */
    fun blocked(x: Float, y: Float): Boolean {
        if (x - 7 < 0 || y - 8 < 0 || x + 7 > width || y > height) return true
        // the way out: the bottom wall has a doorway at the exit
        val inDoor = abs(x - exitX) < 26f && y > height - 24f
        return walls.any { (!inDoor || it.y < height - 24) && feet(x, y, it) } || pieces.any { it.solid && feet(x, y, it.foot) }
    }

    private fun feet(x: Float, y: Float, r: IRect) = x + 7 > r.x && x - 7 < r.x + r.w && y > r.y && y - 8 < r.y + r.h

    /** Where the person who lives or works here stands: in front of the counter, the desk, the sofa or the table. */
    fun keeperSpot(): Pair<Float, Float> {
        val order = if (home) listOf("sofa", "table", "stove") else listOf("counter", "desk", "iceDisplay", "treadmill", "piano", "table")
        // in a shop, the counter customers come to: the one nearest the way in
        for (k in order) for (p in pieces.filter { it.key == k }.sortedByDescending { it.foot.y }) {
            val x = p.foot.x + p.foot.w / 2f; val y = p.foot.y + p.foot.h + 20f
            if (!blocked(x, y) && !blocked(x - 8, y) && !blocked(x + 8, y)) return x to y
        }
        return width / 2f to height / 2f
    }

    companion object {
        /** interiors.json (tools/export-interiors.ts): every building's room by sprite name. */
        fun parseAll(json: String): Map<String, InteriorRoom> {
            val rooms = org.json.JSONObject(json).getJSONObject("rooms")
            fun rect(a: org.json.JSONArray) = IRect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            return rooms.keys().asSequence().associateWith { id ->
                val r = rooms.getJSONObject(id)
                val walls = r.getJSONArray("walls").let { a -> List(a.length()) { rect(a.getJSONArray(it)) } }
                val areas = r.getJSONArray("areas").let { a ->
                    List(a.length()) { i -> a.getJSONObject(i).let { o -> Area(o.getString("name"), rect(o.getJSONArray("rect")), o.optString("floor").takeIf { s -> s.isNotEmpty() && s != "null" }) } }
                }
                val pieces = r.getJSONArray("furniture").let { a ->
                    List(a.length()) { i ->
                        a.getJSONObject(i).let { o ->
                            val at = o.getJSONArray("at")
                            Piece(o.getString("key"), at.getDouble(0).toFloat(), at.getDouble(1).toFloat(), at.getInt(2), at.getInt(3), rect(o.getJSONArray("foot")), o.getBoolean("solid"))
                        }
                    }
                }
                val sp = r.getJSONArray("spawn"); val ex = r.getJSONArray("exit")
                InteriorRoom(id, r.getString("title"), r.getString("use"), r.getString("template"), r.getInt("width"), r.getInt("height"), r.getString("floor"),
                    walls, areas, pieces, sp.getDouble(0).toFloat(), sp.getDouble(1).toFloat(), ex.getDouble(0).toFloat(), ex.getDouble(1).toFloat())
            }
        }
    }
}

class IRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** The interior furniture and floor sprites by name (assets/interior/<name>.png). */
object InteriorArt {
    var images: Map<String, ImageBitmap> = emptyMap()
}

/** Someone walking about a room: feet at ([x], [y]), facing [dir] (0 right, 1 up, 2 left, 3 down). */
class RoomWalker(var x: Float, var y: Float) {
    var dir = 1
    var walking = false
    /** Ground walked (room pixels). */
    var travel = 0f
    /**
     * The same legs as on the street, in room pixels (one is a sprite pixel, as on the street's figure), a little quicker
     * ([MoveTuning.roomPace]: rooms are short walks): the same starts, stops and cadence, the stride grown with the speed.
     */
    val motion = Locomotion(MoveTuning.roomPace / SpriteArt.PIXEL)

    /** One frame: the push [mx], [my] (-1..1, as the stick gives it; its strength sets the speed), [dt] seconds; slides along walls. */
    fun step(room: InteriorRoom, mx: Float, my: Float, dt: Float) {
        motion.drive(dt, mx, my)
        dir = Locomotion.roomFacing(dir, mx, my)
        val t = min(dt, MoveTuning.dtMax)
        val dx = motion.vx * t; val dy = motion.vy * t
        // in small steps, each axis on its own (so it slides along an edge instead of sticking); an axis that is blocked
        // stops dead, so sliding along a wall never carries a stored burst of speed out past its end
        val n = max(1, (max(abs(dx), abs(dy)) / 3f).toInt() + 1)
        val x0 = x; val y0 = y
        var hitX = false; var hitY = false
        repeat(n) {
            if (!room.blocked(x + dx / n, y)) x += dx / n else hitX = true
            if (!room.blocked(x, y + dy / n)) y += dy / n else hitY = true
        }
        if (hitX) motion.stopX()
        if (hitY) motion.stopY()
        val moved = hypot(x - x0, y - y0)
        walking = moved > 1e-3f
        travel += moved
        motion.advance(moved)
    }
}

/** How the room is lit at [hour] (0..24): daylight 0..1 and the sky colour in the windows. */
private fun daylight(hour: Float): Float {
    val d = sin(((hour - 6f) / 12f) * PI).toFloat()
    return d.coerceIn(0f, 1f)
}

private fun skyAt(hour: Float): Pair<Color, Color> {
    val day = daylight(hour)
    val dusk = (1f - abs(day - .25f) / .25f).coerceIn(0f, 1f) * (if (hour in 4f..21f) 1f else 0f)
    val top = lerp(lerp(Color(0xFF0B1630), Color(0xFF6FA8E8), day), Color(0xFF7A5A9A), dusk * .6f)
    val bottom = lerp(lerp(Color(0xFF1B2440), Color(0xFFBFE0F8), day), Color(0xFFF2A65A), dusk * .8f)
    return top to bottom
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

/** The wallpaper of a room by what it is (the template), and a darker wainscot under it. */
private fun wallColors(room: InteriorRoom): Pair<Color, Color> = when (room.template) {
    "home" -> Color(0xFFD9C7A6) to Color(0xFF8A6A4A)
    "apartment" -> Color(0xFFC9CDB8) to Color(0xFF6F7A64)
    "Bakery" -> Color(0xFFF0D9B5) to Color(0xFFB0744A)
    "Butcher" -> Color(0xFFE6ECEE) to Color(0xFF9A3B3B)
    "Ice Cream Parlor" -> Color(0xFFF6D3DE) to Color(0xFF6FB7B0)
    "Bait Shop" -> Color(0xFFA9C2B8) to Color(0xFF4F6B5E)
    "Music Store" -> Color(0xFF6E4E6E) to Color(0xFF3A2A3A)
    "Clothing Store" -> Color(0xFFE2DCCF) to Color(0xFF7D6E5A)
    "Dress Shop" -> Color(0xFFEAD1D6) to Color(0xFF9C6A78)
    "Toy Store" -> Color(0xFFF2E3A0) to Color(0xFF4F7FB8)
    "Gym" -> Color(0xFFB8BEC4) to Color(0xFF3F4650)
    "Bank" -> Color(0xFFD8D2C0) to Color(0xFF5A4A36)
    "Law Office" -> Color(0xFF9DAA9A) to Color(0xFF4A3828)
    "Doctor's Office" -> Color(0xFFDCEAEC) to Color(0xFF7FA7AE)
    else -> Color(0xFFCFC6B4) to Color(0xFF6E5E4C)
}

/**
 * The room [room] with [walker] in it at [hour]; [keeper], when set, is the sheet of the person who lives or works here,
 * standing at [InteriorRoom.keeperSpot]. The whole room fits the screen (rooms are one screen); the camera does not move.
 */
@Composable
fun InteriorStage(room: InteriorRoom, walker: RoomWalker, hour: Float, keeper: ImageBitmap?, modifier: Modifier = Modifier) {
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(room) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, .05f); last = now
            val feet = walker.motion.footfalls
            walker.step(room, GameState.moveX, GameState.moveLane, dt)
            // the game's footsteps follow the walk inside too: one for each foot put down, as on the street
            GameState.walking = walker.walking
            GameState.running = walker.motion.running
            GameState.footfalls += walker.motion.footfalls - feet
            frameNanos = now
        }
    }
    val spot = remember(room) { room.keeperSpot() }
    Canvas(modifier) {
        val clock = frameNanos / 1e9f
        drawRoom(room, walker, hour, keeper, spot, clock)
    }
}

/** The desktop preview's way in to [drawRoom]. */
object InteriorPainter {
    fun DrawScope.draw(room: InteriorRoom, walker: RoomWalker, hour: Float, keeper: ImageBitmap?, spot: Pair<Float, Float>, clock: Float) =
        drawRoom(room, walker, hour, keeper, spot, clock)
}

/** Draws [room] fitted into this scope. */
fun DrawScope.drawRoom(room: InteriorRoom, walker: RoomWalker, hour: Float, keeper: ImageBitmap?, spot: Pair<Float, Float>, clock: Float) {
    drawRect(Color(0xFF0C0E13))
    val s = min(size.width / room.width, size.height / room.height)
    val ox = (size.width - room.width * s) / 2f; val oy = (size.height - room.height * s) / 2f
    val day = daylight(hour)
    val W = room.width.toFloat(); val H = room.height.toFloat()
    withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
        // ---- the floor: the room's, then each area's own
        tile(room.floor, 32f, 96f, W - 64f, H - 120f)
        for (a in room.areas) a.floor?.let { tile(it, a.rect.x.toFloat(), a.rect.y.toFloat(), a.rect.w.toFloat(), a.rect.h.toFloat()) }
        // sunlight through the back windows lies on the floor (angled by the hour)
        val windows = windowXs(room)
        if (day > 0f) {
            val lean = ((hour - 12f) / 6f).coerceIn(-1.2f, 1.2f) * 70f
            val warm = lerp(Color(0xFFFFF4D6), Color(0xFFFFC27A), (1f - day).coerceIn(0f, 1f))
            for (wx in windows) {
                val p = Path().apply {
                    moveTo(wx - 22f, 96f); lineTo(wx + 22f, 96f)
                    lineTo(wx + 34f - lean, 96f + 150f); lineTo(wx - 34f - lean, 96f + 150f); close()
                }
                drawPath(p, Brush.verticalGradient(listOf(warm.copy(alpha = .30f * day), warm.copy(alpha = 0f)), 96f, 250f), blendMode = BlendMode.Screen)
            }
        }
        // contact shadow along the foot of the walls
        drawRect(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent), 96f, 118f), Offset(32f, 96f), Size(W - 64f, 22f))
        drawRect(Brush.horizontalGradient(listOf(Color(0x55000000), Color.Transparent), 32f, 46f), Offset(32f, 96f), Size(14f, H - 120f))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x55000000)), W - 46f, W - 32f), Offset(W - 46f, 96f), Size(14f, H - 120f))
        // ---- the back wall: a top edge, the papered face with its windows, a wainscot and a skirting board
        val (paper, wains) = wallColors(room)
        val lit = .55f + .45f * max(day, .35f)
        drawRect(Color(0xFF2A2622), Offset(0f, 0f), Size(W, 18f))
        drawRect(shade(paper, lit), Offset(32f, 18f), Size(W - 64f, 78f))
        // a faint stripe in the paper
        var sx = 40f
        while (sx < W - 40f) { drawRect(shade(paper, lit * .94f), Offset(sx, 18f), Size(4f, 52f)); sx += 16f }
        drawRect(shade(wains, lit), Offset(32f, 70f), Size(W - 64f, 22f))
        drawRect(shade(wains, lit * 1.25f), Offset(32f, 70f), Size(W - 64f, 2f))
        drawRect(shade(wains, lit * .7f), Offset(32f, 92f), Size(W - 64f, 4f))
        val (skyTop, skyBottom) = skyAt(hour)
        for (wx in windows) {
            drawRect(Color(0xFF3B2F25), Offset(wx - 24f, 24f), Size(48f, 42f))
            drawRect(Brush.verticalGradient(listOf(skyTop, skyBottom), 28f, 62f), Offset(wx - 20f, 28f), Size(40f, 34f))
            if (day < .2f) for (k in 0 until 3) {
                val tw = .5f + .5f * sin(clock * 2f + wx + k * 3f)
                drawRect(Color.White.copy(alpha = .6f * tw * (1f - day * 5f)), Offset(wx - 15f + k * 13f, 32f + (k * 7) % 12), Size(1.5f, 1.5f))
            }
            drawRect(Color(0xFF3B2F25), Offset(wx - 1.5f, 28f), Size(3f, 34f))
            drawRect(Color(0xFF3B2F25), Offset(wx - 20f, 43f), Size(40f, 3f))
            drawRect(Color.White.copy(alpha = .18f), Offset(wx - 18f, 30f), Size(6f, 12f))
            drawRect(Color(0xFF6B5442), Offset(wx - 27f, 64f), Size(54f, 4f))
        }
        // ---- the side and front walls, seen from above
        val top = Color(0xFF3A342E); val edge = Color(0xFF5A5148)
        drawRect(top, Offset(0f, 18f), Size(32f, H - 18f)); drawRect(edge, Offset(28f, 96f), Size(4f, H - 120f))
        drawRect(top, Offset(W - 32f, 18f), Size(32f, H - 18f)); drawRect(edge, Offset(W - 32f, 96f), Size(4f, H - 120f))
        drawRect(top, Offset(0f, H - 24f), Size(W, 24f)); drawRect(edge, Offset(32f, H - 24f), Size(W - 64f, 3f))
        // the doorway out, with a mat
        drawRect(tile0(room.floor), Offset(room.exitX - 26f, H - 24f), Size(52f, 24f))
        drawRect(Color(0xFF7A3F2E), Offset(room.exitX - 22f, H - 30f), Size(44f, 18f))
        drawRect(Color(0xFF9C5A40), Offset(room.exitX - 19f, H - 27f), Size(38f, 12f))
        // ---- everything standing in the room, back to front: the inner walls, the furniture, the people
        class Drawn(val y: Float, val draw: DrawScope.() -> Unit)
        val list = ArrayList<Drawn>()
        for (w in room.walls) {
            if (w.y < 96 || w.y >= H - 24 || w.x < 32 && w.w <= 32 || w.x >= W - 32) continue
            list += Drawn(w.y + w.h.toFloat()) {
                val horizontal = w.w > w.h
                if (horizontal) {
                    // a low wall's face below its top
                    drawRect(shade(paper, lit * .85f), Offset(w.x.toFloat(), w.y + w.h.toFloat()), Size(w.w.toFloat(), 14f))
                    drawRect(shade(wains, lit * .85f), Offset(w.x.toFloat(), w.y + w.h + 8f), Size(w.w.toFloat(), 6f))
                }
                drawRect(top, Offset(w.x.toFloat(), w.y.toFloat() - (if (horizontal) 0f else 14f)), Size(w.w.toFloat(), w.h.toFloat() + (if (horizontal) 0f else 14f)))
                drawRect(edge, Offset(w.x.toFloat(), w.y.toFloat() - (if (horizontal) 0f else 14f)), Size(w.w.toFloat(), 2f))
            }
        }
        for (p in room.pieces) {
            val img = InteriorArt.images[p.key] ?: continue
            val bottom = p.foot.y + p.foot.h.toFloat() + (if (p.solid) 0f else 0.5f)
            list += Drawn(bottom) {
                if (p.solid && p.foot.w > 0) {
                    // a soft contact shadow under it
                    drawOval(Color(0x40000000), Offset(p.foot.x + 2f, bottom - 8f), Size(p.foot.w - 4f, 12f))
                }
                drawImage(img, dstOffset = IntOffset(p.x.roundToInt(), p.y.roundToInt()), dstSize = IntSize(p.w, p.h), filterQuality = FilterQuality.None)
            }
        }
        val sheets = SpriteSheets.sheets
        if (sheets.isNotEmpty()) {
            val me = sheets[GameState.look.mod(sheets.size)]
            list += Drawn(walker.y) { person(me, walker.x, walker.y, walker.dir, walker.walking, walker.motion.gait, clock, walker.motion.runPose) }
            if (keeper != null) {
                // they face the player when the player is near
                val dx = walker.x - spot.first; val dy = walker.y - spot.second
                val face = if (dx * dx + dy * dy < 120f * 120f) (if (abs(dx) > abs(dy)) (if (dx > 0) 0 else 2) else (if (dy < 0) 1 else 3)) else 3
                list += Drawn(spot.second) { person(keeper, spot.first, spot.second, face, false, 0f, clock + 1.3f) }
            }
        }
        list.sortBy { it.y }
        for (d in list) d.draw(this)
        // ---- the light of the hour: the room darkens towards night, lamps glow warm; a vignette
        val night = (1f - day).coerceIn(0f, 1f)
        if (night > .02f) {
            // night blue over everything, then each room's lamp: a warm pool, brightest under it
            drawRect(lerp(Color.White, Color(0xFF2A3360), night * .85f), Offset.Zero, Size(W, H), blendMode = BlendMode.Multiply)
            for (a in room.areas) {
                val cx = a.rect.x + a.rect.w / 2f; val cy = a.rect.y + a.rect.h * .45f
                val r = max(a.rect.w, a.rect.h) * .62f
                val flick = 1f + .02f * sin(clock * 7f + cx)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB866).copy(alpha = .50f * night * flick), Color(0xFFFF9A40).copy(alpha = .18f * night), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy), blendMode = BlendMode.Plus)
            }
        } else drawRect(Color(0xFFFFE9C4).copy(alpha = .05f), Offset.Zero, Size(W, H), blendMode = BlendMode.Screen)
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x66000000)), Offset(W / 2f, H / 2f), max(W, H) * .75f), Offset.Zero, Size(W, H))
    }
}

private fun shade(c: Color, k: Float) = Color((c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f), c.alpha)

/** The back wall's windows: evenly along it, one for every ~150 px. */
private fun windowXs(room: InteriorRoom): List<Float> {
    val n = max(2, room.width / 150)
    return List(n) { i -> 32f + (room.width - 64f) * (i + .5f) / n }
}

private fun tile0(name: String): Color = when (name) { "floorWood" -> Color(0xFFB98B54); "floorTile" -> Color(0xFFD6D8DE); else -> Color(0xFFB3A898) }

private fun DrawScope.tile(name: String, x: Float, y: Float, w: Float, h: Float) {
    val img = InteriorArt.images[name]
    if (img == null) { drawRect(tile0(name), Offset(x, y), Size(w, h)); return }
    clipRect(x, y, x + w, y + h) {
        var ty = y
        while (ty < y + h) {
            var tx = x
            while (tx < x + w) {
                drawImage(img, dstOffset = IntOffset(tx.toInt(), ty.toInt()), dstSize = IntSize(32, 32), filterQuality = FilterQuality.None)
                tx += 32f
            }
            ty += 32f
        }
    }
}

/**
 * One of our character sheets (32 x 64 frames: row 1 idle, row 2 walk; right, up, left, down, 6 frames each); walking,
 * the frame is where in its cycle the walker's legs are ([gait], 0..1), so the feet move with the floor. Running ([run])
 * sideways, the run's frames (a runner's splayed stride, [RunFrames]).
 */
private fun DrawScope.person(sheet: ImageBitmap, x: Float, y: Float, dir: Int, walking: Boolean, gait: Float, clock: Float, run: Boolean = false) {
    val f = if (walking) ((gait * 6f).toInt().coerceIn(0, 5)) else ((clock * 6f).toInt() % 6)
    val running = walking && run && (dir == 0 || dir == 2)
    val src = if (running) RunFrames.of(sheet) else sheet
    val row = if (running) 0 else if (walking) 2 else 1
    drawOval(Color(0x50000000), Offset(x - 10f, y - 5f), Size(20f, 8f))
    drawImage(src, srcOffset = IntOffset((dir * 6 + f) * 32, row * 64), srcSize = IntSize(32, 64),
        dstOffset = IntOffset((x - 16f).roundToInt(), (y - 62f).roundToInt()), dstSize = IntSize(32, 64), filterQuality = FilterQuality.None)
}
