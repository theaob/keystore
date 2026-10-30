package dev.keystore.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.keystore.core.KeystoreBackup

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeCard(
            icon = Icons.Filled.Add,
            title = "Create a new keystore",
            description = "Generate a JKS or PKCS12 upload key for signing your app bundle.",
            onClick = { onOpen(Screen.Generate) },
        )
        HomeCard(
            icon = Icons.Filled.Backup,
            title = "Back up an existing keystore",
            description = "Check a .jks or .p12 file's passwords, then save an encrypted backup with them.",
            onClick = { onOpen(Screen.Import) },
        )
        HomeCard(
            icon = Icons.Filled.Restore,
            title = "Restore from a backup",
            description = "Open a .${KeystoreBackup.EXTENSION} file to get the keystore and its passwords back.",
            onClick = { onOpen(Screen.Restore) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeCard(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ImportScreen(
    state: ImportState,
    onFileChosen: (Uri) -> Unit,
    onChange: ((ImportState) -> ImportState) -> Unit,
    onVerify: () -> Unit,
    contentPadding: PaddingValues,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFileChosen)
    }

    FlowColumn(contentPadding) {
        Text(
            "Choose the keystore you sign with and enter its passwords. The app checks they unlock the key, " +
                "so the backup is guaranteed to be usable.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FilePickerButton(state.fileName, "Choose keystore file") { picker.launch(arrayOf("*/*")) }

        if (state.bytes != null) {
            PasswordField(
                label = "Store password",
                value = state.storePassword,
                error = null,
                onValueChange = { value -> onChange { it.copy(storePassword = value) } },
                onGenerate = null,
                hint = "The password of the keystore file",
                imeAction = if (state.separateKeyPassword) ImeAction.Next else ImeAction.Done,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.separateKeyPassword,
                    onCheckedChange = { checked -> onChange { it.copy(separateKeyPassword = checked) } },
                )
                Text("The key has a different password")
            }
            if (state.separateKeyPassword) {
                PasswordField(
                    label = "Key password",
                    value = state.keyPassword,
                    error = null,
                    onValueChange = { value -> onChange { it.copy(keyPassword = value) } },
                    onGenerate = null,
                    hint = "The password of the key inside the keystore",
                    imeAction = ImeAction.Done,
                )
            }
        }

        ErrorText(state.error)
        BusyButton(
            text = "Verify keystore",
            busy = state.busy,
            enabled = state.bytes != null && state.storePassword.isNotEmpty(),
            onClick = onVerify,
        )
    }
}

@Composable
fun RestoreScreen(
    state: RestoreState,
    onFileChosen: (Uri) -> Unit,
    onChange: ((RestoreState) -> RestoreState) -> Unit,
    onRestore: () -> Unit,
    contentPadding: PaddingValues,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFileChosen)
    }

    FlowColumn(contentPadding) {
        Text(
            "Open a .${KeystoreBackup.EXTENSION} backup created by this app and enter the passphrase you chose " +
                "when you made it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FilePickerButton(state.fileName, "Choose backup file") { picker.launch(arrayOf("*/*")) }

        if (state.bytes != null) {
            PasswordField(
                label = "Backup passphrase",
                value = state.passphrase,
                error = null,
                onValueChange = { value -> onChange { it.copy(passphrase = value) } },
                onGenerate = null,
                hint = "Not the keystore password: the one used to encrypt the backup",
                imeAction = ImeAction.Done,
            )
        }

        ErrorText(state.error)
        BusyButton(
            text = "Restore",
            busy = state.busy,
            enabled = state.bytes != null && state.passphrase.isNotEmpty(),
            onClick = onRestore,
        )
    }
}

@Composable
private fun FlowColumn(contentPadding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun FilePickerButton(fileName: String?, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(fileName ?: label)
    }
}

@Composable
internal fun ErrorText(error: String?) {
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
        }
        Text(text)
    }
}
