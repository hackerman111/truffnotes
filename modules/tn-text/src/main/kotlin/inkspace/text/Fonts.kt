package inkspace.text

import android.graphics.Typeface
import inkspace.core.id.AssetId
import inkspace.core.content.AssetStagingPort
import inkspace.core.ports.StagedAssetRef
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object FontPreflight {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun validate(bytes: ByteArray) {
        require(bytes.size in 12..MAX_BYTES) { "Font resource size limit" }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val signature = buffer.int
        require(signature == 0x00010000 || signature == 0x4f54544f || signature == 0x74727565) { "Unsupported font container" }
        val tables = buffer.short.toInt() and 0xffff
        require(tables in 1..128 && 12L + tables * 16L <= bytes.size) { "Invalid font table directory" }
        repeat(tables) { index ->
            buffer.position(12 + index * 16 + 8)
            val offset = buffer.int.toLong() and 0xffffffffL
            val length = buffer.int.toLong() and 0xffffffffL
            require(offset <= bytes.size && length <= bytes.size - offset) { "Invalid font table bounds" }
        }
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Imported font bytes are capped, validated and content addressed before platform parsing. */
class FontRepository(private val directory: File, private val assets: AssetStagingPort? = null,
                     private val maxFiles: Int = 16, private val maxBytes: Long = 32L * 1024 * 1024) {
    private val typefaces = LinkedHashMap<AssetId, Typeface>(16, .75f, true)
    init { require(maxFiles > 0 && maxBytes >= FontPreflight.MAX_BYTES); directory.mkdirs() }
    @Synchronized fun import(source: InputStream): StagedAssetRef {
        val bytes = readBounded(source)
        FontPreflight.validate(bytes)
        val digest = FontPreflight.digest(bytes)
        val staged = assets?.stage(bytes.inputStream(), FontPreflight.MAX_BYTES.toLong(), "font")
            ?: StagedAssetRef(AssetId("font-$digest"), digest, bytes.size.toLong(), digest)
        val file = File(directory, digest)
        if (!file.exists()) {
            trimFor(bytes.size.toLong())
            val temp = File.createTempFile("font-", ".tmp", directory)
            try {
                java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                check(temp.renameTo(file)) { "Cannot commit font cache" }
            } finally { temp.delete() }
        }
        return staged
    }
    @Synchronized fun resolve(asset: AssetId?, family: String?): Typeface {
        if (asset == null) return Typeface.create(family ?: "sans-serif", Typeface.NORMAL)
        typefaces[asset]?.let { return it }
        return try {
            val source = assets?.open(asset)
            val local = if (source != null) {
                val bytes = source.use { readBounded(it) }; FontPreflight.validate(bytes)
                val file = File(directory, FontPreflight.digest(bytes))
                if (!file.exists()) { trimFor(bytes.size.toLong()); file.writeBytes(bytes) }
                file
            } else File(directory, asset.value.removePrefix("font-"))
            require(local.canonicalFile.parentFile == directory.canonicalFile && local.isFile && local.length() <= FontPreflight.MAX_BYTES)
            Typeface.createFromFile(local).also {
                while (typefaces.size >= maxFiles) typefaces.remove(typefaces.keys.first())
                typefaces[asset] = it
                local.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) { Typeface.create(family ?: "sans-serif", Typeface.NORMAL) }
    }
    @Synchronized fun clearMemory() = typefaces.clear()
    private fun trimFor(bytes: Long) {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty().toMutableList()
        var total = files.sumOf { it.length() }
        while (files.size >= maxFiles || total + bytes > maxBytes) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            check(oldest.delete()) { "Cannot evict font cache" }
            total -= size
        }
    }
    private fun readBounded(source: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            var count = source.read(buffer)
            if(count==0) { val single=source.read();if(single<0) break;buffer[0]=single.toByte();count=1 }
            if (count < 0) break
            require(output.size().toLong() + count <= FontPreflight.MAX_BYTES) { "Font exceeds resource cap" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
