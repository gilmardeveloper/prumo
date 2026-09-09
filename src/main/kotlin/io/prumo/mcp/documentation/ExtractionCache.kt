package io.prumo.mcp.documentation

import io.prumo.mcp.knowledge.SourceStamp
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Guarda o texto já extraído de um documento, para não extrair duas vezes o mesmo arquivo.
 *
 * Extrair custa segundos em documento grande, e a mesma fonte é lida muitas vezes numa sessão de
 * trabalho. A chave é o carimbo da fonte — tamanho, data e resumo —, então arquivo alterado erra a
 * chave e é reextraído sozinho, sem política de invalidação para manter.
 *
 * O cache vive na memória e morre com a IDE. Persistir o texto extraído colocaria no armazenamento
 * do Prumo uma segunda cópia do conteúdo do usuário, com vida própria e alcançável por quem lê a
 * base — exatamente o que a exclusão de caminho existe para impedir.
 */
class ExtractionCache(private val maxDocuments: Int = DEFAULT_MAX_DOCUMENTS) {

    init {
        require(maxDocuments >= 1) { "maxDocuments must be 1 or greater." }
    }

    private val entries = object : LinkedHashMap<Key, ExtractedDocument>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ExtractedDocument>): Boolean =
            size > maxDocuments
    }

    /**
     * O documento já extraído deste arquivo, ou o que [extract] produzir agora.
     *
     * Arquivo que não pode ser carimbado — sumiu, virou diretório, não é legível — não é guardado:
     * sem carimbo não há como saber se o texto guardado ainda descreve a fonte.
     */
    @Synchronized
    fun getOrExtract(file: Path, extract: (Path) -> ExtractedDocument): ExtractedDocument {
        val key = keyOf(file) ?: return extract(file)
        entries[key]?.let { return it }
        val extracted = extract(file)
        entries[key] = extracted
        return extracted
    }

    /** Quantos documentos estão guardados agora. */
    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    private fun keyOf(file: Path): Key? {
        if (!Files.isRegularFile(file)) {
            return null
        }
        return runCatching {
            Key(
                path = file.toAbsolutePath().normalize().toString(),
                stamp = SourceStamp(
                    sizeBytes = Files.size(file),
                    modifiedAtEpochMillis = Files.getLastModifiedTime(file).toMillis(),
                    sha256 = digestOf(file),
                ),
            )
        }.getOrNull()
    }

    private fun digestOf(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Key(val path: String, val stamp: SourceStamp)

    companion object {
        /** Quantos documentos ficam guardados antes de o mais antigo sair. */
        const val DEFAULT_MAX_DOCUMENTS = 8
    }
}
