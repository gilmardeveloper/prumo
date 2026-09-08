package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A janela sobre o documento extraído.
 *
 * O que estes testes protegem é a coordenada: ela precisa sobreviver ao recorte, senão o cliente
 * recebe texto sem saber de onde ele saiu — e é a coordenada que permite voltar à fonte.
 */
class ExtractedDocumentTest {

    @Test
    fun `a janela do comeco traz as primeiras linhas e se diz truncada`() {
        val janela = documento(5).window(firstLine = 1, maxLines = 2)

        assertEquals(listOf("linha 1", "linha 2"), janela.lines.map { it.text })
        assertEquals(1, janela.firstLine)
        assertEquals(2, janela.lastLine)
        assertEquals(5, janela.totalLines)
        assertTrue(janela.truncated)
    }

    @Test
    fun `a janela do meio comeca onde foi pedida`() {
        val janela = documento(5).window(firstLine = 3, maxLines = 2)

        assertEquals(listOf("linha 3", "linha 4"), janela.lines.map { it.text })
        assertEquals(3, janela.firstLine)
        assertEquals(4, janela.lastLine)
        assertTrue(janela.truncated)
    }

    @Test
    fun `a janela que alcanca o fim nao se diz truncada`() {
        val janela = documento(5).window(firstLine = 4, maxLines = 10)

        assertEquals(listOf("linha 4", "linha 5"), janela.lines.map { it.text })
        assertEquals(5, janela.lastLine)
        assertFalse(janela.truncated)
    }

    /** Quem paginou até o fim precisa saber que acabou, não receber erro. */
    @Test
    fun `pedido alem do fim devolve recorte vazio, e nao falha`() {
        val janela = documento(3).window(firstLine = 99, maxLines = 10)

        assertTrue(janela.lines.isEmpty())
        assertEquals(99, janela.firstLine)
        assertEquals(3, janela.totalLines)
        assertFalse(janela.truncated)
    }

    @Test
    fun `documento vazio responde sem quebrar`() {
        val janela = ExtractedDocument(emptyList()).window(firstLine = 1, maxLines = 10)

        assertTrue(janela.lines.isEmpty())
        assertEquals(0, janela.totalLines)
        assertFalse(janela.truncated)
    }

    @Test
    fun `a coordenada sobrevive ao recorte`() {
        val documento = ExtractedDocument(
            listOf(
                ExtractedLine(SourceCoordinate.Page(1), "abertura"),
                ExtractedLine(SourceCoordinate.Page(7), "rubrica 662"),
                ExtractedLine(SourceCoordinate.Row("Casos", 12), "matricula"),
            ),
        )

        val janela = documento.window(firstLine = 2, maxLines = 2)

        assertEquals(
            listOf(SourceCoordinate.Page(7), SourceCoordinate.Row("Casos", 12)),
            janela.lines.map { it.coordinate },
        )
    }

    @Test
    fun `cada familia diz a coordenada do seu jeito`() {
        assertEquals("page 7", SourceCoordinate.Page(7).label)
        assertEquals("sheet 'Casos' row 12", SourceCoordinate.Row("Casos", 12).label)
        assertEquals("paragraph 3", SourceCoordinate.Paragraph(3).label)
        assertEquals("slide 4", SourceCoordinate.Slide(4).label)
    }

    @Test
    fun `janela sem sentido e recusada`() {
        val documento = documento(3)

        assertThrows<IllegalArgumentException> { documento.window(firstLine = 0, maxLines = 1) }
        assertThrows<IllegalArgumentException> { documento.window(firstLine = 1, maxLines = 0) }
    }

    private fun documento(linhas: Int) = ExtractedDocument(
        (1..linhas).map { ExtractedLine(SourceCoordinate.Page(it), "linha $it") },
    )
}
