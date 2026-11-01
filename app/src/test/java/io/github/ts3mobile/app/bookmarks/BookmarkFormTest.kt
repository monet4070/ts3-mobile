package io.github.ts3mobile.app.bookmarks

import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.settings.ConnectionFormEdits
import io.github.ts3mobile.app.settings.RememberedConnection
import io.github.ts3mobile.app.settings.restoreRememberedConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkFormTest {
    @Test fun selectingBookmarkAlwaysReplacesOldPasswordAndNickname() {
        val form = ConnectionFormState("old.example.com", "9987", "Old nickname", "old-password")
        val bookmark = ServerBookmark("fixture", name = "Fixture", host = "new.example.com", nickname = "New nickname")
        val selected = form.withSelectedBookmark(bookmark)
        assertEquals("new.example.com", selected.host)
        assertEquals("New nickname", selected.nickname)
        assertEquals("", selected.password)
        assertEquals(
            selected,
            selected.restoreRememberedConnection(
                RememberedConnection(
                    "old.example.com",
                    "9987",
                    "Old",
                    "old-password",
                ),
                ConnectionFormEdits(
                    true,
                    true,
                    true,
                    true,
                ),
            ),
        )
    }
}
