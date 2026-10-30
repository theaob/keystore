package dev.keystore.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.keystore.core.BackupException
import dev.keystore.core.DistinguishedName
import dev.keystore.core.KeyAlgorithm
import dev.keystore.core.KeystoreBackup
import dev.keystore.core.KeystoreFile
import dev.keystore.core.KeystoreFormat
import dev.keystore.core.KeystoreGenerator
import dev.keystore.core.KeystoreLoadException
import dev.keystore.core.KeystoreReader
import dev.keystore.core.KeystoreRequest
import dev.keystore.core.KeystoreValidator
import dev.keystore.core.PasswordGenerator
import dev.keystore.core.ValidationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Year

data class FormState(
    val alias: String = "upload",
    val storePassword: String = "",
    val keyPassword: String = "",
    val keyPasswordSameAsStore: Boolean = true,
    val format: KeystoreFormat = KeystoreFormat.JKS,
    val algorithm: KeyAlgorithm = KeyAlgorithm.RSA_2048,
    val validityYears: String = KeystoreValidator.RECOMMENDED_VALIDITY_YEARS.toString(),
    val commonName: String = "",
    val organizationalUnit: String = "",
    val organization: String = "",
    val locality: String = "",
    val state: String = "",
    val countryCode: String = "",
) {
    val effectiveKeyPassword: String
        get() = if (keyPasswordSameAsStore || format == KeystoreFormat.PKCS12) storePassword else keyPassword

    val fileName: String
        get() = safeFileName(alias.trim().ifEmpty { "upload" }) + ".${format.extension}"
}

/** A keystore shown on the details screen, whether generated, imported or restored. */
data class KeystoreDetails(
    val keystore: KeystoreFile,
    val fileName: String,
    /** `null` when unknown, e.g. restored from a backup made without passwords. */
    val storePassword: String?,
    val keyPassword: String?,
    val title: String,
    val notice: String,
)

data class ImportState(
    val fileName: String? = null,
    val bytes: ByteArray? = null,
    val storePassword: String = "",
    val keyPassword: String = "",
    val separateKeyPassword: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

data class RestoreState(
    val fileName: String? = null,
    val bytes: ByteArray? = null,
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

/** An encrypted backup waiting for the user to pick where to save it. */
class PendingBackup(val fileName: String, val bytes: ByteArray)

data class BackupState(
    val creating: Boolean = false,
    val error: String? = null,
    val pending: PendingBackup? = null,
)

sealed interface Screen {
    data object Home : Screen
    data object Generate : Screen
    data object Import : Screen
    data object Restore : Screen
    data class Details(val details: KeystoreDetails) : Screen
}

data class UiState(
    val screen: Screen = Screen.Home,
    val form: FormState = FormState(),
    val errors: Map<ValidationError.Field, String> = emptyMap(),
    val generating: Boolean = false,
    val generationError: String? = null,
    val importing: ImportState = ImportState(),
    val restore: RestoreState = RestoreState(),
    val backup: BackupState = BackupState(),
) {
    val validityWarning: String?
        get() {
            val years = form.validityYears.toIntOrNull() ?: return null
            return if (KeystoreValidator.expiresTooEarlyForPlay(years, Year.now().value)) {
                "Google Play requires the certificate to be valid until at least 22 October " +
                    "${KeystoreValidator.PLAY_MIN_EXPIRY_YEAR}. " +
                    "Use ${KeystoreValidator.RECOMMENDED_VALIDITY_YEARS}+ years."
            } else {
                null
            }
        }
}

class KeystoreViewModel(application: Application) : AndroidViewModel(application) {
    private val generator = KeystoreGenerator()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun open(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    /** Returns to the home screen and forgets every password and loaded file. */
    fun goHome() {
        _state.update {
            UiState(form = it.form.copy(storePassword = "", keyPassword = ""))
        }
    }

    // region Generate

    fun updateForm(transform: (FormState) -> FormState) {
        _state.update { it.copy(form = transform(it.form), errors = emptyMap(), generationError = null) }
    }

    fun generateStorePassword() {
        val password = String(PasswordGenerator.generate())
        updateForm { it.copy(storePassword = password) }
    }

    fun generateKeyPassword() {
        val password = String(PasswordGenerator.generate())
        updateForm { it.copy(keyPassword = password) }
    }

    fun generate() {
        val form = _state.value.form
        val request = form.toRequest()
        val errors = KeystoreValidator.validate(request)
        if (errors.isNotEmpty()) {
            request.wipe()
            _state.update { state -> state.copy(errors = errors.associate { it.field to it.message }) }
            return
        }

        _state.update { it.copy(generating = true, generationError = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                // RSA 4096 key generation can take several seconds on older devices.
                withContext(Dispatchers.Default) { generator.generate(request) }
            }
            request.wipe()
            _state.update { state ->
                outcome.fold(
                    onSuccess = { keystore ->
                        state.copy(
                            generating = false,
                            screen = Screen.Details(
                                KeystoreDetails(
                                    keystore = keystore,
                                    fileName = form.fileName,
                                    storePassword = form.storePassword,
                                    keyPassword = form.effectiveKeyPassword,
                                    title = "Keystore ready",
                                    notice = "The keystore only exists in memory until you save it. Save the " +
                                        "file and create an encrypted backup: without the keystore and its " +
                                        "passwords you can't publish updates, and must ask Google Play " +
                                        "support to reset your upload key.",
                                ),
                            ),
                        )
                    },
                    onFailure = { error ->
                        state.copy(generating = false, generationError = error.message ?: error.toString())
                    },
                )
            }
        }
    }

    // endregion

    // region Back up an existing keystore

    fun importFileChosen(uri: Uri) {
        _state.update { it.copy(importing = it.importing.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching { readUri(uri, MAX_KEYSTORE_SIZE) }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { (name, bytes) ->
                        val error = if (KeystoreReader.detectFormat(bytes) == null) {
                            "This file is not a JKS or PKCS12 keystore"
                        } else {
                            null
                        }
                        state.copy(
                            importing = state.importing.copy(
                                fileName = name,
                                bytes = bytes.takeIf { error == null },
                                busy = false,
                                error = error,
                            ),
                        )
                    },
                    onFailure = { state.copy(importing = state.importing.copy(busy = false, error = it.message)) },
                )
            }
        }
    }

    fun updateImport(transform: (ImportState) -> ImportState) {
        _state.update { it.copy(importing = transform(it.importing).copy(error = null)) }
    }

    fun verifyImport() {
        val current = _state.value.importing
        val bytes = current.bytes ?: return
        val keyPassword = if (current.separateKeyPassword) current.keyPassword else current.storePassword
        _state.update { it.copy(importing = current.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    KeystoreReader.load(bytes, current.storePassword.toCharArray(), keyPassword.toCharArray())
                }
            }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { keystore ->
                        state.copy(
                            importing = ImportState(),
                            screen = Screen.Details(
                                KeystoreDetails(
                                    keystore = keystore,
                                    fileName = safeFileName(current.fileName ?: "upload.${keystore.format.extension}"),
                                    storePassword = current.storePassword,
                                    keyPassword = keyPassword,
                                    title = "Keystore verified",
                                    notice = "The passwords unlock this keystore. Create an encrypted backup " +
                                        "and keep copies in more than one place (e.g. cloud storage and a USB drive).",
                                ),
                            ),
                        )
                    },
                    onFailure = { error ->
                        val message = (error as? KeystoreLoadException)?.reason?.message ?: error.message
                        state.copy(importing = state.importing.copy(busy = false, error = message))
                    },
                )
            }
        }
    }

    // endregion

    // region Restore

    fun backupFileChosen(uri: Uri) {
        _state.update { it.copy(restore = it.restore.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching { readUri(uri, MAX_BACKUP_SIZE) }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { (name, bytes) ->
                        state.copy(restore = state.restore.copy(fileName = name, bytes = bytes, busy = false))
                    },
                    onFailure = { state.copy(restore = state.restore.copy(busy = false, error = it.message)) },
                )
            }
        }
    }

    fun updateRestore(transform: (RestoreState) -> RestoreState) {
        _state.update { it.copy(restore = transform(it.restore).copy(error = null)) }
    }

    fun restoreBackup() {
        val restore = _state.value.restore
        val bytes = restore.bytes ?: return
        _state.update { it.copy(restore = restore.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching {
                // PBKDF2 with 600k iterations takes around a second.
                withContext(Dispatchers.Default) { KeystoreBackup.open(bytes, restore.passphrase.toCharArray()) }
            }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { contents ->
                        state.copy(
                            restore = RestoreState(),
                            screen = Screen.Details(
                                KeystoreDetails(
                                    keystore = contents.keystore,
                                    fileName = safeFileName(contents.fileName),
                                    storePassword = contents.storePassword,
                                    keyPassword = contents.keyPassword,
                                    title = "Backup restored",
                                    notice = if (contents.verified) {
                                        "The backup is intact and its passwords unlock the keystore. Save the " +
                                            "keystore file to use it for signing."
                                    } else {
                                        "This backup doesn't include the passwords, so they couldn't be " +
                                            "checked. You'll need them from your password manager to sign."
                                    },
                                ),
                            ),
                        )
                    },
                    onFailure = { error ->
                        val message = (error as? BackupException)?.reason?.message ?: error.message
                        state.copy(restore = state.restore.copy(busy = false, error = message))
                    },
                )
            }
        }
    }

    // endregion

    // region Create backup

    fun createBackup(details: KeystoreDetails, passphrase: String, includePasswords: Boolean) {
        val withPasswords = includePasswords && details.storePassword != null && details.keyPassword != null
        _state.update { it.copy(backup = BackupState(creating = true)) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.Default) {
                    KeystoreBackup.create(
                        keystore = details.keystore,
                        fileName = details.fileName,
                        storePassword = details.storePassword.takeIf { withPasswords },
                        keyPassword = details.keyPassword.takeIf { withPasswords },
                        passphrase = passphrase.toCharArray(),
                    )
                }
            }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { bytes ->
                        state.copy(
                            backup = BackupState(
                                pending = PendingBackup(KeystoreBackup.fileNameFor(details.fileName), bytes),
                            ),
                        )
                    },
                    onFailure = { state.copy(backup = BackupState(error = it.message ?: it.toString())) },
                )
            }
        }
    }

    /** Called once the save dialog for a [PendingBackup] has closed, whether or not it was saved. */
    fun pendingBackupHandled() {
        _state.update { it.copy(backup = BackupState()) }
    }

    // endregion

    override fun onCleared() {
        _state.value = UiState()
    }

    private suspend fun readUri(uri: Uri, maxSize: Int): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "keystore"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > maxSize) error("File is too large")
            }
            out.toByteArray()
        } ?: error("Could not open the file")
        name to bytes
    }

    private fun FormState.toRequest() = KeystoreRequest(
        alias = alias,
        storePassword = storePassword.toCharArray(),
        keyPassword = effectiveKeyPassword.toCharArray(),
        validityYears = validityYears.toIntOrNull() ?: 0,
        keyAlgorithm = algorithm,
        format = format,
        distinguishedName = DistinguishedName(
            commonName = commonName,
            organizationalUnit = organizationalUnit,
            organization = organization,
            locality = locality,
            state = state,
            countryCode = countryCode,
        ),
    )

    private companion object {
        const val MAX_KEYSTORE_SIZE = 1024 * 1024
        const val MAX_BACKUP_SIZE = 8 * 1024 * 1024
    }
}

/** Keeps file names usable as ZIP entries and SAF suggestions. */
internal fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
