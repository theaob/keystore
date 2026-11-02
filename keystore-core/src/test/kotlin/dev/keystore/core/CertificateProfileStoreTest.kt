package dev.keystore.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CertificateProfileStoreTest {
    private val dir = kotlin.io.path.createTempDirectory("profiles").toFile()
    private val file = File(dir, "profiles.properties")

    private val company = DistinguishedName(
        commonName = "Jane Doe",
        organization = "Example, Inc.",
        locality = "İstanbul",
        countryCode = "tr",
    )

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `profiles persist in order and remember the last used`() {
        val store = CertificateProfileStore(file)
        assertEquals(emptyList(), store.list())
        assertNull(store.lastUsed())

        val work = store.save("Work", company)
        val personal = store.save("Personal", DistinguishedName(commonName = "Jane Doe", countryCode = "US"))

        // A fresh instance reads the same file, as after an app restart.
        val reopened = CertificateProfileStore(file)
        assertEquals(listOf("Work", "Personal"), reopened.list().map { it.name })
        assertEquals(company.copy(countryCode = "TR"), reopened.list().first().distinguishedName)
        assertEquals(personal.id, reopened.lastUsed()?.id)

        reopened.markUsed(work.id)
        assertEquals(work.id, CertificateProfileStore(file).lastUsed()?.id)
    }

    @Test
    fun `saving the same name replaces it, delete removes it`() {
        val store = CertificateProfileStore(file)
        val first = store.save("Work", company)
        val updated = store.save(" work ", company.copy(organizationalUnit = "Mobile"))
        assertEquals(first.id, updated.id)
        assertEquals(1, store.list().size)
        assertEquals("Mobile", store.list().single().distinguishedName.organizationalUnit)

        store.delete(first.id)
        assertEquals(emptyList(), store.list())
        assertNull(store.lastUsed())
    }
}
