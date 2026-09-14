package com.aharon.message.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

object Goertzel {
    fun power(samples: ShortArray, frequency: Double, sampleRate: Int): Double {
        if (samples.isEmpty()) return 0.0
        val omega = 2.0 * PI * frequency / sampleRate
        val coefficient = 2.0 * cos(omega)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        for (sample in samples) {
            q0 = coefficient * q1 - q2 + sample.toDouble()
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - coefficient * q1 * q2
    }

    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / samples.size)
    }

    fun dbFs(samples: ShortArray): Double {
        val normalized = rms(samples) / Short.MAX_VALUE.toDouble()
        return if (normalized <= 1e-9) -180.0 else 20.0 * log10(normalized)
    }
}
