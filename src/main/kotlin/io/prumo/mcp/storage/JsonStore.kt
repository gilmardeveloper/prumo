package io.prumo.mcp.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persistência em JSON dos documentos do Prumo.
 *
 * A escrita é atômica: o conteúdo vai para um arquivo temporário vizinho e só então substitui o
 * destino.
 */
class JsonStore(
    private val json: Json = DEFAULT_JSON,
) {

    fun <T> read(path: Path, serializer: KSerializer<T>): T? {
        if (!Files.exists(path)) {
            return null
        }
        val content = Files.readString(path, StandardCharsets.UTF_8)
        return try {
            json.decodeFromString(serializer, content)
        } catch (cause: Exception) {
            throw CorruptedStoreException(path, cause)
        }
    }

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(serializer, value), StandardCharsets.UTF_8)
            moveIntoPlace(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveIntoPlace(temporary: Path, path: Path) {
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupported) {
            // Alguns sistemas de arquivos em Windows recusam o move atômico sobre arquivo existente.
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val DEFAULT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

private typealias AtomicMoveNotSupported = java.nio.file.AtomicMoveNotSupportedException

class CorruptedStoreException(
    path: Path,
    cause: Throwable,
) : IOException("Prumo MCP could not read the store file at $path: it is missing or malformed.", cause)
