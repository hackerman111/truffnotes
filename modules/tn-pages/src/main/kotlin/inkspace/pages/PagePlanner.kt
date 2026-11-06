package inkspace.pages

import inkspace.core.error.FeatureError
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.mutation.*
import inkspace.core.objects.PlanContext
import inkspace.core.outcome.Outcome
import inkspace.core.read.DocumentRead
import inkspace.core.unit.DocRect

enum class ResizePolicy { KEEP_GEOMETRY, SCALE_CONTENT, ALLOW_OUTSIDE }
data class PageResizePreview(val page: PageDescriptor, val transform: AffineTransform2D, val outsideIds: List<ObjectId>)

sealed interface PageAction {
    data class Duplicate(val page: PageId, val prefix: String, val index: Int = -1) : PageAction
    data class Rotate(val page: PageId, val degrees: Int) : PageAction
    data class SetMetadata(val page: PageId, val metadata: inkspace.core.pages.PageMetadata) : PageAction
    data class Bulk(val actions: List<PageAction>) : PageAction
    data class DuplicateLayer(val layer: LayerId, val prefix: String) : PageAction
    data class GroupLayers(val layers: Set<LayerId>, val groupId: String?) : PageAction
    data class MoveObjects(val ids: Set<ObjectId>, val target: LayerId) : PageAction
    data class Add(val page: PageDescriptor, val layer: LayerDescriptor, val index: Int = -1) : PageAction
    data class Delete(val page: PageId) : PageAction
    data class Clear(val page: PageId) : PageAction
    data class Reorder(val ids: List<PageId>) : PageAction
    data class Resize(val page: PageId, val widthPt: Double, val heightPt: Double, val policy: ResizePolicy) : PageAction
    data class AddLayer(val layer: LayerDescriptor, val index: Int = -1) : PageAction
    data class DeleteLayer(val layer: LayerId) : PageAction
    data class LockLayer(val layer: LayerId, val locked: Boolean) : PageAction
    data class StyleLayer(val layer: LayerId, val opacity: Float, val visible: Boolean, val name: String? = null) : PageAction
    data class ReorderLayers(val ids: List<LayerId>) : PageAction
    data class MergeLayers(val source: LayerId, val target: LayerId) : PageAction
}

object PagePlanner {
    private val allBounds = DocRect(-1e9, -1e9, 1e9, 1e9)

    private fun objects(read: DocumentRead, layers: Set<LayerId>): List<SceneObject> {
        // A cold header cannot be silently omitted from destructive/geometry edits.
        return read.objectIdsInLayers(layers).map { requireNotNull(read.getObject(it)) { "Object ${it.value} is not resident" } }
    }

    fun previewResize(read: DocumentRead, action: PageAction.Resize): Outcome<PageResizePreview, FeatureError> = try {
        val page = requireNotNull(read.getPage(action.page)) { "Page missing" }
        val resized = page.copy(widthPt = action.widthPt, heightPt = action.heightPt)
        val matrix = if (action.policy == ResizePolicy.SCALE_CONTENT)
            AffineTransform2D(m00 = action.widthPt / page.widthPt, m11 = action.heightPt / page.heightPt)
        else AffineTransform2D.IDENTITY
        val items = objects(read, read.layers.filter { it.pageId == page.id }.map { it.id }.toSet())
        val outside = items.filter {
            val b = matrix.transformRect(it.bounds)
            b.left < 0 || b.top < 0 || b.right > resized.widthPt || b.bottom > resized.heightPt
        }.map { it.id }
        Outcome.Success(PageResizePreview(resized, matrix, outside))
    } catch (e: IllegalArgumentException) {
        Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Invalid resize"))
    }

    fun plan(read: DocumentRead, action: PageAction, ctx: PlanContext = PlanContext()): Outcome<EditPlan, FeatureError> = try {
        if (action is PageAction.Bulk) {
            require(action.actions.isNotEmpty() && action.actions.size <= 1000 && action.actions.none { it is PageAction.Bulk }) { "Invalid batch size or nesting" }
            val snapshot = when(read) { is DocumentState -> read.snapshot(); is DocumentSnapshot -> read; else -> throw IllegalArgumentException("Batch requires complete document snapshot") }
            val staged = DocumentState.fromSnapshot(snapshot)
            val steps = ArrayList<Mutation>()
            for(step in action.actions) {
                val plan = plan(staged,step,ctx).getOrNull() ?: throw IllegalArgumentException("Invalid batch item: $step")
                val valid = validatePlan(staged,plan,ctx.limits).getOrNull() ?: throw IllegalArgumentException("Invalid batch item")
                require(applyPlan(staged,valid,ctx.limits).isSuccess)
                steps.addAll(plan.mutations)
            }
            val batch = EditPlan(read.version,mutations=steps,gesture=ctx.gestureId,label=UndoLabel("Page batch"))
            return when(val checked=validatePlan(read,batch,ctx.limits)) {
                is Outcome.Success -> Outcome.Success(batch)
                is Outcome.Failure -> Outcome.Failure(FeatureError.InvalidParameters(checked.error.toString()))
            }
        }
        val mutations = mutableListOf<Mutation>()
        fun editable(items: List<SceneObject>) {
            require(items.none { it.isLocked || read.isLayerLocked(it.layer) }) { "Locked content" }
        }
        fun clearLayers(layers: List<LayerDescriptor>) {
            require(layers.none { it.isLocked }) { "Locked layer" }
            val items = objects(read, layers.map { it.id }.toSet())
            editable(items)
            // Remove groups first so their members are no longer referenced.
            items.sortedByDescending { it.payload is inkspace.core.payload.GroupPayload }.forEach { mutations += RemoveObject(it.id) }
        }
        fun cloneItems(items: List<SceneObject>, prefix: String, layerMap: Map<LayerId,LayerId>, space: SpaceId, pageMap: Map<PageId,PageId> = emptyMap()) {
            require(prefix.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid copy prefix" }
            val ids = items.associate { it.id to ObjectId("$prefix-${it.id.value}") }
            for (obj in items) {
                val payload = when(val payload = obj.payload) {
                    is inkspace.core.payload.GroupPayload -> payload.copy(members=payload.members.map { ids[it] ?: throw IllegalArgumentException("Incomplete group copy") })
                    is inkspace.core.payload.ConnectorPayload -> payload.copy(sourceId=ids[payload.sourceId] ?: payload.sourceId,targetId=ids[payload.targetId] ?: payload.targetId)
                    is inkspace.core.payload.StickyPayload -> payload.copy(attachedIds=payload.attachedIds.map { ids[it] ?: throw IllegalArgumentException("Incomplete Sticky copy") })
                    is inkspace.core.payload.LinkAnchorPayload -> payload.copy(targetPage=pageMap[payload.targetPage] ?: payload.targetPage)
                    else -> payload
                }
                mutations += InsertObject(space,layerMap.getValue(obj.layer),ids.getValue(obj.id),payload,obj.bounds,obj.transform,obj.style,obj.isLocked)
            }
        }
        when (action) {
            is PageAction.Bulk -> throw IllegalArgumentException("Nested batch")
            is PageAction.SetMetadata -> {
                val page = requireNotNull(read.getPage(action.page)) { "Page missing" }
                mutations += UpdatePageDescriptor(page.copy(metadata=action.metadata.frozen()))
            }
            is PageAction.Rotate -> {
                require(action.degrees % 90 == 0) { "Rotation must be a quarter turn" }
                val page = requireNotNull(read.getPage(action.page)) { "Page missing" }
                val degrees = ((action.degrees % 360)+360)%360
                val transform = when(degrees) {
                    90 -> AffineTransform2D(0.0,-1.0,page.heightPt,1.0,0.0,0.0)
                    180 -> AffineTransform2D(-1.0,0.0,page.widthPt,0.0,-1.0,page.heightPt)
                    270 -> AffineTransform2D(0.0,1.0,0.0,-1.0,0.0,page.widthPt)
                    else -> AffineTransform2D.IDENTITY
                }
                val items=objects(read,read.layers.filter { it.pageId==page.id }.map { it.id }.toSet()); editable(items)
                items.forEach { mutations += TransformObject(it.id,transform.multiply(it.transform),transform.transformRect(it.bounds)) }
                mutations += UpdatePageDescriptor(page.copy(widthPt=if(degrees%180!=0)page.heightPt else page.widthPt,
                    heightPt=if(degrees%180!=0)page.widthPt else page.heightPt,metadata=page.metadata.copy(rotation=(page.metadata.rotation+degrees)%360)))
            }
            is PageAction.Duplicate -> {
                val page = requireNotNull(read.getPage(action.page)) { "Page missing" }
                val copy = page.copy(id=PageId("${action.prefix}-${page.id.value}"),spaceId=SpaceId("${action.prefix}-${page.spaceId.value}"),metadata=page.metadata.frozen())
                val layers = read.layers.filter { it.pageId==page.id }
                val layerMap=layers.associate { it.id to LayerId("${action.prefix}-${it.id.value}") }
                mutations += AddPage(copy,if(action.index<0)read.pages.indexOf(page)+1 else action.index)
                layers.forEach { mutations += AddLayer(it.copy(id=layerMap.getValue(it.id),pageId=copy.id)) }
                cloneItems(objects(read,layerMap.keys),action.prefix,layerMap,copy.spaceId,mapOf(page.id to copy.id))
            }
            is PageAction.DuplicateLayer -> {
                val layer=requireNotNull(read.getLayer(action.layer)) { "Layer missing" }
                val page=requireNotNull(read.getPage(layer.pageId))
                val target=LayerId("${action.prefix}-${layer.id.value}")
                mutations += AddLayer(layer.copy(id=target,name="${layer.name} copy"),read.layers.indexOf(layer)+1)
                cloneItems(objects(read,setOf(layer.id)),action.prefix,mapOf(layer.id to target),page.spaceId)
            }
            is PageAction.GroupLayers -> {
                require(action.layers.isNotEmpty() && (action.groupId==null || action.groupId.isNotBlank()))
                val layers=action.layers.map { requireNotNull(read.getLayer(it)) { "Layer missing" } }
                require(layers.map { it.pageId }.toSet().size==1 && layers.none { it.isLocked }) { "Layers incompatible or locked" }
                layers.forEach { mutations += UpdateLayerDescriptor(it.copy(groupId=action.groupId)) }
            }
            is PageAction.MoveObjects -> {
                val layer=requireNotNull(read.getLayer(action.target)) { "Target layer missing" }
                val page=requireNotNull(read.getPage(layer.pageId))
                val visited=LinkedHashSet<ObjectId>()
                fun move(id:ObjectId) {
                    if(!visited.add(id)) return
                    val obj=requireNotNull(read.getObject(id)) { "Object missing or not resident" }; editable(listOf(obj))
                    when(val payload=obj.payload) {
                        is inkspace.core.payload.GroupPayload -> payload.members.forEach(::move)
                        is inkspace.core.payload.StickyPayload -> payload.attachedIds.forEach(::move)
                        else -> Unit
                    }
                    mutations += RelocateObject(id,layer.id,page.spaceId)
                }
                action.ids.forEach(::move)
            }
            is PageAction.Add -> {
                require(action.layer.pageId == action.page.id) { "Layer belongs to another page" }
                mutations += AddPage(action.page, action.index)
                mutations += AddLayer(action.layer)
            }
            is PageAction.Reorder -> mutations += ReorderPage(action.ids.toList())
            is PageAction.Delete, is PageAction.Clear -> {
                val id = if (action is PageAction.Delete) action.page else (action as PageAction.Clear).page
                requireNotNull(read.getPage(id)) { "Page missing" }
                val layers = read.layers.filter { it.pageId == id }
                clearLayers(layers)
                if (action is PageAction.Delete) {
                    layers.forEach { mutations += RemoveLayer(it.id) }
                    mutations += RemovePage(id)
                }
            }
            is PageAction.Resize -> {
                val preview = previewResize(read, action).getOrNull() ?: throw IllegalArgumentException("Invalid resize or nonresident content")
                if (action.policy == ResizePolicy.SCALE_CONTENT) {
                    val items = objects(read, read.layers.filter { it.pageId == action.page }.map { it.id }.toSet())
                    editable(items)
                    for (item in items) mutations += TransformObject(item.id,
                        preview.transform.multiply(item.transform), preview.transform.transformRect(item.bounds))
                }
                mutations += ResizePage(action.page, action.widthPt, action.heightPt)
            }
            is PageAction.AddLayer -> mutations += AddLayer(action.layer, action.index)
            is PageAction.DeleteLayer -> {
                val layer = requireNotNull(read.getLayer(action.layer)) { "Layer missing" }
                clearLayers(listOf(layer))
                mutations += RemoveLayer(action.layer)
            }
            is PageAction.LockLayer -> mutations += SetLayerLock(action.layer, action.locked)
            is PageAction.StyleLayer -> mutations += SetLayerStyle(action.layer, action.opacity, action.visible, action.name)
            is PageAction.ReorderLayers -> mutations += ReorderLayer(action.ids.toList())
            is PageAction.MergeLayers -> {
                // Group opacity is not equivalent to multiplying every child opacity.
                require(action.source != action.target) { "Cannot merge a layer into itself" }
                val source = requireNotNull(read.getLayer(action.source)) { "Source missing" }
                val target = requireNotNull(read.getLayer(action.target)) { "Target missing" }
                require(source.pageId == target.pageId && !source.isLocked && !target.isLocked) { "Layers incompatible or locked" }
                require(source.opacity == 1f && target.opacity == 1f && source.isVisible == target.isVisible) { "Merge would change compositing semantics" }
                val pageLayers = read.layers.filter { it.pageId == source.pageId }
                require(kotlin.math.abs(pageLayers.indexOf(source) - pageLayers.indexOf(target)) == 1) { "Only adjacent layers can merge" }
                val items = objects(read, setOf(source.id, target.id))
                editable(items)
                require(items.none { it.payload is inkspace.core.payload.GroupPayload }) { "Grouped merge requires hierarchy preview" }
                val ordered = items.sortedWith(compareBy<SceneObject> { pageLayers.indexOfFirst { layer -> layer.id == it.layer } }.thenBy { it.style.zOrder }.thenBy { it.id.value })
                // Replace both layers' objects to preserve a strict visual order.
                ordered.forEach { mutations += RemoveObject(it.id) }
                ordered.forEachIndexed { index, item -> mutations += InsertObject(item.space, target.id, item.id,
                    item.payload, item.bounds, item.transform, item.style.copy(zOrder = index.toLong()), item.isLocked, item.version + 1) }
                mutations += RemoveLayer(source.id)
            }
        }
        if (mutations.isEmpty()) Outcome.Failure(FeatureError.NoOpAction("Nothing to change"))
        else {
            val plan = EditPlan(read.version, mutations = mutations, gesture = ctx.gestureId, label = UndoLabel("Page/layer edit"))
            when (val checked = validatePlan(read, plan, ctx.limits)) {
                is Outcome.Success -> Outcome.Success(plan)
                is Outcome.Failure -> Outcome.Failure(FeatureError.InvalidParameters(checked.error.toString()))
            }
        }
    } catch (e: IllegalArgumentException) {
        Outcome.Failure(FeatureError.InvalidParameters(e.message ?: "Invalid page action"))
    }
}
