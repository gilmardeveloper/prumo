package io.prumo.mcp.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.inputStream

/**
 * Os estados de carregamento da janela.
 *
 * A leitura do workspace saiu da thread de interface, e com ela vieram dois estados que a tela não
 * tinha: enquanto o disco responde, e quando ele não responde.
 */
class WorkspaceLoadStateTest {

    @Test
    fun `carregando e um estado do modelo da tela`() {
        val model: WorkspaceViewModel = WorkspaceViewModel.Loading
        assertTrue(model is WorkspaceViewModel.Loading)
    }

    @Test
    fun `a falha carrega chave, nunca a mensagem da excecao`() {
        val model = WorkspaceViewModel.Failed("toolwindow.loadFailed")

        assertTrue(
            model.messageKey.startsWith("toolwindow."),
            "a falha precisa carregar chave de mensagem, e não texto",
        )
        assertTrue(
            properties("PrumoBundle.properties").containsKey(model.messageKey),
            "a chave da falha precisa existir no bundle",
        )
    }

    /**
     * Mensagem de exceção de I/O costuma trazer o caminho do arquivo. Caminho de disco não vai para
     * a tela, pela mesma razão que o restante do modelo não o carrega.
     */
    @Test
    fun `a falha nao expoe caminho de disco`() {
        val model = WorkspaceViewModel.Failed("toolwindow.loadFailed")

        assertFalse(model.toString().contains("C:/"), "caminho de disco no modelo da tela")
        assertFalse(model.toString().contains("\\"), "caminho de disco no modelo da tela")
    }

    @Test
    fun `a chave da falha e da carga existem nos dois idiomas`() {
        val base = properties("PrumoBundle.properties")
        val brazilian = properties("PrumoBundle_pt_BR.properties")

        listOf("toolwindow.loading", "toolwindow.loadFailed", "toolwindow.loadFailed.hint").forEach { key ->
            assertTrue(base.containsKey(key), "falta $key no bundle base")
            assertTrue(brazilian.containsKey(key), "falta $key no bundle pt_BR")
            assertEquals(
                false,
                base.getProperty(key) == brazilian.getProperty(key),
                "$key não foi traduzida",
            )
        }
    }

    private fun properties(name: String): Properties = Properties().apply {
        Path.of("src/main/resources/messages", name).inputStream().use { load(it.reader(Charsets.UTF_8)) }
    }
}
