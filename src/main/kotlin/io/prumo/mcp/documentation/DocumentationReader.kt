package io.prumo.mcp.documentation

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Recusa da leitura de documentação, com a mensagem que vai para o cliente. */
class DocumentationReadException(message: String) : RuntimeException(message)

/** Trecho de um documento, com a posição para pedir a continuação. */
data class DocumentSlice(
    val documentationId: String,
    val path: String,
    val text: String,
    val firstLine: Int,
    val lastLine: Int,
    val totalLines: Int,
    val truncated: Boolean,
)

/**
 * Lê o conteúdo de uma fonte de documentação do workspace.
 *
 * A fonte é endereçada pelo identificador cadastrado, nunca por caminho vindo do cliente. Quando a
 * fonte é uma pasta, o arquivo interno é resolvido contra a raiz dela e precisa permanecer dentro:
 * o alcance da leitura é o que o desenvolvedor cadastrou, e nada além.
 */
object DocumentationReader {

    const val DEFAULT_MAX_LINES = 400
    private const val MAX_LINES_CEILING = 2_000

    /**
     * @param source fonte cadastrada no workspace.
     * @param relativePath arquivo dentro da fonte, obrigatório quando ela é uma pasta.
     * @throws DocumentationReadException quando a fonte sumiu, o formato não é lido como texto, o
     *   caminho é absoluto, escapa da raiz cadastrada ou não existe.
     */
    fun read(
        source: DocumentationSource,
        relativePath: String? = null,
        firstLine: Int = 1,
        maxLines: Int = DEFAULT_MAX_LINES,
    ): DocumentSlice {
        require(firstLine >= 1) { "firstLine must be 1 or greater." }
        require(maxLines >= 1) { "maxLines must be 1 or greater." }

        val root = locationOf(source)
        val target = resolveTarget(source, root, relativePath)

        if (!SupportedDocumentFormats.isReadableAsText(target)) {
            throw DocumentationReadException(
                "Documentation source '${source.id}' is catalogued but Prumo does not extract text from " +
                    "'${target.name}'. Ask the developer for a text version, or work from the sources " +
                    "Prumo can read.",
            )
        }

        val lines = runCatching { Files.readAllLines(target) }.getOrElse {
            throw DocumentationReadException("Prumo could not read '${target.name}' as text.")
        }
        val limit = maxLines.coerceAtMost(MAX_LINES_CEILING)
        val from = (firstLine - 1).coerceAtMost(lines.size)
        val slice = lines.drop(from).take(limit)

        return DocumentSlice(
            documentationId = source.id,
            path = relativePath ?: target.name,
            text = slice.joinToString("\n"),
            firstLine = if (slice.isEmpty()) firstLine else from + 1,
            lastLine = from + slice.size,
            totalLines = lines.size,
            truncated = from + slice.size < lines.size,
        )
    }

    /** Enumera os arquivos legíveis de uma fonte que é pasta. */
    fun list(source: DocumentationSource, maxEntries: Int = 200): List<String> {
        val root = locationOf(source)
        if (!root.isDirectory()) {
            return listOf(root.name)
        }
        return Files.walk(root).use { paths ->
            paths.filter(Path::isRegularFile)
                .filter(SupportedDocumentFormats::isSupported)
                .map { root.relativize(it).joinToString("/") { part -> part.toString() } }
                .sorted()
                .limit(maxEntries.toLong())
                .toList()
        }
    }

    private fun locationOf(source: DocumentationSource): Path {
        val location = runCatching { Path.of(source.location) }.getOrElse {
            throw DocumentationReadException("Documentation source '${source.id}' has an unreadable location.")
        }
        if (!Files.exists(location)) {
            throw DocumentationReadException(
                "Documentation source '${source.id}' is no longer where the workspace expects it.",
            )
        }
        return location.toRealPath()
    }

    private fun resolveTarget(source: DocumentationSource, root: Path, relativePath: String?): Path {
        if (!root.isDirectory()) {
            if (relativePath != null) {
                throw DocumentationReadException(
                    "Documentation source '${source.id}' is a single file: do not pass a path.",
                )
            }
            return root
        }

        if (relativePath.isNullOrBlank()) {
            throw DocumentationReadException(
                "Documentation source '${source.id}' is a folder: pass the path of a file inside it.",
            )
        }

        val candidate = runCatching { Path.of(relativePath) }.getOrElse {
            throw DocumentationReadException("Prumo refused the path '$relativePath': it is not a valid path.")
        }
        if (candidate.isAbsolute) {
            throw DocumentationReadException("Prumo refused the path '$relativePath': absolute path.")
        }
        if (candidate.any { it.toString() == ".." }) {
            throw DocumentationReadException("Prumo refused the path '$relativePath': parent traversal.")
        }

        val resolved = try {
            root.resolve(candidate).normalize()
        } catch (_: InvalidPathException) {
            throw DocumentationReadException("Prumo refused the path '$relativePath': it is not a valid path.")
        }
        if (!Files.exists(resolved)) {
            throw DocumentationReadException("'$relativePath' does not exist in documentation source '${source.id}'.")
        }

        val real = resolved.toRealPath()
        if (!real.startsWith(root)) {
            throw DocumentationReadException(
                "Prumo refused the path '$relativePath': it leaves the documentation source.",
            )
        }
        return real
    }
}
