package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.lifemax.rpg.feature.habits.domain.AIM_AIR
import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.FurnitureKind
import com.lifemax.rpg.feature.habits.domain.Camera
import com.lifemax.rpg.feature.habits.domain.HabitStack
import com.lifemax.rpg.feature.habits.domain.HabitsProjection
import com.lifemax.rpg.feature.habits.domain.Hit
import com.lifemax.rpg.feature.habits.domain.HitKind
import com.lifemax.rpg.feature.habits.domain.KeyLight
import com.lifemax.rpg.feature.habits.domain.LensFlare
import com.lifemax.rpg.feature.habits.domain.Perspective
import com.lifemax.rpg.feature.habits.domain.Presence
import com.lifemax.rpg.feature.habits.domain.SceneLight
import com.lifemax.rpg.feature.habits.domain.SceneWindow
import com.lifemax.rpg.feature.habits.domain.Season
import com.lifemax.rpg.feature.habits.domain.Silhouettes
import com.lifemax.rpg.feature.habits.domain.StreetLayout
import com.lifemax.rpg.feature.habits.domain.StreetView
import com.lifemax.rpg.feature.habits.domain.StreetWeather
import com.lifemax.rpg.feature.habits.domain.SunEdge
import com.lifemax.rpg.feature.habits.domain.TOP_FADE
import com.lifemax.rpg.feature.habits.domain.WeatherOverride
import com.lifemax.rpg.feature.habits.domain.buildingAt
import com.lifemax.rpg.feature.habits.domain.buildingLayout
import com.lifemax.rpg.feature.habits.domain.centred
import com.lifemax.rpg.feature.habits.domain.focusCamera
import com.lifemax.rpg.feature.habits.domain.hit
import com.lifemax.rpg.feature.habits.domain.lerp
import com.lifemax.rpg.feature.habits.domain.orbAt
import com.lifemax.rpg.feature.habits.domain.orbSpot
import com.lifemax.rpg.feature.habits.domain.position
import com.lifemax.rpg.feature.habits.domain.rubberBand
import com.lifemax.rpg.feature.habits.domain.settle
import com.lifemax.rpg.feature.habits.domain.streetLayout
import com.lifemax.rpg.feature.habits.domain.streetView
import com.lifemax.rpg.feature.habits.domain.towards
import com.lifemax.rpg.feature.habits.presentation.HabitsCopy
import com.lifemax.rpg.feature.habits.presentation.HabitsUi
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the scene tells the screen. */
internal class StageActions(
    /** The owner's scroll settled on this building. */
    val swiped: (stackId: String) -> Unit = {},
    /** A tap on a building beside the one in front, or on one of the zoomed-out street. */
    val chosen: (stackId: String) -> Unit = {},
    /** A tap on something of the building in front. */
    val tapped: (stackId: String, hit: Hit) -> Unit = { _, _ -> },
    /** A pinch: out to the whole street (true), or back in (false). */
    val zoomed: (out: Boolean) -> Unit = {},
    /** Play [id] is over. */
    val played: (id: Long) -> Unit = {},
    /** A moment to feel: [Feel]. */
    val feel: (Feel) -> Unit = {},
)

/** The moments the phone buzzes at. */
internal enum class Feel {
    /** The street moved to another building. */
    TICK,

    /** A floor's lights came on. */
    LAND,

    /** A floor changed material, or a reward was claimed. */
    GLOW,

    /** A storey came down. */
    FELL,
}

/**
 * The running parts of a play: a floor being finished, a storey coming down, a floor dropping in.
 */
private class PlayMotion {
    val lights = Animatable(1f)
    val squash = Animatable(0f)
    val burst = Animatable(1f)
    val sweep = Animatable(1f)
    val fell = Animatable(1f)
    val drop = Animatable(1f)
    var landing by mutableStateOf<String?>(null)
    var felled by mutableStateOf<String?>(null)
    var adding by mutableStateOf<String?>(null)
    var swept by mutableStateOf(false)

    /**
     * The floor laid a moment ago, which people crowd to the windows of for
     * [Presence.CHEER_SECONDS], and when that began.
     */
    private var cheering: String? = null
    private var cheerAt = 0f
    private var wasLanding = false

    /** The play these parts are running, so one that ends late does not stop the next. */
    var playing = 0L

    /** Play [id] is over or was cut short: nothing is left half way (unless another has begun). */
    fun rest(id: Long) {
        if (playing != id) return
        landing = null
        felled = null
        swept = false
    }

    /** This frame's motion of the building in front, written into [into]. */
    fun now(seconds: Float, into: BuildingMotion): BuildingMotion {
        into.seconds = seconds
        into.landing = landing
        if (landing != null && !wasLanding) {
            cheering = landing
            cheerAt = seconds
        }
        wasLanding = landing != null
        if (cheering != null && seconds - cheerAt >= Presence.CHEER_SECONDS) cheering = null
        into.cheer = cheering
        into.cheerSince = seconds - cheerAt
        into.felled = felled
        into.adding = adding
        into.lights = lights.value
        into.squash = squash.value
        into.burst = burst.value
        into.sweep = sweep.value
        into.swept = swept
        into.fell = fell.value
        into.drop = drop.value
        return into
    }
}

/**
 * The scene's arithmetic for one frame, shared by the drawing and the taps so that a tap lands on
 * what is drawn there. [scroll] is where along the street the view stands (street units), [zoom] 0
 * (the building in front) to 1 (the whole street), [pan] the front building's, [lift] how far the
 * whole street is raised to clear a sheet, [inset] the top bar's pixels.
 */
private class Frame(
    val size: IntSize,
    val density: Float,
    val window: SceneWindow,
    val street: StreetLayout,
    val cameras: List<Camera>,
    val view: StreetView,
    val focus: Int,
    val scroll: Float,
    val zoom: Float,
    pan: Float,
    val lift: Float,
    val inset: Float,
) {
    /** The camera between the two buildings the view stands between. */
    val camera: Camera =
        if (cameras.isEmpty()) Camera(1.15f, 0f, 0f)
        else {
            val at = street.position(scroll)
            val i = floor(at).toInt().coerceIn(0, cameras.size - 1)
            val j = min(i + 1, cameras.size - 1)
            fun cam(k: Int) = if (k == focus) cameras[k].copy(pan = pan) else cameras[k]
            cam(i).towards(cam(j), (at - i).coerceIn(0f, 1f))
        }

    val scale: Float =
        lerp(camera.scale, view.scale, zoom).let { raw ->
            if (!StageTuning.pixelPerfect || !GameState.enabled) raw
            else {
                // a whole, even number of screen pixels to a sprite pixel: pixel art stays crisp, props can be 1.5x
                val per = max(2f, 2f * kotlin.math.round(raw * density * SpriteArt.PIXEL / 2f))
                per / (SpriteArt.PIXEL * density)
            }
        }
    val unit: Float = scale * density

    /** The street's x in the middle of the view. */
    val middle: Float = if (GameState.enabled) scroll else lerp(scroll, view.middle, zoom)

    /** The pavement line, lowered by the camera's pan and raised to clear a sheet. */
    val ground: Float = (window.ground * density - lift + camera.pan * (1f - zoom) * density).let { g ->
        if (!GameState.enabled) g
        else {
            // the game: low enough that the tallest building and its rooftop billboard fit under the top of the screen
            // (a little air above it), but never so low that the road goes
            val need = window.windowTop * density + SpriteArt.tallest * scale * density + 6f * density
            val lowest = size.height - (BuildingPainter.PAVEMENT + 26f) * scale * density
            if (need > g) minOf(need, lowest).coerceAtLeast(g) else g
        }
    }

    /** Under the heading's band, where the sky's own things stay. */
    val skyTop: Float = window.windowTop * density

    /**
     * The top of the bottom band (the page's controls), raised with the street to clear a sheet.
     */
    val band: Float = window.bandTop * density - lift

    /**
     * Nothing of a building is drawn above this: the heading's band, or, as the street rises to
     * clear a floor's dials (the heading is put away then), as far up as the top bar.
     */
    val top: Float = max(min(inset, skyTop), skyTop - lift)

    fun screenX(x: Float): Float = size.width / 2f + (x - middle) * unit

    /**
     * Which side face the building whose front's middle is at street [x] shows, as its back edge's
     * shift ([Perspective.shift]): its right face left of the view's middle, its left face right of
     * it, coming round as the street scrolls; gentler zoomed out.
     */
    fun sideShift(x: Float): Float =
        Perspective.shift(Perspective.sideAt(x, middle, size.width / unit, Perspective.k(zoom)))

    fun streetX(px: Float): Float = middle + (px - size.width / 2f) / unit
}

/**
 * The scene: the living sky of the hour and one street with a building per foundation. The street
 * follows a sideways drag and, let go, coasts and settles softly on the building nearest where it
 * would stop, which is then the one in front; an up-and-down drag tilts the view up a building too
 * tall for it; a pinch (or the Street button) zooms out to the whole street, which shows
 * [HabitsUi.weeks] weeks ago. A tap picks what is under it. [top] and [bottom] are the pixels the
 * screen's own controls take, as they were measured: the buildings, the sun, the moon and the
 * clouds keep between them ([SceneWindow]). [cover] is the height of a sheet over the bottom, which
 * the selected floor is kept clear of; [inset] the top bar's pixels, as far up as a building may
 * then reach. Nothing moves while the screen is not [visible] (A24) or motion is reduced: the
 * street is a still picture then. [ambientAt] holds the street's life at that many seconds (tests).
 */
@Composable
internal fun HabitsStage(
    p: HabitsProjection,
    ui: HabitsUi,
    visible: Boolean,
    top: Float,
    bottom: Float,
    cover: Float,
    inset: Float,
    accent: Color,
    actions: StageActions,
    modifier: Modifier = Modifier,
    ambientAt: Float? = null,
) {
    // the view's size is known while composing, so the first frame already stands where it should
    BoxWithConstraints(modifier.fillMaxSize()) {
        StageCanvas(
            IntSize(constraints.maxWidth, constraints.maxHeight),
            p,
            ui,
            visible,
            top,
            bottom,
            cover,
            inset,
            accent,
            actions,
            ambientAt,
        )
    }
}

@Composable
private fun StageCanvas(
    size: IntSize,
    p: HabitsProjection,
    ui: HabitsUi,
    visible: Boolean,
    top: Float,
    bottom: Float,
    cover: Float,
    inset: Float,
    accent: Color,
    actions: StageActions,
    ambientAt: Float?,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val text = remember(measurer, density.fontScale) { SceneText(measurer, density.fontScale) }
    val painter = remember { BuildingPainter() }
    val streetLife = remember {
        StreetLifePainter().also {
            painter.streetLife = it
            it.painter = painter
        }
    }
    val seed = remember(p.day) { p.day.hashCode() }
    val backdrop = remember(seed) { Backdrop(seed, p.day) }
    val season = remember(p.day) { Season.at(p.day) }
    val seasonFall = remember { SeasonFall() }
    val foregroundPainter = remember { ForegroundPainter() }
    val sunGlare = remember { SunGlare() }
    val backlight = remember { Backlight() }
    val sky = remember(p.hour) { skyColors(p.hour) }
    // the day's weather at this hour (or the one a test forces), and the key light under it
    val forced = WeatherOverride.forced
    val weather = remember(p.day, p.hour, forced) { forced ?: StreetWeather.at(p.day, p.hour) }
    val light = remember(p.hour, weather) { StreetWeather.light(SceneLight.key(p.hour), weather) }
    val layer = remember { Paint() }
    val vignette = remember { VignetteBrush() }
    val shadePath = remember { Path() }
    val shadeBrush = remember { cloudShadeBrush() }
    val scope = rememberCoroutineScope()
    // the painted skies and trees are made once, off the main thread (a still frame waits for them)
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { SceneSprites.warm() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { SceneSprites.warmStreet() } }
    LaunchedEffect(season) { withContext(Dispatchers.Default) { SceneSprites.warm(season) } }
    val still = p.reducedMotion
    val zoomedOut = ui.city
    val live = !zoomedOut || ui.weeks == 0

    val stacks =
        remember(p, zoomedOut, ui.weeks) { if (zoomedOut) p.towersAt(ui.weeks) else p.stacks }
    val focus = stacks.indexOfFirst { it.id == ui.focus }.coerceAtLeast(0)
    val layouts =
        remember(stacks, focus, zoomedOut, live) {
            stacks.mapIndexed { i, s -> buildingLayout(s, live, ghost = StageTuning.ghost && !zoomedOut && i == focus) }
        }
    val arts =
        remember(layouts, text) { layouts.mapIndexed { i, l -> buildingArt(l, stacks[i], text) } }
    val names =
        remember(stacks, text) { stacks.map { text.layout(it.cue, 11f, FontWeight.SemiBold, B.W) } }
    val street = remember(layouts) { streetLayout(layouts) }
    val window =
        remember(size, top, bottom, density.density) {
            SceneWindow(
                size.height / density.density,
                top / density.density,
                bottom / density.density,
            )
        }
    val avail = window.avail
    val cameras =
        remember(layouts, stacks, avail, ui.selected) {
            layouts.mapIndexed { i, l ->
                val id =
                    ui.selected?.takeIf { l.span(it) != null }
                        ?: stacks[i].next.takeIf { it.isNotEmpty() && l.span(it) != null }
                focusCamera(
                    l.height,
                    avail,
                    id?.let(l::span),
                    panRoom = window.panRoom,
                    air = id?.let(l::aimAir) ?: AIM_AIR,
                )
            }
        }
    val view =
        remember(street, layouts, size, avail) {
            streetView(street, layouts.map { it.height }, size.width / density.density, avail)
        }

    // the street: where along it the view stands, in street units
    val home = street.centers.getOrElse(focus) { 0f }
    val scroll = remember { Animatable(home) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(ui.jump, focus, home, dragging) {
        // the focus moved by something other than the finger (a tap, the button, a new foundation),
        // or the street changed under it: it settles on the building in front
        if (!dragging && scroll.targetValue != home) {
            if (still) scroll.snapTo(home)
            else scroll.animateTo(home, spring(dampingRatio = .86f, stiffness = 260f))
        }
    }
    val zoom = remember { Animatable(StageTuning.zoom ?: if (zoomedOut) 1f else 0f) }
    LaunchedEffect(zoomedOut) {
        val to = StageTuning.zoom ?: if (zoomedOut) 1f else 0f
        if (still) zoom.snapTo(to)
        else zoom.animateTo(to, spring(dampingRatio = .9f, stiffness = 220f))
    }

    // the front building's pan: the camera's own aim, until the owner drags it
    val front = cameras.getOrNull(focus)
    val pan = remember(ui.focus, size) { Animatable(front?.pan ?: 0f) }
    var panned by remember { mutableStateOf(false) }
    // another building, another floor picked, a floor finished: the camera aims again
    LaunchedEffect(ui.focus, ui.selected, stacks.getOrNull(focus)?.next) { panned = false }
    SideEffect { pan.updateBounds(0f, front?.maxPan ?: 0f) }
    LaunchedEffect(pan, front?.pan, front?.maxPan, panned) {
        val aim = front?.pan ?: 0f
        if (!panned && pan.targetValue != aim) {
            if (still) pan.snapTo(aim)
            else pan.animateTo(aim, spring(dampingRatio = .9f, stiffness = 180f))
        }
    }

    // a sheet over the bottom: the street rises until the selected floor stands clear of it
    val liftTo = run {
        val piece = ui.selected?.let { id -> layouts.getOrNull(focus)?.labelled(id) }
        if (cover <= 0f || piece == null || front == null) 0f
        else {
            val foot = (window.ground - front.above(piece.bottom)) * density.density
            max(0f, foot + 18f * density.density - (size.height - cover))
        }
    }
    val lift = remember { Animatable(0f) }
    LaunchedEffect(liftTo) {
        if (still) lift.snapTo(liftTo)
        else lift.animateTo(liftTo, spring(dampingRatio = .9f, stiffness = 300f))
    }

    // the street's own time: stars, people, planes. It ticks about 30 times a second while the tab
    // shows (the finger and the plays redraw on their own), and stands still otherwise; in the game it ticks every frame
    // while the player or the camera moves (the street is redrawn every frame then anyway, and the passers-by would
    // otherwise judder against it)
    var seconds by remember { mutableFloatStateOf(ambientAt ?: 0f) }
    LaunchedEffect(visible, still, ambientAt) {
        if (ambientAt != null) seconds = ambientAt
        else if (visible && !still) {
            val start = withFrameNanos { it }
            var last = 0L
            while (true) withFrameNanos {
                if (it - last >= TICK_NANOS || (GameState.enabled && GameState.inMotion)) {
                    last = it
                    seconds = (it - start) / 1e9f + .001f
                }
            }
        } else seconds = 0f
    }

    // a floor finished, a storey coming down
    val motion = remember { PlayMotion() }
    val play = ui.play
    LaunchedEffect(play?.id, visible) {
        if (play == null) return@LaunchedEffect
        try {
            if (visible && !still) {
                motion.playing = play.id
                if (play.fx.laid) {
                    motion.lights.snapTo(0f)
                    motion.squash.snapTo(0f)
                    motion.burst.snapTo(0f)
                    motion.sweep.snapTo(0f)
                    motion.felled = null
                    motion.swept = play.fx.upgrade != null
                    motion.landing = play.fx.id
                    actions.feel(Feel.LAND)
                    coroutineScope {
                        // the lights come on window by window and the people arrive
                        launch {
                            motion.lights.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
                        }
                        launch {
                            motion.squash.snapTo(1f)
                            motion.squash.animateTo(
                                0f,
                                spring(dampingRatio = .4f, stiffness = 420f),
                            )
                        }
                        launch { motion.burst.animateTo(1f, tween(760, easing = LinearEasing)) }
                        if (motion.swept)
                            launch {
                                actions.feel(Feel.GLOW)
                                motion.sweep.animateTo(
                                    1f,
                                    tween(760, delayMillis = 260, easing = LinearEasing),
                                )
                            }
                    }
                } else {
                    motion.fell.snapTo(0f)
                    motion.landing = null
                    motion.felled = play.fx.id
                    actions.feel(Feel.FELL)
                    motion.fell.animateTo(1f, tween(680, easing = LinearEasing))
                }
            } else if (visible) actions.feel(if (play.fx.laid) Feel.LAND else Feel.FELL)
        } finally {
            // also when cut short (the screen hid, another floor finished): nothing is left half
            // way
            motion.rest(play.id)
            actions.played(play.id)
        }
    }

    // a habit just stacked (or moved here) drops into its place with a puff of scaffolding dust
    val known = remember { HashMap<String, Set<String>>() }
    LaunchedEffect(p.stacks) {
        var fresh: String? = null
        for (s in p.stacks) {
            val ids = s.blocks.map { it.id }.toSet()
            val before = known[s.id]
            if (before != null && s.id == ui.focus) fresh = (ids - before).firstOrNull() ?: fresh
            known[s.id] = ids
        }
        if (fresh != null && visible && !still) {
            motion.adding = fresh
            try {
                motion.drop.snapTo(0f)
                motion.drop.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            } finally {
                motion.adding = null
            }
        }
    }

    // the game's crossroads: both streets laid out once (so the walk round the corner swaps them in a frame, with nothing
    // to build), drawn as one place through one camera
    val crossStacks = if (GameState.enabled) StageTuning.crossroads else null
    val sides =
        remember(crossStacks, text, live, avail, size, density.density) {
            crossStacks?.let { cs ->
                listOf(cs.main, cs.cross, cs.east).mapIndexed { k, st ->
                    val ls = st.map { buildingLayout(it, live, ghost = false) }
                    val drawn = ls.mapIndexed { i, l -> buildingArt(l, st[i], text) }
                    val laid = streetLayout(ls)
                    val cams = ls.map { l -> focusCamera(l.height, avail, null, panRoom = window.panRoom, air = AIM_AIR) }
                    val vw = streetView(laid, ls.map { it.height }, size.width / density.density, avail)
                    val life = if (k < 2) StreetLifePainter().also { it.painter = painter; it.street = k } else null
                    CrossSide(k, st, ls, drawn, laid, cams, vw, life)
                }
            }
        }
    val crossroads =
        remember(sides) {
            sides?.let { s ->
                val j = s[0].layouts.indexOfFirst { it.stackId == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID }
                if (j <= 0 || s[1].layouts.isEmpty()) null
                else com.lifemax.rpg.feature.habits.domain.Crossroads(s[0].street, j, s[1].street, s[2].street.takeIf { s[2].layouts.isNotEmpty() })
            }
        }
    val world = remember { CornerWorld() }
    /** The street the player is on, when the game has a crossroads (read every frame: the walk swaps it mid-frame). */
    fun side(): CrossSide? = if (crossroads == null) null else sides?.getOrNull(GameState.street.coerceIn(0, 1))

    fun frame() =
        Frame(
            size,
            density.density,
            window,
            side()?.street ?: street,
            side()?.cameras ?: cameras,
            side()?.view ?: view,
            focus,
            scroll.value,
            zoom.value,
            pan.value,
            if (still) liftTo else lift.value,
            inset,
        )

    val describe =
        remember(p, ui.focus, zoomedOut, ui.weeks) {
            HabitsCopy.describe(p, p.stack(ui.focus), zoomedOut, ui.weeks)
        }
    val talkBack =
        remember(stacks, focus, zoomedOut) {
            if (zoomedOut || stacks.isEmpty()) emptyList()
            else
                buildList {
                    val here = stacks[focus]
                    stacks.getOrNull(focus + 1)?.let { s ->
                        add(
                            CustomAccessibilityAction("Next building: ${s.cue}") {
                                actions.chosen(s.id).let { true }
                            }
                        )
                    }
                    stacks.getOrNull(focus - 1)?.let { s ->
                        add(
                            CustomAccessibilityAction("Previous building: ${s.cue}") {
                                actions.chosen(s.id).let { true }
                            }
                        )
                    }
                    here.blocks.forEach { b ->
                        add(
                            CustomAccessibilityAction("Tune ${b.title}") {
                                actions.tapped(here.id, Hit(HitKind.FLOOR, b.id)).let { true }
                            }
                        )
                    }
                    add(
                        CustomAccessibilityAction("Edit foundation ${here.cue}") {
                            actions.tapped(here.id, Hit(HitKind.LOBBY, here.id)).let { true }
                        }
                    )
                    add(
                        CustomAccessibilityAction("Add a floor to ${here.cue}") {
                            actions.tapped(here.id, Hit(HitKind.GHOST, here.id)).let { true }
                        }
                    )
                }
        }

    // the game: the camera follows the player instead of settling on a building
    // (read by the draw, so every frame the player or the camera moves in is drawn: see the game loop)
    var moves by remember { mutableIntStateOf(0) }
    val streetNow = rememberUpdatedState(street)
    val layoutsNow = rememberUpdatedState(layouts)
    var playerFrame by remember { mutableIntStateOf(0) }
    val crossNow = rememberUpdatedState(crossroads)
    val sidesNow = rememberUpdatedState(sides)
    LaunchedEffect(GameState.enabled) {
        if (!GameState.enabled) return@LaunchedEffect
        var last = 0L
        var seen = Float.NaN
        while (true) {
            val t = withFrameNanos { it }
            val first = last == 0L
            val dt = if (first) 0f else ((t - last) / 1e9f).coerceAtMost(.1f)
            last = t
            // (no time passed: nothing to step; a redraw of the same moment must show the same thing)
            if (!first && dt <= 0f) continue
            // a frame is drawn whenever the player or the camera moved (the walk round the corner every frame); standing
            // still, the street's own clock redraws it
            val moved = GameState.x + 7f * GameState.lane + 13f * GameState.facing + GameState.travel + 31f * GameState.street +
                (if (CornerWalk.active) 1000f * CornerWalk.p + 1f else 0f)
            if (moved != seen) { seen = moved; moves++ }
            val cr = crossNow.value
            val cs = if (cr == null) null else sidesNow.value?.getOrNull(GameState.street.coerceIn(0, 1))
            val s = cs?.street ?: streetNow.value
            val lays = cs?.layouts ?: layoutsNow.value
            // the middle of the side street's mouth on this street, if it opens here
            val jx = if (cr != null) (if (GameState.street == 0) cr.mouthMid else Float.NaN)
                else s.lefts.indices.firstOrNull { lays.getOrNull(it)?.stackId == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID }
                    ?.let { s.lefts[it] + lays[it].lot / 2f } ?: Float.NaN
            GameState.junctionX = jx
            GameState.centers = s.centers
            if (cr == null) {
                // arriving back round the corner: stand just short of the junction end of this street
                if (GameState.x > s.end + 500f) { GameState.x = s.end - 150f; GameState.camera.reset() }
                // arriving back from the side street: at the junction, where it opens
                if (GameState.x < -1e8f) { GameState.x = if (!jx.isNaN()) jx else s.end - 150f; GameState.camera.reset() }
            } else {
                // a push of the stick up (or the up key) in the mouth, or at the cross street's start, walks the player
                // round the corner
                if (!CornerWalk.active && CornerWalk.arm(cr, GameState.street)) {
                    val sd = sidesNow.value!!
                    turnEnds(cr, sd[0].street, sd[1].street, scroll.value)
                    // the frame the walk starts in is the street's own view still: nothing moves until the next
                    GameState.atCorner = false
                    continue
                }
                if (CornerWalk.active) {
                    val yaw = CornerWalk.yaw
                    val done = CornerWalk.walk(dt, cr, kotlin.math.cos(yaw), kotlin.math.sin(yaw), -kotlin.math.sin(yaw), kotlin.math.cos(yaw))
                    // round the corner: the other street's own view, in this same frame, with no look-ahead yet (the
                    // camera's lead grows again from here as the player walks on, so the hand-over does not jump)
                    if (done == 1) { scroll.snapTo(CornerWalk.toMiddle); GameState.camera.reset() }
                    GameState.atCorner = false
                    playerFrame++; GameState.inMotion = true
                    // (the frame the walk ends in shows exactly where it ended; walking on starts with the next)
                    continue
                }
            }
            val s2 = s
            val before = GameState.x
            val laneBefore = GameState.lane
            val walkingBefore = GameState.walking; val leanBefore = GameState.leanPixels
            val facingBefore = GameState.facing; val dirBefore = GameState.dir; val poseBefore = GameState.runPose
            GameState.step(dt, s.start, s.end, BuildingPainter.PAVEMENT - 2f)
            // lamps, trees and benches stand at the back of the pavement: stepping round them, or stopped by them
            GameState.avoid(s.furniture, before, laneBefore, dt)
            GameState.settle()
            // the camera stops where the street's built ends reach the screen's edges (easing into them); the player walks
            // on to the end. It looks ahead of a moving player (more the faster; it stays put when they stop), by whole
            // pixels so the player never flickers
            val half = GameState.halfView
            val lo = s.start + half; val hi = s.end - half
            GameState.camera.step(dt, GameState.vx, 2f * half)
            val cx = GameState.drawnX() + GameState.camera.snapped(GameState.unit)
            val unit = GameState.unit
            val view = if (half <= 0f) cx else CameraLead.withinEnds(cx, lo, hi).let { if (unit > 0f) kotlin.math.round(it * unit) / unit else it }
            // the player redraws every frame they change, also when the camera stands still at an end (the street's draw
            // otherwise follows only the camera and the ~30 Hz clock)
            val changed = GameState.walking || walkingBefore != GameState.walking || leanBefore != GameState.leanPixels ||
                facingBefore != GameState.facing || dirBefore != GameState.dir || poseBefore != GameState.runPose
            if (changed) playerFrame++
            GameState.inMotion = changed || view != scroll.value
            scroll.snapTo(view)
            // at the corner: in the mouth of the side street where it opens off this street, else at the junction end
            GameState.atCorner = if (cr != null) (if (GameState.street == 0) cr.inMouth(GameState.x) else cr.atCrossStart(GameState.x))
                else if (!jx.isNaN()) kotlin.math.abs(GameState.x - jx) < 95f
                else if (GameState.cornerAtEnd) GameState.x > s2.end - 110f else GameState.x < s2.start + 110f
            // the shop stays named across the gaps between buildings (no blinking off between them); cleared past the ends
            val at = s2.lefts.indices.firstOrNull { GameState.x >= s2.lefts[it] && GameState.x <= s2.lefts[it] + (lays.getOrNull(it)?.lot ?: B.W) }
            if (at != null) GameState.nearest = at
            else if (s2.lefts.isEmpty() || GameState.x < s2.lefts.first() || GameState.x > s2.lefts.last() + B.W) GameState.nearest = -1
        }
    }

    val rest = remember { BuildingMotion() }
    val lively = remember { BuildingMotion() }
    val fade = remember { FadeBrush() }

    Canvas(
        Modifier.fillMaxSize()
            .semantics {
                contentDescription = describe
                customActions = talkBack
            }
            .pointerInput(stacks, focus, zoomedOut, still, front?.maxPan, pan, street, GameState.enabled) {
                if (GameState.enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val startAt = scroll.value
                    val unit = frame().unit
                    var total = Offset.Zero
                    var axis = 0 // 1 sideways (the street), 2 up and down (the camera), 3 a pinch
                    var lifted = false
                    var spread0 = 0f
                    var spread = 0f
                    var centred = street.centred(startAt)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // a second finger: a pinch zooms the street in or out
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (axis != 3) {
                                axis = 3
                                spread0 = d
                            }
                            spread = d
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp() || !change.pressed) {
                            lifted = true
                            break
                        }
                        if (axis == 3) continue
                        total += change.positionChange()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (
                            axis == 0 &&
                                total.getDistance() > viewConfiguration.touchSlop &&
                                !zoomedOut &&
                                stacks.isNotEmpty()
                        )
                            axis = if (abs(total.x) > abs(total.y)) 1 else 2
                        if (axis == 1) {
                            change.consume()
                            dragging = true
                            val at = street.rubberBand(startAt - total.x / unit)
                            scope.launch { scroll.snapTo(at) }
                            // a light tick as each building passes the middle
                            val now = street.centred(at)
                            if (now != centred) {
                                centred = now
                                actions.feel(Feel.TICK)
                            }
                        } else if (axis == 2 && (front?.maxPan ?: 0f) > 0f) {
                            change.consume()
                            panned = true
                            val to = pan.value + change.positionChange().y / density.density
                            scope.launch { pan.snapTo(to) }
                        }
                    }
                    val velocity = tracker.calculateVelocity()
                    when (axis) {
                        0 ->
                            if (lifted)
                                tap(frame(), arts, stacks, zoomedOut, down.position, actions)
                        1 -> {
                            val speed = if (lifted) -velocity.x / unit else 0f
                            val target = street.settle(scroll.value, speed)
                            val id = stacks.getOrNull(target)?.id
                            if (id != null && target != focus) actions.swiped(id)
                            val to = street.centers.getOrElse(target) { scroll.value }
                            scope.launch {
                                try {
                                    if (still) scroll.snapTo(to)
                                    else
                                        scroll.animateTo(
                                            to,
                                            spring(dampingRatio = .8f, stiffness = 140f),
                                            initialVelocity = speed,
                                        )
                                } finally {
                                    dragging = false
                                }
                            }
                        }
                        2 ->
                            if (lifted && !still && (front?.maxPan ?: 0f) > 0f)
                                scope.launch {
                                    pan.animateDecay(
                                        velocity.y / density.density,
                                        exponentialDecay(frictionMultiplier = 2.2f),
                                    )
                                }
                        3 ->
                            if (spread0 > 0f) {
                                val ratio = spread / spread0
                                if (ratio < PINCH_OUT && !zoomedOut && stacks.isNotEmpty())
                                    actions.zoomed(true)
                                else if (ratio > PINCH_IN && zoomedOut) actions.zoomed(false)
                            }
                    }
                }
            }
    ) {
        if (this.size.width <= 0f) return@Canvas
        // read, so the player's every step redraws (see the game loop above)
        @Suppress("UNUSED_VARIABLE") val player = playerFrame
        if (moves < 0) return@Canvas
        val f = frame()
        // the crossroads' street the player is on (the walk round the corner swaps it within a frame)
        val cs = side()
        val layouts = cs?.layouts ?: layouts
        val arts = cs?.arts ?: arts
        val street = cs?.street ?: street
        val cameras = cs?.cameras ?: cameras
        val cr = crossroads
        if (GameState.enabled && f.unit > 0f) {
            GameState.halfView = size.width / 2f / f.unit
            GameState.viewMiddle = f.middle
            GameState.unit = f.unit
            GameState.groundPx = f.ground
        }
        val d = density.density
        val horizon = f.ground - HORIZON_DP * d * f.scale.coerceAtMost(1f)
        if (GameState.enabled) { GameState.frameGround = f.ground; GameState.frameHorizon = horizon; GameState.framePx = SpriteArt.PIXEL * f.unit }
        val skyOnly = StageTuning.skyOnly
        // the sun or the moon stands beside the building in front
        // (at a crossroads by the street's fixed scale: one sun for both streets, wherever the player stands)
        val column = (B.W / 2f + B.DX) * (if (cr != null) f.scale else f.camera.scale) + 14f
        val orb =
            orbSpot(p.hour, this.size.width / d, window.windowTop, horizon / d, column, f.zoom)
        // the crossroads' camera: the street's own view, or as far round the corner as the walk has turned it; the sky
        // turns with it, the sun and the moon too (a quarter turn takes them out of the cross street's view)
        if (cr != null) aimWorld(world, cr, sides!!, f.middle, f.ground, horizon, f.unit, Perspective.k(f.zoom), this.size.width, orb.first * d)
        // (the sky settles a little before the street at either end of a walk round the corner: CornerWalk.skyYaw)
        val yawPx = if (cr != null) world.cam.f * (if (CornerWalk.active) CornerWalk.skyYaw else world.cam.yaw) else 0f
        val orbX = orb.first * d + yawPx
        // the trees are lit from the side the sun or the moon stands on; the light stands where the
        // orb is drawn, so shadows swing and facades turn to it as the street scrolls under it
        painter.lightFrom = if (orbX < this.size.width / 2f) -1f else 1f
        painter.light = light
        painter.lightX = f.streetX(orbX)
        painter.lightSpan = this.size.width / f.unit
        painter.viewX = f.middle
        painter.unitPx = f.unit
        painter.perspectiveK = Perspective.k(f.zoom)
        painter.lamps = street.furniture
        painter.weather = weather
        backdrop.cloudiness = weather.cloudiness
        backdrop.mist = weather.mist
        backdrop.street = f.ground
        backdrop.season = season
        painter.season = season
        painter.clock(p.hour, seconds)
        streetLife.light = light
        streetLife.lightX = painter.lightX
        streetLife.lightSpan = painter.lightSpan
        streetLife.lightFrom = painter.lightFrom
        streetLife.update(seconds, seed, street)
        if (cr != null) for (sd in sides!!) {
            val life = sd.life ?: continue
            life.light = light
            life.lightSpan = painter.lightSpan
            life.lightX = sd.lightX
            life.lightFrom = sd.lightFrom
            life.withPlayer = sd.k == CornerWalk.playerStreet()
            life.turn = (if (sd.k == 0) 0f else (Math.PI / 2).toFloat()) - world.cam.yaw
            life.update(seconds, seed, sd.street)
        }
        // a 3D renderer drawing the street takes this frame's crossroads as the stage would draw them (once its people
        // have moved), and draws it now, in the same frame as the sky under it
        if (cr != null && Stage3D.listening) {
            Stage3D.frame.fill(world, cr, sides!!, sky, light, p.hour, seconds, this.size.width, this.size.height, f.ground, horizon, f.unit)
            Stage3D.drawn()
        }
        val from = f.streetX(0f) - B.GAP
        val to = f.streetX(this.size.width) + B.GAP
        // the tallest building in view: it decides how high the clouds hang (clear of its roof and
        // the "add a floor" ghost on it) and whether the street must fade out under the heading
        var reach = 0f
        for (i in layouts.indices) {
            val l = street.lefts[i]
            if (l + layouts[i].lot < from || l > to) continue
            reach = max(reach, layouts[i].height)
        }
        if (cr != null) {
            // everything the crossroads shows, the side street's tall buildings beyond the roofs too
            world.measure(cr, sides!!, this.size.width, f.ground)
            reach = if (world.topY == Float.MAX_VALUE) 0f else (f.ground - world.topY) / f.unit
        }
        backdrop.sunClear = if (cr != null && world.nearRest >= 0) sides!![world.nearRest].let { rs ->
            val half = this.size.width / 2f / f.unit
            sunClear(orbX, orb.second * d, rs.layouts, rs.street, world.nearOrigin(rs.viewX), f.ground, f.unit, rs.viewX - half - B.GAP, rs.viewX + half + B.GAP, d) {
                Perspective.shift(Perspective.sideAt(it, rs.viewX, this.size.width / f.unit, Perspective.k(f.zoom)))
            }
        } else if (cr != null) 1f else sunClear(orbX, orb.second * d, layouts, street, f, from, to, d)
        // the clouds hang under one ceiling for the whole street: the highest any building's roof
        // (with the "add a floor" outline on it) is drawn when it stands in front, so scrolling
        // from
        // a tall building to a short one never moves the sky. Zoomed out, the street's own view.
        var still = window.ground * d
        for (i in layouts.indices) still =
            min(still, window.ground * d - layouts[i].height * cameras[i].scale * d)
        // (at a crossroads one ceiling for both streets, so turning the corner never moves the sky)
        if (cr != null) for (sd in sides!!) for (i in sd.layouts.indices) still =
            min(still, window.ground * d - sd.layouts[i].height * sd.cameras[i].scale * d)
        // but the sky always keeps a band for them: a street with one very tall tower would
        // otherwise have none at all, and a tall building simply stands in front of its clouds
        still = max(still, window.windowTop * d + CLOUD_BAND * d)
        val ceiling =
            if (layouts.isEmpty()) f.ground - reach * f.unit
            else lerp(still, f.ground - reach * f.unit, f.zoom)
        // (not in the game: its street fills the screen with no heading over it, and a tall building simply runs off the
        // top, as it would through a camera, instead of fading into the sky with the clouds showing through it)
        val fades = !GameState.enabled && layouts.isNotEmpty() && f.ground - reach * f.unit < f.top + TOP_FADE * d
        // the buildings' outlines round the sun, for its glare where it peeks past an edge: none
        // over the heading, nor where the fade under it has rubbed them out (half way down it)
        if (skyOnly) sunGlare.edge.clear()
        else if (cr != null && world.nearRest < 0) with(world) { edges(cr, sides!!, sunGlare.edge, this@Canvas.size.width, own = -1) }
        else if (cr != null) {
            // the street in (or all but in) its own view by its own frame (at the walk's ends, the one it starts from or
            // hands over to), the rest of the crossroads through the camera
            val rs = sides!![world.nearRest]
            val half = this.size.width / 2f / f.unit
            sunEdge(sunGlare.edge, rs.layouts, rs.arts, rs.street, world.nearOrigin(rs.viewX), f.ground, f.unit, rs.viewX - half - B.GAP, rs.viewX + half + B.GAP) {
                Perspective.shift(Perspective.sideAt(it, rs.viewX, this.size.width / f.unit, Perspective.k(f.zoom)))
            }
            with(world) { edges(cr, sides, sunGlare.edge, this@Canvas.size.width, own = world.nearRest) }
        } else sunEdge(sunGlare.edge, layouts, arts, street, f, from, to)
        sunGlare.edge.ceiling = if (fades) f.top + TOP_FADE * d * .5f else f.top
        val sunR = SunMoonSprites.SUN_R * d
        val sunAt = Offset(orbX, orb.second * d)
        sunGlare.edge.measure(sunAt.x, sunAt.y, sunR)
        // how far the camera looks into the sun (the cloud across it as last drawn); while the camera swings round the
        // corner the sun's glow fades over a good part of the swing as the sun leaves the picture (or grows as it comes
        // in), not in the few frames the sun takes to cross its own glow, and is the street's own again at either end
        val swing = if (cr != null && CornerWalk.active) CornerWalk.ease.let { e -> smoothUnit(e / .05f) * smoothUnit((1f - e) / .05f) } else 0f
        backlight.update(
            sky,
            orbAt(p.hour).second,
            weather.cloudiness,
            backdrop.sunCover,
            weather.mist,
            sunAt,
            sunR,
            sunGlare.edge,
            f.zoom,
            this.size.width,
            this.size.height,
            seconds,
            reach = lerp(sunR * 3f, this.size.width * SWING_GLOW, swing),
        )
        backdrop.backlit = backlight.amount
        backdrop.yaw = yawPx
        with(backdrop) {
            backdrop(
                p.hour,
                sky,
                horizon,
                f.skyTop,
                Offset(orbX, orb.second * d),
                ceiling,
                if (cr != null && CornerWalk.active) lerp(CornerWalk.fromMiddle, CornerWalk.toMiddle, CornerWalk.skyEase)
                else f.middle + (if (skyOnly) GameState.skyPan else 0f),
                seconds,
            )
        }
        // shooting into a low sun: the sky warmed and blown out round it
        with(backlight) { sky(sky, sunAt, horizon) }
        // the street in a layer of its own when the top fades out under the heading, or when it
        // is exposed for the sky behind it
        // the street in its own layer also when the weather lays a tone over it (only over the street, not the sky)
        val weatherTone = weather.cloudiness > .05f || weather.rain > .05f || weather.mist > .05f
        val layered = fades || backlight.amount > 0f || weatherTone
        // (the corner turn draws its own street over the sky: then there is no street here)
        if (!skyOnly) {
        if (layered)
            drawIntoCanvas { it.saveLayer(Rect(0f, 0f, this.size.width, this.size.height), layer) }
        clipRect(0f, f.top, this.size.width, this.size.height) {
            if (cr != null) {
                // the crossroads: both streets through the one camera
                val sd = sides!!
                val depth = (this@Canvas.size.height - f.ground) / f.unit + 4f
                with(world) {
                    draw(cr, sd, painter, sky, light, seconds, live, accent, f.scale > .55f, Silhouettes.lod(f.scale), from, to, depth) { k, i ->
                        if (k == GameState.street && i == focus) motion.now(seconds, lively) else rest.still(seconds)
                    }
                }
                // what falls and blows through the air: in the frame of the street in view (mid-turn the nearer one's,
                // turned with the camera)
                val near = if (world.restStreet >= 0) world.restStreet else if (world.cam.yaw < (Math.PI / 4).toFloat()) 0 else 1
                val ns = sd[near]
                val turnedBy = world.cam.f * (world.cam.yaw - (if (near == 0) 0f else (Math.PI / 2).toFloat()))
                val nox = kotlin.math.round(this@Canvas.size.width / 2f - ns.viewX * f.unit) + turnedBy
                val nfrom = ns.viewX - this@Canvas.size.width / 2f / f.unit - B.GAP
                val nto = ns.viewX + this@Canvas.size.width / 2f / f.unit + B.GAP
                withTransform({
                    translate(nox, kotlin.math.round(f.ground))
                    scale(f.unit, f.unit, Offset.Zero)
                }) {
                    with(painter) {
                        with(seasonFall) { particles(season, ns.layouts, ns.street, nfrom, nto, (f.top - f.ground) / f.unit, depth, seconds, sky) }
                        // (a building fading out between the camera and the corner takes its reflection with it, and
                        // the mirror, laid flat in the street's own frame, fades as the street leaves its own look)
                        mirrored = if (world.restStreet < 0) { i -> world.shown(cr, near, i) } else null
                        mirrorShare = if (world.restStreet < 0) (if (near == 0) world.leanMain else world.leanCross) else 1f
                        weather(sky, ns.layouts, ns.arts, ns.street, live, nfrom, nto, (f.top - f.ground) / f.unit, depth, seconds)
                        mirrored = null
                        mirrorShare = 1f
                    }
                }
                // exposed for the sky: the street dark in front of it (our buildings keep no lights to give back)
                if (backlight.amount > 0f) with(backlight) { expose(sky) }
                return@clipRect
            }
            // where the side street opens: its own view, through the gap between the corner buildings (they are drawn over
            // its edges), from the camera's place along the street
            val jv = StageTuning.junctionView
            val j = layouts.indexOfFirst { it.stackId == com.lifemax.rpg.feature.habits.domain.JUNCTION_ID }
            if (jv != null && j >= 0) {
                val mid = street.lefts[j] + layouts[j].lot / 2f
                val l = f.screenX(street.lefts[j] - B.GAP); val r = f.screenX(street.lefts[j] + layouts[j].lot + B.GAP)
                if (r > 0f && l < this.size.width) { if (System.getProperty("junctionNoClip") != null) jv((f.middle - mid) * SpriteArt.SRC_W / B.W) else clipRect(l, 0f, r, f.ground + 1f) { jv((f.middle - mid) * SpriteArt.SRC_W / B.W) } }
            }
            withTransform({
                translate(kotlin.math.round(this@Canvas.size.width / 2f - f.middle * f.unit), kotlin.math.round(f.ground))
                scale(f.unit, f.unit, Offset.Zero)
            }) {
                with(painter) {
                    val depth = (this@Canvas.size.height - f.ground) / f.unit + 4f
                    pavement(sky, from, to, depth)
                    pools(street.furniture, from, to, sky.dark)
                    shadows(light, sky, layouts, street, from, to, depth, seconds)
                    with(streetLife) { lampShadows(sky.dark, street.furniture, from, to) }
                    val detailed = f.scale > .55f
                    for (i in layouts.indices) {
                        val l = street.lefts[i]
                        if (l + layouts[i].lot + B.DX < from || l - 20f > to) continue
                        val motion =
                            if (i == focus) motion.now(seconds, lively) else rest.still(seconds)
                        translate(street.centers[i], 0f) {
                            building(
                                arts[i],
                                motion,
                                ui.selected.takeIf { i == focus && f.zoom == 0f },
                                accent,
                                live,
                                detailed,
                                sky,
                                Silhouettes.lod(f.scale),
                                street.centers[i],
                            )
                        }
                    }
                    lampAir(street.furniture, sky, from, to, seconds)
                    with(streetLife) { sweep(sky, layouts, street, from, to, seconds) }
                    // the lamps, trees and benches stand at the back of the pavement; the people
                    // walk along its front, so they pass in front of them
                    for (k in street.furniture.indices) {
                        val item = street.furniture[k]
                        if (item.x < from - 30f || item.x > to + 30f) continue
                        furniture(item, sky, seconds)
                    }
                    with(streetLife) {
                        people(sky, street.furniture, from, to, Silhouettes.lod(f.scale))
                    }
                    with(streetLife) { car(sky, from, to) }
                    with(seasonFall) {
                        particles(
                            season,
                            layouts,
                            street,
                            from,
                            to,
                            (f.top - f.ground) / f.unit,
                            depth,
                            seconds,
                            sky,
                        )
                    }
                    if (layouts.isEmpty()) emptyLot(sky)
                    weather(
                        sky,
                        layouts,
                        arts,
                        street,
                        live,
                        from,
                        to,
                        (f.top - f.ground) / f.unit,
                        depth,
                        seconds,
                    )
                }
            }
            // (the engine's cloud shadows are sized for its own buildings: over ours they show as pale boxes above the roofs)
            if (SpriteArt.buildings.isEmpty()) cloudShade(light, backdrop, layouts, street, f, horizon, d, shadePath, shadeBrush)
            if (backlight.amount > 0f) {
                // exposed for the sky: the street dark in front of it, then its lights given back
                with(backlight) { expose(sky) }
                withTransform({
                    translate(kotlin.math.round(this@Canvas.size.width / 2f - f.middle * f.unit), kotlin.math.round(f.ground))
                    scale(f.unit, f.unit, Offset.Zero)
                }) {
                    with(painter) {
                        val detailed = f.scale > .55f
                        for (i in layouts.indices) {
                            val l = street.lefts[i]
                            if (l + layouts[i].lot + B.DX < from || l - 20f > to) continue
                            translate(street.centers[i], 0f) {
                                relight(
                                    arts[i],
                                    if (i == focus) lively else rest.still(seconds),
                                    ui.selected.takeIf { i == focus && f.zoom == 0f },
                                    accent,
                                    live,
                                    detailed,
                                    sky,
                                    backlight,
                                    f.screenX(street.centers[i]),
                                    f.ground,
                                    f.unit,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (weatherTone) {
            // under cloud and rain the whole street sits darker and cooler (the sprites otherwise keep their sunny colours);
            // in mist the street fades into a pale veil, thickest near the ground
            val dim = (.28f * weather.cloudiness + .14f * weather.rain).coerceAtMost(.42f)
            if (dim > .01f) drawRect(Color(0xFF232a38), Offset.Zero, this.size, alpha = dim, blendMode = BlendMode.SrcAtop)
            if (weather.mist > .01f) {
                // the fog takes the light there is: pale by day, a dark blue-grey at night
                val fog = androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.lerp(sky.sky[2], Color.White, .55f), Color(0xFF2a3446), sky.dark * .8f)
                // drifting banks on the GPU (a lighter even veil under them), else the even veil alone
                val drift = StageFx.get(RainFx.FOG)
                if (drift != null) {
                    drift.set("time", seconds); drift.set("mist", weather.mist); drift.set("offset", f.middle * f.unit)
                    drift.set("ground", f.ground); drift.set("top", f.top); drift.set("tint", fog.red, fog.green, fog.blue)
                    drawRect(drift.brush(), Offset(0f, f.top), Size(this.size.width, this.size.height - f.top), blendMode = BlendMode.SrcAtop)
                }
                drawRect(
                    Brush.verticalGradient(listOf(fog.copy(alpha = (if (drift != null) .08f else .12f) * weather.mist), fog.copy(alpha = (if (drift != null) .26f else .42f) * weather.mist)), f.top, f.ground),
                    Offset(0f, f.top), Size(this.size.width, this.size.height - f.top), blendMode = BlendMode.SrcAtop,
                )
            }
        }
        if (fades) {
            // rub the layer out above the window, and ever less over the fade under it
            val end = f.top + TOP_FADE * d
            drawRect(
                fade.brush(f.top, end),
                Offset.Zero,
                Size(this.size.width, end),
                blendMode = BlendMode.DstOut,
            )
        }
        if (layered) drawIntoCanvas { it.restore() }
        }
        // the near layer: a few dark, soft things between the camera and the street, sliding
        // faster than it
        if (StageTuning.foreground) with(foregroundPainter) {
            foreground(
                sky,
                street,
                seed,
                f.middle,
                f.unit,
                f.zoom,
                f.skyTop,
                f.top,
                f.ground,
                f.band,
                street.centers.getOrNull(focus)?.let { f.screenX(it) } ?: 0f,
                layouts.isNotEmpty(),
                seconds,
            )
        }
        // shooting into a low sun: the light over the silhouettes' edges, the warm haze, the
        // shafts and the dust in them
        with(backlight) { air(sky, sunAt, sunGlare.edge, seconds) }
        // the sun at a building's edge: its glare over the silhouette that hides part of it
        with(sunGlare) {
            glare(
                sky,
                sunAt,
                sunR,
                weather.cloudiness,
                backdrop.sunCover,
                weather.mist,
                f.skyTop,
                backlight.amount,
            )
        }
        // the finish: a very faint vignette (the corners a little deeper) and fine film grain over
        // the
        // whole picture, which also breaks up the banding of the sky's gradients
        drawRect(vignette.brush(this.size.width, this.size.height))
        drawRect(SceneSprites.grain, alpha = GRAIN)
        if (f.zoom > .02f && StageTuning.streetNames)
            for (i in layouts.indices) streetName(
                names[i],
                f.screenX(street.centers[i]),
                f,
                sky.ink,
            )
    }
}

/**
 * How clear the sun at ([x], [y]) (px) is of the buildings in view: 0 behind one, 1 in the open,
 * fading over a short way at their edges ([LensFlare.clear]), for the flare.
 */
private fun sunClear(
    x: Float,
    y: Float,
    layouts: List<BuildingLayout>,
    street: StreetLayout,
    f: Frame,
    from: Float,
    to: Float,
    d: Float,
): Float = sunClear(x, y, layouts, street, f.size.width / 2f - f.middle * f.unit, f.ground, f.unit, from, to, d) { f.sideShift(it) }

/** [sunClear] for a street drawn with its origin at screen x [originX], its pavement at [ground], [u] px a unit. */
private fun sunClear(
    x: Float,
    y: Float,
    layouts: List<BuildingLayout>,
    street: StreetLayout,
    originX: Float,
    ground: Float,
    u: Float,
    from: Float,
    to: Float,
    d: Float,
    shiftOf: (Float) -> Float,
): Float {
    var clear = 1f
    for (i in layouts.indices) {
        val l = street.lefts[i]
        if (l + layouts[i].lot < from || l - B.DX > to) continue
        val cx = street.centers[i]
        val shift = shiftOf(cx)
        clear =
            min(
                clear,
                LensFlare.clear(
                    x,
                    y,
                    originX + (cx + Perspective.left(shift)) * u,
                    ground - layouts[i].height * u,
                    originX + (cx + Perspective.right(shift)) * u,
                    ground,
                    24f * d,
                ),
            )
        if (clear <= 0f) break
    }
    return clear
}

/** One view of a house, set again for each house [sunEdge] sweeps (drawing happens on one thread). */
private var edgeView: com.lifemax.rpg.feature.habits.domain.HouseView? = null
/** And of a villa or a landmark. */
private val modelEdgeView = com.lifemax.rpg.feature.habits.domain.ModelView()

/**
 * The buildings in view as [SunEdge] boxes on the screen, for the sun's glare at their edges: each
 * one's front (its lobby and storeys, then its cornice a little wider) swept along its shown side
 * and up by its roof's depth, and what stands on its roof: the water tank, the reward's sign board.
 */
private fun sunEdge(
    edge: SunEdge,
    layouts: List<BuildingLayout>,
    arts: List<BuildingArt>,
    street: StreetLayout,
    f: Frame,
    from: Float,
    to: Float,
) = sunEdge(edge, layouts, arts, street, f.size.width / 2f - f.middle * f.unit, f.ground, f.unit, from, to) { f.sideShift(it) }

/** [sunEdge] for a street drawn with its origin at screen x [originX], its pavement at [ground], [u] px a unit. */
private fun sunEdge(
    edge: SunEdge,
    layouts: List<BuildingLayout>,
    arts: List<BuildingArt>,
    street: StreetLayout,
    originX: Float,
    ground: Float,
    u: Float,
    from: Float,
    to: Float,
    shiftOf: (Float) -> Float,
) {
    edge.clear()
    val half = B.W / 2f
    for (i in layouts.indices) {
        val l = street.lefts[i]
        if (l + layouts[i].lot < from || l - B.DX > to) continue
        val layout = layouts[i]
        val art = arts.getOrNull(i) ?: continue
        val cx = street.centers[i]
        val s = shiftOf(cx) * u
        val dy = B.DY * u
        fun x(at: Float) = originX + (cx + at) * u
        fun y(up: Float) = ground - up * u
        val roof = layout.roof
        val sb = SpriteArt.buildings[art.stack.id]
        val house = sb?.house
        if (house != null) {
            // one of OUR houses, back in its yard: its wall swept back through its depth, and its roof up to the ridge
            val v = edgeView?.set(house.shape, shiftOf(cx)) ?: com.lifemax.rpg.feature.habits.domain.HouseView(house.shape, shiftOf(cx)).also { edgeView = it }
            val sh = house.shape
            fun hx(px: Float, dd: Float) = x(v.x(px, dd))
            fun hy(up: Float, dd: Float) = ground + v.y(up, dd) * u
            edge.box(hx(sh.wall0, 0f), hy(sh.h, 0f), hx(sh.wall1, 0f), hy(0f, 0f), v.lean(sh.depth) * u, v.lift(sh.depth) * u)
            val w = sb.front.width.toFloat()
            edge.box(hx(0f, sh.depth / 2f), hy(sh.ridge, sh.depth / 2f), hx(w, sh.depth / 2f), hy(sh.h, sh.depth / 2f))
            edge.box(hx(sh.flueX0, sh.flueD0), hy(sh.flueTop, sh.flueD0), hx(sh.flueX1, sh.flueD0), hy(sh.ridge, sh.flueD0))
            continue
        }
        val model = sb?.model
        if (model != null) {
            // a villa or a landmark, back in its yard: its walls swept back through their depth, and its roofs up to the
            // highest ridge, over the walls' span
            val v = modelEdgeView.set(model.shape, shiftOf(cx))
            val m = model.shape
            fun mx(px: Float, dd: Float) = x(v.x(px, dd))
            fun my(up: Float, dd: Float) = ground + v.y(up, dd) * u
            edge.box(mx(m.wall0, 0f), my(m.wallTop, 0f), mx(m.wall1, 0f), my(0f, 0f), v.lean(m.depth) * u, v.lift(m.depth) * u)
            if (m.ridge > m.wallTop) edge.box(mx(m.wall0, m.ridgeD), my(m.ridge, m.ridgeD), mx(m.wall1, m.ridgeD), my(m.wallTop, m.ridgeD))
            continue
        }
        if (sb != null) {
            // one of OUR buildings: its real silhouette (as spriteBuilding draws it), not the engine's cornice, tank and sign
            val k = B.W / SpriteArt.SRC_W
            val hf = sb.front.height * k
            edge.box(x(-half), y(hf), x(half), ground, s, if (sb.roof != null) dy else 0f)
            val over = sb.sign
            val bill = sb.prop
            if (over != null) edge.box(x(-half), y(hf + over.height * k), x(half), y(hf))
            else if (bill != null) {
                val w = 160f * k
                val left = -w / 2f + shiftOf(cx) * .5f
                val bottom = hf + B.DY * .5f
                edge.box(x(left), y(bottom + bill.height * k), x(left + w), y(bottom))
            }
            continue
        }
        edge.box(x(-half), y(roof), x(half), ground, s, dy)
        if (art.floors.isEmpty()) continue
        edge.box(x(-half - 3f), y(roof + B.CORNICE), x(half + 3f), y(roof), s, dy)
        if (art.craneOn == null) {
            // the water tank on its legs, over the roof's middle depth
            val tx = -half + 26f
            val base = roof + B.CORNICE + B.DY / 2f
            edge.box(x(tx - 8f), y(base + 26f), x(tx + 8f), y(base + 6f))
        }
        val sign = art.sign
        if (sign != null) {
            // its board (the legs under it let the light through)
            val w = sign.width / 2f
            val topUp = sign.bottom + sign.height
            edge.box(x(-w), y(topUp), x(w), y(topUp - 24f))
        }
    }
}

/**
 * The clouds' shadows crossing the street: under each cloud a soft band of shade down the buildings
 * and over the ground, standing to the side the light leans to and drifting with its cloud. Only
 * the sun's clouds shade the street; the band is in [brush], a unit wide, drawn under a transform.
 */
private fun DrawScope.cloudShade(
    light: KeyLight,
    backdrop: Backdrop,
    layouts: List<BuildingLayout>,
    street: StreetLayout,
    f: Frame,
    horizon: Float,
    d: Float,
    path: Path,
    brush: Brush,
) {
    val deep = SceneLight.cloudShade(light)
    if (deep < .005f || backdrop.shadeN == 0) return
    val w = size.width
    path.rewind()
    path.addRect(Rect(0f, horizon, w, size.height))
    for (i in layouts.indices) {
        val cx = street.centers[i]
        val shift = f.sideShift(cx)
        val x0 = f.screenX(cx + Perspective.left(shift))
        val x1 = f.screenX(cx + Perspective.right(shift))
        if (x1 < 0f || x0 > w) continue
        val top = f.ground - (layouts[i].roof + B.CORNICE + B.DY) * f.unit
        path.addRect(Rect(x0, top, x1, f.ground))
    }
    val drop = SceneLight.cloudDrop(light) * d
    clipPath(path) {
        for (i in 0 until backdrop.shadeN) {
            val x = backdrop.shadeX[i] + drop
            val half = backdrop.shadeW[i] * .55f
            if (x + half < 0f || x - half > w) continue
            withTransform({
                translate(x, 0f)
                scale(half, 1f, Offset.Zero)
            }) {
                drawRect(
                    brush,
                    Offset(-1f, f.top),
                    Size(2f, size.height - f.top),
                    alpha = deep * backdrop.shadeA[i],
                )
            }
        }
    }
}

/** A cloud's shadow across, soft at both sides, from -1 to 1. */
private fun cloudShadeBrush(): Brush {
    val shade = Color(0xFF0E1428)
    return Brush.horizontalGradient(
        0f to Color.Transparent,
        .3f to shade,
        .7f to shade,
        1f to Color.Transparent,
        startX = -1f,
        endX = 1f,
    )
}

/** The vignette: a radial brush for a view of this size, kept until the view changes size. */
private class VignetteBrush {
    private var w = -1f
    private var h = -1f
    private var brush: Brush? = null

    fun brush(width: Float, height: Float): Brush {
        val b = brush
        if (b != null && w == width && h == height) return b
        w = width
        h = height
        return Brush.radialGradient(
                0f to Color.Transparent,
                .6f to Color.Transparent,
                1f to Color(0x38000010),
                center = Offset(width / 2f, height * .46f),
                radius = kotlin.math.hypot(width / 2f, height / 2f) * 1.08f,
            )
            .also { brush = it }
    }
}

/** The band of sky (dp under the heading) the clouds always keep, however tall the street. */
private const val CLOUD_BAND = 150f

/** How strong the film grain is (alpha of the black and white specks of the tile). */
private const val GRAIN = .05f

/** The fade under the heading: its brush, kept until the window moves. */
private class FadeBrush {
    private var key0 = -1f
    private var key1 = -1f
    private var brush: Brush? = null

    fun brush(top: Float, end: Float): Brush {
        val b = brush
        if (b != null && key0 == top && key1 == end) return b
        key0 = top
        key1 = end
        return Brush.verticalGradient(
                0f to Color.Black,
                (top / end).coerceIn(0f, 1f) to Color.Black,
                1f to Color.Transparent,
                startY = 0f,
                endY = end,
            )
            .also { brush = it }
    }
}

/** Nothing built yet: the empty lot where the first foundation will go, fenced off. */
private fun DrawScope.emptyLot(sky: SkyColors) {
    val half = B.W / 2f
    drawRect(sky.ground[1], Offset(-half, -4f), Size(B.W, 4f))
    var x = -half
    while (x <= half) {
        drawRect(SceneInk.Scaffold, Offset(x, -22f), Size(1.6f, 22f), alpha = .8f)
        x += 12f
    }
    drawRect(SceneInk.Scaffold, Offset(-half, -18f), Size(B.W, 1.4f), alpha = .8f)
    drawRect(SceneInk.Scaffold, Offset(-half, -9f), Size(B.W, 1.4f), alpha = .8f)
}

/** A building's name under it on the zoomed-out street, fading in as the street zooms out. */
private fun DrawScope.streetName(words: TextLayoutResult, x: Float, f: Frame, ink: Color) {
    val d = f.density
    val scale = (f.unit / d * 1.4f).coerceIn(.62f, 1f) * d
    withTransform({
        translate(x, f.ground + (BuildingPainter.PAVEMENT + 6f) * f.unit)
        scale(scale, scale, Offset.Zero)
    }) {
        drawText(words, ink, Offset(-words.size.width / 2f, 0f), alpha = f.zoom)
    }
}

/**
 * A tap at [at]: a building of the zoomed-out street, a building beside the one in front, or a part
 * of the one in front.
 */
private fun tap(
    f: Frame,
    arts: List<BuildingArt>,
    stacks: List<HabitStack>,
    zoomedOut: Boolean,
    at: Offset,
    actions: StageActions,
) {
    if (stacks.isEmpty()) return
    val x = f.streetX(at.x)
    if (zoomedOut) {
        f.street.buildingAt(x, arts.map { it.layout })?.let { actions.chosen(stacks[it].id) }
        return
    }
    // nothing of a building shows in the heading's band
    if (at.y < f.top) return
    val y = (f.ground - at.y) / f.unit
    // the building in front first
    for (i in stacks.indices.sortedBy { abs(it - f.focus) }) {
        val l: BuildingLayout = arts[i].layout
        val cx = f.street.centers[i]
        val hit = l.hit(x - cx, y, f.sideShift(cx)) ?: continue
        if (i == f.focus) actions.tapped(stacks[i].id, hit) else actions.chosen(stacks[i].id)
        return
    }
}

/** How far above the pavement line the horizon is drawn, at full size. */
private const val HORIZON_DP = 30f

/** The street's own clock ticks no faster than this (about 30 a second). */
private const val TICK_NANOS = 33_000_000L

/**
 * A pinch closing to this share of its start zooms out to the street; opening past the other, back.
 */
private const val PINCH_OUT = .82f
private const val PINCH_IN = 1.2f

/**
 * Where the camera stands along street [s] for a player at [x] (street units) when it is not looking ahead of them: with
 * them, snapped to whole pixels, and easing to a stop where the street's built ends reach the screen's edges.
 */
internal fun restMiddle(s: StreetLayout, x: Float): Float {
    val half = GameState.halfView
    val cx = if (GameState.unit > 0f) kotlin.math.round(x * GameState.unit) / GameState.unit else x
    val lo = s.start + half
    val hi = s.end - half
    if (half <= 0f) return cx
    // as the live camera stands with no look-ahead (the game loop), easing into the street's ends, on whole pixels
    val v = CameraLead.withinEnds(cx, lo, hi)
    return if (GameState.unit > 0f) kotlin.math.round(v * GameState.unit) / GameState.unit else v
}

/** The middle of the view the stage really draws for [middle]: its street is moved by whole pixels. */
internal fun drawnMiddle(middle: Float): Float {
    val u = GameState.unit
    if (u <= 0f) return middle
    val half = GameState.halfView * u
    return (half - kotlin.math.round(half - middle * u)) / u
}

/** 0 to 1 eased (smoothstep) over 0..1 of [t]. */
private fun smoothUnit(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3f - 2f * u) }

/** Mid-turn the sun's glow fades as the sun goes this share of the picture's width past its edge. */
private const val SWING_GLOW = .5f

/** Sets the walk's two camera ends: the view the player is in now ([middle]), and the other street's own view where it ends. */
internal fun turnEnds(cr: com.lifemax.rpg.feature.habits.domain.Crossroads, main: StreetLayout, cross: StreetLayout, middle: Float) {
    CornerWalk.fromMiddle = middle
    if (CornerWalk.forward) {
        CornerWalk.toMiddle = restMiddle(cross, cr.arriveX)
        CornerWalk.fromX = drawnMiddle(middle) - cr.westEdge; CornerWalk.fromZ = 0f
        CornerWalk.toX = 0f; CornerWalk.toZ = drawnMiddle(CornerWalk.toMiddle) + cr.zOff
    } else {
        CornerWalk.toMiddle = restMiddle(main, cr.returnX)
        CornerWalk.fromX = 0f; CornerWalk.fromZ = drawnMiddle(middle) + cr.zOff
        CornerWalk.toX = drawnMiddle(CornerWalk.toMiddle) - cr.westEdge; CornerWalk.toZ = 0f
    }
}

/**
 * Aims [world]'s camera for this frame: at rest on the player's street (the view the stage draws, [middle] along it,
 * whole-pixel origin), or as far round the corner as the walk has turned it; and gives each street of the crossroads the
 * view and light it is drawn with (its own resting view, with the sun where that view sees it: [orb0] is its screen x
 * looking up the main street, the cross street's view turned a quarter from it).
 */
internal fun aimWorld(
    world: CornerWorld, cr: com.lifemax.rpg.feature.habits.domain.Crossroads, sides: List<CrossSide>,
    middle: Float, ground: Float, horizon: Float, unit: Float, perspectiveK: Float, width: Float, orb0: Float,
) {
    val cam = world.cam
    val k = GameState.street.coerceIn(0, 1)
    cam.cx = width / 2f
    cam.cy = horizon
    cam.unit = unit
    cam.r = TurnTuning.R
    cam.cutBack = TurnTuning.CUT
    val oy = kotlin.math.round(ground)
    cam.eye = (oy - horizon) / unit
    world.oy = oy
    world.perspectiveK = perspectiveK
    val quarter = (Math.PI / 2).toFloat()
    var restMid = middle
    if (CornerWalk.active) {
        val e = CornerWalk.ease
        cam.yaw = CornerWalk.yaw
        cam.tx = if (e <= 0f) CornerWalk.fromX else if (e >= 1f) CornerWalk.toX else lerp(CornerWalk.fromX, CornerWalk.toX, e)
        cam.tz = if (e <= 0f) CornerWalk.fromZ else if (e >= 1f) CornerWalk.toZ else lerp(CornerWalk.fromZ, CornerWalk.toZ, e)
        world.restStreet = if (e <= 0f) k else if (e >= 1f) 1 - k else -1
        restMid = if (e >= 1f) CornerWalk.toMiddle else CornerWalk.fromMiddle
    } else {
        cam.yaw = if (k == 0) 0f else quarter
        val m = drawnMiddle(middle)
        if (k == 0) { cam.tx = m - cr.westEdge; cam.tz = 0f } else { cam.tx = 0f; cam.tz = m + cr.zOff }
        world.restStreet = k
    }
    // the cut keeps clear of the player (and what stands round them) while they walk round the corner
    cam.keepBack = if (CornerWalk.active) TurnTuning.KEEP else 0f
    cam.keepX = CornerWalk.wx
    cam.keepZ = CornerWalk.wz
    world.leanMain = CornerWalk.leanMain(cam.yaw)
    world.leanCross = CornerWalk.leanCross(cam.yaw)
    world.ox = kotlin.math.round(width / 2f - restMid * unit)
    world.from = restMid - width / 2f / unit - com.lifemax.rpg.feature.habits.domain.BuildingSpec.GAP
    world.to = restMid + width / 2f / unit + com.lifemax.rpg.feature.habits.domain.BuildingSpec.GAP
    cam.update()
    // each street's own view: the one the player is on (or walks from) where it is, the other where the walk would end
    // (the street walked away from eases from where the walk began to the view it keeps while the other is in view, so
    // its light is the same at the walk's end as once the other street has taken over)
    val away0 = restMiddle(sides[0].street, cr.returnX)
    val away1 = restMiddle(sides[1].street, cr.arriveX)
    for (s in sides) {
        val st = s.k.coerceAtMost(1)
        val away = if (st == 0) away0 else away1
        val mid = when {
            CornerWalk.active && st == k -> { val e = CornerWalk.ease; if (e <= 0f) CornerWalk.fromMiddle else if (e >= 1f) away else lerp(CornerWalk.fromMiddle, away, e) }
            CornerWalk.active -> CornerWalk.toMiddle
            st == k -> middle
            else -> away
        }
        s.viewX = mid
        val orbX = orb0 + cam.f * (if (st == 0) 0f else quarter)
        s.lightX = mid + (orbX - width / 2f) / unit
        s.lightFrom = if (orbX < width / 2f) -1f else 1f
    }
    world.crossView = sides[1].viewX
}
