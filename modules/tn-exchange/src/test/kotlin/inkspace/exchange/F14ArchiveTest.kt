package inkspace.exchange

import inkspace.core.id.DocumentId
import inkspace.core.model.DocumentState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import inkspace.core.codec.CoreCodec
import inkspace.core.id.*
import inkspace.core.model.SceneObject
import inkspace.core.payload.*
import inkspace.core.unit.DocRect
import kotlin.test.*
import org.junit.jupiter.api.Test

class F14ArchiveTest {
    @Test fun reimport_same_id() {
        assertEquals(ImportIdentity.OpenMatchingGeneration, resolveImportIdentity("doc", "hash", "hash"))
        assertFailsWith<ExchangeException> { resolveImportIdentity("doc", "old", "changed") }
        assertEquals(ImportIdentity.ImportAsCopy("new"), resolveImportIdentity("doc", "old", "changed", "new"))
        assertFailsWith<ExchangeException> { resolveImportIdentity("doc", "old", "changed", "doc") }
    }
    @Test fun snapshot_export_freezes_revision_and_pending_assets() {
        val state = DocumentState.createInitial(DocumentId("pending"))
        val payload = ImagePayload(AssetId("pending-image"), 16, 16)
        val obj = SceneObject(ObjectId("pending-object"), SpaceId("space-1"), LayerId("layer-1"), DocRect(0.0,0.0,16.0,16.0), payload)
        state.putResidentObjectDirect(obj)
        val frozen = state.snapshot()
        val bytes = ByteArrayOutputStream()
        val reader = ResidentSnapshotReader(frozen) { ByteArrayInputStream(byteArrayOf(1,2,3)) }
        state.putResidentObjectDirect(obj.copy(style=obj.style.copy(opacity=.3f)))
        NativeArchive.write(reader, bytes)
        val sink = MemoryImportSink()
        NativeArchive.read(bytes.toByteArray().inputStream(), sink)
        assertEquals(frozen.version, sink.document!!.version)
        assertEquals(1, sink.records.size)
        assertEquals(obj, CoreCodec.decodeFromCbor<SceneObject>(sink.records.single()).getOrNull())
        assertContentEquals(byteArrayOf(1,2,3), sink.assets.values.single().toByteArray())
    }
    @Test fun bad_hash() {
        val bytes = archive()
        val altered = rewrite(bytes) { name, value ->
            if(name == "manifest.cbor") CoreCodec.encodeToCbor(CoreCodec.decodeFromCbor<NativeManifest>(value).getOrNull()!!.let { manifest ->
                manifest.copy(entries=manifest.entries.map { it.copy(sha256="0".repeat(64)) })
            }) else value
        }
        val sink = MemoryImportSink()
        assertFailsWith<ExchangeException> { NativeArchive.read(altered.inputStream(), sink) }
        assertTrue(sink.aborted); assertFalse(sink.committed)
    }
    @Test fun unsupported_schema() {
        val altered = rewrite(archive()) { name, value ->
            if(name == "manifest.cbor") CoreCodec.encodeToCbor(CoreCodec.decodeFromCbor<NativeManifest>(value).getOrNull()!!.copy(schema=100)) else value
        }
        assertFailsWith<ExchangeException> { NativeArchive.read(altered.inputStream(), MemoryImportSink()) }
    }
    @Test fun short_write_aborts_new_output() {
        var aborted=false; var completed=false
        val output = object : ExportOutput {
            override val replacesExisting=false
            override val atomicReplacement=false
            override fun open() = object : java.io.OutputStream() { override fun write(value:Int) { throw java.io.IOException("ENOSPC") } }
            override fun complete() { completed=true }
            override fun abort() { aborted=true }
        }
        assertFailsWith<java.io.IOException> { SafeExport.write(output) { it.write(1) } }
        assertTrue(aborted); assertFalse(completed)
    }
    @Test fun native_roundtrip() {
        val snapshot = DocumentState.createInitial(DocumentId("roundtrip")).snapshot()
        val output = ByteArrayOutputStream()
        NativeArchive.write(ResidentSnapshotReader(snapshot) { error("No assets") }, output)
        val sink = MemoryImportSink()
        NativeArchive.read(ByteArrayInputStream(output.toByteArray()), sink)
        assertEquals(snapshot.version, sink.document!!.version)
        assertEquals(snapshot.pages, sink.document!!.pages)
        assertTrue(sink.committed)
    }
    @Test fun archive_traversal() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("../outside")); it.write(1); it.closeEntry() }
        val sink = MemoryImportSink()
        assertFailsWith<ExchangeException> { NativeArchive.read(ByteArrayInputStream(bytes.toByteArray()), sink) }
        assertFalse(sink.committed)
        assertTrue(sink.aborted)
    }
    @Test fun expanded_quota() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("document.cbor")); it.write(ByteArray(5000)); it.closeEntry() }
        assertFailsWith<ExchangeException> { NativeArchive.read(ByteArrayInputStream(bytes.toByteArray()), MemoryImportSink(), ArchiveLimits(maxExpandedBytes=100)) }
    }
    @Test fun canceled_import() {
        val sink = MemoryImportSink()
        assertFailsWith<ExchangeException> { NativeArchive.read(ByteArrayInputStream(byteArrayOf()), sink, canceled={true}) }
        assertTrue(sink.aborted)
    }
    @Test fun output_short_write_nonatomic_provider() {
        var opened = false
        val output = object : ExportOutput {
            override val replacesExisting = true
            override val atomicReplacement = false
            override fun open(): java.io.OutputStream { opened=true; error("must not open") }
            override fun complete() = Unit
            override fun abort() = Unit
        }
        assertFailsWith<ExchangeException> { SafeExport.write(output) { it.write(1) } }
        assertFalse(opened)
    }
    private fun archive(): ByteArray = ByteArrayOutputStream().also { output ->
        NativeArchive.write(ResidentSnapshotReader(DocumentState.createInitial(DocumentId("valid")).snapshot()) { error("No assets") }, output)
    }.toByteArray()
    private fun rewrite(bytes: ByteArray, change:(String,ByteArray)->ByteArray): ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { target -> ZipInputStream(bytes.inputStream()).use { input ->
            while(true) { val entry=input.nextEntry ?: break;target.putNextEntry(ZipEntry(entry.name));target.write(change(entry.name,input.readBytes()));target.closeEntry() }
        } }
        return output.toByteArray()
    }
}

private class MemoryImportSink : ImportSink {
    var document: NativeDocument? = null
    var committed = false
    var aborted = false
    val records = ArrayList<ByteArray>()
    val assets = LinkedHashMap<String,ByteArrayOutputStream>()
    override fun document(value: NativeDocument) { document=value }
    override fun record(path:String, bytes:ByteArray) { records+=bytes }
    override fun asset(path:String): java.io.OutputStream = ByteArrayOutputStream().also { assets[path]=it }
    override fun commit(manifest: NativeManifest) { committed=true }
    override fun abort() { aborted=true }
}
