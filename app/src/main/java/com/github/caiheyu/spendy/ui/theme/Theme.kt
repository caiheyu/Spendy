package com.github.caiheyu.spendy.ui.theme

import android.os.Build
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.github.caiheyu.spendy.domain.AppSettings

val LocalSpendyColors = staticCompositionLocalOf { SpendyLight }
fun colorOf(value: String) = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(SpendyPrimary)

fun spendyColorScheme(dark: Boolean, accent: Color = SpendyPrimary): ColorScheme {
    val colors = if (dark) SpendyDark else SpendyLight
    val neutral = colors.ink.copy(alpha = .06f).compositeOver(colors.card)
    val primaryTint = accent.copy(alpha = .12f).compositeOver(colors.card)
    val secondaryTint = SpendyGradientEnd.copy(alpha = .12f).compositeOver(colors.card)
    val incomeTint = Income.copy(alpha = .12f).compositeOver(colors.card)
    val errorTint = Expense.copy(alpha = .12f).compositeOver(colors.card)
    // Explicit roles prevent Material defaults from adding lavender fills and tonal surfaces.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = Color.White,
        primaryContainer = primaryTint, onPrimaryContainer = colors.ink,
        inversePrimary = accent,
        secondary = SpendyGradientEnd, onSecondary = Color.White,
        secondaryContainer = secondaryTint, onSecondaryContainer = colors.ink,
        tertiary = Income, onTertiary = Color.White,
        tertiaryContainer = incomeTint, onTertiaryContainer = colors.ink,
        background = colors.background, onBackground = colors.ink,
        surface = colors.card, onSurface = colors.ink,
        surfaceVariant = neutral, onSurfaceVariant = colors.secondaryInk,
        surfaceTint = Color.Transparent,
        inverseSurface = if (dark) SpendyLight.ink else Color(0xFF1C1E2B),
        inverseOnSurface = Color.White,
        error = Expense, onError = Color.White,
        errorContainer = errorTint, onErrorContainer = colors.ink,
        outline = colors.line, outlineVariant = colors.line, scrim = Color.Black,
        surfaceBright = colors.card, surfaceDim = colors.background,
        surfaceContainerLowest = colors.background, surfaceContainerLow = colors.card,
        surfaceContainer = colors.card, surfaceContainerHigh = colors.card,
        surfaceContainerHighest = colors.card,
    )
}

@Composable
fun SpendyTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val accent = colorOf(settings.accent)
    val colors = if (dark) SpendyDark else SpendyLight
    val view = LocalView.current
    LaunchedEffect(view, dark) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()
        activity?.let {
            it.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(SpendyLight.background.toArgb(), SpendyDark.background.toArgb()) { dark },
            )
            WindowInsetsControllerCompat(it.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        else -> spendyColorScheme(dark, accent)
    }
    CompositionLocalProvider(LocalSpendyColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
