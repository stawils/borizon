package com.borizon.app.data.dao

import androidx.room.*
import com.borizon.app.data.models.MemoryEntry
import com.borizon.app.data.models.MemoryCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntry): Long

    @Query("DELETE FROM memories WHERE id NOT IN (SELECT id FROM memories ORDER BY importance DESC, lastAccessed DESC LIMIT 500)")
    suspend fun pruneToMax()

    @Update
    suspend fun update(memory: MemoryEntry)

    @Query("SELECT * FROM memories ORDER BY importance DESC, createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<MemoryEntry>

    @Query("SELECT * FROM memories ORDER BY importance * (1.0 / (1.0 + (:now - lastAccessed) / 86400000.0)) DESC LIMIT :limit")
    suspend fun getRelevant(now: Long, limit: Int = 20): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC")
    suspend fun getByCategory(category: MemoryCategory): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY importance DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 20): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntry?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("SELECT * FROM memories ORDER BY importance DESC, createdAt DESC")
    fun getAllFlow(): Flow<List<MemoryEntry>>

    @Query("DELETE FROM memories")
    suspend fun deleteAll()

    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessed = :now WHERE id IN (:ids)")
    suspend fun incrementAccessCounts(ids: List<Long>, now: Long = System.currentTimeMillis())
}
