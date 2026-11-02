package com.onurbaykal.keystoremanager.core

import java.io.File
import java.util.Properties
import java.util.UUID

/** A named set of certificate fields that can be reused for new keystores. */
data class CertificateProfile(
    val id: String,
    val name: String,
    val distinguishedName: DistinguishedName,
)

/**
 * Saves [CertificateProfile]s to a single properties file. Profiles hold only the public
 * certificate subject, so they aren't encrypted.
 */
class CertificateProfileStore(private val file: File) {

    fun list(): List<CertificateProfile> {
        val properties = load()
        return properties.getProperty(ORDER).orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { id -> properties.readProfile(id) }
    }

    /** The profile used most recently, so the form can start pre-filled. */
    fun lastUsed(): CertificateProfile? {
        val properties = load()
        return properties.getProperty(LAST_USED)?.let { properties.readProfile(it) }
    }

    /** Saves a new profile, or replaces the one with the same name (ignoring case). */
    fun save(name: String, distinguishedName: DistinguishedName): CertificateProfile {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name is required" }
        val properties = load()
        val existing = list().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        val profile = CertificateProfile(existing?.id ?: UUID.randomUUID().toString(), trimmed, distinguishedName)

        properties.writeProfile(profile)
        val order = properties.getProperty(ORDER).orEmpty().split(',').filter { it.isNotBlank() }
        if (profile.id !in order) properties.setProperty(ORDER, (order + profile.id).joinToString(","))
        properties.setProperty(LAST_USED, profile.id)
        store(properties)
        return profile
    }

    fun markUsed(id: String) {
        val properties = load()
        if (properties.readProfile(id) == null) return
        properties.setProperty(LAST_USED, id)
        store(properties)
    }

    fun delete(id: String) {
        val properties = load()
        FIELDS.forEach { properties.remove("$id.$it") }
        properties.remove("$id.name")
        val order = properties.getProperty(ORDER).orEmpty().split(',').filter { it.isNotBlank() && it != id }
        properties.setProperty(ORDER, order.joinToString(","))
        if (properties.getProperty(LAST_USED) == id) properties.remove(LAST_USED)
        store(properties)
    }

    private fun Properties.readProfile(id: String): CertificateProfile? {
        val name = getProperty("$id.name") ?: return null
        fun field(key: String) = getProperty("$id.$key").orEmpty()
        return CertificateProfile(
            id = id,
            name = name,
            distinguishedName = DistinguishedName(
                commonName = field("cn"),
                organizationalUnit = field("ou"),
                organization = field("o"),
                locality = field("l"),
                state = field("st"),
                countryCode = field("c"),
            ),
        )
    }

    private fun Properties.writeProfile(profile: CertificateProfile) {
        val dn = profile.distinguishedName
        setProperty("${profile.id}.name", profile.name)
        setProperty("${profile.id}.cn", dn.commonName.trim())
        setProperty("${profile.id}.ou", dn.organizationalUnit.trim())
        setProperty("${profile.id}.o", dn.organization.trim())
        setProperty("${profile.id}.l", dn.locality.trim())
        setProperty("${profile.id}.st", dn.state.trim())
        setProperty("${profile.id}.c", dn.countryCode.trim().uppercase())
    }

    private fun load(): Properties = Properties().apply {
        if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
    }

    private fun store(properties: Properties) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writer(Charsets.UTF_8).use { properties.store(it, "Certificate profiles") }
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not save profiles")
        }
    }

    private companion object {
        const val ORDER = "order"
        const val LAST_USED = "lastUsed"
        val FIELDS = listOf("cn", "ou", "o", "l", "st", "c")
    }
}
