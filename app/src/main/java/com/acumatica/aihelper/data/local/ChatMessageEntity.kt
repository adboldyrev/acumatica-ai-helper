package com.acumatica.aihelper.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String, // "user", "bot", "system"
    val text: String,
    val entityName: String? = null,
    val recordKey: String? = null,
    val attachmentUrl: String? = null,
    val barcodeScanned: String? = null,
    val ocrTextExtracted: String? = null,
    val isVoiceInput: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isPendingOffline: Boolean = false
)