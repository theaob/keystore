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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreApp(viewModel: KeystoreViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.result

    BackHandler(enabled = result != null) { viewModel.startOver() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (result == null) "Keystore Generator" else "Keystore ready") },
                navigationIcon = {
                    if (result != null) {
                        IconButton(onClick = viewModel::startOver) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Start over")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (result == null) {
            FormScreen(
                state = state,
                onFormChange = viewModel::updateForm,
                onGenerateStorePassword = viewModel::generateStorePassword,
                onGenerateKeyPassword = viewModel::generateKeyPassword,
                onGenerate = viewModel::generate,
                modifier = Modifier,
                contentPadding = padding,
            )
        } else {
            ResultScreen(result = result, contentPadding = padding)
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
