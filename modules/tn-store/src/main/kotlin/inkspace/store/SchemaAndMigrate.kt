package inkspace.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal object SchemaAndMigrate {

    const val SCHEMA_VERSION = 1

    fun configureAndMigrate(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA journal_mode = WAL")
        connection.execSQL("PRAGMA synchronous = FULL")
        connection.execSQL("PRAGMA foreign_keys = ON")

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY NOT NULL,
                applied_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        val currentVersion = queryCurrentVersion(connection)
        if (currentVersion < SCHEMA_VERSION) {
            val sqlText = loadMigrationSql()
            connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
            try {
                for (stmt in splitSqlStatements(sqlText)) {
                    connection.execSQL(stmt)
                }
                val now = System.currentTimeMillis()
                connection.prepare("INSERT OR REPLACE INTO schema_migrations(version, applied_at_ms) VALUES (?, ?)").use { st ->
                    st.bindLong(1, SCHEMA_VERSION.toLong())
                    st.bindLong(2, now)
                    st.step()
                }
                connection.execSQL("COMMIT")
            } catch (t: Throwable) {
                runCatching { connection.execSQL("ROLLBACK") }
                throw t
            }
        }
    }

    private fun queryCurrentVersion(connection: SQLiteConnection): Int {
        connection.prepare("SELECT COALESCE(MAX(version), 0) FROM schema_migrations").use { st ->
            return if (st.step()) st.getLong(0).toInt() else 0
        }
    }

    fun inspectPragmas(connection: SQLiteConnection): SqlitePragmaReport {
        val journalMode = connection.prepare("PRAGMA journal_mode").use { st ->
            if (st.step()) st.getText(0) else ""
        }
        val synchronous = connection.prepare("PRAGMA synchronous").use { st ->
            if (st.step()) st.getLong(0).toInt() else -1
        }
        val foreignKeys = connection.prepare("PRAGMA foreign_keys").use { st ->
            if (st.step()) st.getLong(0).toInt() else -1
        }
        return SqlitePragmaReport(
            journalMode = journalMode,
            synchronous = synchronous,
            foreignKeys = foreignKeys
        )
    }

    fun verifyIntegrity(connection: SQLiteConnection): Boolean {
        connection.prepare("PRAGMA integrity_check").use { st ->
            if (!st.step()) return false
            val result = st.getText(0)
            return result.equals("ok", ignoreCase = true)
        }
    }

    private fun loadMigrationSql(): String {
        val stream = SchemaAndMigrate::class.java.getResourceAsStream("/migrations/0001_document.sql")
            ?: SchemaAndMigrate::class.java.classLoader?.getResourceAsStream("migrations/0001_document.sql")
        if (stream != null) {
            return stream.bufferedReader().use { it.readText() }
        }
        return FALLBACK_MIGRATION_0001
    }

    private fun splitSqlStatements(sql: String): List<String> {
        val cleaned = sql.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
        return cleaned.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private val FALLBACK_MIGRATION_0001 =
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version INTEGER PRIMARY KEY NOT NULL,
            applied_at_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS document_meta (
            document_id TEXT PRIMARY KEY NOT NULL,
            document_kind TEXT NOT NULL,
            durable_revision INTEGER NOT NULL CHECK (durable_revision >= 0),
            updated_at_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS pages (
            page_id TEXT PRIMARY KEY NOT NULL,
            space_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            width_pt REAL NOT NULL CHECK (width_pt > 0.0),
            height_pt REAL NOT NULL CHECK (height_pt > 0.0)
        );
        CREATE TABLE IF NOT EXISTS layers (
            layer_id TEXT PRIMARY KEY NOT NULL,
            page_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            name TEXT NOT NULL,
            opacity REAL NOT NULL CHECK (opacity >= 0.0 AND opacity <= 1.0),
            is_visible INTEGER NOT NULL CHECK (is_visible IN (0, 1)),
            is_locked INTEGER NOT NULL CHECK (is_locked IN (0, 1)),
            FOREIGN KEY (page_id) REFERENCES pages(page_id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS scene_objects (
            object_id TEXT PRIMARY KEY NOT NULL,
            space_id TEXT NOT NULL,
            layer_id TEXT NOT NULL,
            min_x REAL NOT NULL,
            min_y REAL NOT NULL,
            max_x REAL NOT NULL,
            max_y REAL NOT NULL,
            version INTEGER NOT NULL CHECK (version >= 1),
            is_locked INTEGER NOT NULL CHECK (is_locked IN (0, 1)),
            payload_digest INTEGER NOT NULL,
            encoded_cbor BLOB NOT NULL,
            FOREIGN KEY (layer_id) REFERENCES layers(layer_id) ON DELETE RESTRICT
        );
        CREATE INDEX IF NOT EXISTS idx_scene_objects_space_bounds
            ON scene_objects(space_id, min_x, max_x, min_y, max_y);
        CREATE INDEX IF NOT EXISTS idx_scene_objects_layer
            ON scene_objects(layer_id);
        CREATE TABLE IF NOT EXISTS transaction_log (
            transaction_id TEXT PRIMARY KEY NOT NULL,
            base_revision INTEGER NOT NULL,
            new_revision INTEGER NOT NULL UNIQUE,
            writeset_cbor BLOB NOT NULL,
            committed_at_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS asset_refs (
            asset_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
            sha256_hex TEXT NOT NULL,
            PRIMARY KEY (asset_id, revision)
        );
        CREATE TABLE IF NOT EXISTS checkpoint_versions (
            version_id TEXT PRIMARY KEY NOT NULL,
            revision INTEGER NOT NULL,
            label TEXT NOT NULL,
            is_pinned INTEGER NOT NULL CHECK (is_pinned IN (0, 1)),
            backup_rel_path TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS spilled_history (
            seq_id INTEGER PRIMARY KEY AUTOINCREMENT,
            gesture_id TEXT NOT NULL,
            label TEXT NOT NULL,
            forward_cbor BLOB NOT NULL,
            inverse_cbor BLOB NOT NULL,
            byte_size INTEGER NOT NULL
        );
        """.trimIndent()
}
