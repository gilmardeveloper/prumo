package io.prumo.mcp.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * O registro de abas.
 *
 * Aba com título sem tradução aparece na janela como a chave crua; aba com id repetido torna a
 * ordem do registro a única forma de distingui-las.
 */
class PrumoTabRegistryTest {

    /**
     * Uma aba que existe como classe mas não está no registro compila, passa no verificador e
     * simplesmente não aparece na janela — o mesmo modo de falha que uma toolset fora do
     * `plugin.xml`. Só é descoberto abrindo a IDE.
     */
    @Test
    fun `toda aba escrita esta no registro`() {
        val registro = Path.of("src/main/kotlin/io/prumo/mcp/ui/PrumoTabRegistry.kt").readText()
        val classes = Path.of("src/main/kotlin/io/prumo/mcp/ui/tab")
            .let { pasta -> java.nio.file.Files.list(pasta).use { it.toList() } }
            .map { it.fileName.toString().removeSuffix(".kt") }
            .filter { it.endsWith("Tab") && it != "WorkspaceBackedTab" }

        assertTrue(classes.isNotEmpty(), "nenhuma aba encontrada; o teste perdeu o alvo")

        val ausentes = classes.filterNot { registro.contains("$it(project)") }
        assertTrue(ausentes.isEmpty(), "abas escritas e fora do registro: $ausentes")
    }

    @Test
    fun `toda aba declarada tem titulo nos dois idiomas`() {
        val base = properties("PrumoBundle.properties")
        val brazilian = properties("PrumoBundle_pt_BR.properties")

        val semTraducao = declaredTitleKeys().filterNot { base.containsKey(it) && brazilian.containsKey(it) }

        assertTrue(semTraducao.isEmpty(), "chave de título de aba ausente em algum bundle: $semTraducao")
    }

    @Test
    fun `o titulo da aba nao e o mesmo texto nos dois idiomas`() {
        val base = properties("PrumoBundle.properties")
        val brazilian = properties("PrumoBundle_pt_BR.properties")

        val naoTraduzidas = declaredTitleKeys().filter { base.getProperty(it) == brazilian.getProperty(it) }

        assertTrue(naoTraduzidas.isEmpty(), "título de aba igual nos dois idiomas: $naoTraduzidas")
    }

    @Test
    fun `nenhum id de aba se repete`() {
        val ids = declaredIds()
        assertEquals(ids.size, ids.distinct().size, "id de aba repetido: $ids")
    }

    @Test
    fun `todo id e estavel, sem espaco nem maiuscula`() {
        val foraDoPadrao = declaredIds().filterNot { it.matches(Regex("^[a-z][a-z0-9-]*$")) }
        assertTrue(foraDoPadrao.isEmpty(), "id fora do padrão kebab-case minúsculo: $foraDoPadrao")
    }

    @Test
    fun `o registro declara ao menos uma aba`() {
        assertTrue(declaredIds().isNotEmpty(), "nenhuma aba declarada")
    }

    /**
     * As abas são lidas do fonte, e não instanciadas: `PrumoTab` monta componente Swing e exige um
     * `Project` vivo, que não existe fora da IDE.
     */
    private fun declaredIds(): List<String> = tabSources().flatMap { source ->
        Regex("""override val id = "([^"]+)"""").findAll(source).map { it.groupValues[1] }
    }

    private fun declaredTitleKeys(): List<String> = tabSources().flatMap { source ->
        Regex("""override val titleKey = "([^"]+)"""").findAll(source).map { it.groupValues[1] }
    }

    /** Aba concreta é a que declara título próprio; a base abstrata não declara. */
    @OptIn(ExperimentalPathApi::class)
    private fun tabSources(): List<String> = Path.of("src/main/kotlin/io/prumo/mcp/ui").walk()
        .filter { it.extension == "kt" }
        .map { it.readText() }
        .filter { it.contains("override val titleKey") }
        .toList()

    private fun properties(name: String): Properties = Properties().apply {
        Path.of("src/main/resources/messages", name).inputStream().use { load(it.reader(Charsets.UTF_8)) }
    }
}
