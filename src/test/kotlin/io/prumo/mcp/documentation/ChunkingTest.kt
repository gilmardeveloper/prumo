package io.prumo.mcp.documentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * O corte do documento extraído em pedaços indexáveis.
 *
 * O que estes testes protegem é o endereço: pedaço que atravessa duas páginas não tem o que citar, e
 * corte que muda entre execuções faz o índice divergir do documento sem que nada acuse.
 */
class ChunkingTest {

    @Test
    fun `pedaco nunca cruza a fronteira da coordenada`() {
        val documento = ExtractedDocument(
            listOf(
                ExtractedLine(SourceCoordinate.Page(1), "primeira linha da pagina um"),
                ExtractedLine(SourceCoordinate.Page(1), "segunda linha da pagina um"),
                ExtractedLine(SourceCoordinate.Page(2), "linha da pagina dois"),
            ),
        )

        val pedacos = chunksOf(documento)

        assertEquals(2, pedacos.size)
        assertEquals(SourceCoordinate.Page(1), pedacos.first().coordinate)
        assertEquals(SourceCoordinate.Page(2), pedacos.last().coordinate)
        assertTrue(pedacos.first().text.contains("segunda linha"))
        assertTrue(!pedacos.first().text.contains("pagina dois"))
    }

    @Test
    fun `o mesmo documento corta igual em duas execucoes`() {
        val documento = pagina(1, 40)

        val primeira = chunksOf(documento, maxChars = 200, overlapChars = 40)
        val segunda = chunksOf(documento, maxChars = 200, overlapChars = 40)

        assertEquals(primeira, segunda)
        assertTrue(primeira.size > 1, "o teste perdeu o alvo: precisava de mais de um pedaço")
    }

    @Test
    fun `pagina maior que o alvo vira mais de um pedaco, todos dela`() {
        val pedacos = chunksOf(pagina(7, 40), maxChars = 200, overlapChars = 40)

        assertTrue(pedacos.size > 1)
        assertTrue(pedacos.all { it.coordinate == SourceCoordinate.Page(7) })
        assertTrue(pedacos.all { it.text.length <= 200 + 40 }, pedacos.map { it.text.length }.toString())
    }

    @Test
    fun `o pedaco seguinte repete o fim do anterior`() {
        val pedacos = chunksOf(pagina(1, 40), maxChars = 200, overlapChars = 40)

        val cauda = pedacos.first().text.takeLast(40)
        assertTrue(pedacos[1].text.startsWith(cauda.trimStart()), "sem sobreposição: ${pedacos[1].text.take(60)}")
    }

    @Test
    fun `documento menor que o alvo vira um pedaco so`() {
        val documento = ExtractedDocument(
            listOf(ExtractedLine(SourceCoordinate.Paragraph(1), "regra curta")),
        )

        val pedacos = chunksOf(documento)

        assertEquals(1, pedacos.size)
        assertEquals("regra curta", pedacos.single().text)
        assertEquals(1, pedacos.single().firstLine)
        assertEquals(1, pedacos.single().lastLine)
    }

    @Test
    fun `o pedaco sabe em que linha do documento extraido ele comeca`() {
        val documento = ExtractedDocument(
            listOf(
                ExtractedLine(SourceCoordinate.Page(1), "abertura"),
                ExtractedLine(SourceCoordinate.Page(2), "meio"),
                ExtractedLine(SourceCoordinate.Page(3), "fecho"),
            ),
        )

        val pedacos = chunksOf(documento)

        assertEquals(listOf(1, 2, 3), pedacos.map { it.firstLine })
        assertEquals(listOf(1, 2, 3), pedacos.map { it.lastLine })
    }

    @Test
    fun `documento vazio nao vira pedaco nenhum`() {
        assertTrue(chunksOf(ExtractedDocument(emptyList())).isEmpty())
    }

    @Test
    fun `tamanho sem sentido e recusado`() {
        val documento = pagina(1, 2)

        assertThrows<IllegalArgumentException> { chunksOf(documento, maxChars = 0) }
        assertThrows<IllegalArgumentException> { chunksOf(documento, overlapChars = -1) }
        assertThrows<IllegalArgumentException> { chunksOf(documento, maxChars = 100, overlapChars = 100) }
    }

    private fun pagina(numero: Int, linhas: Int) = ExtractedDocument(
        (1..linhas).map { ExtractedLine(SourceCoordinate.Page(numero), "linha $it com algum texto de enchimento") },
    )
}
