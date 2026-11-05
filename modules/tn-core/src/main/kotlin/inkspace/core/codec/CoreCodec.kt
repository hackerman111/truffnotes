package inkspace.core.codec

import inkspace.core.error.SerializationError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.Revision
import inkspace.core.mutation.EditPlan
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ObjectPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
object CoreCodec {

    const val CURRENT_SCHEMA_VERSION: Int = 1

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

    /**
     * Computes a deterministic 64-bit FNV-1a digest over the canonical CBOR representation of an ObjectPayload.
     * Independent of JVM object layout or memory addresses.
     */
    fun computePayloadDigest(payload: ObjectPayload): Long {
        val bytes = encodeToCbor<ObjectPayload>(payload)
        return fnv1a64(bytes)
    }

    fun fnv1a64(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325UL
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return hash
    }
}

@Serializable
data class WriteRecord(
    val entityKind: String,
    val entityId: String,
    val operation: String,
    val newVersion: Long,
    val expectedDigest: Long
)

@Serializable
data class WriteSet(
    val schemaVersion: Int = CoreCodec.CURRENT_SCHEMA_VERSION,
    val documentId: DocumentId,
    val baseRevision: Revision,
    val newRevision: Revision,
    val plan: EditPlan,
    val records: List<WriteRecord>,
    val referencedAssets: Set<AssetId> = emptySet()
) {
    init {
        require(schemaVersion == CoreCodec.CURRENT_SCHEMA_VERSION) {
            "Unsupported WriteSet schemaVersion: $schemaVersion (expected ${CoreCodec.CURRENT_SCHEMA_VERSION})"
        }
    }
}

object WriteSetCodec {
    fun encodeToCbor(writeSet: WriteSet): ByteArray = CoreCodec.encodeToCbor(writeSet)

    fun decodeFromCbor(bytes: ByteArray): Outcome<WriteSet, SerializationError> =
        CoreCodec.decodeFromCbor(bytes)

    fun encodeToJson(writeSet: WriteSet): String = CoreCodec.encodeToJson(writeSet)

    fun decodeFromJson(jsonString: String): Outcome<WriteSet, SerializationError> =
        CoreCodec.decodeFromJson(jsonString)
}
