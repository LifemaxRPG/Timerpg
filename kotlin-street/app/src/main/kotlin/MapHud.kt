package com.timerpg.street

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifemax.rpg.feature.habits.maps.CompassRose
import com.lifemax.rpg.feature.habits.maps.FullMap
import com.lifemax.rpg.feature.habits.maps.MapAccess
import com.lifemax.rpg.feature.habits.maps.MapData
import com.lifemax.rpg.feature.habits.maps.MapHere
import com.lifemax.rpg.feature.habits.maps.MapNav
import com.lifemax.rpg.feature.habits.maps.MapPlayer
import com.lifemax.rpg.feature.habits.maps.MapRules
import com.lifemax.rpg.feature.habits.maps.MapSettings
import com.lifemax.rpg.feature.habits.maps.MapStyle
import com.lifemax.rpg.feature.habits.maps.Minimap
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay

/*
 * The maps in the game (kotlin-street/docs/MAPS.md): the minimap in the HUD's top-left corner (or the compass rose
 * without a map), the full map it opens, and the rules between them: GPS from MapRules.GPS_YEAR, else the paper maps the
 * player owns (LoopView.maps, bought at the shops), the player's spot only where a street sign can be read. The town's
 * map data is made at load (TownData.map); the pictures on a worker of their own (MapData).
 */
object MapSession {
    var data by mutableStateOf<MapData?>(null); private set
    private var seed: String? = null
    /** The town's railway station (its depot), marked on the maps; null where the town has no railway. */
    var station: com.lifemax.rpg.feature.habits.maps.MapPoi? = null; private set
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The maps of the loaded town [d] (its city map and, with the planet, the regional and world maps). */
    fun start(ctx: Context, d: TownData) {
        if (seed == d.seed && data != null) return
        stop()
        val m = d.map ?: return
        MapData.post = { f -> main.post(f) }
        MapData.onError = { com.timerpg.street.shot.Crumbs.add(it) }
        seed = d.seed
        station = d.rail?.rail?.depot?.rect?.let { r -> com.lifemax.rpg.feature.habits.maps.MapPoi((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, com.lifemax.rpg.feature.habits.maps.PoiKind.STATION, "Station") }
        data = MapData(m, d.world, d.town.year).also { md ->
            // the look the year needs, made now on the maps' worker
            md.layer(com.lifemax.rpg.feature.habits.maps.MapTier.CITY, if (MapRules.gps(d.town.year)) MapStyle.GPS else MapStyle.PAPER, d.town.year)
        }
        val p = ctx.getSharedPreferences("maps", Context.MODE_PRIVATE)
        MapSettings.headingUp = p.getBoolean("headingUp", false)
        MapSettings.minimapZoom = p.getInt("zoom2", 2)
        MapSettings.autoZoom = p.getBoolean("autoZoom", true)
    }

    fun stop() { data?.close(); data = null; seed = null; station = null }

    fun saveSettings(ctx: Context) {
        ctx.getSharedPreferences("maps", Context.MODE_PRIVATE).edit().putBoolean("headingUp", MapSettings.headingUp).putInt("zoom2", MapSettings.minimapZoom).putBoolean("autoZoom", MapSettings.autoZoom).apply()
    }

    /** The player in the open world now (street x, z, heading, the camera's yaw), into [p]; false when not walking the town. */
    fun player(p: MapPlayer): Boolean {
        val w = TownPlay.walk ?: return false
        // (the speed, for the minimap's zoom: the car's when driving)
        val speed = TownPlay.cars?.takeIf { TownPlay.inCar }?.driven?.forwardSpeed?.let { kotlin.math.abs(it) } ?: 0f
        p.set(w.wx, w.wz, w.heading, if (TownPlay.openWorld) OrbitCamera.yaw else Math.toDegrees(w.heading.toDouble()).toFloat(), speed)
        return true
    }

    /** The places the app marks on the maps beside the town's own: the station, and the player's rented room. */
    fun extraPois(): List<com.lifemax.rpg.feature.habits.maps.MapPoi> {
        val out = ArrayList<com.lifemax.rpg.feature.habits.maps.MapPoi>(2)
        if (LoopSession.view?.rent != null) LoopSession.town?.lodging?.let { out += com.lifemax.rpg.feature.habits.maps.MapPoi(it.doorX, it.doorZ, com.lifemax.rpg.feature.habits.maps.PoiKind.HOME, "Your room") }
        station?.let { out += it }
        return out
    }
}

/** What the maps give the player here and now: the year's GPS, the paper maps they own, whether they are in town and on a named street. */
private fun accessNow(d: MapData, year: Int, onStreet: Boolean, inTown: Boolean): MapAccess {
    val v = LoopSession.view?.maps
    return MapRules.access(year, v?.owned.orEmpty(), MapHere(if (inTown) d.city.town.seed else null, v?.region?.ifEmpty { null } ?: d.region, onNamedStreet = onStreet))
}

/**
 * The HUD's map corner: the minimap of the best map the player has here (tap: [onOpen] the full map; pinch or +/-:
 * zoom), or the compass rose alone. Where the player is (in the town, on a street) is looked at twice a second, not per frame.
 */
@Composable
fun MapCorner(year: Int, tick: LongState, onOpen: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 150.dp) {
    val d = MapSession.data ?: return
    var onStreet by remember { mutableStateOf(true) }
    var inTown by remember { mutableStateOf(true) }
    val probe = remember { MapPlayer() }
    LaunchedEffect(d) {
        while (true) {
            if (MapSession.player(probe)) { inTown = d.city.inside(probe.x, probe.z, 1500f); onStreet = inTown && d.city.streetAt(probe.x, probe.z) != null }
            kotlinx.coroutines.delay(500)
        }
    }
    val a = accessNow(d, year, onStreet, inTown)
    @Suppress("UNUSED_VARIABLE") val ver = d.version
    val tier = a.minimap
    val layer = tier?.let { t -> (if (a.gps) year else a.printYear(t))?.let { d.layer(t, if (a.gps) MapStyle.GPS else MapStyle.PAPER, it) } }
    val nav = remember { MapNav() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(MapSettings.headingUp, MapSettings.minimapZoom, MapSettings.autoZoom) { MapSession.saveSettings(ctx) }
    val style = if (a.gps) MapStyle.GPS else MapStyle.PAPER
    val extra = MapSession.extraPois()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (tier == null || layer == null) CompassRose(tick, { OrbitCamera.yaw }, size = size, paper = !a.gps)
        else Minimap({ m -> if (tier == com.lifemax.rpg.feature.habits.maps.MapTier.CITY) d.cityFor(m, style) else layer }, a, tick, { MapSession.player(it) },
            LoopSession.view?.maps?.waypoint, nav, d.city, onOpen = onOpen, size = size, extra = extra)
    }
}

/** The full map over the screen: pan, zoom, tap for the waypoint ([LoopSession.setWaypoint], saved with the loop), close. */
@Composable
fun MapScreen(ctx: Context, year: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val d = MapSession.data ?: return
    val p = remember { MapPlayer() }
    val have = MapSession.player(p)
    val onStreet = have && d.city.inside(p.x, p.z, 1500f) && d.city.streetAt(p.x, p.z) != null
    val a = accessNow(d, year, onStreet, have && d.city.inside(p.x, p.z, 1500f))
    val nav = remember { MapNav() }
    if (a.opens == null) {
        // (no map at all: nothing to open; the corner shows the compass)
        LaunchedEffect(Unit) { onClose() }
        return
    }
    FullMap(d, a, year, if (have) p else null, LoopSession.view?.maps?.waypoint, nav,
        onWaypoint = { x, z -> LoopSession.setWaypoint(x, z) },
        onClose = { MapSession.saveSettings(ctx); onClose() }, modifier = modifier, extra = MapSession.extraPois())
}
