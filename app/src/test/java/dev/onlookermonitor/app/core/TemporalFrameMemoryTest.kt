package dev.onlookermonitor.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalFrameMemoryTest {

    @Test
    fun rollingBufferCalculatesSmoothedMetricsAcrossContinuousFrames() {
        val memory = TemporalFrameMemory(maxHistorySize = 5)

        memory.recordFrame(1_000, 1, 100f, 20f, 0f, 30)
        memory.recordFrame(1_100, 1, 120f, 25f, 1f, 32)
        memory.recordFrame(1_200, 1, 110f, 22f, 2f, 28)

        assertEquals(3, memory.frameHistorySize())
        assertEquals(110f, memory.smoothLuma(), 0.01f)
        assertEquals(22.33f, memory.smoothContrast(), 0.1f)
        assertEquals(30L, memory.averageProcessingMillis())
    }

    @Test
    fun faceTrackerAppliesTrajectorySmoothingAcrossContinuousFrames() {
        val tracker = FaceTracker(iouThreshold = 0.2f, expiryMillis = 1_000)
        val initialFace = FaceObservation(
            sourceTrackingId = 1,
            bounds = FaceBounds(0.20f, 0.20f, 0.50f, 0.50f),
            yawDegrees = 0f,
            pitchDegrees = 0f,
            rollDegrees = 0f,
        )

        val shiftedFace = FaceObservation(
            sourceTrackingId = 1,
            bounds = FaceBounds(0.30f, 0.20f, 0.60f, 0.50f),
            yawDegrees = 10f,
            pitchDegrees = 0f,
            rollDegrees = 0f,
        )

        tracker.update(listOf(initialFace), 1_000)
        val match2 = tracker.update(listOf(shiftedFace), 1_100).single()

        // Smoothed yaw should be between 0f and 10f (EMA smoothed: 0.8 * 10 + 0.2 * 0 = 8.0f)
        assertEquals(8.0f, match2.face.yawDegrees, 0.1f)
        assertTrue(match2.face.bounds.left > 0.20f && match2.face.bounds.left < 0.30f)
    }
}
