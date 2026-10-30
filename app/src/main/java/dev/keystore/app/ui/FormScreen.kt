package dev.keystore.app.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.keystore.core.KeyAlgorithm
import dev.keystore.core.KeystoreFormat
import dev.keystore.core.ValidationError.Field

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    state: UiState,
    onFormChange: ((FormState) -> FormState) -> Unit,
    onGenerateStorePassword: () -> Unit,
    onGenerateKeyPassword: () -> Unit,
    onGenerate: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val errors = state.errors

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Keystore")

        Text("Format", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KeystoreFormat.entries.forEachIndexed { index, format ->
                SegmentedButton(
                    selected = form.format == format,
                    onClick = { onFormChange { it.copy(format = format) } },
                    shape = SegmentedButtonDefaults.itemShape(index, KeystoreFormat.entries.size),
                ) { Text(format.displayName) }
            }
        }
        Text(
            if (form.format == KeystoreFormat.JKS) {
                "Classic Java KeyStore (.jks), the format Android Studio's \"Generate Signed Bundle\" wizard expects."
            } else {
                "PKCS#12 (.p12), keytool's default since JDK 9. The key uses the store password."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = form.alias,
            onValueChange = { value -> onFormChange { it.copy(alias = value) } },
            label = { Text("Key alias") },
            singleLine = true,
            isError = Field.ALIAS in errors,
            supportingText = errors[Field.ALIAS]?.let { message -> @Composable { Text(message) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        PasswordField(
            label = "Store password",
            value = form.storePassword,
            error = errors[Field.STORE_PASSWORD],
            onValueChange = { value -> onFormChange { it.copy(storePassword = value) } },
            onGenerate = onGenerateStorePassword,
        )

        if (form.format == KeystoreFormat.JKS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.keyPasswordSameAsStore,
                    onCheckedChange = { checked -> onFormChange { it.copy(keyPasswordSameAsStore = checked) } },
                )
                Text("Use store password for the key")
            }
            if (!form.keyPasswordSameAsStore) {
                PasswordField(
                    label = "Key password",
                    value = form.keyPassword,
                    error = errors[Field.KEY_PASSWORD],
                    onValueChange = { value -> onFormChange { it.copy(keyPassword = value) } },
                    onGenerate = onGenerateKeyPassword,
                )
            }
        }

        Text("Key algorithm", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyAlgorithm.entries.forEach { algorithm ->
                FilterChip(
                    selected = form.algorithm == algorithm,
                    onClick = { onFormChange { it.copy(algorithm = algorithm) } },
                    label = { Text(algorithm.displayName) },
                )
            }
        }

        OutlinedTextField(
            value = form.validityYears,
            onValueChange = { value ->
                onFormChange { it.copy(validityYears = value.filter(Char::isDigit).take(3)) }
            },
            label = { Text("Validity (years)") },
            singleLine = true,
            isError = Field.VALIDITY in errors,
            supportingText = (errors[Field.VALIDITY] ?: state.validityWarning)
                ?.let { message -> @Composable { Text(message) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Certificate")
        Text(
            "Stored in the certificate and visible to anyone who inspects your app. At least one field is required.",
            style = MaterialTheme.typography.bodySmall,
        )
        errors[Field.DISTINGUISHED_NAME]?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        DnField("First and last name (CN)", form.commonName) { v -> onFormChange { it.copy(commonName = v) } }
        DnField("Organizational unit (OU)", form.organizationalUnit) { v ->
            onFormChange { it.copy(organizationalUnit = v) }
        }
        DnField("Organization (O)", form.organization) { v -> onFormChange { it.copy(organization = v) } }
        DnField("City or locality (L)", form.locality) { v -> onFormChange { it.copy(locality = v) } }
        DnField("State or province (ST)", form.state) { v -> onFormChange { it.copy(state = v) } }
        OutlinedTextField(
            value = form.countryCode,
            onValueChange = { value -> onFormChange { it.copy(countryCode = value.take(2).uppercase()) } },
            label = { Text("Country code (C)") },
            placeholder = { Text("e.g. US") },
            singleLine = true,
            isError = Field.COUNTRY_CODE in errors,
            supportingText = errors[Field.COUNTRY_CODE]?.let { message -> @Composable { Text(message) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        state.generationError?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Generation failed: $it", modifier = Modifier.padding(16.dp))
            }
        }

        Button(
            onClick = onGenerate,
            enabled = !state.generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.generating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Generating…")
            } else {
                Text("Generate keystore")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DnField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun PasswordField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onGenerate: (() -> Unit)?,
    hint: String = "At least 6 characters. Tap the dice for a strong random one.",
    imeAction: ImeAction = ImeAction.Next,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: hint) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            Row {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                    )
                }
                if (onGenerate != null) {
                    IconButton(onClick = {
                        onGenerate()
                        visible = true
                    }) {
                        Icon(Icons.Filled.Casino, contentDescription = "Generate password")
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
