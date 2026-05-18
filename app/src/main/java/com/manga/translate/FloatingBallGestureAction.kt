package com.manga.translate

import androidx.annotation.StringRes

enum class FloatingBallGestureAction(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    START_TRANSLATE("start_translate", R.string.floating_gesture_action_translate),
    OPEN_MENU("open_menu", R.string.floating_gesture_action_open_menu),
    CLEAR_SCREEN("clear_screen", R.string.floating_gesture_action_clear_screen),
    NONE("none", R.string.floating_gesture_action_none),
    SWIPE_TRANSLATE("swipe_translate", R.string.floating_gesture_action_swipe_translate);

    companion object {
        fun fromPref(
            value: String?,
            defaultValue: FloatingBallGestureAction
        ): FloatingBallGestureAction {
            return entries.firstOrNull { it.prefValue == value } ?: defaultValue
        }
    }
}
