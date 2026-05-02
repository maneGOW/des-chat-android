package com.manegow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.manegow.data.db.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAtEpochMillis ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryState = :deliveryState WHERE messageId = :messageId")
    suspend fun updateDeliveryState(messageId: String, deliveryState: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
