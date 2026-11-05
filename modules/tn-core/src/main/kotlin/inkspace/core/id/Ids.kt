package inkspace.core.id

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SpaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SpaceId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class PageId(val value: String) {
    init {
        require(value.isNotBlank()) { "PageId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class LayerId(val value: String) {
    init {
        require(value.isNotBlank()) { "LayerId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class ObjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "ObjectId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class AssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "AssetId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class VersionId(val value: String) {
    init {
        require(value.isNotBlank()) { "VersionId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class GestureId(val value: String) {
    init {
        require(value.isNotBlank()) { "GestureId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class UndoLabel(val value: String) {
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class TransactionId(val value: String) {
    init {
        require(value.isNotBlank()) { "TransactionId cannot be blank" }
    }
    override fun toString(): String = value
}

@Serializable
@JvmInline
value class Revision(val value: Long) : Comparable<Revision> {
    init {
        require(value >= 0L) { "Revision must be non-negative: $value" }
    }
    override fun compareTo(other: Revision): Int = value.compareTo(other.value)
    fun next(): Revision = Revision(value + 1L)
    override fun toString(): String = value.toString()
}

@Serializable
@JvmInline
value class SessionEpoch(val value: Long) {
    init {
        require(value >= 0L) { "SessionEpoch must be non-negative" }
    }
    override fun toString(): String = value.toString()
}

@Serializable
@JvmInline
value class RequestGeneration(val value: Long) {
    init {
        require(value >= 0L) { "RequestGeneration must be non-negative" }
    }
    override fun toString(): String = value.toString()
}

@Serializable
@JvmInline
value class SurfaceEpoch(val value: Long) {
    init {
        require(value >= 0L) { "SurfaceEpoch must be non-negative: $value" }
    }
    fun next(): SurfaceEpoch = SurfaceEpoch(value + 1L)
    override fun toString(): String = value.toString()
}

@Serializable
@JvmInline
value class ResizeGeneration(val value: Long) {
    init {
        require(value >= 0L) { "ResizeGeneration must be non-negative: $value" }
    }
    fun next(): ResizeGeneration = ResizeGeneration(value + 1L)
    override fun toString(): String = value.toString()
}
