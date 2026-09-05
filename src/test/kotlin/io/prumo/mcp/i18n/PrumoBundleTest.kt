package io.prumo.mcp.i18n

import com.intellij.AbstractBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Properties
import kotlin.io.path.extension

/** Paridade entre os dois arquivos de mensagem e resolução por locale explícito. */
@Tag("security")
class PrumoBundleTest {

    private val base = load("src/main/resources/messages/PrumoBundle.properties")
    private val brazilian = load("src/main/resources/messages/PrumoBundle_pt_BR.properties")

    @Test
    fun `as duas linguas tem exatamente as mesmas chaves`() {
        val faltando = base.keys - brazilian.keys
        val sobrando = brazilian.keys - base.keys

        assertTrue(faltando.isEmpty(), "sem tradução: $faltando")
        assertTrue(sobrando.isEmpty(), "chave que não existe no inglês: $sobrando")
    }

    @Test
    fun `nenhum texto esta vazio`() {
        (base + brazilian).forEach { (key, value) ->
            assertTrue(value.isNotBlank(), "texto vazio em '$key'")
        }
    }

    @Test
    fun `traducao e traducao, nao copia do ingles`() {
        // Rótulos técnicos e nomes próprios coincidem; o resto precisa ter sido traduzido de fato.
        val iguais = base.filter { (key, value) -> brazilian[key] == value }.keys -
            setOf("toolwindow.title", "pack.consent.checksum", "datasource.field.host")

        assertTrue(iguais.isEmpty(), "chaves não traduzidas: $iguais")
    }

    @Test
    fun `toda chave usada no codigo existe nos dois arquivos`() {
        val usadas = Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }
                .map { Files.readString(it) }
                .toList()
        }
            .flatMap { source -> KEY_USAGE.findAll(source).map { it.groupValues[1] }.toList() }
            .filter { it.contains('.') }
            .toSet()

        val ausentes = usadas - base.keys
        assertTrue(ausentes.isEmpty(), "chave usada no código e ausente do bundle: $ausentes")
    }

    /** Nome de tool, descrição e erro devolvido ao cliente MCP não passam pelo bundle. */
    @Test
    fun `a superficie MCP nao passa pelo bundle`() {
        val toolsets = Files.walk(Path.of("src/main/kotlin/io/prumo/mcp/toolsets")).use { paths ->
            paths.filter { it.extension == "kt" }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }

        toolsets.forEach { (name, source) ->
            assertFalse(source.contains("PrumoBundle"), "$name não pode traduzir a superfície MCP")
        }
    }

    @Test
    fun `o bundle resolve o texto no idioma pedido`() {
        assertEquals("Policies", base.getValue("toolwindow.policies"))
        assertEquals("Políticas", brazilian.getValue("toolwindow.policies"))
    }

    /**
     * A premissa da preferência de idioma do Prumo: pedir um locale explicitamente tem que vencer o
     * idioma da máquina. Sem isso, escolher "Português" com a IDE em inglês não teria efeito.
     */
    @Test
    fun `o locale pedido vence o idioma da maquina`() {
        val padrao = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))

            assertEquals(
                "Policies",
                AbstractBundle.message(PrumoBundle.localized(Locale.ENGLISH), "toolwindow.policies"),
            )
            assertEquals(
                "Políticas",
                AbstractBundle.message(PrumoBundle.localized(Locale.forLanguageTag("pt-BR")), "toolwindow.policies"),
            )
        } finally {
            Locale.setDefault(padrao)
        }
    }

    private fun load(path: String): Map<String, String> {
        val properties = Properties()
        Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8).use(properties::load)
        return properties.entries.associate { it.key.toString() to it.value.toString() }
    }

    private companion object {
        val KEY_USAGE = Regex("""PrumoBundle\.message\(\s*"([^"]+)"""")
    }
}
