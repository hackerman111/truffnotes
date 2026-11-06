package inkspace.content

import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.payload.*
import inkspace.core.unit.*
import java.nio.file.Files
import kotlin.test.*
import org.junit.jupiter.api.Test

class F11ExtendedTest {
    @Test fun streaming_stager_is_content_addressed_and_bounded() {
        val dir = Files.createTempDirectory("content-stage").toFile()
        try {
            val stager = StreamingAssetStager(dir)
            val bytes = ByteArray(20000) { it.toByte() }
            val asset = stager.stage(bytes.inputStream(), 30000, "image")
            assertContentEquals(bytes, stager.open(asset.assetId).use { it.readBytes() })
            assertFailsWith<IllegalArgumentException> { stager.stage(bytes.inputStream(), 10000, "image") }
        } finally { dir.deleteRecursively() }
    }
    @Test fun freehand_crop_and_sticky_attachment_single_transform() {
        val state = DocumentState.createInitial(DocumentId("content"))
        val image = obj("image", ImagePayload(AssetId("a"), 100, 100))
        val sticky = obj("sticky", StickyPayload(1, ""))
        state.putResidentObjectDirect(image); state.putResidentObjectDirect(sticky)
        val crop = ContentPlanner.plan(state, ContentAction.CropMask(image.id, listOf(DocPoint(1.0, 1.0), DocPoint(90.0, 1.0), DocPoint(45.0, 90.0)))).getOrNull()!!
        assertEquals(1, crop.mutations.size)
        val attach = ContentPlanner.plan(state, ContentAction.AttachSticky(sticky.id, setOf(image.id))).getOrNull()!!
        val payload = (attach.mutations.single() as inkspace.core.mutation.UpdatePayload).newPayload as StickyPayload
        assertEquals(listOf(image.id), payload.attachedIds)
        assertEquals(image.transform, state.getObject(image.id)!!.transform)
    }
    @Test fun elements_crud_and_reload_roundtrip() {
        val dir = Files.createTempDirectory("elements").toFile()
        try {
            val file = java.io.File(dir, "elements.json")
            val repo = ElementsRepository(file)
            repo.createCollection("c", "Collection")
            val obj = obj("a", TextPayload("editable"))
            repo.save("c", "e", "Name", ObjectBundle(1, listOf(obj), setOf(obj.id)))
            repo.createCollection("d", "Other")
            repo.move("e", "d")
            repo.renameCollection("d", "Renamed")
            val reloaded = ElementsRepository(file)
            assertEquals("Renamed", reloaded.collections().last().name)
            assertEquals("editable", (reloaded.bundle("e").objects.single().payload as TextPayload).text)
            reloaded.deleteElement("e")
            assertTrue(reloaded.elements("d").isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun gif_preflight_rejects_bomb_before_movie_decode() {
        val bytes = byteArrayOf(71, 73, 70, 56, 57, 97, -1, 127, -1, 127, 0, 0, 0, 59)
        assertFailsWith<IllegalArgumentException> { GifPreflight.inspect(bytes.inputStream()) }
    }
    private fun obj(id: String, payload: ObjectPayload) = SceneObject(ObjectId(id), SpaceId("space-1"), LayerId("layer-1"), DocRect(0.0, 0.0, 100.0, 100.0), payload)
}
