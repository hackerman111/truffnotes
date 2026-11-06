package inkspace.content

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.mutation.*
import inkspace.core.outcome.Outcome
import inkspace.core.payload.*
import inkspace.core.unit.DocRect
import kotlin.test.*
import org.junit.jupiter.api.Test

class F11BundleAndPlannerTest {
    @Test fun bundle_remap() {
        val a = obj("a", TextPayload("editable"))
        val group = obj("g", GroupPayload(listOf(a.id)))
        val bundle = ObjectBundle(1, listOf(a, group), setOf(group.id))
        val copy = bundle.remap("copy")
        assertEquals(listOf(ObjectId("copy-a")), (copy.objects.last().payload as GroupPayload).members)
        assertEquals(setOf(ObjectId("copy-g")), copy.rootIds)
        assertEquals(listOf(ObjectId("a")), (bundle.objects.last().payload as GroupPayload).members)
    }
    @Test fun bundle_does_not_alias_shape_vertices() {
        val vertices = mutableListOf(inkspace.core.unit.DocPoint(0.0, 0.0), inkspace.core.unit.DocPoint(10.0, 10.0))
        val shape = obj("s", ShapePayload(ShapeType.Line, 1, vertices = vertices))
        val bundle = ObjectBundle(1, listOf(shape), setOf(shape.id))
        vertices.clear()
        assertEquals(2, (bundle.objects.single().payload as ShapePayload).vertices.size)
    }
    @Test fun crop_and_sticky_undo_preserve_source() {
        val state = DocumentState.createInitial(DocumentId("content"))
        state.putResidentObjectDirect(obj("image", ImagePayload(AssetId("source"), 100, 200)))
        val plan = ContentPlanner.plan(state, ContentAction.CropImage(ObjectId("image"), DocRect(10.0, 20.0, 80.0, 180.0))).getOrNull()!!
        val result = applyPlan(state, validatePlan(state, plan, inkspace.core.limits.Limits()).getOrNull()!!).getOrNull()!!
        assertEquals(AssetId("source"), (state.getObjectPayload(ObjectId("image")) as ImagePayload).asset)
        applyPlan(state, validatePlan(state, result.inversePlan, inkspace.core.limits.Limits()).getOrNull()!!)
        assertNull((state.getObjectPayload(ObjectId("image")) as ImagePayload).cropRect)
    }
    @Test fun remove_tape_batch_fails_before_edit() {
        val state = DocumentState.createInitial(DocumentId("content"))
        state.putResidentObjectDirect(obj("t", TapePayload(1)))
        val result = ContentPlanner.plan(state, ContentAction.RemoveTape(setOf(ObjectId("t"), ObjectId("absent"))))
        assertIs<Outcome.Failure<*>>(result)
        assertNotNull(state.getObject(ObjectId("t")))
    }
    private fun obj(id: String, payload: ObjectPayload) = SceneObject(ObjectId(id), SpaceId("space-1"), LayerId("layer-1"), DocRect(0.0, 0.0, 100.0, 200.0), payload)
}
