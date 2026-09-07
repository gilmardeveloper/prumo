package io.prumo.mcp.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Nenhum diálogo deste projeto depende de `bindText`, `bindItem` e afins.
 *
 * `DialogWrapper` só copia o valor ligado para a propriedade quando chama `DialogPanel.apply()`, e
 * isso tem duas condições frágeis: acontece **depois** de `doValidate()` aprovar, e só quando
 * `createCenterPanel()` devolve o `DialogPanel` em pessoa. Envolver o painel — num `JBScrollPane`,
 * por exemplo — faz a plataforma deixar de reconhecê-lo, e a gravação passa a descartar em silêncio
 * tudo o que o usuário editou. As duas armadilhas já custaram um defeito cada; ler do componente
 * não tem nenhuma delas.
 */
class DialogFormBindingTest {

    @Test
    fun `nenhum dialogo depende de valor ligado`() {
        val infratores = dialogClasses()
            .filter { (_, code) -> BINDINGS.any { code.contains(it) } }
            .map { (name, _) -> name }

        assertTrue(
            infratores.isEmpty(),
            "diálogo não pode depender de bind*; leia do próprio componente: $infratores",
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
