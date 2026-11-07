package inkspace.exchange

import inkspace.core.outcome.Outcome
import inkspace.core.ports.JobId
import java.util.UUID
import java.util.concurrent.Executor

enum class ExportMode { Native, VectorPdf, FlattenedPdf, StandardAnnotations, PNG, JPEG }
enum class ExportSource { Durable, Recovery }
data class ExchangeRequest(val source: ExportSource, val mode: ExportMode, val output: ExportOutput)
sealed interface ExchangeError {
    data class Unsupported(val mode: ExportMode) : ExchangeError
    data class Failed(val detail: String) : ExchangeError
}
interface ExchangePorts {
    val executor: Executor
    /** Acquire on Core/IO as appropriate, freezing one revision and pinning its pending assets. */
    fun acquire(source: ExportSource): SnapshotReader
    fun finished(job: JobId, result: Outcome<Unit, ExchangeError>)
    fun canceled(job: JobId): Boolean = false
}

object Exchange {
    fun start(request: ExchangeRequest, ports: ExchangePorts): Outcome<JobId, ExchangeError> {
        if (request.mode != ExportMode.Native) return Outcome.Failure(ExchangeError.Unsupported(request.mode))
        if (request.output.replacesExisting && !request.output.atomicReplacement)
            return Outcome.Failure(ExchangeError.Failed("Select a fresh output URI"))
        val id = JobId(UUID.randomUUID().toString())
        return try {
            ports.executor.execute {
                val result = try {
                    ports.acquire(request.source).use { reader ->
                        SafeExport.write(request.output) { output ->
                            NativeArchive.write(reader, output, canceled = { ports.canceled(id) })
                        }
                    }
                    Outcome.Success(Unit)
                } catch (e: Exception) { Outcome.Failure(ExchangeError.Failed(e.message ?: "Export failed")) }
                ports.finished(id, result)
            }
            Outcome.Success(id)
        } catch (e: Exception) { Outcome.Failure(ExchangeError.Failed(e.message ?: "Export queue rejected")) }
    }
}
