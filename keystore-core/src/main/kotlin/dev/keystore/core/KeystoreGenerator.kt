package dev.keystore.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Result of [KeystoreGenerator.generate]. */
class GeneratedKeystore(
    val bytes: ByteArray,
    val format: KeystoreFormat,
    val alias: String,
    val certificate: X509Certificate,
) {
    val info: CertificateInfo by lazy { CertificateInfo.from(certificate) }
}

/**
 * Creates an upload/signing keystore equivalent to:
 *
 * ```
 * keytool -genkeypair -keyalg RSA -keysize 2048 -validity 9125 -alias <alias> \
 *         -keystore upload.jks -storetype JKS -dname "CN=..., O=..., C=.."
 * ```
 *
 * Uses the bundled Bouncy Castle provider instance directly rather than the "BC" name, because
 * Android registers its own stripped-down provider under that name.
 */
class KeystoreGenerator(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneOffset.UTC) },
) {
    private val bouncyCastle = BouncyCastleProvider()

    fun generate(request: KeystoreRequest): GeneratedKeystore {
        val errors = KeystoreValidator.validate(request)
        require(errors.isEmpty()) { errors.joinToString("; ") { "${it.field}: ${it.message}" } }

        val keyPair = generateKeyPair(request.keyAlgorithm)
        val certificate = selfSign(keyPair, request)
        val alias = request.alias.trim()

        val bytes = when (request.format) {
            KeystoreFormat.JKS -> JksWriter.write(
                alias = alias,
                privateKey = keyPair.private,
                chain = listOf(certificate),
                storePassword = request.storePassword,
                keyPassword = request.keyPassword,
                creationDate = clock().toInstant().toEpochMilli(),
                random = random,
            )

            KeystoreFormat.PKCS12 -> {
                val store = KeyStore.getInstance("PKCS12", bouncyCastle)
                store.load(null, null)
                store.setKeyEntry(alias, keyPair.private, request.storePassword, arrayOf(certificate))
                ByteArrayOutputStream().use { out ->
                    store.store(out, request.storePassword)
                    out.toByteArray()
                }
            }
        }
        return GeneratedKeystore(bytes, request.format, alias, certificate)
    }

    private fun generateKeyPair(algorithm: KeyAlgorithm): KeyPair {
        val generator = KeyPairGenerator.getInstance(algorithm.jcaName)
        when (algorithm.jcaName) {
            "EC" -> generator.initialize(ECGenParameterSpec("secp256r1"), random)
            else -> generator.initialize(algorithm.keySize, random)
        }
        return generator.generateKeyPair()
    }

    private fun selfSign(keyPair: KeyPair, request: KeystoreRequest): X509Certificate {
        // Encode through X500Principal so the RDN order matches what keytool writes.
        val subject = X500Name.getInstance(X500Principal(request.distinguishedName.toX500String()).encoded)
        val now = clock()
        val notBefore = Date.from(now.toInstant())
        val notAfter = Date.from(now.plusYears(request.validityYears.toLong()).toInstant())
        // keytool uses a random 63-bit positive serial; do the same.
        val serial = BigInteger(63, random)

        val holder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
            .addExtension(
                Extension.subjectKeyIdentifier,
                false,
                JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.public),
            )
            .build(
                JcaContentSignerBuilder(request.keyAlgorithm.signatureAlgorithm)
                    .setProvider(bouncyCastle)
                    .setSecureRandom(random)
                    .build(keyPair.private),
            )
        return JcaX509CertificateConverter().setProvider(bouncyCastle).getCertificate(holder)
    }
}
