package com.lifemax.rpg.feature.habits.presentation.scene3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Declarative animation kinds for named nodes inside hand-made landmark `.glb` models.
 *
 * Driven by rules in `landmarks.json` (`animations`), `model-info.json`, or [LandmarkAnimRule.DEFAULT_TABLE] — never
 * hard-coded per landmark. Animated nodes are split out of static material merging by [LandmarkKit.build] into
 * node-local [AnimatedNodePart] renderables pivoted at each node's origin, then updated per frame via Filament's
 * `TransformManager` by [LandmarkAnimator] with zero allocations.
 */
enum class LandmarkAnimKind {
    /** Hour hand: 1 revolution per 12 game hours (or fixed at [LandmarkAnimRule.stoppedTime]). */
    CLOCK_HOUR,
    /** Minute hand: 1 revolution per 1 game hour (or fixed at [LandmarkAnimRule.stoppedTime]). */
    CLOCK_MINUTE,
    /** Constant-rate rotation about [LandmarkAnimRule.axis] (default 1 rev / game day). */
    SLOW_SPIN,
    /** Rotating searchlight/beacon around vertical axis at night only; hidden by day. */
    SWEEP_BEAM,
    /** Pendulum swing around horizontal axis (bells): triggered on the hour or via [LandmarkAnimator.ringBells]. */
    SWING,
    /** Vertical lift moving along +Y between [LandmarkAnimRule.stopsY] with a wait at each stop. */
    LIFT,
}

/**
 * One declarative rule matching glTF node names (glob pattern with `*` and `?`) to an animation kind and parameters.
 */
data class LandmarkAnimRule(
    val pattern: String,
    val kind: LandmarkAnimKind,
    /** Rotation axis in node-local (or model) space; null uses the kind's default. */
    val axis: FloatArray? = null,
    /** Period in game hours (for [LandmarkAnimKind.CLOCK_HOUR], [LandmarkAnimKind.CLOCK_MINUTE], [LandmarkAnimKind.SLOW_SPIN]). */
    val periodGameHours: Float = defaultGameHours(kind),
    /** Period in real seconds (for [LandmarkAnimKind.SWEEP_BEAM], [LandmarkAnimKind.SWING], or real-time [LandmarkAnimKind.SLOW_SPIN]). */
    val periodSeconds: Float = defaultRealSeconds(kind),
    /** Peak swing angle in degrees (for [LandmarkAnimKind.SWING], typically 25..35°). */
    val amplitudeDeg: Float = 30f,
    /** Phase offset in radians for [LandmarkAnimKind.SWING]; NaN auto-staggers multiple bells out of phase. */
    val phaseOffset: Float = Float.NaN,
    /** Full swing cycles triggered on the hour before easing to rest (for [LandmarkAnimKind.SWING]). */
    val swingsOnHour: Int = 5,
    /** Stop heights in metres (model Y, glTF units) for [LandmarkAnimKind.LIFT]. */
    val stopsY: FloatArray? = null,
    /** Dwell time in seconds at each stop for [LandmarkAnimKind.LIFT]. */
    val waitSeconds: Float = 6f,
    /** Travel speed in metres/second between stops for [LandmarkAnimKind.LIFT]. */
    val speedMps: Float = 2.5f,
    /** When true (default for [LandmarkAnimKind.SWEEP_BEAM]), visible only between dusk and dawn. */
    val nightOnly: Boolean = kind == LandmarkAnimKind.SWEEP_BEAM,
    /** Fixed clock time ("h:mm", e.g. "4:17") when a state's clock is stopped; null means live game time. */
    val stoppedTime: String? = null,
    /** Whether this animated element is active in the current state (false hides a disabled beacon or stops a clock). */
    val active: Boolean = true,
    /** Beam aim direction for an empty [LandmarkAnimKind.SWEEP_BEAM] locator node. */
    val direction: FloatArray? = null,
    /** ARGB beam colour for an empty [LandmarkAnimKind.SWEEP_BEAM] locator node. */
    val color: Int = 0xFFFFFFFF.toInt(),
) {
    /** Glob match (`*` = any chars, `?` = one char), case-insensitive. */
    fun matches(nodeName: String): Boolean = globMatch(pattern, nodeName)

    companion object {
        private fun defaultGameHours(kind: LandmarkAnimKind): Float = when (kind) {
            LandmarkAnimKind.CLOCK_HOUR -> 12f
            LandmarkAnimKind.CLOCK_MINUTE -> 1f
            LandmarkAnimKind.SLOW_SPIN -> 24f
            else -> 0f
        }

        private fun defaultRealSeconds(kind: LandmarkAnimKind): Float = when (kind) {
            LandmarkAnimKind.SWEEP_BEAM -> 10f
            LandmarkAnimKind.SWING -> 2.4f
            else -> 0f
        }

        /** Default pattern table applied when neither `landmarks.json` nor `model-info.json` overrides a node. */
        val DEFAULT_TABLE: List<LandmarkAnimRule> = listOf(
            LandmarkAnimRule("clock_hour*", LandmarkAnimKind.CLOCK_HOUR),
            LandmarkAnimRule("clock_minute*", LandmarkAnimKind.CLOCK_MINUTE),
            LandmarkAnimRule("armillary_ecliptic*", LandmarkAnimKind.SLOW_SPIN, periodGameHours = 24f),
            LandmarkAnimRule("armillary_globe*", LandmarkAnimKind.SLOW_SPIN, periodGameHours = 24f),
            LandmarkAnimRule("beacon_beam*", LandmarkAnimKind.SWEEP_BEAM, axis = floatArrayOf(0f, 1f, 0f), periodSeconds = 10f, nightOnly = true),
            LandmarkAnimRule("bell", LandmarkAnimKind.SWING, axis = floatArrayOf(1f, 0f, 0f), periodSeconds = 2.4f, amplitudeDeg = 30f, swingsOnHour = 5),
            LandmarkAnimRule("bell_*", LandmarkAnimKind.SWING, axis = floatArrayOf(1f, 0f, 0f), periodSeconds = 2.4f, amplitudeDeg = 30f, swingsOnHour = 5),
            LandmarkAnimRule("lift_car*", LandmarkAnimKind.LIFT, waitSeconds = 6f, speedMps = 2.5f),
            // the barber shops' striped pole (C005: pivot at the node origin, local +Y): one turn in 3.5 s (the model-info's suggested_period_s), day and night
            LandmarkAnimRule("barber_pole*", LandmarkAnimKind.SLOW_SPIN, axis = floatArrayOf(0f, 1f, 0f), periodSeconds = 3.5f),
        )

        /** Parses `"h:mm"` (e.g. `"4:17"`, `"04:17"`, `"12:30"`) into fractional game hours, or NaN if invalid. */
        fun parseClockTime(text: String?): Float {
            if (text.isNullOrBlank()) return Float.NaN
            val parts = text.trim().split(':')
            if (parts.size < 2) return Float.NaN
            val h = parts[0].trim().toIntOrNull() ?: return Float.NaN
            val m = parts[1].trim().toIntOrNull() ?: return Float.NaN
            val s = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            return h + m / 60f + s / 3600f
        }

        /** Parses a hex colour (`"#FFFFFF"`) into opaque ARGB, or [fallback]. */
        fun parseColor(text: String?, fallback: Int = 0xFFFFFFFF.toInt()): Int {
            if (text.isNullOrBlank()) return fallback
            val clean = text.trim().removePrefix("#")
            val v = clean.toLongOrNull(16) ?: return fallback
            return if (clean.length <= 6) (0xFF000000L or v).toInt() else v.toInt()
        }

        private fun parseKind(name: String): LandmarkAnimKind? = when (name.trim().lowercase().replace('-', '_')) {
            "clock_hour", "hour_hand", "hour" -> LandmarkAnimKind.CLOCK_HOUR
            "clock_minute", "minute_hand", "minute" -> LandmarkAnimKind.CLOCK_MINUTE
            "slow_spin", "spin", "rotate" -> LandmarkAnimKind.SLOW_SPIN
            "sweep_beam", "beam", "beacon", "sweep" -> LandmarkAnimKind.SWEEP_BEAM
            "swing", "bell", "pendulum" -> LandmarkAnimKind.SWING
            "lift", "elevator" -> LandmarkAnimKind.LIFT
            else -> null
        }

        private fun floatVec(a: org.json.JSONArray?): FloatArray? {
            if (a == null || a.length() == 0) return null
            return FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() }
        }

        /** Parses a JSON array of declarative animation rules (`landmarks.json` or `model-info.json` `"animations"`). */
        fun parseList(arr: org.json.JSONArray?): List<LandmarkAnimRule> {
            if (arr == null) return emptyList()
            val out = ArrayList<LandmarkAnimRule>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pattern = o.optString("node").ifBlank { o.optString("pattern") }
                if (pattern.isBlank()) continue
                val kind = parseKind(o.optString("kind")) ?: continue
                val axis = floatVec(o.optJSONArray("axis") ?: o.optJSONArray("rotation_axis"))
                val gh = if (o.has("period_game_hours")) o.optDouble("period_game_hours").toFloat()
                    else if (o.has("period_hours")) o.optDouble("period_hours").toFloat()
                    else defaultGameHours(kind)
                val rs = if (o.has("period_s")) o.optDouble("period_s").toFloat()
                    else if (o.has("period_seconds")) o.optDouble("period_seconds").toFloat()
                    else if (o.has("rotation_period_s")) o.optDouble("rotation_period_s").toFloat()
                    else defaultRealSeconds(kind)
                val amp = if (o.has("amplitude_deg")) o.optDouble("amplitude_deg", 30.0).toFloat() else 30f
                val phase = if (o.has("phase_rad")) o.optDouble("phase_rad").toFloat()
                    else if (o.has("phase_deg")) Math.toRadians(o.optDouble("phase_deg")).toFloat()
                    else Float.NaN
                val swings = o.optInt("swings_on_hour", o.optInt("swings", 5))
                val stops = floatVec(o.optJSONArray("stops_y_m") ?: o.optJSONArray("stops"))
                val wait = o.optDouble("wait_s", o.optDouble("wait_seconds", 6.0)).toFloat()
                val speed = o.optDouble("speed_mps", 2.5).toFloat()
                val nightOnly = o.optBoolean("night_only", kind == LandmarkAnimKind.SWEEP_BEAM)
                val stopped = o.optString("stopped_at").ifBlank { o.optString("stopped_time").ifBlank { null } }
                val active = o.optBoolean("active", true)
                val dir = floatVec(o.optJSONArray("direction"))
                val color = parseColor(o.optString("color").ifBlank { null })
                out += LandmarkAnimRule(
                    pattern = pattern, kind = kind, axis = axis, periodGameHours = gh, periodSeconds = rs,
                    amplitudeDeg = amp, phaseOffset = phase, swingsOnHour = swings, stopsY = stops,
                    waitSeconds = wait, speedMps = speed, nightOnly = nightOnly, stoppedTime = stopped,
                    active = active, direction = dir, color = color,
                )
            }
            return out
        }

        /**
         * Reads declarative animation rules from `model-info.json`: supports both an explicit `"animations"` array and
         * the structured fields used by prototype models (`states[].clock_active`/`clock_time`, `armillary_nodes`,
         * `beacon_beam`, `lift_car`).
         */
        fun fromModelInfo(infoJson: String?, year: Int, fileName: String? = null): List<LandmarkAnimRule> {
            if (infoJson.isNullOrBlank()) return emptyList()
            val root = runCatching { org.json.JSONObject(infoJson) }.getOrNull() ?: return emptyList()
            val out = ArrayList<LandmarkAnimRule>()
            out += parseList(root.optJSONArray("animations"))

            // State-specific clock override (e.g. courthouse 1988 state: clock_active = false, clock_time = "04:17")
            val statesArr = root.optJSONArray("states")
            if (statesArr != null) {
                var matched: org.json.JSONObject? = null
                val baseName = fileName?.substringAfterLast('/')
                for (i in 0 until statesArr.length()) {
                    val s = statesArr.optJSONObject(i) ?: continue
                    val sf = s.optString("file")
                    if (baseName != null && sf.isNotBlank() && sf == baseName) { matched = s; break }
                }
                if (matched == null) {
                    for (i in 0 until statesArr.length()) {
                        val s = statesArr.optJSONObject(i) ?: continue
                        val era = s.optJSONArray("era_years")
                        if (era != null && era.length() >= 2 && year in era.optInt(0)..era.optInt(1)) { matched = s; break }
                        if (s.optInt("year", -1) == year) { matched = s; break }
                    }
                }
                if (matched != null && matched.has("clock_active") && !matched.optBoolean("clock_active", true)) {
                    val t = matched.optString("clock_time").ifBlank { "12:00" }
                    out += LandmarkAnimRule("clock_hour*", LandmarkAnimKind.CLOCK_HOUR, stoppedTime = t)
                    out += LandmarkAnimRule("clock_minute*", LandmarkAnimKind.CLOCK_MINUTE, stoppedTime = t)
                }
            }

            // Armillary rings metadata (e.g. Great Meridian)
            root.optJSONObject("armillary_nodes")?.let { arm ->
                val keys = arm.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val entry = arm.optJSONObject(k) ?: continue
                    val node = entry.optString("node"); if (node.isBlank()) continue
                    val axis = floatVec(entry.optJSONArray("rotation_axis"))
                    val len = axis?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) } ?: 0f
                    if (len < 1e-4f || k.contains("fixed")) {
                        out += LandmarkAnimRule(node, LandmarkAnimKind.SLOW_SPIN, active = false)
                    } else {
                        out += LandmarkAnimRule(node, LandmarkAnimKind.SLOW_SPIN, axis = axis, periodGameHours = 24f)
                    }
                }
            }

            // Rotating aviation beacon metadata
            root.optJSONObject("beacon_beam")?.let { b ->
                val node = b.optString("node", "beacon_beam")
                val period = b.optDouble("rotation_period_s", 10.0).toFloat()
                val dir = floatVec(b.optJSONArray("direction"))
                val color = parseColor(b.optString("color").ifBlank { null })
                val active = b.optBoolean("active", true)
                out += LandmarkAnimRule(
                    node, LandmarkAnimKind.SWEEP_BEAM, axis = floatArrayOf(0f, 1f, 0f),
                    periodSeconds = period, nightOnly = true, active = active, direction = dir, color = color,
                )
            }

            // Lift car metadata
            root.optJSONObject("lift_car")?.let { l ->
                val node = l.optString("node", "lift_car")
                val stops = floatVec(l.optJSONArray("stops_y_m"))
                val wait = l.optDouble("wait_s", 6.0).toFloat()
                val speed = l.optDouble("speed_mps", 2.5).toFloat()
                out += LandmarkAnimRule(node, LandmarkAnimKind.LIFT, stopsY = stops, waitSeconds = wait, speedMps = speed)
            }

            return out
        }

        internal fun globMatch(pattern: String, text: String): Boolean {
            val p = pattern.lowercase()
            val t = text.lowercase()
            var pi = 0; var ti = 0; var star = -1; var match = 0
            while (ti < t.length) {
                if (pi < p.length && (p[pi] == '?' || p[pi] == t[ti])) {
                    pi++; ti++
                } else if (pi < p.length && p[pi] == '*') {
                    star = pi++; match = ti
                } else if (star != -1) {
                    pi = star + 1; ti = ++match
                } else return false
            }
            while (pi < p.length && p[pi] == '*') pi++
            return pi == p.length
        }
    }
}

/**
 * One animated node split out of a landmark `.glb` by [LandmarkKit.build]: its mesh parts are stored in node-local
 * street-unit coordinates (pivoted at `(0, 0, 0)`), and [restModel] maps node-local space into the landmark's model
 * frame (street units, with `groundLift` applied).
 */
class AnimatedNodePart(
    val nodeName: String,
    val kind: LandmarkAnimKind,
    val rule: LandmarkAnimRule,
    /** Node-local street-unit space -> landmark model space (column-major 4x4, translation in street units + groundLift). */
    val restModel: FloatArray,
    /** Renderable parts in node-local street-unit space (one per material inside this animated node). */
    val parts: List<CinemaPart>,
    /** Local bounding box [minX, minY, minZ, maxX, maxY, maxZ] in street units around the node origin. */
    val localBounds: FloatArray,
    /** Index among bells in this model (0, 1, 2...) so multiple bells swing out of phase. */
    val bellIndex: Int = 0,
    /** Effective stopped clock hour (NaN when live). */
    val stoppedHour: Float = LandmarkAnimRule.parseClockTime(rule.stoppedTime),
) {
    /** Total triangles across this animated node's parts. */
    val triangles: Int get() = parts.sumOf { it.mesh.idx.size / 3 }

    /**
     * Precomputed 12-o'clock orthonormal basis in model space for [LandmarkAnimKind.CLOCK_HOUR] / [CLOCK_MINUTE]:
     * `[x0x, x0y, x0z, y0x, y0y, y0z, z0x, z0y, z0z, sx, sy, sz]`, where `z0` is the dial's outward normal,
     * `y0` points to 12 o'clock, `x0 = y0 x z0` points to 3 o'clock (clockwise 90° as seen facing the dial),
     * independent of any static pose baked into the `.glb` node's rotation!
     */
    internal val clockBasis: FloatArray? = if (kind == LandmarkAnimKind.CLOCK_HOUR || kind == LandmarkAnimKind.CLOCK_MINUTE) {
        clockFrameOf(restModel)
    } else null

    /** Normalized rotation axis in node-local space (used for [SLOW_SPIN], [SWEEP_BEAM], [SWING]). */
    internal val spinAxis: FloatArray = normalizeAxis(
        rule.axis ?: when (kind) {
            LandmarkAnimKind.SWEEP_BEAM -> floatArrayOf(0f, 1f, 0f)
            LandmarkAnimKind.SWING -> floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(0f, 1f, 0f)
        }
    )

    /** Effective phase offset (radians) for [LandmarkAnimKind.SWING]: staggered when multiple bells exist. */
    internal val swingPhaseRad: Float = if (!rule.phaseOffset.isNaN()) rule.phaseOffset else bellIndex * 1.15f

    /** Effective swing period (seconds) slightly varied per bell index when unspecified so bells stay out of phase. */
    internal val swingPeriodSec: Float = (rule.periodSeconds.takeIf { it > 0.1f } ?: 2.4f) * (1f + (bellIndex % 4) * 0.06f)

    // Precomputed ping-pong schedule for LIFT (zero allocation per frame)
    private val liftYStops: FloatArray
    private val liftSegStart: FloatArray
    private val liftSegDur: FloatArray
    private val liftSegFrom: FloatArray
    private val liftSegTo: FloatArray
    private val liftTotalDur: Float

    init {
        val rawStops = rule.stopsY
        if (kind == LandmarkAnimKind.LIFT && rawStops != null && rawStops.size >= 2) {
            val U = LandmarkKit.UNITS_PER_METRE
            val restY = restModel[13]
            // Convert stop heights in metres into offsets relative to restModel's Y (which may include groundLift).
            // Detect whether restModel Y already matches one of the stops (modulo groundLift <= 2 units).
            val sorted = rawStops.sortedArray()
            val yOffsets = FloatArray(sorted.size) { i -> sorted[i] * U - restY }
            // Adjust by groundLift if restY was lifted slightly off the nearest stop
            var nearestErr = Float.MAX_VALUE
            var bestLift = 0f
            for (i in sorted.indices) {
                val err = restY - sorted[i] * U
                if (abs(err) < abs(nearestErr)) { nearestErr = err; bestLift = err }
            }
            if (abs(bestLift) <= 2f) {
                for (i in yOffsets.indices) yOffsets[i] += bestLift
            }
            liftYStops = yOffsets
            // Build ping-pong visit order: 0, 1, ..., n-1, n-2, ..., 1
            val order = ArrayList<Int>(yOffsets.size * 2)
            for (i in yOffsets.indices) order += i
            for (i in yOffsets.size - 2 downTo 1) order += i
            val segCount = order.size * 2 // dwell + ride for each stop in cycle
            liftSegStart = FloatArray(segCount)
            liftSegDur = FloatArray(segCount)
            liftSegFrom = FloatArray(segCount)
            liftSegTo = FloatArray(segCount)
            val wait = rule.waitSeconds.coerceAtLeast(0f)
            val speedU = (rule.speedMps.coerceAtLeast(0.2f)) * U
            var t = 0f
            var s = 0
            for (k in order.indices) {
                val cur = yOffsets[order[k]]
                val nxt = yOffsets[order[(k + 1) % order.size]]
                // Dwell at current stop
                liftSegStart[s] = t; liftSegDur[s] = wait; liftSegFrom[s] = cur; liftSegTo[s] = cur
                t += wait; s++
                // Ride to next stop
                val ride = (abs(nxt - cur) / speedU).coerceAtLeast(0.5f)
                liftSegStart[s] = t; liftSegDur[s] = ride; liftSegFrom[s] = cur; liftSegTo[s] = nxt
                t += ride; s++
            }
            liftTotalDur = t.coerceAtLeast(1f)
        } else {
            liftYStops = FloatArray(0)
            liftSegStart = FloatArray(0)
            liftSegDur = FloatArray(0)
            liftSegFrom = FloatArray(0)
            liftSegTo = FloatArray(0)
            liftTotalDur = 0f
        }
    }

    /** Clockwise angle from 12 o'clock (in radians) for a clock hand at [hour]. */
    fun clockAngleRad(hour: Float): Float {
        val h = if (!stoppedHour.isNaN()) stoppedHour else hour
        return when (kind) {
            LandmarkAnimKind.CLOCK_HOUR -> {
                val p = if (rule.periodGameHours > 0f) rule.periodGameHours else 12f
                val frac = ((h % p) + p) % p / p
                (frac * TWO_PI).toFloat()
            }
            LandmarkAnimKind.CLOCK_MINUTE -> {
                val p = if (rule.periodGameHours > 0f) rule.periodGameHours else 1f
                val frac = ((h % p) + p) % p / p
                (frac * TWO_PI).toFloat()
            }
            else -> 0f
        }
    }

    /** Whether this animated part should be visible at lighting level [dark] (0 day .. 1 night). */
    fun isVisible(dark: Float): Boolean {
        if (!rule.active && kind != LandmarkAnimKind.CLOCK_HOUR && kind != LandmarkAnimKind.CLOCK_MINUTE) return false
        if (rule.nightOnly && dark < NIGHT_DARK_THRESHOLD) return false
        return true
    }

    /** Vertical offset (in street units) for [LandmarkAnimKind.LIFT] at real time [seconds]; zero allocations. */
    fun liftOffsetY(seconds: Double): Float {
        if (liftTotalDur <= 0f) return 0f
        val cyc = ((seconds % liftTotalDur.toDouble()) + liftTotalDur.toDouble()) % liftTotalDur.toDouble()
        val t = cyc.toFloat()
        for (i in 0 until liftSegStart.size) {
            val end = liftSegStart[i] + liftSegDur[i]
            if (t <= end || i == liftSegStart.size - 1) {
                val dur = liftSegDur[i]
                if (dur <= 1e-5f) return liftSegTo[i]
                val u = ((t - liftSegStart[i]) / dur).coerceIn(0f, 1f)
                val smooth = u * u * (3f - 2f * u)
                return liftSegFrom[i] + (liftSegTo[i] - liftSegFrom[i]) * smooth
            }
        }
        return 0f
    }

    /**
     * Writes this node's animated model-space matrix (column-major 4x4, street units) into [out] without allocating.
     * [bellAngleRad] is the current pendulum angle (radians) computed by [LandmarkAnimator] for [LandmarkAnimKind.SWING].
     */
    fun computeModelMatrix(hour: Float, seconds: Double, dark: Float, bellAngleRad: Float, out: FloatArray) {
        when (kind) {
            LandmarkAnimKind.CLOCK_HOUR, LandmarkAnimKind.CLOCK_MINUTE -> {
                val cb = clockBasis!!
                val theta = clockAngleRad(hour)
                val c = cos(theta)
                val s = sin(theta)
                val x0x = cb[0]; val x0y = cb[1]; val x0z = cb[2]
                val y0x = cb[3]; val y0y = cb[4]; val y0z = cb[5]
                val z0x = cb[6]; val z0y = cb[7]; val z0z = cb[8]
                val sx = cb[9]; val sy = cb[10]; val sz = cb[11]
                // Clockwise by theta from 12 o'clock (+y0 towards +x0):
                // col0 = (x0 * cos - y0 * sin) * sx, col1 = (x0 * sin + y0 * cos) * sy, col2 = z0 * sz
                out[0] = (x0x * c - y0x * s) * sx; out[1] = (x0y * c - y0y * s) * sx; out[2] = (x0z * c - y0z * s) * sx; out[3] = 0f
                out[4] = (x0x * s + y0x * c) * sy; out[5] = (x0y * s + y0y * c) * sy; out[6] = (x0z * s + y0z * c) * sy; out[7] = 0f
                out[8] = z0x * sz;                 out[9] = z0y * sz;                 out[10] = z0z * sz;                out[11] = 0f
                out[12] = restModel[12];           out[13] = restModel[13];           out[14] = restModel[14];           out[15] = 1f
            }
            LandmarkAnimKind.SLOW_SPIN -> {
                val angle = if (rule.periodSeconds > 0f) {
                    val frac = ((seconds / rule.periodSeconds.toDouble()) % 1.0 + 1.0) % 1.0
                    (frac * TWO_PI).toFloat()
                } else {
                    val p = if (rule.periodGameHours > 0f) rule.periodGameHours else 24f
                    val frac = ((hour % p) + p) % p / p
                    (frac * TWO_PI).toFloat()
                }
                mulRestByAxisAngle(restModel, spinAxis[0], spinAxis[1], spinAxis[2], angle, out)
            }
            LandmarkAnimKind.SWEEP_BEAM -> {
                val p = if (rule.periodSeconds > 0f) rule.periodSeconds else 10f
                val frac = ((seconds / p.toDouble()) % 1.0 + 1.0) % 1.0
                val angle = (frac * TWO_PI).toFloat()
                mulRestByAxisAngle(restModel, spinAxis[0], spinAxis[1], spinAxis[2], angle, out)
            }
            LandmarkAnimKind.SWING -> {
                mulRestByAxisAngle(restModel, spinAxis[0], spinAxis[1], spinAxis[2], bellAngleRad, out)
            }
            LandmarkAnimKind.LIFT -> {
                for (i in 0..15) out[i] = restModel[i]
                out[13] = restModel[13] + liftOffsetY(seconds)
            }
        }
    }

    companion object {
        private const val TWO_PI = 2.0 * PI
        /** Night threshold matching the engine's dusk ramp (0 day, 1 night). */
        const val NIGHT_DARK_THRESHOLD = 0.25f

        internal fun normalizeAxis(a: FloatArray): FloatArray {
            val x = a.getOrElse(0) { 0f }; val y = a.getOrElse(1) { 1f }; val z = a.getOrElse(2) { 0f }
            val len = sqrt(x * x + y * y + z * z)
            return if (len > 1e-6f) floatArrayOf(x / len, y / len, z / len) else floatArrayOf(0f, 1f, 0f)
        }

        /**
         * Derives the 12-o'clock basis `[x0, y0, z0, sx, sy, sz]` on a clock dial from [restModel]'s outward normal
         * (column 2) and model up `(0, 1, 0)`, preserving scale lengths `(sx, sy, sz)` and discarding any baked hand pose.
         */
        internal fun clockFrameOf(restModel: FloatArray): FloatArray {
            val sx = sqrt(restModel[0] * restModel[0] + restModel[1] * restModel[1] + restModel[2] * restModel[2]).coerceAtLeast(1e-6f)
            val sy = sqrt(restModel[4] * restModel[4] + restModel[5] * restModel[5] + restModel[6] * restModel[6]).coerceAtLeast(1e-6f)
            val sz = sqrt(restModel[8] * restModel[8] + restModel[9] * restModel[9] + restModel[10] * restModel[10]).coerceAtLeast(1e-6f)
            val z0x = restModel[8] / sz; val z0y = restModel[9] / sz; val z0z = restModel[10] / sz
            // Up reference (0, 1, 0), or (0, 0, -1) if dial faces straight up/down
            val upx: Float; val upy: Float; val upz: Float
            if (abs(z0y) < 0.99f) { upx = 0f; upy = 1f; upz = 0f } else { upx = 0f; upy = 0f; upz = -1f }
            // x0 = normalize(up x z0) -> 3 o'clock (to viewer's right when facing the dial from +z0)
            var x0x = upy * z0z - upz * z0y
            var x0y = upz * z0x - upx * z0z
            var x0z = upx * z0y - upy * z0x
            val xl = sqrt(x0x * x0x + x0y * x0y + x0z * x0z).coerceAtLeast(1e-6f)
            x0x /= xl; x0y /= xl; x0z /= xl
            // y0 = z0 x x0 -> 12 o'clock (upwards in the dial plane)
            val y0x = z0y * x0z - z0z * x0y
            val y0y = z0z * x0x - z0x * x0z
            val y0z = z0x * x0y - z0y * x0x
            return floatArrayOf(x0x, x0y, x0z, y0x, y0y, y0z, z0x, z0y, z0z, sx, sy, sz)
        }

        /**
         * Computes `rest * R(axis, angle)` (rotating in node-local space about the node's origin) into [out] without
         * allocating any arrays.
         */
        internal fun mulRestByAxisAngle(rest: FloatArray, ax: Float, ay: Float, az: Float, angle: Float, out: FloatArray) {
            val c = cos(angle); val s = sin(angle); val t = 1f - c
            val r00 = t * ax * ax + c;      val r01 = t * ax * ay - s * az; val r02 = t * ax * az + s * ay
            val r10 = t * ax * ay + s * az; val r11 = t * ay * ay + c;      val r12 = t * ay * az - s * ax
            val r20 = t * ax * az - s * ay; val r21 = t * ay * az + s * ax; val r22 = t * az * az + c

            val m00 = rest[0]; val m10 = rest[1]; val m20 = rest[2]
            val m01 = rest[4]; val m11 = rest[5]; val m21 = rest[6]
            val m02 = rest[8]; val m12 = rest[9]; val m22 = rest[10]

            out[0]  = m00 * r00 + m01 * r10 + m02 * r20
            out[1]  = m10 * r00 + m11 * r10 + m12 * r20
            out[2]  = m20 * r00 + m21 * r10 + m22 * r20
            out[3]  = 0f

            out[4]  = m00 * r01 + m01 * r11 + m02 * r21
            out[5]  = m10 * r01 + m11 * r11 + m12 * r21
            out[6]  = m20 * r01 + m21 * r11 + m22 * r21
            out[7]  = 0f

            out[8]  = m00 * r02 + m01 * r12 + m02 * r22
            out[9]  = m10 * r02 + m11 * r12 + m12 * r22
            out[10] = m20 * r02 + m21 * r12 + m22 * r22
            out[11] = 0f

            out[12] = rest[12]; out[13] = rest[13]; out[14] = rest[14]; out[15] = 1f
        }

        /**
         * Builds a lightweight glowing searchlight cone for an empty `beacon_beam` locator node, pivoted at `(0, 0, 0)`
         * and aimed along [dir] (default `[0, -0.05, 1]`). Uses a 4x4 Bayer-dithered alpha fade and an own-colour glow
         * mask (`CinemaPart.Role.SIGN`, `casts = false`) so it feathers cleanly under `street_lit`'s masked pipeline.
         */
        internal fun syntheticBeaconBeam(dir: FloatArray?, color: Int): CinemaPart {
            val U = LandmarkKit.UNITS_PER_METRE
            val dx0 = dir?.getOrElse(0) { 0f } ?: 0f
            val dy0 = dir?.getOrElse(1) { -0.05f } ?: -0.05f
            val dz0 = dir?.getOrElse(2) { 1f } ?: 1f
            val dl = sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0).coerceAtLeast(1e-6f)
            val fx = dx0 / dl; val fy = dy0 / dl; val fz = dz0 / dl
            // Orthonormal basis around forward vector f
            val upx = if (abs(fy) < 0.95f) 0f else 1f
            val upy = if (abs(fy) < 0.95f) 1f else 0f
            val upz = 0f
            var rx = upy * fz - upz * fy; var ry = upz * fx - upx * fz; var rz = upx * fy - upy * fx
            val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-6f)
            rx /= rl; ry /= rl; rz /= rl
            val ux = fy * rz - fz * ry; val uy = fz * rx - fx * rz; val uz = fx * ry - fy * rx

            val pos = ArrayList<Float>()
            val uv = ArrayList<Float>()
            val nrm = ArrayList<Float>()
            val tan = ArrayList<Float>()
            val idx = ArrayList<Int>()

            fun addCone(r0: Float, r1: Float, length: Float, segs: Int, vMin: Float, vMax: Float) {
                val base = pos.size / 3
                for (i in 0..segs) {
                    val a = (i.toDouble() / segs * TWO_PI).toFloat()
                    val ca = cos(a); val sa = sin(a)
                    val cx = rx * ca + ux * sa; val cy = ry * ca + uy * sa; val cz = rz * ca + uz * sa
                    val uCoord = i.toFloat() / segs
                    // Near ring at origin
                    pos += cx * r0; pos += cy * r0; pos += cz * r0
                    uv += uCoord; uv += vMin
                    nrm += cx; nrm += cy; nrm += cz
                    tan += fx; tan += fy; tan += fz
                    // Far ring at +length along forward
                    pos += fx * length + cx * r1; pos += fy * length + cy * r1; pos += fz * length + cz * r1
                    uv += uCoord; uv += vMax
                    nrm += cx; nrm += cy; nrm += cz
                    tan += fx; tan += fy; tan += fz
                }
                for (i in 0 until segs) {
                    val a0 = base + i * 2
                    val b0 = a0 + 1
                    val a1 = a0 + 2
                    val b1 = a0 + 3
                    // Double-sided so the searchlight cone is visible from every angle, including near head-on
                    idx += a0; idx += a1; idx += b0
                    idx += a1; idx += b1; idx += b0
                    idx += a0; idx += b0; idx += a1
                    idx += a1; idx += b0; idx += b1
                }
            }

            // Outer feathered aviation searchlight cone (65 m long) + bright core (30 m long)
            addCone(0.55f * U, 4.4f * U, 65f * U, 8, 0.0f, 1.0f)
            addCone(0.32f * U, 1.5f * U, 30f * U, 8, 0.0f, 0.45f)

            // 16 x 64 Bayer-dithered beam texture: bright solid core near lantern, tapering cleanly along v
            val tw = 16; val th = 64
            val bayer = intArrayOf(
                0, 8, 2, 10,
                12, 4, 14, 6,
                3, 11, 1, 9,
                15, 7, 13, 5,
            )
            val cr = (color shr 16) and 255
            val cg = (color shr 8) and 255
            val cb = color and 255
            val tex = IntArray(tw * th)
            val mask = IntArray(tw * th)
            for (y in 0 until th) {
                val t = y.toFloat() / (th - 1) // 0 at beacon lantern -> 1 at far tip
                val density = (1f - t) * (1f - t * 0.55f) * 0.85f + (if (t < 0.08f) 0.2f else 0f)
                val glowScale = (1f - t * 0.45f).coerceIn(0.45f, 1f)
                val mr = (cr * glowScale).toInt().coerceIn(0, 255)
                val mg = (cg * glowScale).toInt().coerceIn(0, 255)
                val mb = (cb * glowScale).toInt().coerceIn(0, 255)
                val ownGlow = (0x80 shl 24) or (mr shl 16) or (mg shl 8) or mb
                for (x in 0 until tw) {
                    val threshold = (bayer[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f
                    val solid = density > threshold
                    tex[y * tw + x] = if (solid) ((0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb) else 0
                    mask[y * tw + x] = if (solid) ownGlow else 0
                }
            }

            val posA = pos.toFloatArray()
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (v in 0 until posA.size / 3) for (c in 0..2) {
                b[c] = min(b[c], posA[v * 3 + c])
                b[c + 3] = max(b[c + 3], posA[v * 3 + c])
            }
            val mesh = PropMesh(posA, uv.toFloatArray(), nrm.toFloatArray(), tan.toFloatArray(), idx.toIntArray(), tw, th, tex, mask, b)
            return CinemaPart(mesh, casts = false, tiled = false, role = CinemaPart.Role.SIGN)
        }
    }
}

/**
 * Runtime controller for one landmark's animated nodes.
 *
 * Pre-packs node-local geometry on any worker thread ([pack]), uploads batches into a [PropLayer] on the engine thread
 * ([upload] / [add]), and updates their Filament `TransformManager` matrices and visibility each frame via [update]
 * with zero per-frame allocations.
 */
internal class LandmarkAnimator private constructor(
    private val props: PropLayer,
    private val entries: Array<Entry>,
    /** Landmark model space -> world space (column-major 4x4, built from `at`). */
    private val worldFromModel: FloatArray,
) {
    private class Entry(
        val part: AnimatedNodePart,
        val batchStart: Int,
        val batchCount: Int,
        /** True when mesh Z was negated at pack time to cancel a negative determinant (mirrored street frame or node). */
        val mirrorZ: Boolean,
    )

    // Pre-allocated scratch matrices (zero per-frame allocation)
    private val scratchModel = FloatArray(16)
    private val scratchGpu = FloatArray(16)

    // Bell-ringing state
    private var lastHourBucket = Int.MIN_VALUE
    private var bellRingStartSec = Double.NaN
    private var bellRingSwings = 0

    val nodeCount: Int get() = entries.size
    val hasBells: Boolean = entries.any { it.part.kind == LandmarkAnimKind.SWING }

    /** Triggers all bells on this landmark to ring for [swings] cycles starting at [nowSeconds], then ease to rest. */
    fun ringBells(nowSeconds: Double, swings: Int = 5) {
        if (!hasBells || swings <= 0) return
        bellRingStartSec = nowSeconds
        bellRingSwings = swings
    }

    /**
     * Updates every animated node's transform and visibility for game [hour], real [seconds], and lighting [dark]
     * (0 day .. 1 night). When [active] is false (landmark off-screen or beyond near distance), returns immediately
     * doing no work. Allocates nothing.
     */
    fun update(hour: Float, seconds: Double, dark: Float, active: Boolean = true) {
        if (entries.isEmpty() || !active) return
        // Trigger bells automatically when the game clock crosses an integer hour boundary
        if (hasBells) {
            val bucket = floor(hour.toDouble()).toInt()
            if (lastHourBucket != Int.MIN_VALUE && bucket != lastHourBucket) {
                bellRingStartSec = seconds
                bellRingSwings = defaultHourSwings()
            }
            lastHourBucket = bucket
        }

        for (i in 0 until entries.size) {
            val e = entries[i]
            val p = e.part
            val vis = p.isVisible(dark)
            for (b in 0 until e.batchCount) props.setBatchVisible(e.batchStart + b, vis)
            if (!vis) continue

            val bellAngle = if (p.kind == LandmarkAnimKind.SWING) bellAngleRad(p, seconds) else 0f
            p.computeModelMatrix(hour, seconds, dark, bellAngle, scratchModel)
            GlbFile.mulInto(worldFromModel, scratchModel, scratchGpu)
            if (e.mirrorZ) {
                // Cancel pack-time local Z mirror so Filament always receives a positive-determinant matrix
                scratchGpu[8] = -scratchGpu[8]
                scratchGpu[9] = -scratchGpu[9]
                scratchGpu[10] = -scratchGpu[10]
            }
            for (b in 0 until e.batchCount) props.setBatchTransform(e.batchStart + b, scratchGpu)
        }
    }

    private fun defaultHourSwings(): Int {
        var n = 5
        for (i in 0 until entries.size) {
            val p = entries[i].part
            if (p.kind == LandmarkAnimKind.SWING) { n = p.rule.swingsOnHour; break }
        }
        return n
    }

    private fun bellAngleRad(p: AnimatedNodePart, seconds: Double): Float {
        if (bellRingStartSec.isNaN() || bellRingSwings <= 0) return 0f
        val elapsed = (seconds - bellRingStartSec).toFloat()
        if (elapsed < 0f) return 0f
        val period = p.swingPeriodSec
        val activeDur = bellRingSwings * period
        val easeOutDur = period * 1.25f
        val totalDur = activeDur + easeOutDur
        if (elapsed >= totalDur) return 0f
        val rampIn = (elapsed / 0.4f).coerceIn(0f, 1f)
        val envIn = rampIn * rampIn * (3f - 2f * rampIn)
        val envOut = if (elapsed <= activeDur) 1f else {
            val u = ((elapsed - activeDur) / easeOutDur).coerceIn(0f, 1f)
            val k = 1f - u
            k * k * (3f - 2f * k)
        }
        val ampRad = (p.rule.amplitudeDeg * (PI / 180.0)).toFloat()
        return ampRad * envIn * envOut * sin((elapsed / period) * (2.0 * PI) + p.swingPhaseRad).toFloat()
    }

    /** Worker-thread packed animated node ready for [upload]. */
    class PackedNode(
        val part: AnimatedNodePart,
        val batches: List<CinemaLayer.Packed>,
        val mirrorZ: Boolean,
    )

    /** Whole packed set for a landmark (worker-thread safe). */
    class PackedSet(
        val nodes: List<PackedNode>,
        val worldFromModel: FloatArray,
    ) {
        val isEmpty: Boolean get() = nodes.isEmpty()
    }

    companion object {
        /** Builds column-major 4x4 model -> world matrix matching `PropLayer.Packing.pack`'s placement `at`. */
        fun worldMatrixOf(at: FloatArray): FloatArray {
            val c = cos(at[6]); val s = sin(at[6])
            val ax = at[2]; val az = at[3]; val ox = at[4]; val oz = at[5]
            return floatArrayOf(
                ax * c - ox * s, 0f, az * c - oz * s, 0f,
                0f,              1f, 0f,              0f,
                ax * s + ox * c, 0f, az * s + oz * c, 0f,
                at[0],           0f, at[1],           1f,
            )
        }

        /** Packs all animated nodes of [lm] at placement [at] on any thread (no engine calls). */
        fun pack(lm: LandmarkModel, at: FloatArray, share: MutableMap<IntArray, StreetTextures.Prepared>? = null): PackedSet? {
            if (lm.animated.isEmpty()) return null
            val wm = worldMatrixOf(at)
            val worldMirrors = GlbFile.mirrors(wm)
            val packedNodes = ArrayList<PackedNode>(lm.animated.size)
            for (node in lm.animated) {
                val mirrorZ = worldMirrors xor GlbFile.mirrors(node.restModel)
                val batches = ArrayList<CinemaLayer.Packed>(node.parts.size)
                for (p in node.parts) {
                    val pk = PropLayer.Packing.packLocal(p.mesh, mirrorZ = mirrorZ, share = share) ?: continue
                    batches += CinemaLayer.Packed(pk, p.casts, p.tiled, p.role)
                }
                if (batches.isNotEmpty()) packedNodes += PackedNode(node, batches, mirrorZ)
            }
            return if (packedNodes.isEmpty()) null else PackedSet(packedNodes, wm)
        }

        /** Uploads a [PackedSet] onto [props] and registers lit/glowing parts with [cinema] (engine thread). */
        fun upload(props: PropLayer, cinema: CinemaLayer, set: PackedSet?): LandmarkAnimator? {
            if (set == null || set.isEmpty) return null
            val list = ArrayList<Entry>(set.nodes.size)
            for (n in set.nodes) {
                val start = props.batchCount
                for (b in n.batches) {
                    val mi = props.upload(b.packed, b.casts, b.tiled)
                    cinema.track(mi, b.role)
                }
                val count = props.batchCount - start
                if (count > 0) list += Entry(n.part, start, count, n.mirrorZ)
            }
            if (list.isEmpty()) return null
            val anim = LandmarkAnimator(props, list.toTypedArray(), set.worldFromModel)
            // Place at rest immediately so initial frame before first update has valid transforms
            anim.update(12f, 0.0, 0f, active = true)
            return anim
        }

        /** Convenience synchronous helper (preview & tests): packs and uploads on the calling engine thread. */
        fun add(props: PropLayer, cinema: CinemaLayer, lm: LandmarkModel, at: FloatArray): LandmarkAnimator? =
            upload(props, cinema, pack(lm, at))
    }
}
