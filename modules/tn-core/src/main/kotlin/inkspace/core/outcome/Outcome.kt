package inkspace.core.outcome

sealed interface Outcome<out T, out E> {
    val isSuccess: Boolean
    val isFailure: Boolean

    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): E? = when (this) {
        is Success -> null
        is Failure -> error
    }

    data class Success<out T>(val value: T) : Outcome<T, Nothing> {
        override val isSuccess: Boolean get() = true
        override val isFailure: Boolean get() = false
    }

    data class Failure<out E>(val error: E) : Outcome<Nothing, E> {
        override val isSuccess: Boolean get() = false
        override val isFailure: Boolean get() = true
    }
}

inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T, E> Outcome<T, E>.onSuccess(action: (T) -> Unit): Outcome<T, E> {
    if (this is Outcome.Success) action(value)
    return this
}

inline fun <T, E> Outcome<T, E>.onFailure(action: (E) -> Unit): Outcome<T, E> {
    if (this is Outcome.Failure) action(error)
    return this
}
