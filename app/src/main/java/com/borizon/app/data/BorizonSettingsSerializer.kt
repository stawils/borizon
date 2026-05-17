package com.borizon.app.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.borizon.app.proto.BorizonSettings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore serializer for BorizonSettings.
  */
object BorizonSettingsSerializer : Serializer<BorizonSettings> {
    override val defaultValue: BorizonSettings = BorizonSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): BorizonSettings {
        try {
            return BorizonSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read BorizonSettings proto.", exception)
        }
    }

    override suspend fun writeTo(t: BorizonSettings, output: OutputStream) = t.writeTo(output)
}
