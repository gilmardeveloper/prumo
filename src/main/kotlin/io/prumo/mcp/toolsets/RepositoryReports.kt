package io.prumo.mcp.toolsets

import io.prumo.mcp.ide.EditorSnapshot
import io.prumo.mcp.repository.DirectoryListing
import io.prumo.mcp.repository.FileSlice
import io.prumo.mcp.repository.GitBranchState
import io.prumo.mcp.repository.GitFileDelta
import io.prumo.mcp.repository.GitStatusSnapshot
import io.prumo.mcp.repository.TextSearchOutcome
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.domain.RepositoryBinding
import kotlinx.serialization.Serializable
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Serializable
data class BranchResponse(
    val branch: String?,
    val commit: String?,
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val detached: Boolean,
)

@Serializable
data class ChangeResponse(
    val path: String,
    val kind: String,
    val indexStatus: String,
    val worktreeStatus: String,
)

@Serializable
data class RepositoryStatusResponse(
    val repositoryId: String,
    val name: String,
    val accessMode: String,
    val branch: BranchResponse,
    val changes: List<ChangeResponse>,
    val changeCount: Int,
    val truncated: Boolean,
)

@Serializable
data class RepositoryBranchResponse(
    val repositoryId: String,
    val current: BranchResponse,
    val branches: List<String>,
    val truncated: Boolean,
)

@Serializable
data class FileDeltaResponse(
    val path: String,
    val addedLines: Int?,
    val deletedLines: Int?,
    val binary: Boolean,
)

@Serializable
data class RepositoryDiffResponse(
    val repositoryId: String,
    val staged: Boolean,
    val files: List<FileDeltaResponse>,
    val patch: String? = null,
    val patchTruncated: Boolean = false,
)

@Serializable
data class FileContentResponse(
    val repositoryId: String,
    val path: String,
    val text: String,
    val firstLine: Int,
    val lastLine: Int,
    val totalLines: Int,
    val truncated: Boolean,
)

@Serializable
data class TextMatchResponse(
    val path: String,
    val line: Int,
    val text: String,
)

@Serializable
data class TextSearchResponse(
    val repositoryId: String,
    val query: String,
    val matches: List<TextMatchResponse>,
    val filesScanned: Int,
    val truncated: Boolean,
)

@Serializable
data class StructureEntryResponse(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long? = null,
)

@Serializable
data class RepositoryStructureResponse(
    val repositoryId: String,
    val path: String,
    val entries: List<StructureEntryResponse>,
    val truncated: Boolean,
)

/**
 * Onde o desenvolvedor está agora, do ponto de vista do workspace.
 *
 * `insideWorkspace` falso significa que o arquivo aberto não pertence a repositório algum deste
 * workspace, e nesse caso nada além disso é informado.
 */
@Serializable
data class IdeContextResponse(
    val insideWorkspace: Boolean,
    val repositoryId: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val selectionStartLine: Int? = null,
    val selectionEndLine: Int? = null,
    val selectionLength: Int? = null,
    val symbolPath: List<String> = emptyList(),
    val language: String? = null,
    val moduleName: String? = null,
)

/**
 * Respostas das tools de repositório, projeto e IDE.
 *
 * Caminho absoluto não sai: todo caminho é relativo à raiz do repositório que o cliente endereçou
 * por identificador.
 */
object RepositoryReports {

    const val MAX_CHANGES = 200
    const val MAX_BRANCHES = 200
    const val MAX_PATCH_LINES = 400

    fun status(binding: RepositoryBinding, snapshot: GitStatusSnapshot): RepositoryStatusResponse {
        val visible = snapshot.changes.take(MAX_CHANGES)
        return RepositoryStatusResponse(
            repositoryId = binding.id,
            name = binding.name,
            accessMode = binding.accessMode.name,
            branch = snapshot.branch.toResponse(),
            changes = visible.map {
                ChangeResponse(it.path, it.kind.name, it.indexStatus, it.worktreeStatus)
            },
            changeCount = snapshot.changes.size,
            truncated = snapshot.changes.size > visible.size,
        )
    }

    fun branches(
        binding: RepositoryBinding,
        branch: GitBranchState,
        branches: List<String>,
    ): RepositoryBranchResponse {
        val visible = branches.take(MAX_BRANCHES)
        return RepositoryBranchResponse(
            repositoryId = binding.id,
            current = branch.toResponse(),
            branches = visible,
            truncated = branches.size > visible.size,
        )
    }

    fun diff(
        binding: RepositoryBinding,
        staged: Boolean,
        deltas: List<GitFileDelta>,
        patchLines: List<String>? = null,
    ): RepositoryDiffResponse {
        val visiblePatch = patchLines?.take(MAX_PATCH_LINES)
        return RepositoryDiffResponse(
            repositoryId = binding.id,
            staged = staged,
            files = deltas.map { FileDeltaResponse(it.path, it.addedLines, it.deletedLines, it.binary) },
            patch = visiblePatch?.joinToString("\n"),
            patchTruncated = patchLines != null && visiblePatch != null && patchLines.size > visiblePatch.size,
        )
    }

    fun file(binding: RepositoryBinding, slice: FileSlice): FileContentResponse =
        FileContentResponse(
            repositoryId = binding.id,
            path = slice.path,
            text = slice.text,
            firstLine = slice.firstLine,
            lastLine = slice.lastLine,
            totalLines = slice.totalLines,
            truncated = slice.truncated,
        )

    fun search(binding: RepositoryBinding, query: String, outcome: TextSearchOutcome): TextSearchResponse =
        TextSearchResponse(
            repositoryId = binding.id,
            query = query,
            matches = outcome.matches.map { TextMatchResponse(it.path, it.line, it.text) },
            filesScanned = outcome.filesScanned,
            truncated = outcome.truncated,
        )

    fun structure(binding: RepositoryBinding, listing: DirectoryListing): RepositoryStructureResponse =
        RepositoryStructureResponse(
            repositoryId = binding.id,
            path = listing.path,
            entries = listing.entries.map { StructureEntryResponse(it.path, it.directory, it.sizeBytes) },
            truncated = listing.truncated,
        )

    /**
     * O que o cliente pode saber sobre a posição do editor.
     *
     * Arquivo fora de todo repositório vinculado devolve apenas `insideWorkspace = false`.
     */
    fun ideContext(context: WorkspaceContext, snapshot: EditorSnapshot?): IdeContextResponse {
        val located = snapshot?.let { locate(context, it.absolutePath) }
            ?: return IdeContextResponse(insideWorkspace = false)
        val (binding, relativePath) = located
        return IdeContextResponse(
            insideWorkspace = true,
            repositoryId = binding.id,
            path = relativePath,
            line = snapshot.line,
            column = snapshot.column,
            selectionStartLine = snapshot.selectionStartLine,
            selectionEndLine = snapshot.selectionEndLine,
            selectionLength = snapshot.selectionLength,
            symbolPath = snapshot.symbolPath.withoutRepeatedNames(),
            language = snapshot.language,
            moduleName = snapshot.moduleName,
        )
    }

    /**
     * Descobre a qual repositório do workspace um caminho absoluto pertence.
     *
     * Devolve o identificador do repositório e o caminho relativo, ou nulo fora de todo repositório
     * vinculado.
     */
    fun locate(context: WorkspaceContext, absolutePath: String): Pair<RepositoryBinding, String>? {
        val target = pathOrNull(absolutePath)?.toAbsolutePath()?.normalize() ?: return null
        return context.workspace.repositories
            .mapNotNull { binding ->
                val root = pathOrNull(binding.localPath)?.toAbsolutePath()?.normalize()
                if (root != null && target.startsWith(root)) {
                    binding to root.relativize(target).toString().replace('\\', '/')
                } else {
                    null
                }
            }
            // O vínculo mais específico ganha: a raiz mais profunda produz o caminho relativo mais curto.
            .minByOrNull { (_, relative) -> relative.count { it == '/' } }
    }

    /**
     * Colapsa nomes repetidos em sequência na cadeia de símbolos.
     *
     * Em Kotlin o construtor primário responde pelo nome da própria classe, então um atributo
     * declarado no construtor produziria `Classe > Classe > atributo`. A repetição é ruído do PSI,
     * não estrutura do código.
     */
    private fun List<String>.withoutRepeatedNames(): List<String> =
        filterIndexed { index, name -> index == 0 || name != this[index - 1] }

    private fun GitBranchState.toResponse() = BranchResponse(
        branch = branch,
        commit = commit,
        upstream = upstream,
        ahead = ahead,
        behind = behind,
        detached = detached,
    )

    private fun pathOrNull(value: String): Path? = try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        null
    }
}
