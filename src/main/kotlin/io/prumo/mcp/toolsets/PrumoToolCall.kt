package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.client.ClientIdentity
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** O que uma tool do Prumo recebe depois que a fronteira foi resolvida e autorizada. */
internal data class PrumoCall(
    val project: Project,
    val context: WorkspaceContext,
    val repository: RepositoryBinding,
    val datasource: DataSourceProfile? = null,
    /** Quem fez a chamada. Nunca é nulo: cliente que não se identifica vira `UNKNOWN`. */
    val client: ClientIdentity = ClientIdentity.UNKNOWN,
) {
    /** O datasource resolvido dentro da fronteira. Ausente significa erro de programação. */
    val requiredDatasource: DataSourceProfile
        get() = requireNotNull(datasource) { "This tool must resolve a data source before running." }

    /**
     * Dados adicionais para a trilha de auditoria, como tipo de statement e contagem de linhas.
     * Passam pelo saneador antes de serem gravados.
     */
    val auditDetails: MutableMap<String, String> = mutableMapOf()
}

/**
 * Contrato único de execução de toda tool do Prumo.
 *
 * Resolve o contexto do workspace, resolve o repositório e o datasource dentro da fronteira,
 * consulta a política antes de agir, executa o bloco e registra o desfecho na auditoria. Falha
 * sempre com erro explícito.
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
    val client = McpProjectResolver.client(coroutineContext)
    val service = PrumoWorkspaceService.getInstance()
    val context = try {
        service.require(project)
    } catch (failure: WorkspaceResolutionException) {
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
        val prepared = PrumoCall(project, context, binding, profile, client).also { call = it }
        block(prepared).also {
            service.record(context, call, tool, operation, AuditResult.SUCCESS, startedAt, client)
        }
    } catch (failure: PolicyViolationException) {
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt, client)
        throw McpExpectedError(failure.decision.reason)
    } catch (failure: PathAccessDeniedException) {
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt, client)
        throw McpExpectedError(failure.message ?: PATH_REFUSED)
    } catch (failure: WorkspaceResolutionException) {
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt, client)
        throw McpExpectedError(failure.message ?: UNRESOLVED_WORKSPACE)
    } catch (failure: RepositoryReadException) {
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt, client)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: GitReadException) {
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt, client)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: QueryRefusedException) {
        call?.auditDetails?.put("statementType", failure.statementType.name)
        service.record(context, call, tool, operation, AuditResult.DENIED, startedAt, client)
        throw McpExpectedError(failure.message ?: QUERY_REFUSED)
    } catch (failure: DataSourceAccessException) {
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt, client)
        LOG.warn("Prumo MCP tool '$tool' failed for workspace '${context.workspace.id}'.", failure)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (cancellation: CancellationException) {
        service.record(context, call, tool, operation, AuditResult.CANCELLED, startedAt, client)
        throw cancellation
    } catch (failure: Exception) {
        service.record(context, call, tool, operation, AuditResult.ERROR, startedAt, client)
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
    client: ClientIdentity,
) {
    audit.record(
        workspaceId = context.workspace.id,
        tool = tool,
        operation = operation,
        result = result,
        durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        repositoryId = (call?.repository ?: context.currentRepository).id,
        datasourceId = call?.datasource?.id,
        details = call?.auditDetails.orEmpty() + (CLIENT_DETAIL to client.label),
    )
}

/** Chave sob a qual a trilha registra quem chamou. */
private const val CLIENT_DETAIL = "client"

private const val UNRESOLVED_WORKSPACE =
    "This project is not bound to any Prumo workspace. Configure it in the Prumo MCP tool window."

private const val PATH_REFUSED = "Prumo MCP refused the requested path."

private const val READ_REFUSED = "Prumo MCP could not read the requested content."

private const val QUERY_REFUSED = "Prumo MCP refused this statement."

private val LOG = Logger.getInstance("io.prumo.mcp.toolsets")
