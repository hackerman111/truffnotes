package inkspace.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import inkspace.core.codec.CoreCodec
import inkspace.core.codec.WriteSet
import inkspace.core.codec.WriteSetCodec
import inkspace.core.error.ResourceError
import inkspace.core.error.StorageError
import inkspace.core.error.SubmitError
import inkspace.core.id.AssetId
import inkspace.core.id.DocumentId
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.PageId
import inkspace.core.id.Revision
import inkspace.core.id.TransactionId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocumentKind
import inkspace.core.model.DocumentSnapshot
import inkspace.core.model.DocumentState
import inkspace.core.model.SnapshotMetadata
import inkspace.core.model.restoreSnapshot
import inkspace.core.model.LayerDescriptor
import inkspace.core.model.PageDescriptor
import inkspace.core.model.SceneObject
import inkspace.core.mutation.InsertObject
import inkspace.core.mutation.Mutation
import inkspace.core.mutation.UpdatePayload
import inkspace.core.mutation.replayWriteSet
import inkspace.core.outcome.Outcome
import inkspace.core.payload.GroupPayload
import inkspace.core.payload.ImagePayload
import inkspace.core.ports.DurableAck
import inkspace.core.ports.RequestId
import inkspace.core.ports.ResourceLease
import inkspace.core.ports.ResourcePort
import inkspace.core.ports.ResourceRequest
import inkspace.core.ports.StagedAssetRef
import inkspace.core.ports.StoragePort
import inkspace.core.ports.StorageReply
import inkspace.core.ports.StorageRequest
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DocumentStore(
    private val rootDir: File,
    private val faultController: FaultInjectionController = FaultInjectionController(),
    private val limits: Limits = Limits(),
    private val maxQueueDepth: Int = 64
) : StoragePort, ResourcePort {

    private val driver = BundledSQLiteDriver()
    private val writerLock = ReentrantLock()
    private val sessions = ConcurrentHashMap<DocumentId, OpenSession>()
    private val assetsAndGc = AssetsAndGc(faultController)
    private val requestIdSeq = AtomicLong(0L)
    private val inFlightCount = AtomicInteger(0)

    private class OpenSession(
        val documentId: DocumentId,
        val docDir: File,
        val dbFile: File,
        val connection: SQLiteConnection,
        var state: DocumentState
    )

    override fun trySubmit(request: StorageRequest): Outcome<RequestId, SubmitError> {
        val depth = inFlightCount.incrementAndGet()
        if (depth > maxQueueDepth) {
            inFlightCount.decrementAndGet()
            return Outcome.Failure(SubmitError.QueueOverflow(currentDepth = depth, maxDepth = maxQueueDepth))
        }
        return try {
            val res = execute(request)
            if (res is Outcome.Failure) {
                Outcome.Failure(SubmitError.ShutdownInProgress(res.error.toString()))
            } else {
                Outcome.Success(RequestId(requestIdSeq.incrementAndGet()))
            }
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    override fun read(request: ResourceRequest): Outcome<ResourceLease, ResourceError> {
        for (session in sessions.values) {
            val candidate = assetsAndGc.assetFile(session.docDir, request.asset)
            if (candidate.exists()) {
                return assetsAndGc.readAsset(session.docDir, request)
            }
        }
        val docsRoot = File(rootDir, "documents")
        if (docsRoot.exists()) {
            docsRoot.listFiles()?.forEach { docDir ->
                val candidate = assetsAndGc.assetFile(docDir, request.asset)
                if (candidate.exists()) {
                    return assetsAndGc.readAsset(docDir, request)
                }
            }
        }
        return Outcome.Failure(ResourceError.ResourceNotFound(request.asset.value))
    }

    fun execute(request: StorageRequest): Outcome<StorageReply, StorageError> {
        return writerLock.withLock {
            when (request) {
                is StorageRequest.Open -> handleOpen(request)
                is StorageRequest.Read -> handleRead(request)
                is StorageRequest.StageDraftChunk -> handleStageDraftChunk(request)
                is StorageRequest.Commit -> handleCommit(request).also { if(it.isSuccess) assetsAndGc.committed(request.stagedAssets.map { asset -> asset.assetId } + request.writeSet?.referencedAssets.orEmpty()) }
                is StorageRequest.Flush -> handleFlush(request)
                is StorageRequest.Close -> handleClose(request)
                is StorageRequest.Backup -> handleBackup(request)
                is StorageRequest.Version -> handleVersion(request)
                is StorageRequest.Collect -> handleCollect(request)
            }
        }
    }

    private fun docDirectory(documentId: DocumentId): File =
        File(rootDir, "documents/${documentId.value}")

    private fun handleOpen(request: StorageRequest.Open): Outcome<StorageReply, StorageError> {
        sessions[request.documentId]?.let { existing ->
            return Outcome.Success(
                StorageReply.Opened(
                    documentId = existing.documentId,
                    durableRevision = existing.state.version.revision,
                    totalObjects = existing.state.totalObjectCount
                )
            )
        }

        val docDir = docDirectory(request.documentId)
        val dbFile = File(docDir, "document.sqlite")
        if (!request.createIfMissing && !dbFile.exists()) {
            return Outcome.Failure(StorageError.IoFailure("Document DB not found at ${dbFile.absolutePath}"))
        }

        return try {
            docDir.mkdirs()
            val conn = driver.open(dbFile.absolutePath)
            SchemaAndMigrate.configureAndMigrate(conn)

            if (!SchemaAndMigrate.verifyIntegrity(conn)) {
                conn.close()
                return Outcome.Failure(StorageError.CorruptedDatabase("SQLite integrity_check failed for ${request.documentId.value}"))
            }

            val state = loadOrInitializeDocumentState(conn, request.documentId)
            val session = OpenSession(
                documentId = request.documentId,
                docDir = docDir,
                dbFile = dbFile,
                connection = conn,
                state = state
            )
            sessions[request.documentId] = session

            Outcome.Success(
                StorageReply.Opened(
                    documentId = request.documentId,
                    durableRevision = state.version.revision,
                    totalObjects = state.totalObjectCount
                )
            )
        } catch (e: StorageDatabaseCorruptException) {
            Outcome.Failure(StorageError.CorruptedDatabase(e.message ?: "Corrupted document state"))
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Failed to open ${request.documentId.value}: ${e.message}"))
        }
    }

    private class StorageDatabaseCorruptException(message: String) : Exception(message)

    private fun loadOrInitializeDocumentState(
        conn: SQLiteConnection,
        documentId: DocumentId
    ): DocumentState {
        val existingMeta = conn.prepare(
            "SELECT document_kind, durable_revision FROM document_meta WHERE document_id = ?"
        ).use { st ->
            st.bindText(1, documentId.value)
            if (st.step()) {
                Pair(st.getText(0), st.getLong(1))
            } else {
                null
            }
        }

        if (existingMeta == null) {
            val initial = DocumentState.createInitial(documentId)
            conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                val now = System.currentTimeMillis()
                conn.prepare(
                    "INSERT INTO document_meta(document_id, document_kind, durable_revision, updated_at_ms) VALUES (?, ?, ?, ?)"
                ).use { st ->
                    st.bindText(1, documentId.value)
                    st.bindText(2, initial.kind.name)
                    st.bindLong(3, 0L)
                    st.bindLong(4, now)
                    st.step()
                }
                syncPagesAndLayers(conn, initial)
                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw t
            }
            return initial
        }

        val kind = runCatching { DocumentKind.valueOf(existingMeta.first) }.getOrDefault(DocumentKind.Notebook)
        val expectedDurableRev = existingMeta.second
        val hasCheckpoint = conn.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name='native_checkpoint_metadata'").use { it.step() }
        val state = if (hasCheckpoint) {
            val metadata = conn.prepare("SELECT encoded_cbor FROM native_checkpoint_metadata WHERE singleton=1").use { st ->
                if (!st.step()) throw StorageDatabaseCorruptException("Missing native checkpoint")
                CoreCodec.decodeFromCbor<SnapshotMetadata>(st.getBlob(0)).getOrNull()
                    ?: throw StorageDatabaseCorruptException("Invalid native checkpoint metadata")
            }
            if (metadata.version.document != documentId) throw StorageDatabaseCorruptException("Checkpoint document mismatch")
            val snapshot = conn.prepare("SELECT encoded_cbor FROM native_checkpoint_records ORDER BY ordinal").use { st ->
                restoreSnapshot(metadata, sequence {
                    while (st.step()) yield(CoreCodec.decodeFromCbor<SceneObject>(st.getBlob(0)).getOrNull()
                        ?: throw StorageDatabaseCorruptException("Invalid checkpoint object"))
                })
            }
            DocumentState.fromSnapshot(snapshot)
        } else DocumentState.createInitial(documentId = documentId, kind = kind)

        val encodedWriteSets = ArrayList<ByteArray>()
        conn.prepare(
            "SELECT writeset_cbor FROM transaction_log ORDER BY new_revision ASC"
        ).use { st ->
            while (st.step()) {
                encodedWriteSets.add(st.getBlob(0))
            }
        }

        for (cborBytes in encodedWriteSets) {
            if (cborBytes.isEmpty()) continue
            val wsOutcome = WriteSetCodec.decodeFromCbor(cborBytes)
            val ws = wsOutcome.getOrNull()
                ?: throw StorageDatabaseCorruptException("Corrupted WriteSet CBOR in transaction_log")
            val replayed = replayWriteSet(state, ws, limits)
            if (replayed is Outcome.Failure) {
                throw StorageDatabaseCorruptException("Failed to replay WriteSet rev ${ws.newRevision.value}: ${replayed.error}")
            }
        }

        if (state.version.revision.value != expectedDurableRev) {
            throw StorageDatabaseCorruptException(
                "Durable revision mismatch: meta=$expectedDurableRev, replayed=${state.version.revision.value}"
            )
        }
        return state
    }

    private fun handleStageDraftChunk(
        request: StorageRequest.StageDraftChunk
    ): Outcome<StorageReply, StorageError> {
        val session = sessions[request.documentId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${request.documentId.value} is not open"))

        return try {
            val draftsDir = File(session.docDir, "drafts")
            draftsDir.mkdirs()
            val chunkFile = File(draftsDir, "${request.gestureId.value}_${request.chunkIndex}.chunk")
            FileOutputStream(chunkFile).use { fos ->
                fos.write(request.encodedBytes)
                fos.flush()
                fos.fd.sync()
            }
            Outcome.Success(
                StorageReply.DraftChunkStaged(
                    documentId = request.documentId,
                    gestureId = request.gestureId,
                    chunkIndex = request.chunkIndex,
                    stagedBytes = request.encodedBytes.size.toLong()
                )
            )
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Failed to stage draft chunk: ${e.message}"))
        }
    }

    private fun handleCommit(request: StorageRequest.Commit): Outcome<StorageReply, StorageError> {
        val docId = request.writeSet?.documentId ?: sessions.keys.firstOrNull()
            ?: return Outcome.Failure(StorageError.IoFailure("No open document for Commit"))
        val session = sessions[docId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${docId.value} is not open"))

        // 1. Check idempotency by TransactionId
        val existingRevision = queryTransactionRevision(session.connection, request.transactionId)
        if (existingRevision != null) {
            return Outcome.Success(
                StorageReply.Committed(
                    DurableAck(
                        document = docId,
                        revision = Revision(existingRevision),
                        transactionId = request.transactionId,
                        wasDeduplicated = true
                    )
                )
            )
        }

        // 2. Verify expectedDurableBase
        val actualBase = session.state.version.revision
        if (request.expectedDurableBase != actualBase) {
            return Outcome.Failure(
                StorageError.StaleDurableBase(
                    expected = request.expectedDurableBase.value,
                    actual = actualBase.value
                )
            )
        }

        // 3. Verify all referenced & staged assets exist and match SHA-256 BEFORE touching SQLite
        val referencedAssets = LinkedHashSet<AssetId>()
        request.stagedAssets.forEach { referencedAssets.add(it.assetId) }
        request.writeSet?.let { ws ->
            referencedAssets.addAll(ws.referencedAssets)
            extractAssetIdsFromMutations(ws.plan.mutations, referencedAssets)
        }

        for (assetId in referencedAssets) {
            if (!assetsAndGc.verifyPublishedAsset(session.docDir, assetId)) {
                return Outcome.Failure(StorageError.MissingAssetReference(assetId.value))
            }
        }

        // 4. Fault hook: crash after asset fsync/publish but before DB commit
        if (faultController.consumeFault(FaultPoint.CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT)) {
            return Outcome.Failure(
                StorageError.IoFailure("Simulated crash after asset fsync before SQLite commit")
            )
        }

        // 5. Fault hook: disk full on DB commit
        if (faultController.consumeFault(FaultPoint.DISK_FULL_ON_DB_COMMIT)) {
            return Outcome.Failure(
                StorageError.DiskFull("Simulated ENOSPC during SQLite transaction commit")
            )
        }

        val beforeSnap = session.state.snapshot()
        val writeSetToPersist: WriteSet? = request.writeSet
        if (writeSetToPersist != null) {
            val replayRes = replayWriteSet(session.state, writeSetToPersist, limits)
            if (replayRes is Outcome.Failure) {
                restoreStateFromSnapshot(session.state, beforeSnap)
                return Outcome.Failure(
                    StorageError.InvalidWriteSet("WriteSet replay failed: ${replayRes.error}")
                )
            }
        }

        val conn = session.connection
        try {
            conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
            val now = System.currentTimeMillis()

            conn.prepare(
                "UPDATE document_meta SET durable_revision = ?, updated_at_ms = ? WHERE document_id = ?"
            ).use { st ->
                st.bindLong(1, request.revision.value)
                st.bindLong(2, now)
                st.bindText(3, docId.value)
                st.step()
            }

            syncPagesAndLayers(conn, session.state)
            syncSceneObjects(conn, session.state, writeSetToPersist)

            for (assetId in referencedAssets) {
                val file = assetsAndGc.assetFile(session.docDir, assetId)
                val hex = assetId.value.removePrefix("sha256:")
                conn.prepare(
                    "INSERT OR REPLACE INTO asset_refs(asset_id, revision, size_bytes, sha256_hex) VALUES (?, ?, ?, ?)"
                ).use { st ->
                    st.bindText(1, assetId.value)
                    st.bindLong(2, request.revision.value)
                    st.bindLong(3, file.length())
                    st.bindText(4, hex)
                    st.step()
                }
            }

            val encodedWriteSet = if (writeSetToPersist != null) {
                WriteSetCodec.encodeToCbor(writeSetToPersist)
            } else {
                ByteArray(0)
            }

            conn.prepare(
                """
                INSERT INTO transaction_log(transaction_id, base_revision, new_revision, writeset_cbor, committed_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { st ->
                st.bindText(1, request.transactionId.value)
                st.bindLong(2, request.expectedDurableBase.value)
                st.bindLong(3, request.revision.value)
                st.bindBlob(4, encodedWriteSet)
                st.bindLong(5, now)
                st.step()
            }

            if (faultController.consumeFault(FaultPoint.CRASH_DURING_DB_TRANSACTION)) {
                throw SimulatedTransactionCrashException("Simulated crash in the middle of SQLite transaction")
            }

            conn.execSQL("COMMIT")
        } catch (e: SimulatedTransactionCrashException) {
            runCatching { conn.execSQL("ROLLBACK") }
            restoreStateFromSnapshot(session.state, beforeSnap)
            return Outcome.Failure(StorageError.IoFailure(e.message ?: "Simulated transaction crash"))
        } catch (e: Exception) {
            runCatching { conn.execSQL("ROLLBACK") }
            restoreStateFromSnapshot(session.state, beforeSnap)
            val msg = e.message ?: "SQLite commit error"
            return if (msg.contains("FULL", ignoreCase = true) || msg.contains("ENOSPC", ignoreCase = true)) {
                Outcome.Failure(StorageError.DiskFull(msg))
            } else {
                Outcome.Failure(StorageError.IoFailure(msg))
            }
        }

        // Clean up any draft chunks for this gesture
        writeSetToPersist?.plan?.gesture?.let { gestureId ->
            assetsAndGc.collectGarbage(
                docDir = session.docDir,
                referencedAssets = collectAllReferencedAssets(conn),
                pinnedAssets = emptySet(),
                cancelGestureIds = setOf(gestureId)
            )
        }

        if (faultController.consumeFault(FaultPoint.CRASH_AFTER_DB_COMMIT_BEFORE_ACK)) {
            return Outcome.Failure(
                StorageError.IoFailure("Simulated crash after SQLite commit before returning DurableAck")
            )
        }

        return Outcome.Success(
            StorageReply.Committed(
                DurableAck(
                    document = docId,
                    revision = request.revision,
                    transactionId = request.transactionId,
                    wasDeduplicated = false
                )
            )
        )
    }

    private class SimulatedTransactionCrashException(message: String) : Exception(message)

    private fun queryTransactionRevision(conn: SQLiteConnection, txId: TransactionId): Long? {
        conn.prepare("SELECT new_revision FROM transaction_log WHERE transaction_id = ?").use { st ->
            st.bindText(1, txId.value)
            return if (st.step()) st.getLong(0) else null
        }
    }

    private fun extractAssetIdsFromMutations(mutations: List<Mutation>, out: MutableSet<AssetId>) {
        for (m in mutations) {
            when (m) {
                is InsertObject -> {
                    when (val p = m.payload) {
                        is ImagePayload -> out.add(p.asset)
                        else -> {}
                    }
                }
                is UpdatePayload -> {
                    when (val p = m.newPayload) {
                        is ImagePayload -> out.add(p.asset)
                        else -> {}
                    }
                }
                else -> {}
            }
        }
    }

    private fun syncPagesAndLayers(conn: SQLiteConnection, state: DocumentState) {
        val snap = state.snapshot()
        snap.pages.forEachIndexed { idx, page ->
            conn.prepare(
                """
                INSERT INTO pages(page_id, space_id, ordinal, width_pt, height_pt)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(page_id) DO UPDATE SET
                    space_id = excluded.space_id,
                    ordinal = excluded.ordinal,
                    width_pt = excluded.width_pt,
                    height_pt = excluded.height_pt
                """.trimIndent()
            ).use { st ->
                st.bindText(1, page.id.value)
                st.bindText(2, page.spaceId.value)
                st.bindLong(3, idx.toLong())
                st.bindDouble(4, page.widthPt)
                st.bindDouble(5, page.heightPt)
                st.step()
            }
        }
        snap.layers.forEachIndexed { idx, layer ->
            conn.prepare(
                """
                INSERT INTO layers(layer_id, page_id, ordinal, name, opacity, is_visible, is_locked)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(layer_id) DO UPDATE SET
                    page_id = excluded.page_id,
                    ordinal = excluded.ordinal,
                    name = excluded.name,
                    opacity = excluded.opacity,
                    is_visible = excluded.is_visible,
                    is_locked = excluded.is_locked
                """.trimIndent()
            ).use { st ->
                st.bindText(1, layer.id.value)
                st.bindText(2, layer.pageId.value)
                st.bindLong(3, idx.toLong())
                st.bindText(4, layer.name)
                st.bindDouble(5, layer.opacity.toDouble())
                st.bindLong(6, if (layer.isVisible) 1L else 0L)
                st.bindLong(7, if (layer.isLocked) 1L else 0L)
                st.step()
            }
        }
    }

    private fun syncSceneObjects(conn: SQLiteConnection, state: DocumentState, writeSet: WriteSet?) {
        if (writeSet == null) return
        syncSceneObjectsById(conn, state, writeSet.records.filter { it.entityKind.equals("object",ignoreCase=true) }.map { ObjectId(it.entityId) })
    }

    private fun syncSceneObjectsById(conn: SQLiteConnection, state: DocumentState, ids: List<ObjectId>) {
        val snap = state.snapshot()
        for (objId in ids) {
            val currentObj = snap.directory.getObject(objId)
            if (currentObj == null) {
                conn.prepare("DELETE FROM scene_objects WHERE object_id = ?").use { st ->
                    st.bindText(1, objId.value)
                    st.step()
                }
            } else {
                val cbor = CoreCodec.encodeToCbor(currentObj)
                val digest = CoreCodec.computePayloadDigest(currentObj.payload)
                conn.prepare(
                    """
                    INSERT INTO scene_objects(
                        object_id, space_id, layer_id, min_x, min_y, max_x, max_y,
                        version, is_locked, payload_digest, encoded_cbor
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(object_id) DO UPDATE SET
                        space_id = excluded.space_id,
                        layer_id = excluded.layer_id,
                        min_x = excluded.min_x,
                        min_y = excluded.min_y,
                        max_x = excluded.max_x,
                        max_y = excluded.max_y,
                        version = excluded.version,
                        is_locked = excluded.is_locked,
                        payload_digest = excluded.payload_digest,
                        encoded_cbor = excluded.encoded_cbor
                    """.trimIndent()
                ).use { st ->
                    st.bindText(1, currentObj.id.value)
                    st.bindText(2, currentObj.space.value)
                    st.bindText(3, currentObj.layer.value)
                    st.bindDouble(4, currentObj.bounds.left)
                    st.bindDouble(5, currentObj.bounds.top)
                    st.bindDouble(6, currentObj.bounds.right)
                    st.bindDouble(7, currentObj.bounds.bottom)
                    st.bindLong(8, currentObj.version)
                    st.bindLong(9, if (currentObj.isLocked) 1L else 0L)
                    st.bindLong(10, digest)
                    st.bindBlob(11, cbor)
                    st.step()
                }
            }
        }
    }

    private fun restoreStateFromSnapshot(state: DocumentState, snap: DocumentSnapshot) {
        val pageMap = LinkedHashMap<PageId, PageDescriptor>().apply {
            for (p in snap.pages) put(p.id, p)
        }
        val layerMap = LinkedHashMap<LayerId, LayerDescriptor>().apply {
            for (l in snap.layers) put(l.id, l)
        }
        val parentGroups = LinkedHashMap<ObjectId, ObjectId>().apply {
            for (obj in snap.directory.allResidentObjects()) {
                val p = obj.payload
                if (p is GroupPayload) {
                    for (m in p.members) put(m, obj.id)
                }
            }
        }
        val fieldVersion = DocumentState::class.java.getDeclaredField("currentVersion").apply { isAccessible = true }
        val fieldDir = DocumentState::class.java.getDeclaredField("currentDirectory").apply { isAccessible = true }
        val fieldPages = DocumentState::class.java.getDeclaredField("currentPages").apply { isAccessible = true }
        val fieldLayers = DocumentState::class.java.getDeclaredField("currentLayers").apply { isAccessible = true }
        val fieldPageMap = DocumentState::class.java.getDeclaredField("currentPageMap").apply { isAccessible = true }
        val fieldLayerMap = DocumentState::class.java.getDeclaredField("currentLayerMap").apply { isAccessible = true }
        val fieldParents = DocumentState::class.java.getDeclaredField("currentParentGroups").apply { isAccessible = true }

        fieldVersion.set(state, snap.version)
        fieldDir.set(state, snap.directory)
        fieldPages.set(state, snap.pages)
        fieldLayers.set(state, snap.layers)
        fieldPageMap.set(state, pageMap)
        fieldLayerMap.set(state, layerMap)
        fieldParents.set(state, parentGroups)
    }

    private fun handleRead(request: StorageRequest.Read): Outcome<StorageReply, StorageError> {
        val session = sessions[request.documentId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${request.documentId.value} is not open"))

        val loaded = ArrayList<SceneObject>()
        var totalBytes = 0L

        if (request.objectIds.isNotEmpty()) {
            for (objId in request.objectIds) {
                session.connection.prepare(
                    "SELECT encoded_cbor FROM scene_objects WHERE object_id = ?"
                ).use { st ->
                    st.bindText(1, objId.value)
                    if (st.step()) {
                        val blob = st.getBlob(0)
                        totalBytes += blob.size
                        if (totalBytes > request.byteBudget) {
                            return Outcome.Failure(
                                StorageError.IoFailure("Read byteBudget (${request.byteBudget}) exceeded ($totalBytes)")
                            )
                        }
                        val decoded = CoreCodec.decodeFromCbor<SceneObject>(blob).getOrNull()
                            ?: return Outcome.Failure(StorageError.CorruptedDatabase("Corrupt SceneObject CBOR for ${objId.value}"))
                        loaded.add(decoded)
                    }
                }
            }
        } else if (request.spaceId != null) {
            session.connection.prepare(
                "SELECT encoded_cbor FROM scene_objects WHERE space_id = ? ORDER BY object_id ASC"
            ).use { st ->
                st.bindText(1, request.spaceId!!.value)
                while (st.step()) {
                    val blob = st.getBlob(0)
                    totalBytes += blob.size
                    if (totalBytes > request.byteBudget) {
                        return Outcome.Failure(
                            StorageError.IoFailure("Read byteBudget (${request.byteBudget}) exceeded ($totalBytes)")
                        )
                    }
                    val decoded = CoreCodec.decodeFromCbor<SceneObject>(blob).getOrNull()
                        ?: return Outcome.Failure(StorageError.CorruptedDatabase("Corrupt SceneObject CBOR in space ${request.spaceId!!.value}"))
                    loaded.add(decoded)
                }
            }
        }

        val lease = ResourceLease(
            asset = AssetId("chunk:${request.documentId.value}:${session.state.version.revision.value}"),
            sizeBytes = totalBytes,
            isAvailable = true
        )
        return Outcome.Success(
            StorageReply.ReadResult(
                documentId = request.documentId,
                revision = session.state.version.revision,
                objects = loaded,
                lease = lease
            )
        )
    }

    private fun handleFlush(request: StorageRequest.Flush): Outcome<StorageReply, StorageError> {
        val session = sessions[request.documentId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${request.documentId.value} is not open"))
        return try {
            session.connection.execSQL("PRAGMA wal_checkpoint(FULL)")
            Outcome.Success(
                StorageReply.Flushed(
                    documentId = request.documentId,
                    durableRevision = session.state.version.revision
                )
            )
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Failed to flush WAL: ${e.message}"))
        }
    }

    private fun handleClose(request: StorageRequest.Close): Outcome<StorageReply, StorageError> {
        val session = sessions.remove(request.documentId)
            ?: return Outcome.Success(StorageReply.Closed(request.documentId))
        return try {
            session.connection.close()
            Outcome.Success(StorageReply.Closed(request.documentId))
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Failed to close DB: ${e.message}"))
        }
    }

    private fun handleBackup(request: StorageRequest.Backup): Outcome<StorageReply, StorageError> {
        val session = sessions[request.documentId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${request.documentId.value} is not open"))

        return try {
            val targetFile = File(request.targetPath)
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val escapedPath = targetFile.absolutePath.replace("'", "''")
            session.connection.execSQL("VACUUM INTO '$escapedPath'")

            // Also copy reachable assets to the target document directory
            val targetDocDir = targetFile.parentFile
            val sourceAssetsDir = File(session.docDir, "assets")
            if (sourceAssetsDir.exists() && targetDocDir != null) {
                sourceAssetsDir.copyRecursively(File(targetDocDir, "assets"), overwrite = true)
            }

            Outcome.Success(
                StorageReply.BackedUp(
                    documentId = request.documentId,
                    durableRevision = session.state.version.revision,
                    targetPath = targetFile.absolutePath,
                    sizeBytes = targetFile.length()
                )
            )
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Backup failed: ${e.message}"))
        }
    }

    private fun handleVersion(request: StorageRequest.Version): Outcome<StorageReply, StorageError> {
        val session = sessions[request.documentId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${request.documentId.value} is not open"))

        return try {
            val relPath = "versions/${request.versionId.value}.sqlite"
            val versionFile = File(session.docDir, relPath)
            versionFile.parentFile?.mkdirs()
            if (versionFile.exists()) {
                versionFile.delete()
            }

            val escapedPath = versionFile.absolutePath.replace("'", "''")
            session.connection.execSQL("VACUUM INTO '$escapedPath'")

            val rev = session.state.version.revision
            val now = System.currentTimeMillis()
            session.connection.prepare(
                """
                INSERT OR REPLACE INTO checkpoint_versions(version_id, revision, label, is_pinned, backup_rel_path, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { st ->
                st.bindText(1, request.versionId.value)
                st.bindLong(2, rev.value)
                st.bindText(3, request.label)
                st.bindLong(4, if (request.isPinned) 1L else 0L)
                st.bindText(5, relPath)
                st.bindLong(6, now)
                st.step()
            }

            Outcome.Success(
                StorageReply.VersionCreated(
                    documentId = request.documentId,
                    versionId = request.versionId,
                    revision = rev
                )
            )
        } catch (e: Exception) {
            Outcome.Failure(StorageError.IoFailure("Version creation failed: ${e.message}"))
        }
    }

    private fun handleCollect(request: StorageRequest.Collect): Outcome<StorageReply, StorageError> {
        val session = sessions[request.documentId]
            ?: return Outcome.Failure(StorageError.IoFailure("Document ${request.documentId.value} is not open"))

        val referenced = collectAllReferencedAssets(session.connection)
        val stats = assetsAndGc.collectGarbage(
            docDir = session.docDir,
            referencedAssets = referenced,
            pinnedAssets = request.pinnedAssets,
            cancelGestureIds = request.cancelGestureIds
        )
        return Outcome.Success(
            StorageReply.Collected(
                documentId = request.documentId,
                deletedOrphanAssets = stats.deletedOrphanAssets,
                deletedDraftChunks = stats.deletedDraftChunks,
                reclaimedBytes = stats.reclaimedBytes
            )
        )
    }

    private fun collectAllReferencedAssets(conn: SQLiteConnection): Set<AssetId> {
        val set = LinkedHashSet<AssetId>()
        conn.prepare("SELECT DISTINCT asset_id FROM asset_refs").use { st ->
            while (st.step()) {
                set.add(AssetId(st.getText(0)))
            }
        }
        return set
    }

    // --- Inspection & helper methods for contract tests and History spill ---

    fun retainPendingAssets(ids: Collection<AssetId>) { assetsAndGc.retainPending(ids) }

    fun pinAssets(documentId: DocumentId, assets: Set<AssetId>): AutoCloseable = writerLock.withLock {
        assetsAndGc.pinAssets(docDirectory(documentId), assets)
    }

    /** WAL read transaction freezes rows without copying the database or taking the writer lock during export. */
    fun acquireSnapshot(documentId: DocumentId): inkspace.core.ports.FrozenStorageSnapshot = writerLock.withLock {
        val session = requireNotNull(sessions[documentId]) { "Document is not open" }
        val snapshot = session.state.snapshot()
        val connection = driver.open(session.dbFile.absolutePath)
        var pins: AutoCloseable? = null
        try {
            connection.execSQL("BEGIN")
            connection.prepare("SELECT durable_revision FROM document_meta WHERE document_id = ?").use {
                it.bindText(1,documentId.value); check(it.step() && it.getLong(0) == snapshot.version.revision.value)
            }
            val assets = snapshot.referencedAssets()
            pins = assetsAndGc.pinAssets(session.docDir,assets)
            val heldPins = pins
            object : inkspace.core.ports.FrozenStorageSnapshot {
                private val closed = java.util.concurrent.atomic.AtomicBoolean()
                override val metadata = SnapshotMetadata(snapshot.version,snapshot.kind,snapshot.pages,snapshot.layers)
                override val objectCount = snapshot.totalObjectCount
                override val assets = assets
                override fun records(): Sequence<SceneObject> = sequence {
                    check(!closed.get())
                    connection.prepare("SELECT encoded_cbor FROM scene_objects ORDER BY object_id ASC").use { statement ->
                        while (statement.step()) {
                            check(!closed.get())
                            yield(requireNotNull(CoreCodec.decodeFromCbor<SceneObject>(statement.getBlob(0)).getOrNull()))
                        }
                    }
                }
                override fun asset(id: AssetId): java.io.InputStream {
                    check(!closed.get()); require(id in assets)
                    return assetsAndGc.assetFile(session.docDir,id).inputStream()
                }
                override fun close() { if (closed.compareAndSet(false,true)) {
                    try { connection.execSQL("ROLLBACK") } finally { try { connection.close() } finally { heldPins.close() } }
                } }
            }
        } catch (error: Exception) { pins?.close(); connection.close(); throw error }
    }

    fun inspectPragmas(documentId: DocumentId): SqlitePragmaReport {
        return writerLock.withLock {
            val session = sessions[documentId]
                ?: error("Document ${documentId.value} is not open")
            SchemaAndMigrate.inspectPragmas(session.connection)
        }
    }

    fun durableRevision(documentId: DocumentId): Revision {
        return writerLock.withLock {
            val session = sessions[documentId]
                ?: error("Document ${documentId.value} is not open")
            session.state.version.revision
        }
    }

    fun currentState(documentId: DocumentId): DocumentState? {
        return writerLock.withLock {
            sessions[documentId]?.state
        }
    }

    fun verifyIntegrity(documentId: DocumentId): Boolean {
        return writerLock.withLock {
            val session = sessions[documentId] ?: return@withLock false
            SchemaAndMigrate.verifyIntegrity(session.connection)
        }
    }

    /** Installs an already validated import into a new private directory; never overwrites a document. */
    fun installSnapshot(snapshot: DocumentSnapshot, openAsset: (AssetId) -> java.io.InputStream): Outcome<Unit, StorageError> = writerLock.withLock {
        val id = snapshot.version.document
        if (!id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            return@withLock Outcome.Failure(StorageError.IoFailure("Unsafe imported document ID"))
        val target = docDirectory(id)
        if (target.exists() || sessions.containsKey(id)) return@withLock Outcome.Failure(StorageError.IoFailure("Document already exists"))
        val staging = File(target.parentFile, ".native-import-${java.util.UUID.randomUUID()}")
        try {
            val checked = restoreSnapshot(SnapshotMetadata(snapshot.version,snapshot.kind,snapshot.pages,snapshot.layers), snapshot.orderedSceneObjects().asSequence())
            require(checked.totalObjectCount == snapshot.totalObjectCount)
            require(staging.mkdirs()) { "Cannot create import staging directory" }
            for (asset in checked.referencedAssets()) {
                val hex = asset.value.removePrefix("sha256:")
                require(hex.matches(Regex("[a-f0-9]{64}"))) { "Native asset ID must be content addressed" }
                val file = assetsAndGc.assetFile(staging,asset)
                file.parentFile?.mkdirs()
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                openAsset(asset).use { input -> FileOutputStream(file).use { output ->
                    val buffer=ByteArray(32768)
                    while(true) { val n=input.read(buffer);if(n<0) break;require(n>0);output.write(buffer,0,n);digest.update(buffer,0,n) }
                    output.flush();output.fd.sync()
                } }
                require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hex) { "Asset digest mismatch" }
            }
            driver.open(File(staging,"document.sqlite").absolutePath).use { conn ->
                SchemaAndMigrate.configureAndMigrate(conn)
                conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
                try {
                    conn.prepare("INSERT INTO document_meta(document_id,document_kind,durable_revision,updated_at_ms) VALUES (?,?,?,?)").use { st ->
                        st.bindText(1,id.value);st.bindText(2,checked.kind.name);st.bindLong(3,checked.version.revision.value);st.bindLong(4,System.currentTimeMillis());st.step()
                    }
                    conn.execSQL("CREATE TABLE native_checkpoint_metadata(singleton INTEGER PRIMARY KEY CHECK(singleton=1),encoded_cbor BLOB NOT NULL)")
                    conn.execSQL("CREATE TABLE native_checkpoint_records(ordinal INTEGER PRIMARY KEY,encoded_cbor BLOB NOT NULL)")
                    conn.prepare("INSERT INTO native_checkpoint_metadata VALUES (1,?)").use { st ->
                        st.bindBlob(1,CoreCodec.encodeToCbor(SnapshotMetadata(checked.version,checked.kind,checked.pages,checked.layers)));st.step()
                    }
                    checked.orderedSceneObjects().forEachIndexed { index,obj -> conn.prepare("INSERT INTO native_checkpoint_records VALUES (?,?)").use { st ->
                        st.bindLong(1,index.toLong());st.bindBlob(2,CoreCodec.encodeToCbor(obj));st.step()
                    } }
                    val state=DocumentState.fromSnapshot(checked)
                    syncPagesAndLayers(conn,state)
                    syncSceneObjectsById(conn,state,checked.orderedSceneObjects().map { it.id })
                    conn.execSQL("COMMIT")
                    conn.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                } catch(error:Exception) { runCatching {conn.execSQL("ROLLBACK")};throw error }
                require(SchemaAndMigrate.verifyIntegrity(conn))
            }
            require(!target.exists()) { "Document appeared during import" }
            java.nio.file.Files.move(staging.toPath(),target.toPath())
            Outcome.Success(Unit)
        } catch(error:Exception) {
            staging.deleteRecursively()
            Outcome.Failure(StorageError.IoFailure("Native import failed: ${error.message}"))
        }
    }

    fun stageAsset(
        documentId: DocumentId,
        stagingId: String,
        bytes: ByteArray
    ): Outcome<StagedAssetRef, StorageError> {
        return writerLock.withLock {
            val docDir = sessions[documentId]?.docDir ?: docDirectory(documentId)
            assetsAndGc.stageAndPublishAsset(docDir, stagingId, bytes)
        }
    }

    fun assetFile(documentId: DocumentId, assetId: AssetId): File {
        val docDir = sessions[documentId]?.docDir ?: docDirectory(documentId)
        return assetsAndGc.assetFile(docDir, assetId)
    }

    fun spillHistoryEntry(
        docId: DocumentId,
        gestureId: GestureId,
        label: UndoLabel,
        mutations: List<Mutation>,
        inverseMutations: List<Mutation> = emptyList()
    ) {
        writerLock.withLock {
            val session = sessions[docId] ?: error("Document ${docId.value} is not open")
            val forwardCbor = CoreCodec.encodeToCbor(mutations)
            val inverseCbor = CoreCodec.encodeToCbor(inverseMutations)
            session.connection.prepare(
                """
                INSERT INTO spilled_history(gesture_id, label, forward_cbor, inverse_cbor, byte_size)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { st ->
                st.bindText(1, gestureId.value)
                st.bindText(2, label.value)
                st.bindBlob(3, forwardCbor)
                st.bindBlob(4, inverseCbor)
                st.bindLong(5, (forwardCbor.size + inverseCbor.size).toLong())
                st.step()
            }
        }
    }

    fun loadSpilledHistory(docId: DocumentId): List<Pair<GestureId, List<Mutation>>> {
        return writerLock.withLock {
            val session = sessions[docId] ?: error("Document ${docId.value} is not open")
            val list = ArrayList<Pair<GestureId, List<Mutation>>>()
            session.connection.prepare(
                "SELECT gesture_id, forward_cbor FROM spilled_history ORDER BY seq_id ASC"
            ).use { st ->
                while (st.step()) {
                    val gId = GestureId(st.getText(0))
                    val mutations = CoreCodec.decodeFromCbor<List<Mutation>>(st.getBlob(1)).getOrNull().orEmpty()
                    list.add(gId to mutations)
                }
            }
            list
        }
    }

    fun simulateAbruptCloseWithTruncatedWalTail(
        docId: DocumentId,
        trailingGarbageBytes: Int
    ) {
        writerLock.withLock {
            val session = sessions.remove(docId) ?: return@withLock
            val dbFile = session.dbFile
            val walFile = File(dbFile.parentFile, "${dbFile.name}-wal")

            // Snapshot the active DB + WAL bytes BEFORE closing the connection
            // (because clean close checkpoints and removes the WAL file)
            val dbBytesBeforeClose = if (dbFile.exists()) dbFile.readBytes() else ByteArray(0)
            val walBytesBeforeClose = if (walFile.exists()) walFile.readBytes() else ByteArray(0)

            session.connection.close()

            if (walBytesBeforeClose.isNotEmpty()) {
                dbFile.writeBytes(dbBytesBeforeClose)
                val corruptedWal = ByteArray(walBytesBeforeClose.size + trailingGarbageBytes)
                System.arraycopy(walBytesBeforeClose, 0, corruptedWal, 0, walBytesBeforeClose.size)
                for (i in 0 until trailingGarbageBytes) {
                    corruptedWal[walBytesBeforeClose.size + i] = ((i * 37 + 19) and 0xFF).toByte()
                }
                FileOutputStream(walFile).use { fos ->
                    fos.write(corruptedWal)
                    fos.flush()
                    fos.fd.sync()
                }
            }
        }
    }
}
