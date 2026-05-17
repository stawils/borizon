package com.borizon.app.data.dao

import androidx.room.*
import com.borizon.app.data.models.Reflection
import kotlinx.coroutines.flow.Flow

@Dao
interface ReflectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reflection: Reflection): Long

    @Update
    suspend fun update(reflection: Reflection)

    @Query("SELECT * FROM reflections ORDER BY timestamp DESC LIMIT 500")
    fun getAllReflections(): Flow<List<Reflection>>

    @Query("SELECT * FROM reflections ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentReflections(limit: Int): List<Reflection>

    @Query("SELECT * FROM reflections WHERE isProcessed = 0 ORDER BY timestamp ASC")
    suspend fun getUnprocessedReflections(): List<Reflection>

    @Query("SELECT * FROM reflections WHERE id = :id")
    suspend fun getById(id: Long): Reflection?

    @Query("UPDATE reflections SET isProcessed = 1 WHERE id = :id")
    suspend fun markProcessed(id: Long)

    @Query("SELECT COUNT(*) FROM reflections")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM reflections WHERE isProcessed = 0")
    suspend fun countUnprocessed(): Int

    @Query("SELECT * FROM reflections WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    suspend fun getReflectionsInRange(start: Long, end: Long): List<Reflection>

    @Query("SELECT topics FROM reflections")
    suspend fun getAllTopics(): List<String>

    @Query("SELECT * FROM reflections WHERE sessionRef = :sessionRef ORDER BY timestamp ASC")
    suspend fun getBySessionRef(sessionRef: String): List<Reflection>

    @Query("SELECT * FROM reflections WHERE userText LIKE '%' || :query || '%' ESCAPE '\\' OR borizonResponse LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchReflections(query: String, limit: Int = 20): List<Reflection>

    @Query("SELECT COUNT(DISTINCT DATE(timestamp / 1000, 'unixepoch')) FROM reflections")
    suspend fun countDistinctDays(): Int

    @Query("SELECT MAX(timestamp) FROM reflections")
    suspend fun lastReflectionTimestamp(): Long?

    @Query("DELETE FROM reflections")
    suspend fun deleteAllReflections()

    @Query("DELETE FROM reflections WHERE id NOT IN (SELECT id FROM reflections ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun pruneToMax(keep: Int = 500)
}
