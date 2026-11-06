package inkspace.pages

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.mutation.*
import inkspace.core.pages.*
import inkspace.core.payload.*
import inkspace.core.objects.PlanContext
import inkspace.core.limits.Limits
import inkspace.core.unit.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class F09CompletePagesTest {
    private fun state() = DocumentState.createInitial(DocumentId("pages-complete"))
    private fun apply(s: DocumentState, action: PageAction): AppliedEdit {
        val plan = PagePlanner.plan(s, action).getOrNull() ?: fail("Action rejected: $action")
        return applyPlan(s, validatePlan(s, plan, Limits()).getOrNull()!!).getOrNull()!!
    }
    @Test fun templateSnapshotOutlineAndBookmarksSurviveCodecAndUndo() {
        val s = state()
        val template = TemplateDescriptor("grid", 1, "Graph", GridSpec(PaperGrid.SQUARE, 12.0))
        val metadata = PageMetadata(template = template, bookmarked = true, outline = listOf(OutlineNode("root", "A", children = listOf(OutlineNode("child", "B")))))
        val applied = apply(s, PageAction.SetMetadata(PageId("page-1"), metadata))
        assertEquals(1, s.pages.single().metadata.template!!.version)
        val encoded = inkspace.core.codec.CoreCodec.encodeToCbor(s.pages.single())
        assertEquals(metadata, inkspace.core.codec.CoreCodec.decodeFromCbor<PageDescriptor>(encoded).getOrNull()!!.metadata)
        applyPlan(s, validatePlan(s, applied.inversePlan, Limits()).getOrNull()!!)
        assertEquals(PageMetadata(), s.pages.single().metadata)
        var node = OutlineNode("leaf", "leaf")
        repeat(32) { node = OutlineNode("n$it", "x", children = listOf(node)) }
        assertFailsWith<IllegalArgumentException> { PageMetadata(outline = listOf(node)) }
    }
    @Test fun duplicateRemapsPageLayerGroupAndConnectorIds() {
        val s = state()
        val page = s.pages.single()
        val obj = InsertObject(page.spaceId, s.layers.single().id, ObjectId("text"), TextPayload("copy"), DocRect(5.0,5.0,50.0,20.0))
        val plan = EditPlan(s.version, mutations=listOf(obj), gesture=GestureId("seed"), label=UndoLabel("seed"))
        applyPlan(s, validatePlan(s, plan, Limits()).getOrNull()!!)
        apply(s, PageAction.Duplicate(page.id, "copy"))
        assertEquals(2,s.pages.size)
        assertEquals(2,s.totalObjectCount)
        assertNotEquals(s.pages[0].id,s.pages[1].id)
        val copied = s.queryBounds(DocRect(0.0,0.0,100.0,100.0)).mapNotNull(s::getObject).single { it.id != obj.id }
        assertNotEquals(obj.layer,copied.layer)
        assertEquals(obj.payload,copied.payload)
    }
    @Test fun batchIsAtomicAndRotateUndoPreservesGeometry() {
        val s=state(); val before=s.snapshot()
        assertTrue(PagePlanner.plan(s, PageAction.Bulk(listOf(PageAction.Rotate(PageId("page-1"), 90), PageAction.Delete(PageId("missing"))))).isFailure)
        assertEquals(before.pages,s.pages)
        val applied=apply(s,PageAction.Rotate(PageId("page-1"),90))
        assertEquals(before.pages.single().heightPt,s.pages.single().widthPt)
        applyPlan(s,validatePlan(s,applied.inversePlan,Limits()).getOrNull()!!)
        assertEquals(before.pages,s.pages)
    }
    @Test fun lockedPdfBackgroundCanDuplicateDeleteAndUndo() {
        val s=state()
        val seed=EditPlan(s.version,mutations=listOf(InsertObject(SpaceId("space-1"),LayerId("layer-1"),ObjectId("pdf"),
            PdfObjectPayload(inkspace.core.pdf.PdfPageRef(AssetId("fixture"),0,DocRect(0.0,0.0,100.0,100.0))),DocRect(0.0,0.0,100.0,100.0)),SetLayerLock(LayerId("layer-1"),true)),gesture=GestureId("seed"),label=UndoLabel("seed"))
        applyPlan(s,validatePlan(s,seed,Limits()).getOrNull()!!)
        apply(s,PageAction.Duplicate(PageId("page-1"),"copy"))
        assertEquals(2,s.totalObjectCount)
        assertTrue(s.layers.all { it.isLocked })
        val deleted=apply(s,PageAction.Delete(PageId("copy-page-1")))
        assertEquals(1,s.pages.size)
        applyPlan(s,validatePlan(s,deleted.inversePlan,Limits()).getOrNull()!!)
        assertEquals(2,s.pages.size)
        assertTrue(s.layers.all { it.isLocked })
    }

}
