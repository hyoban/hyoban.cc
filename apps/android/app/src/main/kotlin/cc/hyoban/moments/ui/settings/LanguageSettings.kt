package cc.hyoban.moments.ui.settings

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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

    Group(stringResource(R.string.section_language), stringResource(R.string.settings_language_note)) {
        Column(Modifier.selectableGroup()) {
            AppLanguage.entries.forEachIndexed { index, language ->
                if (index > 0) GroupDivider(56.dp)
                LanguageRow(activity, language, selected = language == current)
            }
        }
    }
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
