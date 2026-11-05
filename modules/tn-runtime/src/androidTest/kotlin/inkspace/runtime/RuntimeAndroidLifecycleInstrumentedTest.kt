package inkspace.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import inkspace.core.unit.DocPoint
import inkspace.core.unit.DocRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeAndroidLifecycleInstrumentedTest {

    @Test
    fun android_instrumented_100_rotation_and_surface_cycles_preserve_durable_prefix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageDir = File(context.cacheDir, "f04-instrumented-${System.currentTimeMillis()}")
        storageDir.mkdirs()

        val config = RuntimeConfig(storageRootPath = storageDir.absolutePath)
        val runtime = Runtime.start(config, PlatformPorts()).getOrNull()!!
        val docId = DocumentId("doc-android-inst-100")

        assertTrue(runtime.trySend(AppCommand.OpenSession(docId)).isSuccess)
        var openedSession: inkspace.core.model.SessionKey? = null
        val openDeadline = System.currentTimeMillis() + 5_000L
        while (openedSession == null && System.currentTimeMillis() < openDeadline) {
            for (ev in runtime.pollEvents(32).events) {
                if (ev is AppEvent.SessionOpened && ev.session.document == docId) {
                    openedSession = ev.session
                }
            }
            if (openedSession == null) Thread.sleep(5L)
        }
        val session = requireNotNull(openedSession)

        var epoch = SurfaceEpoch(0L)
        var resize = ResizeGeneration(0L)
        for (cycle in 1..100) {
            epoch = epoch.next()
            resize = resize.next()
            assertTrue(
                runtime.trySend(
                    AppCommand.AttachSurface(
                        session = session,
                        surfaceEpoch = epoch,
                        widthPx = if (cycle % 2 == 0) 1920 else 1080,
                        heightPx = if (cycle % 2 == 0) 1080 else 1920,
                        resizeGeneration = resize
                    )
                ).isSuccess
            )
            val plan = EditPlan(
                base = DocVersion(docId, Revision((cycle - 1).toLong())),
                preconditions = emptyList(),
                mutations = listOf(
                    InsertObject(
                        space = SpaceId("space-1"),
                        layer = LayerId("layer-1"),
                        id = ObjectId("inst-obj-$cycle"),
                        payload = StrokePayload(
                            brushId = "ball",
                            color = 0xFF000000.toInt(),
                            baseWidthPt = 2.0,
                            origin = DocPoint(cycle.toDouble(), cycle.toDouble()),
                            samples = listOf(
                                StrokeSamplePoint(0f, 0f, 0.5f),
                                StrokeSamplePoint(10f, 10f, 0.7f)
                            )
                        ),
                        bounds = DocRect(0.0, 0.0, 20.0, 20.0)
                    )
                ),
                gesture = GestureId("inst-g-$cycle"),
                label = UndoLabel("inst-$cycle")
            )
            assertTrue(runtime.trySend(AppCommand.SubmitEdit(session, plan)).isSuccess)
            assertTrue(runtime.trySend(AppCommand.DetachSurface(session, epoch)).isSuccess)
            if (cycle % 25 == 0) {
                assertTrue(runtime.trySend(AppCommand.BackgroundFlush(session)).isSuccess)
                assertTrue(runtime.trySend(AppCommand.TrimMemory(MemoryPressureLevel.MODERATE)).isSuccess)
            }
        }

        assertTrue(runtime.closeAsync().isSuccess)
        var closed = false
        val closeDeadline = System.currentTimeMillis() + 10_000L
        while (!closed && System.currentTimeMillis() < closeDeadline) {
            if (runtime.pollEvents(64).events.any { it is AppEvent.Closed }) {
                closed = true
            } else {
                Thread.sleep(5L)
            }
        }
        assertTrue(closed)

        val runtime2 = Runtime.start(config, PlatformPorts()).getOrNull()!!
        assertTrue(runtime2.trySend(AppCommand.OpenSession(docId)).isSuccess)
        var reopenedSnap: inkspace.core.model.DocumentSnapshot? = null
        val reopenDeadline = System.currentTimeMillis() + 5_000L
        while (reopenedSnap == null && System.currentTimeMillis() < reopenDeadline) {
            for (ev in runtime2.pollEvents(32).events) {
                if (ev is AppEvent.SessionOpened && ev.session.document == docId) {
                    reopenedSnap = ev.snapshot
                }
            }
            if (reopenedSnap == null) Thread.sleep(5L)
        }
        val snap = requireNotNull(reopenedSnap)
        assertEquals(Revision(100L), snap.version.revision)
        assertEquals(100, snap.orderedSceneObjects().size)
        runtime2.closeAsync()
    }
}
