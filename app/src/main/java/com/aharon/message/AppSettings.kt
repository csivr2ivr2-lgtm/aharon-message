package com.aharon.message

import android.content.Context
import com.aharon.message.acoustic.AcousticProfile

class AppSettings(context: Context) {
    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_PROFILE = "acoustic_profile"
        private const val KEY_RECEIVER = "receiver_enabled"
        private const val KEY_FEC = "fec_enabled"
        private const val KEY_CALIBRATED_AT = "calibrated_at"
        private const val KEY_CALIBRATION_SCORE = "calibration_score"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var acousticProfileId: String
        get() = prefs.getString(KEY_PROFILE, AcousticProfile.STRICT.id) ?: AcousticProfile.STRICT.id
        set(value) = prefs.edit().putString(KEY_PROFILE, value).apply()

    var receiverEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECEIVER, false)
        set(value) = prefs.edit().putBoolean(KEY_RECEIVER, value).apply()

    var fecEnabled: Boolean
        get() = prefs.getBoolean(KEY_FEC, false)
        set(value) = prefs.edit().putBoolean(KEY_FEC, value).apply()

    var calibratedAt: Long
        get() = prefs.getLong(KEY_CALIBRATED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_CALIBRATED_AT, value).apply()

    var calibrationScore: Float
        get() = prefs.getFloat(KEY_CALIBRATION_SCORE, 0f)
        set(value) = prefs.edit().putFloat(KEY_CALIBRATION_SCORE, value).apply()

    fun profile(): AcousticProfile = AcousticProfile.fromId(acousticProfileId)
}
