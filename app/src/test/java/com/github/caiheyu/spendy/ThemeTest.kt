package com.github.caiheyu.spendy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.github.caiheyu.spendy.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTest {
    @Test fun lightPaletteMatchesPrototype() {
        val scheme = spendyColorScheme(dark = false)
        assertEquals(Color(0xFFF4F5FA), scheme.background)
        assertEquals(Color.White, scheme.surface)
        assertEquals(Color(0xFF1B1C28), scheme.onSurface)
        assertEquals(Color(0xFF71748A), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFECEDF4), scheme.outlineVariant)
        assertEquals(Color(0xFF686D80), SpendyLight.mutedInk)
        assertBrandAndSurfaces(dark = false)
    }

    @Test fun darkPaletteKeepsTheSameBrandGradient() {
        val scheme = spendyColorScheme(dark = true)
        assertEquals(Color(0xFF0E0F16), scheme.background)
        assertEquals(Color(0xFF171923), scheme.surface)
        assertEquals(Color(0xFFF1F2F8), scheme.onSurface)
        assertEquals(Color(0xFF9BA0B5), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF242737), scheme.outlineVariant)
        assertEquals(Color(0xFF858CA3), SpendyDark.mutedInk)
        assertBrandAndSurfaces(dark = true)
    }

    @Test fun customAccentControlsButtonsAndTheirTintWithoutChangingLedgerColors() {
        val accent = Color(0xFF0E9F8A)
        listOf(false, true).forEach { dark ->
            val scheme = spendyColorScheme(dark, accent)
            assertEquals(accent, scheme.primary)
            assertEquals(accent.copy(alpha = .12f).compositeOver(scheme.surface), scheme.primaryContainer)
            assertEquals(Color(0xFFF0564A), scheme.error)
            assertEquals(Color(0xFF1FA97A), scheme.tertiary)
        }
    }

    private fun assertBrandAndSurfaces(dark: Boolean) {
        val scheme = spendyColorScheme(dark)
        assertEquals(Color(0xFF5560E9), scheme.primary)
        assertEquals(Color(0xFF8A5CF6), scheme.secondary)
        assertEquals(Color.Transparent, scheme.surfaceTint)
        assertEquals(scheme.surface, scheme.surfaceContainer)
        assertEquals(scheme.surface, scheme.surfaceContainerHigh)
        assertEquals(scheme.surface, scheme.surfaceContainerHighest)
    }
}
