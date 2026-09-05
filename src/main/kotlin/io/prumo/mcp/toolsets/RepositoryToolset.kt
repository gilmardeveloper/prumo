package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.ide.GitCommandExecutor
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.repository.GitStateParser
import io.prumo.mcp.repository.RepositoryReadException
import io.prumo.mcp.repository.RepositoryReader
import io.prumo.mcp.workspace.domain.RepositoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Superfície MCP dos repositórios vinculados ao workspace.
 *
 * Existe pelo que o MCP nativo **não** faz: as tools da plataforma leem o projeto aberto e não
 * conhecem workspace, então não alcançam o repositório de referência que está vinculado mas não
 * aberto na IDE, e não impõem fronteira alguma. Nada aqui duplica busca de símbolo, inspeção,
 * build, teste ou refatoração — para isso o cliente usa as tools nativas.
 *
 * Toda tool é de leitura. O conteúdo vem do disco: alteração ainda não salva no editor não aparece.
 */
class RepositoryToolset : McpToolset {

    @McpTool(name = GET_STATUS_TOOL)
    @McpDescription(
        "Returns the Git status of a repository bound to the current workspace: branch, upstream, " +
            "ahead/behind counters and the changed paths. Read-only.",
    )
    suspend fun getStatus(
        @McpDescription("Repository id from prumo_workspace_get_repositories. Defaults to the current repository.")
        repositoryId: String? = null,
    ): RepositoryStatusResponse =
        repositoryCall(GET_STATUS_TOOL, "repository.get_status", repositoryId) { call ->
            val output = withContext(Dispatchers.IO) {
                GitCommandExecutor(call.project).status(rootOf(call.repository))
            }
            RepositoryReports.status(call.repository, GitStateParser.parseStatus(output))
        }

    @McpTool(name = GET_BRANCH_TOOL)
    @McpDescription(
        "Returns the current branch of a bound repository, its upstream and how far it is ahead or " +
            "behind, plus the local branch list. Read-only.",
    )
    suspend fun getBranch(
        @McpDescription("Repository id from prumo_workspace_get_repositories. Defaults to the current repository.")
        repositoryId: String? = null,
    ): RepositoryBranchResponse =
        repositoryCall(GET_BRANCH_TOOL, "repository.get_branch", repositoryId) { call ->
            val root = rootOf(call.repository)
            val executor = GitCommandExecutor(call.project)
            withContext(Dispatchers.IO) {
                val branch = GitStateParser.parseStatus(executor.status(root)).branch
                val branches = GitStateParser.parseBranchList(executor.branches(root))
                RepositoryReports.branches(call.repository, branch, branches)
            }
        }

    @McpTool(name = GET_DIFF_TOOL)
    @McpDescription(
        "Returns what changed in a bound repository: added and deleted line counts per file and, " +
            "when a path is given, the unified patch for that file. Never runs pull, checkout or reset.",
    )
    suspend fun getDiff(
        @McpDescription("Repository id from prumo_workspace_get_repositories. Defaults to the current repository.")
        repositoryId: String? = null,
        @McpDescription("Path relative to the repository root. Required to receive the patch text.")
        path: String? = null,
        @McpDescription("True to describe the staged changes instead of the working tree.")
        staged: Boolean = false,
    ): RepositoryDiffResponse =
        repositoryCall(GET_DIFF_TOOL, "repository.get_diff", repositoryId) { call ->
            val root = rootOf(call.repository)
            val executor = GitCommandExecutor(call.project)
            withContext(Dispatchers.IO) {
                val deltas = GitStateParser.parseNumstat(executor.changedFiles(root, staged, path))
                val patch = path?.let { executor.patch(root, staged, it, PATCH_CONTEXT_LINES) }
                RepositoryReports.diff(call.repository, staged, deltas, patch)
            }
        }

    @McpTool(name = READ_FILE_TOOL)
    @McpDescription(
        "Reads a text file from a repository bound to the current workspace, addressed by " +
            "repository id and a path relative to its root. Absolute paths are refused. Content " +
            "comes from disk, so unsaved editor changes are not included.",
    )
    suspend fun readFile(
        @McpDescription("Path relative to the repository root.")
        path: String,
        @McpDescription("Repository id from prumo_workspace_get_repositories. Defaults to the current repository.")
        repositoryId: String? = null,
        @McpDescription("First line to return, starting at 1.")
        firstLine: Int = 1,
        @McpDescription("How many lines to return at most.")
        maxLines: Int = 400,
    ): FileContentResponse =
        repositoryCall(READ_FILE_TOOL, "repository.read_file", repositoryId) { call ->
            val slice = withContext(Dispatchers.IO) {
                RepositoryReader.readFile(rootOf(call.repository), path, firstLine, maxLines)
            }
            RepositoryReports.file(call.repository, slice)
        }

    @McpTool(name = SEARCH_TEXT_TOOL)
    @McpDescription(
        "Searches for literal text inside a repository bound to the current workspace and returns " +
            "the matching paths and lines. Binary files and the .git directory are never read.",
    )
    suspend fun searchText(
        @McpDescription("Text to look for.")
        query: String,
        @McpDescription("Repository id from prumo_workspace_get_repositories. Defaults to the current repository.")
        repositoryId: String? = null,
        @McpDescription("Directory relative to the repository root to restrict the search to.")
        scope: String? = null,
        @McpDescription("False to make the search case sensitive.")
        ignoreCase: Boolean = true,
        @McpDescription("How many matches to return at most.")
        maxResults: Int = 50,
    ): TextSearchResponse =
        repositoryCall(SEARCH_TEXT_TOOL, "repository.search_text", repositoryId) { call ->
            val outcome = withContext(Dispatchers.IO) {
                RepositoryReader.searchText(rootOf(call.repository), query, scope, ignoreCase, maxResults)
            }
            RepositoryReports.search(call.repository, query, outcome)
        }

    @McpTool(name = GET_STRUCTURE_TOOL)
    @McpDescription(
        "Lists directories and files of a repository bound to the current workspace. Use it to " +
            "discover the layout of a bound repository that is not the open project — for the open " +
            "project the IDE's own project tools already answer.",
    )
    suspend fun getStructure(
        @McpDescription("Repository id from prumo_workspace_get_repositories. Defaults to the current repository.")
        repositoryId: String? = null,
        @McpDescription("Directory relative to the repository root. Empty means the root itself.")
        path: String? = null,
        @McpDescription("How many directory levels to descend.")
        maxDepth: Int = 2,
        @McpDescription("How many entries to return at most.")
        maxEntries: Int = 300,
    ): RepositoryStructureResponse =
        repositoryCall(GET_STRUCTURE_TOOL, "project.get_structure", repositoryId) { call ->
            val listing = withContext(Dispatchers.IO) {
                RepositoryReader.listDirectory(rootOf(call.repository), path, maxDepth, maxEntries)
            }
            RepositoryReports.structure(call.repository, listing)
        }

    private suspend fun <T> repositoryCall(
        tool: String,
        operation: String,
        repositoryId: String?,
        block: suspend (PrumoCall) -> T,
    ): T = prumoToolCall(
        tool = tool,
        operation = operation,
        action = PolicyAction.READ_REPOSITORY,
        repository = { context ->
            repositoryId?.let(context::repository) ?: context.currentRepository
        },
        block = block,
    )

    private fun rootOf(binding: RepositoryBinding): Path = try {
        Path.of(binding.localPath)
    } catch (_: InvalidPathException) {
        throw RepositoryReadException(
            "The directory bound to repository '${binding.name}' is not a valid path on this machine.",
        )
    }

    private companion object {
        const val GET_STATUS_TOOL = "prumo_repository_get_status"
        const val GET_BRANCH_TOOL = "prumo_repository_get_branch"
        const val GET_DIFF_TOOL = "prumo_repository_get_diff"
        const val READ_FILE_TOOL = "prumo_repository_read_file"
        const val SEARCH_TEXT_TOOL = "prumo_repository_search_text"
        const val GET_STRUCTURE_TOOL = "prumo_repository_get_structure"

        const val PATCH_CONTEXT_LINES = 3
    }
}
