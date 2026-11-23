package com.timerpg.street.play

/**
 * Where the player is and what they are doing, filled in place once a second by the game screen's provider
 * ([PlayLog.state]) on the recorder's thread. Strings are the game's own (the same objects second after second), so a
 * fill allocates nothing new while nothing changes.
 */
class PlayState {
    /** The town's seed ("gamma") or null outside one (the old crossroads). */
    var town: String? = null
    /** The street or row and the neighbourhood the HUD shows. */
    var street: String? = null
    var hood: String? = null
    /** The town position (street units) and the planet position (metres; NaN where the town is not placed). */
    var wx = Float.NaN
    var wz = Float.NaN
    var worldX = Double.NaN
    var worldZ = Double.NaN
    var year = 0
    var hour = 0f
    /** The camera: its distance (zoom), pitch and yaw (degrees), height over the player (street units). */
    var camDist = Float.NaN
    var camPitch = Float.NaN
    var camYaw = Float.NaN
    var camHeight = Float.NaN
    /** One of [Activity]'s names. */
    var activity: String = Activity.LOADING
    /** People drawn near, the person talked to, the room inside. */
    var people = -1
    var talkingTo: String? = null
    var room: String? = null
    /** The AI's lane now ("dialogue", "voice", ...) and the model's state. */
    var ai: String? = null
    var aiQueue = -1

    fun clear() {
        town = null; street = null; hood = null; wx = Float.NaN; wz = Float.NaN; worldX = Double.NaN; worldZ = Double.NaN
        year = 0; hour = 0f; camDist = Float.NaN; camPitch = Float.NaN; camYaw = Float.NaN; camHeight = Float.NaN
        activity = Activity.LOADING; people = -1; talkingTo = null; room = null; ai = null; aiQueue = -1
    }
}

/** What the player is doing (the timeline's "act"). */
object Activity {
    const val LOADING = "loading"
    const val WALK = "walk"
    const val STAND = "stand"
    const val RIDE = "ride"
    const val TALK = "talk"
    const val INSIDE = "inside"
    const val TRAIN = "train"
    const val MENU = "menu"
    const val PAUSED = "paused"
    val ALL = arrayOf(LOADING, WALK, STAND, RIDE, TALK, INSIDE, TRAIN, MENU, PAUSED)
}

/**
 * Play patterns: seconds per activity, a coarse position heatmap per town (grid counts, a cell [cell] street units
 * square), and counts of what the player used (talks, doors, rides, marks). Updated once a second; written as one line
 * now and then ([PlayLog]) and at the session's end.
 */
class Patterns(val cell: Float = 16f) {
    val seconds = IntArray(Activity.ALL.size)
    /** Town to (cell key to seconds). */
    val heat = HashMap<String, HashMap<Long, Int>>()
    val counts = LinkedHashMap<String, Int>()

    fun second(s: PlayState) {
        val a = Activity.ALL.indexOf(s.activity)
        if (a >= 0) seconds[a]++
        val t = s.town ?: return
        if (s.wx.isNaN() || s.wz.isNaN()) return
        val k = key(Math.floorDiv(s.wx.toInt(), cell.toInt().coerceAtLeast(1)), Math.floorDiv(s.wz.toInt(), cell.toInt().coerceAtLeast(1)))
        val m = heat.getOrPut(t) { HashMap() }
        m[k] = (m[k] ?: 0) + 1
    }

    fun count(what: String, n: Int = 1) { counts[what] = (counts[what] ?: 0) + n }

    /** The line's JSON: {"cell":16,"act":{...},"heat":{"town":[[cx,cz,seconds],...]},"use":{...}}. */
    fun json(): String {
        val sb = StringBuilder(256)
        sb.append("{\"cell\":"); JsonLine.fixed(sb, cell.toDouble(), 1)
        sb.append(",\"act\":{")
        var first = true
        for (i in seconds.indices) if (seconds[i] > 0) { if (!first) sb.append(','); first = false; sb.append('"').append(Activity.ALL[i]).append("\":").append(seconds[i]) }
        sb.append("},\"heat\":{")
        first = true
        for ((town, m) in heat) {
            if (!first) sb.append(','); first = false
            JsonLine.quote(sb, town); sb.append(":[")
            var f2 = true
            for ((k, v) in m.entries.sortedByDescending { it.value }) {
                if (!f2) sb.append(','); f2 = false
                sb.append('[').append(cx(k)).append(',').append(cz(k)).append(',').append(v).append(']')
            }
            sb.append(']')
        }
        sb.append("},\"use\":{")
        first = true
        for ((k, v) in counts) { if (!first) sb.append(','); first = false; JsonLine.quote(sb, k); sb.append(':').append(v) }
        sb.append("}}")
        return sb.toString()
    }

    companion object {
        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
        fun cx(k: Long): Int = (k shr 32).toInt()
        fun cz(k: Long): Int = k.toInt()
    }
}
