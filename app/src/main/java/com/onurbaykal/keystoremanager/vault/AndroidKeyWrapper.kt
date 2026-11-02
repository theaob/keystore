package com.onurbaykal.keystoremanager.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.onurbaykal.keystoremanager.core.KeyWrapper
import com.onurbaykal.keystoremanager.core.VaultException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Wraps vault entry keys with an RSA key pair kept in the Android Keystore (hardware-backed where
 * available). Wrapping only needs the public key, so saving never prompts; unwrapping needs the
 * private key, which is usable for [AUTH_VALIDITY_SECONDS] after the user unlocks with a strong
 * biometric or their screen lock. Until then [unwrap] throws [UserNotAuthenticatedException].
 */
class AndroidKeyWrapper(private val context: Context) : KeyWrapper {

    override fun wrap(key: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, softwarePublicKey(), OAEP)
            doFinal(key)
        }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val privateKey = keyStore().getKey(ALIAS, null) as? PrivateKey
            ?: throw VaultException("The app's storage key is missing, so saved keystores can't be opened")
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, privateKey, OAEP)
                doFinal(wrapped)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultException(
                "Your screen lock was removed or changed in a way that invalidated the storage key. " +
                    "Saved keystores can no longer be opened; restore them from a .ksbackup file.",
                e,
            )
        }
    }

    /** Public key operations don't need the user, and work most reliably outside the keystore provider. */
    private fun softwarePublicKey(): PublicKey {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) createKeyPair()
        val public = store.getCertificate(ALIAS).publicKey
        return KeyFactory.getInstance(public.algorithm).generatePublic(X509EncodedKeySpec(public.encoded))
    }

    private fun createKeyPair() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            throw VaultException("Set up a screen lock (PIN, pattern or password) to keep keystores in the app")
        }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                }
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "keystore-vault-v1"
        private const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        const val AUTH_VALIDITY_SECONDS = 60

        // Android Keystore only supports SHA-1 for OAEP's MGF1 on older releases, so pin it explicitly.
        private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}

/** True when [error] (or its cause) means the user must unlock before the vault can be read. */
fun needsUnlock(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is UserNotAuthenticatedException }
