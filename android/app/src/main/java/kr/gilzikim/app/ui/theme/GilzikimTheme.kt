package kr.gilzikim.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark, type-led palette after the EV dashboard references: near-black surfaces stepping up in
 * small increments, oversized white numerals, gray labels, and one mint primary ("guard") for the
 * route, the driver's choice and the main action, kept apart from warning colors. Red is reserved for possible damage to the car. No outlines anywhere.
 */
object GzColors {
    val Background = Color(0xFF0A0B0D)
    val MapEmpty = Color(0xFF121316)
    /** Bundled road network, drawn when there are no map tiles. */
    val RoadMajor = Color(0xFF34373E)
    val RoadMiddle = Color(0xFF2B2E34)
    val RoadMinor = Color(0xFF22252A)
    /** Floating panel over the map. */
    val Card = Color(0xFF16171A)
    /** Rows and buttons inside the panel. */
    val Raised = Color(0xFF212327)
    val TextPrimary = Color(0xFFF5F6F7)
    val TextSecondary = Color(0xFF8E929A)
    val TextMuted = Color(0xFF5E626A)
    /** The app's primary color: the route, the driver's selection, the main action, progress. */
    /** Teal mint: less green than before, so it reads as "guard", not "go / success". */
    val Primary = Color(0xFF2ED3C6)
    val OnPrimary = Color(0xFF062B29)
    val Hazard = Color(0xFFFF5A5F)
    val HazardTint = Color(0xFF3A1E21)
    val Dismissed = Color(0xFF474A51)
    val Track = Color(0xFF2C2E33)
    /** Hairline between read-only rows. Never around things. */
    val Divider = Color(0xFF26282D)
    val Chip = Color(0xF01D1F23)
}

@Composable
fun GilzikimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = GzColors.Background,
            surface = GzColors.Card,
            primary = GzColors.Primary,
            onSurface = GzColors.TextPrimary,
            onBackground = GzColors.TextPrimary,
        ),
        content = content,
    )
}
