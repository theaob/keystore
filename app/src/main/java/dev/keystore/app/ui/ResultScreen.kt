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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.keystore.core.SigningSnippets
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ResultScreen(result: GenerationResult, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keystore = result.keystore
    val info = keystore.info
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    val pemFileName = result.fileName.substringBeforeLast('.') + "_certificate.pem"

    val saveKeystore = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { writeFile(context, it, keystore.bytes, "Keystore saved") } }
    val savePem = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri -> uri?.let { writeFile(context, it, info.pem.toByteArray(), "Certificate saved") } }

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
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text(
                    "The keystore only exists in memory until you save it. Back up the file and both " +
                        "passwords somewhere safe: without them you can't publish updates and must ask " +
                        "Google Play support to reset your upload key.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Button(onClick = { saveKeystore.launch(result.fileName) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Text("  Save ${result.fileName}")
        }
        OutlinedButton(onClick = { savePem.launch(pemFileName) }, modifier = Modifier.fillMaxWidth()) {
            Text("Export upload certificate (.pem)")
        }

        Section("Keystore") {
            InfoRow("Format", keystore.format.displayName)
            InfoRow("Alias", keystore.alias)
            InfoRow("Store password", result.storePassword, sensitive = true)
            InfoRow("Key password", result.keyPassword, sensitive = true)
        }

        Section("Certificate") {
            InfoRow("Owner", info.subject)
            InfoRow("Serial number", info.serialNumberHex)
            InfoRow("Valid from", dateFormat.format(info.notBefore))
            InfoRow("Valid until", dateFormat.format(info.notAfter))
            InfoRow("Key", "${info.publicKeyAlgorithm} · ${keystore.certificate.sigAlgName}")
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
                    fileName = result.fileName,
                    alias = keystore.alias,
                    storePassword = result.storePassword,
                    keyPassword = result.keyPassword,
                ),
                sensitive = true,
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
            CodeBlock("jarsigner command", SigningSnippets.jarsignerCommand(result.fileName, keystore.alias), false)
        }

        Section("CI secret") {
            Text(
                "Base64 of the keystore file, for a GitHub Actions or other CI secret. Decode it with " +
                    "`echo \"\$SECRET\" | base64 -d > ${result.fileName}`.",
                style = MaterialTheme.typography.bodySmall,
            )
            val base64 = remember(keystore) { SigningSnippets.base64(keystore.bytes) }
            OutlinedButton(
                onClick = { copyToClipboard(context, "Keystore (Base64)", base64, sensitive = true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text("  Copy Base64 (${base64.length} chars)")
            }
        }
    }
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
