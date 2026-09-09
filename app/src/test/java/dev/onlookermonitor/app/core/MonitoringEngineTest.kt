package dev.onlookermonitor.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringEngineTest {
    private val owner = face(0.30f, 0.20f, 0.65f, 0.72f, sourceId = 10)
    private val onlooker = face(0.70f, 0.25f, 0.88f, 0.55f, sourceId = 20)

    @Test
    fun stableAdditionalOrientedFaceTriggersShield() {
        val engine = MonitoringEngine(MonitorConfig(triggerDebounceMillis = 1_000))

        val initial = engine.analyze(listOf(owner, onlooker), 10_000)
        engine.analyze(listOf(owner, onlooker), 10_500)
        val triggered = engine.analyze(listOf(owner, onlooker), 11_000)

        assertEquals(MonitorState.CANDIDATE_DETECTED, initial.state)
        assertEquals(MonitorState.SHIELD_ACTIVE, triggered.state)
        assertEquals(1, triggered.candidateTrackIds.size)
        assertEquals(triggered.faces.first { it.primary }.trackId, triggered.primaryTrackId)
    }

    @Test
    fun turnedAdditionalFaceDoesNotTrigger() {
        val engine = MonitoringEngine(MonitorConfig(triggerDebounceMillis = 500))
        val lookingAway = onlooker.copy(yawDegrees = 60f)

        engine.analyze(listOf(owner, lookingAway), 1_000)
        val result = engine.analyze(listOf(owner, lookingAway), 1_200)

        assertEquals(MonitorState.ACTIVE, result.state)
        assertFalse(result.faces.first { !it.primary }.screenOriented)
    }

    @Test
    fun tinyDistantDetectionIsIgnored() {
        val engine = MonitoringEngine()
        val tiny = face(0.80f, 0.25f, 0.83f, 0.30f, sourceId = 20)

        val result = engine.analyze(listOf(owner, tiny), 1_000)

        assertEquals(MonitorState.ACTIVE, result.state)
        assertFalse(result.faces.first { it.trackId != result.primaryTrackId }.usable)
    }

    @Test
    fun candidateIdentityCannotInheritOtherCandidatesEvidence() {
        val engine = MonitoringEngine(MonitorConfig(triggerDebounceMillis = 1_000))
        val replacement = onlooker.copy(
            sourceTrackingId = 30,
            bounds = FaceBounds(0.03f, 0.2f, 0.20f, 0.52f),
        )

        val first = engine.analyze(listOf(owner, onlooker), 1_000)
        val changed = engine.analyze(listOf(owner, replacement), 1_900)

        assertEquals(MonitorState.CANDIDATE_DETECTED, changed.state)
        assertNotEquals(first.candidateTrackIds.single(), changed.candidateTrackIds.single())
        assertEquals(0f, changed.triggerProgress, 0.001f)
    }

    @Test
    fun shieldHasIndependentClearDebounce() {
        val engine = MonitoringEngine(
            MonitorConfig(
                triggerDebounceMillis = 500,
                clearDebounceMillis = 1_500,
                maxFrameGapMillis = 2_000,
            ),
        )
        engine.analyze(listOf(owner, onlooker), 1_000)
        engine.analyze(listOf(owner, onlooker), 1_400)
        engine.analyze(listOf(owner, onlooker), 1_600)

        val clearing = engine.analyze(listOf(owner), 1_700)
        val interrupted = engine.analyze(listOf(owner, onlooker), 2_100)
        val clearingAgain = engine.analyze(listOf(owner), 2_200)
        val cleared = engine.analyze(listOf(owner), 3_800)

        assertEquals(MonitorState.SHIELD_ACTIVE, clearing.state)
        assertEquals("onlooker_still_present", interrupted.reason)
        assertEquals(0f, clearingAgain.clearProgress, 0.001f)
        assertEquals(MonitorState.ACTIVE, cleared.state)
    }

    @Test
    fun longFrameGapExpiresEvidence() {
        val engine = MonitoringEngine(
            MonitorConfig(triggerDebounceMillis = 1_000, maxFrameGapMillis = 500),
        )
        engine.analyze(listOf(owner, onlooker), 1_000)
        val result = engine.analyze(listOf(owner, onlooker), 2_000)

        assertEquals(MonitorState.CANDIDATE_DETECTED, result.state)
        assertEquals(0f, result.triggerProgress, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun timestampsMustBeMonotonic() {
        val engine = MonitoringEngine()
        engine.analyze(emptyList(), 2_000)
        engine.analyze(emptyList(), 1_999)
    }

    @Test
    fun trackRemainsStableAcrossSmallMotionWithoutSourceId() {
        val engine = MonitoringEngine()
        val firstFace = owner.copy(sourceTrackingId = null)
        val movedFace = firstFace.copy(bounds = FaceBounds(0.31f, 0.20f, 0.66f, 0.72f))

        val first = engine.analyze(listOf(firstFace), 1_000)
        val second = engine.analyze(listOf(movedFace), 1_100)

        assertEquals(first.primaryTrackId, second.primaryTrackId)
        assertTrue(second.faces.single().primary)
    }

    @Test
    fun primaryDoesNotSwitchDuringShortDetectionLoss() {
        val engine = MonitoringEngine(MonitorConfig(trackExpiryMillis = 750))
        val first = engine.analyze(listOf(owner), 1_000)

        val missing = engine.analyze(listOf(onlooker), 1_400)
        val recovered = engine.analyze(listOf(owner, onlooker), 1_600)

        assertEquals(first.primaryTrackId, missing.primaryTrackId)
        assertFalse(missing.faces.single().primary)
        assertEquals(first.primaryTrackId, recovered.primaryTrackId)
        assertTrue(recovered.faces.first { it.trackId == first.primaryTrackId }.primary)
    }

    @Test
    fun trackingChangesKeepAnalysisInBurstModeTemporarily() {
        val config = MonitorConfig(
            trackingChangeBurstMillis = 500,
            idleAnalysisIntervalMillis = 350,
            burstAnalysisIntervalMillis = 100,
        )
        val engine = MonitoringEngine(config)

        val acquired = engine.analyze(listOf(owner), 1_000)
        val settling = engine.analyze(listOf(owner), 1_499)
        val stable = engine.analyze(listOf(owner), 1_500)

        assertEquals(100, acquired.recommendedIntervalMillis)
        assertEquals(100, settling.recommendedIntervalMillis)
        assertEquals(350, stable.recommendedIntervalMillis)
    }

    @Test
    fun landscapeOrientationDetectionTriggersShield() {
        val engine = MonitoringEngine()
        // In landscape (e.g. 16:9 / 4:3), the user face is centered and wide.
        val landscapeOwner =
            face(0.35f, 0.15f, 0.65f, 0.85f, yaw = 5f, pitch = 10f, roll = 0f, sourceId = 1)
        val landscapeOnlooker =
            face(0.72f, 0.20f, 0.95f, 0.70f, yaw = -15f, pitch = 5f, roll = 5f, sourceId = 2)
        val faces = listOf(landscapeOwner, landscapeOnlooker)

        var nowMillis = 1_000L
        var result = engine.analyze(faces, nowMillis)
        while (result.state != MonitorState.SHIELD_ACTIVE && nowMillis < 4_000L) {
            nowMillis += 100
            result = engine.analyze(faces, nowMillis)
        }

        assertEquals(MonitorState.SHIELD_ACTIVE, result.state)
        assertEquals(landscapeOwner.sourceTrackingId?.toLong(), result.primaryTrackId)
        assertTrue(result.candidateTrackIds.contains(2L))
    }

    @Test
    fun resetClearsTrackingAcrossOrientationTransition() {
        val engine = MonitoringEngine()
        // Face initially tracked in portrait
        val portraitResult = engine.analyze(listOf(owner), 1_000)
        val initialPrimary = portraitResult.primaryTrackId

        // Transition occurs (e.g. phone rotated to landscape), reset is called
        engine.reset()

        // New face arrives in landscape coordinate system
        val landscapeOwner = face(0.35f, 0.15f, 0.65f, 0.85f, sourceId = 50)
        val landscapeResult = engine.analyze(listOf(landscapeOwner), 1_100)

        assertEquals(MonitorState.ACTIVE, landscapeResult.state)
        assertNotEquals(initialPrimary, landscapeResult.primaryTrackId)
        assertTrue(landscapeResult.faces.single().primary)
    }

    // --- False-positive gates -------------------------------------------------------------

    @Test
    fun singleFrameOfAPhantomSecondFaceNeverShields() {
        // Motion blur or a glint routinely produces one frame with a spurious extra face.
        val engine = MonitoringEngine()
        val states = mutableListOf<MonitorState>()

        states += engine.analyze(listOf(owner), 1_000).state
        states += engine.analyze(listOf(owner, onlooker), 1_100).state
        var nowMillis = 1_100L
        repeat(10) {
            nowMillis += 100
            states += engine.analyze(listOf(owner), nowMillis).state
        }

        assertFalse(states.contains(MonitorState.SHIELD_ACTIVE))
    }

    @Test
    fun sustainedOnlookerStillShieldsWithDefaultTuning() {
        val engine = MonitoringEngine()
        val faces = listOf(owner, onlooker)
        val appearedAtMillis = 1_000L

        var nowMillis = appearedAtMillis
        var result = engine.analyze(faces, nowMillis)
        while (result.state != MonitorState.SHIELD_ACTIVE && nowMillis < appearedAtMillis + 3_000) {
            nowMillis += 100
            result = engine.analyze(faces, nowMillis)
        }

        assertEquals(MonitorState.SHIELD_ACTIVE, result.state)
        assertTrue(
            "shield latency was " + (nowMillis - appearedAtMillis) + "ms",
            nowMillis - appearedAtMillis <= 1_000,
        )
    }

    @Test
    fun flatDarkDetectionIsNotUsable() {
        // Dim-light sensor noise: ML Kit reports a face-shaped blob with no structure in it.
        val engine = MonitoringEngine()
        val noise = onlooker.copy(patchLuma = 11f, patchContrast = 1.5f)

        val result = engine.analyze(
            listOf(owner.copy(patchLuma = 120f, patchContrast = 40f), noise),
            1_000,
            quality(LightingCondition.LOW_LIGHT),
        )

        assertEquals(MonitorState.ACTIVE, result.state)
        assertFalse(result.faces.first { it.trackId != result.primaryTrackId }.usable)
        assertEquals(LightingCondition.LOW_LIGHT, result.lighting)
    }

    @Test
    fun tooDarkFramesNeverShieldHoweverLongTheyPersist() {
        val engine = MonitoringEngine(
            MonitorConfig(triggerDebounceMillis = 0, minCandidateFrames = 1, sceneSettleMillis = 0),
        )
        val faces = listOf(owner, onlooker)
        val dark = quality(LightingCondition.TOO_DARK)
        val states = mutableListOf<MonitorState>()

        var nowMillis = 1_000L
        repeat(20) {
            states += engine.analyze(faces, nowMillis, dark).state
            nowMillis += 100
        }

        assertFalse(states.contains(MonitorState.SHIELD_ACTIVE))
        assertEquals("suppressed_too_dark", engine.analyze(faces, nowMillis, dark).reason)
    }

    @Test
    fun cameraMotionRestartsTriggerEvidence() {
        val config = MonitorConfig(
            triggerDebounceMillis = 500,
            minCandidateFrames = 1,
            sceneSettleMillis = 0,
            motionSuppressMillis = 600,
        )
        val engine = MonitoringEngine(config)
        val faces = listOf(owner, onlooker)
        val still = quality(LightingCondition.GOOD)
        val moving = quality(LightingCondition.GOOD, motionScore = 30f)

        engine.analyze(faces, 1_000, still)
        val shaken = engine.analyze(faces, 1_200, moving)
        val stillSuppressed = engine.analyze(faces, 1_600, still)
        val recovering = engine.analyze(faces, 1_900, still)
        val triggered = engine.analyze(faces, 2_200, still)

        assertEquals(MonitorState.CANDIDATE_DETECTED, shaken.state)
        assertEquals("suppressed_motion", shaken.reason)
        assertEquals(MonitorState.CANDIDATE_DETECTED, stillSuppressed.state)
        assertEquals(MonitorState.CANDIDATE_DETECTED, recovering.state)
        assertEquals(MonitorState.SHIELD_ACTIVE, triggered.state)
    }

    @Test
    fun appearingFaceIsHeldBackWhileTheSceneSettles() {
        val engine = MonitoringEngine(
            MonitorConfig(triggerDebounceMillis = 0, minCandidateFrames = 1),
        )

        engine.analyze(emptyList(), 1_000)
        val appearing = engine.analyze(listOf(owner, onlooker), 1_100)

        assertEquals(MonitorState.CANDIDATE_DETECTED, appearing.state)
        assertEquals("suppressed_scene_settling", appearing.reason)
    }

    @Test
    fun candidateKeepsEvidenceAcrossOneDroppedFrame() {
        val config = MonitorConfig(
            triggerDebounceMillis = 500,
            minCandidateFrames = 3,
            candidateGraceMillis = 250,
            sceneSettleMillis = 0,
        )
        val engine = MonitoringEngine(config)

        engine.analyze(listOf(owner, onlooker), 1_000)
        engine.analyze(listOf(owner), 1_200)
        engine.analyze(listOf(owner, onlooker), 1_400)
        val triggered = engine.analyze(listOf(owner, onlooker), 1_600)

        assertEquals(MonitorState.SHIELD_ACTIVE, triggered.state)
    }

    @Test
    fun candidateAbsentBeyondTheGraceWindowStartsOver() {
        val config = MonitorConfig(
            triggerDebounceMillis = 500,
            minCandidateFrames = 2,
            candidateGraceMillis = 250,
            sceneSettleMillis = 0,
        )
        val engine = MonitoringEngine(config)

        engine.analyze(listOf(owner, onlooker), 1_000)
        engine.analyze(listOf(owner), 1_300)
        engine.analyze(listOf(owner), 1_500)
        val rejoined = engine.analyze(listOf(owner, onlooker), 1_700)

        assertEquals(MonitorState.CANDIDATE_DETECTED, rejoined.state)
        assertEquals(0f, rejoined.triggerProgress, 0.001f)
    }

    @Test
    fun lowLightLengthensTheTriggerDebounce() {
        val config = MonitorConfig(
            triggerDebounceMillis = 500,
            minCandidateFrames = 1,
            sceneSettleMillis = 0,
            lowLightDebounceMultiplier = 2f,
        )
        val engine = MonitoringEngine(config)
        val faces = listOf(owner, onlooker)
        val dim = quality(LightingCondition.LOW_LIGHT)

        engine.analyze(faces, 1_000, dim)
        val beforeStretchedDebounce = engine.analyze(faces, 1_600, dim)
        val afterStretchedDebounce = engine.analyze(faces, 2_100, dim)

        assertEquals(MonitorState.CANDIDATE_DETECTED, beforeStretchedDebounce.state)
        assertEquals(MonitorState.SHIELD_ACTIVE, afterStretchedDebounce.state)
    }

    @Test
    fun poorLightRaisesTheUsableFaceSizeFloor() {
        val config = MonitorConfig(minFaceWidthRatio = 0.10f, lowLightMinFaceScale = 2f)
        val borderline = face(0.60f, 0.25f, 0.75f, 0.60f, sourceId = 20)

        val lit = MonitoringEngine(config)
            .analyze(listOf(owner, borderline), 1_000, quality(LightingCondition.GOOD))
        val dim = MonitoringEngine(config)
            .analyze(listOf(owner, borderline), 1_000, quality(LightingCondition.LOW_LIGHT))

        assertTrue(lit.faces.first { it.trackId != lit.primaryTrackId }.usable)
        assertFalse(dim.faces.first { it.trackId != dim.primaryTrackId }.usable)
    }

    private fun quality(
        lighting: LightingCondition,
        motionScore: Float = 0f,
    ) = FrameQuality(
        meanLuma = 120f,
        contrast = 40f,
        darkFraction = 0f,
        clippedFraction = 0f,
        motionScore = motionScore,
        lighting = lighting,
    )

    private fun face(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        sourceId: Int? = null,
    ) = FaceObservation(
        sourceTrackingId = sourceId,
        bounds = FaceBounds(left, top, right, bottom),
        yawDegrees = yaw,
        pitchDegrees = pitch,
        rollDegrees = roll,
    )
}
