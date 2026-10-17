package com.faceclaw.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SecretMigrationTest {
    private class MemoryStore : SecretMigrationStore {
        val plain = mutableMapOf<String, String>()
        val cipher = mutableMapOf<String, String>()
        var writeSucceeds = true
        var removeSucceeds = true
        var keyAvailable = true
        var corruptWrite = false
        override fun plain(key: String): String? = plain[key]
        override fun cipher(key: String): String? = cipher[key]
        override fun writeCipher(key: String, value: String): Boolean {
            if (writeSucceeds) cipher[key] = if (corruptWrite) "damaged" else value
            return writeSucceeds
        }
        override fun removePlain(key: String): Boolean {
            if (removeSucceeds) plain.remove(key)
            return removeSucceeds
        }
        override fun seal(key: String, value: String): String {
            if (!keyAvailable) error("Key unavailable")
            return "$key:$value"
        }
        override fun open(key: String, value: String): String {
            if (!keyAvailable || !value.startsWith("$key:")) error("Key unavailable or invalid")
            return value.removePrefix("$key:")
        }
    }

    @Test fun migratesPlaintextOnlyAfterVerifiedCipherWrite() {
        val store = MemoryStore().apply { plain["token"] = "old" }
        assertEquals("old", SecretMigration(store).read("token"))
        assertNull(store.plain["token"])
        assertEquals("token:old", store.cipher["token"])
    }

    @Test fun failedWriteRetainsPlaintext() {
        val store = MemoryStore().apply { plain["token"] = "old"; writeSucceeds = false }
        assertFailsWith<IllegalStateException> { SecretMigration(store).read("token") }
        assertEquals("old", store.plain["token"])
    }

    @Test fun interruptedCleanupRetriesWithoutReplacingNewerCiphertext() {
        val store = MemoryStore().apply { plain["token"] = "old"; removeSucceeds = false }
        assertFailsWith<IllegalStateException> { SecretMigration(store).write("token", "new") }
        assertEquals("old", store.plain["token"])
        store.removeSucceeds = true
        assertEquals("new", SecretMigration(store).read("token"))
        assertNull(store.plain["token"])
    }

    @Test fun unverifiedCiphertextNeverTriggersPlaintextDeletion() {
        val store = MemoryStore().apply { plain["token"] = "old"; corruptWrite = true }
        assertFailsWith<IllegalStateException> { SecretMigration(store).read("token") }
        assertEquals("old", store.plain["token"])
    }

    @Test fun unavailableKeyNeverFallsBackToPlaintext() {
        val store = MemoryStore().apply {
            plain["token"] = "old"; cipher["token"] = "token:new"; keyAvailable = false
        }
        assertFailsWith<IllegalStateException> { SecretMigration(store).read("token") }
        assertEquals("old", store.plain["token"])
    }

    @Test fun emptySettingDoesNotRequireKey() {
        assertNull(SecretMigration(MemoryStore().apply { keyAvailable = false }).read("token"))
    }

    @Test fun inventoryCoversEmbeddedCredentialsButNotOrdinarySettings() {
        for (name in listOf("assistant.bridgeToken", "integrations.evenhub.token",
            "terminal.connections", "t3code.environments", "t3code.pairingDraft")) {
            assertTrue(FaceclawSecretKeys.contains(name))
        }
        assertFalse(FaceclawSecretKeys.contains("assistant.bridgeHost"))
        assertFalse(FaceclawSecretKeys.contains("maps.mapboxApiKey")) // public pk token
    }
}
