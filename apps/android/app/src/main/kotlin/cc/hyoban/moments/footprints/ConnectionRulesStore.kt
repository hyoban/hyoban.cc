package cc.hyoban.moments.footprints

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Small local-only settings file, independent of the track database and API export. Calls use IO. */
class ConnectionRulesStore(context: Context) {
    // Preserve the original filename so existing device selections survive upgrades.
    private val file = AtomicFile(File(context.noBackupFilesDir, "connection-reminders.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val _rules = MutableStateFlow(ConnectionRules())
    val rules = _rules.asStateFlow()

    @Synchronized fun load(): ConnectionRules {
        if (!loaded) {
            if (file.baseFile.exists()) _rules.value = file.openRead().bufferedReader().use {
                json.decodeFromString(ConnectionRules.serializer(), it.readText())
            }
            loaded = true
        }
        return _rules.value
    }

    @Synchronized fun save(rules: ConnectionRules) {
        val next = rules.copy(enabled = rules.enabled && rules.hasTargets)
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(ConnectionRules.serializer(), next).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        loaded = true
        _rules.value = next
    }
}
