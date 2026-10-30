package dev.keystore.core

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeystoreGeneratorTest {
    private val dname = DistinguishedName(
        commonName = "Jane Doe",
        organizationalUnit = "Mobile",
        organization = "Example, Inc.",
        locality = "Istanbul",
        state = "Istanbul",
        countryCode = "tr",
    )

    private fun request(
        format: KeystoreFormat,
        algorithm: KeyAlgorithm = KeyAlgorithm.RSA_2048,
        keyPassword: String = "key-secret",
    ) = KeystoreRequest(
        alias = "Upload",
        storePassword = "store-secret".toCharArray(),
        keyPassword = keyPassword.toCharArray(),
        validityYears = 25,
        keyAlgorithm = algorithm,
        format = format,
        distinguishedName = dname,
    )

    @Test
    fun `JKS output loads with the JDK's own JKS implementation`() {
        for (algorithm in listOf(KeyAlgorithm.RSA_2048, KeyAlgorithm.EC_P256)) {
            val generated = KeystoreGenerator().generate(request(KeystoreFormat.JKS, algorithm))

            val store = KeyStore.getInstance("JKS")
            store.load(ByteArrayInputStream(generated.bytes), "store-secret".toCharArray())

            assertEquals(listOf("upload"), store.aliases().toList())
            val key = store.getKey("upload", "key-secret".toCharArray()) as PrivateKey
            val cert = store.getCertificate("upload") as X509Certificate
            assertEquals(generated.certificate, cert)
            assertSignsAndVerifies(key, cert, algorithm)
        }
    }

    @Test
    fun `JKS rejects wrong store password`() {
        val generated = KeystoreGenerator().generate(request(KeystoreFormat.JKS))
        val store = KeyStore.getInstance("JKS")
        assertFailsWith<java.io.IOException> {
            store.load(ByteArrayInputStream(generated.bytes), "wrong-password".toCharArray())
        }
    }

    @Test
    fun `PKCS12 output loads with the JDK`() {
        val generated = KeystoreGenerator().generate(request(KeystoreFormat.PKCS12))

        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(generated.bytes), "store-secret".toCharArray())
        val alias = store.aliases().toList().single()
        assertEquals("upload", alias.lowercase())
        val key = store.getKey(alias, "store-secret".toCharArray()) as PrivateKey
        assertSignsAndVerifies(key, store.getCertificate(alias) as X509Certificate, KeyAlgorithm.RSA_2048)
    }

    @Test
    fun `certificate matches requested subject and validity`() {
        val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val generated = KeystoreGenerator(clock = { now }).generate(request(KeystoreFormat.JKS))
        val info = generated.info

        assertEquals("CN=Jane Doe,OU=Mobile,O=Example\\, Inc.,L=Istanbul,ST=Istanbul,C=TR", info.subject)
        assertEquals(now.toInstant(), info.notBefore)
        assertEquals(now.plusYears(25).toInstant(), info.notAfter)
        assertEquals("SHA256WITHRSA", info.signatureAlgorithm.uppercase())
        assertEquals(32, info.sha256.split(":").size)
        assertEquals(20, info.sha1.split(":").size)
        assertTrue(info.pem.startsWith("-----BEGIN CERTIFICATE-----"))
    }

    @Test
    fun `validation catches keytool rule violations`() {
        val bad = KeystoreRequest(
            alias = " ",
            storePassword = "123".toCharArray(),
            keyPassword = "123".toCharArray(),
            validityYears = 0,
            distinguishedName = DistinguishedName(countryCode = "TUR"),
        )
        val fields = KeystoreValidator.validate(bad).map { it.field }.toSet()
        assertEquals(ValidationError.Field.entries.toSet() - ValidationError.Field.DISTINGUISHED_NAME, fields)
        assertFailsWith<IllegalArgumentException> { KeystoreGenerator().generate(bad) }

        val empty = KeystoreValidator.validate(request(KeystoreFormat.JKS).copy(distinguishedName = DistinguishedName()))
        assertEquals(listOf(ValidationError.Field.DISTINGUISHED_NAME), empty.map { it.field })
    }

    @Test
    fun `play expiry rule`() {
        assertTrue(KeystoreValidator.expiresTooEarlyForPlay(validityYears = 5, currentYear = 2026))
        assertTrue(!KeystoreValidator.expiresTooEarlyForPlay(validityYears = 25, currentYear = 2026))
    }

    @Test
    fun `keytool and jarsigner accept the generated JKS`() {
        val javaHome = File(System.getProperty("java.home"))
        val keytool = File(javaHome, "bin/keytool")
        val jarsigner = File(javaHome, "bin/jarsigner")
        if (!keytool.canExecute() || !jarsigner.canExecute()) return

        val dir = kotlin.io.path.createTempDirectory("jks-test").toFile()
        try {
            val jks = File(dir, "upload.jks")
            jks.writeBytes(KeystoreGenerator().generate(request(KeystoreFormat.JKS)).bytes)

            val listing = run(dir, keytool.path, "-list", "-v", "-keystore", jks.path, "-storepass", "store-secret")
            assertTrue("Alias name: upload" in listing, listing)
            assertTrue("PrivateKeyEntry" in listing, listing)

            // An AAB is a zip; jarsigner is what AGP's bundle signing is equivalent to.
            val bundle = File(dir, "app.aab")
            java.util.zip.ZipOutputStream(bundle.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("base/manifest/AndroidManifest.xml"))
                zip.write("<manifest/>".toByteArray())
                zip.closeEntry()
            }
            run(
                dir, jarsigner.path, "-keystore", jks.path, "-storepass", "store-secret",
                "-keypass", "key-secret", bundle.path, "upload",
            )
            val verify = run(dir, jarsigner.path, "-verify", bundle.path)
            assertTrue("jar verified" in verify, verify)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(dir: File, vararg command: String): String {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private fun assertSignsAndVerifies(key: PrivateKey, cert: X509Certificate, algorithm: KeyAlgorithm) {
        val data = "hello".toByteArray()
        val signature = Signature.getInstance(algorithm.signatureAlgorithm).run {
            initSign(key)
            update(data)
            sign()
        }
        val ok = Signature.getInstance(algorithm.signatureAlgorithm).run {
            initVerify(cert)
            update(data)
            verify(signature)
        }
        assertTrue(ok)
    }
}
