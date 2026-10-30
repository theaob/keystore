package dev.keystore.app.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.keystore.core.KeystoreBackup
import dev.keystore.core.PasswordGenerator
import dev.keystore.core.SigningSnippets
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DetailsScreen(
    details: KeystoreDetails,
    backup: BackupState,
    onCreateBackup: (details: KeystoreDetails, passphrase: String, includePasswords: Boolean) -> Unit,
    onPendingBackupHandled: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keystore = details.keystore
    val info = keystore.info
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    var showBackupDialog by rememberSaveable { mutableStateOf(false) }

    val saveKeystore = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { writeFile(context, it, keystore.bytes, "Keystore saved") } }
    val savePem = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri -> uri?.let { writeFile(context, it, info.pem.toByteArray(), "Certificate saved") } }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = backup.pending
        if (uri != null && pending != null) writeFile(context, uri, pending.bytes, "Backup saved")
        onPendingBackupHandled()
    }

    // As soon as an encrypted backup is ready, ask where to save it.
    LaunchedEffect(backup.pending) {
        backup.pending?.let { saveBackup.launch(it.fileName) }
    }

    if (showBackupDialog) {
        BackupDialog(
            passwordsKnown = details.storePassword != null && details.keyPassword != null,
            onDismiss = { showBackupDialog = false },
            onConfirm = { passphrase, includePasswords ->
                showBackupDialog = false
                onCreateBackup(details, passphrase, includePasswords)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Text(details.notice, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(onClick = { saveKeystore.launch(details.fileName) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Save ${details.fileName}")
        }
        BusyButton(
            text = "Create encrypted backup",
            busy = backup.creating,
            enabled = backup.pending == null,
            onClick = { showBackupDialog = true },
        )
        ErrorText(backup.error?.let { "Backup failed: $it" })
        OutlinedButton(
            onClick = { savePem.launch(KeystoreBackup.certificateFileName(details.fileName)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export upload certificate (.pem)")
        }

        Section("Keystore") {
            InfoRow("Format", keystore.format.displayName)
            InfoRow("Alias", keystore.alias)
            InfoRow("Store password", details.storePassword ?: "Not known", sensitive = details.storePassword != null)
            InfoRow("Key password", details.keyPassword ?: "Not known", sensitive = details.keyPassword != null)
        }

        Section("Certificate") {
            InfoRow("Owner", info.subject)
            InfoRow("Serial number", info.serialNumberHex)
            InfoRow("Valid from", dateFormat.format(info.notBefore))
            InfoRow("Valid until", dateFormat.format(info.notAfter))
            InfoRow("Key", "${info.publicKeyAlgorithm} · ${info.signatureAlgorithm}")
        }

        Section("Fingerprints") {
            Text(
                "Register these in Firebase, Google Sign-In / Maps API key restrictions, or Play Console.",
                style = MaterialTheme.typography.bodySmall,
            )
            InfoRow("SHA-1", info.sha1, monospace = true)
            InfoRow("SHA-256", info.sha256, monospace = true)
            InfoRow("MD5", info.md5, monospace = true)
        }

        Section("keystore.properties") {
            Text("Put this next to settings.gradle and keep it out of git.", style = MaterialTheme.typography.bodySmall)
            CodeBlock(
                label = "keystore.properties",
                code = SigningSnippets.keystoreProperties(
                    fileName = details.fileName,
                    alias = keystore.alias,
                    storePassword = details.storePassword ?: "<store password>",
                    keyPassword = details.keyPassword ?: "<key password>",
                ),
                sensitive = details.storePassword != null,
            )
        }

        Section("app/build.gradle.kts") {
            CodeBlock("Gradle signing config", SigningSnippets.gradleKts(keystore.format), sensitive = false)
            Text(
                "Then run ./gradlew bundleRelease to get a signed app-release.aab.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Manual signing") {
            CodeBlock("jarsigner command", SigningSnippets.jarsignerCommand(details.fileName, keystore.alias), false)
        }

        Section("CI secret") {
            Text(
                "Base64 of the keystore file, for a GitHub Actions or other CI secret. Decode it with " +
                    "`echo \"\$SECRET\" | base64 -d > ${details.fileName}`.",
                style = MaterialTheme.typography.bodySmall,
            )
            val base64 = remember(keystore) { SigningSnippets.base64(keystore.bytes) }
            OutlinedButton(
                onClick = { copyToClipboard(context, "Keystore (Base64)", base64, sensitive = true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Copy Base64 (${base64.length} chars)")
            }
        }
    }
}

@Composable
private fun BackupDialog(
    passwordsKnown: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, includePasswords: Boolean) -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var includePasswords by rememberSaveable { mutableStateOf(passwordsKnown) }

    val tooShort = passphrase.length < KeystoreBackup.MIN_PASSPHRASE_LENGTH
    val mismatch = confirmation.isNotEmpty() && confirmation != passphrase

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Backup, contentDescription = null) },
        title = { Text("Encrypted backup") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "The backup contains the keystore, its certificate and a README, encrypted with AES-256. " +
                        "Choose a passphrase you won't lose: without it the backup can't be opened.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(
                    label = "Backup passphrase",
                    value = passphrase,
                    error = null,
                    onValueChange = { passphrase = it },
                    onGenerate = { passphrase = String(PasswordGenerator.generate(32)) },
                    hint = "At least ${KeystoreBackup.MIN_PASSPHRASE_LENGTH} characters",
                )
                PasswordField(
                    label = "Repeat passphrase",
                    value = confirmation,
                    error = if (mismatch) "Passphrases don't match" else null,
                    onValueChange = { confirmation = it },
                    onGenerate = null,
                    hint = "",
                    imeAction = ImeAction.Done,
                )
                if (passwordsKnown) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includePasswords, onCheckedChange = { includePasswords = it })
                        Text("Include the keystore passwords", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Can also be decrypted without this app:\n${KeystoreBackup.OPENSSL_DECRYPT_COMMAND}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase, includePasswords) },
                enabled = !tooShort && confirmation == passphrase,
            ) { Text("Create backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false, sensitive: Boolean = false) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                )
            }
        }
        IconButton(onClick = { copyToClipboard(context, label, value, sensitive) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

@Composable
private fun CodeBlock(label: String, code: String, sensitive: Boolean) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
            IconButton(onClick = { copyToClipboard(context, label, code, sensitive) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}

private fun writeFile(context: Context, uri: Uri, bytes: ByteArray, message: String) {
    val result = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Could not open file")
    }
    val text = result.fold({ message }, { "Save failed: ${it.message}" })
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
