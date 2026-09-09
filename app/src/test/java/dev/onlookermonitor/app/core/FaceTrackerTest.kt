package dev.onlookermonitor.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceTrackerTest {
    @Test
    fun sourceTrackingIdWinsOverListOrder() {
        val tracker = FaceTracker(iouThreshold = 0.2f, expiryMillis = 1_000)
        val left = observation(1, FaceBounds(0.1f, 0.2f, 0.4f, 0.7f))
        val right = observation(2, FaceBounds(0.6f, 0.2f, 0.9f, 0.7f))
        val initial = tracker.update(listOf(left, right), 1_000)

        val reordered = tracker.update(listOf(right, left), 1_100)

        assertEquals(initial[1].trackId, reordered[0].trackId)
        assertEquals(initial[0].trackId, reordered[1].trackId)
    }

    @Test
    fun expiredFaceReceivesNewInternalId() {
        val tracker = FaceTracker(iouThreshold = 0.2f, expiryMillis = 500)
        val face = observation(null, FaceBounds(0.1f, 0.2f, 0.4f, 0.7f))
        val first = tracker.update(listOf(face), 1_000)
        val later = tracker.update(listOf(face), 1_501)

        assertEquals(first.single().trackId + 1, later.single().trackId)
    }

    private fun observation(sourceId: Int?, bounds: FaceBounds) = FaceObservation(
        sourceTrackingId = sourceId,
        bounds = bounds,
        yawDegrees = 0f,
        pitchDegrees = 0f,
        rollDegrees = 0f,
    )
}
