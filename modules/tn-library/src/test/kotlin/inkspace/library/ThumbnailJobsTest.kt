package inkspace.library

import inkspace.core.outcome.Outcome
import inkspace.core.workspace.NotebookThumbnail
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ThumbnailJobsTest {
    @Test fun thumbnailCacheUsesBoundedBytesAndInvalidatesChangedDocument() {
        val calls = AtomicInteger()
        ThumbnailJobs({ _, _ -> calls.incrementAndGet(); Outcome.Success(NotebookThumbnail(32, 32, IntArray(1024))) }, byteCap = 4096).use { jobs ->
            assertTrue(jobs.get("a", 32).isSuccess)
            assertTrue(jobs.get("a", 32).isSuccess)
            assertEquals(1, calls.get())
            assertTrue(jobs.get("b", 32).isSuccess)
            assertEquals(4096, jobs.residentBytes())
            jobs.invalidate("b")
            assertEquals(0, jobs.residentBytes())
            assertFalse(jobs.get("a", 1000).isSuccess)
        }
    }
}
