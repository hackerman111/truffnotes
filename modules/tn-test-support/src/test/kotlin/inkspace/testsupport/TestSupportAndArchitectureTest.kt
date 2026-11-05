package inkspace.testsupport

import inkspace.core.error.SubmitError
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.ports.JobSpec
import inkspace.core.ports.PdfRequest
import inkspace.core.ports.RenderJob
import inkspace.core.ports.StorageRequest
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestSupportAndArchitectureTest {

    @Test
    fun bounded_fake_ports_enforce_overflow() {
        val storage = BoundedFakeStoragePort(maxQueueDepth = 2)
        val r1 = storage.trySubmit(StorageRequest.Commit(Revision(1L), 1024L))
        val r2 = storage.trySubmit(StorageRequest.Commit(Revision(2L), 2048L))
        val r3 = storage.trySubmit(StorageRequest.Commit(Revision(3L), 4096L))

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertTrue(r3.isFailure)
        assertTrue(r3.errorOrNull() is SubmitError.QueueOverflow)
        assertEquals(2, storage.pendingCount())

        // Polling frees space
        storage.pollNext()
        assertEquals(1, storage.pendingCount())
        val r4 = storage.trySubmit(StorageRequest.Commit(Revision(4L), 512L))
        assertTrue(r4.isSuccess)

        val pdf = BoundedFakePdfPort(maxQueueDepth = 1)
        assertTrue(pdf.trySubmit(PdfRequest.RenderPage(0, 100, 100)).isSuccess)
        assertTrue(pdf.trySubmit(PdfRequest.RenderPage(1, 100, 100)).isFailure)

        val render = BoundedFakeRenderPort(maxQueueDepth = 1)
        assertTrue(render.trySubmit(RenderJob.Draw(DocRect(0.0, 0.0, 10.0, 10.0), Revision(1L))).isSuccess)
        assertTrue(render.trySubmit(RenderJob.Draw(DocRect(0.0, 0.0, 10.0, 10.0), Revision(2L))).isFailure)

        val sink = BoundedFakeJobSink(maxQueueDepth = 1)
        assertTrue(sink.trySubmit(JobSpec("job-1", 1)).isSuccess)
        assertTrue(sink.trySubmit(JobSpec("job-2", 1)).isFailure)
    }

    @Test
    fun test_clock_advances_monotonically() {
        val clock = TestClock(initialNanos = 5_000_000L)
        assertEquals(5L, clock.nowMillis())
        clock.advanceMillis(10L)
        assertEquals(15L, clock.nowMillis())
        assertThrows(IllegalArgumentException::class.java) {
            clock.advanceNanos(-1L)
        }
    }

    @Test
    fun fake_document_read_distinguishes_residency() {
        val presentId = ObjectId("obj-present")
        val nonResId = ObjectId("obj-nonresident")
        val missingId = ObjectId("obj-missing")

        val doc = FakeDocumentRead(
            nonResidentIds = setOf(nonResId),
            lockedIds = setOf(nonResId)
        )

        assertEquals(ResidencyState.Missing, doc.objectState(presentId))
        assertEquals(ResidencyState.NotResident, doc.objectState(nonResId))
        assertEquals(ResidencyState.Missing, doc.objectState(missingId))
        assertTrue(doc.isLocked(nonResId))
    }

    @Test
    fun negative_fixture_gradle_projects_rejected(@TempDir tempDir: File) {
        // 1. Forbidden 'api' edge between feature modules
        val apiFixture = File(tempDir, "case-api").apply { mkdirs() }
        File(apiFixture, "modules/tn-ink").mkdirs()
        File(apiFixture, "modules/tn-ink/build.gradle.kts").writeText(
            """
            dependencies {
                api(project(":tn-pdf"))
            }
            """.trimIndent()
        )
        val vApi = ArchitectureVerifier.verifyWorkspace(apiFixture)
        assertEquals(1, vApi.size)
        assertEquals("forbidden-module-edge", vApi[0].rule)

        // 2. Forbidden 'implementation' edge between feature modules
        val implFixture = File(tempDir, "case-impl").apply { mkdirs() }
        File(implFixture, "modules/tn-objects").mkdirs()
        File(implFixture, "modules/tn-objects/build.gradle.kts").writeText(
            """
            dependencies {
                implementation(project(":tn-store"))
            }
            """.trimIndent()
        )
        val vImpl = ArchitectureVerifier.verifyWorkspace(implFixture)
        assertEquals(1, vImpl.size)
        assertEquals("forbidden-module-edge", vImpl[0].rule)

        // 3. Forbidden 'testImplementation' edge between feature modules
        val testFixture = File(tempDir, "case-test").apply { mkdirs() }
        File(testFixture, "modules/tn-search").mkdirs()
        File(testFixture, "modules/tn-search/build.gradle.kts").writeText(
            """
            dependencies {
                testImplementation(project(":tn-library"))
            }
            """.trimIndent()
        )
        val vTest = ArchitectureVerifier.verifyWorkspace(testFixture)
        assertEquals(1, vTest.size)
        assertEquals("forbidden-module-edge", vTest[0].rule)

        // 4. Forbidden 'variant' (debugImplementation) edge
        val variantFixture = File(tempDir, "case-variant").apply { mkdirs() }
        File(variantFixture, "modules/tn-pdf").mkdirs()
        File(variantFixture, "modules/tn-pdf/build.gradle.kts").writeText(
            """
            dependencies {
                debugImplementation(project(":tn-render"))
            }
            """.trimIndent()
        )
        val vVariant = ArchitectureVerifier.verifyWorkspace(variantFixture)
        assertEquals(1, vVariant.size)
        assertEquals("forbidden-module-edge", vVariant[0].rule)

        // 5. Forbidden 'composite-build' edge
        val compositeFixture = File(tempDir, "case-composite").apply { mkdirs() }
        File(compositeFixture, "settings.gradle.kts").writeText(
            """
            rootProject.name = "bad-composite"
            includeBuild("unauthorized-bypass")
            """.trimIndent()
        )
        val vComposite = ArchitectureVerifier.verifyWorkspace(compositeFixture)
        assertEquals(1, vComposite.size)
        assertEquals("forbidden-composite-build", vComposite[0].rule)

        // 6. Prohibited Android/SQL import inside tn-core
        val coreImportFixture = File(tempDir, "case-core-import").apply { mkdirs() }
        val coreSrcDir = File(coreImportFixture, "modules/tn-core/src/main/kotlin/inkspace/core").apply { mkdirs() }
        File(coreSrcDir, "BadFile.kt").writeText(
            """
            package inkspace.core
            import android.graphics.Canvas
            import java.sql.Connection
            """.trimIndent()
        )
        val vCoreImport = ArchitectureVerifier.verifyWorkspace(coreImportFixture)
        assertEquals(2, vCoreImport.size)
        assertTrue(vCoreImport.all { it.rule == "prohibited-core-import" })

        // 7. Valid workspace passes with 0 violations
        val validFixture = File(tempDir, "case-valid").apply { mkdirs() }
        File(validFixture, "settings.gradle.kts").writeText("""includeBuild("build-logic")""")
        File(validFixture, "modules/tn-ink").mkdirs()
        File(validFixture, "modules/tn-ink/build.gradle.kts").writeText(
            """
            dependencies {
                implementation(project(":tn-core"))
                testImplementation(project(":tn-test-support"))
            }
            """.trimIndent()
        )
        val vValid = ArchitectureVerifier.verifyWorkspace(validFixture)
        assertTrue(vValid.isEmpty(), "Expected 0 violations on valid workspace, got: $vValid")
    }
}
