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
        "Use this tool to learn which Prumo workspace the open project belongs to: its name, " +
            "type, the repository the project sits in and how much documentation is attached. Call " +
            "prumo_workspace_prepare first for the full picture. Only the current workspace is " +
            "ever visible.",
    )
    suspend fun getContext(): WorkspaceContextResponse =
        prumoToolCall(GET_CONTEXT_TOOL, "workspace.get_context", PolicyAction.READ_REPOSITORY) { call ->
            WorkspaceReports.context(call.context)
        }

    @McpTool(name = GET_POLICY_TOOL)
    @McpDescription(
        "Use this tool to learn what this workspace allows, decided by the Prumo policy engine. " +
            "Call it before attempting an operation that may be denied, so you do not spend a turn " +
            "on a refusal.",
    )
    suspend fun getPolicy(): WorkspacePolicyResponse =
        prumoToolCall(GET_POLICY_TOOL, "workspace.get_policy", PolicyAction.READ_REPOSITORY) { call ->
            WorkspaceReports.policy(call.context)
        }

    @McpTool(name = GET_REPOSITORIES_TOOL)
    @McpDescription(
        "Use this tool to list the repositories of this workspace with their role, access mode, " +
            "the developer's description of each one and the paths excluded from it. The " +
            "repositoryId returned here is how every other Prumo tool addresses a repository: " +
            "absolute paths are never accepted. Call prumo_workspace_prepare first if you have " +
            "not yet.",
    )
    suspend fun getRepositories(): RepositoriesResponse =
        prumoToolCall(GET_REPOSITORIES_TOOL, "workspace.get_repositories", PolicyAction.READ_REPOSITORY) { call ->
            WorkspaceReports.repositories(call.context)
        }

    @McpTool(name = GET_DOCUMENTATION_SOURCES_TOOL)
    @McpDescription(
        "Use this tool to find the documentation the developer attached to this workspace: " +
            "specifications, manuals and glossaries that carry more authority than the code. It " +
            "returns each source with its authority level and whether Prumo can read it as text. " +
            "Catalogued formats such as PDF are reported as known but not extractable.",
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
        "Call this first, before reading anything in this project. A Prumo workspace is the " +
            "boundary an AI client may work within: which repositories, which documentation and " +
            "which databases are in reach, and what is allowed. This tool names every one of them " +
            "and returns READY, WARNING or ERROR with one check per repository and documentation " +
            "source, so you know what exists before you ask for it. Read-only: it never runs git " +
            "pull, checkout, reset or any other mutation.",
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
