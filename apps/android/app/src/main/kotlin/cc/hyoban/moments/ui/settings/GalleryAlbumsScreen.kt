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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hyoban.moments.res.*
import cc.hyoban.moments.ui.LocalSettings
import cc.hyoban.moments.ui.components.AppColors
import cc.hyoban.moments.ui.components.LocalTopClearance
import cc.hyoban.moments.ui.components.PageWidth
import cc.hyoban.moments.ui.components.SubPage
import cc.hyoban.moments.ui.design.AppIcons
import cc.hyoban.moments.ui.picker.GalleryAccess
import cc.hyoban.moments.ui.picker.LocalAlbum
import cc.hyoban.moments.ui.picker.LocalMedia
import cc.hyoban.moments.ui.theme.Space
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 相簿: every album of the gallery, the largest first, each switched on to be shown. One turned
 * off leaves 相册, 选照片 and 地图's photos, its title menu too; the app's own (Here, Here Share)
 * start off. Kept in the settings, on the phone alone.
 */
@Composable
fun GalleryAlbumsScreen(onBack: () -> Unit) {
    val settings = LocalSettings.current
    val albums = rememberGalleryAlbums()
    SubPage(stringResource(Res.string.albums_title), onBack = onBack, modifier = Modifier.background(AppColors.page)) {
        PageWidth(maxColumns = 1) { columns ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = LocalTopClearance.current)
                    .navigationBarsPadding()
                    .padding(horizontal = columns.margin)
                    .padding(bottom = Space.xl),
            ) {
                val note = stringResource(if (albums.isEmpty()) Res.string.albums_none else Res.string.albums_note)
                // With no album, only what says so.
                SettingsGroupView(
                    SettingsGroupSpec(
                        null,
                        note,
                        albums.map { album ->
                            val id = album.id.toString()
                            val hidden = settings.isAlbumHidden(id, album.own)
                            SettingsRow.Switch(
                                AppIcons.Folder, album.name, "album_$id", !hidden,
                                supporting = pluralStringResource(Res.plurals.albums_count, album.count, album.count),
                            ) { settings.changeAlbumHidden(id, album.own, !hidden) }
                        },
                    ),
                )
            }
        }
    }
}

/** The gallery's albums, the largest first, hidden or not; none while the gallery may not be read. */
@Composable
fun rememberGalleryAlbums(): List<LocalAlbum> {
    val context = LocalContext.current
    val items by remember {
        if (LocalMedia.access(context) == GalleryAccess.NONE) flowOf(emptyList()) else LocalMedia.items(context)
    }.collectAsStateWithLifecycle(emptyList())
    return remember(items) { LocalMedia.albums(items) }
}

/** What 相簿 says on 我: how many albums are hidden, else that all are shown. */
@Composable
fun galleryAlbumsSummary(): String {
    val settings = LocalSettings.current
    val hidden = rememberGalleryAlbums().count { settings.isAlbumHidden(it.id.toString(), it.own) }
    return if (hidden == 0) stringResource(Res.string.albums_all_shown) else pluralStringResource(Res.plurals.albums_hidden, hidden, hidden)
}
