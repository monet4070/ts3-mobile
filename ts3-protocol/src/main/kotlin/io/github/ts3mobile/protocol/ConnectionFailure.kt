package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.CommandProcessException
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Closed failure facts produced by the protocol, never inferred again by the app. */
enum class ConnectionFailureCategory {
    UNCLASSIFIED,
    CLONES_LIMIT,
}

fun Throwable.connectionFailureCategory(): ConnectionFailureCategory = classifyConnectionFailure().category

fun Throwable.isRetryableConnectionFailure(): Boolean = classifyConnectionFailure().retryable

/** The first protocol error takes precedence over network wrappers and deeper causes. */
private fun Throwable.classifyConnectionFailure(): ClassifiedFailure {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    var networkFailure = false
    while (current != null && visited.add(current)) {
        if (current is CommandProcessException) {
            val clones = current.message?.contains(CLONES_LIMIT_TEXT, ignoreCase = true) == true
            return ClassifiedFailure(
                if (clones) ConnectionFailureCategory.CLONES_LIMIT else ConnectionFailureCategory.UNCLASSIFIED,
                clones,
            )
        }
        if (current is CommandException) return ClassifiedFailure(ConnectionFailureCategory.UNCLASSIFIED, false)
        if (current is IOException || current is TimeoutException || current is InterruptedException) networkFailure = true
        current = current.cause
    }
    return ClassifiedFailure(ConnectionFailureCategory.UNCLASSIFIED, networkFailure)
}

private data class ClassifiedFailure(val category: ConnectionFailureCategory, val retryable: Boolean)

/** Only this observed CommandProcessException text qualifies; no error code is guessed. */
private const val CLONES_LIMIT_TEXT = "too many clones already connected"
