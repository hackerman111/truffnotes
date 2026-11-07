package inkspace.runtime

import android.content.Context
import android.net.Uri
import inkspace.core.codec.CoreCodec
import inkspace.core.id.*
import inkspace.core.model.*
import inkspace.core.workspace.*
import inkspace.exchange.*
import inkspace.store.DocumentStore
import java.io.*
import java.util.UUID

@kotlinx.serialization.Serializable
private data class CollectionPublication(val owner: String, val entries: List<LibraryEntry>)

class AndroidCollectionExchange(private val context: Context, private val runtime: Runtime) {
    private val root = File(runtime.config.storageRootPath)
    companion object {
        private val publicationLock=Any()
        private val owner=UUID.randomUUID().toString()
    }
    fun publishPending(gateway: NotebookGateway) = synchronized(publicationLock) {
        for(receipt in File(root,"collection-publications").listFiles().orEmpty()) {
            if(!receipt.isFile || receipt.length()>8L*1024*1024) continue
            val publication=CoreCodec.decodeFromJson<CollectionPublication>(receipt.readText()).getOrNull() ?: continue
            val documents=publication.entries.filter { !it.isFolder }
            if(documents.any { runCatching { UUID.fromString(it.id) }.isFailure }) continue
            val catalog=gateway.entries(LibrarySection.DOCUMENTS)+gateway.entries(LibrarySection.TRASH)
            if(publication.entries.all { item -> catalog.any { it.id==item.id } }) {receipt.delete();continue}
            if(documents.all { File(root,"documents/${it.id}/document.sqlite").isFile }) {
                if(gateway.submit(LibraryAction.Import(publication.entries)).isSuccess)receipt.delete()
            } else if(publication.owner!=owner && publication.entries.none { item -> catalog.any { it.id==item.id } }) {
                documents.forEach { File(root,"documents/${it.id}").deleteRecursively() };receipt.delete()
            }
        }
    }
    fun export(ids: Set<String>, uri: Uri, entries: List<LibraryEntry>) {
        require(ids.isNotEmpty())
        val byId = entries.associateBy { it.id }
        val leases = mutableListOf<SnapshotReader>()
        try {
            val items = ids.sorted().map { id ->
                val entry = requireNotNull(byId[id]); require(!entry.isFolder && !entry.trashed)
                val session = runtime.sessionForDocument(id)
                val reader = if (session != null) runtime.acquireRecovery(session) else {
                    val store = DocumentStore(root)
                    check(store.execute(inkspace.core.ports.StorageRequest.Open(DocumentId(id),false)).isSuccess)
                    val disk = store.acquireSnapshot(DocumentId(id))
                    object : SnapshotReader {
                        override val document = NativeDocument(disk.metadata.version,disk.metadata.kind,disk.metadata.pages,disk.metadata.layers,disk.objectCount,disk.assets.map { it.value }.sorted())
                        override fun records() = disk.records()
                        override fun asset(id: AssetId) = disk.asset(id)
                        override fun close() { try { disk.close() } finally { store.execute(inkspace.core.ports.StorageRequest.Close(DocumentId(id))) } }
                    }
                }
                leases += reader
                val names = mutableListOf(entry.name)
                val pathIds = mutableListOf(entry.id)
                var parent = entry.parentId
                val visited = hashSetOf(id)
                while(parent != null) { check(visited.add(parent)); val folder=byId.getValue(parent); names.add(folder.name);pathIds.add(folder.id); parent=folder.parentId }
                NativeCollectionItem(pathIds.reversed().joinToString("/"),reader,names.reversed())
            }
            SafeExport.write(AndroidExchangeOutput(context.contentResolver,uri)) { NativeCollection.write(items,it) }
        } finally { leases.asReversed().forEach { it.close() } }
    }
    fun importCollection(uri: Uri, gateway: NotebookGateway): List<String> {
        val staging = File(context.cacheDir,"collection-${UUID.randomUUID()}").apply { check(mkdirs()) }
        data class Child(val directory: File, var header: NativeDocument? = null, val records: MutableList<File> = mutableListOf(), val assets: MutableMap<String,File> = linkedMapOf())
        val children = mutableListOf<Child>()
        val installed = mutableListOf<String>()
        var published = false
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> NativeCollection.read(input,object : NativeCollectionSink {
                override fun document(index: Int): ImportSink {
                    val child=Child(File(staging,index.toString()).apply { check(mkdirs()) });children+=child
                    return object : ImportSink {
                        override fun document(value: NativeDocument) { child.header=value }
                        override fun record(path: String,bytes: ByteArray) { val file=File(child.directory,"r${child.records.size}");file.writeBytes(bytes);child.records+=file }
                        override fun asset(path: String): OutputStream = File(child.directory,"a${child.assets.size}").also { child.assets[path]=it }.outputStream()
                        override fun commit(manifest: NativeManifest) = Unit
                        override fun abort() { child.directory.deleteRecursively() }
                    }
                }
                override fun commit(manifest: CollectionManifest) { synchronized(publicationLock) {
                    val catalog=mutableListOf<LibraryEntry>()
                    val folders=linkedMapOf<String,String>()
                    val ids=manifest.documents.map { UUID.randomUUID().toString() }
                    for ((index,entry) in manifest.documents.withIndex()) {
                        val pathParts=entry.libraryPath.split('/')
                        val parts=entry.names.ifEmpty {pathParts}
                        var parent: String?=null
                        for (depth in 1 until parts.size) {
                            val path=pathParts.take(depth).joinToString("/")
                            val oldParent=parent
                            parent=folders.getOrPut(path) { UUID.randomUUID().toString().also { catalog+=LibraryEntry(it,parts[depth-1],oldParent,isFolder=true) } }
                        }
                        catalog+=LibraryEntry(ids[index],parts.last(),parent)
                    }
                    val receipt=File(root,"collection-publications/${UUID.randomUUID()}.json")
                    check(receipt.parentFile.isDirectory || receipt.parentFile.mkdirs())
                    FileOutputStream(receipt).use { it.write(CoreCodec.encodeToJson(CollectionPublication(owner,catalog)).toByteArray());it.fd.sync() }
                    try {
                        for ((index,child) in children.withIndex()) {
                            val header=requireNotNull(child.header)
                            val restored=restoreSnapshot(SnapshotMetadata(header.version,header.kind,header.pages,header.layers),child.records.asSequence().map { CoreCodec.decodeFromCbor<SceneObject>(it.readBytes()).getOrNull()!! })
                            val copy=nativeCopy(restored,DocumentId(ids[index]))
                            val store=DocumentStore(root)
                            check(store.installSnapshot(copy) { child.assets.getValue(NativeArchive.assetPath(it.value)).inputStream() }.isSuccess)
                            installed+=ids[index]
                        }
                        val result=gateway.submit(LibraryAction.Import(catalog))
                        check(result.isSuccess) { result.errorOrNull().toString() }
                        published=true
                    } finally { receipt.delete() }
                } }

                override fun abort() = Unit
            }) }
            return installed
        } finally {
            if(!published) installed.forEach { File(root,"documents/$it").deleteRecursively() }
            staging.deleteRecursively()
        }
    }
}
