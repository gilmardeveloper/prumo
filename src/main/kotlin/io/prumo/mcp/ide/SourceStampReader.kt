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
 * Carimba o estado atual de uma fonte do workspace.
 *
 * O carimbo é sempre calculado aqui, nunca aceito do cliente: quem grava o conhecimento é uma IA, e
 * um carimbo que ela mesma informasse não provaria nada sobre a fonte — provaria só o que ela disse.
 */
object SourceStampReader {

    /**
     * O carimbo da fonte, ou `null` quando ela não existe mais ao alcance do workspace — o que faz o
     * registro correspondente ser respondido como órfão.
     */
    fun stamp(context: WorkspaceContext, provenance: Provenance): SourceStamp? {
        val file = locate(context, provenance) ?: return null
        if (!Files.isRegularFile(file)) {
            return null
        }
        return SourceStamp(
            sizeBytes = Files.size(file),
            modifiedAtEpochMillis = Files.getLastModifiedTime(file).toMillis(),
            sha256 = digestOf(file),
        )
    }

    private fun locate(context: WorkspaceContext, provenance: Provenance): Path? = when (provenance.sourceKind) {
        SourceKind.DOCUMENTATION -> locateDocumentation(context, provenance)
        SourceKind.REPOSITORY -> locateRepository(context, provenance)
    }

    private fun locateDocumentation(context: WorkspaceContext, provenance: Provenance): Path? {
        val source = context.workspace.documentation.firstOrNull { it.id == provenance.sourceId } ?: return null
        val root = pathOrNull(source.location) ?: return null
        val relative = provenance.path
        return if (relative.isNullOrBlank()) {
            root
        } else {
            runCatching { PathSecurityValidator.resolve(root, relative) }.getOrNull()
        }
    }

    private fun locateRepository(context: WorkspaceContext, provenance: Provenance): Path? {
        val binding = context.workspace.repositories.firstOrNull { it.id == provenance.sourceId } ?: return null
        val root = pathOrNull(binding.localPath) ?: return null
        val relative = provenance.path ?: return null
        if (binding.excludedPaths.any { relative.startsWith(it, ignoreCase = true) }) {
            return null
        }
        return runCatching { PathSecurityValidator.resolve(root, relative) }.getOrNull()
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
