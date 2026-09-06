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
        val infratores = dialogClasses()
            .filter { (_, code) -> code.contains("override fun doValidate") }
            .filter { (_, code) -> BINDINGS.any { code.contains(it) } }
            .map { (name, _) -> name }

        assertTrue(
            infratores.isEmpty(),
            "diálogo com doValidate não pode depender de bind*, que só é aplicado depois: $infratores",
        )
    }

    /**
     * O recorte é por classe, não por arquivo: um mesmo arquivo hospeda diálogos com regras
     * diferentes, e o que decide é quem declara `doValidate`.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun dialogClasses(): List<Pair<String, String>> {
        val classes = Path.of("src/main/kotlin/io/prumo/mcp/ui").walk()
            .filter { it.extension == "kt" }
            .flatMap { file -> topLevelClasses(file.readText()) }
            .filter { (_, code) -> code.contains("DialogWrapper(") }
            .toList()

        assertTrue(classes.isNotEmpty(), "nenhum DialogWrapper encontrado")
        return classes
    }

    private fun topLevelClasses(code: String): List<Pair<String, String>> {
        val declarations = Regex("""(?m)^(?:internal )?class (\w+)""").findAll(code).toList()
        return declarations.mapIndexed { index, match ->
            val end = declarations.getOrNull(index + 1)?.range?.first ?: code.length
            match.groupValues[1] to code.substring(match.range.first, end)
        }
    }

    private companion object {
        val BINDINGS = listOf("bindText(", "bindItem(", "bindIntText(", "bindSelected(")
    }
}
