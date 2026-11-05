package inkspace.render

import inkspace.core.error.RenderError
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.ResizeGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.model.DocumentState
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SessionKey
import inkspace.core.mutation.AddPage
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.UpdatePayload
import inkspace.core.mutation.applyPlan
import inkspace.core.mutation.validatePlan
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.render.RenderRequest
import inkspace.core.render.Scene
import inkspace.core.render.TileArtifact
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import inkspace.testsupport.FakeGeometryPort
import inkspace.testsupport.FakeResourcePort
import inkspace.testsupport.FakeTextPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class F05CullingCachesAndBudgetsTest {

    @Test
    fun same_scene_pan_does_not_rebuild_geometry_and_culls_offscreen_objects() {
        val renderer = Renderer(
            geometryPort = FakeGeometryPort(),
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        try {
            val docId = DocumentId("doc-pan-cull")
            val state = DocumentState.createInitial(
                documentId = docId,
                initialPage = PageDescriptor(
                    id = PageId("page-1"),
                    spaceId = SpaceId("space-1"),
                    widthPt = 2000.0,
                    heightPt = 2000.0
                )
            )
            val mutations = mutableListOf<Mutation>()
            // 10 strokes inside (20..120, 20..120), 20 strokes far away at (1200..1500, 1200..1500)
            for (i in 1..10) {
                val base = (i * 8).toDouble()
                mutations.add(
                    InsertObject(
                        space = SpaceId("space-1"),
                        layer = LayerId("layer-1"),
                        id = ObjectId("near-$i"),
                        payload = StrokePayload(
                            brushId = "ball",
                            color = 0xFF000000.toInt(),
                            baseWidthPt = 2.0,
                            origin = DocPoint(base, base),
                            samples = listOf(
                                StrokeSamplePoint(0f, 0f, 0.5f),
                                StrokeSamplePoint(10f, 10f, 0.5f)
                            )
                        ),
                        bounds = DocRect(base, base, base + 12.0, base + 12.0)
                    )
                )
            }
            for (i in 1..20) {
                val base = 1200.0 + i * 10.0
                mutations.add(
                    InsertObject(
                        space = SpaceId("space-1"),
                        layer = LayerId("layer-1"),
                        id = ObjectId("far-$i"),
                        payload = StrokePayload(
                            brushId = "ball",
                            color = 0xFF000000.toInt(),
                            baseWidthPt = 2.0,
                            origin = DocPoint(base, base),
                            samples = listOf(
                                StrokeSamplePoint(0f, 0f, 0.5f),
                                StrokeSamplePoint(10f, 10f, 0.5f)
                            )
                        ),
                        bounds = DocRect(base, base, base + 12.0, base + 12.0)
                    )
                )
            }

            val plan = EditPlan(
                base = DocVersion(docId, Revision(0L)),
                mutations = mutations,
                gesture = GestureId("g-setup"),
                label = UndoLabel("setup")
            )
            applyPlan(state, validatePlan(state.snapshot(), plan, Limits()).getOrNull()!!, Limits())

            val snap = state.snapshot()
            // Frame 1: viewport 300x300 at (0, 0) -> only the 10 near strokes are visible, 20 far strokes are culled
            val frame1 = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = snap,
                        camera = CameraTransform(scale = 1.0, translateX = 0.0, translateY = 0.0),
                        viewportWidthPx = 300,
                        viewportHeightPx = 300
                    )
                )
            ).getOrNull()!!.stats

            assertEquals(30, frame1.totalSceneObjects)
            assertEquals(10, frame1.visibleObjectsCount)
            assertEquals(20, frame1.culledObjectsCount)
            assertEquals(10, frame1.geometryRebuildsCount)
            assertEquals(0, frame1.geometryCacheHits)

            // Frame 2: same_scene_pan -> camera translates by (+25, +15), same 10 strokes remain visible
            // Must hit 100% of cached geometry with 0 rebuilds!
            val frame2 = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = snap,
                        camera = CameraTransform(scale = 1.0, translateX = 25.0, translateY = 15.0),
                        viewportWidthPx = 300,
                        viewportHeightPx = 300
                    )
                )
            ).getOrNull()!!.stats

            assertEquals(10, frame2.visibleObjectsCount)
            assertEquals(0, frame2.geometryRebuildsCount, "same_scene_pan must not rebuild any geometry")
            assertEquals(10, frame2.geometryCacheHits)

            // Frame 3: zoom to scale = 2.0 -> different scaleBucket, rebuilds visible strokes for new bucket
            val frame3 = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = snap,
                        camera = CameraTransform(scale = 2.0, translateX = 0.0, translateY = 0.0),
                        viewportWidthPx = 300,
                        viewportHeightPx = 300
                    )
                )
            ).getOrNull()!!.stats

            assertTrue(frame3.scaleBucket != frame2.scaleBucket)
            assertEquals(10, frame3.geometryRebuildsCount)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun changeset_dirty_region_invalidates_only_modified_objects() {
        val renderer = Renderer(
            geometryPort = FakeGeometryPort(),
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort()
        )
        try {
            val docId = DocumentId("doc-dirty-changeset")
            val state = DocumentState.createInitial(docId)
            val mutations = (1..8).map { i ->
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("s-$i"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF000000.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(i * 10.0, i * 10.0),
                        samples = listOf(
                            StrokeSamplePoint(0f, 0f, 0.5f),
                            StrokeSamplePoint(8f, 8f, 0.5f)
                        )
                    ),
                    bounds = DocRect(i * 10.0, i * 10.0, i * 10.0 + 10.0, i * 10.0 + 10.0)
                )
            }
            applyPlan(
                state,
                validatePlan(
                    state.snapshot(),
                    EditPlan(DocVersion(docId, Revision(0L)), emptyList(), mutations, GestureId("g1"), UndoLabel("u1")),
                    Limits()
                ).getOrNull()!!,
                Limits()
            )

            // Initial render populates all 8 strokes in cache
            val f1 = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(snapshot = state.snapshot(), viewportWidthPx = 200, viewportHeightPx = 200, clipToPageBounds = false)
                )
            ).getOrNull()!!.stats
            assertEquals(8, f1.geometryRebuildsCount)

            // Update only s-3
            val updatePlan = EditPlan(
                base = DocVersion(docId, Revision(1L)),
                mutations = listOf(
                    UpdatePayload(
                        id = ObjectId("s-3"),
                        newPayload = StrokePayload(
                            brushId = "ball",
                            color = 0xFFFF0000.toInt(),
                            baseWidthPt = 3.0,
                            origin = DocPoint(30.0, 30.0),
                            samples = listOf(
                                StrokeSamplePoint(0f, 0f, 0.5f),
                                StrokeSamplePoint(12f, 12f, 0.8f)
                            )
                        ),
                        newBounds = DocRect(30.0, 30.0, 44.0, 44.0)
                    )
                ),
                gesture = GestureId("g2"),
                label = UndoLabel("u2")
            )
            val applied = applyPlan(
                state,
                validatePlan(state.snapshot(), updatePlan, Limits()).getOrNull()!!,
                Limits()
            ).getOrNull()!!

            val f2 = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = state.snapshot(),
                        viewportWidthPx = 200,
                        viewportHeightPx = 200,
                        clipToPageBounds = false,
                        changeSet = applied.changeSet
                    )
                )
            ).getOrNull()!!.stats

            assertEquals(1, f2.geometryRebuildsCount, "Only the 1 changed stroke in ChangeSet should rebuild")
            assertEquals(7, f2.geometryCacheHits, "Unchanged 7 strokes must hit cache")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun cpu_tile_and_derived_cache_budgets_evict_lru_and_reject_oversize_and_invalid_surface() {
        val renderer = Renderer(
            geometryPort = FakeGeometryPort(),
            textPort = FakeTextPort(),
            resourcePort = FakeResourcePort(),
            maxDerivedCacheBytes = 512L,
            maxTileCacheBytes = 4096L,
            maxControlledGraphicsBytes = 64 * 1024L
        )
        try {
            val docId = DocumentId("doc-budgets")
            val state = DocumentState.createInitial(docId)
            val mutations = (1..15).map { i ->
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("b-$i"),
                    payload = StrokePayload(
                        brushId = "ball",
                        color = 0xFF000000.toInt(),
                        baseWidthPt = 2.0,
                        origin = DocPoint(i * 5.0, i * 5.0),
                        samples = listOf(
                            StrokeSamplePoint(0f, 0f, 0.5f),
                            StrokeSamplePoint(4f, 4f, 0.5f),
                            StrokeSamplePoint(8f, 8f, 0.5f),
                            StrokeSamplePoint(12f, 12f, 0.5f)
                        )
                    ),
                    bounds = DocRect(i * 5.0, i * 5.0, i * 5.0 + 15.0, i * 5.0 + 15.0)
                )
            }
            applyPlan(
                state,
                validatePlan(
                    state.snapshot(),
                    EditPlan(DocVersion(docId, Revision(0L)), emptyList(), mutations, GestureId("g1"), UndoLabel("u1")),
                    Limits()
                ).getOrNull()!!,
                Limits()
            )

            // Create 4 tiles of 16x16 = 1024 bytes each (total 4096 bytes) + 1 extra tile -> triggers LRU eviction
            val tiles = (1..5).map { idx ->
                TileArtifact(
                    tileKey = "tile-$idx",
                    docBounds = DocRect(idx * 10.0, 0.0, idx * 10.0 + 10.0, 10.0),
                    widthPx = 16,
                    heightPx = 16,
                    scaleBucket = 0,
                    argbPixels = IntArray(16 * 16) { 0xFFCCCCCC.toInt() }
                )
            }

            val stats = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = state.snapshot(),
                        viewportWidthPx = 100,
                        viewportHeightPx = 100,
                        clipToPageBounds = false,
                        tiles = tiles
                    )
                )
            ).getOrNull()!!.stats

            assertTrue(stats.derivedCacheBytes <= 512L, "Derived cache (${stats.derivedCacheBytes}) must stay <= 512B")
            assertTrue(stats.tileCacheBytes <= 4096L, "Tile cache (${stats.tileCacheBytes}) must stay <= 4096B")

            // Max 2 pinned snapshots per session
            val session = SessionKey(docId, SessionEpoch(1L))
            val snap = state.snapshot()
            assertTrue(renderer.pinSnapshot(session, snap))
            assertTrue(renderer.pinSnapshot(session, snap))
            assertEquals(2, renderer.pinnedSnapshotsCount(session))
            // 3rd pin replaces oldest so count never exceeds 2
            assertTrue(renderer.pinSnapshot(session, snap))
            assertEquals(2, renderer.pinnedSnapshotsCount(session))

            // Oversize target exceeding maxControlledGraphicsBytes (64 KiB) -> typed MemoryBudgetExceeded
            val oversizeRes = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = snap,
                        viewportWidthPx = 500,
                        viewportHeightPx = 500, // 500 * 500 * 4 = 1,000,000 bytes > 64 KiB
                        clipToPageBounds = false
                    ),
                    capturePixels = true
                )
            )
            assertTrue(oversizeRes.isFailure)
            assertTrue(oversizeRes.errorOrNull() is RenderError.MemoryBudgetExceeded)

            // Acknowledged SurfaceEpoch release -> subsequent render to released epoch returns InvalidSurface
            val releasedEpoch = SurfaceEpoch(7L)
            assertTrue(renderer.releaseSurface(releasedEpoch))
            assertTrue(renderer.isSurfaceReleased(releasedEpoch))

            val staleSurfaceRes = renderer.renderOnExecutor(
                RenderRequest.Draw(
                    scene = Scene(
                        snapshot = snap,
                        viewportWidthPx = 64,
                        viewportHeightPx = 64,
                        surfaceEpoch = releasedEpoch,
                        resizeGeneration = ResizeGeneration(1L)
                    )
                )
            )
            assertTrue(staleSurfaceRes.isFailure)
            assertTrue(staleSurfaceRes.errorOrNull() is RenderError.InvalidSurface)

            // Calling Renderer.render directly on non-render thread returns RenderError.WrongThread
            val wrongThreadRes = renderer.render(
                RenderRequest.Draw(
                    scene = Scene(snapshot = snap, viewportWidthPx = 64, viewportHeightPx = 64)
                )
            )
            assertTrue(wrongThreadRes.isFailure)
            assertTrue(wrongThreadRes.errorOrNull() is RenderError.WrongThread)
        } finally {
            renderer.close()
        }
    }
}
