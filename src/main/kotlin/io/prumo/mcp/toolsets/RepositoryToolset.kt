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
 * Alcança também o repositório vinculado que não está aberto na IDE, e impõe a fronteira do
 * workspace. Não duplica busca de símbolo, inspeção, build, teste nem refatoração.
 *
 * Toda tool é de leitura, e o conteúdo vem do disco: alteração não salva no editor não aparece.
 */
class RepositoryToolset : McpToolset {

    @McpTool(name = GET_STATUS_TOOL)
    @McpDescription(
        "Use this tool to read the Git status of any repository in this workspace: branch, " +
            "upstream, ahead/behind counters and the changed paths. It reaches repositories that " +
            "are not the open project, which the IDE's own tools cannot see. Read-only.",
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
        "Use this tool to learn which branch a bound repository is on, its upstream, how far it " +
            "is ahead or behind, and the local branch list. Works for every repository in this " +
            "workspace, not only the open project. Read-only.",
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
        "Use this tool to see what changed in a bound repository: added and deleted line counts " +
            "per file and, when a path is given, the unified patch for that file. Prefer it over " +
            "running git yourself: it never runs pull, checkout or reset, and it honours the paths " +
            "excluded for this repository.",
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
            if (path != null && RepositoryReader.isExcludedPath(path, call.repository.excludedPaths)) {
                throw RepositoryReadException(
                    "Path '$path' is excluded from this repository in the Prumo workspace, " +
                        "so Prumo does not show its changes.",
                )
            }
            withContext(Dispatchers.IO) {
                val deltas = GitStateParser.parseNumstat(executor.changedFiles(root, staged, path))
                val patch = path?.let { executor.patch(root, staged, it, PATCH_CONTEXT_LINES) }
                RepositoryReports.diff(call.repository, staged, deltas, patch)
            }
        }

    @McpTool(name = READ_FILE_TOOL)
    @McpDescription(
        "Use this tool to read a file inside this workspace. Prefer it over the IDE file tools: " +
            "it reaches repositories that are not the open project, and it refuses the paths the " +
            "developer excluded for this repository, which the IDE tools do not know about. " +
            "Address the file by repository id and a path relative to its root; absolute paths are " +
            "refused. Content comes from disk, so unsaved editor changes are not included.",
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
                RepositoryReader.readFile(
                    rootOf(call.repository),
                    path,
                    firstLine,
                    maxLines,
                    call.repository.excludedPaths,
                )
            }
            RepositoryReports.file(call.repository, slice)
        }

    @McpTool(name = SEARCH_TEXT_TOOL)
    @McpDescription(
        "Use this tool to search literal text across a repository of this workspace. Prefer it " +
            "over the IDE search: it covers repositories that are not the open project, and it " +
            "never reads binary files, the .git directory or the paths excluded for this " +
            "repository. The excluded list is in the repository entry of " +
            "prumo_workspace_get_repositories.",
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
                RepositoryReader.searchText(
                    root = rootOf(call.repository),
                    query = query,
                    scope = scope,
                    ignoreCase = ignoreCase,
                    maxResults = maxResults,
                    excluded = call.repository.excludedPaths,
                )
            }
            RepositoryReports.search(call.repository, query, outcome)
        }

    @McpTool(name = GET_STRUCTURE_TOOL)
    @McpDescription(
        "Use this tool to discover the layout of any repository in this workspace. Prefer it " +
            "over the IDE directory listing: it reaches repositories that are not the open " +
            "project, and it hides the paths the developer excluded for this repository, which " +
            "the IDE listing still shows.",
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
                RepositoryReader.listDirectory(
                    rootOf(call.repository),
                    path,
                    maxDepth,
                    maxEntries,
                    call.repository.excludedPaths,
                )
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
