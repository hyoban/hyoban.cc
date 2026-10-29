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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
import cc.hyoban.moments.R
import cc.hyoban.moments.footprints.ActionRow
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记录项与公开: who sees what, by default. Each book says whether its new bills are public, a
 * public one publishing its amount, category and note alone; the footprints are private, with
 * the spans made public counted here. Every bill and span can still say otherwise.
 */
@Composable
fun ItemsScreen(container: AppContainer, onBack: () -> Unit, onFootprints: () -> Unit) {
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
                    listOf { shapes ->
                        ActionRow(
                            AppIcons.Footprints,
                            stringResource(R.string.footprints_title),
                            shapes,
                            supporting = stringResource(R.string.items_track_shares, shares),
                            tag = "items_tracks",
                            onClick = onFootprints,
                        )
                    },
                )
            }
        }
    }
}
