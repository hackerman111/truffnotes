package inkspace.exchange

import inkspace.core.codec.CoreCodec
import kotlinx.serialization.Serializable
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class NativeCollectionItem(val libraryPath: String, val reader: SnapshotReader, val names: List<String> = emptyList())
@Serializable
data class CollectionDocument(val libraryPath: String, val entry: String, val documentId: String,
    val bytes: Long, val sha256: String, val names: List<String> = emptyList())
@Serializable
data class CollectionManifest(val schema: Int = 1, val documents: List<CollectionDocument>)

/** Child commit only seals private staging. Only this sink's final commit may publish the collection.
 * abort must discard every child's data, including children that have already sealed their staging. */
interface NativeCollectionSink {
    fun document(index: Int): ImportSink
    fun commit(manifest: CollectionManifest)
    fun abort()
}

/** Deterministic streaming envelope of standalone .inknote streams with a hashed hierarchy manifest. */
object NativeCollection {
    private fun safePath(path: String) = path.length in 1..1024 && !path.contains('\\') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.code < 32 || c == ':' } }
    private fun entry(index: Int) = "documents/${index.toString().padStart(8,'0')}.inknote"
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Readers remain caller-owned leases and must all remain frozen until this returns. */
    fun write(items: List<NativeCollectionItem>, output: OutputStream, limits: ArchiveLimits = ArchiveLimits(),
        canceled: () -> Boolean = { false }): CollectionManifest {
        if (items.isEmpty() || items.size > limits.maxEntries || items.any { !safePath(it.libraryPath) || (it.names.isNotEmpty() && (it.names.size != it.libraryPath.split('/').size || it.names.any { name -> name.isBlank() || name.length > 1024 })) } ||
            items.map { it.libraryPath }.distinct().size != items.size ||
            items.map { it.reader.document.version.document }.distinct().size != items.size)
            throw ExchangeException("Invalid collection paths or duplicate documents")
        val ordered = items.sortedBy { it.libraryPath }
        val metadata = ArrayList<CollectionDocument>()
        var remaining = limits.maxExpandedBytes
        var envelopeBytes = 0L
        val zip = ZipOutputStream(object : FilterOutputStream(output) { override fun close() = flush() })
        try {
            for ((index,item) in ordered.withIndex()) {
                if (canceled()) throw ExchangeException("Canceled")
                val path = entry(index)
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val counting = object : OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()),0,1) }
                    override fun write(value: ByteArray, offset: Int, length: Int) {
                        bytes += length
                        envelopeBytes += length
                        if (envelopeBytes > limits.maxExpandedBytes) throw ExchangeException("Collection ZIP entry quota exceeded")
                        zip.write(value,offset,length);digest.update(value,offset,length)
                    }
                    override fun flush() = zip.flush()
                }
                val native = NativeArchive.write(item.reader,counting,limits.copy(maxExpandedBytes=remaining.coerceAtLeast(1)),canceled)
                val expanded = native.entries.sumOf { it.bytes } + CoreCodec.encodeToCbor(native).size
                remaining -= expanded
                if (remaining < 0) throw ExchangeException("Collection expanded quota exceeded")
                zip.closeEntry()
                metadata += CollectionDocument(item.libraryPath,path,item.reader.document.version.document.value,bytes,digest.digest().hex(),item.names.toList())
            }
            val manifest = CollectionManifest(documents=metadata)
            val encoded = CoreCodec.encodeToCbor(manifest)
            if (encoded.size > limits.maxManifestBytes || encoded.size > remaining) throw ExchangeException("Collection manifest quota exceeded")
            if (canceled()) throw ExchangeException("Canceled")
            zip.putNextEntry(ZipEntry("collection.cbor").apply { time=0 });zip.write(encoded);zip.closeEntry()
            zip.finish();zip.flush()
            return manifest
        } finally { zip.close() }
    }

    fun read(input: InputStream, sink: NativeCollectionSink, limits: ArchiveLimits = ArchiveLimits(),
        canceled: () -> Boolean = { false }): CollectionManifest {
        val children = ArrayList<ImportSink>()
        try {
            var remaining = limits.maxExpandedBytes
            var envelopeBytes = 0L
            var manifest: CollectionManifest? = null
            val documents = ArrayList<CollectionDocument>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    if (canceled()) throw ExchangeException("Canceled")
                    val item = zip.nextEntry ?: break
                    if (manifest != null || item.isDirectory) throw ExchangeException("Invalid collection entry")
                    if (item.name == "collection.cbor") {
                        val bytes=ByteArrayOutputStream()
                        val buffer=ByteArray(32768)
                        while (true) {
                            if (canceled()) throw ExchangeException("Canceled")
                            val n=zip.read(buffer);if(n<0) break
                            if(n==0) throw ExchangeException("Input made no progress")
                            if(bytes.size().toLong()+n > limits.maxManifestBytes || bytes.size().toLong()+n > remaining) throw ExchangeException("Collection manifest quota exceeded")
                            bytes.write(buffer,0,n)
                        }
                        manifest=CoreCodec.decodeFromCbor<CollectionManifest>(bytes.toByteArray()).getOrNull() ?: throw ExchangeException("Invalid collection manifest")
                    } else {
                        val index=documents.size
                        if(index >= limits.maxEntries || item.name != entry(index)) throw ExchangeException("Unexpected collection document entry")
                        val digest=MessageDigest.getInstance("SHA-256")
                        var count=0L
                        val counting=object : InputStream() {
                            override fun read(): Int { val one=ByteArray(1);return if(read(one,0,1)<0) -1 else one[0].toInt() and 255 }
                            override fun read(value:ByteArray,offset:Int,length:Int):Int {
                                if(canceled()) throw ExchangeException("Canceled")
                                val n=zip.read(value,offset,length)
                                if(n>0) {
                                    count+=n;envelopeBytes+=n
                                    if(envelopeBytes>limits.maxExpandedBytes) throw ExchangeException("Collection ZIP quota exceeded")
                                    digest.update(value,offset,n)
                                }
                                return n
                            }
                            override fun close() = Unit
                        }
                        val child=sink.document(index);children+=child
                        val native=NativeArchive.read(counting,child,limits.copy(maxExpandedBytes=remaining.coerceAtLeast(1)),canceled)
                        // ZipInputStream may stop after consuming only part of the nested central directory.
                        val drain=ByteArray(32768)
                        while(counting.read(drain)>=0) { }
                        remaining -= native.entries.sumOf { it.bytes } + CoreCodec.encodeToCbor(native).size
                        if(remaining<0) throw ExchangeException("Collection expanded quota exceeded")
                        documents+=CollectionDocument("",item.name,native.documentId,count,digest.digest().hex())
                    }
                    zip.closeEntry()
                }
            }
            val result=manifest ?: throw ExchangeException("Missing collection manifest")
            if(result.schema!=1 || result.documents.isEmpty() || result.documents.size!=documents.size ||
                result.documents.any { !safePath(it.libraryPath) || (it.names.isNotEmpty() && (it.names.size != it.libraryPath.split('/').size || it.names.any { name -> name.isBlank() || name.length > 1024 })) } ||
                result.documents.map { it.libraryPath } != result.documents.map { it.libraryPath }.distinct().sorted() ||
                result.documents.map { it.documentId }.distinct().size!=documents.size ||
                result.documents.mapIndexed { index,doc -> doc.copy(libraryPath="",names=emptyList()) != documents[index] }.any { it })
                throw ExchangeException("Collection manifest/hash/path mismatch")
            if(canceled()) throw ExchangeException("Canceled")
            sink.commit(result)
            return result
        } catch (failure:Exception) {
            for(child in children) try { child.abort() } catch(cleanup:Exception) { failure.addSuppressed(cleanup) }
            try { sink.abort() } catch(cleanup:Exception) { failure.addSuppressed(cleanup) }
            if(failure is ExchangeException) throw failure
            throw ExchangeException("Collection import failed",failure)
        }
    }
}
