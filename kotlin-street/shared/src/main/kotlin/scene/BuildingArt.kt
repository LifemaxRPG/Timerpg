package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.lifemax.rpg.feature.habits.domain.BuildingLayout
import com.lifemax.rpg.feature.habits.domain.BuildingSpec as B
import com.lifemax.rpg.feature.habits.domain.Dice
import com.lifemax.rpg.feature.habits.domain.HabitBlock
import com.lifemax.rpg.feature.habits.domain.HabitMaterial
import com.lifemax.rpg.feature.habits.domain.HabitStack
import com.lifemax.rpg.feature.habits.domain.Occupancy
import com.lifemax.rpg.feature.habits.domain.Piece
import com.lifemax.rpg.feature.habits.domain.PieceKind
import com.lifemax.rpg.feature.habits.domain.RuinWindow
import com.lifemax.rpg.feature.habits.domain.Silhouettes
import com.lifemax.rpg.feature.habits.domain.WindowCast
import com.lifemax.rpg.feature.habits.domain.floorWindows
import com.lifemax.rpg.feature.habits.domain.lightOrder
import com.lifemax.rpg.feature.habits.domain.occupancy
import com.lifemax.rpg.feature.habits.domain.ruinWindow
import com.lifemax.rpg.feature.habits.presentation.HabitsCopy
import kotlin.math.abs

/**
 * Words measured for the scene. A building is drawn in its own units (dp at scale 1) under one
 * canvas transform, so its words are measured once at that size (a density of 1: a 12 sp name is 12
 * units tall) and scale with the building, instead of being measured again at every frame of a
 * zoom. The owner's text size counts up to [MAX_FONT_SCALE]: a floor is only so big.
 */
internal class SceneText(private val measurer: TextMeasurer, fontScale: Float) {
    private val density = Density(1f, fontScale.coerceIn(1f, MAX_FONT_SCALE))

    fun layout(
        text: String,
        size: Float,
        weight: FontWeight,
        maxWidth: Float,
        maxLines: Int = 1,
        spacing: Float = 0f,
    ): TextLayoutResult =
        measurer.measure(
            text,
            TextStyle(
                fontSize = size.sp,
                fontWeight = weight,
                letterSpacing = spacing.sp,
                lineHeight = (size * 1.18f).sp,
            ),
            overflow = TextOverflow.Ellipsis,
            softWrap = maxLines > 1,
            maxLines = maxLines,
            constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)),
            density = density,
        )

    private companion object {
        const val MAX_FONT_SCALE = 1.25f
    }
}

/**
 * The shapes of a [Shapes] as paths, one to fill and one to stroke per [DetailInk], null where the
 * ink has nothing.
 */
internal class InkPaths(val fill: Array<Path?>, val stroke: Array<Path?>) {
    companion object {
        fun of(s: Shapes): InkPaths {
            val n = DetailInk.entries.size
            val fill = arrayOfNulls<Path>(n)
            val stroke = arrayOfNulls<Path>(n)
            for (ink in DetailInk.entries) {
                val r = s.rects(ink)
                if (r.size > 0) {
                    val path = Path()
                    for (i in 0 until r.size / 4) {
                        val x = r[i * 4]
                        val y = r[i * 4 + 1]
                        path.addRect(Rect(x, y, x + r[i * 4 + 2], y + r[i * 4 + 3]))
                    }
                    fill[ink.ordinal] = path
                }
                val lines = s.lines(ink)
                if (lines.isNotEmpty()) {
                    val path = Path()
                    for (pts in lines) for (i in 0 until pts.size / 2) {
                        if (i == 0) path.moveTo(pts[0], pts[1])
                        else path.lineTo(pts[i * 2], pts[i * 2 + 1])
                    }
                    stroke[ink.ordinal] = path
                }
            }
            return InkPaths(fill, stroke)
        }
    }
}

/**
 * One storey worked out once, when the habits change, so a frame only draws: its wall's material,
 * its architecture as paths, its windows, which of them light and who is in them, its plaque's
 * words, its decay.
 */
internal class FloorArt(
    val piece: Piece,
    val block: HabitBlock,
    val facade: Facade,
    /** Its front as a rectangle, y down from its top, in building units (top is -piece.top). */
    val top: Float,
    val bottom: Float,
    /** The material its wall is tiled with ([FacadeSprites]). */
    val material: HabitMaterial,
    /**
     * The side face as a unit ([sidePath]); its surrounds, string course, pilasters or mullions and
     * pipe, drawn under the windows ([detail]), the shadows on the glass and the glazing bars drawn
     * over the rooms ([over]), and what stands in front of them ([front]).
     */
    val side: Path,
    val detail: InkPaths,
    val over: InkPaths,
    val front: InkPaths,
    /** Its windows as x, y, w, h (y from the front's top), in the order they light up. */
    val windows: FloatArray,
    val occupancy: Occupancy,
    /** Each lit window (in the order they light): its room and who takes their turn in it. */
    val cast: Array<WindowCast>,
    /** The floor's own number, and which of its dark windows has a hallway light (-1 none). */
    val seed: Int,
    val hall: Int,
    /** A run-down storey's windows, in window order. */
    val ruin: Array<RuinWindow>,
    val name: TextLayoutResult?,
    val count: TextLayoutResult?,
    val plaqueW: Float,
    val plaqueH: Float,
    /** What a missed floor has suffered, or the mend of a come-back ([FloorDecay]). */
    val decay: DecayArt?,
    /** The storey of a habit being broken (0 the lowest), and how many it has. */
    val storey: Int,
    val storeys: Int,
) {
    val windowCount: Int
        get() = windows.size / 4

    /**
     * How much of a person is in each lit window right now (scratch the painter fills per frame).
     */
    val here = FloatArray(cast.size)

    /** How lit each window's room is right now, 0 to 1 (scratch the painter fills per frame). */
    val glow = FloatArray(windows.size / 4)
}

/**
 * A floor's decay as paths, one per [DecayInk] (null where it has nothing), moved down to the
 * floor's top, and its streaks ([DecayPlan.stains], y from the building's origin).
 */
internal class DecayArt(val paths: Array<Path?>, val stains: Array<FloatArray>) {
    companion object {
        fun of(plan: DecayPlan, top: Float): DecayArt {
            val paths = arrayOfNulls<Path>(DecayInk.entries.size)
            for (ink in DecayInk.entries) {
                val shapes = plan.of(ink)
                if (shapes.isEmpty()) continue
                val path = Path()
                for (pts in shapes) {
                    path.moveTo(pts[0], top + pts[1])
                    for (i in 1 until pts.size / 2) path.lineTo(pts[i * 2], top + pts[i * 2 + 1])
                    if (ink.width <= 0f) path.close()
                }
                paths[ink.ordinal] = path
            }
            val stains =
                Array(plan.stains.size) { plan.stains[it].copyOf().also { s -> s[1] += top } }
            return DecayArt(paths, stains)
        }
    }
}

/** A building worked out once: its storeys and the words on its lobby and its roof. */
internal class BuildingArt(
    val layout: BuildingLayout,
    val stack: HabitStack,
    val floors: Array<FloorArt>,
    val lobby: Piece,
    val sign: Piece?,
    val ghost: Piece?,
    val lobbyName: TextLayoutResult,
    val treat: TextLayoutResult?,
    val ghostWords: TextLayoutResult?,
    val awning: Color,
    /** The lobby's side face as a unit ([sidePath]). */
    val lobbySide: Path,
    /** The material of its top storey, which sets its cornice. */
    val crown: HabitMaterial?,
    /**
     * Its storeys of a habit being broken that are the top of the building: the crane stands there.
     */
    val craneOn: FloorArt?,
    /** Which side its drainpipe runs down, what stands on its roof ([BuildingDetail]). */
    val choices: BuildingChoices,
    /** The cornice's coping and dentils, the lobby's plinth, pipe foot and awning brackets. */
    val cornice: InkPaths,
    val lobbyDetail: InkPaths,
    /** What goes over the lobby's glass: its reveals, the shadows on it, its glazing bars. */
    val lobbyOver: InkPaths,
)

internal fun buildingArt(layout: BuildingLayout, stack: HabitStack, text: SceneText): BuildingArt {
    val floors = ArrayList<FloorArt>()
    val storeyCount = HashMap<String, Int>()
    for (p in layout.pieces) if (p.kind == PieceKind.RUIN)
        storeyCount[p.id] = (storeyCount[p.id] ?: 0) + 1
    val seen = HashMap<String, Int>()
    val choices = BuildingDetail.choices(stack.id.hashCode())
    val pipeX = if (choices.pipeSide == 0) Float.NaN else choices.pipeX
    val stacked =
        layout.pieces.filter {
            it.block != null && (it.kind == PieceKind.FLOOR || it.kind == PieceKind.RUIN)
        }
    // the fire escape: up the brick floors of a building that has one, each platform joined by a
    // stair to the one over it where the next floor up is brick too
    fun escapes(i: Int): Boolean =
        choices.fireEscape &&
            i in stacked.indices &&
            stacked[i].kind == PieceKind.FLOOR &&
            stacked[i].block?.material == HabitMaterial.CLAY
    fun touching(lower: Int, upper: Int): Boolean =
        abs(stacked[upper].bottom - stacked[lower].top) < .5f
    for ((i, p) in stacked.withIndex()) {
        val b = p.block ?: continue
        val storey = seen[p.id] ?: 0
        seen[p.id] = storey + 1
        val escape =
            if (!escapes(i)) null
            else {
                val under = i - 1
                val over = i + 1
                Escape(
                    i,
                    if (escapes(under) && touching(under, i)) stacked[under].height else Float.NaN,
                    escapes(over) && touching(i, over),
                )
            }
        floors += floorArt(p, b, text, storey, storeyCount[p.id] ?: 1, pipeX, escape)
    }
    val lobby = layout.pieces.first { it.kind == PieceKind.LOBBY }
    val sign = layout.pieces.firstOrNull { it.kind == PieceKind.SIGN }
    val ghost = layout.pieces.firstOrNull { it.kind == PieceKind.GHOST }
    val top = floors.lastOrNull()
    return BuildingArt(
        layout,
        stack,
        floors.toTypedArray(),
        lobby,
        sign,
        ghost,
        text.layout(stack.cue.uppercase(), (10.5f * minOf(1f, 18f / stack.cue.length)).coerceAtLeast(7.5f), FontWeight.Bold, B.W - 30f, spacing = 1.1f),
        sign?.let {
            text.layout(
                if (stack.claimed) HabitsCopy.ENJOYED else stack.treat,
                10.5f,
                FontWeight.Bold,
                it.width - 16f,
            )
        },
        ghost?.let {
            text.layout(HabitsCopy.STACK_HERE, 11.5f, FontWeight.SemiBold, it.width - 16f)
        },
        AWNINGS[((Dice(stack.id).next() * AWNINGS.size).toInt()).coerceIn(0, AWNINGS.size - 1)],
        sidePath(-lobby.top, -lobby.bottom),
        top?.block?.material,
        top?.takeIf { it.piece.kind == PieceKind.RUIN },
        choices,
        InkPaths.of(
            Shapes().also {
                BuildingDetail.cornice(
                    if (top?.piece?.kind == PieceKind.RUIN) HabitMaterial.FADE
                    else top?.block?.material ?: HabitMaterial.STONE,
                    -layout.roof,
                    it,
                )
            }
        ),
        InkPaths.of(Shapes().also { BuildingDetail.lobby(-lobby.top, pipeX, it) }),
        InkPaths.of(Shapes().also { BuildingDetail.lobbyOver(-lobby.top, it) }),
    )
}

/**
 * A side face from [top] to [bottom] as a unit: hinged on x 0 (the front's edge), one unit wide,
 * its back edge [B.DY] up. The painter draws it under a transform that moves it to the edge the
 * shown side hangs from and scales it out to that side's width, mirrored for the left face
 * ([com.lifemax.rpg.feature.habits.domain.Perspective]), so no path is made per frame.
 */
private fun sidePath(top: Float, bottom: Float): Path =
    Path().apply {
        moveTo(0f, top)
        lineTo(1f, top - B.DY)
        lineTo(1f, bottom - B.DY)
        lineTo(0f, bottom)
        close()
    }

private fun floorArt(
    p: Piece,
    b: HabitBlock,
    text: SceneText,
    storey: Int,
    storeys: Int,
    pipeX: Float,
    escape: Escape?,
): FloorArt {
    val ruin = p.kind == PieceKind.RUIN
    val f = facade(if (ruin) HabitMaterial.FADE else b.material)
    val top = -p.top
    val bottom = -p.bottom
    // the plaque, in the band under the windows: the name, and the count beside it where it fits
    val labelled = p.labelled
    val room = B.W - 2 * B.PLAQUE_X - 10f
    val name =
        if (labelled)
            text.layout(b.name, if (ruin) 10.5f else 11.5f, FontWeight.SemiBold, room * .78f)
        else null
    val count =
        if (labelled && !ruin)
            text
                .layout(
                    HabitsCopy.count(b).uppercase(),
                    7.5f,
                    FontWeight.SemiBold,
                    room,
                    spacing = .5f,
                )
                .takeIf { name != null && name.size.width + 7f + it.size.width <= room }
        else null
    val plaqueW =
        if (name == null) 0f else name.size.width + (count?.let { it.size.width + 7f } ?: 0f) + 10f
    val plaqueH = if (name == null) 0f else maxOf(B.PLAQUE_H, name.size.height + 2f)
    val spots = floorWindows(p.height, ruin)
    val order = lightOrder(b.id + "/" + storey, spots.size)
    val windows = FloatArray(spots.size * 4)
    for ((k, i) in order.withIndex()) {
        val s = spots[i]
        windows[k * 4] = s.x
        windows[k * 4 + 1] = s.y
        windows[k * 4 + 2] = s.w
        windows[k * 4 + 3] = s.h
    }
    val occ = occupancy(b.reps, spots.size)
    val key = b.id + "/" + storey
    val groups = IntArray(occ.lit) { k -> (spots[order[k]].col + spots[order[k]].row) and 1 }
    val cast =
        Silhouettes.cast(
            b.title,
            key,
            occ.lit,
            groups,
            IntArray(occ.lit) { spots[order[it]].col },
            IntArray(occ.lit) { spots[order[it]].row },
        )
    val seed = key.hashCode()
    val hall = Silhouettes.hallLight(key, spots.size)
    val ruins = Array(spots.size) { ruinWindow(b.id, storey, storeys, it) }
    val wallShapes = Shapes()
    val frontShapes = Shapes()
    val overShapes = Shapes()
    BuildingDetail.floor(
        b.material,
        ruin,
        top,
        bottom,
        windows,
        spots.size,
        occ.lit,
        hall,
        seed,
        storey,
        pipeX,
        if (ruin) ruins else null,
        wallShapes,
        frontShapes,
        overShapes,
        escape,
    )
    val decay =
        if (!ruin && labelled)
            FloorDecay.plan(b.material, key, b.miss, b.scar, p.height, spots)?.let {
                DecayArt.of(it, top)
            }
        else null
    return FloorArt(
        p,
        b,
        f,
        top,
        bottom,
        if (ruin) HabitMaterial.FADE else b.material,
        sidePath(top, bottom),
        InkPaths.of(wallShapes),
        InkPaths.of(overShapes),
        InkPaths.of(frontShapes),
        windows,
        occ,
        cast,
        seed,
        hall,
        ruins,
        name,
        count,
        plaqueW,
        plaqueH,
        decay,
        storey,
        storeys,
    )
}
