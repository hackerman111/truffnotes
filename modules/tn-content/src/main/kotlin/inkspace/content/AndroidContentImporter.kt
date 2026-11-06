package inkspace.content

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.view.DragEvent
import inkspace.core.content.ContentFailure
import inkspace.core.content.ContentImportResult
import inkspace.core.outcome.Outcome

/** All Android entry routes stage their stream before producing an Image insertion. */
class AndroidContentImporter(private val resolver: ContentResolver, private val staging: StreamingAssetStager,
                             private val decoder: AndroidImageDecoder) {
    fun importUri(uri: Uri): Outcome<ContentImportResult, ContentFailure> = try {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE) { "Unsupported image URI scheme" }
        val staged = (if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: throw IllegalArgumentException("Invalid file URI")
            java.io.FileInputStream(java.io.File(path))
        } else {
            resolver.openInputStream(uri) ?: throw IllegalArgumentException("Provider returned no image stream")
        }).use { staging.stage(it, 268_435_456L, "image") }
        val header = decoder.header(staged.assetId)
        Outcome.Success(ContentImportResult(staged, header.width, header.height, header.exifOrientation))
    } catch (error: Exception) { Outcome.Failure(ContentFailure(error.message ?: "Image import failed")) }
    fun cameraResult(success: Boolean, outputUri: Uri): Outcome<ContentImportResult, ContentFailure> =
        if (success) importUri(outputUri) else Outcome.Failure(ContentFailure("Camera cancelled or permission denied"))
    fun clipboard(clip: ClipData?): Outcome<ContentImportResult, ContentFailure> {
        if (clip == null || clip.itemCount == 0) return Outcome.Failure(ContentFailure("Clipboard contains no image"))
        val uri = clip.getItemAt(0).uri ?: return Outcome.Failure(ContentFailure("Clipboard contains no image URI"))
        return importUri(uri)
    }
    fun drop(event: DragEvent): Outcome<ContentImportResult, ContentFailure> = clipboard(event.clipData)
    fun receive(intent: Intent): Outcome<ContentImportResult, ContentFailure> {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
            ?: return Outcome.Failure(ContentFailure("Intent contains no image URI"))
        return importUri(uri)
    }
    companion object {
        fun galleryIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        fun safIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        fun cameraIntent(outputUri: Uri): Intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outputUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("Camera output", outputUri)
        }
    }
}
