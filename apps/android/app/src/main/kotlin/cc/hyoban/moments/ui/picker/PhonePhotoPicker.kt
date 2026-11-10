package cc.hyoban.moments.ui.picker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cc.hyoban.moments.PhoneContainer

/**
 * 选照片 (39) over the page that asked: the gallery and the paired camera in one, and, when the
 * gallery may not be read, the system's documents picker, which keeps where a photo or video was
 * taken. It stays here while the documents picker is open, so its answer has somewhere to come back to.
 */
@Composable
fun PhonePhotoPicker(container: PhoneContainer, limit: Int, onDismiss: () -> Unit, onPicked: (List<Uri>) -> Unit) {
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked -> onPicked(picked) }
    var browsing by remember { mutableStateOf(true) }
    if (browsing) androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        cc.hyoban.moments.ui.components.DialogPane {
            PhotoPickerScreen(
                container,
                limit = limit,
                onClose = onDismiss,
                onFallback = {
                    browsing = false
                    documents.launch(arrayOf("image/*", "video/*"))
                },
            ) { added -> onPicked(added) }
        }
    }
}
