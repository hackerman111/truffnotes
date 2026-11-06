package inkspace.core.payload

import inkspace.core.id.AssetId

fun ObjectPayload.referencedAssetIds(): Set<AssetId> = when(this) {
    is ImagePayload -> setOf(asset)
    is PdfObjectPayload -> setOf(page.asset)
    is TextPayload -> buildSet { fontAsset?.let(::add); runs.mapNotNullTo(this) { it.fontAsset } }
    is StickyPayload -> richText?.referencedAssetIds() ?: emptySet()
    is ShapePayload -> label?.referencedAssetIds() ?: emptySet()
    else -> emptySet()
}
