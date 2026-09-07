package io.prumo.mcp.ui.tab

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.SwingConstants
import kotlin.io.path.readText

/**
 * A rolagem das abas da janela.
 *
 * A trilha de atividade chega a duzentas linhas e a lista de repositórios cresce com o workspace.
 * Sem rolagem, o que passa da altura da janela não tem como ser alcançado.
 */
class TabScrollingTest {

    @Test
    fun `o conteudo acompanha a largura da janela quando cabe nela`() {
        val content = contentOf(width = 300, height = 500)
        viewportOf(content, width = 400, height = 100)

        assertTrue(
            content.scrollableTracksViewportWidth,
            "conteúdo mais estreito que a janela precisa preencher a largura, e não encolher à esquerda",
        )
    }

    @Test
    fun `o conteudo mais largo que a janela rola na horizontal`() {
        val content = contentOf(width = 900, height = 500)
        viewportOf(content, width = 400, height = 100)

        assertFalse(
            content.scrollableTracksViewportWidth,
            "conteúdo mais largo que a janela precisa rolar, e não ser cortado na borda",
        )
    }

    @Test
    fun `o conteudo mais curto que a janela fica no topo`() {
        val content = contentOf(width = 300, height = 40)
        viewportOf(content, width = 400, height = 500)

        assertFalse(
            content.scrollableTracksViewportHeight,
            "conteúdo curto esticado até o rodapé espalha as linhas pela altura da janela",
        )
    }

    @Test
    fun `a rolagem avanca a cada passo`() {
        val content = contentOf(width = 300, height = 500)
        val visible = Rectangle(0, 0, 400, 100)

        assertTrue(
            content.getScrollableUnitIncrement(visible, SwingConstants.VERTICAL, 1) > 0,
            "passo de rolagem zero trava a roda do mouse",
        )
        assertTrue(
            content.getScrollableBlockIncrement(visible, SwingConstants.VERTICAL, 1) > 0,
            "bloco de rolagem zero trava a barra",
        )
    }

    /**
     * A base é o único lugar que monta o componente da aba. Uma aba que escapasse dela devolveria
     * conteúdo sem rolagem de novo, que é exatamente o defeito corrigido aqui.
     */
    @Test
    fun `toda aba entrega o conteudo dentro de um painel rolavel`() {
        val base = Path.of("src/main/kotlin/io/prumo/mcp/ui/tab/WorkspaceBackedTab.kt").readText()

        assertTrue(base.contains("JBScrollPane("), "a base das abas precisa envolver o conteúdo em um JBScrollPane")
        assertTrue(base.contains("ScrollableContent()"), "o conteúdo rolável precisa acompanhar a largura da janela")
        assertTrue(
            base.contains("final override fun createComponent"),
            "createComponent precisa continuar final: é o que impede uma aba de montar conteúdo sem rolagem",
        )
    }

    private fun contentOf(width: Int, height: Int) = ScrollableContent().apply {
        add(JPanel().apply { preferredSize = Dimension(width, height) }, BorderLayout.CENTER)
    }

    private fun viewportOf(content: ScrollableContent, width: Int, height: Int) = JViewport().apply {
        view = content
        setSize(width, height)
    }
}
