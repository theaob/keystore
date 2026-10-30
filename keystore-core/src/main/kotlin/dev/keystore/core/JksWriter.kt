package dev.keystore.core

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Writes the Sun "JKS" keystore format.
 *
 * Android's runtime has no JKS provider, so the format is serialised by hand following
 * `sun.security.provider.JavaKeyStore` and `sun.security.provider.KeyProtector` from OpenJDK.
 * The output loads with `KeyStore.getInstance("JKS")`, `keytool`, and the Android Gradle Plugin.
 */
internal object JksWriter {
    private const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION_2 = 2
    private const val PRIVATE_KEY_TAG = 1
    private const val SALT_LENGTH = 20
    private const val DIGEST_LENGTH = 20

    /** Sun's proprietary key protection algorithm (1.3.6.1.4.1.42.2.17.1.1). */
    private val KEY_PROTECTOR_OID = ASN1ObjectIdentifier("1.3.6.1.4.1.42.2.17.1.1")

    fun write(
        alias: String,
        privateKey: PrivateKey,
        chain: List<X509Certificate>,
        storePassword: CharArray,
        keyPassword: CharArray,
        creationDate: Long,
        random: SecureRandom,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION_2)
            out.writeInt(1)

            out.writeInt(PRIVATE_KEY_TAG)
            // JKS aliases are case-insensitive and stored lower-cased.
            out.writeUTF(alias.lowercase(Locale.ENGLISH))
            out.writeLong(creationDate)
            val protectedKey = protectKey(privateKey.encoded, keyPassword, random)
            out.writeInt(protectedKey.size)
            out.write(protectedKey)
            out.writeInt(chain.size)
            for (cert in chain) {
                val encoded = cert.encoded
                out.writeUTF(cert.type)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }

        val bytes = body.toByteArray()
        val integrity = MessageDigest.getInstance("SHA-1").run {
            update(passwordBytes(storePassword))
            update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
            update(bytes)
            digest()
        }
        return bytes + integrity
    }

    /** Port of `KeyProtector.protect`: a SHA-1 keystream XOR plus a SHA-1 checksum. */
    private fun protectKey(plainKey: ByteArray, password: CharArray, random: SecureRandom): ByteArray {
        val passwordBytes = passwordBytes(password)
        val sha1 = MessageDigest.getInstance("SHA-1")
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)

        val xorKey = ByteArray(plainKey.size)
        var digest = salt
        var offset = 0
        while (offset < plainKey.size) {
            sha1.update(passwordBytes)
            sha1.update(digest)
            digest = sha1.digest()
            val count = minOf(DIGEST_LENGTH, plainKey.size - offset)
            System.arraycopy(digest, 0, xorKey, offset, count)
            offset += count
        }
        val encrypted = ByteArray(plainKey.size) { (plainKey[it].toInt() xor xorKey[it].toInt()).toByte() }

        sha1.update(passwordBytes)
        sha1.update(plainKey)
        val checksum = sha1.digest()

        passwordBytes.fill(0)
        plainKey.fill(0)
        xorKey.fill(0)

        val protected = salt + encrypted + checksum
        return EncryptedPrivateKeyInfo(AlgorithmIdentifier(KEY_PROTECTOR_OID, DERNull.INSTANCE), protected)
            .encoded
    }

    /** JKS hashes passwords as big-endian UTF-16 code units. */
    private fun passwordBytes(password: CharArray): ByteArray {
        val bytes = ByteArray(password.size * 2)
        password.forEachIndexed { i, c ->
            bytes[i * 2] = (c.code shr 8).toByte()
            bytes[i * 2 + 1] = c.code.toByte()
        }
        return bytes
    }
}
