package inkspace.runtime

import android.content.Context
import android.net.Uri
import inkspace.core.codec.CoreCodec
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.exchange.*
import inkspace.store.DocumentStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

data class NativeImportResult(val snapshot: DocumentSnapshot, val openedExisting: Boolean, val template: Boolean = false)

/** Blocking file work: invoke only on a bounded exchange worker, never Main or the Core actor. */
class AndroidNativeExchange(private val context: Context, private val storageRoot: File,
    private val existingSnapshot: (String) -> DocumentSnapshot?) {

    fun exportNative(snapshot: DocumentSnapshot, createdUri: Uri, scope: NativeScope? = null,
        purpose: NativePurpose = NativePurpose.Document, visibleLayersOnly: Boolean = false, layerIds: Set<LayerId>? = null, canceled: () -> Boolean = { false }) {
        SafeExport.write(AndroidExchangeOutput(context.contentResolver, createdUri)) { output ->
            NativeBundles.write(snapshot, purpose, output, scope, layerIds = layerIds, visibleLayersOnly = visibleLayersOnly, canceled = canceled) { openAsset(snapshot.version.document, it) }
        }
    }

    /** A collision opens only an exactly matching generation; copyId is an explicit user choice. */
    fun importNative(uri: Uri, copyId: DocumentId? = null, canceled: () -> Boolean = { false }): NativeImportResult {
        val staging = File(context.cacheDir, "native-import-${UUID.randomUUID()}")
        check(staging.mkdirs())
        var result: NativeImportResult? = null
        val sink = object : ImportSink {
            lateinit var header: NativeDocument
            val records = ArrayList<File>()
            val assets = LinkedHashMap<String, File>()
            override fun document(value: NativeDocument) {
                require(value.version.document.value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Unsafe document ID" }
                header = value
            }
            override fun record(path: String, bytes: ByteArray) {
                val file = File(staging,"record-${records.size}.cbor")
                FileOutputStream(file).use { it.write(bytes) }; records += file
            }
            override fun asset(path: String): OutputStream {
                val file = File(staging,"asset-${assets.size}")
                assets[path] = file
                return FileOutputStream(file)
            }
            override fun commit(manifest: NativeManifest) {
                var snapshot = restoreSnapshot(SnapshotMetadata(header.version,header.kind,header.pages,header.layers), records.asSequence().map {
                    CoreCodec.decodeFromCbor<SceneObject>(it.readBytes()).getOrNull() ?: throw ExchangeException("Invalid object")
                })
                val existing = existingSnapshot(header.version.document.value)
                if (copyId == null && existing != null) {
                    val current = ResidentSnapshotReader(existing) { openAsset(existing.version.document,it) }.use {
                        NativeArchive.write(it, object : OutputStream() { override fun write(value:Int) = Unit; override fun write(bytes:ByteArray,offset:Int,length:Int) = Unit })
                    }
                    if (current != manifest) throw ExchangeException("Document ID collision: explicitly import as copy")
                    result = NativeImportResult(existing,true,header.purpose == NativePurpose.Template)
                    return
                }
                if (copyId != null) snapshot = nativeCopy(snapshot,copyId)
                if (canceled()) throw ExchangeException("Canceled")
                val publication = File(storageRoot, "native-publications/${snapshot.version.document.value}")
                check(publication.parentFile.isDirectory || publication.parentFile.mkdirs())
                FileOutputStream(publication).use { it.write(header.purpose.name.toByteArray()); it.fd.sync() }
                val installed = DocumentStore(storageRoot).installSnapshot(snapshot) { asset ->
                    requireNotNull(assets[NativeArchive.assetPath(asset.value)]) { "Missing staged asset" }.inputStream()
                }
                if (installed.isFailure) { publication.delete(); throw ExchangeException(installed.errorOrNull().toString()) }
                result = NativeImportResult(snapshot,false,header.purpose == NativePurpose.Template)
            }
            override fun abort() { staging.deleteRecursively() }
        }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw ExchangeException("Provider refused input")
            input.use { NativeArchive.read(it,sink,ArchiveLimits(maxExpandedBytes=128L*1024*1024),canceled) }
            return requireNotNull(result)
        } finally { staging.deleteRecursively() }
    }

    /** Retry interrupted library publication; a failed library write leaves its receipt for the next launch. */
    fun publishPending(gateway: inkspace.core.workspace.NotebookGateway) {
        val directory = File(storageRoot, "native-publications")
        for (receipt in directory.listFiles().orEmpty()) {
            val id = receipt.name
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !receipt.isFile) continue
            if (existingSnapshot(id) == null) continue
            val present = gateway.entries(inkspace.core.workspace.LibrarySection.DOCUMENTS).any { it.id == id }
            if (present || gateway.submit(inkspace.core.workspace.LibraryAction.Import(listOf(inkspace.core.workspace.LibraryEntry(id,"Imported notebook",template=receipt.readText()=="Template")))).isSuccess) receipt.delete()
        }
    }

    private fun openAsset(document: DocumentId, asset: AssetId): java.io.InputStream {
        require(document.value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val hex = asset.value.removePrefix("sha256:")
        require(hex.matches(Regex("[a-f0-9]{64}"))) { "Unsafe asset ID" }
        return File(storageRoot,"documents/${document.value}/assets/${hex.take(2)}/$hex").inputStream()
    }
}
