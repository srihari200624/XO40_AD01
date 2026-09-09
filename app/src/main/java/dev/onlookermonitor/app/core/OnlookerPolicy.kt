package dev.onlookermonitor.app.core

internal data class PolicyDecision(
    val state: MonitorState,
    val candidateTrackIds: Set<Long>,
    val triggerProgress: Float,
    val clearProgress: Float,
    val reason: String,
)

internal class OnlookerPolicy(private val config: MonitorConfig) {
    var state: MonitorState = MonitorState.ACTIVE
        private set

    private class Evidence(
        var firstSeenMillis: Long,
        var lastSeenMillis: Long,
        var frames: Int,
    )

    private val evidence = mutableMapOf<Long, Evidence>()
    private var clearSince: Long? = null

    /**
     * @param debounceMultiplier lengthens the trigger window when imaging conditions are poor.
     * @param suppressTrigger holds evidence at zero while the scene is unreadable (motion,
     *   darkness, or the churn right after the tracked-face set changes). The shield cannot
     *   fire while suppressed, but an already-active shield keeps its own clear debounce.
     */
    fun update(
        candidateIds: Set<Long>,
        nowMillis: Long,
        debounceMultiplier: Float = 1f,
        suppressTrigger: Boolean = false,
    ): PolicyDecision {
        recordEvidence(candidateIds, nowMillis, suppressTrigger)
        val effectiveDebounce = effectiveDebounceMillis(debounceMultiplier)
        val matureCandidate = !suppressTrigger && candidateIds.any { trackId ->
            val record = evidence[trackId] ?: return@any false
            record.frames >= config.minCandidateFrames &&
                nowMillis - record.firstSeenMillis >= effectiveDebounce
        }
        val triggerProgress = triggerProgress(candidateIds, nowMillis, effectiveDebounce)

        if (state == MonitorState.SHIELD_ACTIVE) {
            if (candidateIds.isNotEmpty()) {
                clearSince = null
                return decision(candidateIds, 1f, 0f, "onlooker_still_present")
            }
            val startedClearing = clearSince ?: nowMillis.also { clearSince = it }
            val elapsed = nowMillis - startedClearing
            val clearProgress = (elapsed.toFloat() / config.clearDebounceMillis).coerceIn(0f, 1f)
            if (elapsed >= config.clearDebounceMillis) {
                state = MonitorState.ACTIVE
                clearSince = null
                return decision(emptySet(), 0f, 1f, "clear_debounce_complete")
            }
            return decision(emptySet(), 0f, clearProgress, "waiting_for_clear_debounce")
        }

        clearSince = null
        state = when {
            matureCandidate -> MonitorState.SHIELD_ACTIVE
            candidateIds.isNotEmpty() -> MonitorState.CANDIDATE_DETECTED
            else -> MonitorState.ACTIVE
        }
        val reason = when (state) {
            MonitorState.SHIELD_ACTIVE -> "trigger_debounce_complete"
            MonitorState.CANDIDATE_DETECTED -> "candidate_debouncing"
            else -> "no_persistent_onlooker"
        }
        return decision(candidateIds, if (matureCandidate) 1f else triggerProgress, 0f, reason)
    }

    fun resetEvidence() {
        evidence.clear()
        clearSince = null
        if (state != MonitorState.SHIELD_ACTIVE) state = MonitorState.ACTIVE
    }

    /**
     * A candidate that vanishes for a frame or two keeps its accumulated evidence for
     * [MonitorConfig.candidateGraceMillis]; anything absent for longer starts over. Without
     * the grace window a real onlooker would lose all progress on a single dropped detection.
     */
    private fun recordEvidence(candidateIds: Set<Long>, nowMillis: Long, suppressTrigger: Boolean) {
        evidence.entries.removeAll { entry ->
            entry.key !in candidateIds &&
                nowMillis - entry.value.lastSeenMillis > config.candidateGraceMillis
        }
        candidateIds.forEach { trackId ->
            val record = evidence[trackId]
            if (record == null) {
                evidence[trackId] = Evidence(nowMillis, nowMillis, 1)
            } else {
                record.lastSeenMillis = nowMillis
                record.frames += 1
            }
        }
        if (suppressTrigger) {
            evidence.values.forEach { record ->
                record.firstSeenMillis = nowMillis
                record.frames = 1
            }
        }
    }

    private fun effectiveDebounceMillis(debounceMultiplier: Float): Long =
        (config.triggerDebounceMillis * debounceMultiplier.coerceAtLeast(1f)).toLong()

    private fun triggerProgress(
        candidateIds: Set<Long>,
        nowMillis: Long,
        effectiveDebounce: Long,
    ): Float {
        if (candidateIds.isEmpty()) return 0f
        return candidateIds.mapNotNull(evidence::get).maxOfOrNull { record ->
            val elapsed = if (effectiveDebounce <= 0L) {
                1f
            } else {
                (nowMillis - record.firstSeenMillis).toFloat() / effectiveDebounce
            }
            val frames = record.frames.toFloat() / config.minCandidateFrames
            minOf(elapsed, frames).coerceIn(0f, 1f)
        } ?: 0f
    }

    private fun decision(
        candidates: Set<Long>,
        triggerProgress: Float,
        clearProgress: Float,
        reason: String,
    ) = PolicyDecision(state, candidates, triggerProgress, clearProgress, reason)
}
