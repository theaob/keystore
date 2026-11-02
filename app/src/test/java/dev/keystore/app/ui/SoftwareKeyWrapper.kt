package dev.keystore.app.ui

import dev.keystore.core.KeyWrapper
import java.security.KeyPairGenerator
import javax.crypto.Cipher

/** Stands in for [dev.keystore.app.vault.AndroidKeyWrapper], which needs a real Android Keystore. */
class SoftwareKeyWrapper : KeyWrapper {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    override fun wrap(key: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, keyPair.public)
            doFinal(key)
        }

    override fun unwrap(wrapped: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(wrapped)
        }

    private companion object {
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    }
}
