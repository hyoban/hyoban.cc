package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test

class ConnectionRulesTest {
    private val rules = ConnectionRules(true, setOf("Home", "Office"), mapOf("A" to "Car", "B" to "Headphones"))
    private val on = RecordingState(daily = true)
    private val off = RecordingState()
    private fun engine() = ConnectionTransitions(rules)

    @Test fun startupSnapshotsNeverChangeRecording() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.bluetooth("A", true, seed = true)
        assertNull(e.settle(on))
        assertNull(e.settle(off))
    }

    @Test fun wifiPausesOnArrivalAndStartOnDeparture() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.wifi(setOf("Home"))
        val arrival = requireNotNull(e.settle(on))
        assertEquals("pause", arrival.command(on))
        e.wifi(emptySet())
        assertFalse(e.current(arrival))
        val departure = requireNotNull(e.settle(off))
        assertEquals("daily_on", departure.command(off))
        e.wifi(setOf("Office"))
        assertFalse(e.current(departure))
    }

    @Test fun bluetoothStartsOnConnectionAndPauseOnDisconnection() {
        val e = engine()
        e.bluetooth("A", true)
        assertEquals("daily_on", requireNotNull(e.settle(off)).command(off))
        e.bluetooth("A", false)
        assertEquals("pause", requireNotNull(e.settle(on)).command(on))
    }

    @Test fun unrelatedConnectionsAndAlreadySatisfiedActionsAreIgnored() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.wifi(setOf("Cafe"))
        assertFalse(e.bluetooth("Other", true))
        assertNull(e.settle(on))
        e.wifi(setOf("Home"))
        assertNull(e.settle(off))
        e.bluetooth("A", true)
        assertNull(e.settle(on))
    }

    @Test fun briefInterruptionsAndRoamingBetweenSelectedNetworksAreSilent() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.bluetooth("A", true, seed = true)
        e.wifi(emptySet())
        e.wifi(setOf("Office"))
        e.bluetooth("A", false)
        e.bluetooth("A", true)
        assertNull(e.settle(on))
        assertNull(e.settle(off))
    }

    @Test fun lossOfWifiIdentityCannotBeMistakenForLeavingHome() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.wifi(null)
        assertFalse(e.current(ConnectionSignal(ConnectionKind.WIFI, "Home", false)))
        assertNull(e.settle(off))
        e.wifi(emptySet())
        assertNull(e.settle(off))
    }

    @Test fun resolvingNewWifiDefersActionsWithoutLosingTheArrival() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.resolvingWifi(true)
        assertNull(e.settle(on))
        e.resolvingWifi(false)
        e.wifi(setOf("Home"))
        assertEquals("pause", requireNotNull(e.settle(on)).command(on))
    }

    @Test fun switchingWifiWaitsForIdentityAndStillDetectsDeparture() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.resolvingWifi(true)
        assertFalse(e.current(ConnectionSignal(ConnectionKind.WIFI, "Home", true)))
        assertNull(e.settle(off))
        e.resolvingWifi(false)
        e.wifi(setOf("Cafe"))
        assertEquals("daily_on", requireNotNull(e.settle(off)).command(off))
    }

    @Test fun bluetoothDoesNotWaitForWifiIdentity() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.resolvingWifi(true)
        e.bluetooth("A", true)
        assertEquals(ConnectionKind.BLUETOOTH, requireNotNull(e.settle(off)).kind)
    }

    @Test fun manualRecordingChoiceWinsOverPendingWifiArrival() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.resolvingWifi(true)
        e.settle(on, manual = true)
        e.resolvingWifi(false)
        e.wifi(setOf("Home"))
        assertNull(e.settle(on))
        e.wifi(emptySet())
        assertEquals("daily_on", requireNotNull(e.settle(off)).command(off))
    }

    @Test fun failedWifiLookupDoesNotInventADeparture() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.resolvingWifi(true)
        e.resolvingWifi(false)
        e.wifi(null)
        assertNull(e.settle(off))
    }

    @Test fun onlyLastSelectedBluetoothDisconnectionPauses() {
        val e = engine()
        e.bluetooth("A", true, seed = true)
        e.bluetooth("B", true, seed = true)
        e.bluetooth("A", false)
        assertNull(e.settle(on))
        e.bluetooth("B", false)
        val signal = requireNotNull(e.settle(on))
        assertEquals("B", signal.id)
        assertTrue(e.current(signal))
        e.bluetooth("A", true)
        assertFalse(e.current(signal))
    }

    @Test fun lateBluetoothSnapshotDoesNotOverwriteAnObservedDisconnection() {
        val e = engine()
        e.bluetooth("A", false)
        e.bluetooth("A", true, seed = true)
        assertFalse(requireNotNull(e.settle(on)).connected)
        assertNull(e.settle(on))
    }

    @Test fun switchingBluetoothOffCountsAsDisconnection() {
        val e = engine()
        e.bluetooth("A", true, seed = true)
        e.bluetoothOff()
        assertEquals("pause", requireNotNull(e.settle(on)).command(on))
    }

    @Test fun automaticStartsResumeTripsWithoutStartingAnotherSession() {
        val start = ConnectionSignal(ConnectionKind.BLUETOOTH, "A", true)
        val pause = ConnectionSignal(ConnectionKind.BLUETOOTH, "A", false)
        assertEquals("resume", start.command(RecordingState(session = "trip", paused = true)))
        assertEquals("resume", start.command(RecordingState(daily = true, paused = true)))
        assertNull(start.command(on))
        assertNull(pause.command(off))
        assertEquals("pause", pause.command(RecordingState(session = "trip")))
    }

    @Test fun bluetoothWinsSimultaneousArrivalRegardlessOfCurrentRecorderState() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.wifi(setOf("Home"))
        e.bluetooth("A", true)
        val signal = requireNotNull(e.settle(off))
        assertEquals(ConnectionKind.BLUETOOTH, signal.kind)
        assertTrue(signal.start)
        val recordingEngine = engine()
        recordingEngine.wifi(emptySet(), seed = true)
        recordingEngine.wifi(setOf("Home"))
        recordingEngine.bluetooth("A", true)
        assertNull(recordingEngine.settle(on))
    }

    @Test fun wifiArrivalCannotPauseAnAlreadyConnectedSelectedBluetoothDevice() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.bluetooth("A", true, seed = true)
        e.wifi(setOf("Home"))
        assertNull(e.settle(on))
    }

    @Test fun simultaneousDisconnectionsUseBluetoothPauseRatherThanWifiStart() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.bluetooth("A", true, seed = true)
        e.wifi(emptySet())
        e.bluetooth("A", false)
        assertEquals("pause", requireNotNull(e.settle(on)).command(on))
        assertNull(e.settle(off))
    }

    @Test fun realReconnectAndDepartureAreNotLostToANotificationCooldown() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        repeat(3) {
            e.wifi(emptySet())
            assertEquals("daily_on", requireNotNull(e.settle(off)).command(off))
            e.wifi(setOf("Home"))
            assertEquals("pause", requireNotNull(e.settle(on)).command(on))
        }
    }

    @Test fun consumingPendingChangesAfterAManualChoiceDoesNotReplayThem() {
        val e = engine()
        e.wifi(emptySet(), seed = true)
        e.wifi(setOf("Home"))
        e.settle(off) // Manual pause consumes this pending arrival.
        assertNull(e.settle(on)) // A later manual resume stays resumed.
    }
    @Test fun companionInitialSnapshotAndRepeatedConnectedEventPreserveManualChoice() {
        val e = engine()
        assertFalse(e.restoreBluetoothTransition("A", null, true))
        assertNull(e.settle(off))
        assertFalse(e.restoreBluetoothTransition("A", true, true))
        assertNull(e.settle(off))
    }

    @Test fun companionCanRestoreARealTransitionAfterProcessRecreation() {
        val e = engine()
        e.wifi(setOf("Home"), seed = true)
        e.bluetooth("A", true, seed = true) // Connection is already visible in a fresh snapshot.
        assertTrue(e.restoreBluetoothTransition("A", false, true))
        assertEquals("daily_on", requireNotNull(e.settle(off)).command(off))
        assertTrue(e.restoreBluetoothTransition("A", true, false))
        assertEquals("pause", requireNotNull(e.settle(on)).command(on))
    }

    @Test fun restoredDisconnectionCannotPauseAnotherConnectedDevice() {
        val e = engine()
        e.bluetooth("B", true, seed = true)
        e.restoreBluetoothTransition("A", true, false)
        assertNull(e.settle(on))
        assertFalse(e.restoreBluetoothTransition("Other", false, true))
    }
}
