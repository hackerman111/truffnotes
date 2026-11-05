package inkspace.core

import inkspace.core.codec.CoreCodec
import inkspace.core.error.EditError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.TransactionId
import inkspace.core.id.UndoLabel
import inkspace.core.id.VersionId
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.mutation.EditPlan
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.validatePlan
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ConnectorPayload
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.payload.LinkAnchorPayload
import inkspace.core.payload.ObjectPayload
import inkspace.core.payload.ShapePayload
import inkspace.core.payload.ShapeType
import inkspace.core.payload.StickyPayload
import inkspace.core.payload.StrokePayload
import inkspace.core.payload.StrokeSamplePoint
import inkspace.core.payload.TapePayload
import inkspace.core.payload.TextPayload
import inkspace.core.payload.TextRun
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.CameraTransform
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class F01ContractTest {

    @Test
    fun id_types() {
        val docId: Any = DocumentId("018f0000-0000-7000-8000-000000000001")
        val objId: Any = ObjectId("018f0000-0000-7000-8000-000000000001")
        val spaceId: Any = SpaceId("018f0000-0000-7000-8000-000000000001")
        val pageId: Any = PageId("018f0000-0000-7000-8000-000000000001")
        val layerId: Any = LayerId("018f0000-0000-7000-8000-000000000001")
        val assetId: Any = AssetId("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val verId: Any = VersionId("v1")
        val txId: Any = TransactionId("tx-001")

        // Distinct types are never equal even if underlying string matches
        assertNotEquals(docId, objId)
        assertNotEquals(docId, spaceId)
        assertNotEquals(pageId, layerId)
        assertNotEquals(assetId, verId)
        assertNotEquals(verId, txId)

        // Blank IDs are rejected
        assertThrows(IllegalArgumentException::class.java) { DocumentId("") }
        assertThrows(IllegalArgumentException::class.java) { DocumentId("   ") }
        assertThrows(IllegalArgumentException::class.java) { ObjectId("") }
        assertThrows(IllegalArgumentException::class.java) { AssetId("") }

        // Revision is monotonic and non-negative
        assertThrows(IllegalArgumentException::class.java) { Revision(-1L) }
        val r0 = Revision(0L)
        val r1 = r0.next()
        assertEquals(Revision(1L), r1)
        assertTrue(r1 > r0)
    }

    @Test
    fun coordinate_roundtrip() {
        val transforms = listOf(
            CameraTransform(scale = 1.0, translateX = 0.0, translateY = 0.0),
            CameraTransform(scale = 2.5, translateX = -450.25, translateY = 1280.75),
            CameraTransform(scale = 0.05, translateX = 10000.0, translateY = -25000.0),
            CameraTransform(scale = 120.0, translateX = 15.125, translateY = -88.875),
            CameraTransform(scale = 1e-4, translateX = 50.0, translateY = -50.0),
            CameraTransform(scale = 1e4, translateX = -1000.0, translateY = 2000.0)
        )

        val testPoints = listOf(
            DocPoint(0.0, 0.0),
            DocPoint(595.28, 841.89),
            DocPoint(-8192.0, 8192.0),
            DocPoint(123456.789, -654321.123),
            DocPoint(0.005, -0.005)
        )

        for (transform in transforms) {
            for (pt in testPoints) {
                val viewPt = transform.docToView(pt)
                val roundtrip = transform.viewToDoc(viewPt)
                val errX = abs(roundtrip.x - pt.x)
                val errY = abs(roundtrip.y - pt.y)
                assertTrue(errX <= 0.01, "Roundtrip X error $errX > 0.01pt for $pt with $transform")
                assertTrue(errY <= 0.01, "Roundtrip Y error $errY > 0.01pt for $pt with $transform")
            }
        }

        // World envelope bounds check
        assertThrows(IllegalArgumentException::class.java) {
            DocPoint(1e9 + 1.0, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraTransform(scale = 1e-5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CameraTransform(scale = 1e5)
        }
    }

    @Test
    fun limits() {
        val limits = Limits(maxObjectsPerSpace = 2, maxGroupDepth = 32)
        val docVersion = DocVersion(DocumentId("doc-1"), Revision(1L))

        val dummyRead = object : DocumentRead {
            override val version: DocVersion = docVersion
            override val totalObjectCount: Int = 2
            override fun objectState(id: ObjectId): ResidencyState = ResidencyState.Missing
            override fun isLocked(id: ObjectId): Boolean = false
            override fun getObjectPayload(id: ObjectId): ObjectPayload? = null
            override fun queryBounds(rect: DocRect): List<ObjectId> = emptyList()
        }

        val plan = EditPlan(
            base = docVersion,
            mutations = listOf(
                InsertObject(
                    space = SpaceId("space-1"),
                    layer = LayerId("layer-1"),
                    id = ObjectId("obj-3"),
                    payload = StickyPayload(color = 0xFFFFFF00.toInt(), content = "Note"),
                    bounds = DocRect(0.0, 0.0, 100.0, 100.0)
                )
            ),
            gesture = GestureId("g-1"),
            label = UndoLabel("Insert sticky")
        )

        val result = validatePlan(dummyRead, plan, limits)
        assertTrue(result.isFailure)
        val err = result.errorOrNull()
        assertTrue(err is EditError.LimitExceeded)
        val limitErr = err as EditError.LimitExceeded
        assertEquals("maxObjectsPerSpace", limitErr.limit)
        assertEquals(3L, limitErr.current)
        assertEquals(2L, limitErr.max)
    }

    @Test
    fun wire_rejects_nonfinite() {
        // Direct construction rejects NaN and Infinity
        assertThrows(IllegalArgumentException::class.java) { DocPoint(Double.NaN, 10.0) }
        assertThrows(IllegalArgumentException::class.java) { DocPoint(10.0, Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { DocPoint(Double.NEGATIVE_INFINITY, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { StrokeSamplePoint(Float.NaN, 0.0f) }
        assertThrows(IllegalArgumentException::class.java) { StrokeSamplePoint(0.0f, Float.POSITIVE_INFINITY) }

        // JSON wire decoder rejects NaN and Infinity
        val nanJson = """{"x":NaN,"y":10.0}"""
        val infJson = """{"x":Infinity,"y":10.0}"""
        val negInfJson = """{"x":1.0,"y":-Infinity}"""

        assertTrue(CoreCodec.decodeFromJson<DocPoint>(nanJson).isFailure)
        assertTrue(CoreCodec.decodeFromJson<DocPoint>(infJson).isFailure)
        assertTrue(CoreCodec.decodeFromJson<DocPoint>(negInfJson).isFailure)

        // CBOR wire decoder rejects non-finite coordinates during deserialization init check
        val maliciousBytes = CoreCodec.encodeToCbor(RawUncheckedPoint(Double.NaN, 5.0))
        val decodedOutcome = CoreCodec.decodeFromCbor<DocPoint>(maliciousBytes)
        assertTrue(decodedOutcome.isFailure)
    }

    @kotlinx.serialization.Serializable
    data class RawUncheckedPoint(val x: Double, val y: Double)

    @Test
    fun payload_golden_roundtrip() {
        val payloads: List<ObjectPayload> = listOf(
            StrokePayload(
                brushId = "fountain_pen",
                color = 0xFF1A1A1A.toInt(),
                baseWidthPt = 2.5,
                origin = DocPoint(100.0, 200.0),
                samples = listOf(
                    StrokeSamplePoint(0.0f, 0.0f, 0.4f, 0.1f, 0),
                    StrokeSamplePoint(3.5f, 4.2f, 0.7f, 0.15f, 4)
                )
            ),
            ShapePayload(ShapeType.RoundedRectangle, strokeColor = 0xFF000000.toInt(), fillColor = 0x2200FF00, strokeWidthPt = 1.5),
            ConnectorPayload(ObjectId("obj-a"), ObjectId("obj-b"), strokeColor = 0xFF333333.toInt()),
            TextPayload("Привет, мир! שלום 手書き", fontSizePt = 14.0, runs = listOf(TextRun(0, 6, bold = true))),
            ImagePayload(AssetId("sha256:123456"), naturalWidthPx = 1920, naturalHeightPx = 1080, cropRect = DocRect(0.0, 0.0, 500.0, 400.0)),
            GroupPayload(listOf(ObjectId("obj-1"), ObjectId("obj-2"))),
            StickyPayload(color = 0xFFFFF59D.toInt(), content = "Review chapter 3"),
            TapePayload(patternColor = 0xFF80CBC4.toInt(), opacity = 0.85f),
            LinkAnchorPayload(title = "Jump to Page 2", targetPage = PageId("page-2"))
        )

        for (payload in payloads) {
            val cborBytes = CoreCodec.encodeToCbor<ObjectPayload>(payload)
            val fromCbor = CoreCodec.decodeFromCbor<ObjectPayload>(cborBytes)
            assertTrue(fromCbor.isSuccess, "CBOR roundtrip failed for $payload")
            assertEquals(payload, fromCbor.getOrNull())

            val jsonStr = CoreCodec.encodeToJson<ObjectPayload>(payload)
            val fromJson = CoreCodec.decodeFromJson<ObjectPayload>(jsonStr)
            assertTrue(fromJson.isSuccess, "JSON roundtrip failed for $payload")
            assertEquals(payload, fromJson.getOrNull())
        }

        // Unsupported / unknown required feature in JSON is rejected
        val unsupportedJson = """{"type":"stroke","brushId":"pen","color":0,"baseWidthPt":2.0,"origin":{"x":0.0,"y":0.0},"samples":[],"unsupportedFutureFeature":true}"""
        val rejected = CoreCodec.decodeFromJson<ObjectPayload>(unsupportedJson)
        assertFalse(rejected.isSuccess)
    }
}
