package io.prumo.mcp.toolsets

import io.prumo.mcp.quality.InspectionFilter
import io.prumo.mcp.quality.InspectionRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A descrição da tool afirma que as contagens cobrem o recorte inteiro, e não a janela devolvida.
 * É a garantia pela qual um cliente entende um catálogo de mil e quinhentas inspeções pagando por
 * poucas, e por isso é a que precisa de teste.
 */
class QualityReportsTest {

    private val catalog = List(30) { index ->
        InspectionRecord(
            shortName = "Inspection%02d".format(index),
            displayName = "Inspection %02d".format(index),
            group = if (index < 10) "Probable bugs" else "Style issues",
            severity = if (index % 2 == 0) "WARNING" else "ERROR",
            language = if (index < 20) "JAVA" else "kotlin",
            enabledByDefault = true,
        )
    }

    @Test
    fun `as contagens cobrem o recorte inteiro, nao a janela devolvida`() {
        val response = QualityReports.catalog(catalog, InspectionFilter(), maxResults = 3)

        assertEquals(3, response.inspections.size)
        assertEquals(30, response.matchCount)
        assertEquals(30, response.enabledCount)
        assertEquals(mapOf("ERROR" to 15, "WARNING" to 15), response.bySeverity)
        assertEquals(mapOf("Probable bugs" to 10, "Style issues" to 20), response.byGroup)
        assertEquals(30, response.bySeverity.values.sum())
        assertEquals(30, response.byGroup.values.sum())
    }

    @Test
    fun `o recorte muda as contagens, e o total do perfil continua o mesmo`() {
        val response = QualityReports.catalog(catalog, InspectionFilter(language = "kotlin"), maxResults = 2)

        assertEquals(10, response.matchCount)
        assertEquals(30, response.enabledCount, "enabledCount descreve o perfil, não o recorte")
        assertEquals(10, response.bySeverity.values.sum())
    }

    @Test
    fun `truncated diz se ficou alguma coisa de fora`() {
        assertTrue(QualityReports.catalog(catalog, InspectionFilter(), maxResults = 29).truncated)
        assertFalse(QualityReports.catalog(catalog, InspectionFilter(), maxResults = 30).truncated)
        assertFalse(QualityReports.catalog(catalog, InspectionFilter(), maxResults = 31).truncated)
    }

    @Test
    fun `a janela tem teto, e pedir mais que o teto nao o ultrapassa`() {
        val grande = List(QualityReports.MAX_INSPECTIONS + 50) { index ->
            catalog.first().copy(shortName = "Inspection%04d".format(index))
        }

        val response = QualityReports.catalog(grande, InspectionFilter(), maxResults = Int.MAX_VALUE)

        assertEquals(QualityReports.MAX_INSPECTIONS, response.inspections.size)
        assertEquals(grande.size, response.matchCount)
        assertTrue(response.truncated)
    }

    @Test
    fun `janela zero ou negativa devolve uma inspecao, nunca a lista inteira nem nenhuma`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { pedido ->
            val response = QualityReports.catalog(catalog, InspectionFilter(), maxResults = pedido)

            assertEquals(1, response.inspections.size, "maxResults=$pedido")
            assertEquals(30, response.matchCount)
            assertTrue(response.truncated)
        }
    }

    @Test
    fun `a janela sai na ordem do catalogo, nao na ordem de chegada`() {
        val response = QualityReports.catalog(catalog.reversed(), InspectionFilter(), maxResults = 3)

        assertEquals(
            listOf("Inspection00", "Inspection01", "Inspection02"),
            response.inspections.map { it.shortName },
        )
        assertEquals("Probable bugs", response.inspections.first().group)
    }

    @Test
    fun `recorte sem casamento devolve vazio sem se dizer truncado`() {
        val response = QualityReports.catalog(catalog, InspectionFilter(language = "cobol"), maxResults = 50)

        assertEquals(0, response.matchCount)
        assertTrue(response.inspections.isEmpty())
        assertTrue(response.bySeverity.isEmpty())
        assertFalse(response.truncated)
    }

    @Test
    fun `catalogo vazio nao quebra a montagem da resposta`() {
        val response = QualityReports.catalog(emptyList(), InspectionFilter(), maxResults = 50)

        assertEquals(0, response.enabledCount)
        assertEquals(0, response.matchCount)
        assertFalse(response.truncated)
    }
}
