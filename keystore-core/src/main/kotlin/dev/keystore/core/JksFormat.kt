package dev.keystore.core

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Reads and writes the Sun "JKS" keystore format.
 *
 * Android's runtime has no JKS provider, so the format is handled by hand following
 * `sun.security.provider.JavaKeyStore` and `sun.security.provider.KeyProtector` from OpenJDK.
 * The output loads with `KeyStore.getInstance("JKS")`, `keytool`, and the Android Gradle Plugin.
 */
internal object JksFormat {
    const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION_1 = 1
    private const val VERSION_2 = 2
    private const val PRIVATE_KEY_TAG = 1
    private const val TRUSTED_CERT_TAG = 2
    private const val SALT_LENGTH = 20
    private const val DIGEST_LENGTH = 20

    /** Sun's proprietary key protection algorithm (1.3.6.1.4.1.42.2.17.1.1). */
    private val KEY_PROTECTOR_OID = ASN1ObjectIdentifier("1.3.6.1.4.1.42.2.17.1.1")

    /** A private key entry whose key is still protected by the key password. */
    class KeyEntry(val alias: String, val protectedKey: ByteArray, val chain: List<X509Certificate>)

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
        return bytes + integrityDigest(storePassword, bytes, bytes.size)
    }

    /**
     * Parses a JKS file and checks its integrity digest against [storePassword].
     * Returns the private key entries; trusted certificate entries are skipped.
     */
    fun read(bytes: ByteArray, storePassword: CharArray): List<KeyEntry> {
        if (bytes.size < 12 + DIGEST_LENGTH) throw KeystoreLoadException(KeystoreLoadException.Reason.NOT_A_KEYSTORE)
        val bodyLength = bytes.size - DIGEST_LENGTH
        val expected = integrityDigest(storePassword, bytes, bodyLength)
        val actual = bytes.copyOfRange(bodyLength, bytes.size)
        if (!MessageDigest.isEqual(expected, actual)) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.WRONG_STORE_PASSWORD)
        }

        val certificateFactory = CertificateFactory.getInstance("X.509")
        try {
            DataInputStream(ByteArrayInputStream(bytes, 0, bodyLength)).use { input ->
                if (input.readInt() != MAGIC) throw KeystoreLoadException(KeystoreLoadException.Reason.NOT_A_KEYSTORE)
                val version = input.readInt()
                if (version != VERSION_1 && version != VERSION_2) {
                    throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT)
                }

                fun readCertificate(): X509Certificate {
                    if (version == VERSION_2) input.readUTF()
                    val encoded = ByteArray(input.readInt()).also(input::readFully)
                    return certificateFactory.generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
                }

                val entries = mutableListOf<KeyEntry>()
                repeat(input.readInt()) {
                    when (input.readInt()) {
                        PRIVATE_KEY_TAG -> {
                            val alias = input.readUTF()
                            input.readLong()
                            val protectedKey = ByteArray(input.readInt()).also(input::readFully)
                            val chain = List(input.readInt()) { readCertificate() }
                            entries += KeyEntry(alias, protectedKey, chain)
                        }

                        TRUSTED_CERT_TAG -> {
                            input.readUTF()
                            input.readLong()
                            readCertificate()
                        }

                        else -> throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT)
                    }
                }
                return entries
            }
        } catch (e: IOException) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT, e)
        }
    }

    /** Port of `KeyProtector.recover`. Returns the PKCS#8 encoding of the private key. */
    fun recoverKey(protectedKey: ByteArray, password: CharArray): ByteArray {
        val info = try {
            EncryptedPrivateKeyInfo.getInstance(protectedKey)
        } catch (e: Exception) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT, e)
        }
        if (info.encryptionAlgorithm.algorithm != KEY_PROTECTOR_OID) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.UNSUPPORTED)
        }
        val protected = info.encryptedData
        if (protected.size < SALT_LENGTH + DIGEST_LENGTH) {
            throw KeystoreLoadException(KeystoreLoadException.Reason.CORRUPT)
        }
        val salt = protected.copyOfRange(0, SALT_LENGTH)
        val encrypted = protected.copyOfRange(SALT_LENGTH, protected.size - DIGEST_LENGTH)
        val checksum = protected.copyOfRange(protected.size - DIGEST_LENGTH, protected.size)

        val passwordBytes = passwordBytes(password)
        val plainKey = xor(encrypted, keystream(passwordBytes, salt, encrypted.size))
        val expected = MessageDigest.getInstance("SHA-1").run {
            update(passwordBytes)
            update(plainKey)
            digest()
        }
        passwordBytes.fill(0)
        if (!MessageDigest.isEqual(expected, checksum)) {
            plainKey.fill(0)
            throw KeystoreLoadException(KeystoreLoadException.Reason.WRONG_KEY_PASSWORD)
        }
        return plainKey
    }

    /** Port of `KeyProtector.protect`: a SHA-1 keystream XOR plus a SHA-1 checksum. */
    private fun protectKey(plainKey: ByteArray, password: CharArray, random: SecureRandom): ByteArray {
        val passwordBytes = passwordBytes(password)
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val encrypted = xor(plainKey, keystream(passwordBytes, salt, plainKey.size))
        val checksum = MessageDigest.getInstance("SHA-1").run {
            update(passwordBytes)
            update(plainKey)
            digest()
        }

        passwordBytes.fill(0)
        plainKey.fill(0)

        val protected = salt + encrypted + checksum
        return EncryptedPrivateKeyInfo(AlgorithmIdentifier(KEY_PROTECTOR_OID, DERNull.INSTANCE), protected)
            .encoded
    }

    private fun keystream(passwordBytes: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val stream = ByteArray(length)
        var digest = salt
        var offset = 0
        while (offset < length) {
            sha1.update(passwordBytes)
            sha1.update(digest)
            digest = sha1.digest()
            val count = minOf(DIGEST_LENGTH, length - offset)
            System.arraycopy(digest, 0, stream, offset, count)
            offset += count
        }
        return stream
    }

    private fun xor(data: ByteArray, stream: ByteArray): ByteArray {
        val result = ByteArray(data.size) { (data[it].toInt() xor stream[it].toInt()).toByte() }
        stream.fill(0)
        return result
    }

    private fun integrityDigest(storePassword: CharArray, bytes: ByteArray, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-1").run {
            val passwordBytes = passwordBytes(storePassword)
            update(passwordBytes)
            passwordBytes.fill(0)
            update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
            update(bytes, 0, length)
            digest()
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
