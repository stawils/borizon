package com.borizon.app.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [
        Index("timestamp"),
        Index(value = ["packageName", "timestamp", "title"], unique = true),
    ]
)
data class NotificationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)
