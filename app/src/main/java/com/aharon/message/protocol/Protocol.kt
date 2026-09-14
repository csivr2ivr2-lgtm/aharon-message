package com.aharon.message.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

enum class PacketType(val code: Int) {
    PAIR_REQUEST(1),
    PAIR_RESPONSE(2),
    DATA(3),
    ACK(4),
    PING(5),
    PONG(6),
    DATA_FRAGMENT(7),
    ACK_FRAGMENT(8),
    CALIBRATION_PROBE(9),
    CALIBRATION_RESULT(10);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

data class ProtocolPacket(
    val type: PacketType,
    val senderId: Long,
    val receiverId: Long,
    val messageId: Long,
    val payload: ByteArray = ByteArray(0),
)

data class PairPayload(
    val deviceId: UUID,
    val displayName: String,
    val publicKey: ByteArray,
)

data class FragmentPayload(
    val sequence: Int,
    val total: Int,
    val fecProtected: Boolean,
    val encryptedChunk: ByteArray,
)

object ProtocolCodec {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 28
    const val CRC_SIZE: Int = 4
    const val MAX_PAYLOAD: Int = 1024
    const val BROADCAST_ID: Long = 0L
    const val FRAGMENT_HEADER_SIZE: Int = 5
    const val MESSAGE_CHUNK_BYTES: Int = 96
    private const val FLAG_FEC = 0x01

    fun encode(packet: ProtocolPacket): ByteArray {
        require(packet.payload.size <= MAX_PAYLOAD) { "Payload exceeds $MAX_PAYLOAD bytes" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + packet.payload.size + CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION.toByte())
        buffer.put(packet.type.code.toByte())
        buffer.putLong(packet.senderId)
        buffer.putLong(packet.receiverId)
        buffer.putLong(packet.messageId)
        buffer.putShort(packet.payload.size.toShort())
        buffer.put(packet.payload)

        val withoutCrc = buffer.array().copyOfRange(0, HEADER_SIZE + packet.payload.size)
        val crc = CRC32().apply { update(withoutCrc) }.value
        buffer.putInt(crc.toInt())
        return buffer.array()
    }

    fun decode(bytes: ByteArray): ProtocolPacket? {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) return null
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = header.get().toInt() and 0xff
        if (version != VERSION) return null
        val type = PacketType.fromCode(header.get().toInt() and 0xff) ?: return null
        val sender = header.long
        val receiver = header.long
        val messageId = header.long
        val payloadLength = header.short.toInt() and 0xffff
        if (payloadLength > MAX_PAYLOAD || bytes.size != HEADER_SIZE + payloadLength + CRC_SIZE) return null

        val expected = ByteBuffer.wrap(bytes, bytes.size - CRC_SIZE, CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        val actual = CRC32().apply { update(bytes, 0, bytes.size - CRC_SIZE) }.value
        if (expected != actual) return null

        val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)
        return ProtocolPacket(type, sender, receiver, messageId, payload)
    }

    fun payloadLengthFromHeader(header: ByteArray): Int? {
        if (header.size != HEADER_SIZE) return null
        val version = header[0].toInt() and 0xff
        if (version != VERSION) return null
        val value = ByteBuffer.wrap(header, HEADER_SIZE - 2, 2).order(ByteOrder.BIG_ENDIAN)
            .short.toInt() and 0xffff
        return value.takeIf { it <= MAX_PAYLOAD }
    }

    fun encodePairPayload(deviceId: UUID, displayName: String, publicKey: ByteArray): ByteArray {
        val name = displayName.trim().take(32).toByteArray(Charsets.UTF_8)
        require(name.size <= 255)
        require(publicKey.size <= 65535)
        return ByteBuffer.allocate(16 + 1 + name.size + 2 + publicKey.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putLong(deviceId.mostSignificantBits)
                putLong(deviceId.leastSignificantBits)
                put(name.size.toByte())
                put(name)
                putShort(publicKey.size.toShort())
                put(publicKey)
            }.array()
    }

    fun decodePairPayload(payload: ByteArray): PairPayload? = runCatching {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val uuid = UUID(buffer.long, buffer.long)
        val nameLength = buffer.get().toInt() and 0xff
        require(nameLength <= buffer.remaining() - 2)
        val nameBytes = ByteArray(nameLength).also(buffer::get)
        val keyLength = buffer.short.toInt() and 0xffff
        require(keyLength > 0 && keyLength == buffer.remaining())
        val key = ByteArray(keyLength).also(buffer::get)
        PairPayload(uuid, nameBytes.toString(Charsets.UTF_8), key)
    }.getOrNull()

    fun encodeFragmentPayload(
        sequence: Int,
        total: Int,
        encryptedChunk: ByteArray,
        fecProtected: Boolean,
    ): ByteArray {
        require(sequence in 0 until total)
        require(total in 1..65535)
        require(encryptedChunk.size + FRAGMENT_HEADER_SIZE <= MAX_PAYLOAD)
        val flags = if (fecProtected) FLAG_FEC else 0
        return ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + encryptedChunk.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(sequence.toShort())
            .putShort(total.toShort())
            .put(flags.toByte())
            .put(encryptedChunk)
            .array()
    }

    fun decodeFragmentPayload(payload: ByteArray): FragmentPayload? = runCatching {
        require(payload.size >= FRAGMENT_HEADER_SIZE + 28)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val sequence = buffer.short.toInt() and 0xffff
        val total = buffer.short.toInt() and 0xffff
        val flags = buffer.get().toInt() and 0xff
        require(total in 1..65535 && sequence < total)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        FragmentPayload(sequence, total, (flags and FLAG_FEC) != 0, encrypted)
    }.getOrNull()

    fun encodeFragmentAck(sequence: Int, total: Int): ByteArray =
        ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(sequence.toShort())
            .putShort(total.toShort())
            .array()

    fun decodeFragmentAck(payload: ByteArray): Pair<Int, Int>? = runCatching {
        require(payload.size == 4)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val sequence = buffer.short.toInt() and 0xffff
        val total = buffer.short.toInt() and 0xffff
        require(total > 0 && sequence < total)
        sequence to total
    }.getOrNull()

    fun aad(packet: ProtocolPacket): ByteArray = ByteBuffer.allocate(24)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(packet.senderId)
        .putLong(packet.receiverId)
        .putLong(packet.messageId)
        .array()

    fun fragmentAad(packet: ProtocolPacket, sequence: Int, total: Int): ByteArray =
        ByteBuffer.allocate(28)
            .order(ByteOrder.BIG_ENDIAN)
            .put(aad(packet))
            .putShort(sequence.toShort())
            .putShort(total.toShort())
            .array()
}
