package inkspace.library

import inkspace.core.workspace.*
import inkspace.core.outcome.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
private data class LibraryCatalog(val entries: List<LibraryEntry> = emptyList(), val operations: List<OperationId> = emptyList())

/** Metadata only; document bytes remain owned by StoragePort. */
class LibraryService(private val file: File) {
    private var catalog = if (file.exists().also { require(file.length() <= 8L * 1024 * 1024) { "Library catalog exceeds budget" } }) Json.decodeFromString<LibraryCatalog>(file.readText()) else LibraryCatalog()

    private val validId = Regex("[A-Za-z0-9_-]{1,128}")

    init {
        require(catalog.entries.size <= 10000 && catalog.entries.map { it.id }.toSet().size == catalog.entries.size)
        require(catalog.entries.all { validId.matches(it.id) && (it.parentId?.let(validId::matches) != false) }) { "Invalid library identifier" }
    }

    private fun effectivelyTrashed(entry: LibraryEntry, byId: Map<String, LibraryEntry>): Boolean {
        var current: LibraryEntry? = entry
        val visited = hashSetOf<String>()
        while (current != null) {
            if (!visited.add(current.id) || current.trashed) return true
            current = current.parentId?.let { byId[it] }
        }
        return false
    }

    @Synchronized fun entries(): List<LibraryEntry> {
        val byId = catalog.entries.associateBy { it.id }
        return catalog.entries.map { it.copy(trashed = effectivelyTrashed(it, byId)) }
    }

    @Synchronized fun query(section: LibrarySection): List<LibraryEntry> = entries().filter {
        when (section) {
            LibrarySection.TRASH -> it.trashed
            LibrarySection.DOCUMENTS -> !it.trashed
            LibrarySection.FAVORITES -> !it.trashed && it.favorite
            LibrarySection.RECENT -> !it.trashed && it.lastOpened > 0
            LibrarySection.TEMPLATES -> !it.trashed && it.template
        }
    }.let { if (section == LibrarySection.RECENT) it.sortedByDescending { e -> e.lastOpened } else it }

    @Synchronized fun submit(action: LibraryAction): Outcome<OperationId, LibraryError> {
        val entries = catalog.entries.associateByTo(linkedMapOf()) { it.id }
        fun invalid(message: String) = Outcome.Failure(LibraryError(message))
        val ids = when (action) {
            is LibraryAction.Create -> emptySet()
            is LibraryAction.Rename -> setOf(action.id)
            is LibraryAction.Move -> action.ids
            is LibraryAction.Favorite -> action.ids
            is LibraryAction.Trash -> action.ids
            is LibraryAction.Restore -> action.ids
            is LibraryAction.Opened -> setOf(action.id)
            is LibraryAction.Template -> action.ids
            is LibraryAction.Defaults -> setOf(action.id)
        }
        if (ids.any { it !in entries }) return invalid("Unknown library item")
        when (action) {
            is LibraryAction.Create -> {
                if (entries.size >= 10000 || !validId.matches(action.id) || action.id in entries || action.name.isBlank() || action.name.length > 1024) return invalid("Invalid name or duplicate ID")
                if (action.parentId != null && entries[action.parentId]?.let { it.isFolder && !effectivelyTrashed(it, entries) } != true) return invalid("Missing folder")
                entries[action.id] = LibraryEntry(action.id, action.name, action.parentId, action.isFolder, defaults = action.defaults)
            }
            is LibraryAction.Rename -> {
                if (action.name.isBlank() || action.name.length > 1024) return invalid("Invalid name")
                entries[action.id] = entries.getValue(action.id).copy(name = action.name)
            }
            is LibraryAction.Move -> {
                if (action.parentId != null && entries[action.parentId]?.let { it.isFolder && !effectivelyTrashed(it, entries) } != true) return invalid("Missing folder")
                var ancestor = action.parentId
                while (ancestor != null) {
                    if (ancestor in action.ids) return invalid("Folder cycle")
                    ancestor = entries[ancestor]?.parentId
                }
                for (id in ids) entries[id] = entries.getValue(id).copy(parentId = action.parentId)
            }
            is LibraryAction.Favorite -> for (id in ids) entries[id] = entries.getValue(id).copy(favorite = action.value)
            is LibraryAction.Trash -> for (id in ids) entries[id] = entries.getValue(id).copy(trashed = true)
            is LibraryAction.Restore -> {
                for (id in ids) {
                    var ancestor = entries.getValue(id).parentId
                    while (ancestor != null) {
                        if (entries[ancestor]?.trashed == true && ancestor !in ids) return invalid("Restore the parent folder first")
                        ancestor = entries[ancestor]?.parentId
                    }
                }
                for (id in ids) entries[id] = entries.getValue(id).copy(trashed = false)
            }
            is LibraryAction.Template -> {
                if (ids.any { entries.getValue(it).isFolder }) return invalid("Folders cannot be templates")
                for (id in ids) entries[id] = entries.getValue(id).copy(template = action.enabled)
            }
            is LibraryAction.Defaults -> entries[action.id] = entries.getValue(action.id).copy(defaults = action.defaults)
            is LibraryAction.Opened -> entries[action.id] = entries.getValue(action.id).copy(lastOpened = action.timestamp)
        }
        val id = OperationId(UUID.randomUUID().toString())
        val updated = LibraryCatalog(entries.values.toList(), (catalog.operations + id).takeLast(1024))
        return try {
            val encoded = Json.encodeToString(updated).toByteArray(Charsets.UTF_8)
            if (encoded.size > 8 * 1024 * 1024) return invalid("Library catalog exceeds budget")
            file.parentFile?.mkdirs()
            val pending = File(file.path + ".pending")
            FileOutputStream(pending).use { out ->
                out.write(encoded)
                out.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            catalog = updated
            Outcome.Success(id)
        } catch (failure: Exception) {
            invalid(failure.message ?: "Cannot save library")
        }
    }
}
