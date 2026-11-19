package com.lifemax.rpg.feature.habits.presentation.scene

/** What a foot lands on, for the footstep sounds (recorded steps per surface, assets/audio/sfx/step_<name>_<n>.ogg). */
object Surface {
    const val HARD = 0
    const val GRASS = 1
    const val WOOD = 2
    const val GRAVEL = 3
    const val CARPET = 4

    /** The sound set for [surface]; [indoors] floors are wood or carpet; snow lies on everything outdoors in winter. */
    fun sound(surface: Int, indoors: Boolean, carpet: Boolean, snow: Boolean): String = when {
        indoors -> if (carpet) "carpet" else "wood"
        snow && surface != WOOD -> "snow"
        surface == GRASS -> "grass"
        surface == WOOD -> "wood"
        surface == GRAVEL -> "snow" // the crunch is the nearest recorded sound to gravel
        else -> "concrete"
    }
}
