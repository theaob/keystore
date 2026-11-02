package dev.keystore.core

import java.io.File
import java.security.KeyPairGenerator
import java.time.Instant
import javax.crypto.Cipher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for the Android Keystore wrapper; [locked] mimics "user not authenticated". */
class RsaTestWrapper : KeyWrapper {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    var locked = false

    override fun wrap(key: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, keyPair.public)
            doFinal(key)
        }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (locked) throw IllegalStateException("locked")
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(wrapped)
        }
    }
}

class KeystoreVaultTest {
    private val dir = kotlin.io.path.createTempDirectory("vault").toFile()
    private val wrapper = RsaTestWrapper()
    private var now = Instant.parse("2026-11-01T10:00:00Z")
    private val vault = KeystoreVault(File(dir, "vault"), wrapper, clock = { now })

    private fun generate(alias: String) = KeystoreGenerator().generate(
        KeystoreRequest(
            alias = alias,
            storePassword = "store-secret".toCharArray(),
            keyPassword = "key-secret".toCharArray(),
            distinguishedName = DistinguishedName(commonName = "Vault Test"),
        ),
    )

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `saves lists and opens with passwords`() {
        val keystore = generate("upload")
        val summary = vault.save(keystore, "upload.jks", "store-secret", "key-secret")

        assertEquals(listOf(summary), vault.list())
        assertEquals("upload.jks", summary.fileName)
        assertEquals(keystore.info.sha1, summary.sha1)
        assertTrue(summary.hasPasswords)

        val entry = vault.open(summary.id)
        assertContentEquals(keystore.bytes, entry.keystore.bytes)
        assertEquals(keystore.certificate, entry.keystore.certificate)
        assertEquals("store-secret", entry.storePassword)
        assertEquals("key-secret", entry.keyPassword)
    }

    @Test
    fun `entries without passwords keep the certificate`() {
        val keystore = generate("upload")
        val summary = vault.save(keystore, "upload.jks", null, null)
        val entry = vault.open(summary.id)
        assertNull(entry.storePassword)
        assertEquals(keystore.certificate, entry.keystore.certificate)
    }

    @Test
    fun `newest first, same keystore replaces, delete removes`() {
        val first = vault.save(generate("first"), "first.jks", "store-secret", "key-secret")
        now = now.plusSeconds(60)
        val secondKeystore = generate("second")
        val second = vault.save(secondKeystore, "second.jks", null, null)
        assertEquals(listOf(second.id, first.id), vault.list().map { it.id })

        now = now.plusSeconds(60)
        val updated = vault.save(secondKeystore, "second.jks", "store-secret", "key-secret")
        assertEquals(second.id, updated.id)
        assertEquals(2, vault.list().size)
        assertTrue(vault.list().first().hasPasswords)

        vault.delete(first.id)
        assertEquals(listOf(second.id), vault.list().map { it.id })
        assertEquals(listOf("${second.id}.enc", "${second.id}.meta"), File(dir, "vault").list()!!.sorted())
    }

    @Test
    fun `backup passphrases are kept, survive re-saving and are deleted with the entry`() {
        val keystore = generate("upload")
        val summary = vault.save(keystore, "upload.jks", "store-secret", "key-secret")
        assertNull(vault.open(summary.id).backup)

        vault.saveBackupPassphrase(summary.id, "first passphrase", "upload.ksbackup")
        now = now.plusSeconds(60)
        vault.saveBackupPassphrase(summary.id, "second passphrase", "upload.ksbackup")
        vault.save(keystore, "upload.jks", "store-secret", "key-secret")

        val backup = vault.open(summary.id).backup!!
        assertEquals("second passphrase", backup.passphrase)
        assertEquals(now, backup.createdAt)
        File(dir, "vault").listFiles()!!.forEach { file ->
            assertTrue("second passphrase" !in file.readText(Charsets.ISO_8859_1), file.name)
        }

        vault.delete(summary.id)
        assertEquals(emptyList(), File(dir, "vault").list()!!.toList())
        assertFailsWith<VaultException> { vault.saveBackupPassphrase(summary.id, "x", "y") }
    }

    @Test
    fun `wrapper failures propagate so the app can ask the user to unlock`() {
        val summary = vault.save(generate("upload"), "upload.jks", "store-secret", "key-secret")
        wrapper.locked = true
        assertFailsWith<IllegalStateException> { vault.open(summary.id) }
        wrapper.locked = false
        vault.open(summary.id)
    }

    @Test
    fun `tampered entries are rejected`() {
        val summary = vault.save(generate("upload"), "upload.jks", "store-secret", "key-secret")
        val file = File(File(dir, "vault"), "${summary.id}.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<VaultException> { vault.open(summary.id) }
    }

    @Test
    fun `secrets are not stored in plain text`() {
        vault.save(generate("upload"), "upload.jks", "store-secret", "key-secret")
        File(dir, "vault").listFiles()!!.forEach { file ->
            assertTrue("store-secret" !in file.readText(Charsets.ISO_8859_1), file.name)
        }
    }
}
