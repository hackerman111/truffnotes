-- 0001_document.sql: Authoritative Document SQLite Schema v1

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
