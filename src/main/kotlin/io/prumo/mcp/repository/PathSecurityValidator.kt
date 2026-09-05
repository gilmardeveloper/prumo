package io.prumo.mcp.repository

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Motivo pelo qual um caminho foi recusado. A recusa é sempre explícita: um caminho fora da
 * fronteira nunca vira "melhor esforço" nem cai em um diretório vizinho.
 */
enum class PathRejection {
    ABSOLUTE_PATH,
    PARENT_TRAVERSAL,
    ROOT_ESCAPE,
    INVALID_SYNTAX,
    EMPTY_PATH,
}

class PathAccessDeniedException(
    val rejection: PathRejection,
    val relativePath: String,
) : SecurityException(
    "Prumo MCP refused the path '$relativePath': ${rejection.name.lowercase().replace('_', ' ')}.",
)

/**
 * Resolve caminhos relativos contra a raiz de um repositório.
 *
 * Um cliente MCP nunca informa caminho absoluto: informa o identificador do repositório e um
 * caminho relativo, e a resolução acontece aqui. A verificação é feita duas vezes — sobre o caminho
 * normalizado e, quando o alvo existe, sobre o caminho real do sistema de arquivos — porque
 * normalização sozinha não enxerga link simbólico, junction nem ponto de montagem.
 */
object PathSecurityValidator {

    fun resolve(repositoryRoot: Path, relativePath: String): Path {
        if (relativePath.isBlank()) {
            throw PathAccessDeniedException(PathRejection.EMPTY_PATH, relativePath)
        }
        // Nome de arquivo com espaco e legitimo; caractere de controle e NUL nao sao, e alguns
        // sistemas de arquivos os truncam em silencio.
        if (relativePath.any { it.isISOControl() }) {
            throw PathAccessDeniedException(PathRejection.INVALID_SYNTAX, relativePath)
        }

        val candidate = try {
            Path.of(relativePath.replace('\\', '/'))
        } catch (_: InvalidPathException) {
            throw PathAccessDeniedException(PathRejection.INVALID_SYNTAX, relativePath)
        }

        if (candidate.isAbsolute || looksAbsolute(relativePath)) {
            throw PathAccessDeniedException(PathRejection.ABSOLUTE_PATH, relativePath)
        }
        if (candidate.any { it.toString() == ".." }) {
            throw PathAccessDeniedException(PathRejection.PARENT_TRAVERSAL, relativePath)
        }

        val root = repositoryRoot.toAbsolutePath().normalize()
        val resolved = root.resolve(candidate).normalize()
        if (!resolved.startsWith(root)) {
            throw PathAccessDeniedException(PathRejection.ROOT_ESCAPE, relativePath)
        }

        assertRealPathStaysInside(root, resolved, relativePath)
        return resolved
    }

    /**
     * Confere o caminho já resolvido pelo sistema de arquivos. Só é possível quando o alvo existe;
     * para criação futura, a checagem recai sobre o diretório-pai mais próximo que exista.
     */
    private fun assertRealPathStaysInside(root: Path, resolved: Path, relativePath: String) {
        val realRoot = try {
            root.toRealPath()
        } catch (_: IOException) {
            return
        }

        var existing: Path? = resolved
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.parent
        }
        if (existing == null) {
            return
        }

        val realTarget = try {
            existing.toRealPath()
        } catch (_: IOException) {
            throw PathAccessDeniedException(PathRejection.ROOT_ESCAPE, relativePath)
        }
        if (!realTarget.startsWith(realRoot)) {
            throw PathAccessDeniedException(PathRejection.ROOT_ESCAPE, relativePath)
        }
    }

    /**
     * `Path.isAbsolute` decide segundo o sistema operacional corrente, então um caminho Windows
     * passaria despercebido em Linux e vice-versa. Um cliente MCP pode rodar em qualquer lugar.
     */
    private fun looksAbsolute(value: String): Boolean =
        value.startsWith("/") ||
            value.startsWith("\\") ||
            WINDOWS_ABSOLUTE.matches(value) ||
            value.startsWith("~")

    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
}
