package com.aharon.message.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aharon.message.model.ChatMessage
import com.aharon.message.model.Contact
import com.aharon.message.model.MessageStatus
import com.aharon.message.model.PendingPairing

class MessageStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "aharon_message.db"
        private const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE contacts (
                device_id TEXT PRIMARY KEY,
                transport_id INTEGER NOT NULL UNIQUE,
                name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                verification_code TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE pending_pairings (
                device_id TEXT PRIMARY KEY,
                transport_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                verification_code TEXT NOT NULL,
                received_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY,
                contact_id TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                body TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                status TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(contact_id) REFERENCES contacts(device_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_contact_time ON messages(contact_id, timestamp)")
        db.execSQL("CREATE INDEX idx_contacts_transport ON contacts(transport_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    @Synchronized
    fun listContacts(): List<Contact> {
        readableDatabase.query(
            "contacts",
            null,
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC"
        ).use { cursor ->
            val result = mutableListOf<Contact>()
            while (cursor.moveToNext()) {
                result += Contact(
                    deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                    transportId = cursor.getLong(cursor.getColumnIndexOrThrow("transport_id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    publicKeyBase64 = cursor.getString(cursor.getColumnIndexOrThrow("public_key")),
                    verificationCode = cursor.getString(cursor.getColumnIndexOrThrow("verification_code")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                )
            }
            return result
        }
    }

    @Synchronized
    fun contactByDeviceId(deviceId: String): Contact? = listContacts().firstOrNull { it.deviceId == deviceId }

    @Synchronized
    fun contactByTransportId(transportId: Long): Contact? {
        readableDatabase.query(
            "contacts",
            null,
            "transport_id = ?",
            arrayOf(transportId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return Contact(
                deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                transportId = cursor.getLong(cursor.getColumnIndexOrThrow("transport_id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                publicKeyBase64 = cursor.getString(cursor.getColumnIndexOrThrow("public_key")),
                verificationCode = cursor.getString(cursor.getColumnIndexOrThrow("verification_code")),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            )
        }
    }

    @Synchronized
    fun upsertContact(contact: Contact) {
        val values = ContentValues().apply {
            put("device_id", contact.deviceId)
            put("transport_id", contact.transportId)
            put("name", contact.name)
            put("public_key", contact.publicKeyBase64)
            put("verification_code", contact.verificationCode)
            put("created_at", contact.createdAt)
        }
        writableDatabase.insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun listPendingPairings(): List<PendingPairing> {
        readableDatabase.query(
            "pending_pairings",
            null,
            null,
            null,
            null,
            null,
            "received_at DESC"
        ).use { cursor ->
            val result = mutableListOf<PendingPairing>()
            while (cursor.moveToNext()) {
                result += PendingPairing(
                    deviceId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                    transportId = cursor.getLong(cursor.getColumnIndexOrThrow("transport_id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    publicKeyBase64 = cursor.getString(cursor.getColumnIndexOrThrow("public_key")),
                    verificationCode = cursor.getString(cursor.getColumnIndexOrThrow("verification_code")),
                    receivedAt = cursor.getLong(cursor.getColumnIndexOrThrow("received_at")),
                )
            }
            return result
        }
    }

    @Synchronized
    fun upsertPending(pairing: PendingPairing) {
        val values = ContentValues().apply {
            put("device_id", pairing.deviceId)
            put("transport_id", pairing.transportId)
            put("name", pairing.name)
            put("public_key", pairing.publicKeyBase64)
            put("verification_code", pairing.verificationCode)
            put("received_at", pairing.receivedAt)
        }
        writableDatabase.insertWithOnConflict("pending_pairings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun deletePending(deviceId: String) {
        writableDatabase.delete("pending_pairings", "device_id = ?", arrayOf(deviceId))
    }

    @Synchronized
    fun listMessages(): List<ChatMessage> {
        readableDatabase.query(
            "messages",
            null,
            null,
            null,
            null,
            null,
            "timestamp ASC"
        ).use { cursor ->
            val result = mutableListOf<ChatMessage>()
            while (cursor.moveToNext()) {
                result += ChatMessage(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    contactId = cursor.getString(cursor.getColumnIndexOrThrow("contact_id")),
                    outgoing = cursor.getInt(cursor.getColumnIndexOrThrow("outgoing")) != 0,
                    body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
                    timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                    status = MessageStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                )
            }
            return result
        }
    }

    @Synchronized
    fun messageById(id: Long): ChatMessage? = listMessages().firstOrNull { it.id == id }

    @Synchronized
    fun insertMessage(message: ChatMessage) {
        val values = ContentValues().apply {
            put("id", message.id)
            put("contact_id", message.contactId)
            put("outgoing", if (message.outgoing) 1 else 0)
            put("body", message.body)
            put("timestamp", message.timestamp)
            put("status", message.status.name)
            put("retry_count", message.retryCount)
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun updateMessageStatus(id: Long, status: MessageStatus, retryCount: Int? = null) {
        val values = ContentValues().apply {
            put("status", status.name)
            retryCount?.let { put("retry_count", it) }
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id.toString()))
    }
}
