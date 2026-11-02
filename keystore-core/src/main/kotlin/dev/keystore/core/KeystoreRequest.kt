package dev.keystore.core

/** Container format of the generated keystore file. */
enum class KeystoreFormat(val extension: String, val displayName: String) {
    /** Legacy Java KeyStore, the format historically produced by `keytool` and Android Studio. */
    JKS("jks", "JKS"),

    /** PKCS#12, the default `keytool` format since JDK 9. AGP accepts both. */
    PKCS12("p12", "PKCS12"),
}

/** Key algorithm and size used for the signing key. */
enum class KeyAlgorithm(
    val jcaName: String,
    val keySize: Int,
    val signatureAlgorithm: String,
    val displayName: String,
) {
    RSA_2048("RSA", 2048, "SHA256withRSA", "RSA 2048"),
    RSA_3072("RSA", 3072, "SHA256withRSA", "RSA 3072"),
    RSA_4096("RSA", 4096, "SHA256withRSA", "RSA 4096"),
    EC_P256("EC", 256, "SHA256withECDSA", "EC P-256"),
}

/** X.500 distinguished name fields for the self-signed certificate (the `keytool -dname` values). */
data class DistinguishedName(
    val commonName: String = "",
    val organizationalUnit: String = "",
    val organization: String = "",
    val locality: String = "",
    val state: String = "",
    val countryCode: String = "",
) {
    val isEmpty: Boolean
        get() = listOf(commonName, organizationalUnit, organization, locality, state, countryCode)
            .all { it.isBlank() }

    /** RFC 4514 string in the same field order `keytool` uses. */
    fun toX500String(): String = listOf(
        "CN" to commonName,
        "OU" to organizationalUnit,
        "O" to organization,
        "L" to locality,
        "ST" to state,
        "C" to countryCode.uppercase(),
    ).filter { it.second.isNotBlank() }
        .joinToString(", ") { (key, value) -> "$key=${escape(value.trim())}" }

    private fun escape(value: String): String = buildString {
        value.forEachIndexed { index, c ->
            val needsEscape = c in ",+\"\\<>;=" ||
                (index == 0 && (c == '#' || c == ' ')) ||
                (index == value.lastIndex && c == ' ')
            if (needsEscape) append('\\')
            append(c)
        }
    }
}

data class KeystoreRequest(
    val alias: String,
    val storePassword: CharArray,
    /** For [KeystoreFormat.PKCS12] this is ignored and [storePassword] is used for the key. */
    val keyPassword: CharArray,
    val validityYears: Int = 25,
    val keyAlgorithm: KeyAlgorithm = KeyAlgorithm.RSA_2048,
    val format: KeystoreFormat = KeystoreFormat.JKS,
    val distinguishedName: DistinguishedName,
) {
    val effectiveKeyPassword: CharArray
        get() = if (format == KeystoreFormat.PKCS12) storePassword else keyPassword

    /** Overwrites the password buffers. Call once the keystore has been generated. */
    fun wipe() {
        storePassword.fill('\u0000')
        keyPassword.fill('\u0000')
    }

    // CharArray fields make the generated equals/hashCode identity-based; that is fine for this type.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String =
        "KeystoreRequest(alias=$alias, validityYears=$validityYears, keyAlgorithm=$keyAlgorithm, " +
            "format=$format, distinguishedName=$distinguishedName)"
}
