package dev.keystore.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What a backup holds. Passwords are `null` when the user chose not to include them. */
class BackupContents(
    val keystore: KeystoreFile,
    val fileName: String,
    val storePassword: String?,
    val keyPassword: String?,
    val createdAt: Instant,
    /** True when the passwords were checked against the keystore while restoring. */
    val verified: Boolean,
)

class BackupException(val reason: Reason, cause: Throwable? = null) : Exception(reason.message, cause) {
    enum class Reason(val message: String) {
        WRONG_PASSPHRASE("Wrong backup passphrase, or not a keystore backup"),
        CORRUPT("The backup file is damaged"),
        UNSUPPORTED_VERSION("This backup was made by a newer version of the app"),
    }
}

/**
 * Encrypted keystore backups.
 *
 * A backup is a ZIP containing the keystore, its certificate, `keystore.properties` (when
 * passwords are included), a README and a manifest. The ZIP is encrypted in exactly the format of
 * `openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256`, so it can be recovered with a stock
 * `openssl` even without this app:
 *
 * ```
 * openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -in upload.ksbackup -out upload.zip
 * ```
 */
object KeystoreBackup {
    const val EXTENSION = "ksbackup"
    const val MIN_PASSPHRASE_LENGTH = 10
    const val PBKDF2_ITERATIONS = 600_000
    const val OPENSSL_DECRYPT_COMMAND =
        "openssl enc -d -aes-256-cbc -pbkdf2 -iter $PBKDF2_ITERATIONS -md sha256 -in <file>.$EXTENSION -out backup.zip"

    private const val FORMAT_VERSION = 1
    private const val MANIFEST = "backup.properties"
    private val OPENSSL_MAGIC = "Salted__".toByteArray(Charsets.US_ASCII)
    private const val SALT_LENGTH = 8
    private const val KEY_LENGTH = 32
    private const val IV_LENGTH = 16
    private const val MAX_ENTRY_SIZE = 4 * 1024 * 1024

    fun fileNameFor(keystoreFileName: String): String = "${keystoreFileName.substringBeforeLast('.')}.$EXTENSION"

    fun create(
        keystore: KeystoreFile,
        fileName: String,
        storePassword: String?,
        keyPassword: String?,
        passphrase: CharArray,
        createdAt: Instant = Instant.now(),
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase too short" }
        require((storePassword == null) == (keyPassword == null)) { "Include both passwords or neither" }
        return encrypt(zip(keystore, fileName, storePassword, keyPassword, createdAt), passphrase, random)
    }

    /** Decrypts and unpacks a backup, verifying the keystore with the stored passwords if present. */
    fun open(backup: ByteArray, passphrase: CharArray): BackupContents {
        val zip = decrypt(backup, passphrase)
        val entries = try {
            unzip(zip)
        } catch (e: Exception) {
            // CBC with a wrong key still passes the padding check about 1 time in 256.
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE, e)
        }
        return try {
            readContents(entries)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(BackupException.Reason.CORRUPT, e)
        }
    }

    private fun readContents(entries: Map<String, ByteArray>): BackupContents {

        val manifest = entries[MANIFEST]?.let { bytes ->
            Properties().apply { load(ByteArrayInputStream(bytes).reader(Charsets.UTF_8)) }
        } ?: throw BackupException(BackupException.Reason.CORRUPT)
        val version = manifest.getProperty("version")?.toIntOrNull()
            ?: throw BackupException(BackupException.Reason.CORRUPT)
        if (version > FORMAT_VERSION) throw BackupException(BackupException.Reason.UNSUPPORTED_VERSION)

        fun required(key: String) = manifest.getProperty(key) ?: throw BackupException(BackupException.Reason.CORRUPT)
        val fileName = required("keystoreFile")
        val alias = required("alias")
        val format = runCatching { KeystoreFormat.valueOf(required("format")) }
            .getOrElse { throw BackupException(BackupException.Reason.CORRUPT, it) }
        val createdAt = runCatching { Instant.parse(required("createdAt")) }.getOrDefault(Instant.EPOCH)
        val storePassword = manifest.getProperty("storePassword")
        val keyPassword = manifest.getProperty("keyPassword")

        val keystoreBytes = entries[fileName] ?: throw BackupException(BackupException.Reason.CORRUPT)
        if (sha256Hex(keystoreBytes) != required("sha256")) throw BackupException(BackupException.Reason.CORRUPT)

        val keystore = if (storePassword != null) {
            try {
                KeystoreReader.load(keystoreBytes, storePassword.toCharArray(), keyPassword?.toCharArray(), alias)
            } catch (e: KeystoreLoadException) {
                throw BackupException(BackupException.Reason.CORRUPT, e)
            }
        } else {
            val pem = entries[certificateFileName(fileName)] ?: throw BackupException(BackupException.Reason.CORRUPT)
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
            KeystoreFile(keystoreBytes, format, alias, certificate)
        }
        return BackupContents(
            keystore = keystore,
            fileName = fileName,
            storePassword = storePassword,
            keyPassword = keyPassword,
            createdAt = createdAt,
            verified = storePassword != null,
        )
    }

    fun certificateFileName(keystoreFileName: String) = "${keystoreFileName.substringBeforeLast('.')}_certificate.pem"

    private fun zip(
        keystore: KeystoreFile,
        fileName: String,
        storePassword: String?,
        keyPassword: String?,
        createdAt: Instant,
    ): ByteArray {
        val info = keystore.info
        val manifest = Properties().apply {
            setProperty("version", FORMAT_VERSION.toString())
            setProperty("keystoreFile", fileName)
            setProperty("format", keystore.format.name)
            setProperty("alias", keystore.alias)
            setProperty("createdAt", createdAt.toString())
            setProperty("sha256", sha256Hex(keystore.bytes))
            setProperty("certificateSha256", info.sha256)
            if (storePassword != null && keyPassword != null) {
                setProperty("storePassword", storePassword)
                setProperty("keyPassword", keyPassword)
            }
        }
        val manifestBytes = ByteArrayOutputStream().also { out ->
            out.writer(Charsets.UTF_8).use { manifest.store(it, "Keystore backup manifest") }
        }.toByteArray()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = createdAt.toEpochMilli() })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("README.txt", readme(keystore, fileName, storePassword != null, createdAt).toByteArray())
            entry(fileName, keystore.bytes)
            entry(certificateFileName(fileName), info.pem.toByteArray())
            if (storePassword != null && keyPassword != null) {
                entry(
                    "keystore.properties",
                    SigningSnippets.keystoreProperties(fileName, keystore.alias, storePassword, keyPassword)
                        .toByteArray(),
                )
            }
            entry(MANIFEST, manifestBytes)
        }
        return out.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    data.write(buffer, 0, read)
                    if (data.size() > MAX_ENTRY_SIZE) throw BackupException(BackupException.Reason.CORRUPT)
                }
                entries[entry.name] = data.toByteArray()
            }
        }
        if (entries.isEmpty()) throw BackupException(BackupException.Reason.CORRUPT)
        return entries
    }

    private fun readme(keystore: KeystoreFile, fileName: String, hasPasswords: Boolean, createdAt: Instant): String {
        val info = keystore.info
        val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
        return buildString {
            appendLine("Android signing keystore backup")
            appendLine("================================")
            appendLine()
            appendLine("Created:        ${date.format(createdAt)}")
            appendLine("Keystore file:  $fileName (${keystore.format.displayName})")
            appendLine("Key alias:      ${keystore.alias}")
            appendLine("Owner:          ${info.subject}")
            appendLine("Valid until:    ${date.format(info.notAfter)}")
            appendLine("SHA-1:          ${info.sha1}")
            appendLine("SHA-256:        ${info.sha256}")
            appendLine()
            if (hasPasswords) {
                appendLine("The store and key passwords are in keystore.properties.")
            } else {
                appendLine("Passwords were NOT included in this backup. Keep them in your password manager.")
            }
            appendLine()
            appendLine("This archive was encrypted with:")
            appendLine("  openssl enc -aes-256-cbc -pbkdf2 -iter $PBKDF2_ITERATIONS -md sha256")
            appendLine("To decrypt it without the app:")
            appendLine("  $OPENSSL_DECRYPT_COMMAND")
            appendLine()
            appendLine("Verify the keystore with:")
            appendLine("  keytool -list -v -keystore $fileName")
        }
    }

    private fun encrypt(plain: ByteArray, passphrase: CharArray, random: SecureRandom): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt)
        return OPENSSL_MAGIC + salt + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        val headerLength = OPENSSL_MAGIC.size + SALT_LENGTH
        if (data.size < headerLength + IV_LENGTH ||
            !data.copyOfRange(0, OPENSSL_MAGIC.size).contentEquals(OPENSSL_MAGIC)
        ) {
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE)
        }
        val salt = data.copyOfRange(OPENSSL_MAGIC.size, headerLength)
        return try {
            cipher(Cipher.DECRYPT_MODE, passphrase, salt).doFinal(data, headerLength, data.size - headerLength)
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE, e)
        }
    }

    /** OpenSSL's `-pbkdf2` derivation: PBKDF2-HMAC-SHA256 over the UTF-8 passphrase, key then IV. */
    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray): Cipher {
        val passwordBytes = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase)).let { buffer ->
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        val generator = PKCS5S2ParametersGenerator(SHA256Digest())
        generator.init(passwordBytes, salt, PBKDF2_ITERATIONS)
        val derived = (generator.generateDerivedParameters((KEY_LENGTH + IV_LENGTH) * 8) as KeyParameter).key
        passwordBytes.fill(0)

        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                mode,
                SecretKeySpec(derived, 0, KEY_LENGTH, "AES"),
                IvParameterSpec(derived, KEY_LENGTH, IV_LENGTH),
            )
            derived.fill(0)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
