package dev.keystore.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeystoreReaderTest {
    private val generator = KeystoreGenerator()

    private fun generate(format: KeystoreFormat, algorithm: KeyAlgorithm = KeyAlgorithm.RSA_2048) =
        generator.generate(
            KeystoreRequest(
                alias = "upload",
                storePassword = "store-secret".toCharArray(),
                keyPassword = "key-secret".toCharArray(),
                keyAlgorithm = algorithm,
                format = format,
                distinguishedName = DistinguishedName(commonName = "Test"),
            ),
        )

    private fun assertFails(reason: KeystoreLoadException.Reason, block: () -> Unit) {
        assertEquals(reason, assertFailsWith<KeystoreLoadException> { block() }.reason)
    }

    @Test
    fun `reads generated JKS and checks both passwords`() {
        for (algorithm in listOf(KeyAlgorithm.RSA_2048, KeyAlgorithm.EC_P256)) {
            val generated = generate(KeystoreFormat.JKS, algorithm)
            val loaded = KeystoreReader.load(generated.bytes, "store-secret".toCharArray(), "key-secret".toCharArray())
            assertEquals("upload", loaded.alias)
            assertEquals(KeystoreFormat.JKS, loaded.format)
            assertEquals(generated.certificate, loaded.certificate)

            assertFails(KeystoreLoadException.Reason.WRONG_STORE_PASSWORD) {
                KeystoreReader.load(generated.bytes, "nope-nope".toCharArray(), "key-secret".toCharArray())
            }
            assertFails(KeystoreLoadException.Reason.WRONG_KEY_PASSWORD) {
                KeystoreReader.load(generated.bytes, "store-secret".toCharArray(), "nope-nope".toCharArray())
            }
        }
    }

    @Test
    fun `reads generated PKCS12`() {
        val generated = generate(KeystoreFormat.PKCS12)
        val loaded = KeystoreReader.load(generated.bytes, "store-secret".toCharArray())
        assertEquals(KeystoreFormat.PKCS12, loaded.format)
        assertEquals(generated.certificate, loaded.certificate)
        assertFails(KeystoreLoadException.Reason.WRONG_STORE_PASSWORD) {
            KeystoreReader.load(generated.bytes, "nope-nope".toCharArray())
        }
    }

    @Test
    fun `rejects files that are not keystores`() {
        assertFails(KeystoreLoadException.Reason.NOT_A_KEYSTORE) {
            KeystoreReader.load("hello world".toByteArray(), "store-secret".toCharArray())
        }
    }

    @Test
    fun `reads keystores created by keytool`() {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        if (!keytool.canExecute()) return
        val dir = kotlin.io.path.createTempDirectory("keytool").toFile()
        try {
            for ((type, keyPass) in listOf("JKS" to "key-secret", "PKCS12" to "store-secret")) {
                val file = File(dir, "upload.$type")
                val process = ProcessBuilder(
                    keytool.path, "-genkeypair", "-keystore", file.path, "-storetype", type,
                    "-storepass", "store-secret", "-keypass", keyPass, "-alias", "MyKey",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-dname", "CN=Keytool, C=US",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(60, TimeUnit.SECONDS)
                assertEquals(0, process.exitValue(), output)

                val loaded = KeystoreReader.load(file.readBytes(), "store-secret".toCharArray(), keyPass.toCharArray())
                assertEquals(type, loaded.format.name)
                assertEquals("mykey", loaded.alias.lowercase())
                assertEquals("CN=Keytool,C=US", loaded.info.subject)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
