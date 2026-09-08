package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.policy.PolicyAction
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.prumo.mcp.documentation.DocumentationReader
import io.prumo.mcp.documentation.DocumentationReadException

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
            "Database actions are decided per bound database and list every one of them, so an " +
            "action shown as allowed names the databases that allow it. Call it before attempting " +
            "an operation that may be denied, so you do not spend a turn on a refusal.",
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

    @McpTool(name = READ_DOCUMENTATION_TOOL)
    @McpDescription(
        "Use this tool to read the documentation the developer attached to this workspace. Prefer " +
            "it over inferring a rule from the code: a specification or manual listed here carries " +
            "more authority than an implementation. Address the source by the documentationId from " +
            "prumo_workspace_get_documentation_sources; when the source is a folder, pass the path " +
            "of a file inside it. Binary formats — PDF, DOCX, XLSX and PPTX — are read by extracting " +
            "their content: you get the text and nothing else, because style, theme, document " +
            "properties and drawings are dropped before the answer is built. Extraction is plain " +
            "parsing, never a model: what comes back is verbatim from the file. In those formats the " +
            "line numbers belong to the extracted text and address nothing in the original, so the " +
            "answer carries 'coordinates' instead — the page of a PDF, the sheet and row of a " +
            "spreadsheet, the paragraph or the slide. Cite those, not the line. A scanned PDF, whose " +
            "pages are images and carry no text layer, is refused rather than returned empty.",
    )
    suspend fun readDocumentation(
        @McpDescription("Documentation id from prumo_workspace_get_documentation_sources.")
        documentationId: String,
        @McpDescription("Path of a file inside the source, relative to its root. Only for folder sources.")
        path: String? = null,
        @McpDescription("First line to return, starting at 1.")
        firstLine: Int = 1,
        @McpDescription("How many lines to return at most.")
        maxLines: Int = DocumentationReader.DEFAULT_MAX_LINES,
    ): DocumentContentResponse =
        prumoToolCall(
            READ_DOCUMENTATION_TOOL,
            "workspace.read_documentation",
            PolicyAction.READ_DOCUMENTATION,
        ) { call ->
            val source = call.context.workspace.documentation.firstOrNull { it.id == documentationId }
                ?: throw DocumentationReadException(
                    "Documentation source '$documentationId' is not attached to this workspace.",
                )
            val slice = withContext(Dispatchers.IO) {
                DocumentationReader.read(source, path, firstLine, maxLines)
            }
            DocumentContentResponse(
                documentationId = slice.documentationId,
                name = source.name,
                authority = source.authority.name,
                path = slice.path,
                text = slice.text,
                coordinates = slice.coordinates.map {
                    CoordinateResponse(it.coordinate, it.firstLine, it.lastLine)
                },
                firstLine = slice.firstLine,
                lastLine = slice.lastLine,
                totalLines = slice.totalLines,
                truncated = slice.truncated,
            )
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
        const val READ_DOCUMENTATION_TOOL = "prumo_workspace_read_documentation"
    }
}
