package io.prumo.mcp.pack.execution

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.datasource.PostgresConnectionFactory
import io.prumo.mcp.datasource.application.QueryOutcome
import io.prumo.mcp.datasource.application.ReadOnlyQueryExecutor
import io.prumo.mcp.pack.application.PackAccessException
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.PackTool
import io.prumo.mcp.pack.domain.PackToolKind
import io.prumo.mcp.policy.Capability
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.policy.PolicyEngine
import io.prumo.mcp.policy.PolicyRequest
import io.prumo.mcp.storage.LocalStorageProvider
import io.prumo.mcp.workspace.domain.Workspace

/**
 * Executa a consulta salva de um pack.
 *
 * O `datasourceRef` é um identificador lógico resolvido no workspace de destino: o pack não carrega
 * conexão nem credencial.
 *
 * A consulta passa pelas mesmas camadas do Core Toolkit — classificação de statement, transação
 * somente-leitura, teto de linhas, timeout e mascaramento.
 */
class PackQueryRunner(
    storage: LocalStorageProvider,
    credentials: CredentialProvider,
    private val store: PackStore = PackStore(storage),
    private val executor: ReadOnlyQueryExecutor = ReadOnlyQueryExecutor(PostgresConnectionFactory(credentials)),
) {

    fun run(
        workspace: Workspace,
        packId: String,
        toolId: String,
        maxRows: Int = ReadOnlyQueryExecutor.DEFAULT_MAX_ROWS,
        audit: AuditLog? = null,
    ): QueryOutcome {
        val manifest = store.load(workspace.id, packId)
            ?: throw PackAccessException("Pack '$packId' is not installed in this workspace.")
        val tool = manifest.tool(toolId)
            ?: throw PackAccessException("Pack '$packId' has no tool '$toolId'.")
        if (tool.kind != PackToolKind.QUERY) {
            throw PackAccessException("Tool '$toolId' is not a saved query.")
        }

        val profile = resolveDatasource(workspace, manifest, tool)
        PolicyEngine.require(
            PolicyRequest(
                action = PolicyAction.QUERY_DATABASE,
                policies = workspace.policies,
                databaseAccess = profile.accessMode,
                grantedCapabilities = manifest.capabilities,
                fromUserResource = true,
            ),
        )

        val outcome = executor.execute(
            workspaceId = workspace.id,
            profile = profile,
            sql = requireNotNull(tool.sql) { "A query tool always carries its SQL." },
            maxRows = maxRows,
        )

        audit?.record(
            workspaceId = workspace.id,
            tool = "user.$packId.$toolId",
            operation = "pack.run_tool",
            result = AuditResult.SUCCESS,
            durationMillis = outcome.durationMillis,
            datasourceId = profile.id,
            packId = packId,
            details = mapOf(
                "statementType" to outcome.statementType.name,
                "rowCount" to outcome.rowCount.toString(),
                "truncated" to outcome.truncated.toString(),
            ),
        )
        return outcome
    }

    /**
     * O banco vem do workspace, não do pack.
     *
     * Sem `datasourceRef` e com um único banco vinculado, usa esse — o caso comum de um pack escrito
     * para o workspace onde já está. Com mais de um, exige que o pack diga qual: escolher por
     * conveniência exporia o banco errado.
     */
    private fun resolveDatasource(workspace: Workspace, manifest: PackManifest, tool: PackTool) =
        when {
            tool.datasourceRef != null -> workspace.datasource(tool.datasourceRef)
                ?: throw PackAccessException(
                    "Pack '${manifest.id}' asks for data source '${tool.datasourceRef}', " +
                        "which is not bound to this workspace. Bind it in the workspace editor, " +
                        "or ask the pack author for the right identifier.",
                )

            workspace.datasources.size == 1 -> workspace.datasources.single()

            else -> throw PackAccessException(
                "Tool '${tool.id}' does not say which data source it needs, and this workspace has " +
                    "${workspace.datasources.size} of them.",
            )
        }.also {
            if (Capability.DATASOURCE_QUERY !in manifest.capabilities) {
                throw PackAccessException(
                    "Pack '${manifest.id}' did not declare DATASOURCE_QUERY, so it cannot query databases.",
                )
            }
        }
}
