package inkspace.core.mutation

import inkspace.core.error.EditError
import inkspace.core.id.GestureId
import inkspace.core.id.LayerId
import inkspace.core.id.ObjectId
import inkspace.core.id.Revision
import inkspace.core.id.SpaceId
import inkspace.core.id.UndoLabel
import inkspace.core.limits.Limits
import inkspace.core.model.DocVersion
import inkspace.core.outcome.Outcome
import inkspace.core.payload.ObjectPayload
import inkspace.core.read.DocumentRead
import inkspace.core.read.ResidencyState
import inkspace.core.unit.DocRect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Mutation

@Serializable
@SerialName("insert_object")
data class InsertObject(
    val space: SpaceId,
    val layer: LayerId,
    val id: ObjectId,
    val payload: ObjectPayload,
    val bounds: DocRect
) : Mutation

@Serializable
@SerialName("remove_object")
data class RemoveObject(
    val id: ObjectId
) : Mutation

@Serializable
@SerialName("update_payload")
data class UpdatePayload(
    val id: ObjectId,
    val newPayload: ObjectPayload,
    val newBounds: DocRect
) : Mutation

@Serializable
@SerialName("move_object")
data class MoveObject(
    val id: ObjectId,
    val deltaX: Double,
    val deltaY: Double
) : Mutation {
    init {
        require(deltaX.isFinite() && deltaY.isFinite()) { "Move deltas must be finite: ($deltaX, $deltaY)" }
    }
}

@Serializable
data class Precondition(
    val id: String,
    val expectedVersion: Long
)

@Serializable
data class EditPlan(
    val base: DocVersion,
    val preconditions: List<Precondition> = emptyList(),
    val mutations: List<Mutation>,
    val gesture: GestureId,
    val label: UndoLabel
) {
    init {
        require(mutations.isNotEmpty()) { "EditPlan must contain at least one mutation" }
    }
}

class ValidatedPlan internal constructor(val plan: EditPlan)

@Serializable
data class ChangeSet(
    val changedIds: Set<ObjectId>,
    val oldBounds: DocRect? = null,
    val newBounds: DocRect? = null
)

data class AppliedEdit(
    val revision: Revision,
    val changeSet: ChangeSet,
    val inversePlan: EditPlan
)

/**
 * Validates an EditPlan against the current DocumentRead state and limits.
 * Guarantees that either the entire plan is valid, or returns a typed EditError without partial mutation.
 */
fun validatePlan(document: DocumentRead, plan: EditPlan, limits: Limits): Outcome<ValidatedPlan, EditError> {
    if (plan.base.revision != document.version.revision) {
        return Outcome.Failure(
            EditError.StaleRevision(
                expected = plan.base.revision.value,
                actual = document.version.revision.value
            )
        )
    }

    // Verify preconditions
    for (precondition in plan.preconditions) {
        val state = document.objectState(ObjectId(precondition.id))
        if (state == ResidencyState.Missing) {
            return Outcome.Failure(
                EditError.PreconditionFailed("Object ${precondition.id} missing for precondition")
            )
        }
    }

    var pendingInserts = 0
    for (mutation in plan.mutations) {
        when (mutation) {
            is InsertObject -> {
                pendingInserts++
                val existing = document.objectState(mutation.id)
                if (existing != ResidencyState.Missing) {
                    return Outcome.Failure(
                        EditError.PreconditionFailed("Cannot insert object ${mutation.id.value}: already exists")
                    )
                }
            }
            is RemoveObject -> {
                val state = document.objectState(mutation.id)
                if (state == ResidencyState.Missing) {
                    return Outcome.Failure(
                        EditError.EntityNotFound("Cannot remove object ${mutation.id.value}: not found")
                    )
                }
                if (document.isLocked(mutation.id)) {
                    return Outcome.Failure(
                        EditError.LockedEntity("Cannot remove locked object ${mutation.id.value}")
                    )
                }
            }
            is UpdatePayload -> {
                val state = document.objectState(mutation.id)
                if (state == ResidencyState.Missing) {
                    return Outcome.Failure(
                        EditError.EntityNotFound("Cannot update object ${mutation.id.value}: not found")
                    )
                }
                if (document.isLocked(mutation.id)) {
                    return Outcome.Failure(
                        EditError.LockedEntity("Cannot update locked object ${mutation.id.value}")
                    )
                }
            }
            is MoveObject -> {
                val state = document.objectState(mutation.id)
                if (state == ResidencyState.Missing) {
                    return Outcome.Failure(
                        EditError.EntityNotFound("Cannot move object ${mutation.id.value}: not found")
                    )
                }
                if (document.isLocked(mutation.id)) {
                    return Outcome.Failure(
                        EditError.LockedEntity("Cannot move locked object ${mutation.id.value}")
                    )
                }
            }
        }
    }

    val totalObjects = document.totalObjectCount + pendingInserts
    if (totalObjects > limits.maxObjectsPerSpace) {
        return Outcome.Failure(
            EditError.LimitExceeded(
                limit = "maxObjectsPerSpace",
                current = totalObjects.toLong(),
                max = limits.maxObjectsPerSpace.toLong()
            )
        )
    }

    return Outcome.Success(ValidatedPlan(plan))
}
