package inkspace.core

import inkspace.core.codec.WriteSetCodec
import inkspace.core.error.EditError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentKind
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.model.SnapshotPinTracker
import inkspace.core.mutation.AddLayer
import inkspace.core.mutation.AddPage
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.History
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.MoveObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.Precondition
import inkspace.core.mutation.RemoveLayer
import inkspace.core.mutation.RemoveObject
import inkspace.core.mutation.RemovePage
import inkspace.core.mutation.ReorderLayer
import inkspace.core.mutation.ReorderPage
import inkspace.core.mutation.ResizePage
import inkspace.core.mutation.SetLayerLock
import inkspace.core.mutation.SetLayerStyle
import inkspace.core.mutation.SetObjectLock
import inkspace.core.mutation.TransformObject
import inkspace.core.mutation.UpdateObjectStyle
import inkspace.core.mutation.UpdatePayload
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.replayWriteSet
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.LinkAnchorPayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.payload.TextPayload
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class F02TransactionTest {

    private val docId = DocumentId("doc-f02-001")
    private val defaultSpace = SpaceId("space-main")
    private val defaultPage = PageId("page-1")
    private val defaultLayer = LayerId("layer-1")
    private val defaultLimits = Limits()

    private fun createInitialState(): DocumentState {
        return DocumentState.createInitial(
            documentId = docId,
            kind = DocumentKind.Notebook,
            initialSpace = defaultSpace,
            initialPage = PageDescriptor(
                id = defaultPage,
                spaceId = defaultSpace,
                widthPt = 595.28,
                heightPt = 841.89
            ),
            initialLayer = LayerDescriptor(
                id = defaultLayer,
                pageId = defaultPage,
                name = "Layer 1",
                opacity = 1.0f,
                isVisible = true,
                isLocked = false
            )
        )
    }

    @Test
    fun atomic_plan() {
        val state = createInitialState()
        val obj1 = ObjectId("obj-1")
        val obj2 = ObjectId("obj-2")

        // Valid initial insert of obj1
        val insertPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj1,
                    payload = StickyPayload(color = 0xFFFFAA00.toInt(), content = "Initial"),
                    bounds = DocRect(10.0, 10.0, 110.0, 110.0)
                )
            ),
            gesture = GestureId("g-init"),
            label = UndoLabel("Insert initial")
        )
        val validatedInit = validatePlan(state.snapshot(), insertPlan, defaultLimits).getOrNull()!!
        val appliedInit = applyPlan(state, validatedInit).getOrNull()!!
        assertEquals(Revision(1L), appliedInit.revision)

        val snapBeforeMalformed = state.snapshot()

        // Malformed batch: first mutation inserts valid obj2, second moves obj1, third tries to remove non-existent obj-999
        val malformedPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj2,
                    payload = StickyPayload(color = 0xFF00AAFF.toInt(), content = "Should rollback"),
                    bounds = DocRect(20.0, 20.0, 120.0, 120.0)
                ),
                MoveObject(id = obj1, deltaX = 50.0, deltaY = 50.0),
                RemoveObject(id = ObjectId("obj-999-missing"))
            ),
            gesture = GestureId("g-malformed"),
            label = UndoLabel("Malformed batch")
        )

        val validationResult = validatePlan(state.snapshot(), malformedPlan, defaultLimits)
        assertTrue(validationResult.isFailure)
        assertTrue(validationResult.errorOrNull() is EditError.EntityNotFound)

        // State must be completely untouched: revision is still 1, obj2 does not exist, obj1 has original bounds
        assertEquals(Revision(1L), state.version.revision)
        assertEquals(ResidencyState.Missing, state.objectState(obj2))
        assertEquals(DocRect(10.0, 10.0, 110.0, 110.0), state.getObject(obj1)?.bounds)
        assertEquals(snapBeforeMalformed, state.snapshot())

        // Also test intra-batch duplicate insert rejection
        val duplicateInBatch = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj2,
                    payload = StickyPayload(color = 1, content = "A"),
                    bounds = DocRect(0.0, 0.0, 10.0, 10.0)
                ),
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj2,
                    payload = StickyPayload(color = 2, content = "B"),
                    bounds = DocRect(0.0, 0.0, 10.0, 10.0)
                )
            ),
            gesture = GestureId("g-dup"),
            label = UndoLabel("Duplicate insert in batch")
        )
        assertTrue(validatePlan(state.snapshot(), duplicateInBatch, defaultLimits).isFailure)
        assertEquals(Revision(1L), state.version.revision)
    }

    @Test
    fun inverse_roundtrip() {
        val state = createInitialState()
        val obj1 = ObjectId("obj-stroke-1")
        val obj2 = ObjectId("obj-shape-1")

        // Step 1: Insert two objects
        val plan1 = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj1,
                    payload = StrokePayload(
                        brushId = "ballpen",
                        color = 0xFF112233.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(10.0, 20.0),
                        samples = listOf(
                            StrokeSamplePoint(0f, 0f, 0.5f),
                            StrokeSamplePoint(15f, 25f, 0.8f)
                        )
                    ),
                    bounds = DocRect(10.0, 20.0, 25.0, 45.0)
                ),
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj2,
                    payload = ShapePayload(ShapeType.Rectangle, strokeColor = 0xFF000000.toInt(), strokeWidthPt = 1.5),
                    bounds = DocRect(100.0, 100.0, 200.0, 180.0)
                )
            ),
            gesture = GestureId("g-1"),
            label = UndoLabel("Add stroke and shape")
        )
        val snap0 = state.snapshot()
        val applied1 = applyPlan(state, validatePlan(snap0, plan1, defaultLimits).getOrNull()!!).getOrNull()!!
        val snap1 = state.snapshot()

        // Step 2: Transform, update payload, and style
        val plan2 = EditPlan(
            base = state.version,
            mutations = listOf(
                MoveObject(obj1, deltaX = 30.0, deltaY = -10.0),
                UpdatePayload(
                    id = obj2,
                    newPayload = ShapePayload(ShapeType.Ellipse, strokeColor = 0xFFFF0000.toInt(), strokeWidthPt = 3.0),
                    newBounds = DocRect(100.0, 100.0, 220.0, 200.0)
                ),
                UpdateObjectStyle(
                    id = obj2,
                    newStyle = ObjectStyle(opacity = 0.6f, zOrder = 5L)
                ),
                RemoveObject(obj1)
            ),
            gesture = GestureId("g-2"),
            label = UndoLabel("Modify and remove")
        )
        val applied2 = applyPlan(state, validatePlan(snap1, plan2, defaultLimits).getOrNull()!!).getOrNull()!!
        val snap2 = state.snapshot()
        assertNotEquals(snap1.contentFingerprint(), snap2.contentFingerprint())

        // Apply inverse of plan2 -> state must match snap1 content
        val inv2Val = validatePlan(state.snapshot(), applied2.inversePlan, defaultLimits).getOrNull()!!
        applyPlan(state, inv2Val).getOrNull()!!
        assertEquals(snap1.contentFingerprint(), state.snapshot().contentFingerprint())
        assertEquals(snap1.getObject(obj1), state.getObject(obj1))
        assertEquals(snap1.getObject(obj2), state.getObject(obj2))

        // Apply inverse of plan1 -> state must match snap0 content
        val inv1ForCurrentRev = applied1.inversePlan.copy(base = state.version)
        val inv1Val = validatePlan(state.snapshot(), inv1ForCurrentRev, defaultLimits).getOrNull()!!
        applyPlan(state, inv1Val).getOrNull()!!
        assertEquals(snap0.contentFingerprint(), state.snapshot().contentFingerprint())
        assertEquals(0, state.totalObjectCount)
    }

    @Test
    fun revision_precondition() {
        val state = createInitialState()
        val obj1 = ObjectId("obj-precond-1")

        val insert = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = obj1,
                    payload = TextPayload("Hello v1"),
                    bounds = DocRect(0.0, 0.0, 100.0, 30.0)
                )
            ),
            gesture = GestureId("g-1"),
            label = UndoLabel("Insert text")
        )
        applyPlan(state, validatePlan(state.snapshot(), insert, defaultLimits).getOrNull()!!)
        assertEquals(1L, state.getObjectVersion(obj1))

        // 1. Stale base revision is rejected
        val stalePlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            mutations = listOf(MoveObject(obj1, 10.0, 10.0)),
            gesture = GestureId("g-stale"),
            label = UndoLabel("Move with stale base")
        )
        val staleRes = validatePlan(state.snapshot(), stalePlan, defaultLimits)
        assertTrue(staleRes.isFailure)
        assertTrue(staleRes.errorOrNull() is EditError.StaleRevision)

        // 2. Wrong entity expectedVersion in precondition is rejected
        val wrongPrecondPlan = EditPlan(
            base = state.version,
            preconditions = listOf(Precondition(id = obj1.value, expectedVersion = 99L)),
            mutations = listOf(MoveObject(obj1, 10.0, 10.0)),
            gesture = GestureId("g-precond-fail"),
            label = UndoLabel("Move with wrong precondition")
        )
        val precondFailRes = validatePlan(state.snapshot(), wrongPrecondPlan, defaultLimits)
        assertTrue(precondFailRes.isFailure)
        assertTrue(precondFailRes.errorOrNull() is EditError.PreconditionFailed)

        // 3. Matching entity expectedVersion succeeds and increments entity version to 2
        val validPrecondPlan = EditPlan(
            base = state.version,
            preconditions = listOf(Precondition(id = obj1.value, expectedVersion = 1L)),
            mutations = listOf(MoveObject(obj1, 10.0, 10.0)),
            gesture = GestureId("g-precond-ok"),
            label = UndoLabel("Move with valid precondition")
        )
        val validRes = validatePlan(state.snapshot(), validPrecondPlan, defaultLimits)
        assertTrue(validRes.isSuccess)
        applyPlan(state, validRes.getOrNull()!!)
        assertEquals(2L, state.getObjectVersion(obj1))
    }

    @Test
    fun locked_entities() {
        val state = createInitialState()
        val obj1 = ObjectId("obj-lock-1")
        val obj2 = ObjectId("obj-lock-2")

        // Insert obj1 and obj2
        val insertPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, obj1, StickyPayload(1, "One"), DocRect(0.0, 0.0, 50.0, 50.0)),
                InsertObject(defaultSpace, defaultLayer, obj2, StickyPayload(2, "Two"), DocRect(60.0, 0.0, 110.0, 50.0))
            ),
            gesture = GestureId("g-ins"),
            label = UndoLabel("Insert two")
        )
        applyPlan(state, validatePlan(state.snapshot(), insertPlan, defaultLimits).getOrNull()!!)

        // Lock obj1
        val lockObjPlan = EditPlan(
            base = state.version,
            mutations = listOf(SetObjectLock(obj1, isLocked = true)),
            gesture = GestureId("g-lock-obj"),
            label = UndoLabel("Lock obj1")
        )
        applyPlan(state, validatePlan(state.snapshot(), lockObjPlan, defaultLimits).getOrNull()!!)
        assertTrue(state.isLocked(obj1))

        // Attempting to move, update, or remove locked obj1 fails with LockedEntity
        for (mut in listOf(
            MoveObject(obj1, 5.0, 5.0),
            UpdatePayload(obj1, StickyPayload(1, "Hacked"), DocRect(0.0, 0.0, 50.0, 50.0)),
            RemoveObject(obj1)
        )) {
            val res = validatePlan(
                state.snapshot(),
                EditPlan(state.version, mutations = listOf(mut), gesture = GestureId("g-try"), label = UndoLabel("Try")),
                defaultLimits
            )
            assertTrue(res.isFailure)
            assertTrue(res.errorOrNull() is EditError.LockedEntity)
        }

        // Unlocking obj1 succeeds
        val unlockObjPlan = EditPlan(
            base = state.version,
            mutations = listOf(SetObjectLock(obj1, isLocked = false)),
            gesture = GestureId("g-unlock-obj"),
            label = UndoLabel("Unlock obj1")
        )
        assertTrue(applyPlan(state, validatePlan(state.snapshot(), unlockObjPlan, defaultLimits).getOrNull()!!).isSuccess)
        assertFalse(state.isLocked(obj1))

        // Now lock the entire layer
        val lockLayerPlan = EditPlan(
            base = state.version,
            mutations = listOf(SetLayerLock(defaultLayer, isLocked = true)),
            gesture = GestureId("g-lock-layer"),
            label = UndoLabel("Lock layer")
        )
        assertTrue(applyPlan(state, validatePlan(state.snapshot(), lockLayerPlan, defaultLimits).getOrNull()!!).isSuccess)

        // Moving obj2 on the locked layer or inserting a new object into the locked layer must fail with LockedEntity
        val moveOnLockedLayer = EditPlan(
            base = state.version,
            mutations = listOf(MoveObject(obj2, 10.0, 10.0)),
            gesture = GestureId("g-move-locked-layer"),
            label = UndoLabel("Move on locked layer")
        )
        val moveLayerRes = validatePlan(state.snapshot(), moveOnLockedLayer, defaultLimits)
        assertTrue(moveLayerRes.isFailure)
        assertTrue(moveLayerRes.errorOrNull() is EditError.LockedEntity)

        val insertOnLockedLayer = EditPlan(
            base = state.version,
            mutations = listOf(InsertObject(defaultSpace, defaultLayer, ObjectId("obj-3"), StickyPayload(3, "3"), DocRect(0.0, 0.0, 10.0, 10.0))),
            gesture = GestureId("g-ins-locked-layer"),
            label = UndoLabel("Insert on locked layer")
        )
        assertTrue(validatePlan(state.snapshot(), insertOnLockedLayer, defaultLimits).errorOrNull() is EditError.LockedEntity)
    }

    @Test
    fun group_cycles() {
        val state = createInitialState()
        val item1 = ObjectId("item-1")
        val item2 = ObjectId("item-2")
        val groupA = ObjectId("group-A")
        val groupB = ObjectId("group-B")

        val setupPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, item1, StickyPayload(1, "1"), DocRect(0.0, 0.0, 20.0, 20.0)),
                InsertObject(defaultSpace, defaultLayer, item2, StickyPayload(2, "2"), DocRect(30.0, 0.0, 50.0, 20.0)),
                InsertObject(defaultSpace, defaultLayer, groupA, GroupPayload(listOf(item1)), DocRect(0.0, 0.0, 20.0, 20.0)),
                InsertObject(defaultSpace, defaultLayer, groupB, GroupPayload(listOf(groupA, item2)), DocRect(0.0, 0.0, 50.0, 20.0))
            ),
            gesture = GestureId("g-setup-groups"),
            label = UndoLabel("Setup groups")
        )
        assertTrue(applyPlan(state, validatePlan(state.snapshot(), setupPlan, defaultLimits).getOrNull()!!).isSuccess)

        // 1. Self-cycle: groupA contains groupA -> CycleDetected
        val selfCyclePlan = EditPlan(
            base = state.version,
            mutations = listOf(
                UpdatePayload(groupA, GroupPayload(listOf(groupA)), DocRect(0.0, 0.0, 20.0, 20.0))
            ),
            gesture = GestureId("g-self-cycle"),
            label = UndoLabel("Self cycle")
        )
        val selfCycleRes = validatePlan(state.snapshot(), selfCyclePlan, defaultLimits)
        assertTrue(selfCycleRes.isFailure)
        assertTrue(selfCycleRes.errorOrNull() is EditError.CycleDetected)

        // 2. Indirect cycle: groupA -> groupB while groupB -> groupA -> CycleDetected
        val indirectCyclePlan = EditPlan(
            base = state.version,
            mutations = listOf(
                UpdatePayload(groupA, GroupPayload(listOf(item1, groupB)), DocRect(0.0, 0.0, 50.0, 20.0))
            ),
            gesture = GestureId("g-indirect-cycle"),
            label = UndoLabel("Indirect cycle")
        )
        val indirectCycleRes = validatePlan(state.snapshot(), indirectCyclePlan, defaultLimits)
        assertTrue(indirectCycleRes.isFailure)
        assertTrue(indirectCycleRes.errorOrNull() is EditError.CycleDetected)

        // 3. Duplicate parent ownership: groupC tries to also own item1 (which is already in groupA) -> PreconditionFailed
        val duplicateParentPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, ObjectId("group-C"), GroupPayload(listOf(item1)), DocRect(0.0, 0.0, 20.0, 20.0))
            ),
            gesture = GestureId("g-dup-parent"),
            label = UndoLabel("Duplicate parent")
        )
        assertTrue(validatePlan(state.snapshot(), duplicateParentPlan, defaultLimits).isFailure)

        // 4. Exceeding maxGroupDepth (e.g. limit = 2) -> LimitExceeded
        val shallowLimits = Limits(maxGroupDepth = 2)
        val groupC = ObjectId("group-C-deep")
        val tooDeepPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, groupC, GroupPayload(listOf(groupB)), DocRect(0.0, 0.0, 50.0, 20.0))
            ),
            gesture = GestureId("g-too-deep"),
            label = UndoLabel("Too deep group")
        )
        val deepRes = validatePlan(state.snapshot(), tooDeepPlan, shallowLimits)
        assertTrue(deepRes.isFailure)
        assertTrue(deepRes.errorOrNull() is EditError.LimitExceeded)
    }

    @Test
    fun missing_vs_nonresident() {
        val state = createInitialState()
        val nonResidentId = ObjectId("obj-non-resident-1")
        val missingId = ObjectId("obj-missing-1")

        // Register a non-resident object header in directory (e.g. cold page object before payload chunk is loaded)
        state.registerNonResidentObject(
            id = nonResidentId,
            space = defaultSpace,
            layer = defaultLayer,
            bounds = DocRect(10.0, 10.0, 60.0, 60.0),
            version = 1L,
            estimatedBytes = 2048
        )

        assertEquals(ResidencyState.NotResident, state.objectState(nonResidentId))
        assertEquals(ResidencyState.Missing, state.objectState(missingId))

        // Mutating Missing object returns EntityNotFound
        val mutMissing = EditPlan(
            base = state.version,
            mutations = listOf(MoveObject(missingId, 5.0, 5.0)),
            gesture = GestureId("g-missing"),
            label = UndoLabel("Move missing")
        )
        val missingRes = validatePlan(state.snapshot(), mutMissing, defaultLimits)
        assertTrue(missingRes.isFailure)
        assertTrue(missingRes.errorOrNull() is EditError.EntityNotFound)

        // Mutating NotResident object returns EntityNotResident (never treated as missing or silently overwritten)
        val mutNonResident = EditPlan(
            base = state.version,
            mutations = listOf(MoveObject(nonResidentId, 5.0, 5.0)),
            gesture = GestureId("g-nonres"),
            label = UndoLabel("Move non-resident")
        )
        val nonResResult = validatePlan(state.snapshot(), mutNonResident, defaultLimits)
        assertTrue(nonResResult.isFailure)
        assertTrue(nonResResult.errorOrNull() is EditError.EntityNotResident)

        // Inserting with an ID that is NotResident also fails because the object already exists in the document
        val insertOverNonRes = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, nonResidentId, StickyPayload(1, "Overwrite"), DocRect(0.0, 0.0, 10.0, 10.0))
            ),
            gesture = GestureId("g-ins-nonres"),
            label = UndoLabel("Overwrite non-resident")
        )
        assertTrue(validatePlan(state.snapshot(), insertOverNonRes, defaultLimits).isFailure)
    }

    @Test
    fun chunked_directory_w2_single_edit_allocations() {
        val w2Limits = Limits(maxObjectsPerSpace = 150_000)
        val state = createInitialState()

        // Populate W2 scale: 100,000 objects across 500 pages (99,000 non-resident cold objects + 1,000 resident visible objects)
        val sampleStroke = StrokePayload(
            brushId = "pen",
            color = 0xFF000000.toInt(),
            baseWidthPt = 2.0,
            origin = DocPoint(0.0, 0.0),
            samples = List(64) { i -> StrokeSamplePoint(i.toFloat(), i.toFloat(), 0.5f) }
        )

        for (i in 0 until 99_000) {
            state.registerNonResidentObject(
                id = ObjectId("w2-cold-$i"),
                space = defaultSpace,
                layer = defaultLayer,
                bounds = DocRect((i % 500) * 10.0, 0.0, (i % 500) * 10.0 + 5.0, 5.0),
                version = 1L,
                estimatedBytes = 2048
            )
        }
        for (i in 0 until 1_000) {
            state.putResidentObjectDirect(
                SceneObject(
                    id = ObjectId("w2-vis-$i"),
                    space = defaultSpace,
                    layer = defaultLayer,
                    bounds = DocRect(i * 1.0, 0.0, i * 1.0 + 10.0, 10.0),
                    payload = sampleStroke,
                    version = 1L
                )
            )
        }

        assertEquals(100_000, state.totalObjectCount)
        val snapBefore = state.snapshot()

        // Perform a single-object edit on the 100,000-object W2 state
        val singleEditPlan = EditPlan(
            base = state.version,
            mutations = listOf(MoveObject(ObjectId("w2-vis-500"), deltaX = 12.5, deltaY = 7.5)),
            gesture = GestureId("g-w2-single"),
            label = UndoLabel("Single stroke move on W2")
        )
        val validated = validatePlan(snapBefore, singleEditPlan, w2Limits).getOrNull()!!
        val applied = applyPlan(state, validated).getOrNull()!!
        val snapAfter = state.snapshot()

        assertEquals(Revision(1L), applied.revision)
        assertEquals(100_000, snapAfter.totalObjectCount)

        // Verify structural sharing: out of all non-empty leaf chunks in the directory, only 1 leaf chunk changed!
        val sharedLeafChunks = snapBefore.directory.countSharedLeafChunks(snapAfter.directory)
        val totalLeafChunks = snapAfter.directory.totalLeafChunks()
        assertTrue(totalLeafChunks >= 1000, "Expected at least 1000 leaf chunks, got $totalLeafChunks")
        assertEquals(totalLeafChunks - 1, sharedLeafChunks, "Single object edit must only allocate 1 new leaf chunk out of $totalLeafChunks")

        // Verify incremental allocation size of the mutated directory path across 100 single-object edits on W2
        val incrementalSamples = LongArray(100)
        incrementalSamples[0] = snapAfter.directory.estimateUnsharedStructureBytes(snapBefore.directory)
        var prevSnap = snapAfter
        for (sampleIdx in 1 until 100) {
            val targetObj = ObjectId("w2-vis-${sampleIdx % 1000}")
            val planSample = EditPlan(
                base = state.version,
                mutations = listOf(MoveObject(targetObj, deltaX = 1.0, deltaY = 1.0)),
                gesture = GestureId("g-w2-sample-$sampleIdx"),
                label = UndoLabel("W2 sample $sampleIdx")
            )
            val valSample = validatePlan(prevSnap, planSample, w2Limits).getOrNull()!!
            applyPlan(state, valSample).getOrNull()!!
            val nextSnap = state.snapshot()
            incrementalSamples[sampleIdx] = nextSnap.directory.estimateUnsharedStructureBytes(prevSnap.directory)
            prevSnap = nextSnap
        }
        val sortedSamples = incrementalSamples.sortedArray()
        val p50 = sortedSamples[50]
        val p95 = sortedSamples[95]
        val p99 = sortedSamples[99]
        val rawFile = java.io.File("../../docs/evidence/F02/w2-allocations-raw.json")
        rawFile.parentFile?.mkdirs()
        rawFile.writeText(
            """{"workload":"W2","total_objects":100000,"sample_count":100,"p50_bytes":$p50,"p95_bytes":$p95,"p99_bytes":$p99,"samples_bytes":[${incrementalSamples.joinToString(",")}]}""" + "\n"
        )
        println("[F02 W2 Single-Edit Allocations] N=100,000 objects, samples=100: p50=${p50}B, p95=${p95}B, p99=${p99}B")
        assertTrue(p99 <= 16_384L, "Incremental directory allocation p99=$p99 bytes exceeded 16 KiB bound")
    }

    @Test
    fun changeset_bounds_and_gesture_grouping() {
        val state = createInitialState()
        val history = History(maxEntries = 100, maxPinnedBytes = 10_000_000L)
        val obj1 = ObjectId("obj-cs-1")
        val obj2 = ObjectId("obj-cs-2")

        // 1. Insert two objects on a page -> exact newBounds is union, oldBounds is null
        val insPlan = EditPlan(
            base = state.version,
            mutations = listOf(
                InsertObject(defaultSpace, defaultLayer, obj1, StickyPayload(1, "A"), DocRect(10.0, 20.0, 50.0, 60.0)),
                InsertObject(defaultSpace, defaultLayer, obj2, StickyPayload(2, "B"), DocRect(100.0, 120.0, 150.0, 180.0))
            ),
            gesture = GestureId("g-ins"),
            label = UndoLabel("Insert two")
        )
        val appIns = applyPlan(state, validatePlan(state.snapshot(), insPlan, defaultLimits).getOrNull()!!).getOrNull()!!
        history.record(insPlan, appIns)
        assertNull(appIns.changeSet.oldBounds)
        assertEquals(DocRect(10.0, 20.0, 150.0, 180.0), appIns.changeSet.newBounds)
        assertEquals(setOf(obj1, obj2), appIns.changeSet.changedIds)

        // 2. Multi-step resize/move with the SAME GestureId coalesces into ONE logical history entry
        val dragGesture = GestureId("g-drag-resize-1")
        for (step in 1..4) {
            val stepPlan = EditPlan(
                base = state.version,
                mutations = listOf(MoveObject(obj1, deltaX = 5.0, deltaY = 2.5)),
                gesture = dragGesture,
                label = UndoLabel("Drag move")
            )
            val appStep = applyPlan(state, validatePlan(state.snapshot(), stepPlan, defaultLimits).getOrNull()!!).getOrNull()!!
            history.record(stepPlan, appStep)
        }
        // Even though 4 revisions were applied, History has 2 logical entries (1 for insert, 1 for the 4-step drag gesture)
        assertEquals(2, history.undoCount)
        assertEquals(DocRect(30.0, 30.0, 70.0, 70.0), state.getObject(obj1)?.bounds)

        // Single undo reverts all 4 drag steps at once back to (10, 20, 50, 60)!
        val undoDragPlan = history.undo(state.version)!!
        val appUndoDrag = applyPlan(state, validatePlan(state.snapshot(), undoDragPlan, defaultLimits).getOrNull()!!).getOrNull()!!
        assertEquals(DocRect(30.0, 30.0, 70.0, 70.0), appUndoDrag.changeSet.oldBounds)
        assertEquals(DocRect(10.0, 20.0, 50.0, 60.0), appUndoDrag.changeSet.newBounds)
        assertEquals(DocRect(10.0, 20.0, 50.0, 60.0), state.getObject(obj1)?.bounds)

        // 3. Clear page (removing both objects in one gesture) produces one history entry and exact oldBounds
        val clearPagePlan = EditPlan(
            base = state.version,
            mutations = listOf(RemoveObject(obj1), RemoveObject(obj2)),
            gesture = GestureId("g-clear-page"),
            label = UndoLabel("Clear page")
        )
        val appClear = applyPlan(state, validatePlan(state.snapshot(), clearPagePlan, defaultLimits).getOrNull()!!).getOrNull()!!
        history.record(clearPagePlan, appClear)
        assertEquals(DocRect(10.0, 20.0, 150.0, 180.0), appClear.changeSet.oldBounds)
        assertNull(appClear.changeSet.newBounds)
        assertEquals(0, state.totalObjectCount)

        // Undo clear page restores both objects in one step
        val undoClearPlan = history.undo(state.version)!!
        applyPlan(state, validatePlan(state.snapshot(), undoClearPlan, defaultLimits).getOrNull()!!)
        assertEquals(2, state.totalObjectCount)

        // 4. No-op plan (e.g. MoveObject by 0.0, 0.0) is rejected and does NOT create a new revision
        val revBeforeNoOp = state.version.revision
        val noOpPlan = EditPlan(
            base = state.version,
            mutations = listOf(MoveObject(obj1, deltaX = 0.0, deltaY = 0.0)),
            gesture = GestureId("g-noop"),
            label = UndoLabel("No-op move")
        )
        val noOpRes = validatePlan(state.snapshot(), noOpPlan, defaultLimits)
        assertTrue(noOpRes.isFailure)
        assertTrue(noOpRes.errorOrNull() is EditError.NoOpPlan)
        assertEquals(revBeforeNoOp, state.version.revision)
    }

    @Test
    fun property_10000_transaction_sequences_undo_redo() {
        val rng = Random(20261105L)
        val totalSequences = 10_000
        var totalTransactionsApplied = 0

        for (seqIdx in 0 until totalSequences) {
            val state = createInitialState()
            val history = History(maxEntries = 64, maxPinnedBytes = 64_000_000L)
            val initialSnapshot = state.snapshot()

            val stepsCount = 3 + rng.nextInt(6) // 3..8 transactions per sequence => ~55,000 transactions across 10,000 sequences
            val activeObjects = mutableListOf<ObjectId>()
            val activeGroups = mutableListOf<ObjectId>()
            val activeLayers = mutableListOf(defaultLayer)
            val activePages = mutableListOf(defaultPage)

            for (step in 0 until stepsCount) {
                val gestureId = GestureId("seq-$seqIdx-step-$step")
                val opChoice = rng.nextInt(8)
                val mutation: Mutation? = when {
                    activeObjects.isEmpty() || opChoice == 0 -> {
                        // Create object (stroke, shape, image with asset ref, or link anchor)
                        val newId = ObjectId("obj-$seqIdx-$step")
                        val layerId = activeLayers[rng.nextInt(activeLayers.size)]
                        val x = (rng.nextInt(400)).toDouble()
                        val y = (rng.nextInt(600)).toDouble()
                        val payload = when (rng.nextInt(4)) {
                            0 -> StrokePayload(
                                brushId = "pen",
                                color = rng.nextInt(),
                                baseWidthPt = 1.5,
                                origin = DocPoint(x, y),
                                samples = listOf(StrokeSamplePoint(0f, 0f, 0.5f), StrokeSamplePoint(10f, 10f, 0.7f))
                            )
                            1 -> ShapePayload(ShapeType.Rectangle, strokeColor = rng.nextInt(), strokeWidthPt = 2.0)
                            2 -> ImagePayload(AssetId("sha256:asset-${rng.nextInt(20)}"), 800, 600)
                            else -> LinkAnchorPayload("Link $step", targetPage = activePages[rng.nextInt(activePages.size)])
                        }
                        InsertObject(defaultSpace, layerId, newId, payload, DocRect(x, y, x + 40.0, y + 30.0))
                    }
                    opChoice == 1 -> {
                        // Style update
                        val target = activeObjects[rng.nextInt(activeObjects.size)]
                        val curStyle = state.getObject(target)!!.style
                        val newOpacity = if (curStyle.opacity > 0.5f) 0.4f else 0.9f
                        UpdateObjectStyle(target, curStyle.copy(opacity = newOpacity, zOrder = curStyle.zOrder + 1L))
                    }
                    opChoice == 2 -> {
                        // Transform / Move
                        val target = activeObjects[rng.nextInt(activeObjects.size)]
                        if (rng.nextBoolean()) {
                            MoveObject(target, deltaX = (1 + rng.nextInt(25)).toDouble(), deltaY = (1 + rng.nextInt(25)).toDouble())
                        } else {
                            val curObj = state.getObject(target)!!
                            val nextTx = curObj.transform.translate(5.0, -3.0)
                            val b = curObj.bounds
                            TransformObject(target, nextTx, DocRect(b.left + 5.0, b.top - 3.0, b.right + 5.0, b.bottom - 3.0))
                        }
                    }
                    opChoice == 3 -> {
                        // Delete ungrouped object
                        val ungrouped = activeObjects.filter { id -> state.parentGroupOf(id) == null }
                        if (ungrouped.isNotEmpty()) {
                            val target = ungrouped[rng.nextInt(ungrouped.size)]
                            RemoveObject(target)
                        } else null
                    }
                    opChoice == 4 -> {
                        // Group ungrouped objects
                        val candidates = activeObjects.filter { id -> state.parentGroupOf(id) == null }
                        if (candidates.size >= 2) {
                            val m1 = candidates[0]
                            val m2 = candidates[1]
                            val b1 = state.getObject(m1)!!.bounds
                            val b2 = state.getObject(m2)!!.bounds
                            val union = b1.union(b2)
                            val gId = ObjectId("grp-$seqIdx-$step")
                            InsertObject(defaultSpace, defaultLayer, gId, GroupPayload(listOf(m1, m2)), union)
                        } else null
                    }
                    opChoice == 5 -> {
                        // Layer add or style change
                        if (activeLayers.size < 4 && rng.nextBoolean()) {
                            val newLayer = LayerId("layer-$seqIdx-$step")
                            AddLayer(
                                LayerDescriptor(
                                    id = newLayer,
                                    pageId = activePages[0],
                                    name = "Layer $step",
                                    opacity = 0.85f,
                                    isVisible = true,
                                    isLocked = false
                                )
                            )
                        } else {
                            val targetLayer = activeLayers[rng.nextInt(activeLayers.size)]
                            val desc = state.getLayer(targetLayer)!!
                            val nextOpacity = if (desc.opacity > 0.5f) 0.35f else 0.95f
                            SetLayerStyle(targetLayer, opacity = nextOpacity, isVisible = desc.isVisible)
                        }
                    }
                    opChoice == 6 -> {
                        // Page add or resize
                        if (activePages.size < 4 && rng.nextBoolean()) {
                            val newPage = PageId("page-$seqIdx-$step")
                            AddPage(PageDescriptor(id = newPage, spaceId = defaultSpace, widthPt = 612.0, heightPt = 792.0))
                        } else {
                            val targetPage = activePages[rng.nextInt(activePages.size)]
                            val desc = state.getPage(targetPage)!!
                            ResizePage(targetPage, newWidthPt = desc.widthPt + 10.0, newHeightPt = desc.heightPt + 10.0)
                        }
                    }
                    else -> {
                        // Reorder pages or layers if >1, else move object
                        if (activePages.size >= 2) {
                            ReorderPage(activePages.reversed())
                        } else {
                            val target = activeObjects[rng.nextInt(activeObjects.size)]
                            MoveObject(target, deltaX = 3.0, deltaY = 4.0)
                        }
                    }
                }

                if (mutation != null) {
                    val plan = EditPlan(
                        base = state.version,
                        mutations = listOf(mutation),
                        gesture = gestureId,
                        label = UndoLabel("Step $step")
                    )
                    val validated = validatePlan(state.snapshot(), plan, defaultLimits)
                    assertTrue(validated.isSuccess, "Expected valid plan in seq $seqIdx step $step: $mutation -> ${validated.errorOrNull()}")
                    val applied = applyPlan(state, validated.getOrNull()!!)
                    assertTrue(applied.isSuccess)
                    history.record(plan, applied.getOrNull()!!)
                    totalTransactionsApplied++

                    when (mutation) {
                        is InsertObject -> {
                            activeObjects.add(mutation.id)
                            if (mutation.payload is GroupPayload) activeGroups.add(mutation.id)
                        }
                        is RemoveObject -> {
                            activeObjects.remove(mutation.id)
                            activeGroups.remove(mutation.id)
                        }
                        is AddLayer -> activeLayers.add(mutation.layer.id)
                        is AddPage -> activePages.add(mutation.page.id)
                        is ReorderPage -> {
                            activePages.clear()
                            activePages.addAll(mutation.orderedPageIds)
                        }
                        else -> {}
                    }
                }
            }

            val finalSnapshot = state.snapshot()
            assertTrue(finalSnapshot.verifyInvariants(), "Final snapshot invariants failed at seq $seqIdx")

            // Undo all steps -> must equal initialSnapshot
            while (history.canUndo) {
                val undoPlan = history.undo(state.version)!!
                val valUndo = validatePlan(state.snapshot(), undoPlan, defaultLimits)
                assertTrue(valUndo.isSuccess, "Undo validation failed in seq $seqIdx: ${valUndo.errorOrNull()}")
                val appUndo = applyPlan(state, valUndo.getOrNull()!!)
                assertTrue(appUndo.isSuccess, "Undo apply failed in seq $seqIdx: ${appUndo.errorOrNull()}")
            }

            val afterUndoSnapshot = state.snapshot()
            assertEquals(initialSnapshot.contentFingerprint(), afterUndoSnapshot.contentFingerprint(), "Undo mismatch at seq $seqIdx")
            assertEquals(initialSnapshot.totalObjectCount, afterUndoSnapshot.totalObjectCount)
            assertEquals(initialSnapshot.pages, afterUndoSnapshot.pages)
            assertEquals(initialSnapshot.layers, afterUndoSnapshot.layers)

            // Redo all steps -> must equal finalSnapshot
            while (history.canRedo) {
                val redoPlan = history.redo(state.version)!!
                val valRedo = validatePlan(state.snapshot(), redoPlan, defaultLimits)
                assertTrue(valRedo.isSuccess, "Redo validation failed in seq $seqIdx: ${valRedo.errorOrNull()}")
                val appRedo = applyPlan(state, valRedo.getOrNull()!!)
                assertTrue(appRedo.isSuccess, "Redo apply failed in seq $seqIdx: ${appRedo.errorOrNull()}")
            }

            val afterRedoSnapshot = state.snapshot()
            assertEquals(finalSnapshot.contentFingerprint(), afterRedoSnapshot.contentFingerprint(), "Redo mismatch at seq $seqIdx")
            assertEquals(finalSnapshot.orderedSceneObjects(), afterRedoSnapshot.orderedSceneObjects())
            assertEquals(finalSnapshot.referencedAssets(), afterRedoSnapshot.referencedAssets())
            assertTrue(afterRedoSnapshot.verifyInvariants(), "After-redo invariants failed at seq $seqIdx")
        }

        assertTrue(totalTransactionsApplied >= 10_000, "Expected >= 10,000 transactions, got $totalTransactionsApplied")
    }

    @Test
    fun versioned_writeset_codec_and_replay_validation() {
        val stateProducer = createInitialState()
        val stateConsumer = createInitialState()
        val history = History(maxEntries = 32, maxPinnedBytes = 4_000_000L)
        val pinTracker = SnapshotPinTracker(maxTotalPinnedBytes = 8_000_000L)

        val assetId = AssetId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val imgObj = ObjectId("obj-img-1")
        val strokeObj = ObjectId("obj-stroke-1")

        val plan = EditPlan(
            base = stateProducer.version,
            mutations = listOf(
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = imgObj,
                    payload = ImagePayload(asset = assetId, naturalWidthPx = 1920, naturalHeightPx = 1080),
                    bounds = DocRect(0.0, 0.0, 400.0, 300.0)
                ),
                InsertObject(
                    space = defaultSpace,
                    layer = defaultLayer,
                    id = strokeObj,
                    payload = StrokePayload(
                        brushId = "fountain",
                        color = 0xFF001122.toInt(),
                        baseWidthPt = 2.5,
                        origin = DocPoint(10.0, 10.0),
                        samples = listOf(StrokeSamplePoint(0f, 0f, 0.6f), StrokeSamplePoint(20f, 30f, 0.8f))
                    ),
                    bounds = DocRect(10.0, 10.0, 30.0, 40.0)
                )
            ),
            gesture = GestureId("g-ws-1"),
            label = UndoLabel("Insert image and stroke")
        )

        val applied = applyPlan(stateProducer, validatePlan(stateProducer.snapshot(), plan, defaultLimits).getOrNull()!!).getOrNull()!!
        history.record(plan, applied)

        val writeSet = applied.writeSet
        assertEquals(1, writeSet.schemaVersion)
        assertEquals(Revision(0L), writeSet.baseRevision)
        assertEquals(Revision(1L), writeSet.newRevision)
        assertEquals(setOf(assetId), writeSet.referencedAssets)

        // Encode and decode WriteSet via CBOR and JSON
        val cborBytes = WriteSetCodec.encodeToCbor(writeSet)
        val decodedCbor = WriteSetCodec.decodeFromCbor(cborBytes).getOrNull()!!
        assertEquals(writeSet, decodedCbor)

        val jsonStr = WriteSetCodec.encodeToJson(writeSet)
        val decodedJson = WriteSetCodec.decodeFromJson(jsonStr).getOrNull()!!
        assertEquals(writeSet, decodedJson)

        // Replay decoded WriteSet onto independent stateConsumer -> must produce identical state and payload digests
        val replayed = replayWriteSet(stateConsumer, decodedCbor, defaultLimits)
        assertTrue(replayed.isSuccess)
        assertEquals(stateProducer.snapshot().contentFingerprint(), stateConsumer.snapshot().contentFingerprint())
        assertEquals(stateProducer.getObject(imgObj)?.payloadDigest, stateConsumer.getObject(imgObj)?.payloadDigest)
        assertEquals(stateProducer.getObject(strokeObj)?.payloadDigest, stateConsumer.getObject(strokeObj)?.payloadDigest)

        // Tampered payload digest in WriteSet is rejected during replay validation without mutating state
        val stateTamperTarget = createInitialState()
        val tamperedWriteSet = decodedCbor.copy(
            records = decodedCbor.records.map { rec -> rec.copy(expectedDigest = rec.expectedDigest xor 0x55555555L) }
        )
        val tamperedReplay = replayWriteSet(stateTamperTarget, tamperedWriteSet, defaultLimits)
        assertTrue(tamperedReplay.isFailure)
        assertEquals(Revision(0L), stateTamperTarget.version.revision)

        // Snapshot pins measured together with history
        val lease = pinTracker.acquirePin(stateProducer.snapshot(), history).getOrNull()!!
        assertNotNull(lease)
        assertTrue(pinTracker.currentTotalBytes(history) > history.pinnedBytes)
        lease.close()
        assertEquals(history.pinnedBytes, pinTracker.currentTotalBytes(history))
    }
}
