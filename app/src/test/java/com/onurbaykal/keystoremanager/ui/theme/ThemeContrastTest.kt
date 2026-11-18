package com.onurbaykal.keystoremanager.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA needs 4.5:1 for normal-size text, which includes the 16sp section titles. */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun assertReadable(name: String, text: Color, background: Color) {
        val ratio = contrast(text, background)
        assertTrue("$name contrast is %.2f:1, needs 4.5:1".format(ratio), ratio >= 4.5)
    }

    @Test
    fun `section titles are readable on every surface they sit on`() {
        listOf(
            "background" to LightColors.background,
            "card" to LightColors.surfaceContainerHighest,
            "list card" to LightColors.surfaceContainerLow,
        ).forEach { (name, surface) -> assertReadable("light accent text on $name", LightAccentText, surface) }

        listOf(
            "background" to DarkColors.background,
            "card" to DarkColors.surfaceContainerHighest,
            "list card" to DarkColors.surfaceContainerLow,
        ).forEach { (name, surface) -> assertReadable("dark accent text on $name", DarkColors.primary, surface) }
    }

    @Test
    fun `button labels are readable`() {
        assertReadable("light button", LightColors.onPrimary, LightColors.primary)
        assertReadable("dark button", DarkColors.onPrimary, DarkColors.primary)
    }
}
