package com.hensu.chessmaster

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Color(red:green:blue:) with 0...1 components. */
fun rgb(r: Double, g: Double, b: Double): Color = Color(r.toFloat(), g.toFloat(), b.toFloat())
/** Color(hex: 0xRRGGBB) */
fun hex(value: Long): Color = Color(0xFF000000 or value)
/** .opacity(x) */
fun Color.opacity(alpha: Double): Color = copy(alpha = this.alpha * alpha.toFloat())

/**
 * SwiftUI's system text styles and UIKit's semantic colors, same point sizes and the same
 * light/dark values as iOS. Colors are read inside composition and follow `Sys.dark`
 * (set by RootView from the system setting), so screens recompose when the theme flips.
 */
object Sys {
    /** The active color scheme. */
    var dark: Boolean by mutableStateOf(false)

    private fun s(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, fontWeight = weight)
    val largeTitle = s(34)
    val title = s(28)
    val title2 = s(22)
    val title3 = s(20)
    val headline = s(17, FontWeight.SemiBold)
    val body = s(17)
    val callout = s(16)
    val subheadline = s(15)
    val footnote = s(13)
    val caption = s(12)
    val caption2 = s(11)
    /** Font.system(size:weight:) */
    fun font(size: Number, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.Default, fontSize = size.toFloat().sp, fontWeight = weight)
    /** .monospacedDigit() / .system(design: .rounded) have no twin; the default face is used. */

    private fun pick(light: Long, darkValue: Long): Color = Color(if (dark) darkValue else light)

    /** .primary / Color.primary (label) */
    val primary: Color get() = pick(0xFF000000, 0xFFFFFFFF)
    /** .secondary (secondaryLabel) */
    val secondary: Color get() = pick(0x993C3C43, 0x99EBEBF5)
    /** .tertiary (tertiaryLabel) */
    val tertiary: Color get() = pick(0x4D3C3C43, 0x4DEBEBF5)
    /** Color(.systemBackground) */
    val background: Color get() = pick(0xFFFFFFFF, 0xFF000000)
    /** Color(.secondarySystemBackground) */
    val secondaryBackground: Color get() = pick(0xFFF2F2F7, 0xFF1C1C1E)
    /** Color(.tertiarySystemBackground) */
    val tertiaryBackground: Color get() = pick(0xFFFFFFFF, 0xFF2C2C2E)
    /** Color(.systemGroupedBackground) */
    val groupedBackground: Color get() = pick(0xFFF2F2F7, 0xFF000000)
    /** Color(.secondarySystemGroupedBackground) */
    val secondaryGroupedBackground: Color get() = pick(0xFFFFFFFF, 0xFF1C1C1E)
    /** Color(.tertiarySystemFill) */
    val tertiaryFill: Color get() = pick(0x1F767680, 0x3D767680)
    /** Color(.separator) */
    val separator: Color get() = pick(0x4A3C3C43, 0x99545458)

    /** Color.green / .red / .orange / .blue / .yellow / .gray (system colors). */
    val green: Color get() = pick(0xFF34C759, 0xFF30D158)
    val red: Color get() = pick(0xFFFF3B30, 0xFFFF453A)
    val orange: Color get() = pick(0xFFFF9500, 0xFFFF9F0A)
    val blue: Color get() = pick(0xFF007AFF, 0xFF0A84FF)
    val yellow: Color get() = pick(0xFFFFCC00, 0xFFFFD60A)
    val gray: Color get() = pick(0xFF8E8E93, 0xFF8E8E93)
    val purple: Color get() = pick(0xFFAF52DE, 0xFFBF5AF2)
    val teal: Color get() = pick(0xFF30B0C7, 0xFF40C8E0)
    val indigo: Color get() = pick(0xFF5856D6, 0xFF5E5CE6)
    val mint: Color get() = pick(0xFF00C7BE, 0xFF63E6E2)
    val pink: Color get() = pick(0xFFFF2D55, 0xFFFF375F)
    val brown: Color get() = pick(0xFFA2845E, 0xFFAC8E68)

    /** Color.accentColor (Assets.xcassets/AccentColor: the board's wood brown). */
    val accent: Color = rgb(0.710, 0.533, 0.353)
}
