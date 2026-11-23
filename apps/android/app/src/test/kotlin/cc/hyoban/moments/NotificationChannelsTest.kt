package cc.hyoban.moments

import android.app.NotificationManager
import cc.hyoban.moments.plugins.PhonePlugins
import cc.hyoban.moments.plugins.Plugins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationChannelsTest {
    // The phone's plugins, as the app installs them when it starts.
    init { Plugins.install(PhonePlugins.all) }

    private val channels = PhonePlugins.channels()

    private fun strings(folder: String): String =
        File("../shared/src/commonMain/composeResources/$folder/strings.xml").readText()

    @Test fun idsAreUnique() {
        val ids = channels.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        // No channel takes over another's id.
        assertTrue(channels.flatMap { it.replaces }.none { it in ids })
    }

    @Test fun eachIsNamedInEveryLanguage() {
        for (folder in listOf("values", "values-en")) {
            val words = strings(folder)
            channels.forEach { channel ->
                listOfNotNull(channel.name, channel.description).forEach {
                    assertTrue("${channel.id}: ${it.key} in $folder", "name=\"${it.key}\"" in words)
                }
            }
        }
    }

    /** What an installed phone already has: changing any of it would leave a channel behind or change its owner's settings. */
    @Test fun channelsStayAsTheyWere() {
        assertEquals(
            listOf(
                listOf("publishing", "notification_channel_publishing", NotificationManager.IMPORTANCE_LOW, false),
                listOf("ledger-payments-alert", "auto_bill_channel", NotificationManager.IMPORTANCE_HIGH, false),
                listOf("footprint-recording", "footprints_title", NotificationManager.IMPORTANCE_DEFAULT, true),
                listOf("footprint-recording-rest", "footprints_rest_channel", NotificationManager.IMPORTANCE_MIN, true),
                listOf("connection-watch", "connection_automation_title", NotificationManager.IMPORTANCE_LOW, false),
                listOf("updates", "update_channel", NotificationManager.IMPORTANCE_DEFAULT, false),
                listOf("camera_location", "camera_channel", NotificationManager.IMPORTANCE_LOW, false),
            ),
            channels.map { listOf(it.id, it.name.key, it.importance, it.quiet) },
        )
        assertTrue(channels.all { it.description == null })
    }
}
