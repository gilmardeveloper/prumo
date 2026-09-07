package io.prumo.mcp.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/**
 * As recusas do editor de workspace.
 *
 * Duplicidade de repositório, de documentação e de banco, e a recusa de remover o repositório
 * primário, retornavam em silêncio: o usuário escolhia um diretório, confirmava, e nada acontecia
 * na tela.
 */
class WorkspaceEditorGuardsTest {

    private val source = Path.of("src/main/kotlin/io/prumo/mcp/ui/WorkspaceEditorDialog.kt").readText()

    @Test
    fun `nenhuma guarda de duplicidade retorna sem explicar`() {
        val semMensagem = GUARDS.filterNot { guard -> guard.matches(source) }

        assertTrue(semMensagem.isEmpty(), "guarda que recusa em silêncio: ${semMensagem.map { it.key }}")
    }

    @Test
    fun `as mensagens de recusa existem nos dois idiomas`() {
        val base = properties("PrumoBundle.properties")
        val brazilian = properties("PrumoBundle_pt_BR.properties")

        GUARDS.map { it.key }.forEach { key ->
            assertTrue(base.containsKey(key), "falta $key no bundle base")
            assertTrue(brazilian.containsKey(key), "falta $key no bundle pt_BR")
        }
    }

    /**
     * O diálogo lê do próprio componente. Ligar valor por `bind*` faria a gravação depender de
     * `DialogPanel.apply()`, que não roda quando o painel é envolvido — dois defeitos já vieram daí.
     */
    @Test
    fun `as abas nao introduziram valor ligado`() {
        listOf("bindText(", "bindItem(", "bindIntText(", "bindSelected(").forEach { binding ->
            assertTrue(!source.contains(binding), "o editor voltou a depender de $binding")
        }
    }

    private fun properties(name: String): Properties = Properties().apply {
        Path.of("src/main/resources/messages", name).inputStream().use { load(it.reader(Charsets.UTF_8)) }
    }

    private data class Guard(val key: String, val trigger: String) {
        fun matches(source: String): Boolean = source.contains(trigger) && source.contains(key)
    }

    private companion object {
        val GUARDS = listOf(
            Guard("workspace.duplicate.repository", "repositories.elements().toList().any"),
            Guard("workspace.duplicate.documentation", "documentation.elements().toList().any"),
            Guard("workspace.duplicate.datasource", "datasources.elements().toList().any"),
            Guard("workspace.repository.primaryKept", "binding.id == primaryRepositoryId"),
        )
    }
}
