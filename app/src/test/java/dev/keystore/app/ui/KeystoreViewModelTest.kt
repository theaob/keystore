package dev.keystore.app.ui

import dev.keystore.core.KeystoreVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreViewModelTest {
    private val dir = kotlin.io.path.createTempDirectory("vault").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Runs main-thread work until [condition] holds; background work happens on real threads. */
    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting until $description")
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    @Test
    fun `a generated keystore is listed on the home screen without restarting`() {
        val viewModel = KeystoreViewModel(RuntimeEnvironment.getApplication())
        viewModel.useVault(KeystoreVault(dir, SoftwareKeyWrapper()))
        viewModel.updateForm { it.copy(storePassword = "store-secret", commonName = "Jane Doe") }

        viewModel.generate()
        waitUntil("the keystore is generated") { viewModel.state.value.screen is Screen.Details }
        val details = (viewModel.state.value.screen as Screen.Details).details
        assertNotNull("generated keystore should be kept in the app", details.storedId)

        viewModel.goHome()
        waitUntil("the home list shows it") { viewModel.state.value.vault.entries.isNotEmpty() }
        val entries = viewModel.state.value.vault.entries
        assertEquals(listOf("upload.jks"), entries.map { it.fileName })
        assertEquals(details.storedId, entries.single().id)
    }
}
