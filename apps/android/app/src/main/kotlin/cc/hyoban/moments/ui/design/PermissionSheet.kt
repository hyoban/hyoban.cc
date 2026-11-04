package cc.hyoban.moments.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.hyoban.moments.ui.theme.Corners
import cc.hyoban.moments.ui.theme.Space
import cc.hyoban.moments.ui.theme.tokens

/** One line of what a permission costs or where what it reads goes, with its icon. */
data class PermissionFact(val icon: ImageVector, val text: String)

/**
 * The sheet before the system's permission dialog or its settings (PermissionSheet): what the
 * permission is for and what stays on the phone, then [primary] hands over to the system. One
 * that needs the system's settings names the [path] there and marks its button [external].
 * [secondary] says what is had without it, and goes as tapping behind does.
 */
@Composable
fun PermissionSheet(
    icon: ImageVector,
    title: String,
    body: String,
    primary: String,
    secondary: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    facts: List<PermissionFact> = emptyList(),
    path: String? = null,
    external: Boolean = false,
    tag: String = "permission_sheet",
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(tag)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
        ) {
            Column(Modifier.padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Box(Modifier.size(48.dp).background(tokens.fill, Corners.full), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tokens.tx)
                }
                Text(title, Modifier.semantics { heading() }, style = AppTheme.typography.title)
                Text(body, style = AppTheme.typography.body, color = tokens.tx2)
            }
            if (facts.isNotEmpty()) Column(Modifier.padding(top = Space.s)) {
                facts.forEach { fact ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                        Icon(fact.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tokens.tx2)
                        Text(fact.text, style = AppTheme.typography.body)
                    }
                }
            }
            if (path != null) Text(path, Modifier.padding(top = Space.m), style = AppTheme.typography.caption, color = tokens.tx2)
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().testTag("${tag}_primary")) {
                    Text(primary)
                    if (external) Icon(AppIcons.External, contentDescription = null, modifier = Modifier.padding(start = Space.s).size(18.dp))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("${tag}_secondary")) { Text(secondary) }
            }
        }
    }
}
