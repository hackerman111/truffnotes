package inkspace.core.model

import inkspace.core.codec.CoreCodec
import inkspace.core.error.ResourceError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.RequestGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import inkspace.core.id.SpaceId
import inkspace.core.mutation.History
import inkspace.core.outcome.Outcome
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.LinkAnchorPayload
import inkspace.core.payload.ObjectPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.TextPayload
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Serializable
enum class DocumentKind {
    Notebook,
    Canvas
}

@Serializable
data class DocVersion(
    val document: DocumentId,
    val revision: Revision
)

@Serializable
data class SessionKey(
    val document: DocumentId,
    val epoch: SessionEpoch
)

@Serializable
data class JobStamp(
    val session: SessionKey,
    val resource: String,
    val contentVersion: Long,
    val generation: RequestGeneration
)

@Serializable
data class AffineTransform2D(
    val m00: Double = 1.0,
    val m01: Double = 0.0,
    val m02: Double = 0.0,
    val m10: Double = 0.0,
    val m11: Double = 1.0,
    val m12: Double = 0.0
) {
    init {
        require(
            m00.isFinite() && m01.isFinite() && m02.isFinite() &&
                m10.isFinite() && m11.isFinite() && m12.isFinite()
        ) { "AffineTransform2D components must be finite" }
    }

    fun translate(dx: Double, dy: Double): AffineTransform2D {
        return copy(m02 = m02 + dx, m12 = m12 + dy)
    }

    /**
     * Composes `this` after `other` (`(this * other)(p) = this(other(p))`).
     */
    fun multiply(other: AffineTransform2D): AffineTransform2D {
        return AffineTransform2D(
            m00 = m00 * other.m00 + m01 * other.m10,
            m01 = m00 * other.m01 + m01 * other.m11,
            m02 = m00 * other.m02 + m01 * other.m12 + m02,
            m10 = m10 * other.m00 + m11 * other.m10,
            m11 = m10 * other.m01 + m11 * other.m11,
            m12 = m10 * other.m02 + m11 * other.m12 + m12
        )
    }

    fun transformPoint(point: DocPoint): DocPoint {
        return DocPoint(
            x = m00 * point.x + m01 * point.y + m02,
            y = m10 * point.x + m11 * point.y + m12
        )
    }

    fun transformRect(rect: DocRect): DocRect {
        val c0 = transformPoint(DocPoint(rect.left, rect.top))
        val c1 = transformPoint(DocPoint(rect.right, rect.top))
        val c2 = transformPoint(DocPoint(rect.right, rect.bottom))
        val c3 = transformPoint(DocPoint(rect.left, rect.bottom))
        val minX = min(min(c0.x, c1.x), min(c2.x, c3.x))
        val maxX = max(max(c0.x, c1.x), max(c2.x, c3.x))
        val minY = min(min(c0.y, c1.y), min(c2.y, c3.y))
        val maxY = max(max(c0.y, c1.y), max(c2.y, c3.y))
        return DocRect(minX, minY, maxX, maxY)
    }

    fun invert(): AffineTransform2D? {
        val det = m00 * m11 - m01 * m10
        if (!det.isFinite() || abs(det) < 1e-12) return null
        val invDet = 1.0 / det
        val i00 = m11 * invDet
        val i01 = -m01 * invDet
        val i02 = (m01 * m12 - m11 * m02) * invDet
        val i10 = -m10 * invDet
        val i11 = m00 * invDet
        val i12 = (m10 * m02 - m00 * m12) * invDet
        return AffineTransform2D(i00, i01, i02, i10, i11, i12)
    }

    companion object {
        val IDENTITY = AffineTransform2D()

        fun translation(dx: Double, dy: Double): AffineTransform2D {
            return AffineTransform2D(m02 = dx, m12 = dy)
        }

        fun scaleAround(scaleX: Double, scaleY: Double, pivot: DocPoint): AffineTransform2D {
            return AffineTransform2D(
                m00 = scaleX,
                m01 = 0.0,
                m02 = pivot.x * (1.0 - scaleX),
                m10 = 0.0,
                m11 = scaleY,
                m12 = pivot.y * (1.0 - scaleY)
            )
        }

        fun rotateAround(angleRadians: Double, pivot: DocPoint): AffineTransform2D {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return AffineTransform2D(
                m00 = c,
                m01 = -s,
                m02 = pivot.x - c * pivot.x + s * pivot.y,
                m10 = s,
                m11 = c,
                m12 = pivot.y - s * pivot.x - c * pivot.y
            )
        }

        fun flipHorizontalAround(pivotX: Double): AffineTransform2D {
            return AffineTransform2D(
                m00 = -1.0,
                m01 = 0.0,
                m02 = 2.0 * pivotX,
                m10 = 0.0,
                m11 = 1.0,
                m12 = 0.0
            )
        }

        fun flipVerticalAround(pivotY: Double): AffineTransform2D {
            return AffineTransform2D(
                m00 = 1.0,
                m01 = 0.0,
                m02 = 0.0,
                m10 = 0.0,
                m11 = -1.0,
                m12 = 2.0 * pivotY
            )
        }
    }
}

@Serializable
data class ObjectStyle(
    val opacity: Float = 1.0f,
    val isVisible: Boolean = true,
    val zOrder: Long = 0L
) {
    init {
        require(opacity.isFinite() && opacity in 0.0f..1.0f) { "ObjectStyle opacity must be in [0, 1]: $opacity" }
    }
}

@Serializable
data class PageDescriptor(
    val id: PageId,
    val spaceId: SpaceId,
    val widthPt: Double,
    val heightPt: Double
) {
    init {
        require(widthPt.isFinite() && widthPt > 0.0 && heightPt.isFinite() && heightPt > 0.0) {
            "Page dimensions must be positive finite: ($widthPt, $heightPt)"
        }
    }
}

@Serializable
data class LayerDescriptor(
    val id: LayerId,
    val pageId: PageId,
    val name: String,
    val opacity: Float = 1.0f,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false
) {
    init {
        require(name.isNotBlank()) { "Layer name cannot be blank" }
        require(opacity.isFinite() && opacity in 0.0f..1.0f) { "Layer opacity must be in [0, 1]: $opacity" }
    }
}

@Serializable
data class SceneObject(
    val id: ObjectId,
    val space: SpaceId,
    val layer: LayerId,
    val bounds: DocRect,
    val payload: ObjectPayload,
    val transform: AffineTransform2D = AffineTransform2D.IDENTITY,
    val style: ObjectStyle = ObjectStyle(),
    val isLocked: Boolean = false,
    val version: Long = 1L,
    val payloadDigest: Long = CoreCodec.computePayloadDigest(payload)
) {
    init {
        require(version >= 1L) { "SceneObject version must be >= 1" }
    }

    fun frozen(): SceneObject {
        val frozenPayload = when (payload) {
            is StrokePayload -> payload.copy(samples = java.util.List.copyOf(payload.samples))
            is TextPayload -> payload.copy(runs = java.util.List.copyOf(payload.runs))
            is GroupPayload -> payload.copy(members = java.util.List.copyOf(payload.members))
            else -> payload
        }
        return if (frozenPayload === payload) this else copy(payload = frozenPayload)
    }

    fun estimatedMemoryBytes(): Int {
        val payloadBytes = when (payload) {
            is StrokePayload -> 64 + payload.samples.size * 24
            is TextPayload -> 64 + payload.text.length * 2 + payload.runs.size * 24
            is GroupPayload -> 48 + payload.members.size * 16
            else -> 96
        }
        return 128 + payloadBytes
    }
}

internal sealed interface DirectoryEntry {
    val id: ObjectId
    val space: SpaceId
    val layer: LayerId
    val bounds: DocRect
    val isLocked: Boolean
    val version: Long
    val residency: ResidencyState
    val residentBytes: Int

    data class Present(val rawObj: SceneObject) : DirectoryEntry {
        val obj: SceneObject = rawObj.frozen()
        override val id: ObjectId get() = obj.id
        override val space: SpaceId get() = obj.space
        override val layer: LayerId get() = obj.layer
        override val bounds: DocRect get() = obj.bounds
        override val isLocked: Boolean get() = obj.isLocked
        override val version: Long get() = obj.version
        override val residency: ResidencyState get() = ResidencyState.Present
        override val residentBytes: Int = obj.estimatedMemoryBytes()
    }

    data class NonResident(
        override val id: ObjectId,
        override val space: SpaceId,
        override val layer: LayerId,
        override val bounds: DocRect,
        override val isLocked: Boolean,
        override val version: Long,
        val estimatedColdBytes: Int
    ) : DirectoryEntry {
        override val residency: ResidencyState get() = ResidencyState.NotResident
        override val residentBytes: Int get() = 64
    }
}

internal class LeafChunk(
    val entries: Map<ObjectId, DirectoryEntry>,
    val residentBytes: Long
) {
    internal val entryArray: Array<DirectoryEntry> = entries.values.toTypedArray()
}

internal class Level1Branch(
    val leaves: Array<LeafChunk?>
)

/**
 * Persistent 64x64 two-level radix tree (4,096 leaf chunks) storing document object headers and resident payload refs.
 * Guarantees O(1) structural sharing on single-object edits without copying the full object collection.
 */
class ChunkedObjectDirectory internal constructor(
    private val branches: Array<Level1Branch?>,
    val totalCount: Int,
    val totalResidentBytes: Long
) {
    constructor() : this(arrayOfNulls<Level1Branch>(64), 0, 0L)

    private fun mixHash(id: ObjectId): Int {
        var h = id.value.hashCode()
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }

    internal fun getEntry(id: ObjectId): DirectoryEntry? {
        val h = mixHash(id)
        val b = branches[h and 63] ?: return null
        val leaf = b.leaves[(h ushr 6) and 63] ?: return null
        return leaf.entries[id]
    }

    fun objectState(id: ObjectId): ResidencyState {
        return getEntry(id)?.residency ?: ResidencyState.Missing
    }

    fun getObject(id: ObjectId): SceneObject? {
        return (getEntry(id) as? DirectoryEntry.Present)?.obj
    }

    fun getObjectVersion(id: ObjectId): Long? {
        return getEntry(id)?.version
    }

    fun isObjectLocked(id: ObjectId): Boolean {
        return getEntry(id)?.isLocked ?: false
    }

    internal fun putEntry(entry: DirectoryEntry): ChunkedObjectDirectory {
        val h = mixHash(entry.id)
        val i1 = h and 63
        val i2 = (h ushr 6) and 63

        val oldBranch = branches[i1]
        val oldLeaf = oldBranch?.leaves?.get(i2)
        val oldEntry = oldLeaf?.entries?.get(entry.id)

        val newMap = if (oldLeaf == null) {
            mapOf(entry.id to entry)
        } else {
            val m = LinkedHashMap<ObjectId, DirectoryEntry>(oldLeaf.entries.size + 1)
            m.putAll(oldLeaf.entries)
            m[entry.id] = entry
            m
        }

        val deltaCount = if (oldEntry == null) 1 else 0
        val deltaBytes = entry.residentBytes.toLong() - (oldEntry?.residentBytes?.toLong() ?: 0L)
        val newLeafBytes = (oldLeaf?.residentBytes ?: 0L) + deltaBytes
        val newLeaf = LeafChunk(newMap, newLeafBytes)

        val newLeaves = oldBranch?.leaves?.copyOf() ?: arrayOfNulls(64)
        newLeaves[i2] = newLeaf

        val newBranches = branches.copyOf()
        newBranches[i1] = Level1Branch(newLeaves)

        return ChunkedObjectDirectory(
            branches = newBranches,
            totalCount = totalCount + deltaCount,
            totalResidentBytes = totalResidentBytes + deltaBytes
        )
    }

    internal fun removeEntry(id: ObjectId): ChunkedObjectDirectory {
        val h = mixHash(id)
        val i1 = h and 63
        val i2 = (h ushr 6) and 63

        val oldBranch = branches[i1] ?: return this
        val oldLeaf = oldBranch.leaves[i2] ?: return this
        val oldEntry = oldLeaf.entries[id] ?: return this

        val deltaBytes = -oldEntry.residentBytes.toLong()
        val newLeaf: LeafChunk? = if (oldLeaf.entries.size == 1) {
            null
        } else {
            val m = LinkedHashMap<ObjectId, DirectoryEntry>(oldLeaf.entries.size)
            for ((k, v) in oldLeaf.entries) {
                if (k != id) m[k] = v
            }
            LeafChunk(m, oldLeaf.residentBytes + deltaBytes)
        }

        val newLeaves = oldBranch.leaves.copyOf()
        newLeaves[i2] = newLeaf

        val newBranches = branches.copyOf()
        newBranches[i1] = Level1Branch(newLeaves)

        return ChunkedObjectDirectory(
            branches = newBranches,
            totalCount = totalCount - 1,
            totalResidentBytes = totalResidentBytes + deltaBytes
        )
    }

    fun queryBounds(rect: DocRect): List<ObjectId> {
        val result = ArrayList<ObjectId>()
        for (b in branches) {
            if (b == null) continue
            for (leaf in b.leaves) {
                if (leaf == null) continue
                for (entry in leaf.entryArray) {
                    if (entry.bounds.intersects(rect)) {
                        result.add(entry.id)
                    }
                }
            }
        }
        return result
    }

    fun allResidentObjects(): List<SceneObject> {
        val list = ArrayList<SceneObject>()
        for (b in branches) {
            if (b == null) continue
            for (leaf in b.leaves) {
                if (leaf == null) continue
                for (entry in leaf.entryArray) {
                    if (entry is DirectoryEntry.Present) {
                        list.add(entry.obj)
                    }
                }
            }
        }
        return list
    }

    fun totalLeafChunks(): Int {
        var count = 0
        for (b in branches) {
            if (b == null) continue
            for (leaf in b.leaves) {
                if (leaf != null) count++
            }
        }
        return count
    }

    fun countSharedLeafChunks(other: ChunkedObjectDirectory): Int {
        var shared = 0
        for (i1 in 0 until 64) {
            val b1 = this.branches[i1]
            val b2 = other.branches[i1]
            if (b1 == null || b2 == null) continue
            if (b1 === b2) {
                for (leaf in b1.leaves) {
                    if (leaf != null) shared++
                }
            } else {
                for (i2 in 0 until 64) {
                    val l1 = b1.leaves[i2]
                    val l2 = b2.leaves[i2]
                    if (l1 != null && l1 === l2) {
                        shared++
                    }
                }
            }
        }
        return shared
    }

    fun estimateUnsharedStructureBytes(previous: ChunkedObjectDirectory): Long {
        if (this === previous) return 0L
        var bytes = 64L * 8L // root branches array
        for (i1 in 0 until 64) {
            val bCurr = this.branches[i1] ?: continue
            val bPrev = previous.branches[i1]
            if (bCurr === bPrev) continue
            bytes += 64L * 8L // Level1Branch leaves array
            for (i2 in 0 until 64) {
                val lCurr = bCurr.leaves[i2] ?: continue
                val lPrev = bPrev?.leaves?.get(i2)
                if (lCurr === lPrev) continue
                bytes += 48L + lCurr.entries.size.toLong() * 32L
            }
        }
        return bytes
    }
}

/**
 * Deeply immutable snapshot of a document at a specific revision.
 * Uses structural sharing via ChunkedObjectDirectory; never exposes mutable collections.
 */
class DocumentSnapshot internal constructor(
    override val version: DocVersion,
    val kind: DocumentKind,
    val directory: ChunkedObjectDirectory,
    rawPages: List<PageDescriptor>,
    rawLayers: List<LayerDescriptor>,
    private val pageMap: Map<PageId, PageDescriptor>,
    private val layerMap: Map<LayerId, LayerDescriptor>,
    private val parentGroupMap: Map<ObjectId, ObjectId>
) : DocumentRead {

    override val pages: List<PageDescriptor> = java.util.List.copyOf(rawPages)
    override val layers: List<LayerDescriptor> = java.util.List.copyOf(rawLayers)

    override val totalObjectCount: Int
        get() = directory.totalCount

    override fun objectState(id: ObjectId): ResidencyState = directory.objectState(id)

    override fun isLocked(id: ObjectId): Boolean = directory.isObjectLocked(id)

    override fun isLayerLocked(layerId: LayerId): Boolean = layerMap[layerId]?.isLocked ?: false

    override fun getObjectPayload(id: ObjectId): ObjectPayload? = directory.getObject(id)?.payload

    override fun getObject(id: ObjectId): SceneObject? = directory.getObject(id)

    override fun getObjectVersion(id: ObjectId): Long? = directory.getObjectVersion(id)

    override fun getPage(pageId: PageId): PageDescriptor? = pageMap[pageId]

    override fun getLayer(layerId: LayerId): LayerDescriptor? = layerMap[layerId]

    override fun parentGroupOf(id: ObjectId): ObjectId? = parentGroupMap[id]

    override fun queryBounds(rect: DocRect): List<ObjectId> = directory.queryBounds(rect)

    fun orderedSceneObjects(): List<SceneObject> {
        val layerOrder = HashMap<LayerId, Int>(layers.size)
        layers.forEachIndexed { idx, l -> layerOrder[l.id] = idx }
        return java.util.List.copyOf(
            directory.allResidentObjects().sortedWith(
                compareBy<SceneObject> { layerOrder[it.layer] ?: Int.MAX_VALUE }
                    .thenBy { it.style.zOrder }
                    .thenBy { it.id.value }
            )
        )
    }

    fun referencedAssets(): Set<AssetId> {
        val assets = LinkedHashSet<AssetId>()
        for (obj in directory.allResidentObjects()) {
            val payload = obj.payload
            if (payload is ImagePayload) {
                assets.add(payload.asset)
            }
        }
        return assets
    }

    fun verifyInvariants(): Boolean {
        val pageIds = pageMap.keys
        val layerIds = layerMap.keys
        for (layer in layers) {
            if (layer.pageId !in pageIds) return false
        }
        val resident = directory.allResidentObjects()
        val seenMembers = HashSet<ObjectId>()
        for (obj in resident) {
            if (obj.layer !in layerIds) return false
            when (val p = obj.payload) {
                is GroupPayload -> {
                    for (m in p.members) {
                        val memberObj = directory.getObject(m) ?: return false
                        if (memberObj.space != obj.space) return false
                        if (!seenMembers.add(m)) return false
                    }
                }
                is LinkAnchorPayload -> {
                    val targetPage = p.targetPage
                    if (targetPage != null && targetPage !in pageIds) return false
                }
                else -> {}
            }
        }
        return true
    }

    fun contentFingerprint(): Long {
        val sb = StringBuilder(256)
        sb.append(kind.name).append('|')
        for (p in pages) {
            sb.append("P:${p.id.value}:${p.spaceId.value}:${p.widthPt}:${p.heightPt}|")
        }
        for (l in layers) {
            sb.append("L:${l.id.value}:${l.pageId.value}:${l.name}:${l.opacity}:${l.isVisible}:${l.isLocked}|")
        }
        for (obj in orderedSceneObjects()) {
            sb.append(
                "O:${obj.id.value}:${obj.space.value}:${obj.layer.value}:" +
                    "${obj.bounds.left},${obj.bounds.top},${obj.bounds.right},${obj.bounds.bottom}:" +
                    "${obj.transform.m00},${obj.transform.m01},${obj.transform.m02}," +
                    "${obj.transform.m10},${obj.transform.m11},${obj.transform.m12}:" +
                    "${obj.style.opacity},${obj.style.isVisible},${obj.style.zOrder}:" +
                    "${obj.isLocked}:${obj.payloadDigest}|"
            )
        }
        return CoreCodec.fnv1a64(sb.toString().encodeToByteArray())
    }

    fun estimatedPinnedBytes(): Long {
        return 256L + pages.size * 64L + layers.size * 64L + directory.totalResidentBytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentSnapshot) return false
        return version == other.version &&
            kind == other.kind &&
            pages == other.pages &&
            layers == other.layers &&
            contentFingerprint() == other.contentFingerprint()
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + contentFingerprint().hashCode()
        return result
    }
}

/**
 * Actor-confined document state. All business modifications pass through validatePlan -> applyPlan.
 */
class DocumentState private constructor(
    private var currentVersion: DocVersion,
    val kind: DocumentKind,
    private var currentDirectory: ChunkedObjectDirectory,
    private var currentPages: List<PageDescriptor>,
    private var currentLayers: List<LayerDescriptor>,
    private var currentPageMap: Map<PageId, PageDescriptor>,
    private var currentLayerMap: Map<LayerId, LayerDescriptor>,
    private var currentParentGroups: Map<ObjectId, ObjectId>
) : DocumentRead {

    override val version: DocVersion
        get() = currentVersion

    override val totalObjectCount: Int
        get() = currentDirectory.totalCount

    override val pages: List<PageDescriptor>
        get() = currentPages

    override val layers: List<LayerDescriptor>
        get() = currentLayers

    override fun objectState(id: ObjectId): ResidencyState = currentDirectory.objectState(id)

    override fun isLocked(id: ObjectId): Boolean = currentDirectory.isObjectLocked(id)

    override fun isLayerLocked(layerId: LayerId): Boolean = currentLayerMap[layerId]?.isLocked ?: false

    override fun getObjectPayload(id: ObjectId): ObjectPayload? = currentDirectory.getObject(id)?.payload

    override fun getObject(id: ObjectId): SceneObject? = currentDirectory.getObject(id)

    override fun getObjectVersion(id: ObjectId): Long? = currentDirectory.getObjectVersion(id)

    override fun getPage(pageId: PageId): PageDescriptor? = currentPageMap[pageId]

    override fun getLayer(layerId: LayerId): LayerDescriptor? = currentLayerMap[layerId]

    override fun parentGroupOf(id: ObjectId): ObjectId? = currentParentGroups[id]

    override fun queryBounds(rect: DocRect): List<ObjectId> = currentDirectory.queryBounds(rect)

    fun snapshot(): DocumentSnapshot {
        return DocumentSnapshot(
            version = currentVersion,
            kind = kind,
            directory = currentDirectory,
            rawPages = currentPages,
            rawLayers = currentLayers,
            pageMap = currentPageMap,
            layerMap = currentLayerMap,
            parentGroupMap = currentParentGroups
        )
    }

    fun registerNonResidentObject(
        id: ObjectId,
        space: SpaceId,
        layer: LayerId,
        bounds: DocRect,
        version: Long = 1L,
        estimatedBytes: Int = 1024,
        isLocked: Boolean = false
    ) {
        currentDirectory = currentDirectory.putEntry(
            DirectoryEntry.NonResident(
                id = id,
                space = space,
                layer = layer,
                bounds = bounds,
                isLocked = isLocked,
                version = version,
                estimatedColdBytes = estimatedBytes
            )
        )
    }

    fun putResidentObjectDirect(obj: SceneObject) {
        currentDirectory = currentDirectory.putEntry(DirectoryEntry.Present(obj))
        if (obj.payload is GroupPayload) {
            val nextParents = LinkedHashMap(currentParentGroups)
            for (m in obj.payload.members) {
                nextParents[m] = obj.id
            }
            currentParentGroups = nextParents
        }
    }

    internal fun commitTransition(
        nextVersion: DocVersion,
        nextDirectory: ChunkedObjectDirectory,
        nextPages: List<PageDescriptor>,
        nextLayers: List<LayerDescriptor>,
        nextPageMap: Map<PageId, PageDescriptor>,
        nextLayerMap: Map<LayerId, LayerDescriptor>,
        nextParentGroups: Map<ObjectId, ObjectId>
    ) {
        this.currentVersion = nextVersion
        this.currentDirectory = nextDirectory
        this.currentPages = nextPages
        this.currentLayers = nextLayers
        this.currentPageMap = nextPageMap
        this.currentLayerMap = nextLayerMap
        this.currentParentGroups = nextParentGroups
    }

    companion object {
        fun fromSnapshot(snapshot: DocumentSnapshot): DocumentState {
            val pMap = snapshot.pages.associateBy { it.id }
            val lMap = snapshot.layers.associateBy { it.id }
            val parents = LinkedHashMap<ObjectId, ObjectId>()
            for (obj in snapshot.directory.allResidentObjects()) {
                if (obj.payload is GroupPayload) {
                    for (m in obj.payload.members) {
                        parents[m] = obj.id
                    }
                }
            }
            return DocumentState(
                currentVersion = snapshot.version,
                kind = snapshot.kind,
                currentDirectory = snapshot.directory,
                currentPages = snapshot.pages,
                currentLayers = snapshot.layers,
                currentPageMap = pMap,
                currentLayerMap = lMap,
                currentParentGroups = parents
            )
        }

        fun createInitial(
            documentId: DocumentId,
            kind: DocumentKind = DocumentKind.Notebook,
            initialSpace: SpaceId = SpaceId("space-1"),
            initialPage: PageDescriptor = PageDescriptor(PageId("page-1"), initialSpace, 595.28, 841.89),
            initialLayer: LayerDescriptor = LayerDescriptor(LayerId("layer-1"), initialPage.id, "Layer 1")
        ): DocumentState {
            val pages = listOf(initialPage)
            val layers = listOf(initialLayer)
            return DocumentState(
                currentVersion = DocVersion(documentId, Revision(0L)),
                kind = kind,
                currentDirectory = ChunkedObjectDirectory(),
                currentPages = pages,
                currentLayers = layers,
                currentPageMap = mapOf(initialPage.id to initialPage),
                currentLayerMap = mapOf(initialLayer.id to initialLayer),
                currentParentGroups = emptyMap()
            )
        }
    }
}

class SnapshotLease internal constructor(
    val snapshot: DocumentSnapshot,
    val pinnedBytes: Long,
    private val onRelease: (SnapshotLease) -> Unit
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            onRelease(this)
        }
    }
}

class SnapshotPinTracker(
    val maxTotalPinnedBytes: Long = 16_000_000L
) {
    private val activeLeases = LinkedHashSet<SnapshotLease>()
    private var snapshotPinnedBytes: Long = 0L

    fun currentSnapshotBytes(): Long = snapshotPinnedBytes

    fun currentTotalBytes(history: History): Long = snapshotPinnedBytes + history.pinnedBytes

    fun activePinCount(): Int = activeLeases.size

    fun acquirePin(snapshot: DocumentSnapshot, history: History): Outcome<SnapshotLease, ResourceError> {
        val bytes = snapshot.estimatedPinnedBytes().coerceAtLeast(256L)
        val projected = snapshotPinnedBytes + history.pinnedBytes + bytes
        if (projected > maxTotalPinnedBytes) {
            return Outcome.Failure(
                ResourceError.ResourceExhausted(
                    "Total snapshot + history pinned bytes ($projected) exceeds budget ($maxTotalPinnedBytes)"
                )
            )
        }
        val lease = SnapshotLease(snapshot, bytes) { released ->
            if (activeLeases.remove(released)) {
                snapshotPinnedBytes = (snapshotPinnedBytes - released.pinnedBytes).coerceAtLeast(0L)
            }
        }
        activeLeases.add(lease)
        snapshotPinnedBytes += bytes
        return Outcome.Success(lease)
    }
}
