package io.prumo.mcp.knowledge

/**
 * Os registros que atenderam a uma consulta da memória, cada um com o score que o trouxe.
 *
 * @property records os registros escolhidos, na ordem em que devem ser devolvidos. O score é nulo
 *   quando não houve consulta de texto, porque não há o que pontuar.
 * @property terms termos em que a consulta de texto foi reduzida, nulo quando não houve consulta.
 * @property outOfReachCount quantos registros da base inteira têm a fonte fora de alcance. Contado
 *   sobre a base, e não sobre a consulta, de propósito: um número que variasse com o texto
 *   procurado diria quais palavras existem dentro do que o desenvolvedor excluiu.
 */
data class RecalledRecords(
    val records: List<Pair<KnowledgeRecord, Float?>>,
    val terms: List<String>?,
    val outOfReachCount: Int,
)

/**
 * Escolhe os registros que respondem a uma consulta da memória.
 *
 * A ordem das três etapas é a garantia: o que está fora de alcance sai primeiro, e nem o recorte
 * exato nem o motor chegam a vê-lo; etiqueta e origem recortam em seguida; a relevância pontua por
 * último, apenas o que sobrou. Assim o score descreve o recorte pedido, e o texto de um registro
 * fora de alcance não influencia resposta nenhuma. Consulta de texto ausente devolve o recorte
 * inteiro na ordem em que veio, sem score.
 *
 * @param records os registros guardados no workspace.
 * @param tag etiqueta exigida, comparada sem diferenciar caixa. Nula ou em branco não recorta.
 * @param sourceId identificador da origem exigida. Nulo ou em branco não recorta.
 * @param query texto procurado, como o cliente o escreveu. Nulo ou em branco dispensa o motor.
 * @param search o motor que pontua o recorte.
 * @param outOfReach se a fonte de um registro está fora do alcance do workspace.
 */
fun recallRecords(
    records: List<KnowledgeRecord>,
    tag: String?,
    sourceId: String?,
    query: String?,
    search: KnowledgeSearch,
    outOfReach: (KnowledgeRecord) -> Boolean,
): RecalledRecords {
    val reachable = records.filterNot(outOfReach)
    val outOfReachCount = records.size - reachable.size
    val narrowed = reachable
        .filter { record -> tag.isNullOrBlank() || record.tags.any { it.equals(tag, ignoreCase = true) } }
        .filter { record -> sourceId.isNullOrBlank() || record.provenance.sourceId == sourceId }
    if (query.isNullOrBlank()) {
        return RecalledRecords(narrowed.map { it to null }, terms = null, outOfReachCount = outOfReachCount)
    }
    val byId = narrowed.associateBy { it.id }
    val result = search.search(narrowed, query)
    return RecalledRecords(
        records = result.hits.mapNotNull { hit -> byId[hit.id]?.let { it to hit.score } },
        terms = result.terms,
        outOfReachCount = outOfReachCount,
    )
}
