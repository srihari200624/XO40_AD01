package dev.onlookermonitor.app.core

internal data class TrackMatch(
    val trackId: Long,
    val face: FaceObservation,
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
    )

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
            tracks[trackId] = Track(trackId, face.sourceTrackingId, face.bounds, nowMillis)
            TrackMatch(trackId, face)
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
