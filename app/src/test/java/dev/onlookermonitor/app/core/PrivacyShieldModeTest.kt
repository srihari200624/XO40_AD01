package dev.onlookermonitor.app.core

import dev.onlookermonitor.app.overlay.PrivacyShieldMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyShieldModeTest {
    @Test
    fun enumHasExpectedValues() {
        assertEquals(2, PrivacyShieldMode.values().size)
        assertEquals(PrivacyShieldMode.BLACK_SCREEN, PrivacyShieldMode.valueOf("BLACK_SCREEN"))
        assertEquals(PrivacyShieldMode.POPUP_ALERT, PrivacyShieldMode.valueOf("POPUP_ALERT"))
    }
}
