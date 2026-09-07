package io.prumo.mcp.ide

import io.prumo.mcp.knowledge.Provenance
import io.prumo.mcp.knowledge.SourceKind
import io.prumo.mcp.knowledge.SourceStamp
import io.prumo.mcp.repository.PathSecurityValidator
import io.prumo.mcp.workspace.application.WorkspaceContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest

/**
 * O que aconteceu ao tentar carimbar uma fonte.
 *
 * Distinguir os motivos importa porque a recusa chega a um cliente de IA, e ele precisa saber se
 * errou o identificador da fonte, se errou o caminho dentro dela, ou se pediu algo que o
 * desenvolvedor pôs fora de alcance. O resto do produto já distingue essas três coisas.
 */
sealed interface StampResult {

    data class Stamped(val stamp: SourceStamp) : StampResult

    /** O identificador não corresponde a nenhuma fonte deste workspace. */
    data object UnknownSource : StampResult

    /** A fonte existe, mas o caminho pedido dentro dela não. */
    data object PathNotFound : StampResult

    /** A fonte é um repositório, e nenhum caminho foi informado: não há o que carimbar. */
    data object PathMissing : StampResult

    /** O caminho está entre os que o desenvolvedor excluiu deste repositório. */
    data object PathExcluded : StampResult

    val stampOrNull: SourceStamp? get() = (this as? Stamped)?.stamp
}

/**
 * Carimba o estado atual de uma fonte do workspace.
 *
 * O carimbo é sempre calculado aqui, nunca aceito do cliente: quem grava o conhecimento é uma IA, e
 * um carimbo que ela mesma informasse não provaria nada sobre a fonte — provaria só o que ela disse.
 */
object SourceStampReader {

    fun stamp(context: WorkspaceContext, provenance: Provenance): StampResult = when (provenance.sourceKind) {
        SourceKind.DOCUMENTATION -> stampDocumentation(context, provenance)
        SourceKind.REPOSITORY -> stampRepository(context, provenance)
    }

    private fun stampDocumentation(context: WorkspaceContext, provenance: Provenance): StampResult {
        val source = context.workspace.documentation.firstOrNull { it.id == provenance.sourceId }
            ?: return StampResult.UnknownSource
        val root = pathOrNull(source.location) ?: return StampResult.UnknownSource
        val relative = provenance.path
        val target = if (relative.isNullOrBlank()) {
            root
        } else {
            runCatching { PathSecurityValidator.resolve(root, relative) }.getOrNull()
                ?: return StampResult.PathNotFound
        }
        return stampOf(target)
    }

    private fun stampRepository(context: WorkspaceContext, provenance: Provenance): StampResult {
        val binding = context.workspace.repositories.firstOrNull { it.id == provenance.sourceId }
            ?: return StampResult.UnknownSource
        val root = pathOrNull(binding.localPath) ?: return StampResult.UnknownSource
        val relative = provenance.path ?: return StampResult.PathMissing
        if (binding.excludedPaths.any { relative.startsWith(it, ignoreCase = true) }) {
            return StampResult.PathExcluded
        }
        val target = runCatching { PathSecurityValidator.resolve(root, relative) }.getOrNull()
            ?: return StampResult.PathNotFound
        return stampOf(target)
    }

    private fun stampOf(file: Path): StampResult {
        if (!Files.isRegularFile(file)) {
            return StampResult.PathNotFound
        }
        return StampResult.Stamped(
            SourceStamp(
                sizeBytes = Files.size(file),
                modifiedAtEpochMillis = Files.getLastModifiedTime(file).toMillis(),
                sha256 = digestOf(file),
            ),
        )
    }

    private fun pathOrNull(value: String): Path? = try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        null
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
}
