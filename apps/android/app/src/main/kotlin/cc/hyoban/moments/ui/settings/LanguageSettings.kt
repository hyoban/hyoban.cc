package cc.hyoban.moments.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cc.hyoban.moments.AppLanguage
import cc.hyoban.moments.R

/**
 * The app's language, the phone's or one of its own, as rows in a card with the chosen
 * one ticked. Each language is named in itself, so it can be found whatever the app speaks.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanguageSettings() {
    val activity = LocalActivity.current ?: return
    // Choosing brings the page back in the new language, so it is read once per page.
    val current = remember(activity) { AppLanguage.current(activity) }

    // Each language its own segment, and the system's own page for the app's language, which
    // ColorOS has but doesn't link to, one more.
    val system = systemLanguageSettings(activity)
    val segments = AppLanguage.entries.map<AppLanguage, Segment> { language ->
        { shapes -> LanguageRow(activity, language, selected = language == current, shapes) }
    } + listOfNotNull<Segment>(
        system?.let { intent ->
            { shapes ->
                SegmentedListItem(
                    onClick = { activity.startActivity(intent) },
                    shapes = shapes,
                    colors = segmentColors(),
                    leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                    modifier = Modifier.testTag("language_system_settings"),
                ) { Text(stringResource(R.string.action_system_language)) }
            }
        },
    )
    SegmentedGroup(stringResource(R.string.section_language), stringResource(R.string.settings_language_note), segments, Modifier.selectableGroup())
}

/** The system's page for this app's language, on Android 13 and later where there is one to open. */
private fun systemLanguageSettings(activity: Activity): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", activity.packageName, null))
    return intent.takeIf { it.resolveActivity(activity.packageManager) != null }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LanguageRow(activity: Activity, language: AppLanguage, selected: Boolean, shapes: ListItemShapes) {
    // One of them chosen, as Material 3 has a list choose one: a radio button at each row's
    // start, the whole row the target, the chosen one in the selected colours and shape.
    SegmentedListItem(
        selected = selected,
        onClick = { if (!selected) AppLanguage.change(activity, language) },
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.testTag("language_${language.name.lowercase()}"),
    ) {
        Text(
            when (language) {
                AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                AppLanguage.CHINESE -> "简体中文"
                AppLanguage.ENGLISH -> "English"
            },
            // The chosen one emphasized, as Material 3 sets a selected list item.
            style = if (selected) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
        )
    }
}
