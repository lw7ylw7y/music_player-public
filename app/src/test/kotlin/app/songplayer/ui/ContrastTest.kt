package app.songplayer.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/** NFR-5: text contrast at least 4.5 : 1, in both themes, for every text-on-background pair the screens use. */
class ContrastTest {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun pairs(s: ColorScheme) = mapOf(
        "text on background" to (s.onBackground to s.background),
        "text on surface" to (s.onSurface to s.surface),
        "secondary text on surface" to (s.onSurfaceVariant to s.surface),
        "secondary text on background" to (s.onSurfaceVariant to s.background),
        "secondary text on the tinted rows and chips" to (s.onSurfaceVariant to s.surfaceVariant),
        "accent text on background" to (s.primary to s.background),
        "accent text on surface" to (s.primary to s.surface),
        "text on a filled button" to (s.onPrimary to s.primary),
        "text on a chip" to (s.onSecondaryContainer to s.secondaryContainer),
        "error text on background" to (s.error to s.background),
        "error text on surface" to (s.error to s.surface),
        "snackbar text" to (s.inverseOnSurface to s.inverseSurface),
        "snackbar action" to (s.inversePrimary to s.inverseSurface),
    )

    private fun check(theme: String, scheme: ColorScheme) {
        for ((name, pair) in pairs(scheme)) {
            val r = ratio(pair.first, pair.second)
            assertTrue("$theme: $name is %.2f : 1, under 4.5".format(r), r >= 4.5)
        }
    }

    @Test
    fun Contrast_LightTheme_EveryTextPairIsAtLeast4Point5() = check("light", LightColors)

    @Test
    fun Contrast_DarkTheme_EveryTextPairIsAtLeast4Point5() = check("dark", DarkColors)
}
