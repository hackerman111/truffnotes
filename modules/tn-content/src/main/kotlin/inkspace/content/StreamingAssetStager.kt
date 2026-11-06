package inkspace.content

import inkspace.core.content.AssetStagingPort
import inkspace.core.id.AssetId
import inkspace.core.ports.StagedAssetRef
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Streams directly into the document asset store layout, without a whole-file byte array. */
class StreamingAssetStager(private val documentDirectory: File) : AssetStagingPort {
    override fun stage(source: InputStream, maxBytes: Long, suffix: String): StagedAssetRef {
        require(maxBytes in 1..268_435_456L && suffix.matches(Regex("[a-zA-Z0-9_-]{1,32}")))
        val staging = File(documentDirectory, "staging").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val temp = File(staging, "$id.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            java.io.FileOutputStream(temp).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    var read = source.read(buffer)
                    if (read == 0) {
                        val single=source.read()
                        if(single < 0) break
                        buffer[0]=single.toByte();read=1
                    }
                    if (read < 0) break
                    require(read.toLong() <= maxBytes - count) { "Asset exceeds import limit" }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read); count += read
                }
                output.fd.sync()
            }
            require(count > 0) { "Empty asset" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val asset = AssetId("sha256:$sha")
            val target = localFile(asset)
            target.parentFile!!.mkdirs()
            if (!target.exists()) Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return StagedAssetRef(asset, id, count, sha)
        } finally { temp.delete() }
    }
    fun localFile(asset: AssetId): File {
        val hex = asset.value.removePrefix("sha256:")
        require(hex.matches(Regex("[0-9a-f]{64}"))) { "Invalid asset ID" }
        return File(documentDirectory, "assets/${hex.take(2)}/$hex")
    }
    override fun open(asset: AssetId): InputStream = localFile(asset).inputStream()
}
