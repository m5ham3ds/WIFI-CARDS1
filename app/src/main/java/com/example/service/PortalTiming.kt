package com.example.service

/**
 * Shared safety limits for portal automation.
 *
 * Preference values tune how long the app tolerates a slow portal, but are never allowed
 * to make the app inject credentials into a page that is not ready or to stop observing a
 * login result before a minimum safety window has elapsed. Polling still exits immediately
 * when the portal exposes a definitive success/failure state.
 */
internal object PortalTiming {
    const val POLL_INTERVAL_MS = 150L
    const val MIN_PAGE_READY_TIMEOUT_MS = 10_000L
    const val MIN_FORM_READY_TIMEOUT_MS = 10_000L
    const val MIN_RESULT_TIMEOUT_MS = 5_000L
    const val ACKNOWLEDGED_OPERATION_TIMEOUT_MS = 12_000L

    fun pageReadyTimeout(configuredMs: Long): Long =
        maxOf(configuredMs, MIN_PAGE_READY_TIMEOUT_MS)

    fun formReadyTimeout(configuredMs: Long): Long =
        maxOf(configuredMs, MIN_FORM_READY_TIMEOUT_MS)

    fun resultTimeout(configuredMs: Long): Long =
        maxOf(configuredMs, MIN_RESULT_TIMEOUT_MS)

    fun acknowledgedOperationTimeout(configuredMs: Long): Long =
        maxOf(resultTimeout(configuredMs), ACKNOWLEDGED_OPERATION_TIMEOUT_MS)

    /**
     * Absolute deadline measured from the start of result polling. When the portal first
     * acknowledges the submitted form, it must get the full acknowledgement window from
     * that point—even when acknowledgement happens near the end of a longer user timeout.
     */
    fun acknowledgedDeadline(configuredMs: Long, elapsedMs: Long): Long =
        maxOf(
            resultTimeout(configuredMs),
            elapsedMs.coerceAtLeast(0L) + ACKNOWLEDGED_OPERATION_TIMEOUT_MS
        )
}
