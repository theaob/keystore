package com.onurbaykal.keystoremanager.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onurbaykal.keystoremanager.vault.AndroidKeyWrapper
import com.onurbaykal.keystoremanager.vault.needsUnlock
import com.onurbaykal.keystoremanager.core.BackupException
import com.onurbaykal.keystoremanager.core.BackupRecord
import com.onurbaykal.keystoremanager.core.CertificateProfile
import com.onurbaykal.keystoremanager.core.CertificateProfileStore
import com.onurbaykal.keystoremanager.core.DistinguishedName
import com.onurbaykal.keystoremanager.core.KeyAlgorithm
import com.onurbaykal.keystoremanager.core.KeystoreBackup
import com.onurbaykal.keystoremanager.core.KeystoreFile
import com.onurbaykal.keystoremanager.core.KeystoreFormat
import com.onurbaykal.keystoremanager.core.KeystoreGenerator
import com.onurbaykal.keystoremanager.core.KeystoreLoadException
import com.onurbaykal.keystoremanager.core.KeystoreReader
import com.onurbaykal.keystoremanager.core.KeystoreRequest
import com.onurbaykal.keystoremanager.core.KeystoreValidator
import com.onurbaykal.keystoremanager.core.KeystoreVault
import com.onurbaykal.keystoremanager.core.PasswordGenerator
import com.onurbaykal.keystoremanager.core.ValidationError
import com.onurbaykal.keystoremanager.core.VaultEntrySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
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
    /** Profile the certificate fields came from; cleared as soon as they're edited. */
    val profileId: String? = null,
) {
    val distinguishedName: DistinguishedName
        get() = DistinguishedName(commonName, organizationalUnit, organization, locality, state, countryCode)

    fun withDistinguishedName(dn: DistinguishedName) = copy(
        commonName = dn.commonName,
        organizationalUnit = dn.organizationalUnit,
        organization = dn.organization,
        locality = dn.locality,
        state = dn.state,
        countryCode = dn.countryCode,
    )

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
    /** Id in the in-app vault, or `null` if this keystore isn't kept in the app. */
    val storedId: String? = null,
    /** Passphrase of the latest `.ksbackup` made of this keystore, if known. */
    val backup: BackupRecord? = null,
)

data class VaultState(
    val entries: List<VaultEntrySummary> = emptyList(),
    /** Entry being opened or saved, for progress indicators. */
    val busyId: String? = null,
    val saving: Boolean = false,
    /** Entry waiting for the user to unlock with a biometric or their screen lock. */
    val unlockFor: String? = null,
    val error: String? = null,
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
class PendingBackup(val fileName: String, val bytes: ByteArray, val passphrase: String)

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
    val vault: VaultState = VaultState(),
    val profiles: List<CertificateProfile> = emptyList(),
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
    private var vault = KeystoreVault(File(application.filesDir, "vault"), AndroidKeyWrapper(application))
    private val profileStore = CertificateProfileStore(File(application.filesDir, "certificate-profiles.properties"))
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Set right after a successful unlock so a still-locked key reports an error instead of re-prompting forever. */
    private var justUnlocked = false
    private var refreshJob: Job? = null

    init {
        refreshVault()
        // Start the form with the profile used last time, so certificate details needn't be retyped.
        viewModelScope.launch {
            val (profiles, lastUsed) = withContext(Dispatchers.IO) {
                runCatching { profileStore.list() to profileStore.lastUsed() }
                    .getOrDefault(emptyList<CertificateProfile>() to null)
            }
            _state.update { state ->
                val untouched = state.form.distinguishedName.isEmpty
                state.copy(
                    profiles = profiles,
                    form = if (lastUsed != null && untouched) {
                        state.form.withDistinguishedName(lastUsed.distinguishedName).copy(profileId = lastUsed.id)
                    } else {
                        state.form
                    },
                )
            }
        }
    }

    /** Lets tests substitute a vault that doesn't need the Android Keystore. */
    internal fun useVault(replacement: KeystoreVault) {
        refreshJob?.cancel()
        vault = replacement
        _state.update { it.copy(vault = it.vault.copy(entries = replacement.list())) }
    }

    fun open(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    /** Returns to the home screen and forgets every password and loaded file. */
    fun goHome() {
        _state.update {
            UiState(form = it.form.copy(storePassword = "", keyPassword = ""), vault = it.vault.copy(error = null))
        }
    }

    // region Generate

    fun updateForm(transform: (FormState) -> FormState) {
        _state.update { state ->
            var form = transform(state.form)
            val profile = state.profiles.firstOrNull { it.id == form.profileId }
            if (profile == null || profile.distinguishedName != form.distinguishedName) form = form.copy(profileId = null)
            state.copy(form = form, errors = emptyMap(), generationError = null)
        }
    }

    // region Certificate profiles

    fun applyProfile(id: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(form = it.form.withDistinguishedName(profile.distinguishedName).copy(profileId = id), errors = emptyMap())
        }
    }

    /** Saves the form's certificate fields as a profile named [name] (replacing one with the same name). */
    fun saveProfile(name: String) {
        val dn = _state.value.form.distinguishedName
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { profileStore.save(name, dn) to profileStore.list() }
            }
            outcome.onSuccess { (profile, profiles) ->
                _state.update { state ->
                    val form = if (state.form.distinguishedName == dn) {
                        // Adopt the normalised (trimmed, upper-case country) values the profile stores.
                        state.form.withDistinguishedName(profile.distinguishedName).copy(profileId = profile.id)
                    } else {
                        state.form
                    }
                    state.copy(profiles = profiles, form = form)
                }
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val profiles = withContext(Dispatchers.IO) {
                runCatching {
                    profileStore.delete(id)
                    profileStore.list()
                }.getOrNull()
            } ?: return@launch
            _state.update { state ->
                state.copy(
                    profiles = profiles,
                    form = if (state.form.profileId == id) state.form.copy(profileId = null) else state.form,
                )
            }
        }
    }

    // endregion

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
            val profileId = form.profileId
            if (outcome.isSuccess && profileId != null) {
                withContext(Dispatchers.IO) { runCatching { profileStore.markUsed(profileId) } }
            }
            // Keep every generated keystore in the app so it can't be lost by navigating away.
            val stored = outcome.getOrNull()?.let { keystore ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        vault.save(keystore, form.fileName, form.storePassword, form.effectiveKeyPassword)
                    }
                }
            }
            // Update the home screen's list too, not just the details screen.
            if (stored?.isSuccess == true) refreshVault()
            _state.update { state ->
                outcome.fold(
                    onSuccess = { keystore ->
                        val storedId = stored?.getOrNull()?.id
                        state.copy(
                            generating = false,
                            screen = Screen.Details(
                                KeystoreDetails(
                                    keystore = keystore,
                                    fileName = form.fileName,
                                    storePassword = form.storePassword,
                                    keyPassword = form.effectiveKeyPassword,
                                    title = "Keystore ready",
                                    notice = if (storedId != null) {
                                        STORED_NOTICE
                                    } else {
                                        "Couldn't keep this keystore in the app" +
                                            (stored?.exceptionOrNull()?.message?.let { ": $it" } ?: "") +
                                            ". Save the file and create an encrypted backup now: without " +
                                            "the keystore and its passwords you can't publish updates."
                                    },
                                    storedId = storedId,
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

    // region In-app vault

    private fun refreshVault() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { runCatching { vault.list() }.getOrDefault(emptyList()) }
            _state.update { it.copy(vault = it.vault.copy(entries = entries)) }
        }
    }

    /** Keeps an imported or restored keystore in the app. */
    fun keepInApp(details: KeystoreDetails) {
        _state.update { it.copy(vault = it.vault.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    vault.save(details.keystore, details.fileName, details.storePassword, details.keyPassword).also {
                        details.backup?.let { backup ->
                            vault.saveBackupPassphrase(it.id, backup.passphrase, backup.fileName)
                        }
                    }
                }
            }
            _state.update { state ->
                val summary = outcome.getOrNull()
                val screen = state.screen
                state.copy(
                    screen = if (summary != null && screen is Screen.Details && screen.details === details) {
                        Screen.Details(details.copy(storedId = summary.id, notice = STORED_NOTICE))
                    } else {
                        screen
                    },
                    vault = state.vault.copy(saving = false, error = outcome.exceptionOrNull()?.message),
                )
            }
            refreshVault()
        }
    }

    fun openStored(id: String) {
        _state.update { it.copy(vault = it.vault.copy(busyId = id, error = null)) }
        viewModelScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { vault.open(id) } }
            val afterUnlock = justUnlocked
            justUnlocked = false
            _state.update { state ->
                outcome.fold(
                    onSuccess = { entry ->
                        state.copy(
                            vault = state.vault.copy(busyId = null),
                            screen = Screen.Details(
                                KeystoreDetails(
                                    keystore = entry.keystore,
                                    fileName = entry.summary.fileName,
                                    storePassword = entry.storePassword,
                                    keyPassword = entry.keyPassword,
                                    title = entry.summary.fileName,
                                    notice = STORED_NOTICE,
                                    storedId = id,
                                    backup = entry.backup,
                                ),
                            ),
                        )
                    },
                    onFailure = { error ->
                        if (needsUnlock(error) && afterUnlock) {
                            // e.g. a weak biometric on Android 9/10, which doesn't unlock keystore keys.
                            state.copy(
                                vault = state.vault.copy(
                                    busyId = null,
                                    error = "That unlock wasn't accepted for stored keys. Try again using your " +
                                        "PIN, pattern or password.",
                                ),
                            )
                        } else if (needsUnlock(error)) {
                            state.copy(vault = state.vault.copy(busyId = null, unlockFor = id))
                        } else {
                            state.copy(vault = state.vault.copy(busyId = null, error = error.message ?: error.toString()))
                        }
                    },
                )
            }
        }
    }

    /** The user unlocked with a biometric or their screen lock: retry opening the entry. */
    fun unlockSucceeded() {
        val id = _state.value.vault.unlockFor ?: return
        _state.update { it.copy(vault = it.vault.copy(unlockFor = null)) }
        justUnlocked = true
        openStored(id)
    }

    /** @param message `null` when the user simply cancelled. */
    fun unlockFailed(message: String?) {
        _state.update { it.copy(vault = it.vault.copy(unlockFor = null, error = message)) }
    }

    fun deleteStored(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.delete(id) }
            val screen = _state.value.screen
            if (screen is Screen.Details && screen.details.storedId == id) goHome()
            refreshVault()
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
                                pending = PendingBackup(KeystoreBackup.fileNameFor(details.fileName), bytes, passphrase),
                            ),
                        )
                    },
                    onFailure = { state.copy(backup = BackupState(error = it.message ?: it.toString())) },
                )
            }
        }
    }

    /** Called once the save dialog for a [PendingBackup] has closed, whether or not it was saved. */
    fun pendingBackupHandled(saved: Boolean) {
        val pending = _state.value.backup.pending
        _state.update { it.copy(backup = BackupState()) }
        if (saved && pending != null) rememberBackupPassphrase(pending)
    }

    /**
     * Records the passphrase of a backup that was just saved, so a randomly generated one isn't lost.
     * Kept keystores store it in the vault right away; others keep it until "Keep in this app".
     */
    private fun rememberBackupPassphrase(pending: PendingBackup) {
        val details = (_state.value.screen as? Screen.Details)?.details ?: return
        viewModelScope.launch {
            val storedId = details.storedId
            val stored = storedId?.let { id ->
                runCatching {
                    withContext(Dispatchers.IO) { vault.saveBackupPassphrase(id, pending.passphrase, pending.fileName) }
                }.getOrNull()
            }
            val record = stored ?: BackupRecord(pending.passphrase, pending.fileName, Instant.now())
            _state.update { state ->
                val screen = state.screen
                if (screen is Screen.Details && screen.details.keystore === details.keystore) {
                    state.copy(screen = Screen.Details(screen.details.copy(backup = record)))
                } else {
                    state
                }
            }
        }
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
        distinguishedName = distinguishedName,
    )

    private companion object {
        const val STORED_NOTICE = "Kept in this app, encrypted and unlocked with your fingerprint or screen " +
            "lock. It's deleted if you uninstall the app or lose this phone, so also save the file and " +
            "create an encrypted backup somewhere else."
        const val MAX_KEYSTORE_SIZE = 1024 * 1024
        const val MAX_BACKUP_SIZE = 8 * 1024 * 1024
    }
}

/** Keeps file names usable as ZIP entries and SAF suggestions. */
internal fun safeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
