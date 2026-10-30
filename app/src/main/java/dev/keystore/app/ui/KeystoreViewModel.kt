package dev.keystore.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.keystore.core.DistinguishedName
import dev.keystore.core.GeneratedKeystore
import dev.keystore.core.KeyAlgorithm
import dev.keystore.core.KeystoreFormat
import dev.keystore.core.KeystoreGenerator
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
        get() = "${alias.trim().ifEmpty { "upload" }.replace(Regex("[^A-Za-z0-9._-]"), "_")}.${format.extension}"
}

/** Everything shown after generation. Passwords are kept so the config snippets can be built. */
data class GenerationResult(
    val keystore: GeneratedKeystore,
    val fileName: String,
    val storePassword: String,
    val keyPassword: String,
)

data class UiState(
    val form: FormState = FormState(),
    val errors: Map<ValidationError.Field, String> = emptyMap(),
    val generating: Boolean = false,
    val generationError: String? = null,
    val result: GenerationResult? = null,
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

class KeystoreViewModel(
    private val generator: KeystoreGenerator = KeystoreGenerator(),
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

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
                            result = GenerationResult(
                                keystore = keystore,
                                fileName = form.fileName,
                                storePassword = form.storePassword,
                                keyPassword = form.effectiveKeyPassword,
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

    /** Leaves the result screen and clears the passwords so a second key isn't created by accident. */
    fun startOver() {
        _state.update {
            UiState(form = it.form.copy(storePassword = "", keyPassword = ""))
        }
    }

    override fun onCleared() {
        _state.value = UiState()
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
}
