package dev.keystore.core

import java.util.Base64

/** Copy-paste configuration for using a generated keystore to sign an AAB. */
object SigningSnippets {

    /** Contents of a `keystore.properties` file kept out of version control. */
    fun keystoreProperties(fileName: String, alias: String, storePassword: String, keyPassword: String): String =
        """
        |storeFile=$fileName
        |storePassword=$storePassword
        |keyAlias=$alias
        |keyPassword=$keyPassword
        |""".trimMargin()

    /** `app/build.gradle.kts` signing config that reads [keystoreProperties]. */
    fun gradleKts(format: KeystoreFormat): String =
        """
        |import java.util.Properties
        |
        |val keystoreProperties = Properties().apply {
        |    val file = rootProject.file("keystore.properties")
        |    if (file.exists()) file.inputStream().use { load(it) }
        |}
        |
        |android {
        |    signingConfigs {
        |        create("release") {
        |            storeFile = file(keystoreProperties.getProperty("storeFile"))
        |            storePassword = keystoreProperties.getProperty("storePassword")
        |            keyAlias = keystoreProperties.getProperty("keyAlias")
        |            keyPassword = keystoreProperties.getProperty("keyPassword")
        |            storeType = "${format.displayName.lowercase()}"
        |        }
        |    }
        |    buildTypes {
        |        release {
        |            signingConfig = signingConfigs.getByName("release")
        |        }
        |    }
        |}
        |""".trimMargin()

    /** Equivalent command for signing an already-built bundle by hand. */
    fun jarsignerCommand(fileName: String, alias: String): String =
        "jarsigner -keystore $fileName -signedjar app-release-signed.aab app-release.aab \"$alias\""

    /** Base64 text for storing the keystore as a CI secret (e.g. a GitHub Actions secret). */
    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
