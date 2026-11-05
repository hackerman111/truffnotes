package inkspace.store

enum class FaultPoint {
    NONE,
    DISK_FULL_ON_ASSET_WRITE,
    CRASH_AFTER_ASSET_FSYNC_BEFORE_DB_COMMIT,
    DISK_FULL_ON_DB_COMMIT,
    CRASH_DURING_DB_TRANSACTION,
    CRASH_AFTER_DB_COMMIT_BEFORE_ACK
}

class FaultInjectionController(
    @Volatile var nextFault: FaultPoint = FaultPoint.NONE
) {
    fun consumeFault(target: FaultPoint): Boolean {
        if (nextFault == target) {
            nextFault = FaultPoint.NONE
            return true
        }
        return false
    }
}

data class SqlitePragmaReport(
    val journalMode: String,
    val synchronous: Int,
    val foreignKeys: Int
)
