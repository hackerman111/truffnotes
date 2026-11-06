package inkspace.core.pages

import inkspace.core.id.AssetId
import inkspace.core.unit.DocRect
import inkspace.core.pdf.PdfPageRef
import kotlinx.serialization.Serializable

@Serializable enum class PaperGrid { BLANK, RULED, SQUARE, DOT, MILLIMETER, ISOMETRIC }
@Serializable data class GridSpec(val kind: PaperGrid = PaperGrid.BLANK, val spacingPt: Double = 24.0, val color: Int = 0xFFCBD5E1.toInt()) {
    init { require(spacingPt.isFinite() && spacingPt in 1.0..1000.0) }
}
@Serializable data class TemplateDescriptor(val id: String, val version: Int, val name: String, val grid: GridSpec = GridSpec(), val asset: AssetId? = null) {
    init { require(id.isNotBlank() && version > 0 && name.length <= 1024) }
}
@Serializable data class OutlineNode(val id: String, val title: String, val children: List<OutlineNode> = emptyList()) {
    init { require(id.isNotBlank() && title.length <= 4096 && children.size <= 4096) }
    fun frozen(): OutlineNode = copy(children = java.util.List.copyOf(children.map { it.frozen() }))
}
@Serializable data class FrameDescriptor(val id: String, val name: String, val bounds: DocRect)
@Serializable data class PageMetadata(
    val backgroundColor: Int = -1,
    val template: TemplateDescriptor? = null,
    val cover: String = "plain",
    val bookmarked: Boolean = false,
    val outline: List<OutlineNode> = emptyList(),
    val frames: List<FrameDescriptor> = emptyList(),
    val pdfBackground: PdfPageRef? = null,
    val rotation: Int = 0
) {
    init {
        require(cover.length <= 1024 && rotation in listOf(0,90,180,270))
        var count = 0
        val ids = HashSet<String>()
        fun visit(nodes: List<OutlineNode>, depth: Int) {
            require(depth <= 32) { "Outline exceeds 32 levels" }
            for (node in nodes) { require(++count <= 4096 && ids.add(node.id)) { "Outline size or duplicate ID" }; if(node.children.isNotEmpty()) visit(node.children, depth+1) }
        }
        visit(outline,1)
        require(frames.size <= 4096 && frames.map { it.id }.toSet().size == frames.size)
    }
    fun frozen(): PageMetadata = copy(outline=java.util.List.copyOf(outline.map { it.frozen() }), frames=java.util.List.copyOf(frames))
}
