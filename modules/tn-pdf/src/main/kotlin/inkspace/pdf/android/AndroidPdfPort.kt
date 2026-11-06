package inkspace.pdf.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.*
import inkspace.core.error.SubmitError
import inkspace.core.id.AssetId
import inkspace.core.outcome.Outcome
import inkspace.core.pdf.PdfReply
import inkspace.core.ports.PdfPort
import inkspace.core.ports.PdfRequest
import inkspace.core.ports.RequestId
import inkspace.pdf.PdfSession
import inkspace.pdf.StagedPdf
import inkspace.pdf.stagePdf
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One app-owned bridge serves all PDF documents; asset resolution is supplied by the runtime/store. */
class AndroidPdfPort(
    context: Context,
    private val openAsset: (AssetId) -> ParcelFileDescriptor,
    private val onReply: ((RequestId, PdfReply) -> Unit)? = null,
    private val onServiceDied: (() -> Unit)? = null
) : PdfPort, AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("pdf-client").apply { start() }
    private val session = PdfSession()
    private val pending = mutableMapOf<RequestId, PdfSession.Work>()
    private val canceled = mutableSetOf<RequestId>()
    private val closed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private var remote: Messenger? = null
    private var bound = false
    private val handler = object : Handler(thread.looper) {
        override fun handleMessage(message: Message) {
            if (message.what == PDF_REPLY) receive(message.data)
        }
    }
    private val recipient = Messenger(handler)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            handler.post { if (!closed.get()) { remote = Messenger(service); drain() } }
        }
        override fun onServiceDisconnected(name: ComponentName) { handler.post { died() } }
        override fun onBindingDied(name: ComponentName) { handler.post { died() } }
        override fun onNullBinding(name: ComponentName) { handler.post { died() } }
    }

    fun connect() {
        if (closed.get() || !connecting.compareAndSet(false, true)) return
        main.post {
            if (!closed.get()) {
                bound = context.bindService(Intent(context, PdfIsolatedService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) handler.post { died() }
            }
        }
    }

    override fun trySubmit(request: PdfRequest): Outcome<RequestId, SubmitError> {
        if (closed.get()) return Outcome.Failure(SubmitError.ShutdownInProgress("PDF bridge closed"))
        val result = session.trySubmit(request)
        if (result.isSuccess) { connect(); handler.post { drain() } }
        return result
    }

    /** Cancellation suppresses callbacks; native calls finish on their owner and release resources. */
    fun cancel(id: RequestId) { handler.post { if (session.isOutstanding(id)) canceled += id } }
    fun poll(): Pair<RequestId, PdfReply>? = session.poll()

    private fun drain() {
        val service = remote ?: return
        while (!closed.get()) {
            val work = session.take() ?: return
            pending[work.id] = work
            if (canceled.remove(work.id)) {
                finish(work, PdfReply.Failed("PDF request canceled"), deliver = false)
                continue
            }
            var descriptor: ParcelFileDescriptor? = null
            try {
                val encoded = pdfJson.encodeToString(PdfRequest.serializer(), work.request)
                require(encoded.length <= 65536)
                if (work.request is PdfRequest.Open) {
                    descriptor = openAsset(work.request.asset)
                    require(descriptor.statSize in 1..268_435_456L) { "PDF source must be staged, seekable and within 256 MiB" }
                }
                val bundle = Bundle().apply {
                    putString("request", encoded)
                    putLong("id", work.id.value)
                    putLong("epoch", work.epoch)
                    descriptor?.let { putParcelable("source", it) }
                }
                service.send(Message.obtain(null, PDF_REQUEST).apply { data = bundle; replyTo = recipient })
            } catch (failure: Exception) {
                finish(work, PdfReply.Failed((failure.message ?: "PDF service unavailable").take(1024)))
            } finally { descriptor?.close() }
        }
    }

    private fun receive(bundle: Bundle) {
        val id = RequestId(bundle.getLong("id"))
        val work = pending[id]
        if (work == null || work.epoch != bundle.getLong("epoch") || closed.get()) { closeReplyBuffer(bundle); return }
        val reply = try { decodeReply(bundle) } catch (_: Exception) { PdfReply.Failed("Invalid PDF service response") }
        val deliver = !canceled.remove(id)
        finish(work, reply, deliver)
        if (!deliver && reply is PdfReply.Opened) {
            val cleanup = session.trySubmit(PdfRequest.Close(reply.handle))
            cleanup.getOrNull()?.let { canceled += it }
        }
        drain()
    }

    private fun finish(work: PdfSession.Work, reply: PdfReply, deliver: Boolean = true) {
        pending.remove(work.id)
        val result = session.complete(work, reply)
        if (result.isFailure) {
            val fallback = PdfReply.Failed("Stale or invalid PDF response")
            val accepted = session.complete(work, fallback)
            if (accepted.isSuccess) {
                if (!deliver) session.discardReply(work.id)
                else if (onReply != null) { session.discardReply(work.id); runCatching { onReply?.invoke(work.id, fallback) } }
            } else if (deliver && !closed.get()) runCatching { onReply?.invoke(work.id, fallback) }
            return
        }
        if (!deliver) session.discardReply(work.id)
        else if (onReply != null) {
            // Session completion order equals this handler's callback order.
            val completed = session.poll()
            if (deliver && completed != null) runCatching { onReply?.invoke(completed.first, completed.second) }
        }
    }

    private fun died() {
        if (closed.get()) return
        remote = null
        val failed = session.outstandingIds()
        pending.clear()
        canceled.clear()
        session.serviceDied()
        failed.forEach { runCatching { onReply?.invoke(it, PdfReply.Failed("PDF service restarted; reopen the asset")) } }
        runCatching { onServiceDied?.invoke() }
        main.post {
            if (bound) { context.unbindService(connection); bound = false }
            connecting.set(false)
            if (!closed.get()) connect()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.close()
        main.post { if (bound) { context.unbindService(connection); bound = false } }
        handler.post { pending.clear(); canceled.clear(); remote = null; session.close(); thread.quitSafely() }
    }
}

/** Gallery/receive-intent/SAF imports all converge on the same staged immutable source path. */
fun stagePdfUri(context: Context, uri: Uri, destination: File, maxBytes: Long = 268_435_456L): Outcome<StagedPdf, SubmitError> = try {
    val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "PDF provider returned no stream" }
    input.use { Outcome.Success(stagePdf(it, destination, maxBytes)) }
} catch (failure: Exception) {
    Outcome.Failure(SubmitError.InvalidPayload((failure.message ?: "PDF permission or provider unavailable").take(1024)))
}
