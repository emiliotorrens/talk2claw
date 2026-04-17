package com.emiliotorrens.talk2claw.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent transcript entry stored in Room.
 */
@Entity(tableName = "transcript")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** "user", "claw", or "error" */
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)
