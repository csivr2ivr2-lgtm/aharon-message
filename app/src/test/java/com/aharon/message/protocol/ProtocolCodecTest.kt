package com.aharon.message.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProtocolCodecTest {
    @Test
    fun packetRoundTripPreservesFields() {
        val original = ProtocolPacket(
            type = PacketType.DATA,
            senderId = 1234L,
            receiverId = 5678L,
            messageId = 9012L,
            payload = "hello".toByteArray(),
        )

        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(original))
        requireNotNull(decoded)
        assertEquals(original.type, decoded.type)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.receiverId, decoded.receiverId)
        assertEquals(original.messageId, decoded.messageId)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun crcRejectsCorruption() {
        val encoded = ProtocolCodec.encode(
            ProtocolPacket(PacketType.PING, 1L, 2L, 3L, byteArrayOf(10, 20, 30))
        )
        encoded[ProtocolCodec.HEADER_SIZE] = (encoded[ProtocolCodec.HEADER_SIZE].toInt() xor 0x01).toByte()
        assertNull(ProtocolCodec.decode(encoded))
    }

    @Test
    fun pairPayloadRoundTripSupportsUtf8Name() {
        val uuid = UUID.randomUUID()
        val publicKey = ByteArray(91) { it.toByte() }
        val encoded = ProtocolCodec.encodePairPayload(uuid, "ארי", publicKey)
        val decoded = ProtocolCodec.decodePairPayload(encoded)
        requireNotNull(decoded)
        assertEquals(uuid, decoded.deviceId)
        assertEquals("ארי", decoded.displayName)
        assertArrayEquals(publicKey, decoded.publicKey)
    }

    @Test
    fun payloadLengthIsReadableFromHeader() {
        val payload = ByteArray(137) { 7 }
        val encoded = ProtocolCodec.encode(ProtocolPacket(PacketType.DATA, 1, 2, 3, payload))
        val header = encoded.copyOfRange(0, ProtocolCodec.HEADER_SIZE)
        assertEquals(payload.size, ProtocolCodec.payloadLengthFromHeader(header))
        assertTrue(encoded.size == ProtocolCodec.HEADER_SIZE + payload.size + ProtocolCodec.CRC_SIZE)
    }
}
