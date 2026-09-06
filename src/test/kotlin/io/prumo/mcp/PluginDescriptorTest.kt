package io.prumo.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Exigências que a plataforma faz ao descritor e que nem o build nem o `verifyPlugin` recusam —
 * elas só aparecem como diálogo de erro na IDE do usuário.
 */
class PluginDescriptorTest {

    private val descriptor = Path.of("src/main/resources/META-INF/plugin.xml")

    private val document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(descriptor.toFile())

    /**
     * Sem `displayName` nem `key`, a plataforma precisa instanciar a classe para descobrir o nome e
     * registra `PluginException` ao abrir Settings.
     */
    @Test
    fun `todo configurable declara o nome no descritor`() {
        val configurables = listOf("applicationConfigurable", "projectConfigurable")
            .flatMap { tag -> document.getElementsByTagName(tag).elements() }

        assertTrue(configurables.isNotEmpty(), "nenhum configurable encontrado em $descriptor")
        configurables.forEach { element ->
            val id = element.getAttribute("id")
            assertTrue(
                element.hasAttribute("displayName") || element.hasAttribute("key"),
                "configurable '$id' precisa de displayName ou key no plugin.xml",
            )
        }
    }

    /**
     * A descrição é renderizada em HTML 3.2 pelo Swing, que só conhece as entidades nomeadas do
     * XML. `&mdash;` e afins chegam à tela como texto cru.
     */
    @Test
    fun `a descricao nao usa entidade que a IDE nao resolve`() {
        val description = document.getElementsByTagName("description").elements().single().textContent

        val encontradas = Regex("&[a-zA-Z]+;").findAll(description)
            .map { it.value }
            .filterNot { it in RESOLVED_ENTITIES }
            .toList()

        assertTrue(encontradas.isEmpty(), "entidades que a IDE mostra cruas: $encontradas")
        assertFalse(description.isBlank())
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private companion object {
        val RESOLVED_ENTITIES = setOf("&amp;", "&lt;", "&gt;", "&quot;", "&nbsp;")
    }
}
