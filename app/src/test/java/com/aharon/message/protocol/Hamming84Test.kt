package com.aharon.message.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class Hamming84Test {
    @Test
    fun roundTripPreservesPayload() {
        val source = ByteArray(128) { Random(1107).nextInt(0, 256).toByte() }
        assertArrayEquals(source, Hamming84.decode(Hamming84.encode(source)))
    }

    @Test
    fun correctsEverySingleBitPosition() {
        val source = byteArrayOf(0x00, 0x35, 0x7f, 0xa5.toByte(), 0xff.toByte())
        val encoded = Hamming84.encode(source)
        for (index in encoded.indices) {
            for (bit in 0..7) {
                val damaged = encoded.copyOf()
                damaged[index] = (damaged[index].toInt() xor (1 shl bit)).toByte()
                assertArrayEquals("index=$index bit=$bit", source, Hamming84.decode(damaged))
            }
        }
    }

    @Test
    fun rejectsTwoBitErrorInSameCodeword() {
        val source = byteArrayOf(0x5a)
        val damaged = Hamming84.encode(source)
        damaged[0] = (damaged[0].toInt() xor 0b00000011).toByte()
        assertNull(Hamming84.decode(damaged))
    }
}
