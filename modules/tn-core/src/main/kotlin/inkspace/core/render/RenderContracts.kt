package inkspace.core.render

import inkspace.core.id.AssetId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.ResizeGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.SurfaceEpoch
import inkspace.core.model.AffineTransform2D
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.ObjectStyle
import inkspace.core.mutation.ChangeSet
import inkspace.core.payload.StrokePayload
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

/**
 * Immutable raster tile artifact (e.g., decoded PDF/image tile or cached raster region).
 * Never exposes its backing ARGB IntArray directly.
 */
class TileArtifact(
    val tileKey: String,
    val assetId: AssetId? = null,
    val pageIndex: Int = 0,
    val docBounds: DocRect,
    val widthPx: Int,
    val heightPx: Int,
    val scaleBucket: Int = 0,
    argbPixels: IntArray
) {
    private val pixels: IntArray

    init {
        require(widthPx > 0 && heightPx > 0) { "Tile dimensions must be positive: ($widthPx, $heightPx)" }
        require(argbPixels.size >= widthPx * heightPx) {
            "Tile pixel array size ${argbPixels.size} smaller than ${widthPx * heightPx}"
        }
        pixels = argbPixels.copyOf(widthPx * heightPx)
    }

    val byteSize: Long
        get() = widthPx.toLong() * heightPx.toLong() * 4L

    fun pixelAt(x: Int, y: Int): Int {
        if (x !in 0 until widthPx || y !in 0 until heightPx) return 0
        return pixels[y * widthPx + x]
    }

    fun copyPixels(): IntArray = pixels.copyOf()
}

/**
 * Transient active draft stroke rendered through the exact same GeometryPort + CanvasReplay pipeline
 * as committed strokes so draft -> committed produces identical geometry.
 */
data class DraftStrokeSceneItem(
    val draftId: ObjectId,
    val spaceId: SpaceId,
    val layerId: LayerId,
    val bounds: DocRect,
    val payload: StrokePayload,
    val transform: AffineTransform2D = AffineTransform2D.IDENTITY,
    val style: ObjectStyle = ObjectStyle()
)

/**
 * Ephemeral overlay items (selection rectangle, hover cursor, transient laser trail)
 * that never enter the persisted DocumentSnapshot.
 */
sealed interface EphemeralOverlayItem {
    data class SelectionBounds(
        val rect: DocRect,
        val colorArgb: Int = 0xFF2563EB.toInt(),
        val strokeWidthPt: Double = 1.5
    ) : EphemeralOverlayItem

    data class HoverCursor(
        val point: DocPoint,
        val radiusPt: Double = 4.0,
        val colorArgb: Int = 0x802563EB.toInt()
    ) : EphemeralOverlayItem

    data class LaserTrail(
        val origin: DocPoint,
        val relativePoints: List<DocPoint>,
        val colorArgb: Int = 0xFFFF1744.toInt(),
        val widthPt: Double = 3.0
    ) : EphemeralOverlayItem {
        val frozenPoints: List<DocPoint> = java.util.List.copyOf(relativePoints)
    }

    data class RulerOverlay(
        val state: inkspace.core.objects.RulerOverlayState
    ) : EphemeralOverlayItem

    data class ProtractorOverlay(
        val state: inkspace.core.objects.ProtractorOverlayState
    ) : EphemeralOverlayItem

    data class AlignmentGuidesOverlay(
        val guides: List<inkspace.core.objects.AlignmentGuideLine>
    ) : EphemeralOverlayItem {
        val frozenGuides: List<inkspace.core.objects.AlignmentGuideLine> = java.util.List.copyOf(guides)
    }
}

/**
 * Complete immutable scene description consumed by `Renderer.render(...)`.
 * Combines thethree architectural display layers:
 * 1) Stable committed `DocumentSnapshot`
 * 2) Transient `draftStrokes`
 * 3) Ephemeral `ephemeralOverlays`
 */
data class Scene(
    val snapshot: DocumentSnapshot,
    val activeSpaceId: SpaceId = SpaceId("space-1"),
    val camera: CameraTransform = CameraTransform(),
    val viewportWidthPx: Int = 1080,
    val viewportHeightPx: Int = 1920,
    val surfaceEpoch: SurfaceEpoch = SurfaceEpoch(1L),
    val resizeGeneration: ResizeGeneration = ResizeGeneration(1L),
    val backgroundColorArgb: Int = 0xFFFFFFFF.toInt(),
    val clipToPageBounds: Boolean = true,
    val draftStrokes: List<DraftStrokeSceneItem> = emptyList(),
    val ephemeralOverlays: List<EphemeralOverlayItem> = emptyList(),
    val changeSet: ChangeSet? = null,
    val revealedTapeIds: Set<ObjectId> = emptySet(),
    val tiles: List<TileArtifact> = emptyList()
) {
    val frozenDraftStrokes: List<DraftStrokeSceneItem> = java.util.List.copyOf(draftStrokes)
    val frozenEphemeralOverlays: List<EphemeralOverlayItem> = java.util.List.copyOf(ephemeralOverlays)
    val frozenTiles: List<TileArtifact> = java.util.List.copyOf(tiles)
}

@Serializable
enum class RenderTargetKind {
    SURFACE,
    SNAPSHOT_IMAGE,
    PDF_OVERLAY
}

sealed interface RenderRequest {
    val scene: Scene

    data class Draw(
        override val scene: Scene,
        val capturePixels: Boolean = false
    ) : RenderRequest

    data class Snapshot(
        override val scene: Scene,
        val regionDoc: DocRect,
        val scale: Double = 1.0
    ) : RenderRequest

    data class PdfOverlay(
        override val scene: Scene,
        val pageId: PageId,
        val pageBoundsDoc: DocRect
    ) : RenderRequest
}

@Serializable
data class FrameStats(
    val revision: Revision,
    val surfaceEpoch: SurfaceEpoch,
    val resizeGeneration: ResizeGeneration,
    val targetKind: RenderTargetKind,
    val totalSceneObjects: Int,
    val visiblePagesCount: Int,
    val visibleObjectsCount: Int,
    val culledObjectsCount: Int,
    val drawnStrokesCount: Int,
    val drawnShapesCount: Int,
    val drawnTextsCount: Int,
    val drawnTilesCount: Int,
    val draftStrokesCount: Int,
    val ephemeralOverlaysCount: Int,
    val geometryCacheHits: Int,
    val geometryRebuildsCount: Int,
    val textCacheHits: Int,
    val textRebuildsCount: Int,
    val tileCacheHits: Int,
    val scaleBucket: Int,
    val frameDurationNanos: Long,
    val derivedCacheBytes: Long,
    val tileCacheBytes: Long,
    val controlledGraphicsBytes: Long,
    val sdkReportedGraphicsBytes: Long,
    val pinnedSnapshotsCount: Int
) {
    val frameDurationMs: Double
        get() = frameDurationNanos.toDouble() / 1_000_000.0
}

sealed interface RenderReply {
    val stats: FrameStats

    class FrameDrawn(
        override val stats: FrameStats,
        val widthPx: Int,
        val heightPx: Int,
        capturedArgbPixels: IntArray? = null
    ) : RenderReply {
        private val pixels: IntArray? = capturedArgbPixels?.copyOf(widthPx * heightPx)

        fun hasCapturedPixels(): Boolean = pixels != null

        fun copyCapturedPixels(): IntArray? = pixels?.copyOf()

        fun pixelAt(x: Int, y: Int): Int {
            val buf = pixels ?: return 0
            if (x !in 0 until widthPx || y !in 0 until heightPx) return 0
            return buf[y * widthPx + x]
        }
    }

    class SnapshotRendered(
        override val stats: FrameStats,
        val widthPx: Int,
        val heightPx: Int,
        argbPixels: IntArray
    ) : RenderReply {
        private val pixels: IntArray = argbPixels.copyOf(widthPx * heightPx)

        fun copyPixels(): IntArray = pixels.copyOf()

        fun pixelAt(x: Int, y: Int): Int {
            if (x !in 0 until widthPx || y !in 0 until heightPx) return 0
            return pixels[y * widthPx + x]
        }
    }

    class PdfOverlayRendered(
        override val stats: FrameStats,
        val pageId: PageId,
        val widthPt: Int,
        val heightPt: Int,
        pdfBytes: ByteArray,
        rasterPreviewArgb: IntArray
    ) : RenderReply {
        private val encodedPdf: ByteArray = pdfBytes.copyOf()
        private val previewPixels: IntArray = rasterPreviewArgb.copyOf(widthPt * heightPt)

        fun pdfByteSize(): Int = encodedPdf.size

        fun copyPdfBytes(): ByteArray = encodedPdf.copyOf()

        fun copyPreviewPixels(): IntArray = previewPixels.copyOf()

        fun previewPixelAt(x: Int, y: Int): Int {
            if (x !in 0 until widthPt || y !in 0 until heightPt) return 0
            return previewPixels[y * widthPt + x]
        }
    }
}
