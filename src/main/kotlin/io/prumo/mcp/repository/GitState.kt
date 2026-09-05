package io.prumo.mcp.repository

/** Como uma mudança aparece para o Git no momento da leitura. */
enum class GitChangeKind {
    STAGED,
    UNSTAGED,
    UNTRACKED,
    IGNORED,
    CONFLICTED,
}

/**
 * Uma mudança, sempre por caminho relativo à raiz do repositório.
 *
 * Os códigos de índice e de árvore de trabalho são preservados como o Git os reporta.
 */
data class GitChange(
    val path: String,
    val kind: GitChangeKind,
    val indexStatus: String,
    val worktreeStatus: String,
)

data class GitBranchState(
    val branch: String?,
    val commit: String?,
    val upstream: String?,
    val ahead: Int?,
    val behind: Int?,
    val detached: Boolean,
)

data class GitStatusSnapshot(
    val branch: GitBranchState,
    val changes: List<GitChange>,
)

/** Linhas alteradas por arquivo. `null` em arquivo binário, que o Git não conta em linhas. */
data class GitFileDelta(
    val path: String,
    val addedLines: Int?,
    val deletedLines: Int?,
    val binary: Boolean,
)

/** Interpreta a saída dos comandos de porcelana estável do Git. */
object GitStateParser {

    private const val UNCHANGED = "."

    /** Saída de `git status --porcelain=v2 --branch`. O formato v2 é o único estável e documentado. */
    fun parseStatus(lines: List<String>): GitStatusSnapshot {
        var branch: String? = null
        var commit: String? = null
        var upstream: String? = null
        var ahead: Int? = null
        var behind: Int? = null
        val changes = mutableListOf<GitChange>()

        for (line in lines) {
            when {
                line.startsWith("# branch.oid ") -> commit = line.removePrefix("# branch.oid ").trim()
                    .takeIf { it != "(initial)" }

                line.startsWith("# branch.head ") -> branch = line.removePrefix("# branch.head ").trim()
                    .takeIf { it != "(detached)" }

                line.startsWith("# branch.upstream ") -> upstream = line.removePrefix("# branch.upstream ").trim()

                line.startsWith("# branch.ab ") -> {
                    val parts = line.removePrefix("# branch.ab ").trim().split(' ')
                    ahead = parts.getOrNull(0)?.removePrefix("+")?.toIntOrNull()
                    behind = parts.getOrNull(1)?.removePrefix("-")?.toIntOrNull()
                }

                line.startsWith("1 ") || line.startsWith("2 ") -> trackedChange(line)?.let(changes::add)

                line.startsWith("u ") -> unmergedChange(line)?.let(changes::add)

                line.startsWith("? ") -> changes.add(
                    GitChange(line.removePrefix("? ").trim(), GitChangeKind.UNTRACKED, UNCHANGED, "?"),
                )

                line.startsWith("! ") -> changes.add(
                    GitChange(line.removePrefix("! ").trim(), GitChangeKind.IGNORED, UNCHANGED, "!"),
                )
            }
        }

        return GitStatusSnapshot(
            branch = GitBranchState(
                branch = branch,
                commit = commit,
                upstream = upstream,
                ahead = ahead,
                behind = behind,
                detached = branch == null,
            ),
            changes = changes,
        )
    }

    /** Saída de `git diff --numstat`, com `-` no lugar da contagem quando o arquivo é binário. */
    fun parseNumstat(lines: List<String>): List<GitFileDelta> =
        lines.mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 3) {
                return@mapNotNull null
            }
            val path = fields.drop(2).joinToString("\t").trim()
            if (path.isEmpty()) {
                return@mapNotNull null
            }
            val added = fields[0].toIntOrNull()
            val deleted = fields[1].toIntOrNull()
            GitFileDelta(
                path = path,
                addedLines = added,
                deletedLines = deleted,
                binary = added == null && deleted == null,
            )
        }

    /** Saída de `git branch --format=%(refname:short)`. */
    fun parseBranchList(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Linhas `1` (alteração comum) e `2` (renomeada ou copiada). Na linha `2` o campo final traz
     * o caminho novo e o antigo separados por tabulação; o caminho novo é o que endereça o arquivo.
     */
    private fun trackedChange(line: String): GitChange? {
        val fields = line.split(' ')
        val statusField = fields.getOrNull(1) ?: return null
        if (statusField.length < 2) {
            return null
        }
        val pathFieldIndex = if (line.startsWith("2 ")) 9 else 8
        val path = fields.drop(pathFieldIndex).joinToString(" ").substringBefore('\t').trim()
        if (path.isEmpty()) {
            return null
        }
        val index = statusField.substring(0, 1)
        val worktree = statusField.substring(1, 2)
        return GitChange(
            path = path,
            kind = if (index == UNCHANGED) GitChangeKind.UNSTAGED else GitChangeKind.STAGED,
            indexStatus = index,
            worktreeStatus = worktree,
        )
    }

    private fun unmergedChange(line: String): GitChange? {
        val fields = line.split(' ')
        val statusField = fields.getOrNull(1) ?: return null
        if (statusField.length < 2) {
            return null
        }
        val path = fields.drop(10).joinToString(" ").trim()
        if (path.isEmpty()) {
            return null
        }
        return GitChange(
            path = path,
            kind = GitChangeKind.CONFLICTED,
            indexStatus = statusField.substring(0, 1),
            worktreeStatus = statusField.substring(1, 2),
        )
    }
}
