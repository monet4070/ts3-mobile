package io.github.ts3mobile.app.identity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.ts3mobile.app.crypto.KeystorePayloadCipher
import io.github.ts3mobile.app.crypto.PayloadKeyAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Synthetic data and deterministic failure boundaries; no Android or installed state. */
internal class IdentityStoreFixture : DataStore<Preferences>, PayloadKeyAccess {
    val values = MutableStateFlow(emptyPreferences())
    override val data = values
    private val mutex = Mutex()
    var before: suspend (Int) -> Unit = { }
    var after: suspend (Int) -> Unit = { }
    var calls = 0
    var writes = 0
    var existing: SecretKey? = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    var keyCreations = 0
    val cipher = KeystorePayloadCipher(this)
    val storage get() = IdentityStore(this, cipher)

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val call = ++calls
            before(call)
            val next = transform(values.value)
            if (next != values.value) writes++
            values.value = next
            after(call)
            next
        }

    override fun findExistingKey(): SecretKey? = existing

    override fun getOrCreateKeyForNewData(): SecretKey {
        keyCreations++
        return existing ?: SecretKeySpec(ByteArray(32) { 9 }, "AES").also { existing = it }
    }
}
