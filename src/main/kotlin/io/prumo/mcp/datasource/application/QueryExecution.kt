package io.prumo.mcp.datasource.application

import io.prumo.mcp.datasource.DataSourceAccessException
import io.prumo.mcp.datasource.PostgresConnectionFactory
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.security.DataMaskingPolicy
import io.prumo.mcp.datasource.security.SqlClassification
import io.prumo.mcp.datasource.security.SqlStatementClassifier
import io.prumo.mcp.datasource.security.SqlStatementType
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** Recusa de uma consulta antes de ela chegar ao banco. Carrega o motivo, nunca o SQL. */
class QueryRefusedException(
    val statementType: SqlStatementType,
    message: String,
) : IllegalArgumentException(message)

data class QueryColumn(
    val name: String,
    val type: String,
    val masked: Boolean,
)

data class QueryOutcome(
    val statementType: SqlStatementType,
    val columns: List<QueryColumn>,
    val rows: List<List<String?>>,
    val rowCount: Int,
    val truncated: Boolean,
    val durationMillis: Long,
)

/**
 * Executa consulta de leitura com as camadas da seção 9 que cabem ao plugin.
 *
 * 1. A credencial somente-leitura no banco é a barreira principal — e é do usuário, não daqui.
 * 2. A transação é aberta como `READ ONLY` **sempre**, mesmo quando o datasource está marcado como
 *    `READ_WRITE`: esta operação é de leitura por definição, e é o servidor que passa a recusar
 *    qualquer escrita, inclusive `SELECT … FOR UPDATE`, que trava linha.
 * 3. O statement é classificado antes de sair daqui.
 * 4. O número de linhas tem teto, e a resposta diz quando cortou.
 * 5. Há timeout de consulta.
 * 6. A auditoria fica com quem chama, e registra tipo e contagem — nunca os dados.
 *
 * Nada é commitado: a transação termina em `rollback`, então nem um efeito colateral acidental
 * sobrevive à chamada.
 */
class ReadOnlyQueryExecutor(
    private val connections: PostgresConnectionFactory,
    private val queryTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {

    fun execute(
        workspaceId: String,
        profile: DataSourceProfile,
        sql: String,
        maxRows: Int = DEFAULT_MAX_ROWS,
        masking: DataMaskingPolicy = DataMaskingPolicy.NONE,
    ): QueryOutcome {
        val classification = SqlStatementClassifier.classify(sql)
        if (classification is SqlClassification.Denied) {
            throw QueryRefusedException(classification.type, classification.reason)
        }

        val limit = maxRows.coerceIn(1, MAX_ROWS_CEILING)
        val startedAt = System.nanoTime()

        return connections.withConnection(workspaceId, profile) { connection ->
            connection.inReadOnlyTransaction {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = queryTimeoutSeconds
                    statement.maxRows = limit + 1
                    statement.executeQuery(sql).use { rows ->
                        read(rows, limit, masking, classification.type, startedAt)
                    }
                }
            }
        }
    }

    /**
     * A transação nasce somente-leitura e morre em `rollback`.
     *
     * `SET TRANSACTION READ ONLY` precisa ser o primeiro comando da transação; por isso o
     * `autoCommit` é desligado logo antes.
     */
    private fun <T> Connection.inReadOnlyTransaction(block: () -> T): T {
        val previousAutoCommit = autoCommit
        autoCommit = false
        return try {
            createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
            block()
        } finally {
            runCatching { rollback() }
            runCatching { autoCommit = previousAutoCommit }
        }
    }

    private fun read(
        rows: ResultSet,
        limit: Int,
        masking: DataMaskingPolicy,
        statementType: SqlStatementType,
        startedAt: Long,
    ): QueryOutcome {
        val metadata = rows.metaData
        val columns = (1..metadata.columnCount).map { index ->
            val name = metadata.getColumnLabel(index) ?: metadata.getColumnName(index)
            QueryColumn(
                name = name,
                type = metadata.getColumnTypeName(index) ?: "unknown",
                masked = masking.ruleFor(name) != io.prumo.mcp.datasource.security.MaskingRule.ALLOW,
            )
        }

        val values = mutableListOf<List<String?>>()
        var truncated = false
        while (rows.next()) {
            if (values.size == limit) {
                truncated = true
                break
            }
            values.add(
                columns.mapIndexed { index, column ->
                    masking.apply(column.name, cellOf(rows, index + 1, metadata.getColumnType(index + 1)))
                },
            )
        }

        return QueryOutcome(
            statementType = statementType,
            columns = columns,
            rows = values,
            rowCount = values.size,
            truncated = truncated,
            durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /**
     * Valor de uma célula como texto.
     *
     * Conteúdo binário vira rótulo em vez de virar base64 gigante no meio da resposta, e texto
     * longo é cortado com marca — o objetivo é o cliente entender o dado, não recebê-lo inteiro.
     */
    private fun cellOf(rows: ResultSet, index: Int, sqlType: Int): String? = when (sqlType) {
        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
            if (rows.getBytes(index) == null) null else BINARY

        else -> {
            val value = try {
                rows.getString(index)
            } catch (failure: SQLException) {
                throw DataSourceAccessException("The database returned a value Prumo could not read as text.")
            }
            when {
                value == null -> null
                value.length > MAX_CELL_LENGTH -> value.take(MAX_CELL_LENGTH) + "…"
                else -> value
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ROWS = 100
        const val MAX_ROWS_CEILING = 1_000
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val MAX_CELL_LENGTH = 500
        const val BINARY = "[binary]"
    }
}
