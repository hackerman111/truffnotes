package inkspace.core.model

import inkspace.core.id.DocumentId
import inkspace.core.id.RequestGeneration
import inkspace.core.id.Revision
import inkspace.core.id.SessionEpoch
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentKind {
    Notebook,
    Canvas
}

@Serializable
data class DocVersion(
    val document: DocumentId,
    val revision: Revision
)

@Serializable
data class SessionKey(
    val document: DocumentId,
    val epoch: SessionEpoch
)

@Serializable
data class JobStamp(
    val session: SessionKey,
    val resource: String,
    val contentVersion: Long,
    val generation: RequestGeneration
)
