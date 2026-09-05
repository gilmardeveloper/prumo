package io.prumo.mcp.datasource

import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider

/** Cofre de teste: guarda em memória e nunca toca o PasswordSafe da máquina de quem roda a suíte. */
internal class InMemoryCredentialProvider : CredentialProvider {

    private val entries = mutableMapOf<String, CharArray>()

    override fun store(key: CredentialKey, user: String, password: CharArray) {
        entries["${key.serviceName}|$user"] = password.copyOf()
    }

    override fun password(key: CredentialKey, user: String): CharArray? =
        entries["${key.serviceName}|$user"]?.copyOf()

    override fun remove(key: CredentialKey, user: String) {
        entries.remove("${key.serviceName}|$user")
    }
}
