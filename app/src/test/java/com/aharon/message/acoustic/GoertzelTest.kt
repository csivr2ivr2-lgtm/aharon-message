package com.aharon.message.acoustic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class GoertzelTest {
    @Test
    fun targetFrequencyDominatesNeighbor() {
        val sampleRate = 48_000
        val frequency = 20_600.0
        val samples = ShortArray(2_400) { index ->
            (sin(2.0 * PI * frequency * index / sampleRate) * 12_000.0).toInt().toShort()
        }
        val target = Goertzel.power(samples, frequency, sampleRate)
        val neighbor = Goertzel.power(samples, 19_200.0, sampleRate)
        assertTrue(target > neighbor * 20.0)
    }

    @Test
    fun symbolDecoderRoundTripsBytes() {
        val modem = Fsk4Modem(AcousticProfile.STRICT)
        val source = byteArrayOf(0x00, 0x1B, 0x55, 0x7F, 0xA5.toByte(), 0xFF.toByte())
        val symbols = IntArray(source.size * 4)
        var index = 0
        for (byte in source) {
            val value = byte.toInt() and 0xff
            symbols[index++] = (value ushr 6) and 3
            symbols[index++] = (value ushr 4) and 3
            symbols[index++] = (value ushr 2) and 3
            symbols[index++] = value and 3
        }
        assertArrayEquals(source, modem.decodeBytes(symbols))
    }
}
