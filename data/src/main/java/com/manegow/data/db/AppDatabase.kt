package com.manegow.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.manegow.data.db.dao.ChatDao
import com.manegow.data.db.dao.MessageDao
import com.manegow.data.db.entities.ChatEntity
import com.manegow.data.db.entities.MessageEntity

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "deschat_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
