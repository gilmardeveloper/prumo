package io.prumo.mcp.pack

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Exceção lançada de dentro de um ouvinte de botão não chega à tela: a IDE a recolhe como erro
 * interno do plugin, e o usuário fica com um botão que não faz nada. Um pack recusado é desfecho
 * normal — arquivo trocado, checksum diferente, segredo dentro — e precisa virar diálogo.
 */
@Tag("security")
class PackUiFailureHandlingTest {

    @Test
    fun `todo painel que le ou instala pack mostra a falha ao usuario`() {
        val panels = Files.walk(Path.of("src/main/kotlin/io/prumo/mcp/ui/pack")).use { paths ->
            paths.filter { it.extension == "kt" }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }

        panels.filter { (_, source) ->
            source.contains("PackImporter.") || source.contains("PackFileExchange(")
        }.forEach { (name, source) ->
            assertTrue(
                source.contains("showPackFailure"),
                "$name toca um pack e precisa mostrar a falha ao usuário",
            )
        }
    }
}
