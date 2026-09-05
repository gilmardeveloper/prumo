package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.datasource.DataSourceAccessException
import io.prumo.mcp.datasource.application.QueryRefusedException
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.ide.GitReadException
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.policy.PolicyEngine
import io.prumo.mcp.policy.PolicyRequest
import io.prumo.mcp.policy.PolicyViolationException
import io.prumo.mcp.repository.PathAccessDeniedException
import io.prumo.mcp.repository.RepositoryReadException
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.application.WorkspaceResolutionException
import io.prumo.mcp.workspace.domain.RepositoryBinding
import kotlin.coroutines.coroutineContext

/** Tudo o que uma tool do Prumo recebe depois que a fronteira já foi resolvida e autorizada. */
internal data class PrumoCall(
    val project: Project,
    val context: WorkspaceContext,
    val repository: RepositoryBinding,
    val datasource: DataSourceProfile? = null,
) {
    /** O datasource já resolvido dentro da fronteira; ausente é erro de programação, não do cliente. */
    val requiredDatasource: DataSourceProfile
        get() = requireNotNull(datasource) { "This tool must resolve a data source before running." }

    /**
     * O que a tool quer que fique na trilha de auditoria além do desfecho — tipo de statement,
     * contagem de linhas, e nada que reproduza conteúdo. Passa pelo saneador antes de ser gravado.
     */
    val auditDetails: MutableMap<String, String> = mutableMapOf()
}

/**
 * Contrato único de execução de toda tool do Prumo.
 *
 * Existe para que o contrato não seja repetido — e, portanto, não seja esquecido pela metade em
 * uma tool futura: contexto resolvido em um só lugar, repositório resolvido dentro da fronteira do
 * workspace, política consultada antes de agir, auditoria com o desfecho real e erro explícito no
 * lugar de resultado silencioso.
 */
internal suspend fun <T> prumoToolCall(
    tool: String,
    operation: String,
    action: PolicyAction,
    repository: (WorkspaceContext) -> RepositoryBinding = WorkspaceContext::currentRepository,
    datasource: ((WorkspaceContext) -> DataSourceProfile)? = null,
    block: suspend (PrumoCall) -> T,
): T {
    val project = McpProjectResolver.resolve(coroutineContext)
    val service = PrumoWorkspaceService.getInstance()
    val context = try {
        service.require(project)
    } catch (failure: WorkspaceResolutionException) {
        // Sem workspace resolvido não há trilha a que atribuir o evento: registrar em outro lugar
        // significaria inventar um dono para a chamada.
        LOG.info("Prumo MCP tool '$tool' was called from a project without a resolved workspace.")
        throw McpExpectedError(failure.message ?: UNRESOLVED_WORKSPACE)
    }

    val startedAt = System.nanoTime()
    var call: PrumoCall? = null
    return try {
        val binding = repository(context)
        val profile = datasource?.invoke(context)
        PolicyEngine.require(
            PolicyRequest(
                action = action,
                policies = context.policies,
                repositoryAccess = binding.accessMode,
                databaseAccess = profile?.accessMode,
            ),
        )
        val prepared = PrumoCall(project, context, binding, profile).also { call = it }
        block(prepared).also {
            service.record(context, call, tool, operation, AuditResult.SUCCESS, startedAt)
        }
    } catch (failure: PolicyViolationException) {
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.decision.reason)
    } catch (failure: PathAccessDeniedException) {
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.message ?: PATH_REFUSED)
    } catch (failure: WorkspaceResolutionException) {
        // Repositório pedido pelo cliente que não pertence a este workspace: a fronteira recusa,
        // e o cliente precisa saber que recusou.
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.message ?: UNRESOLVED_WORKSPACE)
    } catch (failure: RepositoryReadException) {
        // Arquivo ausente, binário ou grande demais: o cliente corrige o pedido, não é falha do plugin.
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: GitReadException) {
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: QueryRefusedException) {
        // Escrita ou statement irreconhecível barrado antes de chegar ao banco: é recusa, e a
        // trilha precisa dizer de que tipo era o statement — sem guardar o SQL.
        call?.auditDetails?.put("statementType", failure.statementType.name)
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.message ?: QUERY_REFUSED)
    } catch (failure: DataSourceAccessException) {
        // Credencial ausente ou banco que recusou a conexão: o cliente precisa saber qual dos dois.
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: Exception) {
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt)
        LOG.warn("Prumo MCP tool '$tool' failed for workspace '${context.workspace.id}'.", failure)
        throw failure
    }
}

private fun PrumoWorkspaceService.record(
    context: WorkspaceContext,
    call: PrumoCall?,
    tool: String,
    operation: String,
    result: AuditResult,
    startedAt: Long,
) {
    audit.record(
        workspaceId = context.workspace.id,
        tool = tool,
        operation = operation,
        result = result,
        durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        repositoryId = (call?.repository ?: context.currentRepository).id,
        datasourceId = call?.datasource?.id,
        details = call?.auditDetails.orEmpty(),
    )
}

private const val UNRESOLVED_WORKSPACE =
    "This project is not bound to any Prumo workspace. Configure it in the Prumo MCP tool window."

private const val PATH_REFUSED = "Prumo MCP refused the requested path."

private const val READ_REFUSED = "Prumo MCP could not read the requested content."

private const val QUERY_REFUSED = "Prumo MCP refused this statement."

private val LOG = Logger.getInstance("io.prumo.mcp.toolsets")
