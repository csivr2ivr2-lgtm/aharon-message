package com.aharon.message.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FragmentProtocolTest {
    @Test
    fun fragmentPayloadRoundTrip() {
        val encrypted = ByteArray(96) { it.toByte() }
        val encoded = ProtocolCodec.encodeFragmentPayload(2, 7, encrypted)
        val decoded = requireNotNull(ProtocolCodec.decodeFragmentPayload(encoded))
        assertEquals(2, decoded.sequence)
        assertEquals(7, decoded.total)
        assertArrayEquals(encrypted, decoded.encryptedChunk)
    }

    @Test
    fun fragmentAckRoundTrip() {
        val ack = requireNotNull(ProtocolCodec.decodeFragmentAck(ProtocolCodec.encodeFragmentAck(3, 9)))
        assertEquals(3, ack.first)
        assertEquals(9, ack.second)
    }
}
