package io.prumo.mcp.documentation

/**
 * Funde duas listas ordenadas em uma, pela posição de cada item em cada uma delas.
 *
 * As duas buscas do produto devolvem notas que não se comparam: uma é BM25, a outra é distância de
 * vetor. Somá-las seria inventar uma escala; intercalá-las descarta a informação de que um item está
 * bem colocado nas duas. A fusão por posição resolve os dois problemas — cada lista contribui com
 * `1 / (k + posição)`, e quem aparece bem nas duas soma mais que quem lidera só uma.
 *
 * A constante amortece a diferença entre as primeiras posições: sem ela, o primeiro colocado de uma
 * lista valeria o dobro do segundo, e a fusão viraria disputa entre os dois primeiros.
 *
 * @param identity como reconhecer que um item das duas listas é o mesmo.
 * @param k amortecimento; 60 é o valor de referência da literatura de fusão por posição.
 */
fun <T> fuseByRank(
    first: List<T>,
    second: List<T>,
    identity: (T) -> String,
    k: Int = DEFAULT_RANK_CONSTANT,
): List<T> {
    require(k >= 1) { "k must be 1 or greater." }

    val pontos = LinkedHashMap<String, Double>()
    val itens = LinkedHashMap<String, T>()
    listOf(first, second).forEach { lista ->
        lista.forEachIndexed { posicao, item ->
            val chave = identity(item)
            itens.putIfAbsent(chave, item)
            pontos[chave] = (pontos[chave] ?: 0.0) + 1.0 / (k + posicao + 1)
        }
    }
    // Desempate pela ordem de aparição, que é estável: sem isso, dois itens de mesma pontuação podem
    // trocar de lugar entre duas chamadas iguais.
    val ordem = itens.keys.withIndex().associate { (indice, chave) -> chave to indice }
    return itens.keys
        .sortedWith(compareByDescending<String> { pontos.getValue(it) }.thenBy { ordem.getValue(it) })
        .map { itens.getValue(it) }
}

/** Amortecimento padrão da fusão por posição. */
const val DEFAULT_RANK_CONSTANT = 60
