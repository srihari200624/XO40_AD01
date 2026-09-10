package dev.onlookermonitor.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivenessAndPartialFaceTest {
    private val primaryOwner = face(0.30f, 0.20f, 0.65f, 0.72f, sourceId = 1)

    @Test
    fun singleFaceInFrameReportsExactlyOneFaceAndNoCandidateOnlooker() {
        val engine = MonitoringEngine()
        val result = engine.analyze(listOf(primaryOwner), 1_000)

        assertEquals(MonitorState.ACTIVE, result.state)
        assertEquals(1, result.faces.size)
        assertTrue(result.candidateTrackIds.isEmpty())
    }

    @Test
    fun faceWithEyeBlinkingMaintainsHighLivenessScore() {
        val tracker = FaceTracker(iouThreshold = 0.2f, expiryMillis = 1_000)
        val faceOpen = face(0.70f, 0.25f, 0.88f, 0.55f, sourceId = 2, leftEye = 0.95f, rightEye = 0.95f)
        val faceBlink = face(0.70f, 0.25f, 0.88f, 0.55f, sourceId = 2, leftEye = 0.05f, rightEye = 0.05f)

        tracker.update(listOf(faceOpen), 1_000)
        val result = tracker.update(listOf(faceBlink), 1_100)

        assertTrue(result.single().livenessScore >= 0.80f)
    }

    @Test
    fun staticPictureWithoutMicroMovementsDecaysLivenessScore() {
        val tracker = FaceTracker(iouThreshold = 0.2f, expiryMillis = 1_000)
        val staticFace = face(0.70f, 0.25f, 0.88f, 0.55f, sourceId = 2, leftEye = 0.80f, rightEye = 0.80f, patchLuma = 100f)

        var now = 1_000L
        var lastScore = 0f
        repeat(30) {
            now += 100
            val res = tracker.update(listOf(staticFace), now)
            lastScore = res.single().livenessScore
        }

        assertTrue("Static photo score should decay below 0.35f, got $lastScore", lastScore < 0.35f)
    }

    @Test
    fun partialBorderFaceIsDetectedAsCandidateOnlooker() {
        val engine = MonitoringEngine(MonitorConfig(triggerDebounceMillis = 500))
        val borderOnlooker = face(0.0f, 0.10f, 0.18f, 0.40f, sourceId = 5, isPartial = true, faceType = FaceType.PARTIAL_BORDER)

        val result = engine.analyze(listOf(primaryOwner, borderOnlooker), 1_000)

        assertEquals(MonitorState.CANDIDATE_DETECTED, result.state)
        assertTrue(result.candidateTrackIds.isNotEmpty())
        assertTrue(result.faces.first { !it.primary }.isPartialFace)
    }

    @Test
    fun hairAndHeadSilhouetteObservationTriggersCandidateDetection() {
        val engine = MonitoringEngine(MonitorConfig(triggerDebounceMillis = 500))
        val hairObservation = FaceObservation(
            sourceTrackingId = null,
            bounds = FaceBounds(0.0f, 0.0f, 0.25f, 0.35f),
            yawDegrees = 0f,
            pitchDegrees = 0f,
            rollDegrees = 0f,
            patchLuma = 80f,
            patchContrast = 30f,
            isPartialFace = true,
            faceType = FaceType.HAIR_AND_HEAD_SILHOUETTE,
        )

        val result = engine.analyze(listOf(primaryOwner, hairObservation), 1_000)

        assertEquals(MonitorState.CANDIDATE_DETECTED, result.state)
        assertTrue(result.candidateTrackIds.isNotEmpty())
        assertEquals(FaceType.HAIR_AND_HEAD_SILHOUETTE, result.faces.first { !it.primary }.faceType)
    }

    private fun face(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        sourceId: Int? = null,
        leftEye: Float? = null,
        rightEye: Float? = null,
        patchLuma: Float? = 100f,
        patchContrast: Float? = 20f,
        isPartial: Boolean = false,
        faceType: FaceType = FaceType.FULL_FACE,
    ) = FaceObservation(
        sourceTrackingId = sourceId,
        bounds = FaceBounds(left, top, right, bottom),
        yawDegrees = yaw,
        pitchDegrees = pitch,
        rollDegrees = roll,
        patchLuma = patchLuma,
        patchContrast = patchContrast,
        leftEyeOpenProbability = leftEye,
        rightEyeOpenProbability = rightEye,
        isPartialFace = isPartial,
        faceType = faceType,
    )
}
