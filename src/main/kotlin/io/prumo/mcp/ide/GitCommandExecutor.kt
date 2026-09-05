package io.prumo.mcp.ide

import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Path

/** Falha ao consultar o Git de um repositório vinculado. A mensagem nunca carrega caminho de disco. */
class GitReadException(message: String) : IllegalStateException(message)

/**
 * Leitura do estado do Git pelo plugin Git da IDE.
 *
 * Usa o executável que o usuário já configurou. Só lê: não existe aqui método que faça `pull`,
 * `checkout`, `reset` ou commit.
 */
class GitCommandExecutor(private val project: Project) {

    fun status(root: Path): List<String> =
        run(root, GitCommand.STATUS, "--porcelain=v2", "--branch")

    fun branches(root: Path): List<String> =
        run(root, GitCommand.BRANCH, "--list", "--format=%(refname:short)")

    fun changedFiles(root: Path, staged: Boolean, relativePath: String?): List<String> =
        run(root, GitCommand.DIFF, *diffArguments("--numstat", staged, relativePath))

    fun patch(root: Path, staged: Boolean, relativePath: String?, contextLines: Int): List<String> =
        run(
            root,
            GitCommand.DIFF,
            *diffArguments("--unified=$contextLines", staged, relativePath),
        )

    private fun diffArguments(mode: String, staged: Boolean, relativePath: String?): Array<String> =
        buildList {
            add(mode)
            if (staged) {
                add("--cached")
            }
            if (!relativePath.isNullOrBlank()) {
                add("--")
                add(relativePath)
            }
        }.toTypedArray()

    private fun run(root: Path, command: GitCommand, vararg parameters: String): List<String> {
        val handler = GitLineHandler(project, root, command)
        handler.addParameters(*parameters)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) {
            throw GitReadException(
                "Git could not read this repository: ${sanitize(result.errorOutputAsJoinedString, root)}",
            )
        }
        return result.output
    }

    /** A saída de erro do Git cita o diretório; o cliente recebe o papel do repositório. */
    private fun sanitize(message: String, root: Path): String {
        val normalized = message.ifBlank { "the command finished with an error." }
        return listOf(root.toString(), root.toString().replace('\\', '/'))
            .fold(normalized) { text, path -> text.replace(path, "<repository>") }
            .trim()
    }
}
