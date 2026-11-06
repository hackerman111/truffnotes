package inkspace.objects

import inkspace.core.objects.AlignmentGuideKind
import inkspace.core.objects.GridKind
import inkspace.core.objects.GridSpec
import inkspace.core.objects.ProtractorOverlayState
import inkspace.core.objects.RulerEdge
import inkspace.core.objects.RulerOverlayState
import inkspace.core.objects.RulerUnit
import inkspace.core.objects.SnapConfig
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class F08RulerProtractorAndSnapTest {

    @Test
    fun testRulerTwoEdgeSnapAndUnitConversions() {
        // Measurement conversions
        val cmValue = 10.0 // 10 cm
        val ptFromCm = SnapEngine.rulerUnitToPt(cmValue, RulerUnit.CENTIMETERS)
        assertEquals(10.0 * (72.0 / 2.54), ptFromCm, 1e-4)
        val cmBack = SnapEngine.ptToRulerUnit(ptFromCm, RulerUnit.CENTIMETERS)
        assertEquals(cmValue, cmBack, 1e-4)

        val inValue = 5.0 // 5 inches
        val ptFromIn = SnapEngine.rulerUnitToPt(inValue, RulerUnit.INCHES)
        assertEquals(360.0, ptFromIn, 1e-4)
        val inBack = SnapEngine.ptToRulerUnit(ptFromIn, RulerUnit.INCHES)
        assertEquals(inValue, inBack, 1e-4)

        // Horizontal ruler centered at (200, 200), length=300, thickness=40 (halfThickness=20)
        // Top edge at y = 200 + 20 = 220; Bottom edge at y = 200 - 20 = 180
        val ruler = RulerOverlayState(
            isVisible = true,
            centerDoc = DocPoint(200.0, 200.0),
            angleDegrees = 0.0,
            lengthPt = 300.0,
            thicknessPt = 40.0,
            unit = RulerUnit.CENTIMETERS,
            snapTolerancePt = 16.0
        )

        // Point near top edge: (210, 225) within tolerance 16 -> snaps to y = 220
        val snappedTop = SnapEngine.snapToRulerEdge(DocPoint(210.0, 225.0), ruler)
        assertNotNull(snappedTop)
        assertEquals(210.0, snappedTop.first.x, 1e-4)
        assertEquals(220.0, snappedTop.first.y, 1e-4)
        assertEquals(RulerEdge.TOP_EDGE, snappedTop.second)

        // Point near bottom edge
        val snappedBottom = SnapEngine.snapToRulerEdge(DocPoint(150.0, 175.0), ruler)
        assertNotNull(snappedBottom)
        assertEquals(150.0, snappedBottom.first.x, 1e-4)
        assertEquals(180.0, snappedBottom.first.y, 1e-4)
        assertEquals(RulerEdge.BOTTOM_EDGE, snappedBottom.second)

        // Point far beyond ruler length -> no snap
        val outOfBounds = SnapEngine.snapToRulerEdge(DocPoint(500.0, 220.0), ruler)
        assertNull(outOfBounds)
    }

    @Test
    fun testProtractorArcAndRaySnap() {
        val protractor = ProtractorOverlayState(
            isVisible = true,
            centerDoc = DocPoint(300.0, 300.0),
            radiusPt = 100.0,
            baseAngleDegrees = 0.0,
            angleSnapStepDegrees = 15.0,
            snapTolerancePt = 10.0
        )
        val config = SnapConfig(
            protractor = protractor
        )

        // Point at distance 95 (within 10 pt of radius 100) and angle ~16 degrees (within 4 deg of 15 deg)
        val testAngle = 16.0 * (PI / 180.0)
        val testPt = DocPoint(300.0 + 95.0 * kotlin.math.cos(testAngle), 300.0 + 95.0 * kotlin.math.sin(testAngle))

        val result = SnapEngine.snapPoint(testPt, config)
        assertTrue(result.snappedToProtractorArc)
        // Snapped distance must be exactly 100
        val distSnapped = kotlin.math.sqrt(
            (result.point.x - 300.0) * (result.point.x - 300.0) +
                (result.point.y - 300.0) * (result.point.y - 300.0)
        )
        assertEquals(100.0, distSnapped, 1e-3)
    }

    @Test
    fun testAllSevenGridKinds() {
        // 1. BLANK: never snaps
        val blankGrid = GridSpec(kind = GridKind.BLANK, spacingPt = 20.0, snapEnabled = true)
        assertNull(SnapEngine.snapToGrid(DocPoint(19.0, 19.0), blankGrid))

        // 2. RULED: only snaps y
        val ruledGrid = GridSpec(kind = GridKind.RULED, spacingPt = 25.0, snapEnabled = true, snapTolerancePt = 8.0)
        val ruledSnap = SnapEngine.snapToGrid(DocPoint(33.0, 48.0), ruledGrid)
        assertNotNull(ruledSnap)
        assertEquals(33.0, ruledSnap.x) // x unchanged
        assertEquals(50.0, ruledSnap.y) // y snapped to 50

        // 3. SQUARED: snaps x and y
        val squaredGrid = GridSpec(kind = GridKind.SQUARED, spacingPt = 20.0, snapEnabled = true, snapTolerancePt = 8.0)
        val sqSnap = SnapEngine.snapToGrid(DocPoint(22.0, 39.0), squaredGrid)
        assertNotNull(sqSnap)
        assertEquals(20.0, sqSnap.x)
        assertEquals(40.0, sqSnap.y)

        // 4. DOTTED: snaps x and y
        val dottedGrid = GridSpec(kind = GridKind.DOTTED, spacingPt = 30.0, snapEnabled = true, snapTolerancePt = 8.0)
        val dotSnap = SnapEngine.snapToGrid(DocPoint(62.0, 88.0), dottedGrid)
        assertNotNull(dotSnap)
        assertEquals(60.0, dotSnap.x)
        assertEquals(90.0, dotSnap.y)

        // 5. MILLIMETER: snaps to ~2.83465 pt steps
        val mmGrid = GridSpec(kind = GridKind.MILLIMETER, snapEnabled = true, snapTolerancePt = 8.0)
        val mmStep = SnapEngine.POINTS_PER_CM / 10.0
        val mmSnap = SnapEngine.snapToGrid(DocPoint(mmStep * 3 + 0.5, mmStep * 5 - 0.4), mmGrid)
        assertNotNull(mmSnap)
        assertEquals(mmStep * 3, mmSnap.x, 1e-4)
        assertEquals(mmStep * 5, mmSnap.y, 1e-4)

        // 6. ISOMETRIC: triangular isometric lattice
        val isoGrid = GridSpec(kind = GridKind.ISOMETRIC, spacingPt = 20.0, snapEnabled = true, snapTolerancePt = 8.0)
        val isoSnap = SnapEngine.snapToGrid(DocPoint(21.0, 1.0), isoGrid)
        assertNotNull(isoSnap)

        // 7. CUSTOM: custom spacing
        val customGrid = GridSpec(kind = GridKind.CUSTOM, spacingPt = 45.0, snapEnabled = true, snapTolerancePt = 8.0)
        val customSnap = SnapEngine.snapToGrid(DocPoint(47.0, 88.0), customGrid)
        assertNotNull(customSnap)
        assertEquals(45.0, customSnap.x)
        assertEquals(90.0, customSnap.y)
    }

    @Test
    fun testAngleSnapping() {
        val config = SnapConfig(
            angleSnapEnabled = true,
            angleSnapStepDegrees = 15.0,
            angleSnapToleranceDegrees = 4.0
        )

        // 16° is within 4° of 15° -> snaps to 15°
        val snapped1 = SnapEngine.snapAngleDegrees(16.0, config)
        assertEquals(15.0, snapped1, 1e-4)

        // 8° is not within 4° of 0° or 15° -> remains 8°
        val snapped2 = SnapEngine.snapAngleDegrees(8.0, config)
        assertEquals(8.0, snapped2, 1e-4)
    }

    @Test
    fun testObjectAlignmentCentersAndEdges() {
        val config = SnapConfig(
            alignObjectsEnabled = true,
            alignObjectsTolerancePt = 8.0
        )
        // Fixed candidate box at (100, 100, 200, 200) -> Center is (150, 150)
        val candId = inkspace.core.id.ObjectId("cand-1")
        val candidate = candId to DocRect(100.0, 100.0, 200.0, 200.0)

        // Dragged box at (98, 250, 158, 350) -> width = 60, Left is 98 (within 2 of candidate left 100)
        val draggedBox = DocRect(98.0, 250.0, 158.0, 350.0)
        val alignRes = SnapEngine.alignObject(draggedBox, listOf(candidate), config)

        assertFalse(alignRes.activeGuides.isEmpty(), "Should produce alignment guide")
        assertEquals(100.0, alignRes.snappedBounds.left, 1e-4) // Snapped to candidate left edge!
        val guide = alignRes.activeGuides.first()
        assertEquals(AlignmentGuideKind.LEFT_EDGE, guide.kind)
        assertFalse(guide.isDashedCenterLine, "Edge guides must be solid")

        // Center alignment: dragged box with center X at 153 (within 3 of candidate center 150)
        val centerDragged = DocRect(103.0, 300.0, 203.0, 400.0) // Center X = 153
        val centerRes = SnapEngine.alignObject(centerDragged, listOf(candidate), config)
        val centerGuide = centerRes.activeGuides.find { it.kind == AlignmentGuideKind.CENTER_X }
        assertNotNull(centerGuide)
        assertTrue(centerGuide.isDashedCenterLine, "Center alignment guides must be dashed")
        assertEquals(150.0, (centerRes.snappedBounds.left + centerRes.snappedBounds.right) * 0.5, 1e-4)
    }
}
