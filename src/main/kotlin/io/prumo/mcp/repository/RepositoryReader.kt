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

    fun readFile(
        root: Path,
        relativePath: String,
        firstLine: Int = 1,
        maxLines: Int = 400,
        excluded: List<String> = emptyList(),
    ): FileSlice {
        require(firstLine >= 1) { "firstLine must be 1 or greater." }
        require(maxLines >= 1) { "maxLines must be 1 or greater." }

        val file = PathSecurityValidator.resolve(root, relativePath)
        if (isExcluded(root, file, excluded)) {
            throw PathExcludedException(
                "Path '$relativePath' is excluded from this repository in the Prumo workspace, " +
                    "so Prumo does not read it.",
            )
        }
        if (file.isDirectory()) {
            throw RepositoryReadException(
                "Path '$relativePath' is a directory in this repository, not a file. " +
                    "List it with prumo_repository_get_structure.",
            )
        }
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
        excluded: List<String> = emptyList(),
    ): TextSearchOutcome {
        require(query.isNotBlank()) { "The search query must not be blank." }
        require(maxResults >= 1) { "maxResults must be 1 or greater." }

        val start = scopeRoot(root, scope)
        assertScopeAllowed(root, start, scope, excluded)
        assertScopeExists(start, scope)
        val matches = mutableListOf<TextMatch>()
        var scanned = 0
        var truncated = false

        walk(root, start, excluded).forEach { file ->
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
        excluded: List<String> = emptyList(),
    ): DirectoryListing {
        require(maxDepth >= 1) { "maxDepth must be 1 or greater." }
        require(maxEntries >= 1) { "maxEntries must be 1 or greater." }

        val start = scopeRoot(root, relativePath)
        assertScopeAllowed(root, start, relativePath, excluded)
        if (!start.isDirectory()) {
            throw RepositoryReadException("Path '${relativePath.orEmpty()}' is not a directory in this repository.")
        }

        val entries = mutableListOf<DirectoryEntry>()
        var truncated = false
        Files.walk(start, maxDepth).use { paths ->
            paths.filter { it != start }
                .filter { candidate -> !isExcluded(root, candidate, excluded) }
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

    /**
     * Recusa em voz alta quando o próprio alvo pedido é um caminho excluído.
     *
     * Caminho excluído *dentro* de uma varredura continua invisível, que é o desejado. Mas pedir
     * explicitamente o diretório proibido e receber lista vazia faz o cliente concluir que ele está
     * vazio e tentar de novo por outro ângulo — foi o que um avaliador em campo apontou.
     */
    private fun assertScopeAllowed(root: Path, start: Path, relativePath: String?, excluded: List<String>) {
        if (relativePath.isNullOrBlank() || !isExcluded(root, start, excluded)) {
            return
        }
        throw PathExcludedException(
            "Path '$relativePath' is excluded from this repository in the Prumo workspace, " +
                "so Prumo does not read it.",
        )
    }

    /**
     * Recusa escopo que não existe no repositório.
     *
     * Vem depois da checagem de exclusão, e não antes: caminho excluído precisa continuar
     * respondendo que é excluído, sob pena de a recusa virar um oráculo de existência sobre a área
     * que o workspace decidiu não mostrar.
     *
     * Escopo inexistente devolvia busca vazia, indistinguível de busca que percorreu tudo e não
     * achou — e a conclusão que o cliente tira das duas é oposta.
     */
    private fun assertScopeExists(start: Path, relativePath: String?) {
        if (relativePath.isNullOrBlank() || Files.exists(start)) {
            return
        }
        throw RepositoryReadException(
            "Path '$relativePath' does not exist in this repository, so there is nothing to " +
                "search under it. Searching without a scope covers the whole repository.",
        )
    }

    private fun scopeRoot(root: Path, relativePath: String?): Path =
        if (relativePath.isNullOrBlank()) {
            root.toAbsolutePath().normalize()
        } else {
            PathSecurityValidator.resolve(root, relativePath)
        }

    private fun walk(root: Path, start: Path, excluded: List<String>): List<Path> =
        if (!start.isDirectory()) {
            listOf(start)
        } else {
            Files.walk(start).use { paths ->
                paths.filter { candidate -> !isExcluded(root, candidate, excluded) }
                    .filter(Path::isRegularFile)
                    .sorted()
                    .toList()
            }
        }

    /**
     * Decide se um caminho está fora do alcance deste repositório.
     *
     * O `.git` é a primeira entrada, sempre, sem depender de configuração: é onde mora a URL do
     * remote, que pode carregar token. As demais vêm do vínculo do workspace.
     *
     * Uma entrada casa quando o caminho relativo é igual a ela, quando começa com ela seguida de
     * barra, ou quando qualquer segmento do caminho é igual a ela. Isso cobre `target`, `.claude` e
     * `CLAUDE.md` sem motor de padrões — e sem canto escuro onde um caminho escape por acidente.
     */
    private fun isExcluded(root: Path, candidate: Path, excluded: List<String>): Boolean =
        isExcludedPath(relativeForExclusion(root, candidate), excluded)

    /**
     * O caminho relativo sobre o qual a exclusão decide, pela grafia que está no disco.
     *
     * Público porque a família de conhecimento decide o mesmo alcance sobre o mesmo repositório, e
     * decidi-lo sobre o caminho que o cliente escreveu deixa a exclusão cair por outro nome do mesmo
     * arquivo: junção e link simbólico apontando para dentro da área excluída passavam.
     */
    fun relativeForExclusion(root: Path, candidate: Path): String =
        realRelative(root, candidate).map { it.name }.joinToString("/")

    /**
     * Decide a exclusão a partir do caminho relativo em texto, sem tocar o disco.
     *
     * Existe para o caminho que o disco não pode confirmar — o arquivo que o Git reporta como
     * apagado, por exemplo. A regra de casamento é esta, e só esta: duplicá-la em outro ponto foi
     * como a exclusão nasceu contornável por troca de caixa.
     */
    fun isExcludedPath(relativePath: String, excluded: List<String>): Boolean {
        val segments = relativePath.replace(BACKSLASH, '/').split('/').filter { it.isNotBlank() }
        if (segments.any { it.equals(GIT_DIRECTORY, ignoreCase = true) }) {
            return true
        }

        val normalized = segments.joinToString("/")
        return excluded.any { entry ->
            val alvo = entry.trim().trimEnd('/').replace(BACKSLASH, '/')
            alvo.isNotEmpty() && (
                normalized.equals(alvo, ignoreCase = true) ||
                    normalized.startsWith("$alvo/", ignoreCase = true) ||
                    segments.any { it.equals(alvo, ignoreCase = true) }
                )
        }
    }

    /**
     * Caminho relativo pela grafia que está no disco, e não pela que o cliente digitou.
     *
     * No Windows o sistema de arquivos ignora maiúsculas, então `claude.md` abre o `CLAUDE.md`. Uma
     * comparação sobre o texto recebido deixaria a exclusão cair com uma troca de caixa —
     * demonstrado em campo por um agente que leu o arquivo inteiro assim. `toRealPath` devolve a
     * grafia real; a comparação sem diferenciar caixa cobre o caminho que ainda não existe.
     */
    private fun realRelative(root: Path, candidate: Path): Path {
        val raiz = runCatching { root.toRealPath() }.getOrElse { root.toAbsolutePath().normalize() }
        val alvo = runCatching { candidate.toRealPath() }
            .getOrElse { candidate.toAbsolutePath().normalize() }
        return runCatching { raiz.relativize(alvo) }
            .getOrElse { root.toAbsolutePath().normalize().relativize(candidate.toAbsolutePath().normalize()) }
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
    private val BACKSLASH: Char = 92.toChar()
}
