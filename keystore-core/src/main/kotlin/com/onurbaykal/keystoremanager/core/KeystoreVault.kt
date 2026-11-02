package com.onurbaykal.keystoremanager.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps the per-entry AES keys of a [KeystoreVault]. On Android this is a hardware-backed RSA key
 * whose private half needs the user to unlock; [unwrap] may then throw an authentication exception
 * that callers handle by prompting and retrying.
 */
interface KeyWrapper {
    fun wrap(key: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** What the vault lists without decrypting anything. Everything here is also public in the certificate. */
data class VaultEntrySummary(
    val id: String,
    val fileName: String,
    val alias: String,
    val format: KeystoreFormat,
    val savedAt: Instant,
    val subject: String,
    val notAfter: Instant,
    val sha1: String,
    val hasPasswords: Boolean,
)

/** The passphrase of the most recent `.ksbackup` made of an entry. */
data class BackupRecord(val passphrase: String, val fileName: String, val createdAt: Instant)

class VaultEntry(
    val summary: VaultEntrySummary,
    val keystore: KeystoreFile,
    val storePassword: String?,
    val keyPassword: String?,
    val backup: BackupRecord?,
)

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Keeps keystores and their passwords in a directory, each encrypted with its own AES-256-GCM key
 * that is wrapped by a [KeyWrapper]. Per entry there is a plain `<id>.meta` with the public details
 * used for listing, an encrypted `<id>.enc` with the keystore and passwords, and optionally an
 * encrypted `<id>.backup` with the passphrase of its latest backup. Every write only needs the
 * wrapping (public) key, so nothing here prompts the user except [open].
 */
class KeystoreVault(
    private val directory: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Instant = Instant::now,
) {
    fun list(): List<VaultEntrySummary> =
        directory.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .mapNotNull { file -> runCatching { readMeta(file).toSummary() }.getOrNull() }
            .sortedByDescending { it.savedAt }

    /**
     * Stores [keystore]; storing the same keystore file again replaces the earlier entry, so passwords
     * can be added later without creating a duplicate.
     */
    fun save(keystore: KeystoreFile, fileName: String, storePassword: String?, keyPassword: String?): VaultEntrySummary {
        directory.mkdirs()
        val keystoreSha256 = sha256Hex(keystore.bytes)
        val id = directory.listFiles { file -> file.name.endsWith(META_SUFFIX) }
            .orEmpty()
            .firstNotNullOfOrNull { file ->
                runCatching { readMeta(file) }.getOrNull()
                    ?.takeIf { it.getProperty("keystoreSha256") == keystoreSha256 }
                    ?.getProperty("id")
            } ?: UUID.randomUUID().toString()

        val info = keystore.info
        val meta = Properties().apply {
            setProperty("version", FORMAT_VERSION.toString())
            setProperty("id", id)
            setProperty("fileName", fileName)
            setProperty("alias", keystore.alias)
            setProperty("format", keystore.format.name)
            setProperty("savedAt", clock().toString())
            setProperty("subject", info.subject)
            setProperty("notAfter", info.notAfter.toString())
            setProperty("sha1", info.sha1)
            setProperty("keystoreSha256", keystoreSha256)
            setProperty("certificate", Base64.getEncoder().encodeToString(keystore.certificate.encoded))
            setProperty("hasPasswords", (storePassword != null).toString())
        }
        val secret = Properties().apply {
            setProperty("keystore", Base64.getEncoder().encodeToString(keystore.bytes))
            storePassword?.let { setProperty("storePassword", it) }
            keyPassword?.let { setProperty("keyPassword", it) }
        }

        writeAtomically(File(directory, id + SECRET_SUFFIX), encrypt(toBytes(secret)))
        writeAtomically(File(directory, id + META_SUFFIX), toBytes(meta))
        return meta.toSummary()
    }

    /** Decrypts an entry. Exceptions thrown by [KeyWrapper.unwrap] (e.g. authentication) propagate unchanged. */
    fun open(id: String): VaultEntry {
        val meta = readMeta(metaFile(id))
        val summary = meta.toSummary()
        val encrypted = try {
            File(directory, id + SECRET_SUFFIX).readBytes()
        } catch (e: IOException) {
            throw VaultException("The stored keystore is missing", e)
        }
        val secret = Properties().apply { load(ByteArrayInputStream(decrypt(encrypted)).reader(Charsets.UTF_8)) }

        val bytes = Base64.getDecoder().decode(secret.getProperty("keystore") ?: throw VaultException("Damaged entry"))
        if (sha256Hex(bytes) != meta.getProperty("keystoreSha256")) throw VaultException("Damaged entry")
        val storePassword = secret.getProperty("storePassword")
        val keyPassword = secret.getProperty("keyPassword")

        val keystore = if (storePassword != null) {
            try {
                KeystoreReader.load(bytes, storePassword.toCharArray(), keyPassword?.toCharArray(), summary.alias)
            } catch (e: KeystoreLoadException) {
                throw VaultException("The stored passwords no longer unlock this keystore", e)
            }
        } else {
            val der = Base64.getDecoder().decode(meta.getProperty("certificate"))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            KeystoreFile(bytes, summary.format, summary.alias, certificate)
        }
        return VaultEntry(summary, keystore, storePassword, keyPassword, readBackupRecord(id))
    }

    /** Remembers the passphrase of a backup just made of entry [id], replacing any earlier one. */
    fun saveBackupPassphrase(id: String, passphrase: String, fileName: String): BackupRecord {
        if (!metaFile(id).exists()) throw VaultException("Stored keystore not found")
        val record = BackupRecord(passphrase, fileName, clock())
        val properties = Properties().apply {
            setProperty("passphrase", record.passphrase)
            setProperty("fileName", record.fileName)
            setProperty("createdAt", record.createdAt.toString())
        }
        writeAtomically(File(directory, id + BACKUP_SUFFIX), encrypt(toBytes(properties)))
        return record
    }

    fun delete(id: String) {
        File(directory, id + SECRET_SUFFIX).delete()
        File(directory, id + BACKUP_SUFFIX).delete()
        metaFile(id).delete()
    }

    private fun readBackupRecord(id: String): BackupRecord? {
        val file = File(directory, id + BACKUP_SUFFIX)
        if (!file.exists()) return null
        val properties = Properties().apply { load(ByteArrayInputStream(decrypt(file.readBytes())).reader(Charsets.UTF_8)) }
        return BackupRecord(
            passphrase = properties.getProperty("passphrase") ?: return null,
            fileName = properties.getProperty("fileName").orEmpty(),
            createdAt = runCatching { Instant.parse(properties.getProperty("createdAt")) }.getOrDefault(Instant.EPOCH),
        )
    }

    private fun metaFile(id: String): File {
        require(id.matches(ID_PATTERN)) { "Invalid id" }
        return File(directory, id + META_SUFFIX)
    }

    private fun readMeta(file: File): Properties = try {
        Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
    } catch (e: IOException) {
        throw VaultException("Stored keystore not found", e)
    }

    private fun Properties.toSummary(): VaultEntrySummary {
        fun required(key: String) = getProperty(key) ?: throw VaultException("Damaged entry")
        return VaultEntrySummary(
            id = required("id"),
            fileName = required("fileName"),
            alias = required("alias"),
            format = KeystoreFormat.valueOf(required("format")),
            savedAt = Instant.parse(required("savedAt")),
            subject = required("subject"),
            notAfter = Instant.parse(required("notAfter")),
            sha1 = required("sha1"),
            hasPasswords = getProperty("hasPasswords").toBoolean(),
        )
    }

    /** `[wrapped key length][wrapped key][IV][AES-GCM ciphertext]` */
    private fun encrypt(plain: ByteArray): ByteArray {
        val key = ByteArray(AES_KEY_LENGTH).also(random::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plain)
        val wrapped = wrapper.wrap(key)
        key.fill(0)
        plain.fill(0)
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeShort(wrapped.size)
                it.write(wrapped)
                it.write(iv)
                it.write(ciphertext)
            }
        }.toByteArray()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(data))
        val wrapped: ByteArray
        val iv: ByteArray
        try {
            wrapped = ByteArray(input.readUnsignedShort()).also(input::readFully)
            iv = ByteArray(GCM_IV_LENGTH).also(input::readFully)
        } catch (e: IOException) {
            throw VaultException("Damaged entry", e)
        }
        val ciphertext = input.readBytes()
        val key = wrapper.unwrap(wrapped)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ciphertext)
            }
        } catch (e: Exception) {
            throw VaultException("Damaged entry", e)
        } finally {
            key.fill(0)
        }
    }

    private fun toBytes(properties: Properties): ByteArray =
        ByteArrayOutputStream().also { out -> out.writer(Charsets.UTF_8).use { properties.store(it, null) } }
            .toByteArray()

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw VaultException("Could not write ${target.name}")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val FORMAT_VERSION = 1
        const val META_SUFFIX = ".meta"
        const val SECRET_SUFFIX = ".enc"
        const val BACKUP_SUFFIX = ".backup"
        const val AES_KEY_LENGTH = 32
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        val ID_PATTERN = Regex("[0-9a-fA-F-]{36}")
    }
}
