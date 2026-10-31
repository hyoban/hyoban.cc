package cc.hyoban.moments.footprints

import android.location.GnssStatus

/**
 * Satellite status between fixes, without positions. Gaps between status callbacks show whether
 * the receiver keeps tracking or duty-cycles between requested fixes.
 */
internal class GnssStatusDiagnostics {
    private var callbacks = 0
    private var lastCallback = 0L
    private var maxGap = 0L
    var latest: GnssSummary? = null
        private set

    // Lazy so the aggregation can be tested on the JVM without Android's stub classes.
    val callback by lazy {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) = status(summary(status), android.os.SystemClock.elapsedRealtime())
        }
    }

    private val recent = ArrayDeque<Pair<Long, GnssSummary>>()

    fun status(summary: GnssSummary, now: Long) {
        if (lastCallback > 0) maxGap = maxOf(maxGap, now - lastCallback)
        lastCallback = now
        callbacks++
        latest = summary
        // A batch delivers fixes up to a minute old: keep enough status to match each of them.
        recent.addLast(now to summary)
        while (recent.size > RECENT_LIMIT) recent.removeFirst()
    }

    /** The status nearest a fix's elapsed time, if one came within [MATCH_MS] of it. */
    fun near(elapsed: Long): GnssSummary? =
        recent.minByOrNull { kotlin.math.abs(it.first - elapsed) }?.takeIf { kotlin.math.abs(it.first - elapsed) <= MATCH_MS }?.second

    /** Fields for one fix: the latest status and the callbacks since the previous fix. */
    fun take(now: Long): Array<Pair<String, Any?>> {
        val summary = latest
        val fields = arrayOf<Pair<String, Any?>>(
            "gnss_callbacks" to callbacks,
            "gnss_max_gap_ms" to maxGap.takeIf { callbacks > 1 },
            "gnss_status_age_ms" to (now - lastCallback).takeIf { lastCallback > 0 },
            "sats_used" to summary?.used, "sats_visible" to summary?.visible,
            "cn0_top4_dbhz" to summary?.topCn0, "l5_used" to summary?.l5Used,
        )
        callbacks = 0
        maxGap = 0
        return fields
    }

    /**
     * The receiver still reports status but uses too few satellites for a fix: the sky is hidden
     * (a garage, a tunnel), so missing deliveries are not the provider's batching failing.
     */
    fun signalLost(now: Long): Boolean {
        val summary = latest ?: return false
        return now - lastCallback <= SIGNAL_STATUS_MAX_AGE_MS && summary.used < MIN_SATELLITES_FOR_FIX
    }

    fun reset() {
        recent.clear()
        callbacks = 0
        lastCallback = 0
        maxGap = 0
        latest = null
    }

    companion object {
        const val SIGNAL_STATUS_MAX_AGE_MS = 15_000L
        const val MIN_SATELLITES_FOR_FIX = 4
        private const val RECENT_LIMIT = 180
        private const val MATCH_MS = 3_000L

        fun summary(status: GnssStatus): GnssSummary {
            val used = (0 until status.satelliteCount).filter(status::usedInFix)
            return GnssSummary(
                used = used.size,
                visible = status.satelliteCount,
                topCn0 = used.map { status.getCn0DbHz(it).toDouble() }.sortedDescending().take(4)
                    .takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it * 10) / 10.0 },
                l5Used = used.count { status.hasCarrierFrequencyHz(it) && status.getCarrierFrequencyHz(it) < 1.3e9f },
            )
        }
    }
}

internal data class GnssSummary(val used: Int, val visible: Int, val topCn0: Double?, val l5Used: Int)
