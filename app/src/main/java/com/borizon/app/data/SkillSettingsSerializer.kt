package com.borizon.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.borizon.app.proto.Skills
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore serializer for Skills.
 * Same pattern as BorizonSettingsSerializer.
 */
object SkillSettingsSerializer : Serializer<Skills> {
    override val defaultValue: Skills = Skills.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): Skills {
        try {
            return Skills.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read Skills proto.", exception)
        }
    }

    override suspend fun writeTo(t: Skills, output: OutputStream) = t.writeTo(output)
}
