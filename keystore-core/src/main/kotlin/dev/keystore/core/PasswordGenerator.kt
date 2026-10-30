package dev.keystore.core

import java.security.SecureRandom

/** Generates strong passwords that are safe to paste into `.properties` files and shell commands. */
object PasswordGenerator {
    // No quotes, backslashes, '=', ':' or '#', which need escaping in properties files.
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_.!@%"

    fun generate(length: Int = 24, random: SecureRandom = SecureRandom()): CharArray {
        require(length >= KeystoreValidator.MIN_PASSWORD_LENGTH) { "Password too short" }
        return CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] }
    }
}
