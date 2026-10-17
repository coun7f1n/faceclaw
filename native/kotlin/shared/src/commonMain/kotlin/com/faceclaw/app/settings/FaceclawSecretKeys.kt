package com.faceclaw.app

/** Credential-bearing keys in faceclaw_settings; ordinary preferences stay plain. */
object FaceclawSecretKeys {
    private val keys = setOf(
        "assistant.bridgeToken", "voice.elevenLabsApiKey", "voice.openAiApiKey",
        "voice.sonioxApiKey", "llm.anthropicApiKey", "integrations.roam.apiToken",
        "integrations.nightscout.apiToken", "integrations.evenhub.token",
        "terminal.connections", "terminal.newConnectionDraft",
        "t3code.environments", "t3code.pairingDraft",
        // Cleared by current builds; old installs may still hold a password.
        "integrations.evenhub.password"
    )
    private const val PREFIX = "__faceclaw_secret_v1."
    fun contains(key: String): Boolean = key in keys
    fun all(): Set<String> = keys
    fun cipherKey(key: String): String = PREFIX + key
    fun isInternal(key: String): Boolean = key.startsWith(PREFIX)
}
