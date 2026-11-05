package inkspace.render

import inkspace.core.codec.CoreCodec
import inkspace.core.error.RenderError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.DocumentState
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.mutation.AddPage
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.outcome.Outcome
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.FrameStats
import inkspace.core.render.RenderRequest
import inkspace.core.render.Scene
import inkspace.core.runtime.MemoryCategory
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.testsupport.FakeGeometryPort
import inkspace.testsupport.FakeResourcePort
import inkspace.testsupport.FakeTextPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class F05WorkloadBenchmarksTest {

    private val defaultSeed = 20261105L
    private val spaceId = SpaceId("space-1")
    private val layerId = LayerId("layer-1")

    private fun buildStrokeTemplates(
        seed: Long,
        templateCount: Int = 32,
        samplesPerStroke: Int = 64
    ): List<Pair<StrokePayload, Long>> {
        val rng = Random(seed)
        return (0 until templateCount).map { idx ->
            val samples = java.util.List.copyOf(
                (0 until samplesPerStroke).map { s ->
                    val t = s.toDouble() / (samplesPerStroke - 1).toDouble()
                    val x = (t * 40.0 + sin(t * 6.2831853 + idx) * 2.0).toFloat()
                    val y = (cos(t * 3.1415926 + idx * 0.5) * 4.0 + 5.0).toFloat()
                    StrokeSamplePoint(
                        relX = x,
                        relY = y,
                        pressure = 0.4f + (rng.nextFloat() * 0.4f)
                    )
                }
            )
            val payload = StrokePayload(
                brushId = "ball",
                smoothingVersion = 1,
                color = 0xFF1A2B4C.toInt(),
                baseWidthPt = 2.0,
                origin = DocPoint(0.0, 0.0),
                samples = samples
            )
            payload to CoreCodec.computePayloadDigest(payload)
        }
    }

    private fun buildSyntheticWorkloadSnapshot(
        docId: String,
        pageCount: Int,
        strokesPerPage: Int,
        samplesPerStroke: Int
    ): DocumentSnapshot {
        val pageWidthPt = 600.0
        val pageHeightPt = 800.0
        val docIdentifier = DocumentId(docId)
        val state = DocumentState.createInitial(
            documentId = docIdentifier,
            initialSpace = spaceId,
            initialPage = PageDescriptor(PageId("page-0"), spaceId, pageWidthPt, pageHeightPt),
            initialLayer = LayerDescriptor(layerId, PageId("page-0"), "Main Layer")
        )

        if (pageCount > 1) {
            val addPages = (1 until pageCount).map { p ->
                val pId = PageId("page-$p")
                AddPage(
                    page = PageDescriptor(pId, spaceId, pageWidthPt, pageHeightPt)
                )
            }
            val limits = Limits(maxObjectsPerSpace = pageCount * strokesPerPage + 1000)
            val validated = validatePlan(
                state.snapshot(),
                EditPlan(
                    base = DocVersion(docIdentifier, Revision(0L)),
                    mutations = addPages,
                    gesture = GestureId("g-pages"),
                    label = UndoLabel("add-pages")
                ),
                limits
            ).getOrNull()!!
            applyPlan(state, validated, limits)
        }

        val templates = buildStrokeTemplates(
            seed = defaultSeed + pageCount,
            templateCount = 32,
            samplesPerStroke = samplesPerStroke
        )
        val cols = 10
        var globalObjIdx = 0L

        for (p in 0 until pageCount) {
            val pageTopY = p * pageHeightPt
            for (s in 0 until strokesPerPage) {
                val col = s % cols
                val row = s / cols
                val offsetX = 20.0 + col * 55.0
                val offsetY = pageTopY + 20.0 + row * 35.0
                val bounds = DocRect(offsetX, offsetY, offsetX + 44.0, offsetY + 14.0)
                val (payload, digest) = templates[(globalObjIdx % templates.size).toInt()]
                state.putResidentObjectDirect(
                    SceneObject(
                        id = ObjectId("obj-$globalObjIdx"),
                        space = spaceId,
                        layer = layerId,
                        bounds = bounds,
                        payload = payload,
                        transform = AffineTransform2D(m02 = offsetX, m12 = offsetY),
                        style = ObjectStyle(opacity = 1.0f, isVisible = true, zOrder = globalObjIdx),
                        isLocked = false,
                        version = 1L,
                        payloadDigest = digest
                    )
                )
                globalObjIdx += 1L
            }
        }

        return state.snapshot()
    }

    private fun percentile(sortedValues: List<Double>, p: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val idx = ceil((p / 100.0) * sortedValues.size).toInt().coerceIn(1, sortedValues.size) - 1
        return sortedValues[idx]
    }

    @Test
    fun w1_and_w2_synthetic_scene_benchmarks_meet_p95_frame_time_culling_and_memory_caps() {
        val renderer = Renderer(
            geometryPort = FakeGeometryPort(),
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        try {
            // 1. W1 benchmark: 100 pages x 100 strokes x 64 samples = 10,000 strokes
            val w1Snapshot = buildSyntheticWorkloadSnapshot(
                docId = "doc-w1",
                pageCount = 100,
                strokesPerPage = 100,
                samplesPerStroke = 64
            )
            assertEquals(10_000, w1Snapshot.totalObjectCount)
            assertEquals(100, w1Snapshot.pages.size)

            val w1BaseScene = Scene(
                snapshot = w1Snapshot,
                activeSpaceId = spaceId,
                camera = CameraTransform(scale = 1.0, translateX = 0.0, translateY = 0.0),
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
                surfaceEpoch = SurfaceEpoch(1L)
            )

            // Warmup frame on W1 (populates visible geometry in scaleBucket 0)
            val w1Warmup = renderer.renderOnExecutor(
                RenderRequest.Draw(scene = w1BaseScene, capturePixels = false)
            )
            assertTrue(w1Warmup is Outcome.Success)

            // Measure 40 frames on W1 during same_scene_pan within the same visible window
            val w1Stats = ArrayList<FrameStats>(40)
            var w1PanRebuilds = 0
            for (frame in 0 until 40) {
                val dx = (frame % 5) * 1.5
                val frameScene = w1BaseScene.copy(
                    camera = CameraTransform(scale = 1.0, translateX = -dx, translateY = 0.0)
                )
                val outcome = renderer.renderOnExecutor(
                    RenderRequest.Draw(scene = frameScene, capturePixels = false)
                )
                assertTrue(outcome is Outcome.Success)
                val st = outcome.getOrNull()!!.stats
                w1Stats.add(st)
                w1PanRebuilds += st.geometryRebuildsCount
            }
            assertEquals(0, w1PanRebuilds, "W1 same_scene_pan within visible window must trigger 0 geometry rebuilds")

            val w1MsSorted = w1Stats.map { it.frameDurationMs }.sorted()
            val w1P50Ms = percentile(w1MsSorted, 50.0)
            val w1P95Ms = percentile(w1MsSorted, 95.0)
            val w1P99Ms = percentile(w1MsSorted, 99.0)
            val w1MaxVisible = w1Stats.maxOf { it.visibleObjectsCount }

            assertTrue(w1P95Ms <= 16.67, "W1 p95 render duration ($w1P95Ms ms) must be <= 16.67 ms")

            // 2. W2 benchmark: 500 pages, 100,000 strokes x 64 samples, <= 1,000 visible in viewport
            val w2Snapshot = buildSyntheticWorkloadSnapshot(
                docId = "doc-w2",
                pageCount = 500,
                strokesPerPage = 200,
                samplesPerStroke = 64
            )
            assertEquals(100_000, w2Snapshot.totalObjectCount)
            assertEquals(500, w2Snapshot.pages.size)

            val w2BaseScene = Scene(
                snapshot = w2Snapshot,
                activeSpaceId = spaceId,
                camera = CameraTransform(scale = 1.0, translateX = 0.0, translateY = -8000.0),
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
                surfaceEpoch = SurfaceEpoch(1L)
            )

            // Warmup frame on W2 at page 10..12
            val w2Warmup = renderer.renderOnExecutor(
                RenderRequest.Draw(scene = w2BaseScene, capturePixels = false)
            )
            assertTrue(w2Warmup is Outcome.Success)
            val w2WarmupStats = w2Warmup.getOrNull()!!.stats
            assertTrue(
                w2WarmupStats.visibleObjectsCount in 1..1_000,
                "W2 visible objects (${w2WarmupStats.visibleObjectsCount}) must be <= 1,000"
            )
            assertTrue(
                w2WarmupStats.culledObjectsCount >= 99_000,
                "W2 spatial culling must cull >= 99,000 out-of-frustum objects"
            )

            // Measure 40 frames on W2 during same-window pan
            val w2Stats = ArrayList<FrameStats>(40)
            var w2PanRebuilds = 0
            for (frame in 0 until 40) {
                val dx = (frame % 5) * 1.0
                val frameScene = w2BaseScene.copy(
                    camera = CameraTransform(scale = 1.0, translateX = -dx, translateY = -8000.0)
                )
                val outcome = renderer.renderOnExecutor(
                    RenderRequest.Draw(scene = frameScene, capturePixels = false)
                )
                assertTrue(outcome is Outcome.Success)
                val st = outcome.getOrNull()!!.stats
                w2Stats.add(st)
                w2PanRebuilds += st.geometryRebuildsCount
            }
            assertEquals(0, w2PanRebuilds, "W2 same_scene_pan must trigger 0 geometry rebuilds")

            val w2MsSorted = w2Stats.map { it.frameDurationMs }.sorted()
            val w2P50Ms = percentile(w2MsSorted, 50.0)
            val w2P95Ms = percentile(w2MsSorted, 95.0)
            val w2P99Ms = percentile(w2MsSorted, 99.0)
            val w2MaxVisible = w2Stats.maxOf { it.visibleObjectsCount }
            val w2MinCulled = w2Stats.minOf { it.culledObjectsCount }

            assertTrue(w2P95Ms <= 16.67, "W2 p95 render duration ($w2P95Ms ms) must be <= 16.67 ms")
            assertTrue(w2MaxVisible <= 1_000, "W2 max visible objects ($w2MaxVisible) must be <= 1,000")

            // 3. Surface destroy/recreate 20 cycles
            var peakControlledGraphicsBytes = 0L
            val destroyRecreateCycles = 20

            for (cycle in 1..destroyRecreateCycles) {
                val prevEpoch = SurfaceEpoch(cycle.toLong())
                val nextEpoch = SurfaceEpoch((cycle + 1).toLong())
                assertTrue(renderer.releaseSurface(prevEpoch))
                assertTrue(renderer.isSurfaceReleased(prevEpoch))

                // Old epoch must be rejected with RenderError.InvalidSurface
                val staleAttempt = renderer.renderOnExecutor(
                    RenderRequest.Draw(
                        scene = w1BaseScene.copy(surfaceEpoch = prevEpoch),
                        capturePixels = true
                    )
                )
                assertTrue(staleAttempt.isFailure)
                assertTrue(staleAttempt.errorOrNull() is RenderError.InvalidSurface)

                // New epoch succeeds
                val activeAttempt = renderer.renderOnExecutor(
                    RenderRequest.Draw(
                        scene = w1BaseScene.copy(
                            surfaceEpoch = nextEpoch,
                            viewportWidthPx = 512,
                            viewportHeightPx = 512
                        ),
                        capturePixels = true
                    )
                )
                assertTrue(activeAttempt.isSuccess)
                val st = activeAttempt.getOrNull()!!.stats
                if (st.controlledGraphicsBytes > peakControlledGraphicsBytes) {
                    peakControlledGraphicsBytes = st.controlledGraphicsBytes
                }
            }

            val finalDerivedBytes = renderer.derivedCache.currentUsedBytes()
            val finalTileBytes = renderer.tileCache.currentUsedBytes()
            val finalControlledBytes = renderer.surfaceManager.controlledGraphicsBytes()
            val finalSdkReportedBytes = renderer.surfaceManager.sdkReportedGraphicsBytes()

            assertTrue(
                finalDerivedBytes <= MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL.defaultCapBytes,
                "Derived cache bytes ($finalDerivedBytes) must stay <= 32 MiB"
            )
            assertTrue(
                finalTileBytes <= MemoryCategory.CPU_RASTER_TILES.defaultCapBytes,
                "Tile cache bytes ($finalTileBytes) must stay <= 32 MiB"
            )
            assertTrue(
                peakControlledGraphicsBytes <= MemoryCategory.CONTROLLED_GRAPHICS_TARGETS.defaultCapBytes,
                "Peak controlled graphics bytes ($peakControlledGraphicsBytes) must stay <= 96 MiB"
            )

            writeRawBenchmarkEvidence(
                w1SampleCount = w1Stats.size,
                w1P50Ms = w1P50Ms,
                w1P95Ms = w1P95Ms,
                w1P99Ms = w1P99Ms,
                w1VisibleStrokes = w1MaxVisible,
                w1CulledObjects = w1Stats.first().culledObjectsCount,
                w1PanRebuilds = w1PanRebuilds,
                w2SampleCount = w2Stats.size,
                w2P50Ms = w2P50Ms,
                w2P95Ms = w2P95Ms,
                w2P99Ms = w2P99Ms,
                w2VisibleStrokes = w2MaxVisible,
                w2CulledObjects = w2MinCulled,
                w2PanRebuilds = w2PanRebuilds,
                destroyRecreateCycles = destroyRecreateCycles,
                derivedCacheBytes = finalDerivedBytes,
                tileCacheBytes = finalTileBytes,
                controlledGraphicsBytes = finalControlledBytes,
                peakControlledGraphicsBytes = peakControlledGraphicsBytes,
                sdkReportedGraphicsBytes = finalSdkReportedBytes
            )
        } finally {
            renderer.close()
        }
    }

    private fun writeRawBenchmarkEvidence(
        w1SampleCount: Int,
        w1P50Ms: Double,
        w1P95Ms: Double,
        w1P99Ms: Double,
        w1VisibleStrokes: Int,
        w1CulledObjects: Int,
        w1PanRebuilds: Int,
        w2SampleCount: Int,
        w2P50Ms: Double,
        w2P95Ms: Double,
        w2P99Ms: Double,
        w2VisibleStrokes: Int,
        w2CulledObjects: Int,
        w2PanRebuilds: Int,
        destroyRecreateCycles: Int,
        derivedCacheBytes: Long,
        tileCacheBytes: Long,
        controlledGraphicsBytes: Long,
        peakControlledGraphicsBytes: Long,
        sdkReportedGraphicsBytes: Long
    ) {
        val rootDir = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: File(".")
        val outFile = File(rootDir, "docs/evidence/F05/w1-w2-frame-stats-raw.json")
        outFile.parentFile?.mkdirs()

        val json = String.format(
            Locale.US,
            """
            {
              "phase_id": "F05",
              "seed": %d,
              "target_frame_budget_ms": 16.67,
              "workloads": {
                "W1": {
                  "pages": 100,
                  "strokes": 10000,
                  "samples_per_stroke": 64,
                  "total_samples": 640000,
                  "measured_frames": %d,
                  "p50_ms": %.4f,
                  "p95_ms": %.4f,
                  "p99_ms": %.4f,
                  "visible_strokes_in_viewport": %d,
                  "culled_objects": %d,
                  "same_scene_pan_geometry_rebuilds": %d
                },
                "W2": {
                  "pages": 500,
                  "strokes": 100000,
                  "samples_per_stroke": 64,
                  "total_samples": 6400000,
                  "max_visible_objects_cap": 1000,
                  "measured_frames": %d,
                  "p50_ms": %.4f,
                  "p95_ms": %.4f,
                  "p99_ms": %.4f,
                  "visible_strokes_in_viewport": %d,
                  "culled_objects": %d,
                  "same_scene_pan_geometry_rebuilds": %d
                }
              },
              "surface_lifecycle": {
                "destroy_recreate_cycles": %d,
                "stale_epoch_rejected_with_invalid_surface": true
              },
              "memory_accounting": {
                "derived_cache_bytes": %d,
                "derived_cache_cap_bytes": %d,
                "tile_cache_bytes": %d,
                "tile_cache_cap_bytes": %d,
                "controlled_graphics_bytes": %d,
                "peak_controlled_graphics_bytes": %d,
                "controlled_graphics_cap_bytes": %d,
                "sdk_reported_graphics_bytes": %d
              }
            }
            """.trimIndent() + "\n",
            defaultSeed,
            w1SampleCount,
            w1P50Ms,
            w1P95Ms,
            w1P99Ms,
            w1VisibleStrokes,
            w1CulledObjects,
            w1PanRebuilds,
            w2SampleCount,
            w2P50Ms,
            w2P95Ms,
            w2P99Ms,
            w2VisibleStrokes,
            w2CulledObjects,
            w2PanRebuilds,
            destroyRecreateCycles,
            derivedCacheBytes,
            MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL.defaultCapBytes,
            tileCacheBytes,
            MemoryCategory.CPU_RASTER_TILES.defaultCapBytes,
            controlledGraphicsBytes,
            peakControlledGraphicsBytes,
            MemoryCategory.CONTROLLED_GRAPHICS_TARGETS.defaultCapBytes,
            sdkReportedGraphicsBytes
        )
        outFile.writeText(json)
    }
}
