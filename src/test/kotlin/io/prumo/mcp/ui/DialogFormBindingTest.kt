package io.prumo.mcp.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * `DialogWrapper` copia os valores ligados por `bindText`, `bindItem` e afins para as propriedades
 * só quando chama `DialogPanel.apply()`, e isso acontece depois de `doValidate()` aprovar. Diálogo
 * que valida ou consulta o formulário antes do OK precisa ler os componentes direto, senão enxerga
 * o formulário como ele nasceu — e recusa o que o usuário acabou de digitar.
 */
class DialogFormBindingTest {

    @Test
    fun `dialogo que valida le os componentes, e nao valor ligado`() {
        val infratores = dialogSources()
            .filter { (_, code) -> code.contains("override fun doValidate") }
            .filter { (_, code) -> BINDINGS.any { code.contains(it) } }
            .map { (path, _) -> path.fileName.toString() }

        assertTrue(
            infratores.isEmpty(),
            "diálogo com doValidate não pode depender de bind*, que só é aplicado depois: $infratores",
        )
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun dialogSources(): List<Pair<Path, String>> {
        val sources = Path.of("src/main/kotlin/io/prumo/mcp/ui").walk()
            .filter { it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, code) -> code.contains("DialogWrapper(") }
            .toList()

        assertTrue(sources.isNotEmpty(), "nenhum DialogWrapper encontrado")
        return sources
    }

    private companion object {
        val BINDINGS = listOf("bindText(", "bindItem(", "bindIntText(", "bindSelected(")
    }
}
