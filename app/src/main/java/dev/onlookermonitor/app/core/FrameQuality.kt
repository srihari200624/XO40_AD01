package dev.onlookermonitor.app.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * ML Kit exposes no general detection-confidence score, so reliability has to come from
 * somewhere else. These types derive a cheap imaging-quality signal from the camera's luma
 * plane: how bright the scene is, how much structure a detection actually sits on, and how
 * much the frame moved since the previous analysis.
 */
enum class LightingCondition {
    UNKNOWN,
    GOOD,
    LOW_LIGHT,
    TOO_DARK,
    BACKLIT,
}

/** Brightness and local contrast of one region, in 0..255 luma units. */
data class PatchStats(val meanLuma: Float, val contrast: Float)

data class FrameQuality(
    val meanLuma: Float,
    val contrast: Float,
    val darkFraction: Float,
    val clippedFraction: Float,
    val motionScore: Float,
    val lighting: LightingCondition,
) {
    val degraded: Boolean
        get() = lighting == LightingCondition.LOW_LIGHT ||
            lighting == LightingCondition.BACKLIT ||
            lighting == LightingCondition.TOO_DARK

    companion object {
        /** Used when no luma plane was available; every quality gate then passes through. */
        val UNKNOWN = FrameQuality(0f, 0f, 0f, 0f, 0f, LightingCondition.UNKNOWN)
    }
}

/**
 * A coarse downscale of the frame's luma plane, already expressed in the rotated (ML Kit)
 * coordinate space so that [FaceBounds] lookups are a direct index.
 */
class LumaGrid(val cols: Int, val rows: Int, val cells: FloatArray) {
    init {
        require(cols > 0 && rows > 0) { "grid must have positive dimensions" }
        require(cells.size == cols * rows) { "cell count must match grid dimensions" }
    }

    fun statsIn(bounds: FaceBounds): PatchStats {
        val firstCol = floor(bounds.left * cols).toInt().coerceIn(0, cols - 1)
        val lastCol = (ceil(bounds.right * cols).toInt() - 1).coerceIn(firstCol, cols - 1)
        val firstRow = floor(bounds.top * rows).toInt().coerceIn(0, rows - 1)
        val lastRow = (ceil(bounds.bottom * rows).toInt() - 1).coerceIn(firstRow, rows - 1)

        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (row in firstRow..lastRow) {
            for (col in firstCol..lastCol) {
                val value = cells[row * cols + col].toDouble()
                sum += value
                squares += value * value
                count += 1
            }
        }
        if (count == 0) return PatchStats(0f, 0f)
        val mean = sum / count
        val variance = (squares / count - mean * mean).coerceAtLeast(0.0)
        return PatchStats(mean.toFloat(), sqrt(variance).toFloat())
    }

    /** Mean absolute per-cell difference; a proxy for camera or scene movement. */
    fun meanAbsDiff(other: LumaGrid): Float {
        if (other.cols != cols || other.rows != rows) return MAX_LUMA
        var total = 0.0
        for (index in cells.indices) {
            total += abs(cells[index] - other.cells[index])
        }
        return (total / cells.size).toFloat()
    }

    fun quality(previous: LumaGrid?, config: MonitorConfig): FrameQuality {
        var sum = 0.0
        var squares = 0.0
        var dark = 0
        var clipped = 0
        for (value in cells) {
            val luma = value.toDouble()
            sum += luma
            squares += luma * luma
            if (value < config.darkCellLuma) dark += 1
            if (value > config.clippedCellLuma) clipped += 1
        }
        val mean = sum / cells.size
        val contrast = sqrt((squares / cells.size - mean * mean).coerceAtLeast(0.0)).toFloat()
        val darkFraction = dark.toFloat() / cells.size
        val clippedFraction = clipped.toFloat() / cells.size
        val lighting = when {
            mean < config.tooDarkMeanLuma -> LightingCondition.TOO_DARK
            clippedFraction >= config.backlitClippedFraction &&
                darkFraction >= config.backlitDarkFraction -> LightingCondition.BACKLIT
            mean < config.lowLightMeanLuma -> LightingCondition.LOW_LIGHT
            else -> LightingCondition.GOOD
        }
        return FrameQuality(
            meanLuma = mean.toFloat(),
            contrast = contrast,
            darkFraction = darkFraction,
            clippedFraction = clippedFraction,
            motionScore = previous?.let(::meanAbsDiff) ?: 0f,
            lighting = lighting,
        )
    }

    companion object {
        const val MAX_LUMA = 255f
        const val LONG_EDGE_CELLS = 32
        const val SHORT_EDGE_CELLS = 24

        /** Keeps cells roughly square for either frame orientation. */
        fun dimensionsFor(width: Int, height: Int): Pair<Int, Int> = if (width >= height) {
            LONG_EDGE_CELLS to SHORT_EDGE_CELLS
        } else {
            SHORT_EDGE_CELLS to LONG_EDGE_CELLS
        }
    }
}

/**
 * A point in normalized sensor coordinates, i.e. the raw image plane before ML Kit's upright
 * rotation. Both luma sampling and CameraX metering work in that space.
 */
data class SensorPoint(val x: Float, val y: Float)

/**
 * Maps a normalized point in the rotated (upright, ML Kit) frame back to normalized sensor
 * coordinates. ML Kit rotates the sensor image clockwise by `rotationDegrees` to make it
 * upright, so reading the raw luma plane -- or handing a point to CameraX metering -- needs
 * the inverse of that rotation.
 */
fun sensorPointFor(u: Float, v: Float, rotationDegrees: Int): SensorPoint =
    SensorPoint(sensorX(u, v, rotationDegrees), sensorY(u, v, rotationDegrees))

/** Allocation-free component of [sensorPointFor], for per-pixel sampling loops. */
fun sensorX(u: Float, v: Float, rotationDegrees: Int): Float =
    when (normalizedRotation(rotationDegrees)) {
        90 -> v
        180 -> 1f - u
        270 -> 1f - v
        else -> u
    }

/** Allocation-free component of [sensorPointFor], for per-pixel sampling loops. */
fun sensorY(u: Float, v: Float, rotationDegrees: Int): Float =
    when (normalizedRotation(rotationDegrees)) {
        90 -> 1f - u
        180 -> 1f - v
        270 -> u
        else -> v
    }

private fun normalizedRotation(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360
