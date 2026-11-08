package com.zcode.android.core.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.zcode.android.core.ui.R

/**
 * F-37-C: the IME contract for a secret — the password input type (keyboards never learn,
 * suggest or sync what is typed into it), no autocorrect, no auto-capitalization.
 */
val SecretKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

/**
 * F-37-C: one field for every credential the user types (the direct-server token, the
 * manual pairing link). Masked by default with [SecretKeyboardOptions]; the eye toggle
 * reveals it for a visual check and the field RE-MASKS the moment focus leaves it, so a
 * peeked secret never stays on screen. The value itself is never altered — paste works.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    revealTag: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        supportingText = supportingText,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = SecretKeyboardOptions,
        trailingIcon = {
            IconButton(
                onClick = {
                    revealed = !revealed
                    // A reveal always owns focus, so leaving the field is what re-masks it.
                    if (revealed) runCatching { focus.requestFocus() }
                },
                modifier = if (revealTag != null) Modifier.testTag(revealTag) else Modifier,
            ) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (revealed) R.string.secret_hide else R.string.secret_reveal),
                )
            }
        },
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused) revealed = false },
    )
}
