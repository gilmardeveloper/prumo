package io.prumo.mcp.credential

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Endereço de uma credencial no cofre.
 *
 * A chave carrega o workspace e o datasource, então o mesmo identificador de datasource em
 * workspaces diferentes endereça credenciais diferentes.
 */
data class CredentialKey(
    val workspaceId: String,
    val datasourceId: String,
) {
    init {
        require(workspaceId.matches(IDENTIFIER)) { "Invalid workspace id '$workspaceId'." }
        require(datasourceId.matches(IDENTIFIER)) { "Invalid datasource id '$datasourceId'." }
    }

    /** Nome de serviço no cofre. Estável: mudá-lo torna invisível toda senha já guardada. */
    val serviceName: String get() = "$SERVICE_PREFIX $workspaceId/$datasourceId"

    private companion object {
        const val SERVICE_PREFIX = "Prumo MCP"
        val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/**
 * Guarda e devolve senhas sem que elas passem por arquivo, log ou auditoria.
 *
 * A senha trafega como `CharArray` para poder ser apagada da memória depois de usada.
 */
interface CredentialProvider {

    fun store(key: CredentialKey, user: String, password: CharArray)

    fun password(key: CredentialKey, user: String): CharArray?

    fun remove(key: CredentialKey, user: String)
}

/** Implementação sobre o PasswordSafe da IDE. */
class PasswordSafeCredentialProvider : CredentialProvider {

    override fun store(key: CredentialKey, user: String, password: CharArray) {
        PasswordSafe.instance.set(attributesFor(key, user), Credentials(user, password))
    }

    override fun password(key: CredentialKey, user: String): CharArray? {
        val stored = PasswordSafe.instance.get(attributesFor(key, user))?.password ?: return null
        return CharArray(stored.length) { stored[it] }
    }

    override fun remove(key: CredentialKey, user: String) {
        PasswordSafe.instance.set(attributesFor(key, user), null)
    }

    private fun attributesFor(key: CredentialKey, user: String) =
        CredentialAttributes(key.serviceName, user)
}
