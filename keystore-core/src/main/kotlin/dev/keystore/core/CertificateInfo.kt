package dev.keystore.core

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/** The details `keytool -list -v` prints, which Play Console, Firebase and Google APIs ask for. */
data class CertificateInfo(
    val subject: String,
    val serialNumberHex: String,
    val notBefore: Instant,
    val notAfter: Instant,
    val signatureAlgorithm: String,
    val publicKeyAlgorithm: String,
    val md5: String,
    val sha1: String,
    val sha256: String,
    /** PEM encoding, i.e. what `keytool -export -rfc` writes (Play's "upload certificate"). */
    val pem: String,
) {
    companion object {
        fun from(certificate: X509Certificate): CertificateInfo {
            val der = certificate.encoded
            return CertificateInfo(
                subject = certificate.subjectX500Principal.name,
                serialNumberHex = certificate.serialNumber.toString(16),
                notBefore = certificate.notBefore.toInstant(),
                notAfter = certificate.notAfter.toInstant(),
                signatureAlgorithm = certificate.sigAlgName,
                publicKeyAlgorithm = certificate.publicKey.algorithm,
                md5 = fingerprint("MD5", der),
                sha1 = fingerprint("SHA-1", der),
                sha256 = fingerprint("SHA-256", der),
                pem = toPem(der),
            )
        }

        fun fingerprint(algorithm: String, data: ByteArray): String =
            MessageDigest.getInstance(algorithm).digest(data)
                .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

        private fun toPem(der: ByteArray): String {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
        }
    }
}
