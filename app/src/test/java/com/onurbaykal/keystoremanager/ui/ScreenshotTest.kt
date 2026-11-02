package com.onurbaykal.keystoremanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.onurbaykal.keystoremanager.ui.theme.KeystoreTheme
import com.onurbaykal.keystoremanager.core.BackupRecord
import com.onurbaykal.keystoremanager.core.CertificateProfileStore
import com.onurbaykal.keystoremanager.core.DistinguishedName
import com.onurbaykal.keystoremanager.core.KeystoreGenerator
import com.onurbaykal.keystoremanager.core.KeystoreFormat
import com.onurbaykal.keystoremanager.core.KeystoreRequest
import com.onurbaykal.keystoremanager.core.KeystoreVault
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
import java.time.Instant
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

    /** Google Play store listing screenshots (fastlane layout). */
    private val playDir =
        File(System.getProperty("playScreenshotDir") ?: "build/play-screenshots").apply { mkdirs() }

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
        notice = "Kept in this app, encrypted and unlocked with your fingerprint or screen lock. It's deleted " +
            "if you uninstall the app or lose this phone, so also save the file and create an encrypted " +
            "backup somewhere else.",
        storedId = "00000000-0000-0000-0000-000000000000",
        backup = BackupRecord("tB7#q2-Lw9.zR4!mK8@xV3c", "upload.ksbackup", Instant.parse("2026-10-30T09:05:00Z")),
    )

    /** A vault with a few entries, using a software RSA key instead of the Android Keystore. */
    private fun sampleVault(): KeystoreVault {
        val wrapper = SoftwareKeyWrapper()
        var now = Instant.parse("2026-10-01T09:00:00Z")
        val dir = kotlin.io.path.createTempDirectory("vault").toFile()
        val vault = KeystoreVault(dir, wrapper, clock = { now })
        vault.save(keystore, "upload.jks", "Xk7-pQ2.mR9!vT4_wZ8@hN3e", "Xk7-pQ2.mR9!vT4_wZ8@hN3e")
        now = now.plusSeconds(86_400)
        val legacy = KeystoreGenerator().generate(
            KeystoreRequest(
                alias = "release",
                storePassword = "legacy-pass".toCharArray(),
                keyPassword = "legacy-pass".toCharArray(),
                format = KeystoreFormat.PKCS12,
                validityYears = 30,
                distinguishedName = DistinguishedName(commonName = "Example Apps", countryCode = "TR"),
            ),
        )
        vault.save(legacy, "release-key.p12", null, null)
        return vault
    }

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

    private fun saveScreen(name: String, withDialog: Boolean = false, directory: File = outputDir) {
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
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun home() {
        show { useVault(sampleVault()) }
        saveScreen("01-home")
    }

    @Test
    fun homeDark() {
        show(dark = true) { useVault(sampleVault()) }
        saveScreen("01-home-dark")
    }

    @Test
    @Config(qualifiers = "w400dp-h1900dp-xhdpi")
    fun generateForm() {
        // Saved profiles; the last one saved is used to pre-fill the form.
        CertificateProfileStore(File(RuntimeEnvironment.getApplication().filesDir, "certificate-profiles.properties"))
            .apply {
                save("Personal", DistinguishedName(commonName = "Jane Doe", countryCode = "TR"))
                save(
                    "Example Apps",
                    DistinguishedName(
                        commonName = "Jane Doe",
                        organization = "Example Apps",
                        locality = "Istanbul",
                        countryCode = "TR",
                    ),
                )
            }
        val viewModel = show {
            open(Screen.Generate)
            updateForm { it.copy(storePassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e") }
        }
        // Profiles load on a background thread.
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.state.value.form.profileId == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
        settle()
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
    // On API 35 Robolectric never lets the dialog window go idle.
    @Config(sdk = [33])
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

    // Google Play phone screenshots: 1080x1920 (9:16), within Play's 2:1 aspect-ratio limit.

    private fun showFormWithProfile(): KeystoreViewModel {
        CertificateProfileStore(File(RuntimeEnvironment.getApplication().filesDir, "certificate-profiles.properties"))
            .save(
                "Example Apps",
                DistinguishedName(
                    commonName = "Jane Doe",
                    organization = "Example Apps",
                    locality = "Istanbul",
                    countryCode = "TR",
                ),
            )
        val viewModel = show {
            open(Screen.Generate)
            updateForm { it.copy(storePassword = "Xk7-pQ2.mR9!vT4_wZ8@hN3e") }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.state.value.form.profileId == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
        settle()
        return viewModel
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun playHome() {
        show { useVault(sampleVault()) }
        saveScreen("1_home", directory = playDir)
    }

    @Test
    @Config(qualifiers = "w360dp-h2400dp-xxhdpi")
    fun playCertificateProfile() {
        showFormWithProfile()
        saveScrolledScreen("2_certificate_profile", anchorText = "Certificate")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun playDetails() {
        show { open(Screen.Details(details())) }
        saveScreen("3_keystore_details", directory = playDir)
    }

    @Test
    @Config(qualifiers = "w360dp-h2400dp-xxhdpi")
    fun playFingerprints() {
        show { open(Screen.Details(details())) }
        saveScrolledScreen("4_fingerprints", anchorText = "Fingerprints")
    }

    /**
     * A phone-sized (1080x1920) screenshot as if scrolled to [anchorText]. Compose's test
     * synchronisation never goes idle after scrolling under Robolectric, so instead the screen is
     * rendered on a tall window and the visible part is cropped out under the app bar.
     */
    private fun saveScrolledScreen(name: String, anchorText: String) {
        val full = render(compose.activity.window.decorView)
        val density = compose.activity.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (640 * density).toInt()
        val appBar = (64 * density).toInt()
        val anchor = findTextTop(anchorText) ?: error("No node with text $anchorText")
        val contentTop = (anchor - 16 * density).toInt().coerceIn(appBar, full.height - (height - appBar))

        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            val content = Rect(0, contentTop, width, contentTop + height - appBar)
            drawBitmap(full, content, Rect(0, appBar, width, height), null)
            drawBitmap(full, Rect(0, 0, width, appBar), Rect(0, 0, width, appBar), null)
        }
        File(playDir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Reads the layout tree directly, without the test framework's idle synchronisation. */
    private fun findTextTop(text: String): Float? {
        fun findRoot(view: View): ViewRootForTest? = when (view) {
            is ViewRootForTest -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findRoot(view.getChildAt(it)) }
            else -> null
        }

        fun search(node: SemanticsNode): SemanticsNode? {
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            if (texts.any { it.text == text }) return node
            return node.children.firstNotNullOfOrNull { search(it) }
        }
        val root = findRoot(compose.activity.window.decorView) ?: return null
        return search(root.semanticsOwner.unmergedRootSemanticsNode)?.boundsInRoot?.top
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun playRestore() {
        show {
            open(Screen.Restore)
            updateRestore {
                it.copy(fileName = "upload.ksbackup", bytes = byteArrayOf(1), passphrase = "my backup passphrase")
            }
        }
        saveScreen("5_restore_backup", directory = playDir)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    fun playHomeDark() {
        show(dark = true) { useVault(sampleVault()) }
        saveScreen("6_home_dark", directory = playDir)
    }
}
