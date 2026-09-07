package io.prumo.mcp.toolsets

import io.prumo.mcp.quality.InspectionCatalog
import io.prumo.mcp.quality.InspectionFilter
import io.prumo.mcp.quality.InspectionProfileOrigin
import io.prumo.mcp.quality.InspectionRecord
import io.prumo.mcp.quality.languages
import io.prumo.mcp.quality.matching
import io.prumo.mcp.quality.severities
import io.prumo.mcp.quality.sortedForCatalog
import io.prumo.mcp.quality.unknownCriteria
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
data class InspectionCatalogSource(
    /** Nome do perfil corrente, como a IDE o apresenta. */
    val profileName: String,
    /** `PROJECT` quando o perfil acompanha o projeto; `APPLICATION` quando é o da instalação. */
    val profileScope: String,
    /** O que este catálogo mede, escrito para quem lê a resposta sem a descrição da tool à mão. */
    val note: String,
)

@Serializable
data class InspectionCatalogResponse(
    /** De onde saiu o catálogo, e o que suas contagens descrevem. */
    val source: InspectionCatalogSource,
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
    /**
     * Critérios cujo valor não existe neste catálogo, mapeados ao valor recusado.
     *
     * Vazio quando todos os valores informados existem — inclusive quando o recorte não casa
     * inspeção nenhuma, que é resultado legítimo e não erro de escrita.
     */
    val unknownFilters: Map<String, String> = emptyMap(),
    /**
     * Os valores que este catálogo conhece para os critérios recusados de vocabulário curto.
     *
     * Preenchido para `severity` e `language`. `group` fica de fora porque uma instalação tem
     * centenas de grupos: para descobri-los, chamar sem filtro e ler `byGroup`.
     */
    val knownValues: Map<String, List<String>> = emptyMap(),
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
        catalog: InspectionCatalog,
        criteria: InspectionFilter,
        maxResults: Int,
    ): InspectionCatalogResponse {
        val inspections = catalog.inspections
        val matches = inspections.matching(criteria).sortedForCatalog()
        val window = matches.take(maxResults.coerceIn(1, MAX_INSPECTIONS))
        val unknown = inspections.unknownCriteria(criteria)
        return InspectionCatalogResponse(
            source = source(catalog.origin),
            enabledCount = inspections.size,
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
            unknownFilters = unknown,
            knownValues = knownValues(inspections, unknown.keys),
        )
    }

    private fun source(origin: InspectionProfileOrigin) = InspectionCatalogSource(
        profileName = origin.profileName,
        profileScope = origin.scope.name,
        note = when (origin.scope) {
            InspectionProfileOrigin.Scope.PROJECT -> PROJECT_NOTE
            InspectionProfileOrigin.Scope.APPLICATION -> APPLICATION_NOTE
        },
    )

    private fun knownValues(
        inspections: List<InspectionRecord>,
        rejected: Set<String>,
    ): Map<String, List<String>> = buildMap {
        if ("severity" in rejected) put("severity", inspections.severities())
        if ("language" in rejected) put("language", inspections.languages())
    }

    private const val COUNTS =
        "Counts describe this IDE installation and its enabled plugins, not the Prumo plugin."

    private const val PROJECT_NOTE =
        "This profile is configured in the project and travels with it, so it is the ruleset this " +
            "project agreed on. $COUNTS"

    private const val APPLICATION_NOTE =
        "This project declares no profile of its own, so the answer comes from the IDE-wide " +
            "profile of whoever opened it, and another developer may see a different ruleset. $COUNTS"
}
