package app.lumen.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

object C {
    val bg = Color(0xFF0B0D12)
    val bg2 = Color(0xFF161A23)
    val card = Color(0xFF1C212D)
    val cardHi = Color(0xFF2A3142)
    val text = Color(0xFFF1F3F8)
    val text2 = Color(0xFFA3ABBD)
    val text3 = Color(0xFF6D7588)
    val live = Color(0xFFE5343F)
    val accent: Color get() = Color(AppState.accent)
}

/**
 * Lays the interface out on a 960 × 540 dp canvas whatever the TV's resolution or density, so it looks the
 * same on every TV, then applies the "Interface size" setting.
 */
@Composable
fun LumenTheme(scalePercent: Int, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dm = ctx.resources.displayMetrics
    val widthPx = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
    val base = widthPx / 960f
    val density = Density(base * (scalePercent.coerceIn(70, 150) / 100f), 1f)
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = C.accent, background = C.bg, surface = C.bg2, onSurface = C.text, onBackground = C.text,
                surfaceVariant = C.card, onSurfaceVariant = C.text2,
            ),
            content = content,
        )
    }
}

/** Material icons not in the core set (paths from Material Design icons, Apache 2.0). */
object Ico {
    private fun v(name: String, path: String) = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()

    val Movie = v("movie", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    val Tv = v("tv", "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z")
    val LiveTv = v("livetv", "M21,6h-7.59l3.29,-3.29L16,2l-4,4 -4,-4 -0.71,0.71L10.59,6H3c-1.1,0 -2,0.89 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V8c0,-1.11 -0.9,-2 -2,-2zM21,20H3V8h18v12zM9,10v8l7,-4z")
    val Guide = v("guide", "M3,14h4v-4H3v4zM3,19h4v-4H3v4zM3,9h4V5H3v4zM8,14h13v-4H8v4zM8,19h13v-4H8v4zM8,5v4h13V5H8z")
    val Pause = v("pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z")
    val SkipNext = v("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Rewind = v("rew", "M11,18L11,6l-8.5,6 8.5,6zM11.5,12l8.5,6L20,6l-8.5,6z")
    val Forward = v("ff", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z")
    val Subtitles = v("subs", "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zM14,18H4v-2h10v2zM20,18h-4v-2h4v2zM20,14H10v-2h10v2z")
    val Rec = v("rec", "M12,12m-8,0a8,8 0,1 1,16 0a8,8 0,1 1,-16 0")
    val Library = v("library", "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,14.5v-9l6,4.5 -6,4.5z")
    val Restart = v("restart", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    val Channels = v("channels", "M4,10.5c-0.83,0 -1.5,0.67 -1.5,1.5s0.67,1.5 1.5,1.5 1.5,-0.67 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM4,4.5c-0.83,0 -1.5,0.67 -1.5,1.5S3.17,7.5 4,7.5 5.5,6.83 5.5,6 4.83,4.5 4,4.5zM4,16.5c-0.83,0 -1.5,0.68 -1.5,1.5s0.68,1.5 1.5,1.5 1.5,-0.68 1.5,-1.5 -0.67,-1.5 -1.5,-1.5zM7,19h14v-2H7v2zM7,13h14v-2H7v2zM7,5v2h14V5H7z")
}
