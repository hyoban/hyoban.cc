package cc.hyoban.moments.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.hyoban.moments.PhoneContainer

/** 同步与来源's backup and NAS sections on the phone, each with the dialogs it opens. */
@Composable
fun PhoneStorageSettings(container: PhoneContainer) {
    var editingBackup by rememberSaveable { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var editingNas by rememberSaveable { mutableStateOf(false) }
    BackupSection(container.backup, onEdit = { editingBackup = true }, onRestore = { restoring = true })
    NasSection(container.nas) { editingNas = true }

    if (editingBackup) {
        val backup = container.backup
        WebDavDialog(
            backup.url.value,
            backup.user.value,
            passwordSet = backup.password.isSet,
            configured = backup.configured,
            onDismiss = { editingBackup = false },
            onForget = {
                backup.forget()
                editingBackup = false
            },
        ) { url, user, password ->
            backup.change(url, user, password)
            editingBackup = false
        }
    }

    if (editingNas) {
        val nas = container.nas
        NasDialog(
            nas,
            onDismiss = { editingNas = false },
            onForget = {
                nas.forget()
                editingNas = false
            },
        ) { link, user, password ->
            nas.change(link, user, password)
            editingNas = false
        }
    }

    if (restoring) {
        RestoreDialog(container.backup) { restoring = false }
    }
}
