package inkspace.pdf

import inkspace.core.error.FeatureError
import inkspace.core.id.*
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.PageDescriptor
import inkspace.core.model.ObjectStyle
import inkspace.core.mutation.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.payload.PdfObjectPayload
import inkspace.core.pdf.PdfPageRef
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocRect

sealed interface PdfImportAction {
    data class Pages(val pages: List<PdfPageRef>, val index: Int = -1, val prefix: String = java.util.UUID.randomUUID().toString(), val replaceBlankInitial: Boolean = false) : PdfImportAction {
        val frozenPages: List<PdfPageRef> = java.util.List.copyOf(pages)
    }
    data class Background(val pageId: PageId, val source: PdfPageRef, val prefix: String = java.util.UUID.randomUUID().toString()) : PdfImportAction
    data class OnCanvas(val source: PdfPageRef, val id: ObjectId, val space: SpaceId, val layer: LayerId, val bounds: DocRect) : PdfImportAction
}

/** PDF bytes never enter the mutation graph; annotations remain ordinary independent scene objects. */
object PdfImportPlanner {
    fun plan(read: DocumentRead, action: PdfImportAction, ctx: PlanContext = PlanContext()): Outcome<EditPlan, FeatureError> = try {
        val mutations = mutableListOf<Mutation>()
        fun check(ref: PdfPageRef): PdfPageTransform {
            require(ref.pageIndex in 0..99999)
            return PdfPageTransform(ref.cropBox, ref.rotation)
        }
        fun requireFresh(id: ObjectId, layer: LayerId, page: PageId? = null) {
            require(read.getObject(id) == null && read.getLayer(layer) == null && (page == null || read.getPage(page) == null)) { "Import IDs already exist" }
        }
        when (action) {
            is PdfImportAction.Pages -> {
                require(action.frozenPages.isNotEmpty() && action.frozenPages.size <= 10000)
                require(action.prefix.isNotBlank() && action.prefix.length <= 128)
                val index = if (action.index == -1) read.pages.size else action.index
                require(index in 0..read.pages.size)
                val transforms = action.frozenPages.map(::check)
                if (action.replaceBlankInitial) require(read.pages.size == 1 && read.totalObjectCount == 0) { "Only a fresh blank document may be replaced" }
                action.frozenPages.forEachIndexed { offset, source ->
                    val pageId = PageId("${action.prefix}-page-$offset")
                    val spaceId = SpaceId("${action.prefix}-space-$offset")
                    val layerId = LayerId("${action.prefix}-background-$offset")
                    val inkLayerId = LayerId("${action.prefix}-ink-$offset")
                    val objectId = ObjectId("${action.prefix}-pdf-$offset")
                    requireFresh(objectId, layerId, pageId)
                    require(read.getLayer(inkLayerId) == null)
                    val mapping = transforms[offset]
                    mutations += AddPage(PageDescriptor(pageId, spaceId, mapping.width, mapping.height), index + offset)
                    mutations += AddLayer(LayerDescriptor(layerId, pageId, "PDF background"), 0)
                    val bounds = DocRect(0.0, 0.0, mapping.width, mapping.height)
                    mutations += InsertObject(spaceId, layerId, objectId, PdfObjectPayload(source, bounds), bounds)
                    mutations += SetLayerLock(layerId, true)
                    mutations += AddLayer(LayerDescriptor(inkLayerId, pageId, "Annotations"))
                }
                if (action.replaceBlankInitial) {
                    read.layers.forEach { mutations += RemoveLayer(it.id) }
                    mutations += RemovePage(read.pages.single().id)
                }
            }
            is PdfImportAction.Background -> {
                check(action.source)
                require(action.prefix.isNotBlank() && action.prefix.length <= 128)
                val page = requireNotNull(read.getPage(action.pageId)) { "Page missing" }
                val layerId = LayerId("${action.prefix}-background")
                val objectId = ObjectId("${action.prefix}-pdf")
                requireFresh(objectId, layerId)
                mutations += AddLayer(LayerDescriptor(layerId, page.id, "PDF background"), 0)
                val bounds = DocRect(0.0, 0.0, page.widthPt, page.heightPt)
                mutations += InsertObject(page.spaceId, layerId, objectId, PdfObjectPayload(action.source, bounds), bounds)
                mutations += SetLayerLock(layerId, true)
            }
            is PdfImportAction.OnCanvas -> {
                check(action.source)
                require(read.getObject(action.id) == null)
                val layer = requireNotNull(read.getLayer(action.layer)) { "Layer missing" }
                require(!layer.isLocked && layer.isVisible) { "Target layer is hidden or locked" }
                require(read.getPage(layer.pageId)?.spaceId == action.space)
                require(action.bounds.width > 0 && action.bounds.height > 0)
                mutations += InsertObject(action.space, action.layer, action.id, PdfObjectPayload(action.source, action.bounds), action.bounds,
                    style = ObjectStyle(zOrder = 0))
            }
        }
        Outcome.Success(EditPlan(read.version, mutations = mutations, gesture = ctx.gestureId, label = UndoLabel("Import PDF")))
    } catch (failure: IllegalArgumentException) {
        Outcome.Failure(FeatureError.InvalidParameters(failure.message ?: "Invalid PDF import"))
    }
}
