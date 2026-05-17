package com.borizon.app.data.database

import android.util.Log
import androidx.room.TypeConverter

/**
 * Type converters for Room database.
 * Uses NUL character (\u0000) as delimiter instead of comma
 * to avoid splitting user text that contains commas.
 */
class Converters {

    companion object {
        private const val DELIMITER = "\u0000"
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String =
        value.joinToString(DELIMITER) { it.replace(DELIMITER, "") }

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(DELIMITER).filter { it.isNotBlank() }

    @TypeConverter
    fun fromLongList(value: List<Long>): String = value.joinToString(DELIMITER)

    @TypeConverter
    fun toLongList(value: String): List<Long> =
        if (value.isBlank()) emptyList()
        else value.split(DELIMITER).mapNotNull { it.toLongOrNull() }

    @TypeConverter
    fun fromMessageRole(value: com.borizon.app.data.models.MessageRole): String = value.name

    @TypeConverter
    fun toMessageRole(value: String): com.borizon.app.data.models.MessageRole =
        try { com.borizon.app.data.models.MessageRole.valueOf(value) }
        catch (e: Exception) {
            Log.w("Converters", "Unknown MessageRole: $value", e)
            com.borizon.app.data.models.MessageRole.USER
        }

    @TypeConverter
    fun fromMemoryCategory(value: com.borizon.app.data.models.MemoryCategory): String = value.name

    @TypeConverter
    fun toMemoryCategory(value: String): com.borizon.app.data.models.MemoryCategory =
        try { com.borizon.app.data.models.MemoryCategory.valueOf(value) }
        catch (e: Exception) {
            Log.w("Converters", "Unknown MemoryCategory: $value", e)
            com.borizon.app.data.models.MemoryCategory.FACT
        }

}
