package inkspace.runtime

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import inkspace.exchange.ExportOutput
import java.io.OutputStream

/** URI must be the newly returned ACTION_CREATE_DOCUMENT result, never an existing user file. */
class AndroidExchangeOutput(private val resolver: ContentResolver, private val createdUri: Uri) : ExportOutput {
    override val replacesExisting = false
    override val atomicReplacement = false
    private var opened = false
    private val receipt = inkspace.exchange.OutputReceipt()
    override fun open(): OutputStream {
        check(!opened)
        opened = true
        return receipt.stream(resolver.openOutputStream(createdUri, "w") ?: throw java.io.IOException("Provider refused output"))
    }
    override fun complete() { receipt.verify(resolver.openInputStream(createdUri) ?: throw java.io.IOException("Provider refused output verification")) }
    override fun abort() { if (opened) DocumentsContract.deleteDocument(resolver, createdUri) }
}
