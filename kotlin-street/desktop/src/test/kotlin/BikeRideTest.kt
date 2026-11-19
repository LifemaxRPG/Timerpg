import com.lifemax.rpg.feature.habits.presentation.scene.BikeState
import com.lifemax.rpg.feature.habits.presentation.scene.BikeStyle
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import com.lifemax.rpg.feature.habits.presentation.scene.RideTuning
import com.lifemax.rpg.feature.habits.presentation.scene.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The bicycle in the open world (docs/TOWN.md "Open world: the bicycle") on the shipped gamma town: a scripted rider
 * crosses the town (back yards through their gates, the bridges, the plaza, the cinema's car park, back to the spawn)
 * never inside anything and faster than a runner on the same legs; the speed, the turning limit and the turn round;
 * sliding along a fence with a loss; slower on grass; getting on and off.
 */
class BikeRideTest {
    private val map get() = OpenWalkTest.map
    private val town get() = OpenWalkTest.town
    private val yards get() = OpenWalkTest.yards
    private val dt = 1f / 60f

    @Before fun reset() { GameState.motion.vx = 0f; GameState.motion.vy = 0f; GameState.moveX = 0f; GameState.moveLane = 0f; GameState.surface = Surface.HARD }

    private fun walk() = OpenWalk(town, map, yards, OpenWalkTest.doors)

    /** The bike's grid: cells of [STEP] where the bike's circle and some room besides are clear. */
    companion object {
        const val STEP = 6f
        const val ROOM = BikeState.RADIUS + 2.5f
        val cols by lazy { ((OpenWalkTest.map.maxX - OpenWalkTest.map.minX) / STEP).toInt() }
        val rows by lazy { ((OpenWalkTest.map.maxZ - OpenWalkTest.map.minZ) / STEP).toInt() }
        val free: BitSet by lazy {
            val m = OpenWalkTest.map
            val b = BitSet(cols * rows)
            for (j in 0 until rows) for (i in 0 until cols) if (!m.blocked(cx(i), cz(j), ROOM)) b.set(j * cols + i)
            b
        }
        fun cx(i: Int) = OpenWalkTest.map.minX + (i + .5f) * STEP
        fun cz(j: Int) = OpenWalkTest.map.minZ + (j + .5f) * STEP
        fun cellOf(x: Float, z: Float) = ((z - OpenWalkTest.map.minZ) / STEP).toInt() * cols + ((x - OpenWalkTest.map.minX) / STEP).toInt()
    }

    private fun bfs(from: Int): IntArray {
        val par = IntArray(cols * rows) { -1 }
        val q = IntArray(cols * rows)
        var h = 0; var t = 0
        par[from] = from; q[t++] = from
        while (h < t) {
            val c = q[h++]
            val i = c % cols; val j = c / cols
            fun go(n: Int) { if (par[n] < 0 && free[n]) { par[n] = c; q[t++] = n } }
            if (i > 0) go(c - 1); if (i < cols - 1) go(c + 1); if (j > 0) go(c - cols); if (j < rows - 1) go(c + cols)
            // (and the diagonals where both sides are free: paths at any angle, not staircases)
            if (i > 0 && j > 0 && free[c - 1] && free[c - cols]) go(c - cols - 1)
            if (i < cols - 1 && j > 0 && free[c + 1] && free[c - cols]) go(c - cols + 1)
            if (i > 0 && j < rows - 1 && free[c - 1] && free[c + cols]) go(c + cols - 1)
            if (i < cols - 1 && j < rows - 1 && free[c + 1] && free[c + cols]) go(c + cols + 1)
        }
        return par
    }

    private fun path(par: IntArray, start: Int, to: Int): IntArray? {
        if (par[to] < 0) return null
        val p = ArrayList<Int>()
        var c = to
        while (c != start) { p += c; c = par[c] }
        p += start
        return p.reversed().toIntArray()
    }

    /** Nothing in the bike's way on the straight line (its circle, every 2 units). */
    private fun clearLine(ax: Float, az: Float, bx: Float, bz: Float, r: Float): Boolean {
        val l = hypot(bx - ax, bz - az)
        val n = (l / 2f).toInt() + 1
        for (i in 0..n) { val t = i.toFloat() / n; if (map.blocked(ax + (bx - ax) * t, az + (bz - az) * t, r)) return false }
        return true
    }

    private var lifts = 0
    private var liftFrames = 0

    class Leg(val frames: Int, val arrived: Boolean, val maxRateExcess: Float)

    /**
     * Rides (or runs, [w] on foot) [w] along [path] with the stick (the camera swinging round all the while, the push
     * turned into its frame): aims at the farthest path point in plain sight within a reach that grows with the speed,
     * pushes less into sharp bends. Checks every frame that it is never inside anything and that the bike's turning
     * stays within its limit.
     */
    private fun follow(w: OpenWalk, path: IntArray, maxSeconds: Float, trail: MutableList<Pair<Float, Float>>?, yaw0: Float): Leg {
        var k = 0
        var frames = 0
        val goalX = cx(path.last() % cols); val goalZ = cz(path.last() / cols)
        var excess = 0f
        val log = ArrayDeque<String>()
        var stuck = 0f
        lifts = 0
        val r = w.radius
        while (frames < maxSeconds / dt) {
            if (hypot(w.wx - goalX, w.wz - goalZ) < 14f) break
            var best = k; var bd = Float.MAX_VALUE
            for (i in k until minOf(path.size, k + 80)) {
                val px = cx(path[i] % cols); val pz = cz(path[i] / cols)
                val d = hypot(px - w.wx, pz - w.wz)
                if (d < bd && (i == k || clearLine(w.wx, w.wz, px, pz, .5f))) { bd = d; best = i }
            }
            k = best
            val v = if (w.riding) w.bike.speed else w.speed
            val reach = if (w.riding) (v * .7f).coerceIn(24f, 220f) else (v * .45f).coerceIn(14f, 120f)
            var ahead = minOf(path.size - 1, k + (reach / STEP).toInt() + 1)
            while (ahead > k + 1 && !clearLine(w.wx, w.wz, cx(path[ahead] % cols), cz(path[ahead] / cols), if (w.riding) ROOM else r + .5f)) ahead--
            var tgt = path[ahead]
            // (stuck against something, as a player would: the stick pulled back a moment, the bike walked back, then on)
            val backing = stuck > .5f && w.riding
            if (stuck > 1.3f) stuck = 0f
            val tx = cx(tgt % cols) - w.wx; val tz = cz(tgt / cols) - w.wz
            val tl = hypot(tx, tz).coerceAtLeast(1e-3f)
            val yaw = yaw0 + frames * dt * .5f + sin(frames * dt * .37f) * 2f
            w.cameraYaw = yaw
            val fx = sin(yaw); val fz = -cos(yaw); val rx = -fz; val rz = fx
            val dx = tx / tl; val dz = tz / tl
            // (a bend ahead: push less, so the bike slows into it; a goal near: slow down for it)
            var p = 1f
            var bx = dx; var bz = dz
            if (w.riding) {
                val bend = abs(atan2(w.hx * dz - w.hz * dx, w.hx * dx + w.hz * dz))
                p = (1f - (bend - .12f).coerceAtLeast(0f) / 1.4f).coerceIn(.35f, 1f)
                val toGoal = hypot(w.wx - goalX, w.wz - goalZ)
                p = min(p, (toGoal / 120f).coerceIn(.35f, 1f))
                // (a bend in the path within the braking distance: the speed it can be taken at, braking for it as a player
                // would, the stick pulled back)
                val look = v * v / (2f * RideTuning.brake) + v * .35f + 30f
                var safe = RideTuning.sprint
                var along = 0f
                var i = k
                while (i + 6 < path.size && along < look) {
                    val ax = cx(path[i + 6] % cols) - cx(path[i] % cols); val az = cz(path[i + 6] / cols) - cz(path[i] / cols)
                    val al = hypot(ax, az).coerceAtLeast(1e-3f)
                    val dev = abs(atan2(w.hx * az - w.hz * ax, w.hx * ax + w.hz * az))
                    // (a turn of dev within the room left: the radius it needs, the speed the grip allows round it)
                    if (dev > .6f) safe = min(safe, (RideTuning.lateral * (along + 40f) / (dev * 1.2f)).let { kotlin.math.sqrt(it) })
                    along += STEP; i++
                }
                if (v > safe + 40f) { bx = -w.hx; bz = -w.hz; p = 1f }
                else p = min(p, (safe / RideTuning.cruise).coerceIn(.3f, 1f))
            }
            if (backing) { bx = -w.hx; bz = -w.hz; p = 1f }
            val mx = (bx * rx + bz * rz) * p
            val my = -(bx * fx + bz * fz) * p
            val px = w.wx; val pz = w.wz
            val v0 = if (w.riding) w.bike.speed else 0f
            w.step(dt, mx, my)
            frames++
            if (true) { log.addLast("t %.2f at (%.1f, %.1f) h (%.2f, %.2f) v %.1f push (%.2f, %.2f) tgt (%.0f, %.0f) blocked %.2f".format(frames * dt, w.wx, w.wz, w.hx, w.hz, if (w.riding) w.bike.speed else w.speed, bx * p, bz * p, cx(tgt % cols), cz(tgt / cols), w.blockedShare)); if (log.size > 2000) log.removeFirst() }
            val c = map.clearance(w.wx, w.wz, r * 2f)
            assertTrue("inside something at ${w.wx},${w.wz} (clearance $c, ${map.nearest(w.wx, w.wz).let { if (it >= 0) map.kind(it).name + " " + map.owner(it) else "" }})", c >= r - .05f)
            if (w.riding) {
                if (!w.bike.lifted && map.blocked(w.wx + w.hx * BikeState.FRONT, w.wz + w.hz * BikeState.FRONT, BikeState.FRONT_R - BikeState.FRONT_SLACK - .05f)) {
                    println(log.toList().takeLast(8).joinToString("\n"))
                    println("now at (${w.wx}, ${w.wz}) h (${w.hx}, ${w.hz}) v ${w.bike.speed} rate ${w.bike.rate} front clearance ${map.clearance(w.wx + w.hx * BikeState.FRONT, w.wz + w.hz * BikeState.FRONT, 20f)}")
                    val sh = FloatArray(6)
                    for (i in 0 until map.size) { map.shape(i, sh); if (hypot(sh[0] - w.wx, sh[1] - w.wz) < 60f) println("  near: ${map.kind(i)} ${map.owner(i)} ${sh.toList()}") }
                }
                if (w.bike.lifted) lifts++
                assertFalse("the front wheel in something at ${w.wx},${w.wz}", !w.bike.lifted && map.blocked(w.wx + w.hx * BikeState.FRONT, w.wz + w.hz * BikeState.FRONT, BikeState.FRONT_R - BikeState.FRONT_SLACK - .05f))
                val b = w.bike
                // (the limit at the speed the turn was made: after the frame's pedalling, before a wall took some off; never
                // more than the pedalling could add to the speed before the frame)
                assertTrue(b.turnSpeed <= v0 + RideTuning.accel * dt + .01f)
                excess = maxOf(excess, abs(b.rate) - RideTuning.maxRate(b.turnSpeed) * 1.001f)
            }
            val moved = hypot(w.wx - px, w.wz - pz)
            assertTrue("jumped $moved", moved <= RideTuning.sprint * dt * 1.5f + .5f)
            stuck = if (moved < 10f * dt || stuck > .5f) stuck + dt else 0f
            if (trail != null && frames % 6 == 0) trail += w.wx to w.wz
        }
        val arrived = hypot(w.wx - goalX, w.wz - goalZ) < 14f
        if (!arrived && w.riding) {
            println(log.joinToString("\n"))
            println("path near: " + path.filter { hypot(cx(it % cols) - w.wx, cz(it / cols) - w.wz) < 80f }.joinToString { "(${cx(it % cols).toInt()},${cz(it / cols).toInt()})" })
            val sh = FloatArray(6)
            for (i in 0 until map.size) { map.shape(i, sh); if (hypot(sh[0] - w.wx, sh[1] - w.wz) < 60f) println("  near: ${map.kind(i)} ${map.owner(i)} ${sh.toList()}") }
        }
        return Leg(frames, arrived, excess)
    }

    @Test fun aScriptedRiderCrossesTheTownFasterThanARunner() {
        val rider = walk()
        val spawn = rider.wx to rider.wz
        assertTrue("could not get on at the spawn", rider.mount())
        val goals = ArrayList<Pair<String, Pair<Float, Float>>>()
        val gated = yards.yards.filter { y -> y.back != null && y.items.any { it.kind == "sideGate" || it.kind == "gate" } }
        for (y in gated.filterIndexed { i, _ -> i % 9 == 0 }) { val b = y.back!!; goals += "back yard of ${y.sprite}" to ((b[0] + b[2]) / 2f to (b[1] + b[3]) / 2f) }
        for (b in town.bridges.take(4)) {
            val r = b.rect
            if (b.horizontal) { goals += "bridge west" to (r[0] - 60f to (r[1] + r[3]) / 2f); goals += "bridge east" to (r[2] + 60f to (r[1] + r[3]) / 2f) }
            else { goals += "bridge north" to ((r[0] + r[2]) / 2f to r[1] - 60f); goals += "bridge south" to ((r[0] + r[2]) / 2f to r[3] + 60f) }
        }
        town.plaza?.let { p -> goals += "plaza" to ((p[0] + p[2]) / 2f to p[1] + 30f) }
        OpenWalkTest.cinema?.let { c ->
            val (row, b) = town.placement(c.sprite)!!
            val p = com.lifemax.rpg.feature.habits.domain.TownPlace.of(row, b).world(0f, b.setback + c.deep - 60f)
            goals += "cinema car park" to (p.x to p.z)
        }
        goals += "spawn" to spawn
        val trail = ArrayList<Pair<Float, Float>>()
        var rideFrames = 0; var runFrames = 0
        var rode = 0f
        val done = ArrayList<String>()
        var worstExcess = 0f
        val legs = StringBuilder()
        for ((i, g) in goals.withIndex()) {
            val start = cellOf(rider.wx, rider.wz)
            val par = bfs(start)
            // the free cell nearest the goal that the bike can reach
            val c0 = cellOf(g.second.first, g.second.second); val i0 = c0 % cols; val j0 = c0 / cols
            var cell = -1; var bd = Int.MAX_VALUE
            val n = (90f / STEP).toInt()
            for (dj in -n..n) for (di in -n..n) {
                val ii = i0 + di; val jj = j0 + dj
                if (ii !in 0 until cols || jj !in 0 until rows) continue
                val c = jj * cols + ii
                if (par[c] >= 0 && di * di + dj * dj < bd) { bd = di * di + dj * dj; cell = c }
            }
            if (cell < 0) { println("goal ${g.first}: no way for the bike: skipped"); continue }
            val p = path(par, start, cell) ?: continue
            // a runner on the same leg, from the same spot (a full push: the run)
            val runner = walk()
            runner.placeAt(rider.wx, rider.wz, rider.hx, rider.hz)
            val run = follow(runner, p, 60f + p.size * STEP / 60f, null, i * 1.3f)
            val before = GameState.travel
            val leg = follow(rider, p, 60f + p.size * STEP / 60f, trail, i * 1.3f)
            rode += GameState.travel - before
            worstExcess = maxOf(worstExcess, leg.maxRateExcess)
            if (!leg.arrived) drawTrail(trail)
            assertTrue("did not ride to ${g.first}: at (${rider.wx}, ${rider.wz}) heading (${rider.hx}, ${rider.hz}), speed ${rider.bike.speed}, goal cell ${cx(cell % cols)},${cz(cell / cols)}", leg.arrived)
            rideFrames += leg.frames
            liftFrames += lifts
            if (run.arrived) runFrames += run.frames else runFrames += (60f + p.size * STEP / 60f / dt).toInt()
            legs.appendLine("  %-26s path %5.0f: ride %5.1f s, run %5.1f s%s".format(g.first, p.size * STEP, leg.frames * dt, run.frames * dt, if (run.arrived) "" else " (did not arrive)"))
            done += g.first
        }
        println("scripted ride: ${done.size} of ${goals.size} goals, ${"%.1f".format(rideFrames * dt)} s riding (${rode.toInt()} units, mean ${"%.0f".format(rode / (rideFrames * dt))} units/s) vs ${"%.1f".format(runFrames * dt)} s running the same legs; worst turn over the limit ${"%.3f".format(worstExcess)} rad/s; front wheel lifted round in ${liftFrames} frames")
        print(legs)
        assertTrue("too few goals: ${done.size} of ${goals.size}", done.size >= goals.size - 2)
        assertTrue("the turning limit was broken by $worstExcess rad/s", worstExcess <= .02f)
        assertTrue("riding (${rideFrames * dt} s) is not much faster than running (${runFrames * dt} s)", rideFrames < runFrames * .7f)
        drawTrail(trail)
    }

    /** A long clear straight near the spawn (a road): stands [w] at its start, returns its direction. */
    private fun straight(w: OpenWalk): Pair<Float, Float> {
        val (sx, sz) = OpenWalkTest.spawn
        var best = Triple(sx, sz, 0f to -1f); var bestLen = 0f
        var x = sx - 600f
        while (x <= sx + 600f) {
            var z = sz - 600f
            while (z <= sz + 600f) {
                if (!map.blocked(x, z, 20f)) for (k in 0 until 8) {
                    val a = k * PI.toFloat() / 4f
                    val dx = sin(a); val dz = -cos(a)
                    var l = 0f
                    while (l < 2400f && !map.blocked(x + dx * l, z + dz * l, 20f)) l += 10f
                    if (l > bestLen) { bestLen = l; best = Triple(x, z, dx to dz) }
                }
                z += 40f
            }
            x += 40f
        }
        println("straight from (${best.first}, ${best.second}) towards ${best.third}: ${bestLen.toInt()} clear")
        if (w.riding) w.dismount()
        w.placeAt(best.first, best.second, best.third.first, best.third.second)
        return best.third
    }

    /** The world push ([dx], [dz]) as the stick sees it under camera yaw 0 (looking north): right is +x, up is -z. */
    private fun ride(w: OpenWalk, dx: Float, dz: Float, frames: Int, each: (Int) -> Unit = {}) { w.cameraYaw = 0f; repeat(frames) { w.step(dt, dx, dz); each(it) } }

    @Test fun speedsTurningAndTheTurnRound() {
        val w = walk()
        val (dx, dz) = straight(w)
        assertTrue(w.mount())
        // from standing to the cruise in about 1.5 s at a full push (before the sprint starts)
        var t90 = -1f
        ride(w, dx * .9f, dz * .9f, 180) { if (t90 < 0f && w.bike.speed >= .9f * RideTuning.cruise * .9f) t90 = (it + 1) * dt }
        val cruise = w.bike.speed
        println("pushed .9: ${"%.0f".format(cruise)} units/s after 3 s (90% of it after ${"%.2f".format(t90)} s)")
        assertEquals(RideTuning.cruise * .9f, cruise, 2f)
        assertTrue("slow to speed up: $t90", t90 in .6f..2.2f)
        // a full push held: the sprint
        ride(w, dx, dz, 150)
        println("full push held 2.5 s: ${"%.0f".format(w.bike.speed)} units/s (sprinting ${w.bike.sprinting})")
        assertTrue(w.bike.sprinting && w.bike.speed > RideTuning.cruise + 40f)
        // let go: it coasts on (a glide, not a stop)
        val v0 = w.bike.speed
        ride(w, 0f, 0f, 60)
        println("coasting 1 s: ${"%.0f".format(v0)} -> ${"%.0f".format(w.bike.speed)}")
        assertTrue(w.bike.speed in v0 * .75f..v0 * .97f)
        assertFalse(w.bike.pedalling)
        // the turn round: the push straight back. It brakes first, then turns round tightly; no instant turn
        val h0x = w.hx; val h0z = w.hz
        var half = -1f; var whole = -1f; var speedAtHalf = 0f
        ride(w, -dx, -dz, 300) {
            val dot = w.hx * h0x + w.hz * h0z
            if (half < 0f && dot < 0f) { half = (it + 1) * dt; speedAtHalf = w.bike.speed }
            if (whole < 0f && dot < -.97f) whole = (it + 1) * dt
        }
        println("turn round from ${"%.0f".format(w.bike.speed)}: past 90 degrees after ${"%.2f".format(half)} s at ${"%.0f".format(speedAtHalf)} units/s, round after ${"%.2f".format(whole)} s")
        assertTrue("turned round too quickly: $whole s", whole > .9f)
        assertTrue("never turned round", whole in 0f..4f)
        assertTrue("turned at speed: $speedAtHalf", speedAtHalf <= RideTuning.cruise * .6f)
    }

    @Test fun turningRadiusGrowsWithSpeed() {
        val w = walk()
        // an open square of ground: the plaza, else the widest clear spot near the spawn
        for (target in floatArrayOf(.5f, 1f)) {
            val (dx, dz) = straight(w)
            assertTrue(w.mount())
            ride(w, dx * target, dz * target, 120)
            val v = w.bike.speed
            // push square to the right of where it heads: the turn is limited by the grip at this speed
            val rx = -w.hz; val rz = w.hx
            var most = 0f
            ride(w, rx * target, rz * target, 10) { most = maxOf(most, abs(w.bike.rate)) }
            val radius = w.bike.speed / most
            println("at ${"%.0f".format(v)} units/s the tightest turn is ${"%.2f".format(most)} rad/s: a radius of ${"%.0f".format(radius)} units (${"%.1f".format(radius / 21.18f)} m); lean ${"%.0f".format(Math.toDegrees(w.bike.lean.toDouble()))} degrees")
            assertTrue(most <= min(RideTuning.rateMax, RideTuning.lateral / (v * .9f)) + .01f)
            assertTrue("a sharp turn should lean right: ${w.bike.lean}", w.bike.lean > .05f)
            w.dismount()
        }
    }

    @Test fun slidesAlongAFenceWithALossAndStopsHeadOn() {
        val w = walk()
        val R = BikeState.RADIUS
        var pick: Triple<FloatArray, Float, Float>? = null
        loop@ for (y in yards.yards) for (it in y.items) {
            if (!(it.kind.endsWith("Fence")) || it.rect[2] - it.rect[0] < 400f) continue
            for (sd in floatArrayOf(1f, -1f)) {
                val zf = if (sd > 0) it.rect[3] + R + 1f else it.rect[1] - R - 1f
                var x = it.rect[0] + 20f
                while (x + 360f < it.rect[2]) {
                    if ((0..90).all { k -> !map.blocked(x + k * 4f, zf, R + .5f) && !map.blocked(x + k * 4f, zf + sd * 30f, R + 4f) }) { pick = Triple(it.rect, sd, x); break@loop }
                    x += 20f
                }
            }
        }
        val (r, side, sx) = pick ?: error("no clear fence")
        val z0 = if (side > 0) r[3] + 24f else r[1] - 24f
        w.placeAt(sx, z0, 1f, 0f)
        assertTrue(w.mount())
        // along it first, then pushed 30 degrees in: it slides along, slower, never through
        ride(w, 1f, 0f, 50)
        val free = w.bike.speed
        val x0 = w.wx
        ride(w, .866f, -side * .5f, 40)
        val gap = if (side > 0) w.wz - r[3] else r[1] - w.wz
        println("a fence: ${"%.0f".format(free)} units/s along it, then pushed 30 degrees in: ${"%.0f".format(w.wx - x0)} units on, at ${"%.0f".format(w.bike.speed)} units/s, ${"%.1f".format(gap)} from it")
        assertTrue("not against the fence ($gap)", gap >= R - .05f && gap < R + 3f)
        assertTrue("no loss scraping along", w.bike.speed < free * .95f)
        assertTrue("stuck on the fence", w.wx - x0 > 40f)
        // straight into it from a run up: it stops at it, the front wheel touching, never inside
        w.dismount()
        w.placeAt(sx + 200f, z0 + side * 60f, 0f, -side)
        assertTrue(w.mount())
        ride(w, 0f, -side, 120)
        val g2 = if (side > 0) w.wz - r[3] else r[1] - w.wz
        println("head on into it: stopped ${"%.1f".format(g2)} from it, speed ${w.bike.speed}")
        assertTrue("head on: $g2", g2 >= BikeState.FRONT - 1f && g2 < BikeState.FRONT + BikeState.FRONT_R + 2f)
        assertTrue(w.bike.speed < 5f)
    }

    @Test fun grassIsSlowerThanTheRoad() {
        val w = walk()
        // a lawn: a yard's open middle with the most room
        var spot: Pair<Float, Float>? = null
        for (y in yards.yards) {
            val p = y.parcel
            val x = (p[0] + p[2]) / 2f; val z = (p[1] + p[3]) / 2f
            if (w.surfaceAt(x, z, y) == Surface.GRASS && !map.blocked(x, z, 70f)) { spot = x to z; break }
        }
        val (x, z) = spot ?: error("no open lawn")
        w.placeAt(x - 50f, z, 1f, 0f)
        assertTrue(w.mount())
        var most = 0f
        ride(w, 1f, 0f, 30) { if (GameState.surface == Surface.GRASS) most = maxOf(most, w.bike.speed) }
        println("on a lawn: ${"%.0f".format(most)} units/s at most after 0.5 s (the road's cruise ${RideTuning.cruise})")
        assertTrue(most > 0f && most <= RideTuning.cruise * .56f)
    }

    @Test fun gettingOnAndOff() {
        val w = walk()
        GameState.footfalls = 0
        assertTrue(w.mount())
        assertTrue(w.riding && GameState.riding)
        assertTrue(map.clearance(w.wx, w.wz) >= BikeState.RADIUS - .05f)
        ride(w, 0f, -1f, 90)
        assertEquals("no footsteps while riding", 0, GameState.footfalls)
        assertFalse(GameState.walking)
        w.dismount()
        assertFalse(w.riding || GameState.riding)
        assertEquals(0f, w.speed, 1e-3f)
        assertTrue(map.clearance(w.wx, w.wz) >= OpenWalk.RADIUS - .05f)
        // the style is the town's era
        assertEquals(BikeStyle.of(town.year), w.bike.style)
        assertEquals(BikeStyle.CRUISER, BikeStyle.of(1955)); assertEquals(BikeStyle.TEN_SPEED, BikeStyle.of(1975))
        assertEquals(BikeStyle.BMX, BikeStyle.of(1985)); assertEquals(BikeStyle.HYBRID, BikeStyle.of(2025))
        // no room for a bike: squeezed between two things a walker fits between
        var tight: Pair<Float, Float>? = null
        val m = map
        search@ for (j in 0 until OpenWalkTest.rows step 3) for (i in 0 until OpenWalkTest.cols step 3) {
            val x = OpenWalkTest.cx(i); val z = OpenWalkTest.cz(j)
            if (!m.blocked(x, z, OpenWalk.RADIUS) && m.clearance(x, z, 40f) < 6f) {
                // (and nowhere within reach a bike fits)
                if (!m.nearestClear(x, z, BikeState.RADIUS, FloatArray(2), 2f, BikeState.MOUNT_REACH)) { tight = x to z; break@search }
            }
        }
        tight?.let { (x, z) ->
            w.placeAt(x, z)
            assertFalse("got on where there is no room at $x,$z", w.mount())
            println("no room for the bike at ($x, $z): stays on foot")
        }
    }

    /**
     * The camera on the bike (the owner's one exception to the camera rule): it swings round behind the rider with a lag,
     * never faster than its limit, not when stopped; a drag pauses it for three seconds; on foot it never turns.
     */
    @Test fun theCameraFollowsTheBikeOnlyOnTheBike() {
        val c = com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
        val dt = 1f / 60f
        c.bikeFollow = true
        c.set(0f, 20f, 420f)
        // on foot, heading east for 10 s: the yaw never changes
        repeat(600) { c.onFoot(dt); c.step(dt) }
        assertEquals(0f, c.yaw, 1e-4f)
        // riding east (90) at speed: it swings round, smoothly, within its rate
        var last = c.yaw; var maxRate = 0f
        repeat(600) { c.ride(dt, 90f, 200f); c.step(dt); maxRate = maxOf(maxRate, abs(c.yaw - last) / dt); last = c.yaw }
        assertTrue("swung behind the rider: ${c.yaw}", abs(c.yaw - 90f) < 3f)
        assertTrue("no faster than its limit: $maxRate", maxRate <= c.FOLLOW_MAX_RATE + 1f)
        // stopped, facing back west: it does not spin
        val y0 = c.yaw
        repeat(300) { c.ride(dt, -90f, 0f); c.step(dt) }
        assertEquals(y0, c.yaw, 1e-3f)
        // a drag pauses it: for under three seconds nothing but the drag turns it
        c.drag(100f, 0f, 2000f, 1000f)
        repeat(30) { c.ride(dt, 90f, 200f); c.step(dt) }
        val yd = c.goalYaw
        repeat(120) { c.ride(dt, 90f, 200f); c.step(dt) }
        assertEquals(yd, c.goalYaw, 1e-3f)
        // ... then eases back to following
        repeat(600) { c.ride(dt, 90f, 200f); c.step(dt) }
        assertTrue("following again: ${c.yaw}", abs(c.yaw - 90f) < 3f)
        // switched off: it stays
        c.bikeFollow = false
        c.set(0f, 20f, 420f)
        repeat(600) { c.ride(dt, 90f, 200f); c.step(dt) }
        assertEquals(0f, c.yaw, 1e-4f)
        c.bikeFollow = true
    }

    @Test fun aRideStepAllocatesNothing() {
        val w = walk()
        assertTrue(w.mount())
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        repeat(20000) { w.cameraYaw = it * .001f; w.step(dt, sin(it * .01f), -cos(it * .01f)) }
        val a0 = mx.getThreadAllocatedBytes(id)
        val t0 = System.nanoTime()
        val n = 20000
        for (it in 0 until n) { w.cameraYaw = it * .001f; w.step(dt, sin(it * .01f), -cos(it * .01f)) }
        val ns = (System.nanoTime() - t0) / n
        val alloc = (mx.getThreadAllocatedBytes(id) - a0).toDouble() / n
        println("a ride step: $ns ns, $alloc bytes each (build server JVM)")
        assertTrue("a ride step allocates $alloc bytes", alloc < 8.0)
    }

    private fun drawTrail(trail: List<Pair<Float, Float>>) {
        val m = map
        val W = 1400
        val k = W / (m.maxX - m.minX)
        val H = ((m.maxZ - m.minZ) * k).toInt()
        val img = java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (py in 0 until H) for (px in 0 until W) {
            val x = m.minX + px / k; val z = m.minZ + py / k
            val c = cellOf(x, z).coerceIn(0, cols * rows - 1)
            img.setRGB(px, py, if (free[c]) 0x5C685C else 0x202420)
        }
        for ((x, z) in trail) { val px = ((x - m.minX) * k).toInt(); val pz = ((z - m.minZ) * k).toInt(); if (px in 0 until W && pz in 0 until H) img.setRGB(px, pz, 0xFFD040) }
        javax.imageio.ImageIO.write(img, "png", java.io.File(OpenWalkTest.out, "bike_ride_town.png"))
    }
}
