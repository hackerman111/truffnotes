package inkspace.content

import inkspace.core.id.ObjectId
import inkspace.core.model.SceneObject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable data class ElementCollection(val id: String, val name: String)
@Serializable data class SavedElement(val id: String, val collection: String, val name: String,
    val schema: Int, val objects: List<SceneObject>, val roots: Set<ObjectId>)
@Serializable private data class ElementLibrary(val collections: List<ElementCollection> = emptyList(), val elements: List<SavedElement> = emptyList())

/** Every edit is persisted atomically before becoming visible to callers. */
class ElementsRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = false }
    private var state = if (file.exists()) {
        require(file.length() <= MAX_BYTES) { "Elements library exceeds cap" }
        json.decodeFromString<ElementLibrary>(file.readText()).also { validate(it) }
    } else ElementLibrary()
    @Synchronized fun collections(): List<ElementCollection> = java.util.List.copyOf(state.collections)
    @Synchronized fun elements(collection: String): List<SavedElement> = java.util.List.copyOf(state.elements.filter { it.collection == collection }.map { frozen(it) })
    @Synchronized fun bundle(id: String): ObjectBundle {
        val element = state.elements.first { it.id == id }
        return ObjectBundle(element.schema, element.objects, element.roots)
    }
    @Synchronized fun createCollection(id: String, name: String) {
        require(state.collections.none { it.id == id })
        commit(state.copy(collections = state.collections + ElementCollection(id, name)))
    }
    @Synchronized fun renameCollection(id: String, name: String) {
        require(state.collections.any { it.id == id })
        commit(state.copy(collections = state.collections.map { if (it.id == id) it.copy(name = name) else it }))
    }
    @Synchronized fun reorderCollections(ids: List<String>) {
        require(ids.size == state.collections.size && ids.toSet() == state.collections.map { it.id }.toSet())
        commit(state.copy(collections = ids.map { id -> state.collections.first { it.id == id } }))
    }
    @Synchronized fun deleteCollection(id: String) {
        require(state.collections.any { it.id == id })
        commit(state.copy(collections = state.collections.filterNot { it.id == id }, elements = state.elements.filterNot { it.collection == id }))
    }
    @Synchronized fun save(collection: String, id: String, name: String, bundle: ObjectBundle) {
        require(state.elements.none { it.id == id })
        commit(state.copy(elements = state.elements + SavedElement(id, collection, name, bundle.schema, bundle.objects, bundle.rootIds)))
    }
    @Synchronized fun renameElement(id: String, name: String) {
        require(state.elements.any { it.id == id })
        commit(state.copy(elements = state.elements.map { if (it.id == id) it.copy(name = name) else it }))
    }
    @Synchronized fun move(id: String, collection: String) {
        require(state.elements.any { it.id == id })
        commit(state.copy(elements = state.elements.map { if (it.id == id) it.copy(collection = collection) else it }))
    }
    @Synchronized fun reorderElements(collection: String, ids: List<String>) {
        val targets = state.elements.filter { it.collection == collection }
        require(ids.size == targets.size && ids.toSet() == targets.map { it.id }.toSet())
        commit(state.copy(elements = state.elements.filterNot { it.collection == collection } + ids.map { id -> targets.first { it.id == id } }))
    }
    @Synchronized fun deleteElement(id: String) {
        require(state.elements.any { it.id == id })
        commit(state.copy(elements = state.elements.filterNot { it.id == id }))
    }
    private fun frozen(element: SavedElement): SavedElement {
        val bundle = ObjectBundle(element.schema, element.objects, element.roots)
        return element.copy(objects = bundle.objects, roots = bundle.rootIds)
    }
    private fun commit(next: ElementLibrary) {
        validate(next)
        val encoded = json.encodeToString(ElementLibrary.serializer(), next).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES)
        file.absoluteFile.parentFile!!.mkdirs()
        val temp = File.createTempFile("elements-", ".tmp", file.absoluteFile.parentFile)
        try {
            java.io.FileOutputStream(temp).use { it.write(encoded); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            state = next.copy(elements = next.elements.map { frozen(it) })
        } finally { temp.delete() }
    }
    private fun validate(library: ElementLibrary) {
        require(library.collections.size <= 128 && library.elements.size <= 512)
        require(library.collections.map { it.id }.distinct().size == library.collections.size)
        require(library.elements.map { it.id }.distinct().size == library.elements.size)
        require(library.elements.sumOf { it.objects.size } <= 10_000)
        require(library.collections.all { it.id.isNotBlank() && it.id.length <= 128 && it.name.isNotBlank() && it.name.length <= 256 })
        require(library.elements.all { element -> element.name.isNotBlank() && element.name.length <= 256 && element.id.isNotBlank() && element.id.length <= 128 && library.collections.any { it.id == element.collection } })
        library.elements.forEach { ObjectBundle(it.schema, it.objects, it.roots) }
    }
    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}
