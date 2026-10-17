package io.github.ts3mobile.app.settings

import io.github.ts3mobile.app.ConnectionFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RememberedConnectionTest {
    private val saved = RememberedConnection("saved.example.com", "10011", "测试昵称", "fixture-password")

    @Test fun allFieldsIncludingUnicodeAndEmptyPasswordsRoundTrip() {
        assertEquals(saved, RememberedConnectionCodec.decode(RememberedConnectionCodec.encode(saved)))
        val emptyPassword = saved.copy(password = "")
        assertEquals(emptyPassword, RememberedConnectionCodec.decode(RememberedConnectionCodec.encode(emptyPassword)))
    }

    @Test fun valuesAreNeverIncludedInTheSnapshotString() {
        assertFalse(saved.toString().contains(saved.password))
        assertFalse(saved.toString().contains(saved.host))
        assertFalse(saved.toString().contains(saved.nickname))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownVersionsAreRejected() {
        RememberedConnectionCodec.decode(byteArrayOf(0, 0, 0, 2))
    }

    @Test fun startupRestoresAllFourFields() {
        val form = ConnectionFormState().restoreRememberedConnection(saved, ConnectionFormEdits())
        assertEquals(saved, form.toRememberedConnection())
        assertFalse(form.submitted)
    }

    @Test fun lateDiskLoadPreservesNicknameAndPasswordEdits() {
        val form =
            ConnectionFormState(nickname = "typed", password = "new-fixture")
                .restoreRememberedConnection(saved, ConnectionFormEdits(nickname = true, password = true))
        assertEquals(saved.host, form.host)
        assertEquals("typed", form.nickname)
        assertEquals("new-fixture", form.password)
    }

    @Test fun oldPasswordCannotBeRestoredToAnEditedEndpoint() {
        val form =
            ConnectionFormState(host = "other.example.com")
                .restoreRememberedConnection(saved, ConnectionFormEdits(host = true))
        assertEquals("other.example.com", form.host)
        assertEquals("", form.password)
    }
}
