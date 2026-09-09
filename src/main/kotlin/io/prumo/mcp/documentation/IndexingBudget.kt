package io.prumo.mcp.documentation

/**
 * Indexa o que couber no tempo, e diz quanto ficou de fora.
 *
 * Indexar um documento grande custa segundos, e a busca não pode segurar a resposta até o acervo
 * inteiro ficar pronto — o cliente desiste antes e conclui que a ferramenta não funciona. Esta
 * função gasta até o teto, responde com o que conseguiu, e devolve quantas fontes ficaram para a
 * chamada seguinte, que continua de onde esta parou.
 *
 * @param sources as fontes a considerar, na ordem em que devem ser tentadas.
 * @param indexed se a fonte já está indexada e atualizada — consultar isso não gasta orçamento.
 * @param index indexa a fonte, e é a operação cara.
 * @param elapsedNanos quanto tempo já se gastou, em nanossegundos, desde o começo da chamada.
 * @param budgetNanos o teto de tempo.
 * @return quantas fontes ficaram sem indexar.
 */
fun <T> indexWithinBudget(
    sources: List<T>,
    indexed: (T) -> Boolean,
    index: (T) -> Unit,
    elapsedNanos: () -> Long,
    budgetNanos: Long,
): Int = sources.count { source ->
    when {
        indexed(source) -> false
        elapsedNanos() < budgetNanos -> {
            index(source)
            false
        }
        else -> true
    }
}
