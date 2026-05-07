package com.manegow.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relay_messages")
data class RelayMessageEntity(
    @PrimaryKey val messageId: String,
    val destinationId: String,
    val senderId: String,
    val body: String,
    val createdAtEpochMillis: Long,
    val ttl: Int,
    val receivedAt: Long = System.currentTimeMillis()
)
