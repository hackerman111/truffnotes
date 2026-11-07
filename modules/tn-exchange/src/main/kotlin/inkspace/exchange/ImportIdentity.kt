package inkspace.exchange

sealed interface ImportIdentity {
    data object NewDocument : ImportIdentity
    data object OpenMatchingGeneration : ImportIdentity
    data class ImportAsCopy(val newDocumentId: String) : ImportIdentity
}

/** IDs alone never grant overwrite permission. A caller must compare the full archive generation digest. */
fun resolveImportIdentity(incomingId: String, existingGeneration: String?, incomingGeneration: String,
    requestedCopyId: String? = null): ImportIdentity {
    if (requestedCopyId != null) {
        if (requestedCopyId.isBlank() || requestedCopyId == incomingId) throw ExchangeException("Import copy requires a fresh document ID")
        return ImportIdentity.ImportAsCopy(requestedCopyId)
    }
    if (existingGeneration == null) return ImportIdentity.NewDocument
    if (existingGeneration == incomingGeneration) return ImportIdentity.OpenMatchingGeneration
    throw ExchangeException("Document ID collision: explicitly import as copy")
}
