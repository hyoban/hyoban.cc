package cc.hyoban.moments.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.AppLanguage
import cc.hyoban.moments.R

/**
 * The app's language, the phone's or one of its own, as rows in a card with the chosen
 * one ticked. Each language is named in itself, so it can be found whatever the app speaks.
 */
@Composable
fun LanguageSettings() {
    val activity = LocalActivity.current ?: return
    // Choosing brings the page back in the new language, so it is read once per page.
    val current = remember(activity) { AppLanguage.current(activity) }

    // Each language its own segment, and the system's own page for the app's language, which
    // ColorOS has but doesn't link to, one more.
    val system = systemLanguageSettings(activity)
    val segments = AppLanguage.entries.map<AppLanguage, @Composable () -> Unit> { language ->
        { LanguageRow(activity, language, selected = language == current) }
    } + listOfNotNull<@Composable () -> Unit>(
        system?.let { intent ->
            {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_system_language)) },
                    leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                    colors = rowColors(),
                    modifier = Modifier.clickable { activity.startActivity(intent) }.testTag("language_system_settings"),
                )
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

@Composable
private fun LanguageRow(activity: Activity, language: AppLanguage, selected: Boolean) {
    ListItem(
        headlineContent = {
            Text(
                when (language) {
                    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                    AppLanguage.CHINESE -> "简体中文"
                    AppLanguage.ENGLISH -> "English"
                },
            )
        },
        leadingContent = if (language == AppLanguage.SYSTEM) {
            { Icon(Icons.Rounded.Translate, contentDescription = null) }
        } else {
            // Room for the first row's icon, so the three names line up.
            { Spacer(Modifier.size(24.dp)) }
        },
        trailingContent = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        colors = rowColors(),
        modifier = Modifier
            .selectable(selected, role = Role.RadioButton) { if (!selected) AppLanguage.change(activity, language) }
            .testTag("language_${language.name.lowercase()}"),
    )
}
