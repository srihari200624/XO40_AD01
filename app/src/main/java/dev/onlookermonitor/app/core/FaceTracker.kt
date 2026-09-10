package dev.onlookermonitor.app.core

import kotlin.math.abs

internal data class TrackMatch(
    val trackId: Long,
    val face: FaceObservation,
    val livenessScore: Float = 1.0f,
)

internal class FaceTracker(
    private val iouThreshold: Float,
    private val expiryMillis: Long,
) {
    private data class Track(
        val trackId: Long,
        var sourceTrackingId: Int?,
        var bounds: FaceBounds,
        var lastSeenMillis: Long,
        var smoothedBounds: FaceBounds = bounds,
        var smoothedYaw: Float = 0f,
        var smoothedPitch: Float = 0f,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var prevLeftEyeProb: Float? = null,
        var prevRightEyeProb: Float? = null,
        var prevYaw: Float? = null,
        var prevPitch: Float? = null,
        var prevPatchLuma: Float? = null,
        var frameCount: Int = 0,
        var eyeVariations: Int = 0,
        var poseVariations: Float = 0f,
        var lumaVariations: Float = 0f,
        var livenessScore: Float = 0.85f,
    ) {
        fun updateTrajectory(face: FaceObservation) {
            val alpha = 0.80f
            val prevX = smoothedBounds.centerX
            val prevY = smoothedBounds.centerY

            val newLeft = alpha * face.bounds.left + (1 - alpha) * smoothedBounds.left
            val newTop = alpha * face.bounds.top + (1 - alpha) * smoothedBounds.top
            val newRight = alpha * face.bounds.right + (1 - alpha) * smoothedBounds.right
            val newBottom = alpha * face.bounds.bottom + (1 - alpha) * smoothedBounds.bottom

            smoothedBounds = FaceBounds(newLeft, newTop, newRight, newBottom)
            smoothedYaw = alpha * face.yawDegrees + (1 - alpha) * smoothedYaw
            smoothedPitch = alpha * face.pitchDegrees + (1 - alpha) * smoothedPitch

            velocityX = smoothedBounds.centerX - prevX
            velocityY = smoothedBounds.centerY - prevY
        }
        fun updateLiveness(face: FaceObservation) {
            frameCount += 1
            var dynamicSignatures = 0

            // 1. Eye open/blink variations
            val leftProb = face.leftEyeOpenProbability
            val rightProb = face.rightEyeOpenProbability
            if (leftProb != null && prevLeftEyeProb != null) {
                if (abs(leftProb - prevLeftEyeProb!!) > 0.08f) {
                    eyeVariations += 1
                    dynamicSignatures += 1
                }
            }
            if (rightProb != null && prevRightEyeProb != null) {
                if (abs(rightProb - prevRightEyeProb!!) > 0.08f) {
                    eyeVariations += 1
                    dynamicSignatures += 1
                }
            }

            // 2. Micro pose variations
            if (prevYaw != null && prevPitch != null) {
                val yawDelta = abs(face.yawDegrees - prevYaw!!)
                val pitchDelta = abs(face.pitchDegrees - prevPitch!!)
                if (yawDelta > 0.3f || pitchDelta > 0.3f) {
                    poseVariations += yawDelta + pitchDelta
                    dynamicSignatures += 1
                }
            }

            // 3. Patch luma variations
            val luma = face.patchLuma
            if (luma != null && prevPatchLuma != null) {
                val lumaDelta = abs(luma - prevPatchLuma!!)
                if (lumaDelta > 0.5f) {
                    lumaVariations += lumaDelta
                    dynamicSignatures += 1
                }
            }

            prevLeftEyeProb = leftProb
            prevRightEyeProb = rightProb
            prevYaw = face.yawDegrees
            prevPitch = face.pitchDegrees
            prevPatchLuma = luma

            // For partial face / hair silhouette detections, maintain good base liveness
            if (face.isPartialFace || face.faceType != FaceType.FULL_FACE) {
                livenessScore = (livenessScore + 0.05f).coerceAtMost(1.0f)
                return
            }

            if (dynamicSignatures > 0) {
                livenessScore = (livenessScore + 0.08f).coerceAtMost(1.0f)
            } else if (frameCount > 20 && eyeVariations == 0 && poseVariations < 0.5f) {
                // Static picture / printout with zero eye or pose variation over extended frames
                livenessScore = (livenessScore - 0.05f).coerceAtLeast(0.10f)
            } else {
                // Default reasonable live level
                livenessScore = livenessScore.coerceIn(0.35f, 1.0f)
            }
        }
    }

    private val tracks = mutableMapOf<Long, Track>()
    private var nextId = 1L

    fun update(faces: List<FaceObservation>, nowMillis: Long): List<TrackMatch> {
        expire(nowMillis)
        val unmatchedTrackIds = tracks.keys.toMutableSet()
        val unmatchedFaceIndices = faces.indices.toMutableSet()
        val assignments = mutableMapOf<Int, Long>()

        // Prefer ML Kit's exact tracking identity when it is present and unambiguous.
        faces.forEachIndexed { index, face ->
            val sourceId = face.sourceTrackingId ?: return@forEachIndexed
            val matching = unmatchedTrackIds.singleOrNull { tracks[it]?.sourceTrackingId == sourceId }
            if (matching != null) {
                assignments[index] = matching
                unmatchedTrackIds.remove(matching)
                unmatchedFaceIndices.remove(index)
            }
        }

        data class Candidate(val overlap: Float, val trackId: Long, val faceIndex: Int)
        val candidates = buildList {
            for (trackId in unmatchedTrackIds) {
                val track = tracks.getValue(trackId)
                for (faceIndex in unmatchedFaceIndices) {
                    val overlap = track.bounds.iou(faces[faceIndex].bounds)
                    if (overlap >= iouThreshold) add(Candidate(overlap, trackId, faceIndex))
                }
            }
        }.sortedByDescending(Candidate::overlap)

        candidates.forEach { candidate ->
            if (candidate.trackId in unmatchedTrackIds && candidate.faceIndex in unmatchedFaceIndices) {
                assignments[candidate.faceIndex] = candidate.trackId
                unmatchedTrackIds.remove(candidate.trackId)
                unmatchedFaceIndices.remove(candidate.faceIndex)
            }
        }

        unmatchedFaceIndices.sorted().forEach { faceIndex ->
            assignments[faceIndex] = nextId++
        }

        return faces.mapIndexed { index, face ->
            val trackId = assignments.getValue(index)
            val track = tracks.getOrPut(trackId) {
                Track(trackId, face.sourceTrackingId, face.bounds, nowMillis)
            }
            track.sourceTrackingId = face.sourceTrackingId
            track.bounds = face.bounds
            track.lastSeenMillis = nowMillis
            track.updateTrajectory(face)
            track.updateLiveness(face)

            val smoothedFace = face.copy(
                bounds = track.smoothedBounds,
                yawDegrees = track.smoothedYaw,
                pitchDegrees = track.smoothedPitch,
            )
            TrackMatch(trackId, smoothedFace, track.livenessScore)
        }
    }

    fun reset() {
        tracks.clear()
    }

    fun isActive(trackId: Long): Boolean = trackId in tracks

    private fun expire(nowMillis: Long) {
        tracks.entries.removeAll { nowMillis - it.value.lastSeenMillis > expiryMillis }
    }
}
