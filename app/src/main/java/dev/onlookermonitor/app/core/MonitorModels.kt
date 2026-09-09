package dev.onlookermonitor.app.core

import kotlin.math.max
import kotlin.math.min

enum class MonitorState {
    DISARMED,
    /** Armed and running, but the camera is intentionally off because the foreground app is not
     *  in the user's protected set. Distinct from [DISARMED] (service stopped). */
    STANDBY,
    STARTING,
    ACTIVE,
    CANDIDATE_DETECTED,
    SHIELD_ACTIVE,
    DEGRADED,
}

data class FaceBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun iou(other: FaceBounds): Float {
        val intersectionWidth = max(0f, min(right, other.right) - max(left, other.left))
        val intersectionHeight = max(0f, min(bottom, other.bottom) - max(top, other.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = area + other.area - intersection
        return if (union > 0f) intersection / union else 0f
    }
}

data class FaceObservation(
    val sourceTrackingId: Int?,
    val bounds: FaceBounds,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    /** Mean luma under the detection, or null when no luma plane was sampled. */
    val patchLuma: Float? = null,
    /** Local luma spread under the detection; flat regions are usually sensor noise. */
    val patchContrast: Float? = null,
)

data class TrackedFace(
    val trackId: Long,
    val bounds: FaceBounds,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val usable: Boolean,
    val screenOriented: Boolean,
    val primary: Boolean,
)

data class AnalysisDecision(
    val state: MonitorState,
    val faces: List<TrackedFace>,
    val primaryTrackId: Long?,
    val candidateTrackIds: Set<Long>,
    val triggerProgress: Float,
    val clearProgress: Float,
    val recommendedIntervalMillis: Long,
    val reason: String,
    val lighting: LightingCondition = LightingCondition.UNKNOWN,
)

data class MonitorConfig(
    // Pose gate.
    val maxAbsYawDegrees: Float = 50f,
    val maxAbsPitchDegrees: Float = 40f,
    val maxAbsRollDegrees: Float = 35f,
    // Usable-detection floors. ML Kit reports no confidence score, so size, brightness and
    // local contrast stand in for one: tiny, near-black or flat blobs are sensor noise.
    val minFaceWidthRatio: Float = 0.08f,
    val minFaceAreaRatio: Float = 0.006f,
    val minPatchLuma: Float = 26f,
    val minPatchContrast: Float = 6f,
    // Temporal evidence. A candidate must persist in both elapsed time and frame count
    // before the shield fires; one blurred or noisy frame must never be enough.
    val triggerDebounceMillis: Long = 700,
    val minCandidateFrames: Int = 3,
    val candidateGraceMillis: Long = 250,
    val clearDebounceMillis: Long = 1_800,
    // Trigger suppression windows for conditions where detections cannot be trusted.
    val sceneSettleMillis: Long = 400,
    val motionSuppressMillis: Long = 600,
    val motionScoreThreshold: Float = 12f,
    // Frame-quality classification, in 0..255 luma units.
    val darkCellLuma: Float = 32f,
    val clippedCellLuma: Float = 235f,
    val tooDarkMeanLuma: Float = 28f,
    val lowLightMeanLuma: Float = 55f,
    val backlitClippedFraction: Float = 0.22f,
    val backlitDarkFraction: Float = 0.25f,
    val lowLightDebounceMultiplier: Float = 1.8f,
    val lowLightMinFaceScale: Float = 1.5f,
    // Tracking and analysis cadence.
    val trackExpiryMillis: Long = 1_000,
    val maxFrameGapMillis: Long = 600,
    val trackingIouThreshold: Float = 0.10f,
    val primaryCenterWeight: Float = 0.20f,
    val idleAnalysisIntervalMillis: Long = 100,
    val burstAnalysisIntervalMillis: Long = 40,
    val trackingChangeBurstMillis: Long = 2_000,
) {
    init {
        require(maxAbsYawDegrees > 0f)
        require(maxAbsPitchDegrees > 0f)
        require(maxAbsRollDegrees > 0f)
        require(minFaceWidthRatio in 0f..1f)
        require(minFaceAreaRatio in 0f..1f)
        require(minPatchLuma >= 0f)
        require(minPatchContrast >= 0f)
        require(triggerDebounceMillis >= 0)
        require(minCandidateFrames >= 1)
        require(candidateGraceMillis >= 0)
        require(clearDebounceMillis > 0)
        require(sceneSettleMillis >= 0)
        require(motionSuppressMillis >= 0)
        require(motionScoreThreshold > 0f)
        require(darkCellLuma in 0f..255f)
        require(clippedCellLuma in 0f..255f)
        require(tooDarkMeanLuma in 0f..255f)
        require(lowLightMeanLuma >= tooDarkMeanLuma)
        require(backlitClippedFraction in 0f..1f)
        require(backlitDarkFraction in 0f..1f)
        require(lowLightDebounceMultiplier >= 1f)
        require(lowLightMinFaceScale >= 1f)
        require(trackExpiryMillis > 0)
        require(maxFrameGapMillis > 0)
        require(trackingIouThreshold in 0f..1f)
        require(idleAnalysisIntervalMillis > 0)
        require(burstAnalysisIntervalMillis > 0)
        require(trackingChangeBurstMillis >= 0)
    }
}
