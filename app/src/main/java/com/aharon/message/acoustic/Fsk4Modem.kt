package com.aharon.message.acoustic

import com.aharon.message.protocol.ProtocolCodec
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

class Fsk4Modem(
    val profile: AcousticProfile,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        private val SYNC = intArrayOf(0, 1, 2, 3, 3, 2, 1, 0)
        private const val WAKE_MILLIS = 180
        private const val GUARD_MILLIS = 60
        private const val DETECTOR_WINDOW_MILLIS = 4
        private const val WAKE_WINDOWS_REQUIRED = 20
        private const val WAKE_RATIO = 3.2
    }

    val symbolSamples: Int = sampleRate * profile.symbolMillis / 1000
    val detectorSamples: Int = sampleRate * DETECTOR_WINDOW_MILLIS / 1000

    fun render(frame: ByteArray): ShortArray {
        val output = ArrayList<Short>(
            sampleRate * (WAKE_MILLIS + GUARD_MILLIS) / 1000 +
                (SYNC.size + frame.size * 4) * symbolSamples
        )
        appendTone(output, profile.wakeFrequency, sampleRate * WAKE_MILLIS / 1000)
        repeat(sampleRate * GUARD_MILLIS / 1000) { output += 0 }
        for (symbol in SYNC) appendTone(output, profile.frequencies[symbol], symbolSamples)
        for (byte in frame) {
            val value = byte.toInt() and 0xff
            appendTone(output, profile.frequencies[(value ushr 6) and 0x03], symbolSamples)
            appendTone(output, profile.frequencies[(value ushr 4) and 0x03], symbolSamples)
            appendTone(output, profile.frequencies[(value ushr 2) and 0x03], symbolSamples)
            appendTone(output, profile.frequencies[value and 0x03], symbolSamples)
        }
        return ShortArray(output.size) { output[it] }
    }

    fun detectWake(window: ShortArray): Boolean {
        val wakePower = Goertzel.power(window, profile.wakeFrequency, sampleRate)
        val competing = profile.frequencies.maxOf { Goertzel.power(window, it, sampleRate) }.coerceAtLeast(1.0)
        return Goertzel.dbFs(window) > -72.0 && wakePower / competing > WAKE_RATIO
    }

    fun requiredWakeWindows(): Int = WAKE_WINDOWS_REQUIRED

    fun guardWindowsAfterWake(): Int = GUARD_MILLIS / DETECTOR_WINDOW_MILLIS

    fun decodeSymbol(samples: ShortArray): Int {
        var bestIndex = 0
        var bestPower = Double.NEGATIVE_INFINITY
        for (index in profile.frequencies.indices) {
            val power = Goertzel.power(samples, profile.frequencies[index], sampleRate)
            if (power > bestPower) {
                bestPower = power
                bestIndex = index
            }
        }
        return bestIndex
    }

    fun validateSync(symbols: IntArray): Boolean {
        if (symbols.size != SYNC.size) return false
        var matches = 0
        for (index in SYNC.indices) if (symbols[index] == SYNC[index]) matches++
        return matches >= SYNC.size - 1
    }

    fun decodeBytes(symbols: IntArray): ByteArray {
        require(symbols.size % 4 == 0)
        val output = ByteArrayOutputStream(symbols.size / 4)
        var index = 0
        while (index < symbols.size) {
            val value = ((symbols[index] and 3) shl 6) or
                ((symbols[index + 1] and 3) shl 4) or
                ((symbols[index + 2] and 3) shl 2) or
                (symbols[index + 3] and 3)
            output.write(value)
            index += 4
        }
        return output.toByteArray()
    }

    fun expectedFrameBytesFromHeader(header: ByteArray): Int? {
        val payload = ProtocolCodec.payloadLengthFromHeader(header) ?: return null
        return ProtocolCodec.HEADER_SIZE + payload + ProtocolCodec.CRC_SIZE
    }

    private fun appendTone(output: MutableList<Short>, frequency: Double, count: Int) {
        val scale = Short.MAX_VALUE * profile.amplitude
        for (index in 0 until count) {
            val envelope = when {
                index < 24 -> index / 24.0
                count - index < 24 -> (count - index) / 24.0
                else -> 1.0
            }
            val sample = sin(2.0 * PI * frequency * index / sampleRate) * scale * envelope
            output += sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
