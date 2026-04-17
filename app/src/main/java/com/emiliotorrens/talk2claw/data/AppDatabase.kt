package com.emiliotorrens.talk2claw.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for Talk2Claw persistent storage.
 */
@Database(entities = [TranscriptEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
}
