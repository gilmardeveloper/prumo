package io.prumo.mcp.credential

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Endereço de uma credencial no cofre.
 *
 * A chave carrega o workspace **e** o datasource: duas configurações com o mesmo identificador de
 * datasource em workspaces diferentes são credenciais diferentes, e nenhuma alcança a outra (P3).
 */
data class CredentialKey(
    val workspaceId: String,
    val datasourceId: String,
) {
    init {
        require(workspaceId.matches(IDENTIFIER)) { "Invalid workspace id '$workspaceId'." }
        require(datasourceId.matches(IDENTIFIER)) { "Invalid datasource id '$datasourceId'." }
    }

    /** Nome de serviço no cofre. Estável: mudá-lo tornaria invisível toda senha já guardada. */
    val serviceName: String get() = "$SERVICE_PREFIX $workspaceId/$datasourceId"

    private companion object {
        const val SERVICE_PREFIX = "Prumo MCP"
        val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

/**
 * Guarda e devolve senhas sem que elas passem por arquivo, log ou auditoria (P5).
 *
 * É interface por dois motivos: o núcleo não precisa da IDE para ser testado, e a implementação de
 * teste jamais toca o cofre real da máquina de quem roda a suíte.
 *
 * A senha trafega como `CharArray` porque `String` fica no pool da JVM até o coletor decidir
 * recolher, e não há como apagá-la antes disso.
 */
interface CredentialProvider {

    fun store(key: CredentialKey, user: String, password: CharArray)

    fun password(key: CredentialKey, user: String): CharArray?

    fun remove(key: CredentialKey, user: String)
}

/**
 * Implementação sobre o PasswordSafe da IDE — mesmo comportamento em Windows, Linux e macOS, com o
 * cofre nativo quando existe.
 */
class PasswordSafeCredentialProvider : CredentialProvider {

    override fun store(key: CredentialKey, user: String, password: CharArray) {
        PasswordSafe.instance.set(attributesFor(key, user), Credentials(user, password))
    }

    override fun password(key: CredentialKey, user: String): CharArray? {
        val stored = PasswordSafe.instance.get(attributesFor(key, user))?.password ?: return null
        // Copia caractere a caractere em vez de `toString`: a conversão criaria uma String com o
        // segredo, que não pode ser apagada da memória.
        return CharArray(stored.length) { stored[it] }
    }

    override fun remove(key: CredentialKey, user: String) {
        PasswordSafe.instance.set(attributesFor(key, user), null)
    }

    private fun attributesFor(key: CredentialKey, user: String) =
        CredentialAttributes(key.serviceName, user)
}
