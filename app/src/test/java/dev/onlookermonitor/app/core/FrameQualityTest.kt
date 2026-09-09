package dev.onlookermonitor.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {
    private val config = MonitorConfig()

    @Test
    fun statsCoverOnlyTheCellsUnderTheBounds() {
        // Left half black, right half bright.
        val grid = grid(4, 4) { _, col -> if (col < 2) 0f else 200f }

        val left = grid.statsIn(FaceBounds(0f, 0f, 0.5f, 1f))
        val right = grid.statsIn(FaceBounds(0.5f, 0f, 1f, 1f))
        val whole = grid.statsIn(FaceBounds(0f, 0f, 1f, 1f))

        assertEquals(0f, left.meanLuma, 0.01f)
        assertEquals(0f, left.contrast, 0.01f)
        assertEquals(200f, right.meanLuma, 0.01f)
        assertEquals(100f, whole.meanLuma, 0.01f)
        assertEquals(100f, whole.contrast, 0.01f)
    }

    @Test
    fun degenerateBoundsStillProduceStats() {
        val grid = grid(4, 4) { _, _ -> 90f }

        val stats = grid.statsIn(FaceBounds(0.5f, 0.5f, 0.5f, 0.5f))

        assertEquals(90f, stats.meanLuma, 0.01f)
    }

    @Test
    fun motionScoreIsZeroForAnIdenticalFrameAndLargeForAShiftedOne() {
        val first = grid(8, 8) { _, col -> if (col < 4) 0f else 255f }
        val same = grid(8, 8) { _, col -> if (col < 4) 0f else 255f }
        val shifted = grid(8, 8) { _, col -> if (col < 4) 255f else 0f }

        assertEquals(0f, first.meanAbsDiff(same), 0.01f)
        assertEquals(255f, first.meanAbsDiff(shifted), 0.01f)
    }

    @Test
    fun mismatchedGridSizesReadAsMaximumMotion() {
        val portrait = grid(4, 8) { _, _ -> 100f }
        val landscape = grid(8, 4) { _, _ -> 100f }

        assertEquals(LumaGrid.MAX_LUMA, portrait.meanAbsDiff(landscape), 0.01f)
    }

    @Test
    fun nearBlackFrameIsTooDark() {
        val quality = grid(8, 8) { _, _ -> 10f }.quality(null, config)

        assertEquals(LightingCondition.TOO_DARK, quality.lighting)
        assertTrue(quality.degraded)
    }

    @Test
    fun dimFrameIsLowLight() {
        val quality = grid(8, 8) { _, _ -> 45f }.quality(null, config)

        assertEquals(LightingCondition.LOW_LIGHT, quality.lighting)
    }

    @Test
    fun evenlyLitFrameIsGood() {
        val quality = grid(8, 8) { _, _ -> 130f }.quality(null, config)

        assertEquals(LightingCondition.GOOD, quality.lighting)
        assertFalse(quality.degraded)
    }

    @Test
    fun blownHighlightsBesideDarkRegionsAreBacklit() {
        // Top third clipped, bottom third near black: the classic window-behind-the-user frame.
        val quality = grid(9, 9) { row, _ ->
            when {
                row < 3 -> 250f
                row < 6 -> 120f
                else -> 15f
            }
        }.quality(null, config)

        assertEquals(LightingCondition.BACKLIT, quality.lighting)
        assertTrue(quality.clippedFraction >= config.backlitClippedFraction)
        assertTrue(quality.darkFraction >= config.backlitDarkFraction)
    }

    @Test
    fun qualityCarriesMotionFromThePreviousGrid() {
        val previous = grid(4, 4) { _, _ -> 100f }
        val current = grid(4, 4) { _, _ -> 140f }

        assertEquals(40f, current.quality(previous, config).motionScore, 0.01f)
        assertEquals(0f, current.quality(null, config).motionScore, 0.01f)
    }

    @Test
    fun sensorPointInvertsMlKitUprightRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val sensorU = 0.25f
            val sensorV = 0.80f
            val rotated = rotateClockwise(sensorU, sensorV, rotation)

            val recovered = sensorPointFor(rotated.first, rotated.second, rotation)

            assertEquals("rotation " + rotation, sensorU, recovered.x, 0.0001f)
            assertEquals("rotation " + rotation, sensorV, recovered.y, 0.0001f)
        }
    }

    @Test
    fun quarterTurnsMoveThePointAsExpected() {
        val upright = sensorPointFor(0.1f, 0.2f, 0)
        val quarter = sensorPointFor(0.1f, 0.2f, 90)

        assertEquals(0.1f, upright.x, 0.0001f)
        assertEquals(0.2f, upright.y, 0.0001f)
        assertEquals(0.2f, quarter.x, 0.0001f)
        assertEquals(0.9f, quarter.y, 0.0001f)
        assertNotEquals(upright, quarter)
    }

    @Test
    fun gridDimensionsStayRoughlySquarePerOrientation() {
        assertEquals(
            LumaGrid.LONG_EDGE_CELLS to LumaGrid.SHORT_EDGE_CELLS,
            LumaGrid.dimensionsFor(640, 480),
        )
        assertEquals(
            LumaGrid.SHORT_EDGE_CELLS to LumaGrid.LONG_EDGE_CELLS,
            LumaGrid.dimensionsFor(480, 640),
        )
    }

    /** Forward of [sensorPointFor]: how ML Kit rotates the sensor image to upright. */
    private fun rotateClockwise(u: Float, v: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            90 -> (1f - v) to u
            180 -> (1f - u) to (1f - v)
            270 -> v to (1f - u)
            else -> u to v
        }

    private fun grid(cols: Int, rows: Int, value: (row: Int, col: Int) -> Float): LumaGrid {
        val cells = FloatArray(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                cells[row * cols + col] = value(row, col)
            }
        }
        return LumaGrid(cols, rows, cells)
    }
}
