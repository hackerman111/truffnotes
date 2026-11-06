package inkspace.render

import inkspace.core.error.RenderError
import inkspace.core.id.ObjectId
import inkspace.core.mutation.ChangeSet
import inkspace.core.outcome.Outcome
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.TextPayload
import inkspace.core.ports.GeometryPort
import inkspace.core.ports.GeometryRequest
import inkspace.core.ports.PathArtifact
import inkspace.core.ports.TextLayout
import inkspace.core.ports.TextLayoutRequest
import inkspace.core.ports.TextPort
import inkspace.core.runtime.MemoryCategory
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.roundToInt

data class GeometryCacheKey(
    val objectId: ObjectId,
    val contentVersion: Long,
    val payloadDigest: Long,
    val brushId: String,
    val smoothingVersion: Int,
    val scaleBucket: Int
)

class CachedGeometryEntry(
    val key: GeometryCacheKey,
    val artifact: PathArtifact,
    val originX: Double,
    val originY: Double,
    val relXs: FloatArray,
    val relYs: FloatArray,
    val sizeBytes: Long
)

data class TextCacheKey(
    val objectId: ObjectId,
    val contentVersion: Long,
    val payloadDigest: Long,
    val fontSizeTenthsPt: Int,
    val maxWidthTenthsPt: Int,
    val isRtl: Boolean
)

class CachedTextEntry(
    val key: TextCacheKey,
    val layout: TextLayout,
    val sizeBytes: Long
)

/**
 * Bounded LRU cache for derived vector paths and text layouts (`DERIVED_PATHS_LAYOUT_SPATIAL`, default 32 MiB).
 * Invalidates only affected entries when a `ChangeSet` is applied, and never rebuilds geometry on camera pan.
 */
class DerivedArtifactCache(
    val maxBytes: Long = MemoryCategory.DERIVED_PATHS_LAYOUT_SPATIAL.defaultCapBytes
) {
    private val geometryEntries = LinkedHashMap<GeometryCacheKey, CachedGeometryEntry>(128, 0.75f, true)
    private val textEntries = LinkedHashMap<TextCacheKey, CachedTextEntry>(64, 0.75f, true)
    private var currentBytes: Long = 0L
    private var lastDirtyRegion: DocRect? = null

    @Synchronized
    fun currentUsedBytes(): Long = currentBytes

    @Synchronized
    fun lastDirtyBounds(): DocRect? = lastDirtyRegion

    @Synchronized
    fun applyChangeSet(changeSet: ChangeSet) {
        if (changeSet.changedIds.isNotEmpty()) {
            val geoIter = geometryEntries.entries.iterator()
            while (geoIter.hasNext()) {
                val entry = geoIter.next().value
                if (entry.key.objectId in changeSet.changedIds) {
                    currentBytes = (currentBytes - entry.sizeBytes).coerceAtLeast(0L)
                    geoIter.remove()
                }
            }
            val txtIter = textEntries.entries.iterator()
            while (txtIter.hasNext()) {
                val entry = txtIter.next().value
                if (entry.key.objectId in changeSet.changedIds) {
                    currentBytes = (currentBytes - entry.sizeBytes).coerceAtLeast(0L)
                    txtIter.remove()
                }
            }
        }
        val oldB = changeSet.oldBounds
        val newB = changeSet.newBounds
        lastDirtyRegion = when {
            oldB != null && newB != null -> oldB.union(newB)
            oldB != null -> oldB
            else -> newB
        }
    }

    @Synchronized
    fun getOrPutGeometry(
        objectId: ObjectId,
        contentVersion: Long,
        payloadDigest: Long,
        payload: StrokePayload,
        scaleBucket: Int,
        geometryPort: GeometryPort,
        onHit: () -> Unit,
        onRebuild: () -> Unit
    ): Outcome<CachedGeometryEntry, RenderError> {
        val key = GeometryCacheKey(
            objectId = objectId,
            contentVersion = contentVersion,
            payloadDigest = payloadDigest,
            brushId = payload.brushId,
            smoothingVersion = payload.smoothingVersion,
            scaleBucket = scaleBucket
        )
        val existing = geometryEntries[key]
        if (existing != null) {
            onHit()
            return Outcome.Success(existing)
        }

        onRebuild()
        val sampleDocs = payload.samples.map { DocPoint(it.relX.toDouble(), it.relY.toDouble()) }
        val req = GeometryRequest(
            strokeId = objectId,
            origin = payload.origin,
            samples = sampleDocs,
            baseWidthPt = payload.baseWidthPt,
            brushId = payload.brushId,
            smoothingVersion = payload.smoothingVersion,
            scaleBucket = scaleBucket
        )
        val pathOutcome = geometryPort.paths(req)
        val artifact = when (pathOutcome) {
            is Outcome.Failure -> {
                return Outcome.Failure(
                    RenderError.GeometryFailure(
                        strokeId = objectId.value,
                        reason = pathOutcome.error.toString()
                    )
                )
            }
            is Outcome.Success -> pathOutcome.value
        }

        val pts = artifact.relativePoints
        val count = if (pts.isNotEmpty()) pts.size else payload.samples.size
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        if (pts.isNotEmpty()) {
            for (i in 0 until count) {
                xs[i] = pts[i].x.toFloat()
                ys[i] = pts[i].y.toFloat()
            }
        } else {
            for (i in 0 until count) {
                xs[i] = payload.samples[i].relX
                ys[i] = payload.samples[i].relY
            }
        }

        val entryBytes = (64L + count.toLong() * 8L).coerceAtLeast(64L)
        val created = CachedGeometryEntry(
            key = key,
            artifact = artifact,
            originX = payload.origin.x,
            originY = payload.origin.y,
            relXs = xs,
            relYs = ys,
            sizeBytes = entryBytes
        )

        if (entryBytes <= maxBytes) {
            evictUntilFits(entryBytes)
            geometryEntries[key] = created
            currentBytes += entryBytes
        }
        return Outcome.Success(created)
    }

    @Synchronized
    fun getOrPutTextLayout(
        objectId: ObjectId,
        contentVersion: Long,
        payloadDigest: Long,
        payload: TextPayload,
        maxWidthPt: Double,
        textPort: TextPort,
        onHit: () -> Unit,
        onRebuild: () -> Unit
    ): Outcome<CachedTextEntry, RenderError> {
        val key = TextCacheKey(
            objectId = objectId,
            contentVersion = contentVersion,
            payloadDigest = payloadDigest,
            fontSizeTenthsPt = (payload.fontSizePt * 10.0).roundToInt(),
            maxWidthTenthsPt = (maxWidthPt.coerceAtLeast(1.0) * 10.0).roundToInt(),
            isRtl = payload.isRtl
        )
        val existing = textEntries[key]
        if (existing != null) {
            onHit()
            return Outcome.Success(existing)
        }

        onRebuild()
        val req = TextLayoutRequest(
            text = payload.text,
            fontSizePt = payload.fontSizePt,
            maxWidthPt = maxWidthPt.coerceAtLeast(1.0),
            isRtl = payload.isRtl,
            payload = payload
        )
        val layoutOutcome = textPort.layout(req)
        val layout = when (layoutOutcome) {
            is Outcome.Failure -> {
                return Outcome.Failure(
                    RenderError.TextLayoutFailure(
                        objectId = objectId.value,
                        reason = layoutOutcome.error.toString()
                    )
                )
            }
            is Outcome.Success -> layoutOutcome.value
        }

        val entryBytes = layout.estimatedBytes.coerceAtLeast(64L)
        val created = CachedTextEntry(
            key = key,
            layout = layout,
            sizeBytes = entryBytes
        )
        if (entryBytes <= maxBytes) {
            evictUntilFits(entryBytes)
            textEntries[key] = created
            currentBytes += entryBytes
        }
        return Outcome.Success(created)
    }

    @Synchronized
    fun trimToBytes(targetBytes: Long) {
        val cap = targetBytes.coerceAtLeast(0L)
        while (currentBytes > cap && (geometryEntries.isNotEmpty() || textEntries.isNotEmpty())) {
            evictOneOldest()
        }
    }

    @Synchronized
    fun clear() {
        geometryEntries.clear()
        textEntries.clear()
        currentBytes = 0L
        lastDirtyRegion = null
    }

    private fun evictUntilFits(neededBytes: Long) {
        while (currentBytes + neededBytes > maxBytes && (geometryEntries.isNotEmpty() || textEntries.isNotEmpty())) {
            evictOneOldest()
        }
    }

    private fun evictOneOldest() {
        if (geometryEntries.isNotEmpty()) {
            val firstKey = geometryEntries.keys.iterator().next()
            val removed = geometryEntries.remove(firstKey)
            if (removed != null) {
                currentBytes = (currentBytes - removed.sizeBytes).coerceAtLeast(0L)
                return
            }
        }
        if (textEntries.isNotEmpty()) {
            val firstKey = textEntries.keys.iterator().next()
            val removed = textEntries.remove(firstKey)
            if (removed != null) {
                currentBytes = (currentBytes - removed.sizeBytes).coerceAtLeast(0L)
            }
        }
    }
}
