package com.heihun.pixivplus.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface

/**
 * Material Design 3 颜色角色。
 *
 * 模块运行在宿主进程里，无法使用自身 APK 的资源（LSPosed 不注入模块资源），
 * 因此这里使用纯代码定义配色，不依赖 XML 主题或 Material 库。
 */
data class Md3Colors(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val isDark: Boolean
)

object Md3 {

    fun isDark(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    fun colors(context: Context): Md3Colors = if (isDark(context)) DARK else LIGHT

    val medium: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    val regular: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }

    private val LIGHT = Md3Colors(
        primary = 0xFF00639B.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFCDE5FF.toInt(),
        onPrimaryContainer = 0xFF001D32.toInt(),
        secondaryContainer = 0xFFD3E5F4.toInt(),
        onSecondaryContainer = 0xFF0C1D29.toInt(),
        surface = 0xFFF8F9FC.toInt(),
        onSurface = 0xFF191C20.toInt(),
        surfaceContainerLowest = 0xFFFFFFFF.toInt(),
        surfaceContainerLow = 0xFFF2F3F7.toInt(),
        surfaceContainer = 0xFFEDEEF3.toInt(),
        surfaceContainerHigh = 0xFFE7E8EE.toInt(),
        surfaceContainerHighest = 0xFFE1E2E9.toInt(),
        onSurfaceVariant = 0xFF41474E.toInt(),
        outline = 0xFF71787F.toInt(),
        outlineVariant = 0xFFC1C7CE.toInt(),
        error = 0xFFBA1A1A.toInt(),
        onError = 0xFFFFFFFF.toInt(),
        errorContainer = 0xFFFFDAD6.toInt(),
        onErrorContainer = 0xFF410002.toInt(),
        isDark = false
    )

    private val DARK = Md3Colors(
        primary = 0xFF95CCFF.toInt(),
        onPrimary = 0xFF003353.toInt(),
        primaryContainer = 0xFF004A75.toInt(),
        onPrimaryContainer = 0xFFCDE5FF.toInt(),
        secondaryContainer = 0xFF384956.toInt(),
        onSecondaryContainer = 0xFFD3E5F4.toInt(),
        surface = 0xFF101418.toInt(),
        onSurface = 0xFFE1E2E6.toInt(),
        surfaceContainerLowest = 0xFF0B0F12.toInt(),
        surfaceContainerLow = 0xFF181C20.toInt(),
        surfaceContainer = 0xFF1C2024.toInt(),
        surfaceContainerHigh = 0xFF272A2E.toInt(),
        surfaceContainerHighest = 0xFF313539.toInt(),
        onSurfaceVariant = 0xFFC1C7CE.toInt(),
        outline = 0xFF8B9198.toInt(),
        outlineVariant = 0xFF41474E.toInt(),
        error = 0xFFFFB4AB.toInt(),
        onError = 0xFF690005.toInt(),
        errorContainer = 0xFF93000A.toInt(),
        onErrorContainer = 0xFFFFDAD6.toInt(),
        isDark = true
    )
}
