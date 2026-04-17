package com.emiliotorrens.talk2claw.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data access object for transcript entries.
 */
@Dao
interface TranscriptDao {

    @Insert
    suspend fun insert(entry: TranscriptEntity)

    /** Get the most recent entries, ordered oldest-first for display. */
    @Query("SELECT * FROM transcript ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<TranscriptEntity>

    @Query("DELETE FROM transcript")
    suspend fun deleteAll()

    @Query("DELETE FROM transcript WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
