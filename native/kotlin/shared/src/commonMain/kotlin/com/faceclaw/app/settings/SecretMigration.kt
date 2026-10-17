package com.faceclaw.app

/** Crash-safe transition from a plaintext preference to an authenticated ciphertext. */
interface SecretMigrationStore {
    fun plain(key: String): String?
    fun cipher(key: String): String?
    fun writeCipher(key: String, value: String): Boolean
    fun removePlain(key: String): Boolean
    fun seal(key: String, value: String): String
    fun open(key: String, value: String): String
}

class SecretMigration(private val store: SecretMigrationStore) {
    fun read(key: String): String? {
        val plain = store.plain(key)
        val cipher = store.cipher(key)
        if (cipher != null) {
            val verified = store.open(key, cipher) // Invalid/missing key: fail closed.
            if (plain != null) {
                // A prior write reached disk but deletion was interrupted. The
                // ciphertext is authoritative; never overwrite it with stale plaintext.
                if (!store.removePlain(key)) throw IllegalStateException("Secret migration cleanup failed")
            }
            return verified
        }
        if (plain == null) return null
        write(key, plain)
        return plain
    }

    fun write(key: String, value: String) {
        val sealed = store.seal(key, value)
        if (!store.writeCipher(key, sealed)) throw IllegalStateException("Encrypted setting write failed")
        val persisted = store.cipher(key) ?: throw IllegalStateException("Encrypted setting missing after write")
        if (store.open(key, persisted) != value) throw IllegalStateException("Encrypted setting verification failed")
        if (store.plain(key) != null && !store.removePlain(key)) {
            throw IllegalStateException("Secret migration cleanup failed")
        }
    }
}
