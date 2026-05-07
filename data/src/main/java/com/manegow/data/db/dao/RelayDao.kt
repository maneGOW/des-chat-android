package com.manegow.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.manegow.data.db.entities.RelayMessageEntity

@Dao
interface RelayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: RelayMessageEntity)

    @Query("SELECT * FROM relay_messages WHERE destinationId = :destId")
    suspend fun getPendingFor(destId: String): List<RelayMessageEntity>

    @Query("DELETE FROM relay_messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM relay_messages WHERE receivedAt < :timestamp")
    suspend fun deleteOldMessages(timestamp: Long)
}
