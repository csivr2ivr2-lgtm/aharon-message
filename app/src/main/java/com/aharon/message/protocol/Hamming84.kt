package com.aharon.message.protocol

/**
 * Extended Hamming (8,4) SECDED codec.
 *
 * Every four data bits become one protected byte. A single flipped bit is
 * corrected automatically; a detected two-bit error makes decoding fail so
 * the packet can be retransmitted instead of delivering corrupted data.
 */
object Hamming84 {
    fun encode(input: ByteArray): ByteArray {
        val output = ByteArray(input.size * 2)
        var out = 0
        for (byte in input) {
            val value = byte.toInt() and 0xff
            output[out++] = encodeNibble((value ushr 4) and 0x0f).toByte()
            output[out++] = encodeNibble(value and 0x0f).toByte()
        }
        return output
    }

    fun decode(input: ByteArray): ByteArray? {
        if (input.size % 2 != 0) return null
        val output = ByteArray(input.size / 2)
        var out = 0
        var index = 0
        while (index < input.size) {
            val high = decodeCodeword(input[index].toInt() and 0xff) ?: return null
            val low = decodeCodeword(input[index + 1].toInt() and 0xff) ?: return null
            output[out++] = ((high shl 4) or low).toByte()
            index += 2
        }
        return output
    }

    private fun encodeNibble(nibble: Int): Int {
        val d1 = (nibble ushr 3) and 1
        val d2 = (nibble ushr 2) and 1
        val d3 = (nibble ushr 1) and 1
        val d4 = nibble and 1
        val p1 = d1 xor d2 xor d4
        val p2 = d1 xor d3 xor d4
        val p4 = d2 xor d3 xor d4

        var code = 0
        code = set(code, 1, p1)
        code = set(code, 2, p2)
        code = set(code, 3, d1)
        code = set(code, 4, p4)
        code = set(code, 5, d2)
        code = set(code, 6, d3)
        code = set(code, 7, d4)

        var overall = 0
        for (position in 1..7) overall = overall xor bit(code, position)
        return set(code, 8, overall)
    }

    private fun decodeCodeword(raw: Int): Int? {
        var code = raw
        val s1 = bit(code, 1) xor bit(code, 3) xor bit(code, 5) xor bit(code, 7)
        val s2 = bit(code, 2) xor bit(code, 3) xor bit(code, 6) xor bit(code, 7)
        val s4 = bit(code, 4) xor bit(code, 5) xor bit(code, 6) xor bit(code, 7)
        val syndrome = s1 or (s2 shl 1) or (s4 shl 2)

        var overall = 0
        for (position in 1..8) overall = overall xor bit(code, position)

        when {
            syndrome != 0 && overall == 1 -> code = code xor (1 shl (syndrome - 1))
            syndrome != 0 && overall == 0 -> return null // detected double-bit error
            syndrome == 0 && overall == 1 -> code = code xor (1 shl 7) // overall parity bit
        }

        return (bit(code, 3) shl 3) or
            (bit(code, 5) shl 2) or
            (bit(code, 6) shl 1) or
            bit(code, 7)
    }

    private fun bit(value: Int, position: Int): Int = (value ushr (position - 1)) and 1

    private fun set(value: Int, position: Int, bit: Int): Int =
        if (bit == 0) value and (1 shl (position - 1)).inv()
        else value or (1 shl (position - 1))
}
