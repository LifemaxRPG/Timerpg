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
    val ground: Float = window.ground * density - lift + camera.pan * (1f - zoom) * density

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
    // shows (the finger and the plays redraw on their own), and stands still otherwise
    var seconds by remember { mutableFloatStateOf(ambientAt ?: 0f) }
    LaunchedEffect(visible, still, ambientAt) {
        if (ambientAt != null) seconds = ambientAt
        else if (visible && !still) {
            val start = withFrameNanos { it }
            var last = 0L
            while (true) withFrameNanos {
                if (it - last >= TICK_NANOS) {
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

    fun frame() =
        Frame(
            size,
            density.density,
            window,
            street,
            cameras,
            view,
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
    val streetNow = rememberUpdatedState(street)
    LaunchedEffect(GameState.enabled) {
        if (!GameState.enabled) return@LaunchedEffect
        var last = 0L
        while (true) {
            val t = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(.1f)
            last = t
            val s = streetNow.value
            val before = GameState.x
            GameState.step(dt, s.start, s.end, BuildingPainter.PAVEMENT - 2f)
            // lamps, trees and benches stand at the back of the pavement (lane 0): walk round them (a step out), not through them
            if (GameState.lane < 6.5f) {
                for (f in s.furniture) {
                    val r = when (f.kind) { FurnitureKind.TREE -> 6f; FurnitureKind.BENCH -> 24f; else -> 4f }
                    if ((before <= f.x - r && GameState.x > f.x - r) || (before >= f.x + r && GameState.x < f.x + r) ||
                        (kotlin.math.abs(before - f.x) < r && kotlin.math.abs(GameState.x - f.x) < r)) {
                        GameState.x = if (before < f.x) f.x - r else f.x + r
                        break
                    }
                }
            }
            scroll.snapTo(GameState.x)
            GameState.nearest = s.lefts.indices.firstOrNull { GameState.x >= s.lefts[it] && GameState.x <= s.lefts[it] + B.W } ?: -1
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
        val f = frame()
        val d = density.density
        val horizon = f.ground - HORIZON_DP * d * f.scale.coerceAtMost(1f)
        // the sun or the moon stands beside the building in front
        val column = (B.W / 2f + B.DX) * f.camera.scale + 14f
        val orb =
            orbSpot(p.hour, this.size.width / d, window.windowTop, horizon / d, column, f.zoom)
        // the trees are lit from the side the sun or the moon stands on; the light stands where the
        // orb is drawn, so shadows swing and facades turn to it as the street scrolls under it
        painter.lightFrom = if (orb.first * d < this.size.width / 2f) -1f else 1f
        painter.light = light
        painter.lightX = f.streetX(orb.first * d)
        painter.lightSpan = this.size.width / f.unit
        painter.viewX = f.middle
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
        backdrop.sunClear = sunClear(orb.first * d, orb.second * d, layouts, street, f, from, to, d)
        // the clouds hang under one ceiling for the whole street: the highest any building's roof
        // (with the "add a floor" outline on it) is drawn when it stands in front, so scrolling
        // from
        // a tall building to a short one never moves the sky. Zoomed out, the street's own view.
        var still = window.ground * d
        for (i in layouts.indices) still =
            min(still, window.ground * d - layouts[i].height * cameras[i].scale * d)
        // but the sky always keeps a band for them: a street with one very tall tower would
        // otherwise have none at all, and a tall building simply stands in front of its clouds
        still = max(still, window.windowTop * d + CLOUD_BAND * d)
        val ceiling =
            if (layouts.isEmpty()) f.ground - reach * f.unit
            else lerp(still, f.ground - reach * f.unit, f.zoom)
        val fades = layouts.isNotEmpty() && f.ground - reach * f.unit < f.top + TOP_FADE * d
        // the buildings' outlines round the sun, for its glare where it peeks past an edge: none
        // over the heading, nor where the fade under it has rubbed them out (half way down it)
        sunEdge(sunGlare.edge, layouts, arts, street, f, from, to)
        sunGlare.edge.ceiling = if (fades) f.top + TOP_FADE * d * .5f else f.top
        val sunR = SunMoonSprites.SUN_R * d
        val sunAt = Offset(orb.first * d, orb.second * d)
        sunGlare.edge.measure(sunAt.x, sunAt.y, sunR)
        // how far the camera looks into the sun (the cloud across it as last drawn)
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
        )
        backdrop.backlit = backlight.amount
        with(backdrop) {
            backdrop(
                p.hour,
                sky,
                horizon,
                f.skyTop,
                Offset(orb.first * d, orb.second * d),
                ceiling,
                f.middle,
                seconds,
            )
        }
        // shooting into a low sun: the sky warmed and blown out round it
        with(backlight) { sky(sky, sunAt, horizon) }
        // the street in a layer of its own when the top fades out under the heading, or when it
        // is exposed for the sky behind it
        val layered = fades || backlight.amount > 0f
        if (layered)
            drawIntoCanvas { it.saveLayer(Rect(0f, 0f, this.size.width, this.size.height), layer) }
        clipRect(0f, f.top, this.size.width, this.size.height) {
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
): Float {
    var clear = 1f
    for (i in layouts.indices) {
        val l = street.lefts[i]
        if (l + layouts[i].lot < from || l - B.DX > to) continue
        val cx = street.centers[i]
        val shift = f.sideShift(cx)
        clear =
            min(
                clear,
                LensFlare.clear(
                    x,
                    y,
                    f.screenX(cx + Perspective.left(shift)),
                    f.ground - layouts[i].height * f.unit,
                    f.screenX(cx + Perspective.right(shift)),
                    f.ground,
                    24f * d,
                ),
            )
        if (clear <= 0f) break
    }
    return clear
}

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
) {
    edge.clear()
    val u = f.unit
    val half = B.W / 2f
    for (i in layouts.indices) {
        val l = street.lefts[i]
        if (l + layouts[i].lot < from || l - B.DX > to) continue
        val layout = layouts[i]
        val art = arts.getOrNull(i) ?: continue
        val cx = street.centers[i]
        val s = f.sideShift(cx) * u
        val dy = B.DY * u
        fun x(at: Float) = f.screenX(cx + at)
        fun y(up: Float) = f.ground - up * u
        val roof = layout.roof
        edge.box(x(-half), y(roof), x(half), f.ground, s, dy)
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
