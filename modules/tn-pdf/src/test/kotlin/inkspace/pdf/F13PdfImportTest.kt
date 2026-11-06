package inkspace.pdf

import inkspace.core.id.*
import inkspace.core.limits.Limits
import inkspace.core.model.*
import inkspace.core.mutation.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.PdfObjectPayload
import inkspace.core.pdf.PdfPageRef
import inkspace.core.unit.DocRect
import kotlin.test.*

class F13PdfImportTest {
    @Test fun range_import_background_undo_and_source_reference_survive() {
        val state = DocumentState.createInitial(DocumentId("pdf-import"))
        val beforePages = state.pages.toList()
        val refs = listOf(0, 1).map { PdfPageRef(AssetId("immutable-source"), it, DocRect(10.0, 20.0, 210.0, 320.0), 90) }
        val plan = PdfImportPlanner.plan(state, PdfImportAction.Pages(refs, prefix = "range"), PlanContext()).getOrNull()!!
        val validated = validatePlan(state, plan, Limits()).getOrNull()!!
        val applied = applyPlan(state, validated).getOrNull()!!
        assertEquals(3, state.pages.size)
        val source = state.getObject(ObjectId("range-pdf-0"))!!
        assertEquals(refs[0], (source.payload as PdfObjectPayload).page)
        assertTrue(state.getLayer(source.layer)!!.isLocked)
        assertEquals(300.0, state.pages[1].widthPt)
        applyPlan(state, validatePlan(state, applied.inversePlan, Limits()).getOrNull()!!)
        assertEquals(beforePages, state.pages)
        assertEquals(0, state.totalObjectCount)
    }

    @Test fun background_import_checks_every_reference_before_plan() {
        val state = DocumentState.createInitial(DocumentId("pdf-bounds"))
        val invalid = PdfPageRef(AssetId("source"), -1, DocRect(0.0, 0.0, 200.0, 200.0))
        assertIs<Outcome.Failure<*>>(PdfImportPlanner.plan(state, PdfImportAction.Pages(listOf(invalid)), PlanContext()))
        assertEquals(1, state.pages.size)
        assertEquals(0, state.totalObjectCount)
    }
}
