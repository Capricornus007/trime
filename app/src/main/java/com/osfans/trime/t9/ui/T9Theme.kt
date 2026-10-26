@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.osfans.trime.t9.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive 主題。
 *
 * 形狀刻意不覆寫：`MaterialExpressiveTheme` 自帶的就是 M3E 的多邊形輪廓
 * （MaterialShapes 那批），自己填 `Shapes(...)` 反而會退回普通圓角。
 * 顏色走系統動態取色（Monet）；`dynamicXColorScheme` 在拿不到動態色的機器上會給空，
 * 那时退回內建的深／淺色板。
 */
@Composable
fun T9Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val dynamic: ColorScheme? =
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    MaterialExpressiveTheme(
        colorScheme = dynamic ?: if (dark) darkColorScheme() else lightColorScheme(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
