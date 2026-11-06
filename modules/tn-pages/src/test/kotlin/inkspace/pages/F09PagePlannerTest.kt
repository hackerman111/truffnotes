package inkspace.pages

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.mutation.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.*
import inkspace.core.unit.DocRect
import kotlin.test.*
import org.junit.jupiter.api.Test

class F09PagePlannerTest {
    private fun state() = DocumentState.createInitial(DocumentId("pages-test"))
    private fun apply(s: DocumentState, plan: EditPlan): AppliedEdit =
        applyPlan(s, validatePlan(s, plan, inkspace.core.limits.Limits()).getOrNull()!!).getOrNull()!!

    @Test fun pageOrderKeepsIdsAndUndo() {
        val s = state()
        val page = PageDescriptor(PageId("second"), SpaceId("second-space"), 400.0, 500.0)
        apply(s, PagePlanner.plan(s, PageAction.Add(page, LayerDescriptor(LayerId("second-layer"), page.id, "Ink"))).getOrNull()!!)
        val ids = s.pages.map { it.id }
        val applied = apply(s, PagePlanner.plan(s, PageAction.Reorder(ids.reversed())).getOrNull()!!)
        assertEquals(ids.reversed(), s.pages.map { it.id })
        apply(s, applied.inversePlan)
        assertEquals(ids, s.pages.map { it.id })
    }

    @Test fun allResizePoliciesPreserveOutsideObjectsAndUndo() {
        for (policy in ResizePolicy.entries) {
            val s = state()
            val obj = ObjectId("outside")
            apply(s, EditPlan(s.version, mutations = listOf(InsertObject(SpaceId("space-1"), LayerId("layer-1"), obj,
                TextPayload("outside"), DocRect(900.0, 900.0, 950.0, 950.0))), gesture = GestureId("seed"), label = UndoLabel("seed")))
            val before = s.getObject(obj)!!
            val action = PageAction.Resize(PageId("page-1"), 200.0, 200.0, policy)
            val preview = PagePlanner.previewResize(s, action).getOrNull()!!
            val applied = apply(s, PagePlanner.plan(s, action).getOrNull()!!)
            assertNotNull(s.getObject(obj))
            assertEquals(preview.transform.transformRect(before.bounds), s.getObject(obj)!!.bounds)
            apply(s, applied.inversePlan)
            assertEquals(before.bounds, s.getObject(obj)!!.bounds)
        }
    }

    @Test fun clearKeepsPageDeleteRemovesItAndUndoRestores() {
        val s = state()
        assertIs<Outcome.Failure<*>>(PagePlanner.plan(s, PageAction.Clear(PageId("page-1"))))
        assertIs<Outcome.Failure<*>>(PagePlanner.plan(s, PageAction.Delete(PageId("page-1"))))
        val second = PageDescriptor(PageId("second"), SpaceId("second-space"), 400.0, 500.0)
        apply(s, PagePlanner.plan(s, PageAction.Add(second, LayerDescriptor(LayerId("second-layer"), second.id, "Ink"))).getOrNull()!!)
        val applied = apply(s, PagePlanner.plan(s, PageAction.Delete(PageId("page-1"))).getOrNull()!!)
        assertEquals(listOf(second.id), s.pages.map { it.id })
        apply(s, applied.inversePlan)
        assertEquals(PageId("page-1"), s.pages.first().id)
    }

    @Test fun layerStyleUndoAndUnsafeMergeRefused() {
        val s = state()
        val layer = LayerDescriptor(LayerId("other"), PageId("page-1"), "Other", opacity = 0.5f)
        apply(s, PagePlanner.plan(s, PageAction.AddLayer(layer)).getOrNull()!!)
        val applied = apply(s, PagePlanner.plan(s, PageAction.LockLayer(layer.id, true)).getOrNull()!!)
        assertTrue(s.getLayer(layer.id)!!.isLocked)
        apply(s, applied.inversePlan)
        assertFalse(s.getLayer(layer.id)!!.isLocked)
        assertIs<Outcome.Failure<*>>(PagePlanner.plan(s, PageAction.MergeLayers(layer.id, LayerId("layer-1"))))
    }
    @Test fun mergePreservesOrderAndUndoRestoresLayers() {
        val s = state()
        val source = LayerDescriptor(LayerId("upper"), PageId("page-1"), "Upper")
        apply(s, PagePlanner.plan(s, PageAction.AddLayer(source)).getOrNull()!!)
        for ((id, layer) in listOf("bottom" to LayerId("layer-1"), "top" to source.id)) {
            apply(s, EditPlan(s.version, mutations = listOf(InsertObject(SpaceId("space-1"), layer,
                ObjectId(id), TextPayload(id), DocRect(0.0, 0.0, 10.0, 10.0))),
                gesture = GestureId(id), label = UndoLabel(id)))
        }
        val applied = apply(s, PagePlanner.plan(s, PageAction.MergeLayers(source.id, LayerId("layer-1"))).getOrNull()!!)
        assertNull(s.getLayer(source.id))
        assertTrue(s.getObject(ObjectId("bottom"))!!.style.zOrder < s.getObject(ObjectId("top"))!!.style.zOrder)
        apply(s, applied.inversePlan)
        assertEquals(source, s.getLayer(source.id))
        assertEquals(source.id, s.getObject(ObjectId("top"))!!.layer)
    }

    @Test fun clearRemovesOnlyContentAndUndoRestoresIt() {
        val s = state()
        apply(s, EditPlan(s.version, mutations = listOf(InsertObject(SpaceId("space-1"), LayerId("layer-1"),
            ObjectId("note"), TextPayload("keep on undo"), DocRect(0.0, 0.0, 10.0, 10.0))),
            gesture = GestureId("seed"), label = UndoLabel("seed")))
        val applied = apply(s, PagePlanner.plan(s, PageAction.Clear(PageId("page-1"))).getOrNull()!!)
        assertEquals(1, s.pages.size)
        assertEquals(1, s.layers.size)
        assertEquals(0, s.totalObjectCount)
        apply(s, applied.inversePlan)
        assertEquals("keep on undo", (s.getObjectPayload(ObjectId("note")) as TextPayload).text)
    }

}
