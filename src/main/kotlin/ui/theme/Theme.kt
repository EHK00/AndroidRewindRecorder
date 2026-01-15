package ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),         // Sky blue
    onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF004D67),
    onPrimaryContainer = Color(0xFFBFE9FF),
    
    secondary = Color(0xFFA5D6A7),        // Mint green
    onSecondary = Color(0xFF003910),
    secondaryContainer = Color(0xFF005319),
    onSecondaryContainer = Color(0xFFC0F2C1),
    
    tertiary = Color(0xFFFFB74D),          // Amber
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    
    background = Color(0xFF0F172A),        // Slate 900
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E293B),           // Slate 800
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),    // Slate 700
    onSurfaceVariant = Color(0xFFCBD5E1),
    
    outline = Color(0xFF64748B),           // Slate 500
    outlineVariant = Color(0xFF475569),
    
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF1E293B),
    inversePrimary = Color(0xFF0284C7),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
