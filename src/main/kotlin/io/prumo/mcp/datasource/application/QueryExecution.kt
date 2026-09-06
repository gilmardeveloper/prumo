package io.prumo.mcp.datasource.application

import io.prumo.mcp.datasource.DataSourceAccessException
import io.prumo.mcp.datasource.PostgresConnectionFactory
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.security.DataMaskingPolicy
import io.prumo.mcp.datasource.security.PersonalDataKind
import io.prumo.mcp.datasource.security.PersonalDataObfuscator
import io.prumo.mcp.datasource.security.MaskingRule
import io.prumo.mcp.datasource.security.SensitiveColumnScanner
import org.postgresql.PGResultSetMetaData
import io.prumo.mcp.datasource.security.SqlClassification
import io.prumo.mcp.datasource.security.SqlStatementClassifier
import io.prumo.mcp.datasource.security.SqlStatementType
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** Consulta recusada antes de chegar ao banco. Carrega o motivo, nunca o SQL. */
class QueryRefusedException(
    val statementType: SqlStatementType,
    message: String,
) : IllegalArgumentException(message)

data class QueryColumn(
    val name: String,
    val type: String,
    val masked: Boolean,
    /** Categoria de dado pessoal aplicada ao valor. `NONE` quando o valor sai como veio do banco. */
    val obfuscatedAs: PersonalDataKind = PersonalDataKind.NONE,
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
 * Executa uma consulta de leitura.
 *
 * O statement é classificado antes de sair daqui, a transação é aberta como `READ ONLY` mesmo em
 * datasource `READ_WRITE` e termina em `rollback`, o número de linhas tem teto e a consulta tem
 * timeout. A auditoria fica a cargo de quem chama.
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
                        read(
                            rows = rows,
                            limit = limit,
                            masking = masking,
                            obfuscate = profile.obfuscatePersonalData,
                            statementType = classification.type,
                            startedAt = startedAt,
                            sql = sql,
                        )
                    }
                }
            }
        }
    }

    /**
     * Executa o bloco numa transação somente-leitura, encerrada em `rollback`.
     *
     * `SET TRANSACTION READ ONLY` precisa ser o primeiro comando da transação, então `autoCommit` é
     * desligado logo antes.
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

    /**
     * Monta o resultado aplicando máscara e ofuscação.
     *
     * É o único ponto em que valor de célula sai para o cliente, e por isso o único lugar seguro
     * para aplicar a proteção: os dois caminhos de consulta — o Core Toolkit e o de packs — chegam
     * aqui, e um deles herda a política por omissão.
     */
    private fun read(
        rows: ResultSet,
        limit: Int,
        masking: DataMaskingPolicy,
        obfuscate: Boolean,
        statementType: SqlStatementType,
        startedAt: Long,
        sql: String,
    ): QueryOutcome {
        val metadata = rows.metaData
        // Coluna calculada nao tem coluna de origem; so a leitura do statement denuncia a origem.
        // Posicoes nulas significam lista de selecao nao mapeavel: toda calculada e tratada como
        // sensivel se o statement encostar em segredo em qualquer ponto.
        val sensitivePositions = SensitiveColumnScanner.sensitivePositions(sql, masking, metadata.columnCount)
        val touchesSecret by lazy { SensitiveColumnScanner.touchesSensitiveColumn(sql, masking) }
        val columns = (1..metadata.columnCount).map { index ->
            val label = metadata.getColumnLabel(index).orEmpty()
            val origin = baseColumnName(metadata, index)
            val name = label.ifBlank { origin }
            val computed = origin.isBlank()
            val computedFromSecret =
                if (sensitivePositions == null) touchesSecret else index in sensitivePositions
            val masked = masking.ruleFor(listOf(label, origin)) == MaskingRule.MASK ||
                (computed && computedFromSecret)
            QueryColumn(
                name = name,
                type = metadata.getColumnTypeName(index) ?: "unknown",
                masked = masked,
                obfuscatedAs = if (masked || !obfuscate) {
                    PersonalDataKind.NONE
                } else {
                    PersonalDataObfuscator.classify(name, null)
                },
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
                    val value = cellOf(rows, index + 1, metadata.getColumnType(index + 1))
                    when {
                        column.masked && value != null -> DataMaskingPolicy.MASKED
                        column.obfuscatedAs == PersonalDataKind.NONE -> value
                        else -> PersonalDataObfuscator.obfuscate(
                            PersonalDataObfuscator.classify(column.name, value),
                            value,
                        )
                    }
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
     * Conteúdo binário vira rótulo; texto acima de [MAX_CELL_LENGTH] é cortado com reticências.
     */
    /**
     * Nome da coluna de origem, indiferente ao apelido que o cliente escolheu.
     *
     * `getColumnName` do driver PostgreSQL devolve o apelido, igual a `getColumnLabel`, então não
     * serve para decidir mascaramento. `PGResultSetMetaData.getBaseColumnName` devolve a coluna de
     * verdade, e vazio quando a coluna é calculada. Driver que não exponha a API cai no nome comum,
     * e a decisão fica a cargo do rótulo e da varredura do statement.
     */
    private fun baseColumnName(metadata: java.sql.ResultSetMetaData, index: Int): String =
        when (metadata) {
            is PGResultSetMetaData -> runCatching { metadata.getBaseColumnName(index) }.getOrNull().orEmpty()
            else -> metadata.getColumnName(index).orEmpty()
        }

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
