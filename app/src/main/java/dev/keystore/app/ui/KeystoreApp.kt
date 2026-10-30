package dev.keystore.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreApp(viewModel: KeystoreViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screen = state.screen

    BackHandler(enabled = screen != Screen.Home) { viewModel.goHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Home -> "Keystore Generator"
                            Screen.Generate -> "New keystore"
                            Screen.Import -> "Back up a keystore"
                            Screen.Restore -> "Restore a backup"
                            is Screen.Details -> screen.details.title
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Home) {
                        IconButton(onClick = viewModel::goHome) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (screen) {
            Screen.Home -> HomeScreen(onOpen = viewModel::open, contentPadding = padding)
            Screen.Generate -> FormScreen(
                state = state,
                onFormChange = viewModel::updateForm,
                onGenerateStorePassword = viewModel::generateStorePassword,
                onGenerateKeyPassword = viewModel::generateKeyPassword,
                onGenerate = viewModel::generate,
                contentPadding = padding,
            )
            Screen.Import -> ImportScreen(
                state = state.importing,
                onFileChosen = viewModel::importFileChosen,
                onChange = viewModel::updateImport,
                onVerify = viewModel::verifyImport,
                contentPadding = padding,
            )
            Screen.Restore -> RestoreScreen(
                state = state.restore,
                onFileChosen = viewModel::backupFileChosen,
                onChange = viewModel::updateRestore,
                onRestore = viewModel::restoreBackup,
                contentPadding = padding,
            )
            is Screen.Details -> DetailsScreen(
                details = screen.details,
                backup = state.backup,
                onCreateBackup = viewModel::createBackup,
                onPendingBackupHandled = viewModel::pendingBackupHandled,
                contentPadding = padding,
            )
        }
    }
}

/** Copies [text] to the clipboard, flagging secrets so Android 13+ hides them in the preview. */
internal fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            putBoolean(key, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    // Android 13+ shows its own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }
}
