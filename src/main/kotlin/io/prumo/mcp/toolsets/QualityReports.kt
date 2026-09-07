package io.prumo.mcp.toolsets

import io.prumo.mcp.quality.InspectionFilter
import io.prumo.mcp.quality.InspectionRecord
import io.prumo.mcp.quality.matching
import io.prumo.mcp.quality.sortedForCatalog
import kotlinx.serialization.Serializable

@Serializable
data class InspectionResponse(
    /** Identificador estável, sempre em inglês. */
    val shortName: String,
    /** Nome de exibição, no idioma da IDE. */
    val displayName: String,
    val group: String,
    /** Nível configurado no perfil corrente. Não é vocabulário fechado. */
    val severity: String,
    /** Linguagem declarada pela inspeção. Ausente quando ela não declara nenhuma. */
    val language: String? = null,
    val enabledByDefault: Boolean,
)

@Serializable
data class InspectionCatalogResponse(
    /** Quantas inspeções o perfil corrente tem habilitadas, antes de qualquer recorte. */
    val enabledCount: Int,
    /** Quantas atendem ao recorte pedido. */
    val matchCount: Int,
    /** Quantas do recorte em cada severidade. Cobre o recorte inteiro, não só a janela devolvida. */
    val bySeverity: Map<String, Int>,
    /** Quantas do recorte em cada grupo. Cobre o recorte inteiro, não só a janela devolvida. */
    val byGroup: Map<String, Int>,
    val inspections: List<InspectionResponse>,
    val truncated: Boolean,
)

/**
 * Resposta da tool de catálogo.
 *
 * Os agregados vêm antes do detalhe e cobrem o recorte inteiro: um catálogo de quase mil inspeções
 * não cabe numa resposta, e a contagem por severidade e por grupo responde sozinha boa parte das
 * perguntas.
 */
object QualityReports {

    const val MAX_INSPECTIONS = 200

    fun catalog(
        catalog: List<InspectionRecord>,
        criteria: InspectionFilter,
        maxResults: Int,
    ): InspectionCatalogResponse {
        val matches = catalog.matching(criteria).sortedForCatalog()
        val window = matches.take(maxResults.coerceIn(1, MAX_INSPECTIONS))
        return InspectionCatalogResponse(
            enabledCount = catalog.size,
            matchCount = matches.size,
            bySeverity = matches.groupingBy { it.severity }.eachCount().toSortedMap(),
            byGroup = matches.groupingBy { it.group }.eachCount().toSortedMap(),
            inspections = window.map {
                InspectionResponse(
                    shortName = it.shortName,
                    displayName = it.displayName,
                    group = it.group,
                    severity = it.severity,
                    language = it.language,
                    enabledByDefault = it.enabledByDefault,
                )
            },
            truncated = window.size < matches.size,
        )
    }
}
