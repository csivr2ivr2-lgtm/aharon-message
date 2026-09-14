package com.aharon.message.acoustic

import android.content.Context
import android.media.AudioManager

data class AcousticProfile(
    val id: String,
    val displayName: String,
    val frequencies: DoubleArray,
    val wakeFrequency: Double,
    val symbolMillis: Int,
    val amplitude: Double,
    val strictSilent: Boolean,
) {
    init {
        require(frequencies.size == 4)
        require(symbolMillis in 8..100)
        require(amplitude in 0.01..0.95)
    }

    companion object {
        val STRICT = AcousticProfile(
            id = "strict",
            displayName = "Strict silent",
            frequencies = doubleArrayOf(20_200.0, 20_600.0, 21_000.0, 21_400.0),
            wakeFrequency = 21_800.0,
            symbolMillis = 12,
            amplitude = 0.28,
            strictSilent = true,
        )

        val COMPATIBLE = AcousticProfile(
            id = "compatible",
            displayName = "Compatible near-ultrasound",
            frequencies = doubleArrayOf(18_800.0, 19_200.0, 19_600.0, 20_000.0),
            wakeFrequency = 20_400.0,
            symbolMillis = 14,
            amplitude = 0.24,
            strictSilent = false,
        )

        fun fromId(id: String?): AcousticProfile = when (id) {
            COMPATIBLE.id -> COMPATIBLE
            else -> STRICT
        }

        fun microphoneSupport(context: Context): Boolean? = property(context, AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)
        fun speakerSupport(context: Context): Boolean? = property(context, AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)

        private fun property(context: Context, key: String): Boolean? {
            val manager = context.getSystemService(AudioManager::class.java)
            return when (manager.getProperty(key)?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }
}
