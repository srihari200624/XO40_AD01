package dev.onlookermonitor.app.core

import kotlin.math.abs
import kotlin.math.hypot

class MonitoringEngine(private val config: MonitorConfig = MonitorConfig()) {
    private val tracker = FaceTracker(config.trackingIouThreshold, config.trackExpiryMillis)
    private val policy = OnlookerPolicy(config)
    val frameMemory = TemporalFrameMemory(maxHistorySize = 20)
    private var primaryTrackId: Long? = null
    private var lastTimestampMillis: Long? = null
    private var lastVisibleTrackIds = emptySet<Long>()
    private var lastUsableTrackIds = emptySet<Long>()
    private var burstUntilMillis = 0L
    private var settleUntilMillis = 0L
    private var motionSuppressedUntilMillis = 0L

    @Synchronized
    fun analyze(
        faces: List<FaceObservation>,
        nowMillis: Long,
        quality: FrameQuality = FrameQuality.UNKNOWN,
    ): AnalysisDecision {
        val previousTimestamp = lastTimestampMillis
        require(previousTimestamp == null || nowMillis >= previousTimestamp) {
            "timestamps must be monotonic"
        }
        if (previousTimestamp != null && nowMillis - previousTimestamp > config.maxFrameGapMillis) {
            resetEvidenceState()
        }
        lastTimestampMillis = nowMillis

        frameMemory.recordFrame(
            nowMillis = nowMillis,
            faceCount = faces.size,
            meanLuma = quality.meanLuma,
            contrast = quality.contrast,
            motionScore = quality.motionScore,
            processingMillis = 0L,
        )

        val matches = tracker.update(faces, nowMillis)
        val usableMatches = matches.filter { it.face.isUsable(quality) }
        val visibleTrackIds = matches.mapTo(mutableSetOf(), TrackMatch::trackId)
        if (visibleTrackIds != lastVisibleTrackIds) {
            burstUntilMillis = saturatingAdd(nowMillis, config.trackingChangeBurstMillis)
            lastVisibleTrackIds = visibleTrackIds
        }
        // A changing set of usable faces means the scene is still churning -- a face just
        // appeared, or tracking is re-acquiring. Detections during that churn are the least
        // trustworthy ones, so hold off triggering until it settles.
        val usableTrackIds = usableMatches.mapTo(mutableSetOf(), TrackMatch::trackId)
        if (usableTrackIds != lastUsableTrackIds) {
            settleUntilMillis = saturatingAdd(nowMillis, config.sceneSettleMillis)
            lastUsableTrackIds = usableTrackIds
        }
        if (quality.motionScore >= config.motionScoreThreshold) {
            motionSuppressedUntilMillis = saturatingAdd(nowMillis, config.motionSuppressMillis)
        }
        selectPrimary(usableMatches)

        val trackedFaces = matches.map { match ->
            val usable = match.face.isUsable(quality)
            val oriented = usable && match.face.isScreenOriented()
            val livenessScore = match.livenessScore
            val isLive = if (config.enableLivenessCheck) livenessScore >= config.minLivenessScore else true

            TrackedFace(
                trackId = match.trackId,
                bounds = match.face.bounds,
                yawDegrees = match.face.yawDegrees,
                pitchDegrees = match.face.pitchDegrees,
                rollDegrees = match.face.rollDegrees,
                usable = usable,
                screenOriented = oriented,
                primary = match.trackId == primaryTrackId,
                livenessScore = livenessScore,
                isLive = isLive,
                isPartialFace = match.face.isPartialFace,
                faceType = match.face.faceType,
            )
        }
        val candidates = if (usableMatches.size > 1) {
            trackedFaces.asSequence()
                .filter { face ->
                    face.usable &&
                        !face.primary &&
                        face.isLive &&
                        (face.screenOriented || face.isCandidateOriented(config))
                }
                .map(TrackedFace::trackId)
                .toSet()
        } else {
            emptySet()
        }
        val suppressionReason = suppressionReason(quality, nowMillis)
        val policyDecision = policy.update(
            candidateIds = candidates,
            nowMillis = nowMillis,
            debounceMultiplier = debounceMultiplier(quality),
            suppressTrigger = suppressionReason != null,
        )
        val stableSingleFace = matches.size == 1 && usableMatches.size == 1
        val interval = if (
            !stableSingleFace ||
            policyDecision.state != MonitorState.ACTIVE ||
            nowMillis < burstUntilMillis
        ) {
            config.burstAnalysisIntervalMillis
        } else {
            config.idleAnalysisIntervalMillis
        }
        val reason = if (
            suppressionReason != null &&
            policyDecision.state != MonitorState.SHIELD_ACTIVE
        ) {
            suppressionReason
        } else {
            policyDecision.reason
        }
        return AnalysisDecision(
            state = policyDecision.state,
            faces = trackedFaces,
            primaryTrackId = primaryTrackId,
            candidateTrackIds = policyDecision.candidateTrackIds,
            triggerProgress = policyDecision.triggerProgress,
            clearProgress = policyDecision.clearProgress,
            recommendedIntervalMillis = interval,
            reason = reason,
            lighting = quality.lighting,
        )
    }

    @Synchronized
    fun reset() {
        tracker.reset()
        lastTimestampMillis = null
        resetEvidenceState()
    }

    private fun resetEvidenceState() {
        tracker.reset()
        frameMemory.clear()
        primaryTrackId = null
        lastVisibleTrackIds = emptySet()
        lastUsableTrackIds = emptySet()
        burstUntilMillis = 0L
        settleUntilMillis = 0L
        motionSuppressedUntilMillis = 0L
        policy.resetEvidence()
    }

    private fun suppressionReason(quality: FrameQuality, nowMillis: Long): String? = when {
        quality.lighting == LightingCondition.TOO_DARK -> "suppressed_too_dark"
        nowMillis < motionSuppressedUntilMillis -> "suppressed_motion"
        nowMillis < settleUntilMillis -> "suppressed_scene_settling"
        else -> null
    }

    private fun debounceMultiplier(quality: FrameQuality): Float =
        if (quality.lighting == LightingCondition.LOW_LIGHT ||
            quality.lighting == LightingCondition.BACKLIT
        ) {
            config.lowLightDebounceMultiplier
        } else {
            1f
        }

    private fun selectPrimary(matches: List<TrackMatch>) {
        val currentPrimary = primaryTrackId
        if (currentPrimary != null && tracker.isActive(currentPrimary)) return
        primaryTrackId = matches.maxByOrNull { match ->
            val distance = hypot(
                (match.face.bounds.centerX - 0.5f).toDouble(),
                (match.face.bounds.centerY - 0.5f).toDouble(),
            ).toFloat()
            match.face.bounds.area - config.primaryCenterWeight * distance
        }?.trackId
    }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    /**
     * Poor light makes ML Kit report noise as faces, so a detection has to clear a larger
     * size floor there, and must sit on a region that is neither near-black nor flat.
     */
    private fun FaceObservation.isUsable(quality: FrameQuality): Boolean {
        val sizeScale = if (quality.degraded) config.lowLightMinFaceScale else 1f
        return bounds.width >= config.minFaceWidthRatio * sizeScale &&
            bounds.area >= config.minFaceAreaRatio * sizeScale * sizeScale &&
            yawDegrees.isFinite() && pitchDegrees.isFinite() && rollDegrees.isFinite() &&
            (patchLuma == null || patchLuma >= config.minPatchLuma) &&
            (patchContrast == null || patchContrast >= config.minPatchContrast)
    }

    private fun FaceObservation.isScreenOriented(): Boolean =
        kotlin.math.abs(yawDegrees) <= config.maxAbsYawDegrees &&
            kotlin.math.abs(pitchDegrees) <= config.maxAbsPitchDegrees &&
            abs(rollDegrees) <= config.maxAbsRollDegrees

    private fun TrackedFace.isCandidateOriented(config: MonitorConfig): Boolean =
        abs(yawDegrees) <= config.maxCandidateYawDegrees &&
            abs(pitchDegrees) <= config.maxCandidatePitchDegrees &&
            kotlin.math.abs(rollDegrees) <= config.maxAbsRollDegrees
}
