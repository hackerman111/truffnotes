package inkspace.exchange

import inkspace.core.codec.CoreCodec
import inkspace.core.id.DocumentId
import inkspace.core.model.DocumentState
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlin.test.*

class F14CollectionTest {
    private fun item(id:String,path:String)=NativeCollectionItem(path,ResidentSnapshotReader(DocumentState.createInitial(DocumentId(id)).snapshot()) { error("No assets") })
    @Test fun collection_roundtrip_preserves_hierarchy_and_deterministic_order() {
        fun bytes()=ByteArrayOutputStream().also { NativeCollection.write(listOf(item("b","Course/B"),item("a","Course/A")),it) }.toByteArray()
        val first=bytes()
        assertContentEquals(first,bytes())
        val sink=CollectionSink()
        val manifest=NativeCollection.read(first.inputStream(),sink)
        assertEquals(listOf("Course/A","Course/B"),manifest.documents.map { it.libraryPath })
        assertEquals(listOf("a","b"),sink.ids)
        assertTrue(sink.committed)
    }
    @Test fun collection_names_preserve_duplicate_and_separator_containing_names() {
        val names=listOf("Course / section: 1","Same title")
        val documents=listOf(item("one","folder-id/one-id").copy(names=names),item("two","folder-id/two-id").copy(names=names))
        val output=ByteArrayOutputStream()
        NativeCollection.write(documents,output)
        val manifest=NativeCollection.read(output.toByteArray().inputStream(),CollectionSink())
        assertEquals(listOf(names,names),manifest.documents.map {it.names})
        assertEquals(2,manifest.documents.map {it.libraryPath}.distinct().size)
    }
    @Test fun unsafe_library_path_is_rejected_before_output() {
        val bytes=ByteArrayOutputStream()
        assertFailsWith<ExchangeException> { NativeCollection.write(listOf(item("a","../escape")),bytes) }
        assertEquals(0,bytes.size())
    }
    @Test fun bad_outer_digest_aborts_every_staged_document() {
        val valid=ByteArrayOutputStream().also { NativeCollection.write(listOf(item("a","A"),item("b","B")),it) }.toByteArray()
        val corrupted=ByteArrayOutputStream()
        ZipOutputStream(corrupted).use { target -> ZipInputStream(valid.inputStream()).use { input ->
            while(true) {
                val entry=input.nextEntry ?: break
                val bytes=input.readBytes()
                target.putNextEntry(ZipEntry(entry.name))
                target.write(if(entry.name=="collection.cbor") CoreCodec.encodeToCbor(CoreCodec.decodeFromCbor<CollectionManifest>(bytes).getOrNull()!!.let { it.copy(documents=it.documents.map { doc -> doc.copy(sha256="0".repeat(64)) }) }) else bytes)
                target.closeEntry()
            }
        } }
        val sink=CollectionSink()
        assertFailsWith<ExchangeException> { NativeCollection.read(corrupted.toByteArray().inputStream(),sink) }
        assertFalse(sink.committed);assertTrue(sink.aborted)
    }
    @Test fun collection_aggregate_expanded_quota_and_cancellation_abort() {
        val valid=ByteArrayOutputStream().also { NativeCollection.write(listOf(item("a","A")),it) }.toByteArray()
        val sink=CollectionSink()
        assertFailsWith<ExchangeException> { NativeCollection.read(valid.inputStream(),sink,ArchiveLimits(maxExpandedBytes=100)) }
        assertTrue(sink.aborted)
        val canceled=CollectionSink()
        assertFailsWith<ExchangeException> { NativeCollection.read(valid.inputStream(),canceled,canceled={true}) }
        assertTrue(canceled.aborted)
    }
}

private class CollectionSink: NativeCollectionSink {
    val ids=ArrayList<String>()
    var committed=false
    var aborted=false
    override fun document(index:Int):ImportSink=object:ImportSink {
        override fun document(value:NativeDocument) { ids+=value.version.document.value }
        override fun record(path:String,bytes:ByteArray)=Unit
        override fun asset(path:String)=ByteArrayOutputStream()
        override fun commit(manifest:NativeManifest)=Unit
        override fun abort()=Unit
    }
    override fun commit(manifest:CollectionManifest) { committed=true }
    override fun abort() { aborted=true }
}
