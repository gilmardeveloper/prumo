package io.prumo.mcp.datasource

import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.workspace.domain.AccessMode
import org.postgresql.Driver
import java.sql.Connection
import java.sql.SQLException
import java.util.Arrays
import java.util.Properties

/** Falha esperada ao alcançar um banco do workspace. A mensagem nunca carrega dado de conexão. */
class DataSourceAccessException(message: String) : IllegalStateException(message)

/**
 * Abre conexões para os bancos do workspace corrente.
 *
 * **Sem pool, de propósito.** Um pool manteria conexões autenticadas vivas entre chamadas, o que
 * significa segredo em memória por mais tempo e estado de sessão sobrevivendo de uma consulta para
 * a outra — os dois contrariam o desenho. Introspecção e consulta são operações sob demanda; abrir
 * e fechar por chamada custa uma ida ao banco e devolve isolamento.
 *
 * Conexão de datasource `READ_ONLY` nasce marcada como somente-leitura no próprio driver: é a
 * segunda camada da defesa da seção 9, independente da credencial de leitura, que é a primeira.
 */
class PostgresConnectionFactory(
    private val credentials: CredentialProvider,
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {

    fun open(workspaceId: String, profile: DataSourceProfile): Connection {
        val password = credentials.password(CredentialKey(workspaceId, profile.id), profile.user)
            ?: throw DataSourceAccessException(
                "No password is stored for data source '${profile.name}'. " +
                    "Open the Prumo workspace editor and save it again.",
            )

        val properties = properties(profile, password)
        return try {
            val connection = Driver().connect(profile.jdbcUrl(), properties)
                ?: throw DataSourceAccessException("Data source '${profile.name}' is not a PostgreSQL connection.")
            connection.applyAccessMode(profile.accessMode)
            connection
        } catch (failure: SQLException) {
            // A mensagem do driver cita host e usuário: o cliente recebe o desfecho classificado.
            throw DataSourceAccessException(
                "Prumo could not connect to data source '${profile.name}': " +
                    ConnectionFailureClassifier.classify(failure.sqlState, failure.message).hint,
            )
        } finally {
            properties.clear()
            Arrays.fill(password, ' ')
        }
    }

    /**
     * Empresta uma conexão pelo tempo de uma operação e a fecha em seguida, aconteça o que
     * acontecer — conexão vazada é sessão aberta no banco do usuário.
     */
    fun <T> withConnection(workspaceId: String, profile: DataSourceProfile, block: (Connection) -> T): T =
        open(workspaceId, profile).use { connection ->
            try {
                block(connection)
            } catch (failure: SQLException) {
                throw DataSourceAccessException(
                    "Data source '${profile.name}' refused the operation: " +
                        ConnectionFailureClassifier.classify(failure.sqlState, failure.message).hint,
                )
            }
        }

    private fun Connection.applyAccessMode(accessMode: AccessMode) {
        if (accessMode == AccessMode.READ_ONLY) {
            isReadOnly = true
        }
        autoCommit = true
    }

    private fun properties(profile: DataSourceProfile, password: CharArray) = Properties().apply {
        setProperty("user", profile.user)
        // Único ponto em que o segredo vira String, exigência da API do JDBC. Não sai daqui e o
        // mapa é limpo no `finally`.
        setProperty("password", String(password))
        setProperty("connectTimeout", timeoutSeconds.toString())
        setProperty("loginTimeout", timeoutSeconds.toString())
        setProperty("socketTimeout", (timeoutSeconds * 2).toString())
        setProperty("sslmode", profile.sslMode.parameterValue)
        setProperty("ApplicationName", APPLICATION_NAME)
        profile.defaultSchema?.let { setProperty("currentSchema", it) }
        if (profile.accessMode == AccessMode.READ_ONLY) {
            // Terceira camada: mesmo que a credencial tenha permissão de escrita e o driver seja
            // contornado, a sessão inteira recusa qualquer statement que escreva.
            setProperty("readOnly", "true")
            setProperty("readOnlyMode", "always")
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10
        const val APPLICATION_NAME = "Prumo MCP"
    }
}
