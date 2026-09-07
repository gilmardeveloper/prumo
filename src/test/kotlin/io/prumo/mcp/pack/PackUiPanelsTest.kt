package io.prumo.mcp.pack

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** O que os painéis de pack precisam fazer, e que nenhum teste de unidade alcança. */
@Tag("security")
class PackUiPanelsTest {

    private val panels = Files.walk(Path.of("src/main/kotlin/io/prumo/mcp/ui/pack")).use { paths ->
        paths.filter { it.extension == "kt" }
            .map { it.fileName.toString() to Files.readString(it) }
            .toList()
    }

    /**
     * Exceção lançada de dentro de um ouvinte de botão não chega à tela: a IDE a recolhe como erro
     * interno do plugin, e o usuário fica com um botão que não faz nada. Um pack recusado é desfecho
     * normal — arquivo trocado, checksum diferente, segredo dentro — e precisa virar diálogo.
     */
    @Test
    fun `todo painel que le ou instala pack mostra a falha ao usuario`() {
        panels.filter { (_, source) ->
            source.contains("PackImporter.") || source.contains("PackFileExchange(")
        }.forEach { (name, source) ->
            assertTrue(
                source.contains("showPackFailure"),
                "$name toca um pack e precisa mostrar a falha ao usuário",
            )
        }
    }

    /**
     * Instalar muda seções que o painel da ação não desenha. Sem avisar, a tela continua dizendo
     * que não há pack instalado logo depois de instalar um, e a fila segue anunciando uma proposta
     * que já saiu.
     */
    @Test
    fun `todo painel que instala ou descarta pack remonta a tela`() {
        panels.filter { (_, source) ->
            source.contains("PackImporter.install") ||
                source.contains("SubmissionQueue(") ||
                source.contains(".remove(")
        }.forEach { (name, source) ->
            assertTrue(
                source.contains("publishStateChanged"),
                "$name muda o estado do workspace e precisa avisar a janela",
            )
        }
    }

    /**
     * O aviso sai por evento, não por chamada à janela. Um painel que conhece a tool window pelo
     * nome amarra o domínio à superfície e obriga toda tela nova a ser conhecida por ele.
     */
    @Test
    fun `nenhum painel de pack chama a janela pelo nome`() {
        val infratores = panels
            .filter { (_, source) -> source.contains("PrumoToolWindowFactory") }
            .map { (name, _) -> name }

        assertTrue(
            infratores.isEmpty(),
            "devem publicar PrumoUiEvents.publishStateChanged em vez de chamar a janela: $infratores",
        )
    }
}
