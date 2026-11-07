package inkspace.core.workspace

import inkspace.core.unit.DocRect
import kotlinx.serialization.Serializable

@Serializable
data class ExportOptions(
    val mode: String = "Native", val scope: String = "full",
    val firstPage: Int = 1, val lastPage: Int = 1,
    val region: DocRect? = null, val layerIds: Set<String>? = null,
    val objectIds: Set<String> = emptySet(), val visibleOnly: Boolean = false, val stickyExpanded: Boolean = true, val tapeRevealed: Boolean = false
) {
    init {
        require(mode in setOf("Native","VectorPdf","FlattenedPdf","StandardAnnotations","PNG","JPEG"))
        require(scope in setOf("full","page","range","region","selection","template","objects","visible"))
        require(firstPage > 0 && lastPage >= firstPage)
        require(region == null || region.width > 0 && region.height > 0)
    }
}
