package io.prumo.mcp.ide

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * O cache do workspace resolvido.
 *
 * Cache sem invalidação é pior que releitura: a tela passaria a afirmar um estado que já mudou.
 * A persistência não tem lock nem transação, e o arquivo pode mudar por fora desta IDE — por isso
 * o cache vale para a interface e não para as ferramentas MCP.
 */
class PrumoProjectContextTest {

    private val source = Path.of("src/main/kotlin/io/prumo/mcp/ide/PrumoProjectContext.kt").readText()

    @Test
    fun `o cache e invalidado pelo evento de mudanca de estado`() {
        assertTrue(source.contains("PrumoUiEvents.STATE_CHANGED"), "o cache precisa assinar o evento")
        assertTrue(source.contains("invalidate()"), "o evento precisa descartar o que está guardado")
    }

    @Test
    fun `o cache e descartado quando o servico morre`() {
        assertTrue(
            source.contains("override fun dispose() = invalidate()"),
            "fechar o projeto precisa descartar o cache",
        )
    }

    @Test
    fun `a assinatura vive presa ao ciclo de vida do servico`() {
        assertTrue(
            source.contains("messageBus.connect(this)"),
            "assinar sem amarrar ao Disposable deixa o ouvinte vivo depois do projeto fechar",
        )
    }

    @Test
    fun `o campo guardado e volatil`() {
        assertTrue(
            source.contains("@Volatile"),
            "o cache é lido de thread de fundo e escrito de outra: precisa ser volátil",
        )
    }

    /**
     * O caminho MCP resolve a cada chamada, de propósito. Se este teste falhar, alguém apontou as
     * ferramentas para o cache e um cliente de IA pode receber um workspace vencido.
     */
    @Test
    fun `as ferramentas MCP nao usam o cache`() {
        val toolCall = Path.of("src/main/kotlin/io/prumo/mcp/toolsets/PrumoToolCall.kt").readText()

        assertTrue(
            !toolCall.contains("PrumoProjectContext"),
            "as tools precisam resolver o workspace a cada chamada",
        )
    }
}
