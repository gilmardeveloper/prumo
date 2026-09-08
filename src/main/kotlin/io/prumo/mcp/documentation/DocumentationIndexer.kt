package io.prumo.mcp.documentation

import java.nio.file.Files
import java.nio.file.Path

/**
 * Transforma uma fonte de documentação nos pedaços que o índice guarda.
 *
 * A fonte pode ser um arquivo ou uma pasta; o que não é legível é ignorado em silêncio aqui, porque
 * a recusa por formato já é dita na leitura direta e repeti-la na busca encheria a resposta de ruído
 * sobre arquivos que o desenvolvedor nem pediu.
 *
 * @param read como obter o documento extraído de um arquivo. Injetado para o teste não depender de
 *   formato binário de verdade.
 */
fun chunksOfSource(
    source: DocumentationSource,
    read: (Path) -> ExtractedDocument,
    maxChars: Int = DEFAULT_MAX_CHARS,
): List<IndexedChunk> {
    val root = runCatching { Path.of(source.location) }.getOrNull() ?: return emptyList()
    return filesOf(root)
        .flatMap { file ->
            val relative = if (Files.isDirectory(root)) root.relativize(file).toString().replace('\\', '/')
            else file.fileName.toString()
            runCatching { read(file) }
                .map { document -> chunksOf(document, maxChars) }
                .getOrElse { emptyList() }
                .map { chunk -> IndexedChunk(source.id, relative, chunk) }
        }
}

private fun filesOf(root: Path): List<Path> = when {
    Files.isRegularFile(root) -> listOf(root).filter { SupportedDocumentFormats.isSupported(it) }
    Files.isDirectory(root) -> Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && SupportedDocumentFormats.isSupported(it) }
            .sorted()
            .toList()
    }
    else -> emptyList()
}
