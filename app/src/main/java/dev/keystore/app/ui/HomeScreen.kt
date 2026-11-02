package dev.keystore.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.keystore.core.KeystoreBackup
import dev.keystore.core.VaultEntrySummary
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.security.auth.x500.X500Principal

@Composable
fun HomeScreen(
    vault: VaultState,
    onOpen: (Screen) -> Unit,
    onOpenStored: (id: String) -> Unit,
    onDeleteStored: (id: String) -> Unit,
    contentPadding: PaddingValues,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    pendingDelete?.let { id ->
        val entry = vault.entries.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete ${entry?.fileName ?: "keystore"}?") },
            text = {
                Text(
                    "This removes it and its passwords from the app permanently. Make sure you have the " +
                        "keystore file or a .${KeystoreBackup.EXTENSION} backup somewhere else: without it you " +
                        "can't publish updates signed with this key.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteStored(id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

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
            description = "Check a .jks or .p12 file's passwords, then back it up or keep it in the app.",
            onClick = { onOpen(Screen.Import) },
        )
        HomeCard(
            icon = Icons.Filled.Restore,
            title = "Restore from a backup",
            description = "Open a .${KeystoreBackup.EXTENSION} file to get the keystore and its passwords back.",
            onClick = { onOpen(Screen.Restore) },
        )

        Text(
            "Saved in this app",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        ErrorText(vault.error)
        if (vault.entries.isEmpty()) {
            Text(
                "Keystores you create are kept here, encrypted and unlocked with your fingerprint or screen lock.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            vault.entries.forEach { entry ->
                SavedKeystoreCard(
                    entry = entry,
                    busy = vault.busyId == entry.id,
                    onOpen = { onOpenStored(entry.id) },
                    onDelete = { pendingDelete = entry.id },
                )
            }
            Text(
                "Stored only on this phone. Uninstalling the app or losing the phone deletes them, so keep " +
                    "backups elsewhere too.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedKeystoreCard(entry: VaultEntrySummary, busy: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    Card(
        onClick = onOpen,
        enabled = !busy,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.fileName, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(entry.alias, commonName(entry.subject), entry.format.displayName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Valid until ${dateFormat.format(entry.notAfter)}" +
                        if (entry.hasPasswords) "" else " · passwords not stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${entry.fileName}")
            }
        }
    }
}

/** The CN of an RFC 2253 name, if there is one. */
private fun commonName(subject: String): String? = runCatching {
    X500Principal(subject).getName(X500Principal.RFC1779)
        .split(Regex(",\\s*"))
        .firstOrNull { it.startsWith("CN=") }
        ?.removePrefix("CN=")
        ?.trim('"')
}.getOrNull()
