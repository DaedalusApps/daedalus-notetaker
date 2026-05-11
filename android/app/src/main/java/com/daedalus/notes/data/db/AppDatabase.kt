package com.daedalus.notes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.daedalus.notes.data.model.Recording

@Database(entities = [Recording::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "daedalus_notes.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
