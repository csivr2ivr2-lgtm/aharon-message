package com.aharon.message.model

data class Contact(
    val deviceId: String,
    val transportId: Long,
    val name: String,
    val publicKeyBase64: String,
    val verificationCode: String,
    val createdAt: Long,
)

data class PendingPairing(
    val deviceId: String,
    val transportId: Long,
    val name: String,
    val publicKeyBase64: String,
    val verificationCode: String,
    val receivedAt: Long,
)

enum class MessageStatus {
    QUEUED,
    SENT,
    DELIVERED,
    FAILED,
}

data class ChatMessage(
    val id: Long,
    val contactId: String,
    val outgoing: Boolean,
    val body: String,
    val timestamp: Long,
    val status: MessageStatus,
    val retryCount: Int = 0,
)

data class AudioDiagnostics(
    val microphoneNearUltrasound: Boolean?,
    val speakerNearUltrasound: Boolean?,
    val profileName: String,
    val receiverRunning: Boolean = false,
    val receivedFrames: Long = 0,
    val crcErrors: Long = 0,
    val lastSignalDb: Double? = null,
)
