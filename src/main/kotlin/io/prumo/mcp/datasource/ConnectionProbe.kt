package io.prumo.mcp.datasource

import io.prumo.mcp.datasource.domain.DataSourceProfile
import org.postgresql.Driver
import java.sql.SQLException
import java.util.Locale
import java.util.Properties

/**
 * Resultado do teste de conexão.
 *
 * O chamador recebe o desfecho classificado, nunca a mensagem do driver.
 */
enum class ConnectionTestOutcome {
    SUCCESS,
    AUTHENTICATION_FAILED,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    DATABASE_NOT_FOUND,
    SSL_ERROR,

    /** O servidor respondeu e recusou o statement: objeto inexistente, sintaxe ou privilégio. */
    STATEMENT_REJECTED,

    /** Falha que não se encaixa nas anteriores. */
    UNEXPECTED_ERROR,
}

/**
 * Frase fixa para cada desfecho.
 *
 * Fica em inglês porque também é devolvida ao cliente MCP, e não cita host, usuário nem banco.
 */
val ConnectionTestOutcome.hint: String
    get() = when (this) {
        ConnectionTestOutcome.SUCCESS -> "Connected."
        ConnectionTestOutcome.AUTHENTICATION_FAILED -> "The database refused the user or the password."
        ConnectionTestOutcome.NETWORK_UNREACHABLE -> "The database could not be reached. Check host, port and network access."
        ConnectionTestOutcome.TIMEOUT -> "The database did not answer in time."
        ConnectionTestOutcome.DATABASE_NOT_FOUND -> "The server answered, but that database does not exist."
        ConnectionTestOutcome.SSL_ERROR -> "The TLS handshake failed. Check the SSL mode and the server certificate."
        ConnectionTestOutcome.STATEMENT_REJECTED -> "The database rejected the statement."
        ConnectionTestOutcome.UNEXPECTED_ERROR -> "The connection failed for a reason Prumo does not recognise. See the IDE log."
    }

/** Chave do bundle para cada desfecho, usada pela interface. */
val ConnectionTestOutcome.messageKey: String
    get() = when (this) {
        ConnectionTestOutcome.SUCCESS -> "datasource.outcome.success"
        ConnectionTestOutcome.AUTHENTICATION_FAILED -> "datasource.outcome.authenticationFailed"
        ConnectionTestOutcome.NETWORK_UNREACHABLE -> "datasource.outcome.networkUnreachable"
        ConnectionTestOutcome.TIMEOUT -> "datasource.outcome.timeout"
        ConnectionTestOutcome.DATABASE_NOT_FOUND -> "datasource.outcome.databaseNotFound"
        ConnectionTestOutcome.SSL_ERROR -> "datasource.outcome.sslError"
        ConnectionTestOutcome.STATEMENT_REJECTED -> "datasource.outcome.statementRejected"
        ConnectionTestOutcome.UNEXPECTED_ERROR -> "datasource.outcome.unexpected"
    }

/**
 * Traduz a falha do driver em desfecho.
 *
 * Decide por `SQLState` — que é padrão e estável — e recorre à mensagem apenas onde o PostgreSQL
 * não distingue por estado, como no caso de TLS, que chega com o mesmo `08006` de falha de rede.
 * O texto do servidor só é repassado na classe `42`, por [ConnectionFailureClassifier.statementError].
 */
object ConnectionFailureClassifier {

    private const val INVALID_PASSWORD = "28P01"
    private const val INVALID_AUTHORIZATION = "28000"
    private const val UNDEFINED_DATABASE = "3D000"

    /** O PostgreSQL cancela por timeout com este estado, e a mensagem nao cita tempo algum. */
    private const val QUERY_CANCELED = "57014"
    private const val CONNECTION_CLASS = "08"

    /** Sintaxe inválida, objeto inexistente e privilégio insuficiente compartilham esta classe. */
    private const val SYNTAX_OR_ACCESS_CLASS = "42"

    private const val MAX_STATEMENT_ERROR_LENGTH = 500
    private val WHITESPACE = Regex("""\s+""")

    private val SSL_MARKERS = listOf("ssl", "certificate", "pkix", "tls")
    private val TIMEOUT_MARKERS = listOf("timeout", "timed out", "canceling statement")
    private val NETWORK_MARKERS = listOf(
        "connection refused", "unknownhost", "unknown host", "no route to host",
        "network is unreachable", "connection reset",
    )

    fun classify(sqlState: String?, message: String?): ConnectionTestOutcome {
        val state = sqlState.orEmpty()
        val text = message.orEmpty().lowercase(Locale.ROOT)

        return when {
            state == INVALID_PASSWORD || state == INVALID_AUTHORIZATION -> ConnectionTestOutcome.AUTHENTICATION_FAILED
            state == UNDEFINED_DATABASE -> ConnectionTestOutcome.DATABASE_NOT_FOUND
            state == QUERY_CANCELED -> ConnectionTestOutcome.TIMEOUT

            // Precede os marcadores de texto: a mensagem da classe 42 cita o objeto da consulta, e
            // uma coluna chamada "certificate" ou "timeout" cairia no marcador errado.
            state.startsWith(SYNTAX_OR_ACCESS_CLASS) -> ConnectionTestOutcome.STATEMENT_REJECTED

            // O PostgreSQL reporta falha de TLS com o mesmo SQLState 08006 da rede; só a mensagem separa.
            SSL_MARKERS.any { text.contains(it) } -> ConnectionTestOutcome.SSL_ERROR
            TIMEOUT_MARKERS.any { text.contains(it) } -> ConnectionTestOutcome.TIMEOUT

            state.startsWith(CONNECTION_CLASS) -> ConnectionTestOutcome.NETWORK_UNREACHABLE
            NETWORK_MARKERS.any { text.contains(it) } -> ConnectionTestOutcome.NETWORK_UNREACHABLE

            else -> ConnectionTestOutcome.UNEXPECTED_ERROR
        }
    }

    /**
     * Reduz a mensagem do servidor a uma linha, para devolvê-la a quem chamou.
     *
     * Aplica-se apenas a [ConnectionTestOutcome.STATEMENT_REJECTED], cujo texto descreve o objeto ou
     * a sintaxe recusada e não cita host, usuário nem banco. Mensagem em branco recai na frase fixa
     * do desfecho; texto acima de [MAX_STATEMENT_ERROR_LENGTH] caracteres é truncado.
     */
    fun statementError(message: String?): String {
        val text = message.orEmpty().replace(WHITESPACE, " ").trim()
        return when {
            text.isEmpty() -> ConnectionTestOutcome.STATEMENT_REJECTED.hint
            text.length > MAX_STATEMENT_ERROR_LENGTH -> text.take(MAX_STATEMENT_ERROR_LENGTH) + "…"
            else -> text
        }
    }
}

/**
 * Abre uma conexão de teste e a fecha em seguida.
 *
 * Nada é consultado: o teste responde se dá para conectar. O driver é o do próprio plugin.
 */
class PostgresConnectionProbe(
    private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {

    fun test(profile: DataSourceProfile, password: CharArray?): ConnectionTestOutcome {
        val properties = connectionProperties(profile, password)
        return try {
            Driver().connect(profile.jdbcUrl(), properties)?.use { connection ->
                if (connection.isValid(timeoutSeconds)) {
                    ConnectionTestOutcome.SUCCESS
                } else {
                    ConnectionTestOutcome.UNEXPECTED_ERROR
                }
            } ?: ConnectionTestOutcome.UNEXPECTED_ERROR
        } catch (failure: SQLException) {
            ConnectionFailureClassifier.classify(failure.sqlState, failure.message)
        } finally {
            properties.clear()
        }
    }

    private fun connectionProperties(profile: DataSourceProfile, password: CharArray?) = Properties().apply {
        setProperty("user", profile.user)
        password?.let { setProperty("password", String(it)) }
        setProperty("connectTimeout", timeoutSeconds.toString())
        setProperty("loginTimeout", timeoutSeconds.toString())
        setProperty("socketTimeout", timeoutSeconds.toString())
        setProperty("sslmode", profile.sslMode.parameterValue)
        setProperty("ApplicationName", APPLICATION_NAME)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 8
        const val APPLICATION_NAME = "Prumo MCP"
    }
}
