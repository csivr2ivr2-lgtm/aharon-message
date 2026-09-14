package com.aharon.message.engine

import android.content.Context
import android.util.Base64
import com.aharon.message.crypto.IdentityManager
import com.aharon.message.data.MessageStore
import com.aharon.message.model.ChatMessage
import com.aharon.message.model.Contact
import com.aharon.message.model.MessageStatus
import com.aharon.message.model.PendingPairing
import com.aharon.message.protocol.PacketType
import com.aharon.message.protocol.ProtocolCodec
import com.aharon.message.protocol.ProtocolPacket
import com.aharon.message.service.AcousticReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessagingEngine(
    private val context: Context,
    private val store: MessageStore,
    private val identity: IdentityManager,
) {
    data class NotificationEvent(val title: String, val body: String)
    data class IncomingResult(
        val replies: List<ProtocolPacket> = emptyList(),
        val notification: NotificationEvent? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryJobs = ConcurrentHashMap<Long, Boolean>()

    private val _contacts = MutableStateFlow(store.listContacts())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _messages = MutableStateFlow(store.listMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow(store.listPendingPairings())
    val pendingPairings: StateFlow<List<PendingPairing>> = _pending.asStateFlow()

    fun refresh() {
        _contacts.value = store.listContacts()
        _messages.value = store.listMessages()
        _pending.value = store.listPendingPairings()
    }

    fun startPairing() {
        val packet = ProtocolPacket(
            type = PacketType.PAIR_REQUEST,
            senderId = identity.transportId(),
            receiverId = ProtocolCodec.BROADCAST_ID,
            messageId = identity.newMessageId(),
            payload = ProtocolCodec.encodePairPayload(
                identity.deviceUuid,
                identity.displayName,
                identity.publicKeyBytes(),
            )
        )
        AcousticReceiverService.enqueue(context, packet)
    }

    fun confirmPairing(deviceId: String) {
        val pending = store.listPendingPairings().firstOrNull { it.deviceId == deviceId } ?: return
        store.upsertContact(
            Contact(
                deviceId = pending.deviceId,
                transportId = pending.transportId,
                name = pending.name,
                publicKeyBase64 = pending.publicKeyBase64,
                verificationCode = pending.verificationCode,
                createdAt = System.currentTimeMillis(),
            )
        )
        store.deletePending(deviceId)
        refresh()
    }

    fun rejectPairing(deviceId: String) {
        store.deletePending(deviceId)
        refresh()
    }

    fun sendText(contact: Contact, text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512) { "Message is limited to 512 UTF-8 bytes" }

        val messageId = identity.newMessageId()
        val message = ChatMessage(
            id = messageId,
            contactId = contact.deviceId,
            outgoing = true,
            body = normalized,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.QUEUED,
        )
        store.insertMessage(message)
        refresh()
        AcousticReceiverService.enqueue(context, buildDataPacket(message, contact))
    }

    fun onPacketTransmitted(packet: ProtocolPacket) {
        if (packet.type != PacketType.DATA) return
        val message = store.messageById(packet.messageId) ?: return
        if (!message.outgoing || message.status == MessageStatus.DELIVERED) return
        store.updateMessageStatus(message.id, MessageStatus.SENT, message.retryCount)
        refresh()
        scheduleRetry(message.id)
    }

    fun resumeRetries() {
        store.listMessages()
            .filter { it.outgoing && it.status == MessageStatus.SENT && it.retryCount < MAX_RETRIES }
            .forEach { scheduleRetry(it.id, initialDelayMs = 2_000L) }
    }

    fun handleIncoming(packet: ProtocolPacket): IncomingResult {
        if (packet.senderId == identity.transportId()) return IncomingResult()
        if (packet.receiverId != ProtocolCodec.BROADCAST_ID && packet.receiverId != identity.transportId()) {
            return IncomingResult()
        }

        return when (packet.type) {
            PacketType.PAIR_REQUEST -> handlePair(packet, respond = true)
            PacketType.PAIR_RESPONSE -> handlePair(packet, respond = false)
            PacketType.DATA -> handleData(packet)
            PacketType.ACK -> handleAck(packet)
            PacketType.PING -> IncomingResult(
                replies = listOf(
                    ProtocolPacket(
                        PacketType.PONG,
                        identity.transportId(),
                        packet.senderId,
                        packet.messageId,
                    )
                )
            )
            PacketType.PONG -> IncomingResult()
        }
    }

    private fun handlePair(packet: ProtocolPacket, respond: Boolean): IncomingResult {
        val pair = ProtocolCodec.decodePairPayload(packet.payload) ?: return IncomingResult()
        if (pair.deviceId == identity.deviceUuid) return IncomingResult()
        val keyBase64 = Base64.encodeToString(pair.publicKey, Base64.NO_WRAP)
        val verification = runCatching { identity.verificationCode(pair.publicKey) }.getOrNull()
            ?: return IncomingResult()
        val pending = PendingPairing(
            deviceId = pair.deviceId.toString(),
            transportId = packet.senderId,
            name = pair.displayName.ifBlank { "Nearby device" }.take(32),
            publicKeyBase64 = keyBase64,
            verificationCode = verification,
            receivedAt = System.currentTimeMillis(),
        )
        store.upsertPending(pending)
        refresh()

        val replies = if (respond) {
            listOf(
                ProtocolPacket(
                    type = PacketType.PAIR_RESPONSE,
                    senderId = identity.transportId(),
                    receiverId = packet.senderId,
                    messageId = packet.messageId,
                    payload = ProtocolCodec.encodePairPayload(
                        identity.deviceUuid,
                        identity.displayName,
                        identity.publicKeyBytes(),
                    )
                )
            )
        } else emptyList()

        return IncomingResult(
            replies = replies,
            notification = NotificationEvent(
                title = "Pairing request",
                body = "${pending.name} · verify ${pending.verificationCode}",
            )
        )
    }

    private fun handleData(packet: ProtocolPacket): IncomingResult {
        val contact = store.contactByTransportId(packet.senderId) ?: return IncomingResult()
        val publicKey = Base64.decode(contact.publicKeyBase64, Base64.NO_WRAP)
        val plaintext = runCatching {
            identity.decrypt(publicKey, packet.payload, ProtocolCodec.aad(packet))
        }.getOrNull() ?: return IncomingResult()
        val body = plaintext.toString(Charsets.UTF_8)
        if (body.isBlank() || body.toByteArray(Charsets.UTF_8).size > 512) return IncomingResult()

        if (store.messageById(packet.messageId) == null) {
            store.insertMessage(
                ChatMessage(
                    id = packet.messageId,
                    contactId = contact.deviceId,
                    outgoing = false,
                    body = body,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.DELIVERED,
                )
            )
            refresh()
        }

        val ack = ProtocolPacket(
            type = PacketType.ACK,
            senderId = identity.transportId(),
            receiverId = packet.senderId,
            messageId = packet.messageId,
        )
        return IncomingResult(
            replies = listOf(ack),
            notification = NotificationEvent(contact.name, body),
        )
    }

    private fun handleAck(packet: ProtocolPacket): IncomingResult {
        val message = store.messageById(packet.messageId) ?: return IncomingResult()
        if (message.outgoing) {
            store.updateMessageStatus(message.id, MessageStatus.DELIVERED, message.retryCount)
            retryJobs.remove(message.id)
            refresh()
        }
        return IncomingResult()
    }

    private fun buildDataPacket(message: ChatMessage, contact: Contact): ProtocolPacket {
        val shell = ProtocolPacket(
            type = PacketType.DATA,
            senderId = identity.transportId(),
            receiverId = contact.transportId,
            messageId = message.id,
        )
        val publicKey = Base64.decode(contact.publicKeyBase64, Base64.NO_WRAP)
        val encrypted = identity.encrypt(
            publicKey,
            message.body.toByteArray(Charsets.UTF_8),
            ProtocolCodec.aad(shell),
        )
        return shell.copy(payload = encrypted)
    }

    private fun scheduleRetry(messageId: Long, initialDelayMs: Long = RETRY_DELAY_MS) {
        if (retryJobs.putIfAbsent(messageId, true) != null) return
        scope.launch {
            try {
                delay(initialDelayMs)
                while (true) {
                    val message = store.messageById(messageId) ?: break
                    if (message.status == MessageStatus.DELIVERED) break
                    if (message.retryCount >= MAX_RETRIES) {
                        store.updateMessageStatus(message.id, MessageStatus.FAILED, message.retryCount)
                        refresh()
                        break
                    }
                    val contact = store.contactByDeviceId(message.contactId) ?: break
                    val nextRetry = message.retryCount + 1
                    store.updateMessageStatus(message.id, MessageStatus.SENT, nextRetry)
                    refresh()
                    AcousticReceiverService.enqueue(context, buildDataPacket(message.copy(retryCount = nextRetry), contact))
                    delay(RETRY_DELAY_MS * (nextRetry + 1))
                }
            } finally {
                retryJobs.remove(messageId)
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 6_000L
    }
}
