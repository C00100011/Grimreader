package com.vdelaar.mylibby.ui.profile

import androidx.annotation.StringRes
import com.vdelaar.mylibby.R

/** The profile achievements; [key] is also what the secret menu stores to force one on or off. */
data class BadgeDef(val key: String, val emoji: String, @StringRes val title: Int, @StringRes val description: Int) {
    companion object {
        val all = listOf(
            BadgeDef("first", "🌱", R.string.badge_first, R.string.badge_first_d),
            BadgeDef("fire", "🔥", R.string.badge_fire, R.string.badge_fire_d),
            BadgeDef("habit", "🏆", R.string.badge_habit, R.string.badge_habit_d),
            BadgeDef("ten", "⏱️", R.string.badge_ten, R.string.badge_ten_d),
            BadgeDef("finisher", "📚", R.string.badge_finisher, R.string.badge_finisher_d),
            BadgeDef("century", "💯", R.string.badge_century, R.string.badge_century_d),
            BadgeDef("annotator", "✍️", R.string.badge_annotator, R.string.badge_annotator_d),
            BadgeDef("challenger", "🎯", R.string.badge_challenger, R.string.badge_challenger_d),
        )
    }
}
