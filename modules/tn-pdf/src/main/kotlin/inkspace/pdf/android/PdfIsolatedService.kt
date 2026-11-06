package inkspace.pdf.android

import android.app.Service
import android.content.Intent
import android.os.*
import inkspace.core.pdf.PdfReply
import inkspace.core.ports.PdfRequest

/** No PDFium class is constructed in the application process. */
class PdfIsolatedService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    private var binding: PdfiumBinding? = null
    private var client: IBinder? = null
    private var death: IBinder.DeathRecipient? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("pdfium-binding").apply { start() }
        handler = object : Handler(worker.looper) {
            override fun handleMessage(message: Message) { receive(message) }
        }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun receive(message: Message) {
        val bundle = message.data
        bundle.classLoader = ParcelFileDescriptor::class.java.classLoader
        @Suppress("DEPRECATION") val descriptor = bundle.getParcelable<ParcelFileDescriptor>("source")
        var fdOwnedByBinding = false
        try {
            val recipient = requireNotNull(message.replyTo)
            if (client != recipient.binder) {
                death?.let { callback -> client?.unlinkToDeath(callback, 0) }
                client = recipient.binder
                death = IBinder.DeathRecipient { handler.post { resetClient() } }.also { recipient.binder.linkToDeath(it, 0) }
            }
            if (message.what == PDF_CANCEL) return // replies are rejected by request id at the client
            require(message.what == PDF_REQUEST)
            val id = bundle.getLong("id")
            val epoch = bundle.getLong("epoch")
            require(id > 0 && epoch > 0)
            val payload = requireNotNull(bundle.getString("request"))
            require(payload.length <= 65536)
            val request = pdfJson.decodeFromString(PdfRequest.serializer(), payload)
            if (request !is PdfRequest.Open) require(descriptor == null)
            val adapter = binding ?: PdfiumBinding().also { binding = it }
            fdOwnedByBinding = request is PdfRequest.Open
            val result = adapter.execute(request, descriptor, epoch)
            val response = try { encodeReply(result) } catch (_: Exception) { encodeReply(PdfReply.Failed("PDF reply exceeds IPC limits")) }
            response.putLong("id", id)
            response.putLong("epoch", epoch)
            try { recipient.send(Message.obtain(null, PDF_REPLY).apply { data = response }) }
            finally { closeReplyBuffer(response) }
        } catch (e: Exception) {
            android.util.Log.e("PdfIsolatedService", "Failed to process message", e)
            val response = encodeReply(PdfReply.Failed("PDF service rejected request: ${e.message}"))
            response.putLong("id", bundle.getLong("id"))
            response.putLong("epoch", bundle.getLong("epoch"))
            runCatching { message.replyTo?.send(Message.obtain(null, PDF_REPLY).apply { data = response }) }
        } finally { if (!fdOwnedByBinding) descriptor?.close() }
    }

    private fun resetClient() {
        binding?.close()
        binding = null
        death?.let { callback -> client?.unlinkToDeath(callback, 0) }
        client = null
        death = null
    }

    override fun onDestroy() {
        handler.post { resetClient(); worker.quitSafely() }
        super.onDestroy()
    }
}
