package io.prumo.mcp.pack.application

import io.prumo.mcp.pack.exchange.PackExchangeException
import io.prumo.mcp.pack.exchange.PackExporter
import io.prumo.mcp.pack.exchange.PackImportPreview
import io.prumo.mcp.pack.exchange.PackImporter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Troca de packs com o disco: o arquivo escolhido pelo usuário de um lado, o store do outro.
 *
 * Não decide nada sobre risco nem sobre consentimento — lê, prepara a decisão e grava. Quem exibe
 * o termo e recolhe o aceite é a interface.
 */
class PackFileExchange(private val store: PackStore) {

    /**
     * Lê um arquivo de troca e prepara a decisão do usuário.
     *
     * @throws PackExchangeException se o arquivo não puder ser lido ou não for um pack legível.
     */
    fun read(file: Path): PackImportPreview {
        val content = try {
            Files.readString(file, StandardCharsets.UTF_8)
        } catch (cause: IOException) {
            throw PackExchangeException("Prumo could not read '${file.fileName}': ${cause.message}")
        }
        return PackImporter.preview(content)
    }

    /**
     * Grava o pack instalado no arquivo indicado.
     *
     * @return o caminho gravado.
     * @throws PackExchangeException se o pack não existir, não for portável ou o arquivo não puder
     *   ser escrito.
     */
    fun write(workspaceId: String, packId: String, file: Path): Path {
        val content = PackExporter.export(store, workspaceId, packId)
        try {
            file.parent?.let(Files::createDirectories)
            Files.writeString(file, content, StandardCharsets.UTF_8)
        } catch (cause: IOException) {
            throw PackExchangeException("Prumo could not write '${file.fileName}': ${cause.message}")
        }
        return file
    }
}
