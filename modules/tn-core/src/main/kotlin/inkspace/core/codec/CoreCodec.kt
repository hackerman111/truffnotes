package inkspace.core.codec

import inkspace.core.error.SerializationError
import inkspace.core.outcome.Outcome
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object CoreCodec {

    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        prettyPrint = false
    }

    inline fun <reified T> encodeToCbor(value: T): ByteArray {
        return cbor.encodeToByteArray(value)
    }

    inline fun <reified T> decodeFromCbor(bytes: ByteArray): Outcome<T, SerializationError> {
        return try {
            val value = cbor.decodeFromByteArray<T>(bytes)
            Outcome.Success(value)
        } catch (e: Exception) {
            Outcome.Failure(SerializationError(e.message ?: "Failed to decode CBOR payload", e.cause?.toString()))
        }
    }

    inline fun <reified T> encodeToJson(value: T): String {
        return json.encodeToString(value)
    }

    inline fun <reified T> decodeFromJson(jsonString: String): Outcome<T, SerializationError> {
        // Enforce NaN and Infinity wire rejection
        if (jsonString.contains("NaN") || jsonString.contains("Infinity") || jsonString.contains("-Infinity")) {
            return Outcome.Failure(
                SerializationError("Wire rejection: Non-finite numerical values (NaN/Infinity) are strictly prohibited")
            )
        }

        return try {
            val value = json.decodeFromString<T>(jsonString)
            Outcome.Success(value)
        } catch (e: Exception) {
            Outcome.Failure(SerializationError(e.message ?: "Failed to decode JSON payload", e.cause?.toString()))
        }
    }
}
