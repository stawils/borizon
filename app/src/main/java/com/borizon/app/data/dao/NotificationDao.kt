package com.borizon.app.data.dao

import androidx.room.*
import com.borizon.app.data.models.NotificationEntry

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntry): Long

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<NotificationEntry>

    @Query("SELECT * FROM notifications WHERE title LIKE '%' || :query || '%' ESCAPE '\\' OR text LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<NotificationEntry>

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun count(): Int

    @Query("DELETE FROM notifications WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}
