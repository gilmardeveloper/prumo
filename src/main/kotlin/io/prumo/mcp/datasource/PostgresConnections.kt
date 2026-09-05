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
 * Abre conexões para os bancos do workspace corrente, uma por operação e sem pool.
 *
 * Conexão de datasource `READ_ONLY` nasce marcada como somente-leitura no driver e na sessão.
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
            throw DataSourceAccessException(
                "Prumo could not connect to data source '${profile.name}': " +
                    ConnectionFailureClassifier.classify(failure.sqlState, failure.message).hint,
            )
        } finally {
            properties.clear()
            Arrays.fill(password, '\u0000')
        }
    }

    /** Empresta uma conexão pelo tempo de uma operação e a fecha em seguida. */
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
        setProperty("password", String(password))
        setProperty("connectTimeout", timeoutSeconds.toString())
        setProperty("loginTimeout", timeoutSeconds.toString())
        setProperty("socketTimeout", (timeoutSeconds * 2).toString())
        setProperty("sslmode", profile.sslMode.parameterValue)
        setProperty("ApplicationName", APPLICATION_NAME)
        profile.defaultSchema?.let { setProperty("currentSchema", it) }
        if (profile.accessMode == AccessMode.READ_ONLY) {
            setProperty("readOnly", "true")
            setProperty("readOnlyMode", "always")
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10
        const val APPLICATION_NAME = "Prumo MCP"
    }
}
