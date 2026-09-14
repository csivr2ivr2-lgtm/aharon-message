package com.aharon.message

import android.content.Context
import com.aharon.message.acoustic.AcousticProfile

class AppSettings(context: Context) {
    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_PROFILE = "acoustic_profile"
        private const val KEY_RECEIVER = "receiver_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var acousticProfileId: String
        get() = prefs.getString(KEY_PROFILE, AcousticProfile.STRICT.id) ?: AcousticProfile.STRICT.id
        set(value) = prefs.edit().putString(KEY_PROFILE, value).apply()

    var receiverEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECEIVER, false)
        set(value) = prefs.edit().putBoolean(KEY_RECEIVER, value).apply()

    fun profile(): AcousticProfile = AcousticProfile.fromId(acousticProfileId)
}
