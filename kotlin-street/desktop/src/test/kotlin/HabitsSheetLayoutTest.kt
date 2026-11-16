package com.lifemax.rpg.feature.habits

import com.lifemax.rpg.feature.habits.presentation.barRoomDp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The room Habits keeps for the shared bottom bar. The activity shortens the screen by the keyboard
 * and hides the bar while it is up, so the sheet's bottom edge is the screen's bottom then, and
 * nothing may shift it by the keyboard a second time.
 */
class HabitsSheetLayoutTest {
    @Test
    fun barRoomIsKeptWhileTheKeyboardIsDown() = assertEquals(72f, barRoomDp(72f, imeUp = false), 0f)

    @Test
    fun barRoomIsDroppedWhileTheKeyboardIsUp() = assertEquals(0f, barRoomDp(72f, imeUp = true), 0f)
}
