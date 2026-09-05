package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.policy.PolicyAction

/**
 * Superfície MCP do workspace corrente.
 *
 * Os nomes registrados no MCP usam `_`, que é o formato aceito pelos clientes; o nome canônico com
 * ponto (`workspace.get_context`) permanece na trilha de auditoria.
 */
class WorkspaceToolset : McpToolset {

    @McpTool(name = GET_CONTEXT_TOOL)
    @McpDescription(
        "Returns the Prumo workspace bound to the current project: its name, type, the repository " +
            "the project belongs to and how much documentation is attached. Only the current " +
            "workspace is ever visible.",
    )
    suspend fun getContext(): WorkspaceContextResponse =
        prumoToolCall(GET_CONTEXT_TOOL, "workspace.get_context", PolicyAction.READ_REPOSITORY) { call ->
            WorkspaceReports.context(call.context)
        }

    @McpTool(name = GET_POLICY_TOOL)
    @McpDescription(
        "Returns what this workspace allows, decided by the Prumo policy engine. Database actions " +
            "are evaluated without a specific datasource. Read it before attempting an operation " +
            "that may be denied.",
    )
    suspend fun getPolicy(): WorkspacePolicyResponse =
        prumoToolCall(GET_POLICY_TOOL, "workspace.get_policy", PolicyAction.READ_REPOSITORY) { call ->
            WorkspaceReports.policy(call.context)
        }

    @McpTool(name = GET_REPOSITORIES_TOOL)
    @McpDescription(
        "Lists the repositories bound to the current workspace with their role and access mode. " +
            "Use the returned repositoryId to address a repository: Prumo never accepts absolute " +
            "paths from the client.",
    )
    suspend fun getRepositories(): RepositoriesResponse =
        prumoToolCall(GET_REPOSITORIES_TOOL, "workspace.get_repositories", PolicyAction.READ_REPOSITORY) { call ->
            WorkspaceReports.repositories(call.context)
        }

    @McpTool(name = GET_DOCUMENTATION_SOURCES_TOOL)
    @McpDescription(
        "Lists the documentation attached to the current workspace, its authority level and " +
            "whether Prumo can read it as text. Catalogued formats such as PDF are reported as " +
            "known but not extractable.",
    )
    suspend fun getDocumentationSources(): DocumentationSourcesResponse =
        prumoToolCall(
            GET_DOCUMENTATION_SOURCES_TOOL,
            "workspace.get_documentation_sources",
            PolicyAction.READ_DOCUMENTATION,
        ) { call ->
            WorkspaceReports.documentationSources(call.context)
        }

    @McpTool(name = PREPARE_TOOL)
    @McpDescription(
        "Validates the current workspace and returns READY, WARNING or ERROR with one check per " +
            "repository and documentation source. Read-only: it never runs git pull, checkout, " +
            "reset or any other mutation.",
    )
    suspend fun prepare(): WorkspacePreparationResponse =
        prumoToolCall(PREPARE_TOOL, "workspace.prepare", PolicyAction.READ_REPOSITORY) { call ->
            WorkspacePreparation.evaluate(call.context)
        }

    private companion object {
        const val GET_CONTEXT_TOOL = "prumo_workspace_get_context"
        const val GET_POLICY_TOOL = "prumo_workspace_get_policy"
        const val GET_REPOSITORIES_TOOL = "prumo_workspace_get_repositories"
        const val GET_DOCUMENTATION_SOURCES_TOOL = "prumo_workspace_get_documentation_sources"
        const val PREPARE_TOOL = "prumo_workspace_prepare"
    }
}
