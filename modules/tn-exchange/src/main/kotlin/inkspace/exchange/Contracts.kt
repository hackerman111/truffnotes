package inkspace.exchange

import inkspace.core.id.AssetId
import inkspace.core.model.*
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

class ExchangeException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

data class ArchiveLimits(
    val maxExpandedBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntryBytes: Int = 8 * 1024 * 1024,
    val maxEntries: Int = 200_000,
    val maxManifestBytes: Int = 16 * 1024 * 1024
) {
    init { require(maxExpandedBytes > 0 && maxEntryBytes > 0 && maxEntries > 0 && maxManifestBytes > 0) }
}

@Serializable
enum class NativePurpose { Document, ObjectBundle, Collection, Template }

@Serializable
data class NativeDocument(val version: DocVersion, val kind: DocumentKind,
    val pages: List<PageDescriptor>, val layers: List<LayerDescriptor>, val objectCount: Int,
    val assets: List<String>, val purpose: NativePurpose = NativePurpose.Document)

@Serializable
data class NativeEntry(val path: String, val bytes: Long, val sha256: String)

@Serializable
data class NativeManifest(val schema: Int = 1, val documentId: String, val revision: Long,
    val entries: List<NativeEntry>)

/** A revision-frozen lease. Records must be returned in ascending ID order, once only. */
interface SnapshotReader : AutoCloseable {
    val document: NativeDocument
    fun records(): Sequence<SceneObject>
    fun asset(id: AssetId): InputStream
}

/** Resident-only adapter: callers must hold a Core snapshot pin and pin the referenced assets. */
class ResidentSnapshotReader(private val snapshot: DocumentSnapshot, purpose: NativePurpose,
    private val openAsset: (AssetId) -> InputStream) : SnapshotReader {
    constructor(snapshot: DocumentSnapshot, openAsset: (AssetId) -> InputStream) : this(snapshot,NativePurpose.Document,openAsset)
    init {
        require(snapshot.directory.allResidentObjects().size == snapshot.totalObjectCount) { "A bounded storage reader is required for nonresident records" }
        require(snapshot.verifyInvariants()) { "Invalid snapshot" }
    }
    override val document = NativeDocument(snapshot.version, snapshot.kind, snapshot.pages, snapshot.layers,
        snapshot.totalObjectCount, snapshot.referencedAssets().map { it.value }.sorted(),purpose)
    override fun records() = snapshot.directory.allResidentObjects().sortedBy { it.id.value }.asSequence()
    override fun asset(id: AssetId) = openAsset(id)
    override fun close() = Unit
}

/** All callbacks stage untrusted data. commit alone may publish, atomically and without overwriting IDs.
 * The sink must reject an existing ID unless the caller explicitly requested a fully remapped copy.
 * abort must remove staged records/assets even if commit failed. */
interface ImportSink {
    fun document(value: NativeDocument)
    fun record(path: String, bytes: ByteArray)
    fun asset(path: String): OutputStream
    fun commit(manifest: NativeManifest)
    fun abort()
}

/** A fresh URI, or a provider-backed atomic replacement transaction. */
interface ExportOutput {
    val replacesExisting: Boolean
    val atomicReplacement: Boolean
    fun open(): OutputStream
    fun complete()
    fun abort()
}

object SafeExport {
    fun write(output: ExportOutput, writer: (OutputStream) -> Unit) {
        if (output.replacesExisting && !output.atomicReplacement)
            throw ExchangeException("Existing output requires proven atomic replacement")
        try {
            output.open().use { writer(it); it.flush() }
            output.complete()
        } catch (failure: Exception) {
            try { output.abort() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
