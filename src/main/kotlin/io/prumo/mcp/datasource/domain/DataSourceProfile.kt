package io.prumo.mcp.datasource.domain

import io.prumo.mcp.workspace.domain.AccessMode
import kotlinx.serialization.Serializable

/** Modo TLS da conexão, com os mesmos nomes que o PostgreSQL usa. */
@Serializable
enum class SslMode {
    DISABLE,
    ALLOW,
    PREFER,
    REQUIRE,
    VERIFY_CA,
    VERIFY_FULL;

    val parameterValue: String get() = name.lowercase().replace('_', '-')
}

/**
 * Um banco que o workspace corrente pode consultar.
 *
 * **Não existe campo de senha aqui, e nunca deve existir.** Este objeto é serializado em disco; o
 * segredo mora apenas no cofre da IDE, endereçado pela chave do workspace mais a do datasource
 * (P5). Um teste falha se aparecer propriedade com cara de segredo.
 *
 * O modo de acesso nasce `READ_ONLY` e é apenas a primeira das camadas de proteção: a barreira real
 * é a credencial de leitura no próprio banco (seção 9 do prompt mestre).
 */
@Serializable
data class DataSourceProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val database: String,
    val user: String,
    val accessMode: AccessMode = AccessMode.READ_ONLY,
    val sslMode: SslMode = SslMode.PREFER,
    val defaultSchema: String? = null,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid datasource id '$id'." }
        require(name.isNotBlank()) { "Datasource name must not be blank." }
        require(host.isNotBlank()) { "Datasource host must not be blank." }
        require(database.isNotBlank()) { "Datasource database must not be blank." }
        require(user.isNotBlank()) { "Datasource user must not be blank." }
        require(port in 1..65535) { "Invalid datasource port '$port'." }
    }

    val writable: Boolean get() = accessMode == AccessMode.READ_WRITE

    /**
     * URL de conexão sem usuário e sem senha: as credenciais viajam em `Properties`, nunca na URL.
     * URL com segredo acaba em log, em mensagem de exceção e em captura de tela.
     */
    fun jdbcUrl(): String = "jdbc:postgresql://$host:$port/$database"

    companion object {
        const val DEFAULT_PORT = 5432
        private val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
