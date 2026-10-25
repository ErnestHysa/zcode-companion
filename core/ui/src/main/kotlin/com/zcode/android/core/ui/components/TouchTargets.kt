package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * §30.2 touch targets (B3-a11y): every interactive node is at least 48dp. Icon buttons
 * and chips take the square minimum; list rows take the height minimum. Apply BEFORE
 * `clickable` so the whole target area is the click area.
 */
val MinTouchTarget = 48.dp

fun Modifier.minTouchTarget(): Modifier = this.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)

fun Modifier.minRowTouchTarget(): Modifier = this.heightIn(min = MinTouchTarget)
