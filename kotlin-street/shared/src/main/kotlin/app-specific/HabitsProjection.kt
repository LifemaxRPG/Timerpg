package com.lifemax.rpg.feature.habits.domain

import com.lifemax.rpg.core.designsystem.feedback.HapticScale

/*
 * Habits as the screen draws it: one day of the save, worked out by HabitsRules (the app owns the data and its
 * rules; nothing of Habits lives on the page). The screen draws this and asks through HabitsCommands.
 */

/**
 * What a block is made of: how set the habit is, by total repetitions; [FADE] is a habit being
 * broken.
 */
enum class HabitMaterial(val wire: String) {
    GLASS("glass"),
    CLAY("clay"),
    STONE("stone"),
    GOLD("gold"),
    FADE("fade");

    companion object {
        fun of(wire: String): HabitMaterial =
            entries.firstOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("No material $wire")
    }
}

/** A habit being built (a block laid a day) or broken (a tower that loses a floor a clean day). */
enum class HabitKind {
    BUILD,
    FADE,
}

/**
 * One habit today. [name] is its name at today's [size] (0 two minutes, 1 normal, 2 stretch; a
 * rough day shows the two-minute one) and [names] all three; [set] is the habit's own size. [reps]
 * never goes down. [done] is today's block laid. [miss] counts the days in a row before today with
 * no block (1 a crack, 2 or more a wobble); [scar] is the gold seam left by coming back. A [FADE]
 * habit has [left] of its [floors] still standing and is [cleared] when none is. [pair] is what it
 * is paired with ("" for nothing).
 */
data class HabitBlock(
    val id: String,
    val kind: HabitKind,
    val name: String,
    val names: List<String>,
    val size: Int,
    val set: Int,
    val reps: Int,
    val done: Boolean,
    val miss: Int,
    val scar: Boolean,
    val material: HabitMaterial,
    val left: Int,
    val floors: Int,
    val cleared: Boolean,
    val pair: String,
) {
    val fade: Boolean
        get() = kind == HabitKind.FADE

    /** The name the habit goes by whatever today's size is. */
    val title: String
        get() = names[1]
}

/**
 * A foundation (something the owner already does, [cue]) and the habits stacked on it in the order
 * they are done. [treat] is its rooftop reward ("" for none), [claimed] today, [claimable] once the
 * stack is done. [removable] when nothing stands on it. [next] is the id of the block to lay next,
 * "" when the stack is done for today.
 */
data class HabitStack(
    val id: String,
    val cue: String,
    val treat: String,
    val claimed: Boolean,
    val claimable: Boolean,
    val removable: Boolean,
    val next: String,
    val blocks: List<HabitBlock>,
) {
    val nextBlock: HabitBlock?
        get() = blocks.firstOrNull { it.id == next }
}

/** What a field may hold, as the page says (the screen hard-codes none). */
data class HabitLimits(
    val cue: Int,
    val treat: Int,
    val name: Int,
    val pair: Int,
    val who: Int,
    val floorsMin: Int,
    val floorsMax: Int,
    val floors: Int,
)

/** The repetitions at which a block turns to clay, stone and gold, and the time-lapse's reach. */
data class HabitRules(val clay: Int, val stone: Int, val gold: Int, val weeks: Int)

/** A habit as it stood on a past day of the time-lapse. */
data class LapseBlock(
    val id: String,
    val size: Int,
    val reps: Int,
    val material: HabitMaterial,
    val left: Int,
    val cleared: Boolean,
)

data class LapseStack(val id: String, val blocks: List<LapseBlock>)

/** The towers [weeks] weeks before today, on [day]. */
data class LapseFrame(val weeks: Int, val day: String, val stacks: List<LapseStack>)

data class HabitsProjection(
    /** Today, the phone's local day ("YYYY-MM-DD"). */
    val day: String,
    /** The local hour with its fraction (14.5 is half past two): the sky follows it. */
    val hour: Float,
    /** Who the owner is becoming ("" when they have not said) and their class, to show instead. */
    val who: String,
    val whoHint: String,
    val rough: Boolean,
    /** Today's blocks laid, of the [due] habits being built. */
    val built: Int,
    val due: Int,
    /**
     * The app's reduce-motion setting, and the owner's haptic strength (null with haptics off): the
     * page's.
     */
    val reducedMotion: Boolean,
    val haptics: HapticScale?,
    /**
     * Something the owner must know about their habits file, in plain words ("" when all is well):
     * it could not be read and an older copy was loaded, or it was made by a newer app.
     */
    val problem: String,
    val limits: HabitLimits,
    val rules: HabitRules,
    val stacks: List<HabitStack>,
    /** The past, a week apart, most recent first: [HabitRules.weeks] frames. */
    val lapse: List<LapseFrame>,
    val tokens: Map<String, Long>,
    val light: Boolean,
    /** The page's developer mode: the screen shows its developer clock ([HabitsDev]). */
    val dev: Boolean = false,
) {
    fun stack(id: String?): HabitStack? = stacks.firstOrNull { it.id == id }

    fun block(id: String?): HabitBlock? =
        stacks.firstNotNullOfOrNull { s -> s.blocks.firstOrNull { it.id == id } }

    fun stackOf(blockId: String?): HabitStack? =
        stacks.firstOrNull { s -> s.blocks.any { it.id == blockId } }

    /**
     * The towers [weeks] weeks ago (0 is today): the past frame's blocks, named as they are today,
     * with nothing of today on them (no blueprint, crack, seam, reward or next block).
     */
    fun towersAt(weeks: Int): List<HabitStack> {
        val frame = lapse.firstOrNull { it.weeks == weeks } ?: return stacks
        return stacks.map { live ->
            val past = frame.stacks.firstOrNull { it.id == live.id }?.blocks.orEmpty()
            live.copy(
                treat = "",
                claimed = false,
                claimable = false,
                next = "",
                blocks =
                    past.mapNotNull { b ->
                        live.blocks
                            .firstOrNull { it.id == b.id }
                            ?.copy(
                                name = live.blocks.first { it.id == b.id }.names[b.size],
                                size = b.size,
                                reps = b.reps,
                                done = true,
                                miss = 0,
                                scar = false,
                                material = b.material,
                                left = b.left,
                                cleared = b.cleared,
                            )
                    },
            )
        }
    }
}

/** What the screen plays for a block that was just laid. */
data class HabitFx(
    /** A block dropped on the tower (false: a floor knocked off a habit being broken). */
    val laid: Boolean,
    val id: String,
    /** The material this repetition turned the block into; null when it stayed what it was. */
    val upgrade: HabitMaterial?,
    /** The last floor came down. */
    val cleared: Boolean,
)

/**
 * The answer to a command: [ok] only when the change is stored in the habits file (or there was
 * nothing to change: [changed] false). [message] is what to tell the owner ("" for nothing): why it
 * was refused, that the write failed and the change was taken back, what a block turned into.
 */
data class HabitsReply(
    val ok: Boolean,
    val changed: Boolean,
    val message: String = "",
    /** The id given to what was added ("" otherwise). */
    val id: String = "",
    val fx: HabitFx? = null,
)
