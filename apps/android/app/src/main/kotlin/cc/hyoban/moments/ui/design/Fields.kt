package cc.hyoban.moments.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.tokens

// The design system's text fields, on Compose's basic one: a field's name above it in the
// small print, never floating into its edge. A boxed field ([OutlinedTextField]) sits on the
// surface in the medium corners, its edge the control border at 3:1, the text colour while
// typed in, the danger's in error; a plain one ([TextField]) is the text alone, for a field
// already inside a group.

/**
 * What is around the text being typed: its name, its leading icon and prefix, what it shows
 * while empty, its suffix and trailing icon, and its note or error under it; in a box when [boxed].
 */
@Composable
private fun Decoration(
    field: @Composable () -> Unit,
    empty: Boolean,
    focused: Boolean,
    boxed: Boolean,
    singleLine: Boolean,
    textStyle: TextStyle,
    enabled: Boolean,
    isError: Boolean,
    label: (@Composable () -> Unit)?,
    placeholder: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    prefix: (@Composable () -> Unit)?,
    suffix: (@Composable () -> Unit)?,
    supportingText: (@Composable () -> Unit)?,
) {
    val shape = AppTheme.shapes.medium
    val edge = when {
        isError -> tokens.danger
        !enabled -> tokens.border
        focused -> tokens.tx
        else -> tokens.borderStrong
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null && boxed) Caption(label)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (boxed) 48.dp else 56.dp)
                .then(if (boxed) Modifier.background(if (enabled) tokens.surface else tokens.fill, shape).border(1.dp, edge, shape) else Modifier)
                .padding(horizontal = if (boxed) 14.dp else 16.dp, vertical = 12.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leadingIcon != null) {
                CompositionLocalProvider(LocalContentColor provides tokens.tx2) { leadingIcon() }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (label != null && !boxed) Caption(label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefix != null) {
                        CompositionLocalProvider(LocalContentColor provides tokens.tx2) { ProvideTextStyle(textStyle) { prefix() } }
                        Spacer(Modifier.width(4.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (empty && placeholder != null) {
                            CompositionLocalProvider(LocalContentColor provides tokens.tx3) { ProvideTextStyle(textStyle) { placeholder() } }
                        }
                        field()
                    }
                    if (suffix != null) {
                        Spacer(Modifier.width(4.dp))
                        CompositionLocalProvider(LocalContentColor provides tokens.tx2) { ProvideTextStyle(textStyle) { suffix() } }
                    }
                }
            }
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                CompositionLocalProvider(LocalContentColor provides if (isError) tokens.danger else tokens.tx2) { trailingIcon() }
            }
        }
        if (supportingText != null) {
            Box(Modifier.padding(start = if (boxed) 4.dp else 16.dp)) {
                CompositionLocalProvider(LocalContentColor provides if (isError) tokens.danger else tokens.tx2) {
                    ProvideTextStyle(AppTheme.typography.caption) { supportingText() }
                }
            }
        }
    }
}

@Composable
private fun Caption(label: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides tokens.tx2) {
        ProvideTextStyle(AppTheme.typography.caption) { Box(Modifier.padding(start = 4.dp)) { label() } }
    }
}

/** The style typed text takes: [textStyle] in the text colour, faded while not [enabled]. */
@Composable
private fun typed(textStyle: TextStyle, enabled: Boolean): TextStyle =
    textStyle.merge(TextStyle(color = if (enabled) tokens.tx else tokens.tx3))

@Composable
private fun StringField(
    boxed: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle,
    label: (@Composable () -> Unit)?,
    placeholder: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    prefix: (@Composable () -> Unit)?,
    suffix: (@Composable () -> Unit)?,
    supportingText: (@Composable () -> Unit)?,
    isError: Boolean,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = typed(textStyle, enabled)
    BasicTextField(
        value, onValueChange, modifier, enabled, readOnly, style, keyboardOptions, keyboardActions, singleLine,
        if (singleLine) 1 else maxLines, minLines, visualTransformation, interactionSource = interaction, cursorBrush = SolidColor(tokens.tx),
        decorationBox = { inner ->
            Decoration(inner, value.isEmpty(), focused, boxed, singleLine, style, enabled, isError, label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText)
        },
    )
}

@Composable
private fun ValueField(
    boxed: Boolean,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    textStyle: TextStyle,
    label: (@Composable () -> Unit)?,
    placeholder: (@Composable () -> Unit)?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?,
    prefix: (@Composable () -> Unit)?,
    suffix: (@Composable () -> Unit)?,
    supportingText: (@Composable () -> Unit)?,
    isError: Boolean,
    visualTransformation: VisualTransformation,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    singleLine: Boolean,
    maxLines: Int,
    minLines: Int,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = typed(textStyle, enabled)
    BasicTextField(
        value, onValueChange, modifier, enabled, readOnly, style, keyboardOptions, keyboardActions, singleLine,
        if (singleLine) 1 else maxLines, minLines, visualTransformation, interactionSource = interaction, cursorBrush = SolidColor(tokens.tx),
        decorationBox = { inner ->
            Decoration(inner, value.text.isEmpty(), focused, boxed, singleLine, style, enabled, isError, label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText)
        },
    )
}

/** A field with no box of its own, for one already inside a group: the text alone, its name over it. */
@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = AppTheme.typography.body,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) = StringField(false, value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines)

/** A field in a box on the surface, its name above it. */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = AppTheme.typography.body,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) = StringField(true, value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines)

@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = AppTheme.typography.body,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) = ValueField(true, value, onValueChange, modifier, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation, keyboardOptions, keyboardActions, singleLine, maxLines, minLines)

/** A boxed field for a secret, its characters shown as dots unless [textObfuscationMode] says otherwise. */
@Composable
fun OutlinedSecureTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textObfuscationMode: TextObfuscationMode = TextObfuscationMode.RevealLastTyped,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = typed(AppTheme.typography.body, true)
    BasicSecureTextField(
        state,
        modifier,
        textStyle = style,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        textObfuscationMode = textObfuscationMode,
        interactionSource = interaction,
        cursorBrush = SolidColor(tokens.tx),
        decorator = TextFieldDecorator { inner ->
            Decoration(inner, state.text.isEmpty(), focused, true, true, style, true, false, null, placeholder, null, trailingIcon, null, null, null)
        },
    )
}

/** A search's field: one line, [leadingIcon] and [trailingIcon] round it, [onSearch] on the keyboard's search key. */
@Composable
fun SearchField(
    state: TextFieldState,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val style = typed(AppTheme.typography.bodyReading.copy(lineHeight = AppTheme.typography.body.lineHeight), true)
    BasicTextField(
        state,
        modifier,
        textStyle = style,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onSearch() },
        cursorBrush = SolidColor(tokens.tx),
        decorator = TextFieldDecorator { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) CompositionLocalProvider(LocalContentColor provides tokens.tx2) { leadingIcon() }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    if (state.text.isEmpty() && placeholder != null) {
                        CompositionLocalProvider(LocalContentColor provides tokens.tx3) { ProvideTextStyle(style) { placeholder() } }
                    }
                    inner()
                }
                if (trailingIcon != null) CompositionLocalProvider(LocalContentColor provides tokens.tx2) { trailingIcon() }
            }
        },
    )
}
