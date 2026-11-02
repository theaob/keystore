package com.onurbaykal.keystoremanager.vault

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks the user to unlock with a biometric or their screen lock, which unlocks the vault key for
 * [AndroidKeyWrapper.AUTH_VALIDITY_SECONDS].
 *
 * @param onError receives `null` when the user cancelled.
 */
fun showUnlockPrompt(context: Context, title: String, onSuccess: () -> Unit, onError: (String?) -> Unit) {
    val activity = context.findFragmentActivity() ?: return onError("Can't show the unlock prompt here")
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                onError(if (cancelled) null else errString.toString())
            }
        },
    )
    // Strong biometrics combined with the screen lock is only supported from Android 11.
    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Unlock to open the saved keystore")
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
