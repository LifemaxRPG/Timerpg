package com.lifemax.rpg.feature.habits.presentation

/** Stand-ins for the habit-tracker's screen state and copy, which the street scene still reads. */
object HabitsCopy {
    const val ENJOYED = "OPEN"
    const val STACK_HERE = "FOR RENT"
    fun describe(p: Any?, stack: Any?, zoomedOut: Boolean, weeks: Int): String = "A street of shops"
    fun count(b: com.lifemax.rpg.feature.habits.domain.HabitBlock): String = b.name
}

class PlayFx(val laid: Boolean = false, val upgrade: Any? = null, val id: String = "")
class HabitPlay(val id: Long = 0L, val fx: PlayFx = PlayFx())

/** What the scene is asked to show: nothing is selected and no play runs in our game. */
class HabitsUi(
    val city: Boolean = false,
    val weeks: Int = 0,
    val focus: String? = null,
    val selected: String? = null,
    val jump: Int = 0,
    val play: HabitPlay? = null,
)
