package com.borizon.app.data.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A single thinking session entry -- the core data unit for idea capture.
 * Created from conversations via the PROCESS mode.
 * Stored in Room for structured queries.
 */
@Entity(
    tableName = "reflections",
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [
        Index("conversationId"),
        Index(value = ["isProcessed", "timestamp"]),
        Index("timestamp"),
        Index("sessionRef"),
    ]
)
@Serializable
data class Reflection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val conversationId: Long? = null,  // Link to source conversation
    val userText: String,
    val borizonResponse: String,
    val topics: String = "",     // comma-separated: "research,design,assessment"
    val isProcessed: Boolean = false,

    // Session file reference and media
    val sessionRef: String = "",        // e.g., "2026-04-10/session-0915"
    val mediaRefs: List<String> = emptyList()  // media file paths
)
