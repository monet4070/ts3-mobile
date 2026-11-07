package io.github.ts3mobile.app.bookmarks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface BookmarkStore {
    suspend fun read(): List<ServerBookmark>

    suspend fun save(entry: ServerBookmark): List<ServerBookmark>

    suspend fun saveValidated(
        entry: ServerBookmark,
        contextValid: () -> Boolean,
    ): List<ServerBookmark> {
        if (!contextValid()) throw BookmarkContextChanged()
        return save(entry)
    }

    suspend fun remove(entry: ServerBookmark): List<ServerBookmark>
}

data class BookmarkState(
    val entries: List<ServerBookmark> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val contextChanged: Boolean = false,
)

internal class BookmarkController(private val scope: CoroutineScope, private val store: BookmarkStore) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BookmarkState())
    val state = mutableState.asStateFlow()

    init {
        reload()
    }

    fun reload() = operation { store.read() }

    fun save(entry: ServerBookmark) = operation { store.save(entry) }

    fun saveValidated(
        entry: ServerBookmark,
        contextValid: () -> Boolean,
    ) = operation { store.saveValidated(entry, contextValid) }

    fun remove(entry: ServerBookmark) = operation { store.remove(entry) }

    private fun operation(action: suspend () -> List<ServerBookmark>) {
        scope.launch {
            mutex.withLock {
                mutableState.update { it.copy(busy = true, failed = false, contextChanged = false) }
                try {
                    mutableState.value = BookmarkState(action(), loading = false)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(
                            loading = false,
                            busy = false,
                            failed = true,
                            contextChanged = error is BookmarkContextChanged,
                        )
                    }
                }
            }
        }
    }
}
