package io.prumo.mcp.ui

import io.prumo.mcp.ui.pack.selectionOrSingle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import javax.swing.DefaultListModel

/**
 * A escolha do item sobre o qual um botão age.
 *
 * A fila de aprovação agia sempre no primeiro item da lista, ignorando o que o usuário tinha
 * selecionado: com duas propostas pendentes, descartar removia a errada.
 */
class PackSelectionTest {

    @Test
    fun `lista vazia nao devolve item`() {
        assertNull(selectionOrSingle(model(), -1))
    }

    @Test
    fun `item unico dispensa selecao`() {
        assertEquals("a", selectionOrSingle(model("a"), -1))
    }

    @Test
    fun `sem selecao e com varios itens nao escolhe pelo usuario`() {
        assertNull(selectionOrSingle(model("a", "b", "c"), -1))
    }

    @Test
    fun `devolve o item selecionado, nao o primeiro`() {
        assertEquals("b", selectionOrSingle(model("a", "b", "c"), 1))
        assertEquals("c", selectionOrSingle(model("a", "b", "c"), 2))
    }

    @Test
    fun `indice fora da faixa nao devolve item`() {
        assertNull(selectionOrSingle(model("a", "b"), 5))
        assertNull(selectionOrSingle(model("a", "b"), -2))
    }

    /** Índice inválido sobre lista de um item cai na regra do item único, não em exceção. */
    @Test
    fun `indice invalido com item unico devolve o unico`() {
        assertEquals("a", selectionOrSingle(model("a"), 9))
    }

    private fun model(vararg items: String) = DefaultListModel<String>().apply {
        items.forEach(::addElement)
    }
}
