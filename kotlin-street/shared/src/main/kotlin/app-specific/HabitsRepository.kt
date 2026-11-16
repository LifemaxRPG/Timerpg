package com.lifemax.rpg.feature.habits.domain

import kotlinx.coroutines.flow.StateFlow

interface HabitsRepository {
    val state: StateFlow<HabitsProjection?>
}

/**
 * What the screen asks for. Each command answers at once: `ok` only when the change is stored in
 * the habits file; a refusal or a write that failed changes nothing and says why.
 */
interface HabitsCommands {
    fun lay(id: String): HabitsReply

    fun undo(id: String): HabitsReply

    fun claim(stackId: String): HabitsReply

    fun rough(on: Boolean): HabitsReply

    fun move(id: String, dir: Int): HabitsReply

    fun size(id: String, size: Int): HabitsReply

    fun pair(id: String, text: String): HabitsReply

    fun names(id: String, names: List<String>): HabitsReply

    fun restack(id: String, stackId: String): HabitsReply

    fun retire(id: String): HabitsReply

    fun who(text: String): HabitsReply

    fun addStack(cue: String): HabitsReply

    fun editStack(id: String, cue: String, treat: String): HabitsReply

    fun removeStack(id: String): HabitsReply

    /**
     * A new habit: [small] and [big] are its two-minute and stretch names (one to build), [floors]
     * its clean days (one to break), [pair] what it is paired with or done instead.
     */
    fun addHabit(
        stackId: String,
        fade: Boolean,
        name: String,
        small: String,
        floors: Int,
        big: String = "",
        pair: String = "",
    ): HabitsReply
}

/** What the one big button does now, on the stack in front. */
sealed interface PrimaryAction {
    /** Nothing built yet: lay the first foundation. */
    data object FirstFoundation : PrimaryAction

    /** An empty foundation: stack a habit on it. */
    data class FirstHabit(val stack: HabitStack) : PrimaryAction

    /** Lay the next block. */
    data class Lay(val block: HabitBlock) : PrimaryAction

    /** A clean day on a habit being broken: knock a floor off. */
    data class Knock(val block: HabitBlock) : PrimaryAction

    /** The stack is done and its rooftop reward waits. */
    data class Claim(val stack: HabitStack) : PrimaryAction

    /** This stack is done; [stack] still has a block to lay. */
    data class GoTo(val stack: HabitStack) : PrimaryAction

    /** Every block is laid today. */
    data object Complete : PrimaryAction
}

/** The big button's action for the stack [focus] of [stacks]. */
fun primaryAction(stacks: List<HabitStack>, focus: String?): PrimaryAction {
    val stack = stacks.firstOrNull { it.id == focus } ?: stacks.firstOrNull()
    if (stack == null) return PrimaryAction.FirstFoundation
    if (stack.blocks.isEmpty()) return PrimaryAction.FirstHabit(stack)
    val next = stack.nextBlock
    if (next != null) return if (next.fade) PrimaryAction.Knock(next) else PrimaryAction.Lay(next)
    if (stack.claimable) return PrimaryAction.Claim(stack)
    val more = stacks.firstOrNull { it.id != stack.id && it.next.isNotEmpty() }
    return if (more != null) PrimaryAction.GoTo(more) else PrimaryAction.Complete
}
