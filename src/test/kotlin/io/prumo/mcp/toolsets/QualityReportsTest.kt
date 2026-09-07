package io.prumo.mcp.toolsets

import io.prumo.mcp.quality.InspectionCatalog
import io.prumo.mcp.quality.InspectionFilter
import io.prumo.mcp.quality.InspectionProfileOrigin
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

    private fun catalogo(
        inspections: List<InspectionRecord> = catalog,
        scope: InspectionProfileOrigin.Scope = InspectionProfileOrigin.Scope.PROJECT,
        profileName: String = "Project Default",
    ) = InspectionCatalog(InspectionProfileOrigin(profileName, scope), inspections)

    @Test
    fun `as contagens cobrem o recorte inteiro, nao a janela devolvida`() {
        val response = QualityReports.catalog(catalogo(), InspectionFilter(), maxResults = 3)

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
        val response = QualityReports.catalog(catalogo(), InspectionFilter(language = "kotlin"), maxResults = 2)

        assertEquals(10, response.matchCount)
        assertEquals(30, response.enabledCount, "enabledCount descreve o perfil, não o recorte")
        assertEquals(10, response.bySeverity.values.sum())
    }

    @Test
    fun `truncated diz se ficou alguma coisa de fora`() {
        assertTrue(QualityReports.catalog(catalogo(), InspectionFilter(), maxResults = 29).truncated)
        assertFalse(QualityReports.catalog(catalogo(), InspectionFilter(), maxResults = 30).truncated)
        assertFalse(QualityReports.catalog(catalogo(), InspectionFilter(), maxResults = 31).truncated)
    }

    @Test
    fun `a janela tem teto, e pedir mais que o teto nao o ultrapassa`() {
        val grande = List(QualityReports.MAX_INSPECTIONS + 50) { index ->
            catalog.first().copy(shortName = "Inspection%04d".format(index))
        }

        val response = QualityReports.catalog(catalogo(grande), InspectionFilter(), maxResults = Int.MAX_VALUE)

        assertEquals(QualityReports.MAX_INSPECTIONS, response.inspections.size)
        assertEquals(grande.size, response.matchCount)
        assertTrue(response.truncated)
    }

    @Test
    fun `janela zero ou negativa devolve uma inspecao, nunca a lista inteira nem nenhuma`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { pedido ->
            val response = QualityReports.catalog(catalogo(), InspectionFilter(), maxResults = pedido)

            assertEquals(1, response.inspections.size, "maxResults=$pedido")
            assertEquals(30, response.matchCount)
            assertTrue(response.truncated)
        }
    }

    @Test
    fun `a janela sai na ordem do catalogo, nao na ordem de chegada`() {
        val response = QualityReports.catalog(catalogo(catalog.reversed()), InspectionFilter(), maxResults = 3)

        assertEquals(
            listOf("Inspection00", "Inspection01", "Inspection02"),
            response.inspections.map { it.shortName },
        )
        assertEquals("Probable bugs", response.inspections.first().group)
    }

    @Test
    fun `recorte sem casamento devolve vazio sem se dizer truncado`() {
        val response = QualityReports.catalog(catalogo(), InspectionFilter(language = "cobol"), maxResults = 50)

        assertEquals(0, response.matchCount)
        assertTrue(response.inspections.isEmpty())
        assertTrue(response.bySeverity.isEmpty())
        assertFalse(response.truncated)
    }

    @Test
    fun `catalogo vazio nao quebra a montagem da resposta`() {
        val response = QualityReports.catalog(catalogo(emptyList()), InspectionFilter(), maxResults = 50)

        assertEquals(0, response.enabledCount)
        assertEquals(0, response.matchCount)
        assertFalse(response.truncated)
    }

    @Test
    fun `a resposta diz de qual perfil o catalogo veio`() {
        val doProjeto = QualityReports.catalog(catalogo(profileName = "Time"), InspectionFilter(), maxResults = 1)

        assertEquals("Time", doProjeto.source.profileName)
        assertEquals("PROJECT", doProjeto.source.profileScope)
        assertTrue(
            doProjeto.source.note.contains("travels with it"),
            "a nota do perfil do projeto: ${doProjeto.source.note}",
        )
    }

    @Test
    fun `perfil da instalacao avisa que outro desenvolvedor pode ver outro conjunto`() {
        val daInstalacao = QualityReports.catalog(
            catalogo(scope = InspectionProfileOrigin.Scope.APPLICATION),
            InspectionFilter(),
            maxResults = 1,
        )

        assertEquals("APPLICATION", daInstalacao.source.profileScope)
        assertTrue(
            daInstalacao.source.note.contains("different ruleset"),
            "a nota do perfil da instalação: ${daInstalacao.source.note}",
        )
    }

    @Test
    fun `as duas notas dizem que as contagens descrevem a instalacao, nao o Prumo`() {
        listOf(InspectionProfileOrigin.Scope.PROJECT, InspectionProfileOrigin.Scope.APPLICATION).forEach { scope ->
            val response = QualityReports.catalog(catalogo(scope = scope), InspectionFilter(), maxResults = 1)

            assertTrue(response.source.note.contains("not the Prumo plugin"), "escopo $scope")
        }
    }

    @Test
    fun `valor que o catalogo nao conhece se distingue de recorte legitimamente vazio`() {
        val errado = QualityReports.catalog(catalogo(), InspectionFilter(severity = "WARNIG"), maxResults = 50)
        val vazio = QualityReports.catalog(
            catalogo(),
            InspectionFilter(language = "kotlin", group = "Probable bugs"),
            maxResults = 50,
        )

        assertEquals(0, errado.matchCount)
        assertEquals(0, vazio.matchCount)
        assertEquals(mapOf("severity" to "WARNIG"), errado.unknownFilters)
        assertTrue(vazio.unknownFilters.isEmpty(), "recorte com valores existentes: ${vazio.unknownFilters}")
    }

    @Test
    fun `o criterio recusado vem com os valores que o catalogo aceita`() {
        val response = QualityReports.catalog(
            catalogo(),
            InspectionFilter(language = "cobol", severity = "FATAL"),
            maxResults = 50,
        )

        assertEquals(mapOf("language" to "cobol", "severity" to "FATAL"), response.unknownFilters)
        assertEquals(listOf("JAVA", "kotlin"), response.knownValues["language"])
        assertEquals(listOf("ERROR", "WARNING"), response.knownValues["severity"])
    }

    @Test
    fun `grupo inexistente e acusado sem despejar os grupos da instalacao`() {
        val response = QualityReports.catalog(
            catalogo(),
            InspectionFilter(group = "Grupo que nao existe"),
            maxResults = 50,
        )

        assertEquals(mapOf("group" to "Grupo que nao existe"), response.unknownFilters)
        assertTrue(response.knownValues.isEmpty(), "grupos não entram: ${response.knownValues}")
    }

    @Test
    fun `criterio informado com outra caixa continua sendo conhecido`() {
        val response = QualityReports.catalog(catalogo(), InspectionFilter(language = "java"), maxResults = 1)

        assertTrue(response.unknownFilters.isEmpty())
        assertEquals(20, response.matchCount)
    }
}
