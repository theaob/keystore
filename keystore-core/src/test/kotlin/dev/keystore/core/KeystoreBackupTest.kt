package dev.keystore.core

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeystoreBackupTest {
    private val keystore = KeystoreGenerator().generate(
        KeystoreRequest(
            alias = "upload",
            storePassword = "store-secret".toCharArray(),
            keyPassword = "key-secret".toCharArray(),
            distinguishedName = DistinguishedName(commonName = "Backup Test", countryCode = "US"),
        ),
    )
    private val passphrase = "correct horse battery staple"

    @Test
    fun `round trip with passwords verifies the keystore`() {
        val backup = KeystoreBackup.create(keystore, "upload.jks", "store-secret", "key-secret", passphrase.toCharArray())
        val restored = KeystoreBackup.open(backup, passphrase.toCharArray())

        assertContentEquals(keystore.bytes, restored.keystore.bytes)
        assertEquals("upload.jks", restored.fileName)
        assertEquals("store-secret", restored.storePassword)
        assertEquals("key-secret", restored.keyPassword)
        assertEquals(keystore.certificate, restored.keystore.certificate)
        assertTrue(restored.verified)
    }

    @Test
    fun `round trip without passwords keeps the certificate`() {
        val backup = KeystoreBackup.create(keystore, "upload.jks", null, null, passphrase.toCharArray())
        val restored = KeystoreBackup.open(backup, passphrase.toCharArray())

        assertContentEquals(keystore.bytes, restored.keystore.bytes)
        assertNull(restored.storePassword)
        assertEquals(keystore.certificate, restored.keystore.certificate)
        assertTrue(!restored.verified)
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val backup = KeystoreBackup.create(keystore, "upload.jks", "store-secret", "key-secret", passphrase.toCharArray())
        val error = assertFailsWith<BackupException> { KeystoreBackup.open(backup, "not the passphrase".toCharArray()) }
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE, error.reason)

        val garbage = assertFailsWith<BackupException> { KeystoreBackup.open(keystore.bytes, passphrase.toCharArray()) }
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE, garbage.reason)
    }

    @Test
    fun `tampered backup is rejected`() {
        val backup = KeystoreBackup.create(keystore, "upload.jks", "store-secret", "key-secret", passphrase.toCharArray())
        backup[backup.size / 2] = (backup[backup.size / 2].toInt() xor 0x55).toByte()
        assertFailsWith<BackupException> { KeystoreBackup.open(backup, passphrase.toCharArray()) }
    }

    @Test
    fun `stock openssl decrypts the backup`() {
        val openssl = listOf("/usr/bin/openssl", "/usr/local/bin/openssl", "/opt/homebrew/bin/openssl")
            .map(::File).firstOrNull { it.canExecute() } ?: return
        val dir = kotlin.io.path.createTempDirectory("backup").toFile()
        try {
            val encrypted = File(dir, "upload.ksbackup")
            encrypted.writeBytes(
                KeystoreBackup.create(keystore, "upload.jks", "store-secret", "key-secret", passphrase.toCharArray()),
            )
            val zip = File(dir, "backup.zip")
            val process = ProcessBuilder(
                openssl.path, "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", KeystoreBackup.PBKDF2_ITERATIONS.toString(),
                "-md", "sha256", "-in", encrypted.path, "-out", zip.path, "-pass", "pass:$passphrase",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            assertEquals(0, process.exitValue(), output)

            ZipFile(zip).use { archive ->
                val names = archive.entries().toList().map { it.name }.toSet()
                assertEquals(
                    setOf("README.txt", "upload.jks", "upload_certificate.pem", "keystore.properties", "backup.properties"),
                    names,
                )
                val jks = archive.getInputStream(archive.getEntry("upload.jks")).readBytes()
                assertContentEquals(keystore.bytes, jks)
                val properties = archive.getInputStream(archive.getEntry("keystore.properties")).reader().readText()
                assertTrue("storePassword=store-secret" in properties, properties)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
