package com.ideacode.soundly.app

import android.content.Context

/** App-only preferences. Audio settings remain owned by soundly-sdk. */
class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("soundly_app", Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    private companion object {
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
