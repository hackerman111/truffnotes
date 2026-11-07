package inkspace.exchange

import inkspace.core.codec.CoreCodec
import inkspace.core.id.AssetId
import inkspace.core.model.SceneObject
import inkspace.core.payload.referencedAssetIds
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Streaming payloads; memory is bounded by one record and the quota-limited manifest directory. */
object NativeArchive {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    fun assetPath(id: String) = "assets/" + hash(id.encodeToByteArray())

    fun write(reader: SnapshotReader, output: OutputStream, limits: ArchiveLimits = ArchiveLimits(),
        canceled: () -> Boolean = { false }): NativeManifest {
        val entries = ArrayList<NativeEntry>()
        var total = 0L
        val zip = ZipOutputStream(object : FilterOutputStream(output) { override fun close() = flush() })
        fun entry(path: String, input: InputStream, maximum: Long) {
            checkCanceled(canceled)
            if (entries.size >= limits.maxEntries) throw ExchangeException("Entry quota exceeded")
            zip.putNextEntry(ZipEntry(path).apply { time = 0 })
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            var size = 0L
            while (true) {
                checkCanceled(canceled)
                val n = input.read(buffer)
                if (n == -1) break
                if (n == 0) throw ExchangeException("Input made no progress")
                size += n; total += n
                if (size > maximum || total > limits.maxExpandedBytes) throw ExchangeException("Expanded quota exceeded")
                zip.write(buffer, 0, n); digest.update(buffer, 0, n)
            }
            zip.closeEntry()
            entries += NativeEntry(path, size, digest.digest().hex())
        }
        try {
            val document = reader.document
            if (document.assets != document.assets.distinct().sorted()) throw ExchangeException("Assets must be unique and sorted")
            entry("document.cbor", CoreCodec.encodeToCbor(document).inputStream(), limits.maxEntryBytes.toLong())
            var count = 0
            var lastId: String? = null
            val reachable = linkedSetOf<String>()
            document.pages.forEach { it.metadata.template?.asset?.value?.let(reachable::add); it.metadata.pdfBackground?.asset?.value?.let(reachable::add) }
            for (record in reader.records()) {
                if (lastId != null && record.id.value <= lastId!!) throw ExchangeException("Record IDs must be unique and sorted")
                lastId = record.id.value
                reachable.addAll(record.payload.referencedAssetIds().map { it.value })
                val bytes = CoreCodec.encodeToCbor(record)
                entry("records/${count.toString().padStart(8, '0')}.cbor", bytes.inputStream(), limits.maxEntryBytes.toLong())
                count++
            }
            if (count != document.objectCount || reachable.sorted() != document.assets) throw ExchangeException("Snapshot record/asset directory mismatch")
            for (asset in document.assets) reader.asset(AssetId(asset)).use {
                entry(assetPath(asset), it, limits.maxExpandedBytes)
            }
            val result = NativeManifest(documentId = document.version.document.value,
                revision = document.version.revision.value, entries = entries.sortedBy { it.path })
            val manifest = CoreCodec.encodeToCbor(result)
            if (manifest.size > limits.maxManifestBytes || total + manifest.size > limits.maxExpandedBytes) throw ExchangeException("Manifest quota exceeded")
            checkCanceled(canceled)
            zip.putNextEntry(ZipEntry("manifest.cbor").apply { time = 0 }); zip.write(manifest); zip.closeEntry()
            zip.finish(); zip.flush()
            return result
        } finally { zip.close() }
    }

    fun read(input: InputStream, sink: ImportSink, limits: ArchiveLimits = ArchiveLimits(),
        canceled: () -> Boolean = { false }): NativeManifest {
        try {
            checkCanceled(canceled)
            val seen = LinkedHashMap<String, NativeEntry>()
            val reachable = linkedSetOf<String>()
            var total = 0L
            var document: NativeDocument? = null
            var manifest: NativeManifest? = null
            var records = 0
            var previousId: String? = null
            ZipInputStream(input).use { zip ->
                while (true) {
                    checkCanceled(canceled)
                    val item = zip.nextEntry ?: break
                    val path = item.name
                    if (manifest != null || seen.size >= limits.maxEntries || item.isDirectory ||
                        path.length > 256 || path.contains('\\') || path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                        seen.containsKey(path)) throw ExchangeException("Invalid or duplicate archive entry")
                    val isAsset = path.matches(Regex("assets/[a-f0-9]{64}"))
                    val isRecord = path.matches(Regex("records/[0-9]{8}\\.cbor"))
                    if (path != "document.cbor" && path != "manifest.cbor" && !isAsset && !isRecord) throw ExchangeException("Unknown archive entry")
                    if (document == null && path != "document.cbor") throw ExchangeException("Missing document header")
                    if (isAsset && path !in document!!.assets.map(::assetPath)) throw ExchangeException("Unreachable asset")
                    val memory = if (isAsset) null else ByteArrayOutputStream()
                    val target = if (isAsset) sink.asset(path) else memory!!
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    try {
                        while (true) {
                            checkCanceled(canceled)
                            val n = zip.read(buffer)
                            if (n == -1) break
                            if (n == 0) throw ExchangeException("Input made no progress")
                            size += n; total += n
                            val maximum = if (isAsset) limits.maxExpandedBytes else if (path == "manifest.cbor") limits.maxManifestBytes.toLong() else limits.maxEntryBytes.toLong()
                            if (size > maximum || total > limits.maxExpandedBytes) throw ExchangeException("Expanded quota exceeded")
                            target.write(buffer, 0, n); digest.update(buffer, 0, n)
                        }
                    } finally { target.close() }
                    zip.closeEntry()
                    val bytes = memory?.toByteArray()
                    when {
                        path == "document.cbor" -> {
                            document = decode<NativeDocument>(bytes!!)
                            val doc = document!!
                            if (doc.objectCount < 0 || doc.objectCount > limits.maxEntries || doc.assets != doc.assets.distinct().sorted() ||
                                doc.pages.map { it.id }.distinct().size != doc.pages.size || doc.layers.map { it.id }.distinct().size != doc.layers.size ||
                                doc.layers.any { layer -> doc.pages.none { it.id == layer.pageId } }) throw ExchangeException("Invalid document directory")
                            doc.pages.forEach { it.metadata.template?.asset?.value?.let(reachable::add); it.metadata.pdfBackground?.asset?.value?.let(reachable::add) }
                            sink.document(doc)
                        }
                        path == "manifest.cbor" -> manifest = decode<NativeManifest>(bytes!!)
                        isRecord -> {
                            if (path != "records/${records.toString().padStart(8, '0')}.cbor") throw ExchangeException("Noncanonical record order")
                            val record = decode<SceneObject>(bytes!!)
                            if (previousId != null && record.id.value <= previousId!!) throw ExchangeException("Duplicate or unsorted object ID")
                            previousId = record.id.value
                            val layer = document!!.layers.find { it.id == record.layer } ?: throw ExchangeException("Missing layer")
                            if (document!!.pages.find { it.id == layer.pageId }?.spaceId != record.space ||
                                CoreCodec.computePayloadDigest(record.payload) != record.payloadDigest) throw ExchangeException("Invalid object")
                            reachable.addAll(record.payload.referencedAssetIds().map { it.value })
                            records++; sink.record(path, bytes)
                        }
                    }
                    if (path != "manifest.cbor") seen[path] = NativeEntry(path, size, digest.digest().hex())
                }
            }
            val result = manifest ?: throw ExchangeException("Missing manifest")
            val doc = document ?: throw ExchangeException("Missing document")
            if (result.schema != 1) throw ExchangeException("Unsupported schema ${result.schema}")
            if (result.documentId != doc.version.document.value || result.revision != doc.version.revision.value ||
                result.entries != seen.values.sortedBy { it.path } || records != doc.objectCount ||
                reachable.sorted() != doc.assets || doc.assets.any { assetPath(it) !in seen }) throw ExchangeException("Manifest/hash/directory mismatch")
            checkCanceled(canceled)
            sink.commit(result)
            return result
        } catch (failure: Exception) {
            try { sink.abort() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            if (failure is ExchangeException) throw failure
            throw ExchangeException("Archive import failed", failure)
        }
    }

    private inline fun <reified T> decode(bytes: ByteArray): T = CoreCodec.decodeFromCbor<T>(bytes).getOrNull()
        ?: throw ExchangeException("Invalid CBOR")
    private fun checkCanceled(canceled: () -> Boolean) { if (canceled()) throw ExchangeException("Canceled") }
}
