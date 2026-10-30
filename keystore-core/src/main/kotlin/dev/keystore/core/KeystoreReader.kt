package dev.keystore.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** A keystore file together with its signing key's alias and certificate. */
class KeystoreFile(
    val bytes: ByteArray,
    val format: KeystoreFormat,
    val alias: String,
    val certificate: X509Certificate,
) {
    val info: CertificateInfo by lazy { CertificateInfo.from(certificate) }
}

class KeystoreLoadException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    enum class Reason(val message: String) {
        NOT_A_KEYSTORE("This file is not a JKS or PKCS12 keystore"),
        WRONG_STORE_PASSWORD("Wrong store password"),
        WRONG_KEY_PASSWORD("Wrong key password"),
        NO_PRIVATE_KEY("The keystore contains no private key"),
        KEY_CERTIFICATE_MISMATCH("The private key does not match its certificate"),
        UNSUPPORTED("This keystore uses an unsupported key protection scheme"),
        CORRUPT("The keystore file is damaged"),
    }
}

/**
 * Opens an existing keystore and proves the passwords work by recovering the private key and
 * signing with it, so a backup is never made of a keystore nobody can unlock.
 */
object KeystoreReader {
    private val bouncyCastle = BouncyCastleProvider()

    fun detectFormat(bytes: ByteArray): KeystoreFormat? = when {
        bytes.size >= 4 && ByteBuffer.wrap(bytes, 0, 4).int == JksFormat.MAGIC -> KeystoreFormat.JKS
        // PKCS#12 is a DER SEQUENCE.
        bytes.isNotEmpty() && bytes[0] == 0x30.toByte() -> KeystoreFormat.PKCS12
        else -> null
    }

    /**
     * @param keyPassword the private key's password; `null` means the same as [storePassword].
     * @param alias the entry to use; `null` picks the first private key entry.
     */
    fun load(
        bytes: ByteArray,
        storePassword: CharArray,
        keyPassword: CharArray? = null,
        alias: String? = null,
    ): KeystoreFile {
        val format = detectFormat(bytes) ?: throw KeystoreLoadException(KeystoreLoadException.Reason.NOT_A_KEYSTORE)
        val keyPass = keyPassword ?: storePassword
        return when (format) {
            KeystoreFormat.JKS -> loadJks(bytes, storePassword, keyPass, alias)
            KeystoreFormat.PKCS12 -> loadPkcs12(bytes, storePassword, keyPass, alias)
        }
    }

    private fun loadJks(bytes: ByteArray, storePassword: CharArray, keyPassword: CharArray, alias: String?): KeystoreFile {
        val entries = JksFormat.read(bytes, storePassword)
        val entry = if (alias == null) {
            entries.firstOrNull()
        } else {
            entries.firstOrNull { it.alias.equals(alias, ignoreCase = true) }
        } ?: throw KeystoreLoadException(KeystoreLoadException.Reason.NO_PRIVATE_KEY)
        val certificate = entry.chain.firstOrNull()
            ?: throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT)

        val encoded = JksFormat.recoverKey(entry.protectedKey, keyPassword)
        val privateKey = try {
            KeyFactory.getInstance(certificate.publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(encoded))
        } catch (e: Exception) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT, e)
        } finally {
            encoded.fill(0)
        }
        checkKeyMatches(privateKey, certificate)
        return KeystoreFile(bytes, KeystoreFormat.JKS, entry.alias, certificate)
    }

    private fun loadPkcs12(
        bytes: ByteArray,
        storePassword: CharArray,
        keyPassword: CharArray,
        alias: String?,
    ): KeystoreFile {
        val store = KeyStore.getInstance("PKCS12", bouncyCastle)
        try {
            store.load(ByteArrayInputStream(bytes), storePassword)
        } catch (e: Exception) {
            // Bouncy Castle reports a bad MAC (wrong password) and malformed input the same way.
            val reason = if (e.message.orEmpty().contains("password", ignoreCase = true) ||
                e.message.orEmpty().contains("mac", ignoreCase = true)
            ) {
                KeystoreLoadException.Reason.WRONG_STORE_PASSWORD
            } else {
                KeystoreLoadException.Reason.NOT_A_KEYSTORE
            }
            throw KeystoreLoadException(reason, e)
        }

        val entryAlias = store.aliases().toList()
            .filter { store.isKeyEntry(it) }
            .let { keyAliases ->
                if (alias == null) keyAliases.firstOrNull() else keyAliases.firstOrNull { it.equals(alias, ignoreCase = true) }
            }
            ?: throw KeystoreLoadException(KeystoreLoadException.Reason.NO_PRIVATE_KEY)

        val privateKey = try {
            store.getKey(entryAlias, keyPassword) as? PrivateKey
        } catch (e: Exception) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.WRONG_KEY_PASSWORD, e)
        } ?: throw KeystoreLoadException(KeystoreLoadException.Reason.NO_PRIVATE_KEY)
        val certificate = store.getCertificate(entryAlias) as? X509Certificate
            ?: throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT)

        checkKeyMatches(privateKey, certificate)
        return KeystoreFile(bytes, KeystoreFormat.PKCS12, entryAlias, certificate)
    }

    private fun checkKeyMatches(privateKey: PrivateKey, certificate: X509Certificate) {
        val algorithm = when (certificate.publicKey.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> return
        }
        val data = "keystore-check".toByteArray()
        val matches = runCatching {
            val signature = Signature.getInstance(algorithm).run {
                initSign(privateKey)
                update(data)
                sign()
            }
            Signature.getInstance(algorithm).run {
                initVerify(certificate.publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!matches) throw KeystoreLoadException(KeystoreLoadException.Reason.KEY_CERTIFICATE_MISMATCH)
    }
}
