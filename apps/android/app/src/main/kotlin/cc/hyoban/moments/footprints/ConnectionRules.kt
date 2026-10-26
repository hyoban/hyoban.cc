package cc.hyoban.moments.footprints

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionRules(
    val enabled: Boolean = false,
    val wifi: Set<String> = emptySet(),
    /** Stable device addresses, with their last user-visible names. No scanning or pairing. */
    val bluetooth: Map<String, String> = emptyMap(),
) {
    val hasTargets get() = wifi.isNotEmpty() || bluetooth.isNotEmpty()
}

enum class ConnectionKind { WIFI, BLUETOOTH }

data class ConnectionSignal(val kind: ConnectionKind, val id: String, val connected: Boolean) {
    val start get() = if (kind == ConnectionKind.WIFI) !connected else connected
}

/** An automatic start resumes an existing trip; otherwise it enables daily recording. */
fun ConnectionSignal.command(state: RecordingState): String? = when {
    state.recording == start -> null
    !start -> "pause"
    state.requested -> "resume"
    else -> "daily_on"
}

/**
 * Pure transition detector. Compare settled snapshots, not individual radio callbacks:
 * roaming/reconnecting within the debounce window produces no action. Unknown Wi-Fi
 * identity means permission/redaction, never a fabricated disconnection.
 */
class ConnectionTransitions(private val rules: ConnectionRules) {
    private var wifiBefore: Set<String>? = null
    private var wifiNow: Set<String>? = null
    private val bluetoothBefore = mutableMapOf<String, Boolean>()
    private val bluetoothNow = mutableMapOf<String, Boolean>()

    fun wifi(ssids: Set<String>?, seed: Boolean = false) {
        wifiNow = ssids?.intersect(rules.wifi)
        if (seed || ssids == null || wifiBefore == null) wifiBefore = wifiNow
    }

    fun bluetooth(address: String, connected: Boolean, seed: Boolean = false): Boolean {
        if (address !in rules.bluetooth) return false
        // A late profile snapshot cannot overwrite a newer ACL event.
        if (seed && address in bluetoothNow) return false
        val changed = bluetoothNow[address] != connected
        bluetoothNow[address] = connected
        if (seed) bluetoothBefore[address] = connected
        return changed && !seed
    }

    fun bluetoothOff() {
        bluetoothNow.keys.toList().forEach { bluetoothNow[it] = false }
    }

    fun current(signal: ConnectionSignal): Boolean = when (signal.kind) {
        ConnectionKind.WIFI -> wifiNow?.let { if (signal.connected) signal.id in it else it.isEmpty() } ?: false
        ConnectionKind.BLUETOOTH -> if (signal.connected) bluetoothNow[signal.id] == true
            else bluetoothNow[signal.id] == false && bluetoothNow.values.none { it }
    }

    /** Consume the settled changes and return at most one action, with Bluetooth taking priority. */
    fun settle(state: RecordingState): ConnectionSignal? {
        val changes = mutableListOf<ConnectionSignal>()
        val before = wifiBefore
        val now = wifiNow
        if (before != null && now != null && before.isEmpty() != now.isEmpty()) {
            changes += ConnectionSignal(ConnectionKind.WIFI, (if (now.isEmpty()) before else now).sorted().first(), now.isNotEmpty())
        }
        wifiBefore = now
        val previouslyConnected = bluetoothBefore.values.any { it }
        val connected = bluetoothNow.values.any { it }
        if (connected && !previouslyConnected) {
            changes += ConnectionSignal(ConnectionKind.BLUETOOTH, bluetoothNow.filterValues { it }.keys.sorted().first(), true)
        } else if (!connected) {
            val disconnected = bluetoothNow.keys.filter { bluetoothNow[it] == false && bluetoothBefore[it] != false }
            if (disconnected.isNotEmpty()) changes += ConnectionSignal(ConnectionKind.BLUETOOTH, disconnected.sorted().first(), false)
        }
        bluetoothBefore.clear()
        bluetoothBefore.putAll(bluetoothNow)
        if (changes.isEmpty()) return null // Startup snapshots never undo a manual choice.
        // Resolve priority before checking the recorder state. Otherwise the same conflicting
        // connections could alternate between start and pause depending on the current state.
        val chosen = if (connected) {
            ConnectionSignal(ConnectionKind.BLUETOOTH, bluetoothNow.filterValues { it }.keys.sorted().first(), true)
        } else changes.last() // Bluetooth disconnection takes precedence over simultaneous Wi-Fi changes.
        return chosen.takeIf { it.command(state) != null && current(it) }
    }
}
