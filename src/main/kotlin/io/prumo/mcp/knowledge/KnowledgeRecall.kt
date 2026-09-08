package io.prumo.mcp.knowledge

/**
 * Os registros que atenderam a uma consulta da memória, cada um com o score que o trouxe.
 *
 * @property records os registros escolhidos, na ordem em que devem ser devolvidos. O score é nulo
 *   quando não houve consulta de texto, porque não há o que pontuar.
 * @property terms termos em que a consulta de texto foi reduzida, nulo quando não houve consulta.
 */
data class RecalledRecords(
    val records: List<Pair<KnowledgeRecord, Float?>>,
    val terms: List<String>?,
)

/**
 * Escolhe os registros que respondem a uma consulta da memória.
 *
 * Etiqueta e origem são recortes exatos e valem antes da relevância: o motor pontua apenas o que
 * sobrou deles, e o resultado descreve o recorte que o cliente pediu. Consulta de texto ausente
 * devolve o recorte inteiro na ordem em que veio, sem score.
 *
 * @param records os registros guardados no workspace.
 * @param tag etiqueta exigida, comparada sem diferenciar caixa. Nula ou em branco não recorta.
 * @param sourceId identificador da origem exigida. Nulo ou em branco não recorta.
 * @param query texto procurado, como o cliente o escreveu. Nulo ou em branco dispensa o motor.
 * @param search o motor que pontua o recorte.
 */
fun recallRecords(
    records: List<KnowledgeRecord>,
    tag: String?,
    sourceId: String?,
    query: String?,
    search: KnowledgeSearch,
): RecalledRecords {
    val narrowed = records
        .filter { record -> tag.isNullOrBlank() || record.tags.any { it.equals(tag, ignoreCase = true) } }
        .filter { record -> sourceId.isNullOrBlank() || record.provenance.sourceId == sourceId }
    if (query.isNullOrBlank()) {
        return RecalledRecords(narrowed.map { it to null }, terms = null)
    }
    val byId = narrowed.associateBy { it.id }
    val result = search.search(narrowed, query)
    return RecalledRecords(
        records = result.hits.mapNotNull { hit -> byId[hit.id]?.let { it to hit.score } },
        terms = result.terms,
    )
}
