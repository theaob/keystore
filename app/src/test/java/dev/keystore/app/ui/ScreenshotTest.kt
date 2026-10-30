package dev.keystore.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.keystore.app.ui.theme.KeystoreTheme
import dev.keystore.core.DistinguishedName
import dev.keystore.core.KeystoreGenerator
import dev.keystore.core.KeystoreRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Renders each screen to `docs/screenshots/` so the UI can be reviewed without a device.
 * Run with `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h840dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outputDir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private val keystore by lazy {
        KeystoreGenerator(clock = { ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, ZoneOffset.UTC) }).generate(
            KeystoreRequest(
                alias = "upload",
                storePassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e".toCharArray(),
                keyPassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e".toCharArray(),
                distinguishedName = DistinguishedName(
                    commonName = "Jane Doe",
                    organization = "Example Apps",
                    locality = "Istanbul",
                    countryCode = "TR",
                ),
            ),
        )
    }

    private fun details() = KeystoreDetails(
        keystore = keystore,
        fileName = "upload.jks",
        storePassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e",
        keyPassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e",
        title = "Keystore ready",
        notice = "The keystore only exists in memory until you save it. Save the file and create an " +
            "encrypted backup: without the keystore and its passwords you can't publish updates, and must " +
            "ask Google Play support to reset your upload key.",
    )

    private fun show(dark: Boolean = false, setUp: KeystoreViewModel.() -> Unit): KeystoreViewModel {
        val viewModel = KeystoreViewModel(RuntimeEnvironment.getApplication())
        viewModel.setUp()
        compose.setContent {
            KeystoreTheme(darkTheme = dark, dynamicColor = false) {
                KeystoreApp(viewModel)
            }
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun SemanticsNodeInteraction.save(name: String) {
        val bitmap = captureToImage().asAndroidBitmap()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        show { }
        compose.onRoot().save("01-home")
    }

    @Test
    fun homeDark() {
        show(dark = true) { }
        compose.onRoot().save("01-home-dark")
    }

    @Test
    @Config(qualifiers = "w400dp-h1900dp-xhdpi")
    fun generateForm() {
        show {
            open(Screen.Generate)
            updateForm {
                it.copy(
                    storePassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e",
                    commonName = "Jane Doe",
                    organization = "Example Apps",
                    locality = "Istanbul",
                    countryCode = "TR",
                )
            }
        }
        compose.onRoot().save("02-generate-form")
    }

    @Test
    @Config(qualifiers = "w400dp-h2900dp-xhdpi")
    fun keystoreDetails() {
        show { open(Screen.Details(details())) }
        compose.onRoot().save("03-details")
    }

    @Test
    fun keystoreDetailsDark() {
        show(dark = true) { open(Screen.Details(details())) }
        compose.onRoot().save("03-details-dark")
    }

    @Test
    fun backupDialog() {
        show { open(Screen.Details(details())) }
        compose.onNodeWithText("Create encrypted backup").performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).save("04-backup-dialog")
    }

    @Test
    fun importExisting() {
        show {
            open(Screen.Import)
            updateImport {
                it.copy(fileName = "release-key.jks", bytes = keystore.bytes, storePassword = "secret-pass")
            }
        }
        compose.onRoot().save("05-backup-existing")
    }

    @Test
    fun restore() {
        show {
            open(Screen.Restore)
            updateRestore {
                it.copy(fileName = "upload.ksbackup", bytes = byteArrayOf(1), passphrase = "my backup passphrase")
            }
        }
        compose.onRoot().save("06-restore")
    }
}
