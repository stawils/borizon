package com.borizon.app.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["importance", "createdAt"]),
        Index(value = ["category", "importance"]),
        Index(value = ["importance", "lastAccessed"]),
    ]
)
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: MemoryCategory,
    val importance: Float,
    val accessCount: Int = 0,
    val lastAccessed: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val sourceConversationId: Long? = null,
)

enum class MemoryCategory { PREFERENCE, FACT, RELATIONSHIP, EVENT, SKILL }
