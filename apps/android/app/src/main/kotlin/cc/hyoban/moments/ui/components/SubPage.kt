package cc.hyoban.moments.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.OnTabReselect

/**
 * A pushed page as Material 3 lays one out: a medium flexible top app bar over [content],
 * its title large under the back button while the page is at its top, folding into the
 * bar as the content scrolls up, the bar taking the scrolled-under container colour. A
 * page that is a full-screen dialog, done by its action or left without saving, [close]s
 * with an X rather than going back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    close: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) = BarPage(title, subtitle, onBack, large = false, modifier, actions, content, close = close)

/**
 * A tab's page as Material 3 lays one out: a large flexible top app bar with no back
 * button over [content], folding into the bar the same way. Its [tab] tapped again, the
 * bar opens out again as the page goes back to its top.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TabPage(
    title: String,
    tab: Any,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) = BarPage(title, subtitle, onBack = null, large = true, modifier, actions, content, tab)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BarPage(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    large: Boolean,
    modifier: Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
    tab: Any? = null,
    close: Boolean = false,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    OnTabReselect(tab) {
        animate(scroll.state.heightOffset, 0f) { value, _ -> scroll.state.heightOffset = value }
    }
    val titleText: @Composable () -> Unit = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    val subtitleText: (@Composable () -> Unit)? = subtitle?.let { { Text(it, maxLines = 1) } }
    val back: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                if (close) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                } else {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = AppColors.page,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    Column(modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        if (large) {
            LargeFlexibleTopAppBar(titleText, subtitle = subtitleText, navigationIcon = back, actions = actions, colors = colors, scrollBehavior = scroll)
        } else {
            MediumFlexibleTopAppBar(titleText, subtitle = subtitleText, navigationIcon = back, actions = actions, colors = colors, scrollBehavior = scroll)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
