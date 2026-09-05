package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.diagnostic.logger
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.policy.PolicyEngine
import io.prumo.mcp.policy.PolicyRequest
import io.prumo.mcp.policy.PolicyViolationException
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.application.WorkspaceResolutionException
import kotlin.coroutines.coroutineContext

/**
 * Superfície MCP do workspace corrente.
 *
 * O adaptador é fino de propósito: resolve o projeto, resolve o workspace, consulta a política,
 * delega a resposta ao núcleo e registra a auditoria. Toda regra que decide o que pode ser visto
 * mora fora daqui, onde é testável sem a IDE.
 *
 * Os nomes registrados no MCP usam `_` porque é o formato aceito pelos clientes e o mesmo das tools
 * nativas da plataforma; o nome canônico com ponto (`workspace.get_context`) permanece na trilha de
 * auditoria.
 */
class WorkspaceToolset : McpToolset {

    @McpTool(name = GET_CONTEXT_TOOL)
    @McpDescription(
        "Returns the Prumo workspace bound to the current project: its name, type, the repository " +
            "the project belongs to and how much documentation is attached. Only the current " +
            "workspace is ever visible.",
    )
    suspend fun getContext(): WorkspaceContextResponse =
        respond(GET_CONTEXT_TOOL, "workspace.get_context", PolicyAction.READ_REPOSITORY) {
            WorkspaceReports.context(it)
        }

    @McpTool(name = GET_POLICY_TOOL)
    @McpDescription(
        "Returns what this workspace allows, decided by the Prumo policy engine. Database actions " +
            "are evaluated without a specific datasource. Read it before attempting an operation " +
            "that may be denied.",
    )
    suspend fun getPolicy(): WorkspacePolicyResponse =
        respond(GET_POLICY_TOOL, "workspace.get_policy", PolicyAction.READ_REPOSITORY) {
            WorkspaceReports.policy(it)
        }

    @McpTool(name = GET_REPOSITORIES_TOOL)
    @McpDescription(
        "Lists the repositories bound to the current workspace with their role and access mode. " +
            "Use the returned repositoryId to address a repository: Prumo never accepts absolute " +
            "paths from the client.",
    )
    suspend fun getRepositories(): RepositoriesResponse =
        respond(GET_REPOSITORIES_TOOL, "workspace.get_repositories", PolicyAction.READ_REPOSITORY) {
            WorkspaceReports.repositories(it)
        }

    @McpTool(name = GET_DOCUMENTATION_SOURCES_TOOL)
    @McpDescription(
        "Lists the documentation attached to the current workspace, its authority level and " +
            "whether Prumo can read it as text. Catalogued formats such as PDF are reported as " +
            "known but not extractable.",
    )
    suspend fun getDocumentationSources(): DocumentationSourcesResponse =
        respond(
            GET_DOCUMENTATION_SOURCES_TOOL,
            "workspace.get_documentation_sources",
            PolicyAction.READ_DOCUMENTATION,
        ) {
            WorkspaceReports.documentationSources(it)
        }

    @McpTool(name = PREPARE_TOOL)
    @McpDescription(
        "Validates the current workspace and returns READY, WARNING or ERROR with one check per " +
            "repository and documentation source. Read-only: it never runs git pull, checkout, " +
            "reset or any other mutation.",
    )
    suspend fun prepare(): WorkspacePreparationResponse =
        respond(PREPARE_TOOL, "workspace.prepare", PolicyAction.READ_REPOSITORY) {
            WorkspacePreparation.evaluate(it)
        }

    /**
     * Contrato comum a toda tool do Prumo: contexto resolvido em um único lugar, política
     * consultada antes de agir e auditoria registrada com o desfecho real da chamada.
     */
    private suspend fun <T> respond(
        tool: String,
        operation: String,
        action: PolicyAction,
        block: (WorkspaceContext) -> T,
    ): T {
        val project = McpProjectResolver.resolve(coroutineContext)
        val service = PrumoWorkspaceService.getInstance()
        val context = try {
            service.require(project)
        } catch (failure: WorkspaceResolutionException) {
            // Sem workspace resolvido não há trilha a que atribuir o evento: registrar em outro
            // lugar significaria inventar um dono para a chamada.
            LOG.info("Prumo MCP tool '$tool' was called from a project without a resolved workspace.")
            throw McpExpectedError(failure.message ?: UNRESOLVED_WORKSPACE)
        }

        val startedAt = System.nanoTime()
        return try {
            PolicyEngine.require(
                PolicyRequest(
                    action = action,
                    policies = context.policies,
                    repositoryAccess = context.currentRepository.accessMode,
                ),
            )
            block(context).also {
                service.record(context, tool, operation, AuditResult.SUCCESS, startedAt)
            }
        } catch (failure: PolicyViolationException) {
            service.record(context, tool, operation, AuditResult.DENIED, startedAt)
            throw McpExpectedError(failure.decision.reason)
        } catch (failure: Exception) {
            service.record(context, tool, operation, AuditResult.ERROR, startedAt)
            LOG.warn("Prumo MCP tool '$tool' failed for workspace '${context.workspace.id}'.", failure)
            throw failure
        }
    }

    private fun PrumoWorkspaceService.record(
        context: WorkspaceContext,
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
            repositoryId = context.currentRepository.id,
        )
    }

    private companion object {
        const val GET_CONTEXT_TOOL = "prumo_workspace_get_context"
        const val GET_POLICY_TOOL = "prumo_workspace_get_policy"
        const val GET_REPOSITORIES_TOOL = "prumo_workspace_get_repositories"
        const val GET_DOCUMENTATION_SOURCES_TOOL = "prumo_workspace_get_documentation_sources"
        const val PREPARE_TOOL = "prumo_workspace_prepare"

        const val UNRESOLVED_WORKSPACE =
            "This project is not bound to any Prumo workspace. Configure it in the Prumo MCP tool window."

        val LOG = logger<WorkspaceToolset>()
    }
}
