package cc.hyoban.moments.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.design.Icon
import cc.hyoban.moments.ui.design.ListItem
import cc.hyoban.moments.ui.design.Text
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记录项与公开: who sees what, by default. The timeline shows the Moments about tech, which the
 * site never does, only when asked; each book says whether its new bills are public, a public
 * one publishing its amount, category and note alone; the footprints are private, with the spans
 * made public counted here and made so on a trip's page. Every bill and span can still say
 * otherwise. Nothing here opens a page further down.
 */
@Composable
fun ItemsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings = container.settings
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val revision by container.footprints.revision.collectAsStateWithLifecycle()
    val shares by produceState(0, revision) {
        value = withContext(Dispatchers.IO) { container.footprints.shares().count { !it.removed } }
    }
    val scope = rememberCoroutineScope()
    SubPage(stringResource(R.string.items_title), onBack, Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                SegmentedGroup(
                    stringResource(R.string.section_timeline),
                    stringResource(R.string.settings_show_tech_note),
                    listOf { shapes ->
                        SwitchRow(settings.showTech, AppIcons.Code, stringResource(R.string.setting_show_tech), "show_tech", shapes) {
                            container.changeShowTech(!settings.showTech)
                        }
                    },
                )
                val books = ledger.books.filter { !it.archived }
                if (books.isNotEmpty()) {
                    SegmentedGroup(
                        stringResource(R.string.items_books),
                        stringResource(R.string.items_book_note),
                        books.map { book ->
                            { shapes ->
                                SwitchRow(
                                    book.visibility == "public",
                                    AppIcons.Receipt,
                                    book.name,
                                    "book_public_${book.id}",
                                    shapes,
                                    supporting = stringResource(R.string.items_book_public),
                                ) {
                                    scope.launch {
                                        container.ledger.saveBook(book.copy(visibility = if (book.visibility == "public") "private" else "public"))
                                    }
                                }
                            }
                        },
                    )
                }
                SegmentedGroup(
                    stringResource(R.string.items_tracks),
                    stringResource(R.string.items_tracks_note),
                    listOf { _ ->
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.footprints_title)) },
                            leadingContent = { Icon(AppIcons.Footprints, contentDescription = null) },
                            trailingContent = { Text(stringResource(R.string.items_track_shares, shares)) },
                            modifier = Modifier.testTag("items_tracks"),
                        )
                    },
                )
            }
        }
    }
}
