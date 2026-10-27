package cc.hyoban.moments.footprints

import org.junit.Assert.*
import org.junit.Test

class GnssStatusDiagnosticsTest {
    private fun fields(diagnostics: GnssStatusDiagnostics, now: Long) = diagnostics.take(now).toMap()

    @Test fun fixCarriesLatestStatusAndTheGapsSinceThePreviousFix() {
        val d = GnssStatusDiagnostics()
        d.status(GnssSummary(used = 6, visible = 20, topCn0 = 31.5, l5Used = 2), 1_000)
        d.status(GnssSummary(used = 8, visible = 22, topCn0 = 34.0, l5Used = 3), 2_000)
        d.status(GnssSummary(used = 9, visible = 22, topCn0 = 35.2, l5Used = 3), 14_000)
        val first = fields(d, 14_500)
        assertEquals(3, first["gnss_callbacks"])
        assertEquals(12_000L, first["gnss_max_gap_ms"])
        assertEquals(500L, first["gnss_status_age_ms"])
        assertEquals(9, first["sats_used"])
        assertEquals(35.2, first["cn0_top4_dbhz"])
        // A receiver that went quiet between fixes reports no new callbacks, only an older status.
        val second = fields(d, 29_000)
        assertEquals(0, second["gnss_callbacks"])
        assertNull(second["gnss_max_gap_ms"])
        assertEquals(15_000L, second["gnss_status_age_ms"])
    }

    @Test fun resetForgetsAnEarlierRequest() {
        val d = GnssStatusDiagnostics()
        d.status(GnssSummary(4, 10, 25.0, 0), 1_000)
        d.reset()
        val fields = fields(d, 2_000)
        assertNull(fields["sats_used"])
        assertNull(fields["gnss_status_age_ms"])
    }
}
