package inkspace.exchange

import inkspace.core.codec.CoreCodec
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.*
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

class F14SelectionTest {
    private val p1 = PageId("p1")
    private val p2 = PageId("p2")
    private val l1 = LayerId("l1")
    private val hidden = LayerId("hidden")
    private val l2 = LayerId("l2")
    private fun source(): DocumentSnapshot {
        val pages = listOf(PageDescriptor(p1,SpaceId("s1"),200.0,300.0),PageDescriptor(p2,SpaceId("s2"),200.0,300.0))
        val layers = listOf(LayerDescriptor(l1,p1,"Visible"),LayerDescriptor(hidden,p1,"Hidden",isVisible=false),LayerDescriptor(l2,p2,"Second"))
        fun obj(id:String,layer:LayerId,payload:ObjectPayload=TextPayload(id),bounds:DocRect=DocRect(10.0,10.0,20.0,20.0)) = SceneObject(ObjectId(id),if(layer==l2) SpaceId("s2") else SpaceId("s1"),layer,bounds,payload)
        return restoreSnapshot(SnapshotMetadata(DocVersion(DocumentId("source"),Revision(9)),DocumentKind.Notebook,pages,layers),sequenceOf(
            obj("a",l1),obj("b",l1,bounds=DocRect(100.0,100.0,120.0,120.0)),obj("group",l1,GroupPayload(listOf(ObjectId("a"),ObjectId("b")))),
            obj("hidden",hidden),obj("second",l2),obj("link",l1,LinkAnchorPayload("Second",targetPage=p2))))
    }
    @Test fun restore_rejects_deep_group_chains_before_publication() {
        val original = source()
        for (depth in listOf(33, 10000)) {
            val records = (0..depth).asSequence().map { index ->
                SceneObject(ObjectId("g$index"),SpaceId("s1"),l1,DocRect(0.0,0.0,10.0,10.0),
                    if(index == depth) TextPayload("leaf") else GroupPayload(listOf(ObjectId("g${index+1}"))))
            }
            assertFailsWith<IllegalArgumentException> {
                restoreSnapshot(SnapshotMetadata(original.version,original.kind,original.pages,original.layers),records)
            }
        }
    }
    @Test fun page_range_keeps_order_and_detaches_omitted_link_with_report() {
        val result=NativeSelection.project(source(),NativeScope.PageRange(0,0))
        assertEquals(listOf(p1),result.snapshot.pages.map { it.id })
        assertNull((result.snapshot.getObject(ObjectId("link"))!!.payload as LinkAnchorPayload).targetPage)
        assertEquals(setOf(ObjectId("link")),result.report.detachedLinks)
        assertEquals(Revision(9),result.snapshot.version.revision)
    }
    @Test fun chosen_visible_layers_do_not_mutate_source() {
        val original=source()
        val result=NativeSelection.project(original,NativeScope.Full,visibleLayersOnly=true)
        assertNull(result.snapshot.getObject(ObjectId("hidden")))
        assertNotNull(original.getObject(ObjectId("hidden")))
        assertEquals(listOf(l1,l2),result.snapshot.layers.map { it.id })
    }
    @Test fun object_bundle_closes_group_members_without_other_pages() {
        val result=NativeSelection.project(source(),NativeScope.Objects(setOf(ObjectId("group"))))
        assertEquals(setOf("a","b","group"),result.snapshot.orderedSceneObjects().map { it.id.value }.toSet())
        assertEquals(setOf(ObjectId("a"),ObjectId("b")),result.report.dependencyObjects)
        assertEquals(listOf(p1),result.snapshot.pages.map { it.id })
    }
    @Test fun region_keeps_intersecting_whole_editable_objects_and_group_dependencies() {
        val result=NativeSelection.project(source(),NativeScope.Region(p1,DocRect(0.0,0.0,30.0,30.0)),layerIds=setOf(l1))
        assertNotNull(result.snapshot.getObject(ObjectId("b")))
        assertEquals(DocRect(100.0,100.0,120.0,120.0),result.snapshot.getObject(ObjectId("b"))!!.bounds)
        assertEquals(DocRect(0.0,0.0,30.0,30.0),result.report.region)
    }
    @Test fun template_defaults_to_first_page_and_bundle_has_explicit_purpose() {
        val output=ByteArrayOutputStream()
        NativeBundles.write(source(),NativePurpose.Template,output,openAsset={ error("No assets") })
        val sink=HeaderSink()
        NativeArchive.read(output.toByteArray().inputStream(),sink)
        assertEquals(NativePurpose.Template,sink.header!!.purpose)
        assertEquals(listOf(p1),sink.header!!.pages.map { it.id })
    }
    @Test fun invalid_selection_fails_instead_of_exporting_empty_or_wrong_scope() {
        assertFailsWith<IllegalArgumentException> { NativeSelection.project(source(),NativeScope.Pages(listOf(PageId("missing")))) }
        assertFailsWith<IllegalArgumentException> { NativeSelection.project(source(),NativeScope.PageRange(1,0)) }
        assertFailsWith<IllegalArgumentException> { NativeSelection.project(source(),NativeScope.Objects(setOf(ObjectId("missing")))) }
    }
    @Test fun subset_archive_opens_only_assets_reachable_from_selected_pages() {
        val first=AssetId("first-image")
        val second=AssetId("second-image")
        val original=source()
        val records=original.orderedSceneObjects()+listOf(
            SceneObject(ObjectId("image1"),SpaceId("s1"),l1,DocRect(1.0,1.0,8.0,8.0),ImagePayload(first,7,7)),
            SceneObject(ObjectId("image2"),SpaceId("s2"),l2,DocRect(1.0,1.0,8.0,8.0),ImagePayload(second,7,7)))
        val snapshot=restoreSnapshot(SnapshotMetadata(original.version,original.kind,original.pages,original.layers),records.asSequence())
        val opened=ArrayList<AssetId>()
        val bytes=ByteArrayOutputStream()
        NativeBundles.write(snapshot,NativePurpose.Document,bytes,scope=NativeScope.Pages(listOf(p1)),openAsset={ id -> opened+=id;byteArrayOf(1,2,3).inputStream() })
        assertEquals(listOf(first),opened)
        val sink=HeaderSink()
        NativeArchive.read(bytes.toByteArray().inputStream(),sink)
        assertEquals(listOf(first.value),sink.header!!.assets)
    }
    @Test fun selecting_one_member_closes_parent_group_and_reports_dependencies() {
        val projection=NativeSelection.project(source(),NativeScope.Objects(setOf(ObjectId("a"))))
        assertEquals(setOf(ObjectId("group"),ObjectId("b")),projection.report.dependencyObjects)
        assertTrue(projection.snapshot.verifyInvariants())
    }
    @Test fun explicit_template_page_selection_is_preserved() {
        val bytes=ByteArrayOutputStream()
        NativeBundles.write(source(),NativePurpose.Template,bytes,scope=NativeScope.Pages(listOf(p2)),openAsset={ error("No assets") })
        val sink=HeaderSink();NativeArchive.read(bytes.toByteArray().inputStream(),sink)
        assertEquals(listOf(p2),sink.header!!.pages.map { it.id })
    }
}

private class HeaderSink: ImportSink {
    var header:NativeDocument?=null
    override fun document(value:NativeDocument) { header=value }
    override fun record(path:String,bytes:ByteArray) { assertNotNull(CoreCodec.decodeFromCbor<SceneObject>(bytes).getOrNull()) }
    override fun asset(path:String)=ByteArrayOutputStream()
    override fun commit(manifest:NativeManifest)=Unit
    override fun abort()=Unit
}
