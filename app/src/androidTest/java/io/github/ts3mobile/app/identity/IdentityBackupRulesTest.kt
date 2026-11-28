package io.github.ts3mobile.app.identity

import android.content.pm.ApplicationInfo
import android.content.res.XmlResourceParser
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class IdentityBackupRulesTest {
    @Test fun packagedRulesExcludeActualDataStoreFromEveryBackupAndTransferSection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.preferencesDataStoreFile(IDENTITY_DATA_STORE_NAME).relativeTo(context.filesDir).invariantSeparatorsPath
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(
            listOf("full-backup-content"),
            context.resources.getXml(R.xml.backup_rules).use { exclusionSections(it, path) },
        )
        assertEquals(
            listOf("cloud-backup", "device-transfer"),
            context.resources.getXml(R.xml.data_extraction_rules).use { exclusionSections(it, path) },
        )
    }

    private fun exclusionSections(
        parser: XmlResourceParser,
        path: String,
    ): List<String> {
        val parents = ArrayDeque<String>()
        val sections = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "file" &&
                        parser.getAttributeValue(null, "path") == path
                    ) {
                        sections += parents.last()
                    }
                    parents.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> parents.removeLast()
            }
            parser.next()
        }
        return sections
    }
}
