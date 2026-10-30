package dev.keystore.core

/** A field-level problem with a [KeystoreRequest]. */
data class ValidationError(val field: Field, val message: String) {
    enum class Field { ALIAS, STORE_PASSWORD, KEY_PASSWORD, VALIDITY, DISTINGUISHED_NAME, COUNTRY_CODE }
}

/**
 * Applies the same rules `keytool` enforces, plus Google Play's requirement that the
 * certificate stays valid past 22 October 2033.
 */
object KeystoreValidator {
    const val MIN_PASSWORD_LENGTH = 6
    const val MIN_VALIDITY_YEARS = 1
    const val MAX_VALIDITY_YEARS = 100

    /** Play rejects uploads signed with certificates expiring before this year (22 Oct 2033). */
    const val PLAY_MIN_EXPIRY_YEAR = 2033
    const val RECOMMENDED_VALIDITY_YEARS = 25

    fun validate(request: KeystoreRequest): List<ValidationError> = buildList {
        if (request.alias.isBlank()) {
            add(ValidationError(ValidationError.Field.ALIAS, "Alias is required"))
        } else if (request.alias.any { it.isISOControl() }) {
            add(ValidationError(ValidationError.Field.ALIAS, "Alias contains invalid characters"))
        }

        if (request.storePassword.size < MIN_PASSWORD_LENGTH) {
            add(
                ValidationError(
                    ValidationError.Field.STORE_PASSWORD,
                    "Must be at least $MIN_PASSWORD_LENGTH characters",
                ),
            )
        }
        if (request.format == KeystoreFormat.JKS && request.keyPassword.size < MIN_PASSWORD_LENGTH) {
            add(
                ValidationError(
                    ValidationError.Field.KEY_PASSWORD,
                    "Must be at least $MIN_PASSWORD_LENGTH characters",
                ),
            )
        }

        if (request.validityYears !in MIN_VALIDITY_YEARS..MAX_VALIDITY_YEARS) {
            add(
                ValidationError(
                    ValidationError.Field.VALIDITY,
                    "Must be between $MIN_VALIDITY_YEARS and $MAX_VALIDITY_YEARS years",
                ),
            )
        }

        if (request.distinguishedName.isEmpty) {
            add(
                ValidationError(
                    ValidationError.Field.DISTINGUISHED_NAME,
                    "Fill in at least one certificate field",
                ),
            )
        }
        val country = request.distinguishedName.countryCode.trim()
        if (country.isNotEmpty() && !(country.length == 2 && country.all { it in 'A'..'Z' || it in 'a'..'z' })) {
            add(ValidationError(ValidationError.Field.COUNTRY_CODE, "Use a two-letter country code"))
        }
    }

    /** True when the validity period is too short for Google Play uploads. */
    fun expiresTooEarlyForPlay(validityYears: Int, currentYear: Int): Boolean =
        currentYear + validityYears <= PLAY_MIN_EXPIRY_YEAR
}
