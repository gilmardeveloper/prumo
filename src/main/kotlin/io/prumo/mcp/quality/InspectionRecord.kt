package io.prumo.mcp.quality

/**
 * Uma inspeção registrada nesta instalação da IDE.
 *
 * Descreve o que a IDE sabe procurar. Nenhum campo se refere a um arquivo: nada aqui é resultado de
 * análise.
 */
data class InspectionRecord(
    /** Identificador estável, sempre em inglês. Único dentro do catálogo. */
    val shortName: String,
    /** Nome de exibição, traduzido conforme o idioma da IDE. */
    val displayName: String,
    /** Grupo sob o qual a IDE a apresenta. Vazio quando a inspeção não declara grupo. */
    val group: String,
    /** Nível configurado no perfil corrente, pelo nome interno da severidade. */
    val severity: String,
    /** Linguagem declarada pela inspeção. Ausente quando ela não declara nenhuma. */
    val language: String?,
    /** O que a declaração da inspeção diz sobre estar ligada de fábrica. */
    val enabledByDefault: Boolean,
)

/**
 * Recorte do catálogo.
 *
 * Campo ausente não filtra. A comparação de texto ignora maiúsculas e é exata: `severity` e `group`
 * não casam por fragmento, e `language` só casa a inspeção que declara aquela linguagem — a que não
 * declara nenhuma fica de fora, porque o catálogo diz o que está registrado, não o que se aplicaria
 * a um arquivo.
 */
data class InspectionFilter(
    val language: String? = null,
    val severity: String? = null,
    val group: String? = null,
    val enabledByDefault: Boolean? = null,
)

/**
 * Ordem estável do catálogo: grupo, depois identificador.
 *
 * A ordenação usa `shortName` como desempate porque ele é único e não muda com o idioma da IDE —
 * o mesmo catálogo sai na mesma ordem em qualquer instalação.
 */
fun List<InspectionRecord>.sortedForCatalog(): List<InspectionRecord> =
    sortedWith(compareBy({ it.group }, { it.shortName }))

/** As inspeções que atendem a todos os critérios informados, na ordem em que chegaram. */
fun List<InspectionRecord>.matching(criteria: InspectionFilter): List<InspectionRecord> =
    filter { record ->
        criteria.language.matches(record.language) &&
            criteria.severity.matches(record.severity) &&
            criteria.group.matches(record.group) &&
            (criteria.enabledByDefault == null || criteria.enabledByDefault == record.enabledByDefault)
    }

private fun String?.matches(value: String?): Boolean =
    this == null || (value != null && value.equals(this, ignoreCase = true))
