package com.borizon.app.data.dao

import androidx.room.*
import com.borizon.app.data.models.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesBefore(conversationId: Long, beforeTimestamp: Long, limit: Int): List<Message>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND role = 'ASSISTANT' AND id = (SELECT MAX(id) FROM messages WHERE conversationId = :conversationId AND role = 'ASSISTANT')")
    suspend fun deleteLastAssistantMessage(conversationId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
