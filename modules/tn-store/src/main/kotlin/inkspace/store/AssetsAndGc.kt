package inkspace.store

import inkspace.core.error.ResourceError
import inkspace.core.error.StorageError
import inkspace.core.id.AssetId
import inkspace.core.id.GestureId
import inkspace.core.outcome.Outcome
import inkspace.core.ports.ResourceLease
import inkspace.core.ports.ResourceRequest
import inkspace.core.ports.StagedAssetRef
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class AssetsAndGc(
    private val faultController: FaultInjectionController
) {
    private val activeLeases = ConcurrentHashMap<AssetId, AtomicInteger>()

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    fun assetFile(docDir: File, assetId: AssetId): File {
        val raw = assetId.value.removePrefix("sha256:")
        val prefix = if (raw.length >= 2) raw.substring(0, 2) else "00"
        return File(docDir, "assets/$prefix/$raw")
    }

    fun stageAndPublishAsset(
        docDir: File,
        stagingId: String,
        bytes: ByteArray
    ): Outcome<StagedAssetRef, StorageError> {
        if (faultController.consumeFault(FaultPoint.DISK_FULL_ON_ASSET_WRITE)) {
            return Outcome.Failure(StorageError.DiskFull("Simulated ENOSPC while writing staged asset $stagingId"))
        }

        return try {
            val stagingDir = File(docDir, "staging")
            stagingDir.mkdirs()
            val stagingFile = File(stagingDir, "$stagingId.part")

            FileOutputStream(stagingFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }

            val hex = sha256Hex(bytes)
            val assetId = AssetId("sha256:$hex")
            val targetFile = assetFile(docDir, assetId)
            targetFile.parentFile?.mkdirs()

            Files.move(
                stagingFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )

            Outcome.Success(
                StagedAssetRef(
                    assetId = assetId,
                    stagingId = stagingId,
                    sizeBytes = bytes.size.toLong(),
                    sha256Hex = hex
                )
            )
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Failed to stage asset $stagingId: ${e.message}"))
        }
    }

    fun verifyPublishedAsset(docDir: File, assetId: AssetId): Boolean {
        val file = assetFile(docDir, assetId)
        if (!file.exists() || !file.isFile) return false
        val expectedHex = assetId.value.removePrefix("sha256:")
        val actualHex = sha256Hex(file.readBytes())
        return actualHex.equals(expectedHex, ignoreCase = true)
    }

    fun readAsset(docDir: File, request: ResourceRequest): Outcome<ResourceLease, ResourceError> {
        val file = assetFile(docDir, request.asset)
        if (!file.exists() || !file.isFile) {
            return Outcome.Failure(ResourceError.ResourceNotFound(request.asset.value))
        }

        val size = file.length()
        if (size > request.byteBudget) {
            return Outcome.Failure(
                ResourceError.ResourceExhausted(
                    "Requested asset bytes ($size) exceeds budget (${request.byteBudget})"
                )
            )
        }

        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return Outcome.Failure(
                ResourceError.CorruptedResource(
                    key = request.asset.value,
                    reason = "Failed to read asset bytes: ${e.message}"
                )
            )
        }

        val expectedHex = request.asset.value.removePrefix("sha256:")
        val actualHex = sha256Hex(bytes)
        if (!actualHex.equals(expectedHex, ignoreCase = true)) {
            return Outcome.Failure(
                ResourceError.CorruptedResource(
                    key = request.asset.value,
                    reason = "SHA-256 mismatch: expected $expectedHex, actual $actualHex"
                )
            )
        }

        val counter = activeLeases.computeIfAbsent(request.asset) { AtomicInteger(0) }
        counter.incrementAndGet()

        val lease = ResourceLease(
            asset = request.asset,
            sizeBytes = size,
            isAvailable = true,
            onRelease = {
                counter.decrementAndGet()
            }
        )
        return Outcome.Success(lease)
    }

    fun activeLeaseCount(assetId: AssetId): Int {
        return activeLeases[assetId]?.get() ?: 0
    }

    data class GcStats(
        val deletedOrphanAssets: Int,
        val deletedDraftChunks: Int,
        val reclaimedBytes: Long
    )

    fun collectGarbage(
        docDir: File,
        referencedAssets: Set<AssetId>,
        pinnedAssets: Set<AssetId>,
        cancelGestureIds: Set<GestureId>
    ): GcStats {
        var deletedAssets = 0
        var deletedDrafts = 0
        var reclaimed = 0L

        val assetsRoot = File(docDir, "assets")
        if (assetsRoot.exists()) {
            assetsRoot.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val candidateId = AssetId("sha256:${file.name}")
                    val isReferenced = candidateId in referencedAssets
                    val isPinned = candidateId in pinnedAssets
                    val isLeased = activeLeaseCount(candidateId) > 0
                    if (!isReferenced && !isPinned && !isLeased) {
                        val len = file.length()
                        if (file.delete()) {
                            deletedAssets++
                            reclaimed += len
                        }
                    }
                }
        }

        val draftsRoot = File(docDir, "drafts")
        if (draftsRoot.exists()) {
            draftsRoot.listFiles()?.forEach { draftFile ->
                val shouldDelete = cancelGestureIds.isEmpty() ||
                    cancelGestureIds.any { draftFile.name.startsWith("${it.value}_") }
                if (shouldDelete && draftFile.isFile) {
                    val len = draftFile.length()
                    if (draftFile.delete()) {
                        deletedDrafts++
                        reclaimed += len
                    }
                }
            }
        }

        return GcStats(
            deletedOrphanAssets = deletedAssets,
            deletedDraftChunks = deletedDrafts,
            reclaimedBytes = reclaimed
        )
    }

    companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
