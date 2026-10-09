package com.zcode.android.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Compact phone-density type scale — ui-ux/typography.md v2. Operational screens
 * claim LESS vertical space for titles; content wins. 200% font scale must not clip
 * primary actions (§30) — all sizes are sp so they scale, and line heights are
 * proportional (no fixed-height containers on text).
 *
 * Code font: system monospace (typography.md allows "JetBrains Mono OR system
 * monospace"; the OFL font binary is a Phase 11 polish option).
 */
data class ZCodeTypography(
    val m3: Typography,
    /** ui-ux `code` 12/18 mono — code blocks. */
    val code: TextStyle,
) {
    val titleScreen: TextStyle get() = m3.titleLarge
    val titleSection: TextStyle get() = m3.titleSmall
    val titleTask: TextStyle get() = m3.titleMedium
    val body: TextStyle get() = m3.bodyMedium
    val bodySm: TextStyle get() = m3.bodySmall
    val label: TextStyle get() = m3.labelSmall
    val button: TextStyle get() = m3.labelLarge
}

val ZCodeTypographyLight = ZCodeTypography(
    m3 = Typography(
        // titleScreen 18/24 SemiBold — app bar only, never marketing display type.
        titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        // titleTask 15/20 SemiBold — task row primary.
        titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        // titleSection 13/18 SemiBold — workspace headers, section labels.
        titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        // body 14/20 — conversation prose (bodyLarge kept equal so default Text is compact).
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        // bodySm 12/16 — meta, timestamps.
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        // label 11/14 Medium — status chips, overlines.
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
        // button 14/18 Medium.
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    ),
    code = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace),
)
