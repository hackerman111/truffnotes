package inkspace.library

import inkspace.core.workspace.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class LibraryTest {
    @TempDir lateinit var dir: Path

    @Test fun collectionPublicationIsAtomicAndValidatesHierarchy() {
        val file=dir.resolve("collection.json").toFile()
        val library=LibraryService(file)
        val batch=listOf(LibraryEntry("folder","Folder",isFolder=true),LibraryEntry("one","One","folder"),LibraryEntry("two","Two","folder",template=true))
        assertTrue(library.submit(LibraryAction.Import(batch)).isSuccess)
        assertEquals(batch,LibraryService(file).entries())
        val before=file.readBytes()
        assertFalse(library.submit(LibraryAction.Import(listOf(LibraryEntry("new","New"),LibraryEntry("bad","Bad","missing")))).isSuccess)
        assertContentEquals(before,file.readBytes())
        assertFalse(library.submit(LibraryAction.Import(batch)).isSuccess)
        assertContentEquals(before,file.readBytes())
    }
    @Test fun operationsPersistAndBulkIsAtomic() {
        val file = dir.resolve("library.json").toFile()
        val library = LibraryService(file)
        assertTrue(library.submit(LibraryAction.Create("doc", "Notes")).isSuccess)
        assertTrue(library.submit(LibraryAction.Favorite(setOf("doc"), true)).isSuccess)
        assertFalse(library.submit(LibraryAction.Trash(setOf("doc", "missing"))).isSuccess)
        assertFalse(library.entries().single().trashed)
        assertEquals("Notes", LibraryService(file).entries().single().name)
        assertEquals(1, library.query(LibrarySection.FAVORITES).size)
        assertTrue(library.submit(LibraryAction.Trash(setOf("doc"))).isSuccess)
        assertEquals(0, library.query(LibrarySection.DOCUMENTS).size)
        assertEquals(1, LibraryService(file).query(LibrarySection.TRASH).size)
        assertTrue(library.submit(LibraryAction.Restore(setOf("doc"))).isSuccess)
    }

    @Test fun foldersRejectCyclesAndFilesRemainAfterReload() {
        val library = LibraryService(dir.resolve("library.json").toFile())
        library.submit(LibraryAction.Create("a", "Folder", isFolder = true))
        library.submit(LibraryAction.Create("b", "Child", parentId = "a", isFolder = true))
        assertFalse(library.submit(LibraryAction.Move(setOf("a"), "b")).isSuccess)
        assertFalse(library.submit(LibraryAction.Create("bad", "", parentId = "missing")).isSuccess)
        assertEquals(2, library.entries().size)
    }

    @Test fun oversizedEncodedCatalogDoesNotReplaceReadableFile() {
        val file = dir.resolve("library.json").toFile()
        val library = LibraryService(file)
        assertTrue(library.submit(LibraryAction.Create("notes", "Notes")).isSuccess)
        val before = file.readBytes()
        assertFalse(library.submit(LibraryAction.Create("large", "Too large", defaults = NotebookDefaults(cover = "x".repeat(8 * 1024 * 1024)))).isSuccess)
        assertContentEquals(before, file.readBytes())
        assertEquals("notes", LibraryService(file).entries().single().id)
    }

    @Test fun folderTrashHidesDescendantsAndRestoreKeepsIndividuallyTrashedChild() {
        val library = LibraryService(dir.resolve("library.json").toFile())
        library.submit(LibraryAction.Create("folder", "Folder", isFolder = true))
        library.submit(LibraryAction.Create("a", "A", parentId = "folder"))
        library.submit(LibraryAction.Create("b", "B", parentId = "folder"))
        library.submit(LibraryAction.Favorite(setOf("a"), true))
        library.submit(LibraryAction.Opened("a", 1))
        library.submit(LibraryAction.Trash(setOf("b")))
        library.submit(LibraryAction.Trash(setOf("folder")))
        assertTrue(library.query(LibrarySection.FAVORITES).isEmpty())
        assertTrue(library.query(LibrarySection.RECENT).isEmpty())
        assertEquals(3, library.query(LibrarySection.TRASH).size)
        assertFalse(library.submit(LibraryAction.Restore(setOf("a"))).isSuccess)
        library.submit(LibraryAction.Restore(setOf("folder")))
        assertEquals(setOf("folder", "a"), library.query(LibrarySection.DOCUMENTS).map { it.id }.toSet())
        assertEquals("b", library.query(LibrarySection.TRASH).single().id)
    }

    @Test fun pathTraversalIdsAreRejectedOnCreateAndReload() {
        val file = dir.resolve("library.json").toFile()
        val library = LibraryService(file)
        assertFalse(library.submit(LibraryAction.Create("../escape", "Escape")).isSuccess)
        assertTrue(library.submit(LibraryAction.Create("notes", "Notes")).isSuccess)
        file.writeText(file.readText().replace("\"notes\"", "\"../escape\""))
        assertFailsWith<IllegalArgumentException> { LibraryService(file) }
    }

    @Test fun templatesAndDefaultsPersistWithBulkValidation() {
        val file = dir.resolve("library.json").toFile()
        val library = LibraryService(file)
        library.submit(LibraryAction.Create("notes", "Notes"))
        assertTrue(library.submit(LibraryAction.Template(setOf("notes"), true)).isSuccess)
        val defaults = NotebookDefaults(paper = "grid", cover = "blue", widthPt = 420.0, heightPt = 595.0, horizontalScroll = true)
        assertTrue(library.submit(LibraryAction.Defaults("notes", defaults)).isSuccess)
        assertEquals(defaults, LibraryService(file).query(LibrarySection.TEMPLATES).single().defaults)
        assertFalse(library.submit(LibraryAction.Template(setOf("notes", "missing"), false)).isSuccess)
        assertEquals(1, library.query(LibrarySection.TEMPLATES).size)
    }
}
