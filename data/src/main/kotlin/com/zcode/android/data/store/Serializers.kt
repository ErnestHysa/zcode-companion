package com.zcode.android.data.store

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.zcode.android.data.proto.AppPreferencesProto
import com.zcode.android.data.proto.CredentialEnvelopeProto
import com.zcode.android.data.proto.EndpointStoreProto
import com.zcode.android.data.proto.NotificationRecordStoreProto
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore serializers (§17.2/§32.3): additive-first schemas — unknown/absent
 * fields fall back to defaults (protobuf wire compatibility); corruption fails typed
 * (§33 storage category), never silently reinitialized for the credential class.
 */
object EndpointStoreSerializer : Serializer<EndpointStoreProto> {
    override val defaultValue: EndpointStoreProto = EndpointStoreProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): EndpointStoreProto =
        try {
            EndpointStoreProto.parseFrom(input)
        } catch (e: Exception) {
            throw CorruptionException("endpoint store corrupted", e)
        }

    override suspend fun writeTo(t: EndpointStoreProto, output: OutputStream) = t.writeTo(output)
}

object AppPreferencesSerializer : Serializer<AppPreferencesProto> {
    override val defaultValue: AppPreferencesProto = AppPreferencesProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): AppPreferencesProto =
        try {
            AppPreferencesProto.parseFrom(input)
        } catch (e: Exception) {
            throw CorruptionException("preferences store corrupted", e)
        }

    override suspend fun writeTo(t: AppPreferencesProto, output: OutputStream) = t.writeTo(output)
}

object CredentialEnvelopeSerializer : Serializer<CredentialEnvelopeProto> {
    override val defaultValue: CredentialEnvelopeProto = CredentialEnvelopeProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): CredentialEnvelopeProto =
        try {
            CredentialEnvelopeProto.parseFrom(input)
        } catch (e: Exception) {
            // §32.3: credential envelope corruption NEVER reinitializes (fail-closed).
            throw CorruptionException("credential envelope corrupted — refusing reinit (§32.3)", e)
        }

    override suspend fun writeTo(t: CredentialEnvelopeProto, output: OutputStream) = t.writeTo(output)
}

object DataStoreNotificationRecordStore : Serializer<NotificationRecordStoreProto> {
    override val defaultValue: NotificationRecordStoreProto = NotificationRecordStoreProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): NotificationRecordStoreProto =
        try {
            NotificationRecordStoreProto.parseFrom(input)
        } catch (e: Exception) {
            throw CorruptionException("notification record store corrupted", e)
        }

    override suspend fun writeTo(t: NotificationRecordStoreProto, output: OutputStream) = t.writeTo(output)
}
