package com.borizon.app.data.dao

import androidx.room.*
import com.borizon.app.data.models.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 200")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE isPinned = 1 ORDER BY updatedAt DESC")
    fun getPinnedConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("UPDATE conversations SET messageCount = :count, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateMessageCount(id: Long, count: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestConversation(): Conversation?

    @Query("SELECT * FROM conversations WHERE sessionRef = :sessionRef LIMIT 1")
    suspend fun getBySessionRef(sessionRef: String): Conversation?

    @Query("UPDATE conversations SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET sessionSummary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun updateSessionSummary(id: Long, summary: String, now: Long = System.currentTimeMillis())

    @Transaction
    suspend fun deleteWithMessages(conversation: Conversation, messageDao: MessageDao) {
        messageDao.deleteForConversation(conversation.id)
        delete(conversation)
    }
}
