package inkspace.runtime

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.ResizeGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.model.DocVersion
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.runtime.AppCommand
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.MemoryPressureLevel
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.runtime.ViewportState
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class F04LifecycleAndPersistenceTest {

    private fun makeEdit(docId: DocumentId, baseRev: Long, idx: Int): EditPlan {
        return EditPlan(
            base = DocVersion(docId, Revision(baseRev)),
            preconditions = emptyList(),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("obj-$idx"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF112233.toInt(),
                        baseWidthPt = 2.5,
                        origin = DocPoint(idx.toDouble(), idx.toDouble()),
                        samples = listOf(
                            StrokeSamplePoint(0f, 0f, 0.5f),
                            StrokeSamplePoint(15f, 25f, 0.9f)
                        )
                    ),
                    bounds = DocRect(0.0, 0.0, 30.0, 30.0)
                )
            ),
            gesture = GestureId("g-$idx"),
            label = UndoLabel("stroke-$idx")
        )
    }

    @Test
    fun lifecycle_100_rotation_and_surface_cycles_preserve_durable_prefix(@TempDir tempDir: File) {
        val config = RuntimeConfig(storageRootPath = tempDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts()).getOrNull()!!

        val docId = DocumentId("doc-lifecycle-100")
        val session = runtime.openSessionSyncForTest(docId)
        val baselineMemoryBytes = runtime.governor.totalReservedBytes()

        var currentEpoch = SurfaceEpoch(0L)
        var currentResize = ResizeGeneration(0L)
        var rev = 0L

        // Run 100 full rotation/resize/surface destroy+recreate cycles interwoven with commits
        for (cycle in 1..100) {
            currentEpoch = currentEpoch.next()
            currentResize = currentResize.next()
            val isLandscape = (cycle % 2 == 0)
            val width = if (isLandscape) 1920 else 1080
            val height = if (isLandscape) 1080 else 1920

            // 1. Attach recreated Surface for new orientation
            assertTrue(
                runtime.trySend(
                    AppCommand.AttachSurface(
                        session = session,
                        surfaceEpoch = currentEpoch,
                        widthPx = width,
                        heightPx = height,
                        resizeGeneration = currentResize
                    )
                ).isSuccess
            )

            // 2. Submit an edit during the cycle
            assertTrue(
                runtime.trySend(
                    AppCommand.SubmitEdit(
                        session = session,
                        plan = makeEdit(docId, rev, (rev + 1).toInt()),
                        estimatedBytes = 512L
                    )
                ).isSuccess
            )
            rev += 1L

            // 3. Update viewport (camera scroll + portrait/landscape + selection state)
            assertTrue(
                runtime.trySend(
                    AppCommand.UpdateViewport(
                        ViewportState(
                            session = session,
                            camera = CameraTransform(scale = 1.25, translateX = cycle * 4.0, translateY = cycle * 2.0),
                            widthPx = width,
                            heightPx = height,
                            isLandscape = isLandscape,
                            selectedObjectIds = setOf(ObjectId("obj-$rev")),
                            surfaceEpoch = currentEpoch,
                            resizeGeneration = currentResize
                        )
                    )
                ).isSuccess
            )

            // 4. Detach surface (simulating Activity destroy on configuration change) and verify acknowledged teardown
            assertTrue(
                runtime.trySend(
                    AppCommand.DetachSurface(
                        session = session,
                        surfaceEpoch = currentEpoch
                    )
                ).isSuccess
            )

            if (cycle % 20 == 0) {
                runtime.trySend(AppCommand.TrimMemory(MemoryPressureLevel.MODERATE))
            }

            runtime.drainEngineLoopForTest()
            // Drain events so event buffer stays clean across 100 cycles
            val batch = runtime.pollEvents(max = 256)
            assertTrue(batch.events.any { it is AppEvent.SurfaceTeardownAck && it.surfaceEpoch == currentEpoch })
        }

        // Flush and verify all 100 revisions are durable
        assertTrue(runtime.trySend(AppCommand.BackgroundFlush(session)).isSuccess)
        runtime.drainEngineLoopForTest()

        val status = runtime.latestSaveStatus(session)!!
        assertEquals(Revision(100L), status.appliedRevision)
        assertEquals(Revision(100L), status.durableRevision)
        assertEquals(SaveStatusKind.SAVED, status.status)

        val expectedFingerprint = runtime.latestSnapshot(session)!!.contentFingerprint()
        val expectedViewport = runtime.latestViewport(session)!!
        assertEquals(true, expectedViewport.isLandscape)
        assertEquals(1920, expectedViewport.widthPx)
        assertEquals(setOf(ObjectId("obj-100")), expectedViewport.selectedObjectIds)

        // Verify controlled memory delta after 100 cycles is well within <= 8 MiB gate
        val finalMemoryBytes = runtime.governor.totalReservedBytes()
        val deltaMiB = (finalMemoryBytes - baselineMemoryBytes).toDouble() / (1024.0 * 1024.0)
        assertTrue(deltaMiB <= 8.0, "Controlled memory delta after 100 cycles ($deltaMiB MiB) must be <= 8.0 MiB")

        // Simulate abrupt process restart by closing and starting a fresh Runtime on the same storage root
        runtime.closeForTest()

        val restoredRuntime = Runtime.start(config, PlatformPorts()).getOrNull()!!
        val restoredSession = restoredRuntime.openSessionSyncForTest(docId)
        val restoredSnapshot = restoredRuntime.latestSnapshot(restoredSession)!!
        assertEquals(Revision(100L), restoredSnapshot.version.revision)
        assertEquals(100, restoredSnapshot.totalObjectCount)
        assertEquals(expectedFingerprint, restoredSnapshot.contentFingerprint())
        restoredRuntime.closeForTest()
    }
}
