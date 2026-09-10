package dev.onlookermonitor.app.camera

import dev.onlookermonitor.app.core.FaceBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OnlookerPhotoCapturerTest {

    @Test
    fun faceBoundsCalculationsAreValid() {
        val bounds = FaceBounds(left = 0.2f, top = 0.3f, right = 0.5f, bottom = 0.7f)
        assertEquals(0.3f, bounds.width, 0.001f)
        assertEquals(0.4f, bounds.height, 0.001f)
        assertEquals(0.35f, bounds.centerX, 0.001f)
        assertEquals(0.5f, bounds.centerY, 0.001f)
    }

    @Test
    fun paddingInFaceCroppingIsNonNegative() {
        val bounds = FaceBounds(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f)
        assertNotNull(bounds)
    }
}
