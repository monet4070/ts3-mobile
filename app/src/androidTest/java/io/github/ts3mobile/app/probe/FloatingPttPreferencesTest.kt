package io.github.ts3mobile.app.probe

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class FloatingPttPreferencesTest {
    @Test fun oldStyleChoiceSurvivesAndTheRadioChoiceUsesTheSamePreference() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureName = "ptt_style_fixture_${UUID.randomUUID()}"
        val preferences = target.getSharedPreferences(fixtureName, Context.MODE_PRIVATE)
        val context =
            object : ContextWrapper(target) {
                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences = preferences
            }
        try {
            assertEquals(FloatingPttStyle.STANDARD, context.floatingPttStyle)
            preferences.edit().putBoolean("minimal_style", true).apply()
            assertEquals(FloatingPttStyle.MINIMAL, context.floatingPttStyle)
            context.floatingPttStyle = FloatingPttStyle.STANDARD
            assertEquals(false, preferences.getBoolean("minimal_style", true))
            context.floatingPttStyle = FloatingPttStyle.MINIMAL
            assertEquals(true, preferences.getBoolean("minimal_style", false))
        } finally {
            target.deleteSharedPreferences(fixtureName)
        }
    }
}
