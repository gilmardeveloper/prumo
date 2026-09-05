package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.datasource.DataSourceAccessException
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
    var target: RepositoryBinding? = null
    var source: DataSourceProfile? = null
    return try {
        val binding = repository(context).also { target = it }
        val profile = datasource?.invoke(context)?.also { source = it }
        PolicyEngine.require(
            PolicyRequest(
                action = action,
                policies = context.policies,
                repositoryAccess = binding.accessMode,
                databaseAccess = profile?.accessMode,
            ),
        )
        block(PrumoCall(project, context, binding, profile)).also {
            service.record(context, target, source, tool, operation, AuditResult.SUCCESS, startedAt)
        }
    } catch (failure: PolicyViolationException) {
        service.record(context, target, source, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.decision.reason)
    } catch (failure: PathAccessDeniedException) {
        service.record(context, target, source, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.message ?: PATH_REFUSED)
    } catch (failure: WorkspaceResolutionException) {
        // Repositório pedido pelo cliente que não pertence a este workspace: a fronteira recusa,
        // e o cliente precisa saber que recusou.
        service.record(context, target, source, tool, operation, AuditResult.DENIED, startedAt)
        throw McpExpectedError(failure.message ?: UNRESOLVED_WORKSPACE)
    } catch (failure: RepositoryReadException) {
        // Arquivo ausente, binário ou grande demais: o cliente corrige o pedido, não é falha do plugin.
        service.record(context, target, source, tool, operation, AuditResult.ERROR, startedAt)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: GitReadException) {
        service.record(context, target, source, tool, operation, AuditResult.ERROR, startedAt)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: DataSourceAccessException) {
        // Credencial ausente ou banco que recusou a conexão: o cliente precisa saber qual dos dois.
        service.record(context, target, source, tool, operation, AuditResult.ERROR, startedAt)
        throw McpExpectedError(failure.message ?: READ_REFUSED)
    } catch (failure: Exception) {
        service.record(context, target, source, tool, operation, AuditResult.ERROR, startedAt)
        LOG.warn("Prumo MCP tool '$tool' failed for workspace '${context.workspace.id}'.", failure)
        throw failure
    }
}

private fun PrumoWorkspaceService.record(
    context: WorkspaceContext,
    repository: RepositoryBinding?,
    datasource: DataSourceProfile?,
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
        repositoryId = (repository ?: context.currentRepository).id,
        datasourceId = datasource?.id,
    )
}

private const val UNRESOLVED_WORKSPACE =
    "This project is not bound to any Prumo workspace. Configure it in the Prumo MCP tool window."

private const val PATH_REFUSED = "Prumo MCP refused the requested path."

private const val READ_REFUSED = "Prumo MCP could not read the requested content."

private val LOG = Logger.getInstance("io.prumo.mcp.toolsets")
