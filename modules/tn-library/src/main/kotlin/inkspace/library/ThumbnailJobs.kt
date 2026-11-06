package inkspace.library

import inkspace.core.workspace.NotebookThumbnail
import inkspace.core.error.FeatureError
import inkspace.core.outcome.Outcome
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Bounded workers, pending jobs, result memory and deadlines for virtualized library cells. */
class ThumbnailJobs(
    private val render: (String, Int) -> Outcome<NotebookThumbnail, FeatureError>,
    private val byteCap: Int = 4 * 1024 * 1024
) : AutoCloseable {
    private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "notebook-thumbnail").apply { isDaemon = true } })
    private val cache = LinkedHashMap<Pair<String, Int>, NotebookThumbnail>(16, 0.75f, true)
    private var bytes = 0

    fun get(documentId: String, edge: Int): Outcome<NotebookThumbnail, FeatureError> {
        if (edge !in 16..256) return Outcome.Failure(FeatureError.InvalidParameters("Thumbnail edge out of range"))
        val key = documentId to edge
        synchronized(cache) { cache[key]?.let { return Outcome.Success(it) } }
        val future = try { executor.submit<Outcome<NotebookThumbnail, FeatureError>> { render(documentId, edge) } }
        catch (_: RejectedExecutionException) { return Outcome.Failure(FeatureError.InvalidParameters("Thumbnail queue full")) }
        return try {
            val result = future.get(3, TimeUnit.SECONDS)
            val thumbnail = result.getOrNull()
            if (thumbnail != null) synchronized(cache) {
                cache.remove(key)?.let { bytes -= it.byteSize }
                cache[key] = thumbnail
                bytes += thumbnail.byteSize
                while (bytes > byteCap && cache.isNotEmpty()) {
                    val oldest = cache.entries.iterator()
                    val entry = oldest.next()
                    bytes -= entry.value.byteSize
                    oldest.remove()
                }
            }
            result
        } catch (failure: Exception) {
            future.cancel(true)
            Outcome.Failure(FeatureError.InvalidParameters(failure.message ?: "Thumbnail failed"))
        }
    }

    fun invalidate(documentId: String) = synchronized(cache) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.first == documentId) { bytes -= entry.value.byteSize; iterator.remove() }
        }
    }
    fun residentBytes(): Int = synchronized(cache) { bytes }
    override fun close() { executor.shutdownNow(); synchronized(cache) { cache.clear(); bytes = 0 } }
}
