package dev.keystore.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
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
    val compose = createAndroidComposeRule<ComponentActivity>()

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
        // Don't let idling wait on animations such as the text cursor blink.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KeystoreTheme(darkTheme = dark, dynamicColor = false) {
                KeystoreApp(viewModel)
            }
        }
        settle()
        return viewModel
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    /**
     * Compose's captureToImage() waits for a hardware redraw that Robolectric never delivers,
     * so draw the view hierarchy into a software canvas instead.
     */
    private fun render(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun saveScreen(name: String, withDialog: Boolean = false) {
        val bitmap = render(compose.activity.window.decorView)
        if (withDialog) {
            val dialogView = checkNotNull(ShadowDialog.getLatestDialog()?.window?.decorView) { "No dialog shown" }
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.argb(128, 0, 0, 0))
            val dialog = render(dialogView)
            canvas.drawBitmap(
                dialog,
                (bitmap.width - dialog.width) / 2f,
                (bitmap.height - dialog.height) / 2f,
                null,
            )
        }
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        show { }
        saveScreen("01-home")
    }

    @Test
    fun homeDark() {
        show(dark = true) { }
        saveScreen("01-home-dark")
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
        saveScreen("02-generate-form")
    }

    @Test
    @Config(qualifiers = "w400dp-h2900dp-xhdpi")
    fun keystoreDetails() {
        show { open(Screen.Details(details())) }
        saveScreen("03-details")
    }

    @Test
    fun keystoreDetailsDark() {
        show(dark = true) { open(Screen.Details(details())) }
        saveScreen("03-details-dark")
    }

    @Test
    fun backupDialog() {
        show { open(Screen.Details(details())) }
        compose.onNodeWithText("Create encrypted backup").performClick()
        settle()
        saveScreen("04-backup-dialog", withDialog = true)
    }

    @Test
    fun importExisting() {
        show {
            open(Screen.Import)
            updateImport {
                it.copy(fileName = "release-key.jks", bytes = keystore.bytes, storePassword = "secret-pass")
            }
        }
        saveScreen("05-backup-existing")
    }

    @Test
    fun restore() {
        show {
            open(Screen.Restore)
            updateRestore {
                it.copy(fileName = "upload.ksbackup", bytes = byteArrayOf(1), passphrase = "my backup passphrase")
            }
        }
        saveScreen("06-restore")
    }
}
