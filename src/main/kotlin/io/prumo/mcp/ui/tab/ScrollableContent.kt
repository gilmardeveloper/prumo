package io.prumo.mcp.ui.tab

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Painel que serve de conteúdo a um `JScrollPane`.
 *
 * Acompanha a largura do viewport enquanto o conteúdo couber nela, e passa a rolar na horizontal
 * quando não couber. Sem isso, um painel mais estreito que a janela ficaria encolhido à esquerda e
 * um mais largo teria a borda direita cortada sem barra.
 */
class ScrollableContent : JPanel(BorderLayout()), Scrollable {

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = UNIT

    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width

    override fun getScrollableTracksViewportWidth(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.width >= preferredSize.width
    }

    /** Conteúdo mais curto que a janela fica ancorado no topo, e não esticado até o rodapé. */
    override fun getScrollableTracksViewportHeight(): Boolean = false

    private companion object {
        const val UNIT = 16
    }
}
