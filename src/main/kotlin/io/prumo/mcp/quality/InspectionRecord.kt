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
 * Monta um registro a partir do metadado cru da plataforma.
 *
 * A plataforma não é uniforme no que devolve para o que a inspeção não declara: o nome de exibição
 * e o grupo podem vir nulos, e a linguagem vem ora nula, ora como texto vazio. Aqui as três formas
 * de "não declarado" viram uma só, e a fronteira que lê a IDE fica sem decisão nenhuma.
 */
fun inspectionRecord(
    shortName: String,
    displayName: String?,
    group: String?,
    severity: String,
    language: String?,
    enabledByDefault: Boolean,
): InspectionRecord = InspectionRecord(
    shortName = shortName,
    displayName = displayName?.takeIf { it.isNotBlank() } ?: shortName,
    group = group?.takeIf { it.isNotBlank() } ?: "",
    severity = severity,
    language = language?.takeIf { it.isNotBlank() },
    enabledByDefault = enabledByDefault,
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

/**
 * Critérios cujo valor não existe em inspeção nenhuma do catálogo, mapeados ao valor recusado.
 *
 * Um recorte legítimo sem resultado e um valor escrito errado devolvem a mesma lista vazia. Esta
 * função separa os dois casos: o que aparece aqui é valor que o catálogo não conhece.
 */
fun List<InspectionRecord>.unknownCriteria(criteria: InspectionFilter): Map<String, String> {
    val unknown = LinkedHashMap<String, String>()
    criteria.language?.takeUnless { value -> any { value.equals(it.language, ignoreCase = true) } }
        ?.let { unknown["language"] = it }
    criteria.severity?.takeUnless { value -> any { value.equals(it.severity, ignoreCase = true) } }
        ?.let { unknown["severity"] = it }
    criteria.group?.takeUnless { value -> any { value.equals(it.group, ignoreCase = true) } }
        ?.let { unknown["group"] = it }
    return unknown
}

/** As severidades que aparecem no catálogo, em ordem alfabética. */
fun List<InspectionRecord>.severities(): List<String> = map { it.severity }.distinct().sorted()

/** As linguagens declaradas no catálogo, em ordem alfabética. Inspeção sem linguagem não entra. */
fun List<InspectionRecord>.languages(): List<String> = mapNotNull { it.language }.distinct().sorted()
