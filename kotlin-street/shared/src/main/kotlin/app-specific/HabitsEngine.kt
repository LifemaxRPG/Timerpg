package com.lifemax.rpg.feature.habits.domain

import com.lifemax.rpg.core.designsystem.feedback.HapticScale

/** What the habits file gave on opening. */
sealed interface HabitsLoad {
    /**
     * The save to work on; [notice] says, in plain words, if it is not the newest file (""
     * otherwise).
     */
    data class Ready(val save: HabitsSave, val notice: String = "") : HabitsLoad

    /**
     * The file cannot be used and must not be replaced (a newer app made it): nothing can be
     * changed.
     */
    data class Blocked(val message: String) : HabitsLoad
}

/** Where the save is kept. [save] answers whether it is safely stored. */
interface HabitsStorage {
    fun load(): HabitsLoad

    fun save(save: HabitsSave): Boolean
}

/**
 * The habits and their commands: each one works out the save after it ([HabitsRules]), stores it,
 * and only then takes it as the truth. A write that fails leaves the save in memory as it was (the
 * change is taken back) and says so; a refusal says why. [today] is the phone's local day, read
 * afresh for every command, so a command never sees two days.
 */
class HabitsEngine(private val storage: HabitsStorage, private val today: () -> String) {
    var save: HabitsSave = HabitsSave()
        private set

    /** What the owner must know about the file ("" when all is well). */
    var problem: String = ""
        private set

    private var blocked = false

    init {
        when (val loaded = storage.load()) {
            is HabitsLoad.Ready -> {
                save = loaded.save
                problem = loaded.notice
            }
            is HabitsLoad.Blocked -> {
                blocked = true
                problem = loaded.message
            }
        }
    }

    fun day(day: String? = null): HabitsDay = today().let { HabitsRules.day(save, it, day ?: it) }

    /**
     * Store [next] and take it; [unchanged] is the answer when the rules found nothing to change.
     */
    private fun commit(
        next: HabitsSave?,
        unchanged: HabitsReply,
        done: () -> HabitsReply = { OK },
    ): HabitsReply {
        if (blocked) return no(problem)
        if (next == null) return unchanged
        if (!storage.save(next)) return no(UNSAVED)
        save = next
        return done()
    }

    private fun block(id: String): Pair<HabitStack, HabitBlock>? =
        day().stacks.firstNotNullOfOrNull { s ->
            s.blocks.firstOrNull { it.id == id }?.let { s to it }
        }

    /**
     * Lay today's block; the answer's fx is what to play: a block landing or a floor coming down.
     */
    fun lay(id: String): HabitsReply {
        val (_, b) = block(id) ?: return no(GONE)
        if (b.done || b.cleared) return same("Already laid today.")
        return commit(HabitsRules.lay(save, today(), id), same()) {
            val after = block(id)?.second ?: b
            val up = after.material.takeIf { !b.fade && it != b.material }
            val message =
                when {
                    up != null ->
                        "${b.title} is now ${up.wire}" +
                            if (up == HabitMaterial.GOLD) ". It runs itself." else "."
                    b.fade && after.cleared -> "${b.title} is gone. A tree stands where it was."
                    else -> ""
                }
            HabitsReply(true, true, message, fx = HabitFx(!b.fade, id, up, b.fade && after.cleared))
        }
    }

    fun undo(id: String): HabitsReply {
        block(id) ?: return no(GONE)
        return commit(HabitsRules.undo(save, today(), id), same())
    }

    fun claim(stackId: String): HabitsReply {
        val s = day().stacks.firstOrNull { it.id == stackId } ?: return no(GONE)
        if (s.claimed) return same("Already enjoyed today.")
        if (!s.claimable) return no("Finish the stack first.")
        return commit(HabitsRules.claim(save, today(), stackId), same())
    }

    fun rough(on: Boolean): HabitsReply = commit(HabitsRules.rough(save, today(), on), same())

    fun move(id: String, dir: Int): HabitsReply {
        block(id) ?: return no(GONE)
        return commit(HabitsRules.move(save, id, dir), same())
    }

    fun size(id: String, size: Int): HabitsReply {
        val (_, b) = block(id) ?: return no(GONE)
        if (b.fade || size !in 0..2) return no("This habit has no size.")
        if (b.set == size) return same()
        return commit(HabitsRules.resize(save, id, size), same())
    }

    fun pair(id: String, text: String): HabitsReply {
        val (_, b) = block(id) ?: return no(GONE)
        if (b.pair == HabitsRules.clean(text, HabitsRules.LIMITS.pair)) return same()
        return commit(HabitsRules.pair(save, id, text), same())
    }

    fun names(id: String, names: List<String>): HabitsReply {
        block(id) ?: return no(GONE)
        return commit(HabitsRules.rename(save, id, names), no("Give the habit a name."))
    }

    fun restack(id: String, stackId: String): HabitsReply {
        val (s, _) = block(id) ?: return no(GONE)
        if (save.stacks.none { it.id == stackId }) return no(GONE)
        if (s.id == stackId) return same()
        return commit(HabitsRules.restack(save, id, stackId), same())
    }

    fun retire(id: String): HabitsReply =
        commit(HabitsRules.retire(save, id), same("Already removed."))

    fun who(text: String): HabitsReply = commit(HabitsRules.who(save, text), same())

    fun addStack(cue: String): HabitsReply {
        val added = HabitsRules.addStack(save, cue)
        return commit(added?.first, no("Say when: something you already do every day.")) {
            HabitsReply(true, true, id = added!!.second)
        }
    }

    fun editStack(id: String, cue: String, treat: String): HabitsReply {
        if (save.stacks.none { it.id == id }) return no(GONE)
        if (HabitsRules.clean(cue, HabitsRules.LIMITS.cue).isEmpty())
            return no("Say when: something you already do every day.")
        return commit(HabitsRules.editStack(save, id, cue, treat), no(GONE))
    }

    fun removeStack(id: String): HabitsReply {
        if (save.stacks.none { it.id == id }) return same("Already removed.")
        if (save.items.any { it.stack == id }) return no("Retire its habits first.")
        return commit(HabitsRules.removeStack(save, id), same())
    }

    fun addHabit(
        stackId: String,
        fade: Boolean,
        name: String,
        small: String,
        floors: Int,
        big: String = "",
        pair: String = "",
    ): HabitsReply {
        if (save.stacks.none { it.id == stackId }) return no(GONE)
        val added =
            HabitsRules.addHabit(save, today(), stackId, name, small, big, fade, floors, pair)
        return commit(added?.first, no("Give the habit a name.")) {
            HabitsReply(true, true, id = added!!.second)
        }
    }

    /**
     * Today as the screen draws it, with the towers of the last [HabitRules.weeks] weeks for the
     * time-lapse. [hour] is the local hour with its fraction; the rest is the page's surroundings.
     */
    fun project(
        hour: Float,
        whoHint: String,
        reducedMotion: Boolean,
        haptics: HapticScale?,
        tokens: Map<String, Long>,
        light: Boolean,
    ): HabitsProjection {
        val now = today()
        val d = HabitsRules.day(save, now)
        val lapse =
            (1..HabitsRules.RULES.weeks).map { w ->
                val past = HabitsRules.day(save, now, HabitsRules.addDays(now, -7 * w))
                LapseFrame(
                    w,
                    past.day,
                    past.stacks.map { s ->
                        LapseStack(
                            s.id,
                            s.blocks.map {
                                LapseBlock(
                                    it.id,
                                    it.size,
                                    it.reps,
                                    it.material,
                                    it.left,
                                    it.cleared,
                                )
                            },
                        )
                    },
                )
            }
        return HabitsProjection(
            day = d.day,
            hour = hour,
            who = d.who,
            whoHint = whoHint,
            rough = d.rough,
            built = d.built,
            due = d.due,
            reducedMotion = reducedMotion,
            haptics = haptics,
            problem = problem,
            limits = HabitsRules.LIMITS,
            rules = HabitsRules.RULES,
            stacks = d.stacks,
            lapse = lapse,
            tokens = tokens,
            light = light,
        )
    }

    companion object {
        const val GONE = "That is no longer there."
        const val UNSAVED =
            "Could not save your habits to this phone (storage full?). Nothing was changed."
        private val OK = HabitsReply(ok = true, changed = true)

        private fun same(message: String = "") =
            HabitsReply(ok = true, changed = false, message = message)

        private fun no(message: String) =
            HabitsReply(ok = false, changed = false, message = message)
    }
}
