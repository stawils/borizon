package com.borizon.app.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A single conversation thread between the user and Borizon.
 * Each conversation can spawn reflections and journal entries.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["isPinned", "updatedAt"]),
        Index("sessionRef"),
    ]
)
@Serializable
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val isPinned: Boolean = false,
    /** Session reference, e.g. "2026-04-15/session-0915". Links conversation to session data. */
    val sessionRef: String = "",
    /** Compaction summary — persists prior context across app restarts. */
    val summary: String = "",
    /** Comprehensive session summary — key facts, topics, decisions from the conversation. Updated at compaction and background. */
    val sessionSummary: String = ""
)
