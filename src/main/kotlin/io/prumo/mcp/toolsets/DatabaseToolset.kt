package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.datasource.PostgresConnectionFactory
import io.prumo.mcp.datasource.application.QueryOutcome
import io.prumo.mcp.datasource.application.ReadOnlyQueryExecutor
import io.prumo.mcp.datasource.security.DataMaskingPolicy
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.postgres.PostgresIntrospector
import io.prumo.mcp.datasource.postgres.TableDetail
import io.prumo.mcp.datasource.postgres.TableSummary
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.application.WorkspaceResolutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class AvailableDataSourceResponse(
    val datasourceId: String,
    val name: String,
    val engine: String,
    val accessMode: String,
    val writable: Boolean,
    val defaultSchema: String? = null,
)

@Serializable
data class AvailableDataSourcesResponse(
    val workspaceId: String,
    val datasources: List<AvailableDataSourceResponse>,
)

@Serializable
data class SchemaResponse(
    val name: String,
    val objectCount: Int,
)

@Serializable
data class DatabaseSchemaResponse(
    val datasourceId: String,
    val schemas: List<SchemaResponse>,
)

@Serializable
data class TableResponse(
    val schema: String,
    val name: String,
    val kind: String,
    val comment: String? = null,
    val estimatedRows: Long? = null,
)

@Serializable
data class TableListResponse(
    val datasourceId: String,
    val schema: String? = null,
    val tables: List<TableResponse>,
    val truncated: Boolean,
)

@Serializable
data class ColumnResponse(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val position: Int,
    val defaultValue: String? = null,
    val comment: String? = null,
)

@Serializable
data class ConstraintResponse(
    val name: String,
    val kind: String,
    val definition: String,
)

@Serializable
data class IndexResponse(
    val name: String,
    val unique: Boolean,
    val definition: String,
)

@Serializable
data class QueryColumnResponse(
    val name: String,
    val type: String,
    val masked: Boolean,
)

@Serializable
data class QueryResultResponse(
    val datasourceId: String,
    val statementType: String,
    val columns: List<QueryColumnResponse>,
    val rows: List<List<String?>>,
    val rowCount: Int,
    val truncated: Boolean,
    val durationMillis: Long,
)

@Serializable
data class TableDetailResponse(
    val datasourceId: String,
    val schema: String,
    val name: String,
    val kind: String,
    val comment: String? = null,
    val columns: List<ColumnResponse>,
    val constraints: List<ConstraintResponse>,
    val indexes: List<IndexResponse>,
)

/**
 * Respostas das tools de banco.
 *
 * O cliente endereça o banco por identificador e nunca recebe host, porta, usuário ou nome do
 * banco: com o identificador ele consegue tudo o que precisa, e o que sobraria seria material para
 * tentar a conexão por fora do Prumo.
 */
object DatabaseReports {

    const val MAX_TABLES = 500

    fun available(context: WorkspaceContext): AvailableDataSourcesResponse =
        AvailableDataSourcesResponse(
            workspaceId = context.workspace.id,
            datasources = context.workspace.datasources.map {
                AvailableDataSourceResponse(
                    datasourceId = it.id,
                    name = it.name,
                    engine = ENGINE,
                    accessMode = it.accessMode.name,
                    writable = it.writable,
                    defaultSchema = it.defaultSchema,
                )
            },
        )

    fun tables(profile: DataSourceProfile, schema: String?, tables: List<TableSummary>): TableListResponse =
        TableListResponse(
            datasourceId = profile.id,
            schema = schema,
            tables = tables.map { TableResponse(it.schema, it.name, it.kind, it.comment, it.estimatedRows) },
            truncated = tables.size >= MAX_TABLES,
        )

    fun detail(profile: DataSourceProfile, detail: TableDetail): TableDetailResponse =
        TableDetailResponse(
            datasourceId = profile.id,
            schema = detail.schema,
            name = detail.name,
            kind = detail.kind,
            comment = detail.comment,
            columns = detail.columns.map {
                ColumnResponse(it.name, it.type, it.nullable, it.position, it.defaultValue, it.comment)
            },
            constraints = detail.constraints.map { ConstraintResponse(it.name, it.kind, it.definition) },
            indexes = detail.indexes.map { IndexResponse(it.name, it.unique, it.definition) },
        )

    fun query(profile: DataSourceProfile, outcome: QueryOutcome): QueryResultResponse =
        QueryResultResponse(
            datasourceId = profile.id,
            statementType = outcome.statementType.name,
            columns = outcome.columns.map { QueryColumnResponse(it.name, it.type, it.masked) },
            rows = outcome.rows,
            rowCount = outcome.rowCount,
            truncated = outcome.truncated,
            durationMillis = outcome.durationMillis,
        )

    private const val ENGINE = "PostgreSQL"
}

/**
 * Superfície MCP dos bancos vinculados ao workspace corrente.
 *
 * Estrutura, não conteúdo: estas tools descrevem o banco. Consultar dados é `execute_readonly`, que
 * chega em W14 com as camadas de proteção da seção 9.
 *
 * O MCP nativo tem tools de banco no Ultimate, pelo Database Tools, mas elas não conhecem workspace
 * e não existem no Community (F-007) — é o vão que estas ocupam.
 */
class DatabaseToolset : McpToolset {

    @McpTool(name = LIST_AVAILABLE_TOOL)
    @McpDescription(
        "Lists the databases bound to the current workspace. Returns identifiers and access mode " +
            "only — never host, port, user or credentials. Use the datasourceId in the other database tools.",
    )
    suspend fun listAvailable(): AvailableDataSourcesResponse =
        prumoToolCall(LIST_AVAILABLE_TOOL, "database.list_available", PolicyAction.READ_REPOSITORY) { call ->
            DatabaseReports.available(call.context)
        }

    @McpTool(name = GET_SCHEMA_TOOL)
    @McpDescription(
        "Lists the schemas of a database bound to the current workspace, with how many tables and " +
            "views each one holds. System schemas are omitted.",
    )
    suspend fun getSchema(
        @McpDescription("Data source id from prumo_database_list_available.")
        datasourceId: String,
    ): DatabaseSchemaResponse =
        databaseCall(GET_SCHEMA_TOOL, "database.get_schema", datasourceId) { call, connection ->
            DatabaseSchemaResponse(
                datasourceId = call.requiredDatasource.id,
                schemas = introspector.schemas(connection).map { SchemaResponse(it.name, it.objectCount) },
            )
        }

    @McpTool(name = LIST_TABLES_TOOL)
    @McpDescription(
        "Lists tables, views and materialized views of a bound database, optionally restricted to " +
            "one schema. Row counts are the planner estimate, not an exact count.",
    )
    suspend fun listTables(
        @McpDescription("Data source id from prumo_database_list_available.")
        datasourceId: String,
        @McpDescription("Schema to restrict the listing to. Omit to list every non-system schema.")
        schema: String? = null,
    ): TableListResponse =
        databaseCall(LIST_TABLES_TOOL, "database.list_tables", datasourceId) { call, connection ->
            val tables = introspector.tables(connection, schema, DatabaseReports.MAX_TABLES)
            DatabaseReports.tables(call.requiredDatasource, schema, tables)
        }

    @McpTool(name = DESCRIBE_TABLE_TOOL)
    @McpDescription(
        "Describes one table or view of a bound database: columns with types and defaults, " +
            "constraints and indexes. Reads structure only — no row is ever read.",
    )
    suspend fun describeTable(
        @McpDescription("Data source id from prumo_database_list_available.")
        datasourceId: String,
        @McpDescription("Table or view name.")
        table: String,
        @McpDescription("Schema of the table. Omit to use the connection's current schema.")
        schema: String? = null,
    ): TableDetailResponse =
        databaseCall(DESCRIBE_TABLE_TOOL, "database.describe_table", datasourceId) { call, connection ->
            DatabaseReports.detail(
                call.requiredDatasource,
                introspector.describe(connection, schema, table),
            )
        }

    @McpTool(name = EXECUTE_READONLY_TOOL)
    @McpDescription(
        "Runs one read-only SQL statement against a bound database and returns the rows. Only " +
            "SELECT, WITH … SELECT and EXPLAIN without ANALYZE are accepted: anything that writes " +
            "is refused before reaching the database, and the transaction is read-only anyway. " +
            "Columns whose name announces a secret come back masked.",
    )
    suspend fun executeReadonly(
        @McpDescription("Data source id from prumo_database_list_available.")
        datasourceId: String,
        @McpDescription("A single read-only SQL statement.")
        sql: String,
        @McpDescription("How many rows to return at most. Default 100, ceiling 1000.")
        maxRows: Int = ReadOnlyQueryExecutor.DEFAULT_MAX_ROWS,
    ): QueryResultResponse =
        prumoToolCall(
            tool = EXECUTE_READONLY_TOOL,
            operation = "database.execute_readonly",
            action = PolicyAction.QUERY_DATABASE,
            datasource = { context -> context.datasource(datasourceId) },
        ) { call ->
            val service = PrumoWorkspaceService.getInstance()
            val outcome = withContext(Dispatchers.IO) {
                ReadOnlyQueryExecutor(PostgresConnectionFactory(service.credentials)).execute(
                    workspaceId = call.context.workspace.id,
                    profile = call.requiredDatasource,
                    sql = sql,
                    maxRows = maxRows,
                    // Regras por coluna chegam com a configuração de masking; o padrão já mascara
                    // toda coluna cujo nome anuncia segredo.
                    masking = DataMaskingPolicy.NONE,
                )
            }
            call.auditDetails["statementType"] = outcome.statementType.name
            call.auditDetails["rowCount"] = outcome.rowCount.toString()
            call.auditDetails["truncated"] = outcome.truncated.toString()
            DatabaseReports.query(call.requiredDatasource, outcome)
        }

    /**
     * Resolve o datasource dentro da fronteira, abre a conexão pelo tempo da leitura e a fecha em
     * seguida. A conexão nunca sobrevive à chamada.
     */
    private suspend fun <T> databaseCall(
        tool: String,
        operation: String,
        datasourceId: String,
        block: (PrumoCall, java.sql.Connection) -> T,
    ): T = prumoToolCall(
        tool = tool,
        operation = operation,
        action = PolicyAction.QUERY_DATABASE,
        datasource = { context -> context.datasource(datasourceId) },
    ) { call ->
        val service = PrumoWorkspaceService.getInstance()
        withContext(Dispatchers.IO) {
            PostgresConnectionFactory(service.credentials).withConnection(
                call.context.workspace.id,
                call.requiredDatasource,
            ) { connection -> block(call, connection) }
        }
    }

    private val introspector = PostgresIntrospector()

    private companion object {
        const val LIST_AVAILABLE_TOOL = "prumo_database_list_available"
        const val GET_SCHEMA_TOOL = "prumo_database_get_schema"
        const val LIST_TABLES_TOOL = "prumo_database_list_tables"
        const val DESCRIBE_TABLE_TOOL = "prumo_database_describe_table"
        const val EXECUTE_READONLY_TOOL = "prumo_database_execute_readonly"
    }
}

/**
 * Datasource do workspace corrente, ou recusa explícita.
 *
 * Mesma regra dos repositórios: um identificador que não pertence a este workspace não é procurado
 * em outro lugar — a fronteira responde que ele não existe aqui (P3, P9).
 */
internal fun WorkspaceContext.datasource(datasourceId: String): DataSourceProfile =
    workspace.datasource(datasourceId)
        ?: throw WorkspaceResolutionException(
            "Data source '$datasourceId' is not bound to the current workspace.",
        )
