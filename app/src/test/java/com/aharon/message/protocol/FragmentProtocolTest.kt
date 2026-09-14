package com.aharon.message.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentProtocolTest {
    @Test
    fun fragmentPayloadRoundTripWithoutFec() {
        val encrypted = ByteArray(96) { it.toByte() }
        val encoded = ProtocolCodec.encodeFragmentPayload(2, 7, encrypted, fecProtected = false)
        val decoded = requireNotNull(ProtocolCodec.decodeFragmentPayload(encoded))
        assertEquals(2, decoded.sequence)
        assertEquals(7, decoded.total)
        assertFalse(decoded.fecProtected)
        assertArrayEquals(encrypted, decoded.encryptedChunk)
    }

    @Test
    fun fragmentPayloadCarriesFecFlag() {
        val protected = Hamming84.encode(byteArrayOf(1, 2, 3, 4))
        val encoded = ProtocolCodec.encodeFragmentPayload(0, 1, protected, fecProtected = true)
        val decoded = requireNotNull(ProtocolCodec.decodeFragmentPayload(encoded))
        assertTrue(decoded.fecProtected)
        assertArrayEquals(protected, decoded.encryptedChunk)
    }

    @Test
    fun fragmentAckRoundTrip() {
        val ack = requireNotNull(ProtocolCodec.decodeFragmentAck(ProtocolCodec.encodeFragmentAck(3, 9)))
        assertEquals(3, ack.first)
        assertEquals(9, ack.second)
    }
}
