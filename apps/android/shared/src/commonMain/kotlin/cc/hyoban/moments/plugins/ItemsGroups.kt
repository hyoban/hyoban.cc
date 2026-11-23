package cc.hyoban.moments.plugins

import cc.hyoban.moments.res.*
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.graphics.vector.ImageVector
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.data.spec.Module
import cc.hyoban.moments.plugins.Plugins
import cc.hyoban.moments.ui.components.LocalTopClearance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.hyoban.moments.ui.design.DateDialog
import cc.hyoban.moments.ui.format.formatDate
import java.time.LocalDate
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.AppContainer
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
import cc.hyoban.moments.ui.settings.SegmentedGroup
import cc.hyoban.moments.ui.settings.SettingsGroupSpec
import cc.hyoban.moments.ui.settings.SettingsGroupView
import cc.hyoban.moments.ui.settings.SettingsRow

/**
 * The ledger's books on 记录项与公开: whether each one's bills are public, from the day picked
 * as it is switched on (or all of them, for a book made public before there was a day).
 */
@Composable
internal fun BooksGroup(container: AppContainer) {
    val ledger by container.ledger.ledger.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
        val books = ledger.books.filter { !it.archived }
        // The book whose first public day is being picked, as it is switched on or after.
        var picking by rememberSaveable { mutableStateOf<String?>(null) }
        if (books.isNotEmpty() && container.ledger.writable) {
            SettingsGroupView(
                SettingsGroupSpec(
                    stringResource(Res.string.items_books),
                    stringResource(Res.string.items_book_note),
                    books.flatMap { book ->
                        val public = book.visibility == "public"
                        listOfNotNull(
                            SettingsRow.Switch(
                                AppIcons.Receipt,
                                book.name,
                                "book_public_${book.id}",
                                public,
                                supporting = stringResource(Res.string.items_book_public),
                            ) {
                                if (public) {
                                    scope.launch { container.ledger.saveBook(book.copy(visibility = "private", publicFrom = null)) }
                                } else {
                                    picking = book.id
                                }
                            },
                            if (public) {
                                SettingsRow.Value(
                                    AppIcons.Calendar,
                                    stringResource(Res.string.items_book_public_from),
                                    "book_public_from_${book.id}",
                                    book.publicFrom?.let { formatDate(it) } ?: stringResource(Res.string.items_book_public_all),
                                    maxLines = Int.MAX_VALUE,
                                ) { picking = book.id }
                            } else {
                                null
                            },
                        )
                    },
                ),
            )
        }
        books.firstOrNull { it.id == picking }?.let { book ->
            DateDialog(
                book.publicFrom?.let(LocalDate::parse) ?: LocalDate.now(),
                stringResource(Res.string.items_book_public_from),
                onDismiss = { picking = null },
            ) { day ->
                picking = null
                scope.launch { container.ledger.saveBook(book.copy(visibility = "public", publicFrom = day.toString())) }
            }
        }
}

/** The footprints on 记录项与公开: private, and never leaving the phone. */
@Composable
internal fun TracksGroup() {
        SegmentedGroup(
            stringResource(Res.string.items_tracks),
            stringResource(Res.string.items_tracks_note),
            listOf { _ ->
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.footprints_title)) },
                    leadingContent = { Icon(AppIcons.Footprints, contentDescription = null) },
                    trailingContent = { Text(stringResource(Res.string.visibility_private)) },
                    modifier = Modifier.testTag("items_tracks"),
                )
            },
        )
}
