package io.prumo.mcp.datasource

import io.prumo.mcp.datasource.domain.DataSourceProfile
import org.postgresql.Driver
import java.sql.SQLException
import java.util.Locale
import java.util.Properties

/**
 * Resultado do teste de conexão.
 *
 * O usuário recebe o desfecho, nunca a mensagem crua do driver: mensagem de exceção de banco cita
 * host, usuário e, em alguns casos, a URL inteira — e é exatamente o que P5 manda não vazar.
 */
enum class ConnectionTestOutcome {
    SUCCESS,
    AUTHENTICATION_FAILED,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    DATABASE_NOT_FOUND,
    SSL_ERROR,

    /**
     * Falha que não se encaixa nas anteriores.
     *
     * Existe para não vestir de "rede inacessível" um erro que ninguém classificou: um desfecho
     * errado manda o usuário depurar o lugar errado.
     */
    UNEXPECTED_ERROR,
}

/**
 * O que dizer ao usuário sobre cada desfecho.
 *
 * A frase é fixa e fala do que fazer, não do que o driver disse: nenhuma delas cita host, usuário,
 * banco ou qualquer parte da conexão.
 */
val ConnectionTestOutcome.hint: String
    get() = when (this) {
        ConnectionTestOutcome.SUCCESS -> "Connected."
        ConnectionTestOutcome.AUTHENTICATION_FAILED -> "The database refused the user or the password."
        ConnectionTestOutcome.NETWORK_UNREACHABLE -> "The database could not be reached. Check host, port and network access."
        ConnectionTestOutcome.TIMEOUT -> "The database did not answer in time."
        ConnectionTestOutcome.DATABASE_NOT_FOUND -> "The server answered, but that database does not exist."
        ConnectionTestOutcome.SSL_ERROR -> "The TLS handshake failed. Check the SSL mode and the server certificate."
        ConnectionTestOutcome.UNEXPECTED_ERROR -> "The connection failed for a reason Prumo does not recognise. See the IDE log."
    }

/**
 * Traduz a falha do driver em desfecho, sem repassar o texto.
 *
 * Decide por `SQLState` — que é padrão e estável — e recorre à mensagem apenas onde o PostgreSQL
 * não distingue por estado, como no caso de TLS, que chega com o mesmo `08006` de falha de rede.
 */
object ConnectionFailureClassifier {

    private const val INVALID_PASSWORD = "28P01"
    private const val INVALID_AUTHORIZATION = "28000"
    private const val UNDEFINED_DATABASE = "3D000"

    /** O PostgreSQL cancela por timeout com este estado, e a mensagem nao cita tempo algum. */
    private const val QUERY_CANCELED = "57014"
    private const val CONNECTION_CLASS = "08"

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

            // TLS antes de rede: o PostgreSQL reporta falha de TLS com o mesmo estado 08006 de
            // conexão interrompida, e só a mensagem separa os dois casos.
            SSL_MARKERS.any { text.contains(it) } -> ConnectionTestOutcome.SSL_ERROR
            TIMEOUT_MARKERS.any { text.contains(it) } -> ConnectionTestOutcome.TIMEOUT

            state.startsWith(CONNECTION_CLASS) -> ConnectionTestOutcome.NETWORK_UNREACHABLE
            NETWORK_MARKERS.any { text.contains(it) } -> ConnectionTestOutcome.NETWORK_UNREACHABLE

            else -> ConnectionTestOutcome.UNEXPECTED_ERROR
        }
    }
}

/**
 * Abre uma conexão de teste e a fecha em seguida.
 *
 * Nada é consultado: o teste responde se dá para conectar, não o que existe lá dentro. O driver é o
 * do próprio plugin, sem depender do Database Tools, que só existe no Ultimate (F-007).
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
            // O mapa fica sem a senha assim que a conexão termina; a String exigida pela API do
            // driver some com ela.
            properties.clear()
        }
    }

    private fun connectionProperties(profile: DataSourceProfile, password: CharArray?) = Properties().apply {
        setProperty("user", profile.user)
        // A API do JDBC só aceita String aqui. É o único ponto em que o segredo vira String, ele
        // não sai desta função e o mapa é limpo no `finally`.
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
