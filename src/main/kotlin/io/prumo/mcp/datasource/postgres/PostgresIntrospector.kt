package io.prumo.mcp.datasource.postgres

import io.prumo.mcp.datasource.DataSourceAccessException
import java.sql.Connection
import java.sql.ResultSet

data class SchemaSummary(
    val name: String,
    val objectCount: Int,
)

data class TableSummary(
    val schema: String,
    val name: String,
    val kind: String,
    val comment: String? = null,
    val estimatedRows: Long? = null,
)

data class ColumnDetail(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val position: Int,
    val comment: String?,
)

data class ConstraintDetail(
    val name: String,
    val kind: String,
    val definition: String,
)

data class IndexDetail(
    val name: String,
    val unique: Boolean,
    val definition: String,
)

data class TableDetail(
    val schema: String,
    val name: String,
    val kind: String,
    val comment: String?,
    val columns: List<ColumnDetail>,
    val constraints: List<ConstraintDetail>,
    val indexes: List<IndexDetail>,
)

/**
 * Lê a estrutura de um banco PostgreSQL.
 *
 * Só estrutura: nome de schema, tabela, coluna, tipo, restrição e índice. Nenhuma linha de dado é
 * consultada aqui — descrever o banco não é o mesmo que ler o que há dentro dele.
 *
 * Todo nome vindo do cliente entra como **parâmetro** de `PreparedStatement`, nunca concatenado no
 * SQL. Um identificador com aspas ou ponto e vírgula é comparado como texto e simplesmente não
 * encontra tabela alguma.
 */
class PostgresIntrospector(
    private val queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS,
) {

    fun schemas(connection: Connection): List<SchemaSummary> =
        connection.query(SCHEMAS_SQL) { rows ->
            buildList {
                while (rows.next()) {
                    add(SchemaSummary(rows.getString("schema_name"), rows.getInt("object_count")))
                }
            }
        }

    fun tables(connection: Connection, schema: String?, limit: Int = DEFAULT_TABLE_LIMIT): List<TableSummary> =
        connection.query(TABLES_SQL, listOf(schema, schema, limit)) { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        TableSummary(
                            schema = rows.getString("schema_name"),
                            name = rows.getString("table_name"),
                            kind = kindOf(rows.getString("kind")),
                            comment = rows.getString("comment"),
                            estimatedRows = rows.getLong("estimated_rows").takeIf { it >= 0 },
                        ),
                    )
                }
            }
        }

    fun describe(connection: Connection, schema: String?, table: String): TableDetail {
        val resolvedSchema = schema ?: currentSchema(connection)
        val summary = connection.query(TABLE_SQL, listOf(resolvedSchema, table)) { rows ->
            if (!rows.next()) {
                null
            } else {
                TableSummary(
                    schema = rows.getString("schema_name"),
                    name = rows.getString("table_name"),
                    kind = kindOf(rows.getString("kind")),
                    comment = rows.getString("comment"),
                )
            }
        } ?: throw DataSourceAccessException("Table '$table' was not found in schema '$resolvedSchema'.")

        return TableDetail(
            schema = summary.schema,
            name = summary.name,
            kind = summary.kind,
            comment = summary.comment,
            columns = columns(connection, summary.schema, summary.name),
            constraints = constraints(connection, summary.schema, summary.name),
            indexes = indexes(connection, summary.schema, summary.name),
        )
    }

    private fun columns(connection: Connection, schema: String, table: String): List<ColumnDetail> =
        connection.query(COLUMNS_SQL, listOf(schema, table)) { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        ColumnDetail(
                            name = rows.getString("column_name"),
                            type = rows.getString("data_type"),
                            nullable = !rows.getBoolean("not_null"),
                            defaultValue = rows.getString("default_value"),
                            position = rows.getInt("position"),
                            comment = rows.getString("comment"),
                        ),
                    )
                }
            }
        }

    private fun constraints(connection: Connection, schema: String, table: String): List<ConstraintDetail> =
        connection.query(CONSTRAINTS_SQL, listOf(schema, table)) { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        ConstraintDetail(
                            name = rows.getString("constraint_name"),
                            kind = constraintKindOf(rows.getString("constraint_type")),
                            definition = rows.getString("definition"),
                        ),
                    )
                }
            }
        }

    private fun indexes(connection: Connection, schema: String, table: String): List<IndexDetail> =
        connection.query(INDEXES_SQL, listOf(schema, table)) { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        IndexDetail(
                            name = rows.getString("index_name"),
                            unique = rows.getBoolean("is_unique"),
                            definition = rows.getString("definition"),
                        ),
                    )
                }
            }
        }

    private fun currentSchema(connection: Connection): String =
        connection.query("SELECT current_schema() AS schema_name") { rows ->
            if (rows.next()) rows.getString("schema_name") ?: PUBLIC_SCHEMA else PUBLIC_SCHEMA
        }

    private fun <T> Connection.query(sql: String, parameters: List<Any?> = emptyList(), read: (ResultSet) -> T): T =
        prepareStatement(sql).use { statement ->
            statement.queryTimeout = queryTimeoutSeconds
            parameters.forEachIndexed { index, value ->
                when (value) {
                    is Int -> statement.setInt(index + 1, value)
                    null -> statement.setNull(index + 1, java.sql.Types.VARCHAR)
                    else -> statement.setString(index + 1, value.toString())
                }
            }
            statement.executeQuery().use(read)
        }

    private fun kindOf(relkind: String?): String = when (relkind) {
        "r" -> "TABLE"
        "p" -> "PARTITIONED_TABLE"
        "v" -> "VIEW"
        "m" -> "MATERIALIZED_VIEW"
        "f" -> "FOREIGN_TABLE"
        else -> "UNKNOWN"
    }

    private fun constraintKindOf(contype: String?): String = when (contype) {
        "p" -> "PRIMARY_KEY"
        "f" -> "FOREIGN_KEY"
        "u" -> "UNIQUE"
        "c" -> "CHECK"
        "x" -> "EXCLUSION"
        else -> "OTHER"
    }

    private companion object {
        const val DEFAULT_QUERY_TIMEOUT_SECONDS = 15
        const val DEFAULT_TABLE_LIMIT = 500
        const val PUBLIC_SCHEMA = "public"

        const val SYSTEM_SCHEMAS =
            "n.nspname NOT IN ('pg_catalog', 'information_schema') " +
                "AND n.nspname NOT LIKE 'pg\\_toast%' AND n.nspname NOT LIKE 'pg\\_temp%'"

        const val OBJECT_KINDS = "c.relkind IN ('r', 'p', 'v', 'm', 'f')"

        val SCHEMAS_SQL = """
            SELECT n.nspname AS schema_name,
                   count(c.oid) FILTER (WHERE $OBJECT_KINDS) AS object_count
            FROM pg_namespace n
            LEFT JOIN pg_class c ON c.relnamespace = n.oid
            WHERE $SYSTEM_SCHEMAS
            GROUP BY n.nspname
            ORDER BY n.nspname
        """.trimIndent()

        val TABLES_SQL = """
            SELECT n.nspname AS schema_name,
                   c.relname AS table_name,
                   c.relkind AS kind,
                   obj_description(c.oid) AS comment,
                   c.reltuples::bigint AS estimated_rows
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE $OBJECT_KINDS
              AND $SYSTEM_SCHEMAS
              AND (?::text IS NULL OR n.nspname = ?::text)
            ORDER BY n.nspname, c.relname
            LIMIT ?
        """.trimIndent()

        val TABLE_SQL = """
            SELECT n.nspname AS schema_name,
                   c.relname AS table_name,
                   c.relkind AS kind,
                   obj_description(c.oid) AS comment
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE $OBJECT_KINDS AND n.nspname = ?::text AND c.relname = ?::text
        """.trimIndent()

        val COLUMNS_SQL = """
            SELECT a.attname AS column_name,
                   format_type(a.atttypid, a.atttypmod) AS data_type,
                   a.attnotnull AS not_null,
                   pg_get_expr(d.adbin, d.adrelid) AS default_value,
                   a.attnum AS position,
                   col_description(a.attrelid, a.attnum) AS comment
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attnum > 0 AND NOT a.attisdropped
              AND n.nspname = ?::text AND c.relname = ?::text
            ORDER BY a.attnum
        """.trimIndent()

        val CONSTRAINTS_SQL = """
            SELECT con.conname AS constraint_name,
                   con.contype AS constraint_type,
                   pg_get_constraintdef(con.oid) AS definition
            FROM pg_constraint con
            JOIN pg_class c ON c.oid = con.conrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?::text AND c.relname = ?::text
            ORDER BY con.contype, con.conname
        """.trimIndent()

        val INDEXES_SQL = """
            SELECT i.relname AS index_name,
                   idx.indisunique AS is_unique,
                   pg_get_indexdef(idx.indexrelid) AS definition
            FROM pg_index idx
            JOIN pg_class i ON i.oid = idx.indexrelid
            JOIN pg_class c ON c.oid = idx.indrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?::text AND c.relname = ?::text
            ORDER BY i.relname
        """.trimIndent()
    }
}
