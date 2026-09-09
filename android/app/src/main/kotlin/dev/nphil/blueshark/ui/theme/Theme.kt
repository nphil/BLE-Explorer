package dev.nphil.blueshark.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Persisted look settings. `paletteId == null` means "Material You dynamic color". */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val paletteId: String? = Palettes.DEFAULT_ID,
) {
    val usesDynamicColor: Boolean get() = paletteId == null
}

val LocalThemeSettings = staticCompositionLocalOf { ThemeSettings() }

private val Context.themeStore: DataStore<Preferences> by preferencesDataStore("theme")
private val KEY_MODE = stringPreferencesKey("mode")
private val KEY_PALETTE = stringPreferencesKey("palette")
private const val DYNAMIC = "dynamic"

class ThemeRepository(private val context: Context) {
    val settings: Flow<ThemeSettings> = context.themeStore.data.map { prefs ->
        ThemeSettings(
            mode = prefs[KEY_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            paletteId = when (val p = prefs[KEY_PALETTE]) {
                null -> Palettes.DEFAULT_ID
                DYNAMIC -> null
                else -> p
            },
        )
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setPalette(paletteId: String?) {
        context.themeStore.edit { it[KEY_PALETTE] = paletteId ?: DYNAMIC }
    }
}

@Composable
fun ThemeSettings.isDark(): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun ThemeSettings.resolveScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return if (usesDynamicColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        Palettes.byId(paletteId).scheme(dark)
    }
}

private val BlueSharkTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.2.sp),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Default),
    )
}

/** Monospace style used for hex dumps, UUIDs, addresses. */
val MonoFamily: FontFamily = FontFamily.Monospace

@Composable
fun BlueSharkTheme(settings: ThemeSettings, content: @Composable () -> Unit) {
    val dark = settings.isDark()
    val scheme = settings.resolveScheme(dark)
    androidx.compose.runtime.CompositionLocalProvider(LocalThemeSettings provides settings) {
        MaterialTheme(colorScheme = scheme, typography = BlueSharkTypography, content = content)
    }
}
