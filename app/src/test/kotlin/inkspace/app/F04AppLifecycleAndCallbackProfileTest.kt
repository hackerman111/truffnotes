package inkspace.app

import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.model.DocVersion
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.runtime.AppEvent
import inkspace.core.runtime.InputActionKind
import inkspace.core.runtime.MemoryPressureLevel
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import inkspace.core.runtime.SaveStatusKind
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Locale

class F04AppLifecycleAndCallbackProfileTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun oldSurfaceCallbackKeepsItsSessionAndCannotDetachNewSurface() {
        val container = TruffnotesAppContainer.create(RuntimeConfig(tempDir.resolve("switch-surface").toString())).getOrNull()!!
        try {
            container.openOrRestoreSession(DocumentId("a"))
            awaitCondition { container.syncEvents(); container.controller.uiState.activeSession?.document == DocumentId("a") }
            val oldSession = container.controller.uiState.activeSession!!
            val oldEpoch = container.onSurfaceAttached(800, 1200).getOrNull()!!
            container.openOrRestoreSession(DocumentId("b"))
            awaitCondition { container.syncEvents(); container.controller.uiState.activeSession?.document == DocumentId("b") }
            val newSession = container.controller.uiState.activeSession!!
            val newEpoch = container.onSurfaceAttached(800, 1200).getOrNull()!!
            assertEquals(oldEpoch, container.onSurfaceDestroyed(oldSession))
            assertTrue(container.surfaceHost.isSurfaceActive())
            assertEquals(newEpoch, container.onSurfaceDestroyed(newSession))
        } finally {
            container.closeAsync()
        }
    }

    @Test
    fun app_container_handles_rotation_background_reopen_and_profiles_callbacks_under_1ms() {
        val rootDir = tempDir.resolve("app-store").toFile()
        val docId = DocumentId("doc-f04-app-e2e")

        val containerOutcome = TruffnotesAppContainer.create(
            config = RuntimeConfig(storageRootPath = rootDir.absolutePath),
            ports = PlatformPorts()
        )
        assertTrue(containerOutcome.isSuccess)
        val container = containerOutcome.getOrNull()!!

        // 1. Cold start open session
        assertTrue(container.openOrRestoreSession(docId, savedBundle = null).isSuccess)
        awaitCondition {
            container.syncEvents()
            container.controller.uiState.activeSession != null
        }

        val session = container.controller.uiState.activeSession!!
        assertNotNull(container.controller.uiState.snapshot)

        // 2. Attach surface, submit edit + input batches, rotate & save state
        assertTrue(container.onSurfaceAttached(1600, 2560).isSuccess)
        val editPlan = EditPlan(
            base = DocVersion(docId, Revision(0L)),
            preconditions = emptyList(),
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("stroke-app-1"),
                    payload = StrokePayload(
                        brushId = "pen",
                        color = 0xFF000000.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(0.0, 0.0),
                        samples = listOf(
                            StrokeSamplePoint(0f, 0f, 0.5f),
                            StrokeSamplePoint(50f, 50f, 0.6f)
                        )
                    ),
                    bounds = DocRect(0.0, 0.0, 50.0, 50.0)
                )
            ),
            gesture = GestureId("g-app-1"),
            label = UndoLabel("stroke-app-1")
        )
        assertTrue(container.controller.dispatchEdit(editPlan).isSuccess)

        awaitCondition {
            container.syncEvents()
            container.controller.uiState.saveStatus?.durableRevision == Revision(1L) &&
                container.controller.uiState.saveStatus?.status == SaveStatusKind.SAVED
        }

        assertTrue(
            container.controller.updateViewport {
                it.copy(
                    camera = CameraTransform(scale = 1.5, translateX = 42.0, translateY = 84.0),
                    isFocusMode = true
                )
            }.isSuccess
        )
        awaitCondition {
            container.syncEvents()
            container.controller.uiState.viewport?.isFocusMode == true
        }

        val savedBundle = container.onSaveInstanceState()
        assertNotNull(savedBundle)
        assertEquals(docId.value, savedBundle!!.documentId)
        assertEquals(42.0, savedBundle.cameraTx, 1e-6)
        assertTrue(savedBundle.isFocusMode)

        // Simulate Surface destroy + rotation resize + BackgroundFlush + TrimMemory
        val destroyedEpoch: SurfaceEpoch? = container.onSurfaceDestroyed()
        assertNotNull(destroyedEpoch)
        awaitCondition {
            container.syncEvents()
            container.surfaceHost.isTeardownAcknowledged(destroyedEpoch!!)
        }

        assertTrue(container.onSurfaceAttached(2560, 1600).isSuccess)
        assertTrue(container.onSurfaceChanged(2560, 1500).isSuccess)
        assertTrue(container.onBackgroundFlush().isSuccess)
        assertTrue(container.onTrimMemory(MemoryPressureLevel.MODERATE).isSuccess)

        // 3. Profile 500 end-to-end input batch callbacks through InputTransport -> Controller -> Runtime
        val xs = floatArrayOf(10f, 12f, 14f, 16f)
        val ys = floatArrayOf(20f, 22f, 24f, 26f)
        val pressures = floatArrayOf(0.4f, 0.5f, 0.6f, 0.5f)
        val tilts = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f)
        val timestamps = longArrayOf(1000L, 1004L, 1008L, 1012L)

        // Warmup 50 dispatches
        for (w in 1..50) {
            val action = when (w % 3) {
                1 -> InputActionKind.DOWN
                2 -> InputActionKind.MOVE
                else -> InputActionKind.UP
            }
            assertTrue(
                container.submitRawMotionBatch(
                    pointerId = 0,
                    action = action,
                    count = 4,
                    xs = xs,
                    ys = ys,
                    pressures = pressures,
                    tilts = tilts,
                    timestampsMs = timestamps
                ).isSuccess
            )
            container.syncEvents()
        }

        container.controller.callbackTracker.reset()
        for (i in 1..500) {
            val action = when (i % 4) {
                1 -> InputActionKind.DOWN
                2, 3 -> InputActionKind.MOVE
                else -> InputActionKind.UP
            }
            val res = container.submitRawMotionBatch(
                pointerId = 0,
                action = action,
                count = 4,
                xs = xs,
                ys = ys,
                pressures = pressures,
                tilts = tilts,
                timestampsMs = timestamps
            )
            assertTrue(res.isSuccess)
            if (i % 10 == 0) {
                container.syncEvents()
            }
        }

        val stats = container.controller.callbackTracker.computeQuantilesMs()
        assertTrue(stats.sampleCount >= 500)
        assertTrue(
            stats.p95Ms <= 1.0,
            "Callback duration p95 (${stats.p95Ms} ms) exceeded 1.0 ms budget"
        )

        writeCallbackProfileEvidence(
            stats.sampleCount,
            stats.minMs,
            stats.p50Ms,
            stats.p95Ms,
            stats.p99Ms,
            stats.maxMs
        )

        // 4. Close first container and cold-reopen from disk using savedBundle
        assertTrue(container.closeAsync().isSuccess)
        awaitCondition {
            container.syncEvents().any { it is AppEvent.Closed } ||
                container.controller.uiState.isClosed
        }

        val reopenedContainer = TruffnotesAppContainer.create(
            config = RuntimeConfig(storageRootPath = rootDir.absolutePath),
            ports = PlatformPorts()
        ).getOrNull()!!

        assertTrue(reopenedContainer.openOrRestoreSession(docId, savedBundle = savedBundle).isSuccess)
        awaitCondition {
            reopenedContainer.syncEvents()
            reopenedContainer.controller.uiState.activeSession != null &&
                reopenedContainer.controller.uiState.viewport?.isFocusMode == true
        }

        val restoredSnap = reopenedContainer.controller.uiState.snapshot!!
        assertEquals(Revision(1L), restoredSnap.version.revision)
        assertTrue(restoredSnap.orderedSceneObjects().any { it.id == ObjectId("stroke-app-1") })
        assertEquals(42.0, reopenedContainer.controller.uiState.viewport?.camera?.translateX ?: 0.0, 1e-6)

        assertTrue(reopenedContainer.closeAsync().isSuccess)
    }

    private fun writeCallbackProfileEvidence(
        sampleCount: Int,
        minMs: Double,
        p50Ms: Double,
        p95Ms: Double,
        p99Ms: Double,
        maxMs: Double
    ) {
        val repoRoot = findRepoRoot() ?: return
        val evidenceDir = File(repoRoot, "docs/evidence/F04")
        evidenceDir.mkdirs()
        val outFile = File(evidenceDir, "callback-profile-raw.json")
        val json = String.format(
            Locale.US,
            """
            {
              "phase_id": "F04",
              "metric": "main_callback_duration_ms",
              "quantile_method": "nearest_rank_inclusive_sorted_1_indexed",
              "target_p95_ms": 1.0,
              "sample_count": %d,
              "min_ms": %.6f,
              "p50_ms": %.6f,
              "p95_ms": %.6f,
              "p99_ms": %.6f,
              "max_ms": %.6f,
              "budget_satisfied": %s
            }
            """.trimIndent() + "\n",
            sampleCount,
            minMs,
            p50Ms,
            p95Ms,
            p99Ms,
            maxMs,
            if (p95Ms <= 1.0) "true" else "false"
        )
        outFile.writeText(json)
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(5L)
        }
        assertTrue(predicate(), "Condition timed out after ${timeoutMs}ms")
    }
}
