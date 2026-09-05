package io.prumo.mcp.repository

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Falha de leitura: arquivo ausente, binário ou grande demais. */
class RepositoryReadException(message: String) : IllegalArgumentException(message)

data class FileSlice(
    val path: String,
    val text: String,
    val firstLine: Int,
    val lastLine: Int,
    val totalLines: Int,
    val truncated: Boolean,
)

data class TextMatch(
    val path: String,
    val line: Int,
    val text: String,
)

data class TextSearchOutcome(
    val matches: List<TextMatch>,
    val truncated: Boolean,
    val filesScanned: Int,
)

data class DirectoryEntry(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long?,
)

data class DirectoryListing(
    val path: String,
    val entries: List<DirectoryEntry>,
    val truncated: Boolean,
)

/**
 * Leitura de arquivos dentro de um repositório vinculado ao workspace.
 *
 * Todo acesso entra por [PathSecurityValidator]: o cliente informa o identificador do repositório e
 * um caminho relativo, e nada fora da raiz é alcançável.
 *
 * As respostas têm teto de tamanho e declaram quando foram cortadas.
 */
object RepositoryReader {

    private const val BINARY_PROBE_BYTES = 8_000
    private const val MAX_READABLE_BYTES = 2L * 1024 * 1024
    private const val SNIPPET_LENGTH = 200

    fun readFile(root: Path, relativePath: String, firstLine: Int = 1, maxLines: Int = 400): FileSlice {
        require(firstLine >= 1) { "firstLine must be 1 or greater." }
        require(maxLines >= 1) { "maxLines must be 1 or greater." }

        val file = PathSecurityValidator.resolve(root, relativePath)
        if (!file.isRegularFile()) {
            throw RepositoryReadException("File '$relativePath' does not exist in this repository.")
        }
        assertReadableAsText(file, relativePath)

        val lines = try {
            Files.readAllLines(file, StandardCharsets.UTF_8)
        } catch (failure: IOException) {
            throw RepositoryReadException("File '$relativePath' could not be read as UTF-8 text: ${failure.message}")
        }

        val from = (firstLine - 1).coerceAtMost(lines.size)
        val to = (from + maxLines).coerceAtMost(lines.size)
        val slice = lines.subList(from, to)
        return FileSlice(
            path = normalize(relativePath),
            text = slice.joinToString("\n"),
            firstLine = if (slice.isEmpty()) 0 else from + 1,
            lastLine = if (slice.isEmpty()) 0 else to,
            totalLines = lines.size,
            truncated = to < lines.size,
        )
    }

    fun searchText(
        root: Path,
        query: String,
        scope: String? = null,
        ignoreCase: Boolean = true,
        maxResults: Int = 50,
        maxFiles: Int = 5_000,
    ): TextSearchOutcome {
        require(query.isNotBlank()) { "The search query must not be blank." }
        require(maxResults >= 1) { "maxResults must be 1 or greater." }

        val start = scopeRoot(root, scope)
        val matches = mutableListOf<TextMatch>()
        var scanned = 0
        var truncated = false

        walk(root, start).forEach { file ->
            if (truncated) {
                return@forEach
            }
            if (scanned >= maxFiles) {
                truncated = true
                return@forEach
            }
            if (!file.isRegularFile() || isBinary(file) || sizeOf(file) > MAX_READABLE_BYTES) {
                return@forEach
            }
            scanned++
            readLinesOrNull(file)?.forEachIndexed { index, line ->
                if (matches.size >= maxResults) {
                    truncated = true
                    return@forEachIndexed
                }
                if (line.contains(query, ignoreCase)) {
                    matches.add(
                        TextMatch(
                            path = relativeOf(root, file),
                            line = index + 1,
                            text = line.trim().take(SNIPPET_LENGTH),
                        ),
                    )
                }
            }
        }

        return TextSearchOutcome(matches, truncated, scanned)
    }

    fun listDirectory(
        root: Path,
        relativePath: String? = null,
        maxDepth: Int = 2,
        maxEntries: Int = 300,
    ): DirectoryListing {
        require(maxDepth >= 1) { "maxDepth must be 1 or greater." }
        require(maxEntries >= 1) { "maxEntries must be 1 or greater." }

        val start = scopeRoot(root, relativePath)
        if (!start.isDirectory()) {
            throw RepositoryReadException("Path '${relativePath.orEmpty()}' is not a directory in this repository.")
        }

        val entries = mutableListOf<DirectoryEntry>()
        var truncated = false
        Files.walk(start, maxDepth).use { paths ->
            paths.filter { it != start }
                .filter { candidate -> !isInsideGitDirectory(root, candidate) }
                .sorted()
                .forEach { candidate ->
                    if (entries.size >= maxEntries) {
                        truncated = true
                        return@forEach
                    }
                    val directory = candidate.isDirectory()
                    entries.add(
                        DirectoryEntry(
                            path = relativeOf(root, candidate),
                            directory = directory,
                            sizeBytes = if (directory) null else sizeOf(candidate),
                        ),
                    )
                }
        }

        return DirectoryListing(
            path = relativePath?.let(::normalize).orEmpty(),
            entries = entries,
            truncated = truncated,
        )
    }

    private fun scopeRoot(root: Path, relativePath: String?): Path =
        if (relativePath.isNullOrBlank()) {
            root.toAbsolutePath().normalize()
        } else {
            PathSecurityValidator.resolve(root, relativePath)
        }

    private fun walk(root: Path, start: Path): List<Path> =
        if (!start.isDirectory()) {
            listOf(start)
        } else {
            Files.walk(start).use { paths ->
                paths.filter { candidate -> !isInsideGitDirectory(root, candidate) }
                    .filter(Path::isRegularFile)
                    .sorted()
                    .toList()
            }
        }

    /** O diretório interno do Git nunca entra em leitura nem em busca. */
    private fun isInsideGitDirectory(root: Path, candidate: Path): Boolean {
        val relative = root.toAbsolutePath().normalize()
            .relativize(candidate.toAbsolutePath().normalize())
        return relative.any { it.name == GIT_DIRECTORY }
    }

    private fun assertReadableAsText(file: Path, relativePath: String) {
        if (sizeOf(file) > MAX_READABLE_BYTES) {
            throw RepositoryReadException(
                "File '$relativePath' is larger than the ${MAX_READABLE_BYTES / (1024 * 1024)} MB " +
                    "limit Prumo reads through MCP.",
            )
        }
        if (isBinary(file)) {
            throw RepositoryReadException("File '$relativePath' is binary and is not readable as text.")
        }
    }

    /** Byte nulo no início do arquivo é o sinal que o próprio Git usa para tratar conteúdo como binário. */
    private fun isBinary(file: Path): Boolean = try {
        Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(BINARY_PROBE_BYTES)
            val read = stream.read(buffer)
            read > 0 && buffer.take(read).any { it == 0.toByte() }
        }
    } catch (_: IOException) {
        true
    }

    private fun readLinesOrNull(file: Path): List<String>? = try {
        Files.readAllLines(file, StandardCharsets.UTF_8)
    } catch (_: IOException) {
        null
    }

    private fun sizeOf(file: Path): Long = try {
        Files.size(file)
    } catch (_: IOException) {
        0
    }

    private fun relativeOf(root: Path, file: Path): String =
        normalize(root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString())

    private fun normalize(path: String): String = path.replace('\\', '/')

    private const val GIT_DIRECTORY = ".git"
}
