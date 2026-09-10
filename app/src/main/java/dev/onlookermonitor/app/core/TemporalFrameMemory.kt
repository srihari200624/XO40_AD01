package dev.onlookermonitor.app.core

data class FrameMemoryRecord(
    val timestampMillis: Long,
    val faceCount: Int,
    val meanLuma: Float,
    val contrast: Float,
    val motionScore: Float,
    val processingMillis: Long,
)

/**
 * Maintains a rolling persistent memory buffer across continuous frames to evaluate
 * temporal trajectories, smooth out single-frame lighting/contrast glitches, and track
 * processing latency trends.
 */
class TemporalFrameMemory(private val maxHistorySize: Int = 15) {
    private val history = ArrayDeque<FrameMemoryRecord>()

    @Synchronized
    fun recordFrame(
        nowMillis: Long,
        faceCount: Int,
        meanLuma: Float,
        contrast: Float,
        motionScore: Float,
        processingMillis: Long,
    ) {
        history.addLast(
            FrameMemoryRecord(
                timestampMillis = nowMillis,
                faceCount = faceCount,
                meanLuma = meanLuma,
                contrast = contrast,
                motionScore = motionScore,
                processingMillis = processingMillis,
            ),
        )
        while (history.size > maxHistorySize) {
            history.removeFirst()
        }
    }

    @Synchronized
    fun smoothLuma(): Float {
        if (history.isEmpty()) return 0f
        return history.map { it.meanLuma }.average().toFloat()
    }

    @Synchronized
    fun smoothContrast(): Float {
        if (history.isEmpty()) return 0f
        return history.map { it.contrast }.average().toFloat()
    }

    @Synchronized
    fun averageProcessingMillis(): Long {
        if (history.isEmpty()) return 0L
        return history.map { it.processingMillis }.average().toLong()
    }

    @Synchronized
    fun recentMotionTrend(): Float {
        if (history.isEmpty()) return 0f
        return history.map { it.motionScore }.average().toFloat()
    }

    @Synchronized
    fun frameHistorySize(): Int = history.size

    @Synchronized
    fun clear() {
        history.clear()
    }
}
