package io.prumo.mcp.knowledge

/**
 * Um registro que atendeu à consulta, com a força do casamento.
 *
 * @property id identificador do registro casado.
 * @property score o quanto ele responde à consulta. Comparável apenas dentro do mesmo resultado.
 */
data class KnowledgeHit(val id: String, val score: Float)

/**
 * Como a consulta foi entendida, junto com o que ela achou.
 *
 * Os termos vão de volta ao cliente porque a análise de texto reduz a consulta antes de procurar:
 * quem pediu "pagamentos" procura por "pagament". Sem isso, resultado vazio não se distingue de
 * consulta que virou nada.
 *
 * @property terms termos em que a consulta foi reduzida, na ordem em que aparecem.
 * @property hits registros casados, do mais forte para o mais fraco.
 */
data class KnowledgeSearchResult(val terms: List<String>, val hits: List<KnowledgeHit>)

/**
 * Recuperação por relevância sobre os registros da memória.
 *
 * A interface não menciona o motor de propósito: quem o implementa depende de um módulo da IDE que
 * não é API sancionada, e trocá-lo não pode obrigar a reescrever o domínio — o mesmo isolamento que
 * [KnowledgeStore] faz com o armazenamento.
 *
 * O índice é derivado dos registros entregues em cada chamada e não sobrevive a ela. Índice
 * persistido seria uma segunda cópia do dado, com vida própria e sujeita a divergir dele.
 */
interface KnowledgeSearch {

    /**
     * Os registros que atendem à consulta, do mais relevante para o menos.
     *
     * A ordem é determinística: score decrescente, e empate desempatado pelo identificador. Duas
     * chamadas iguais devolvem a mesma lista na mesma ordem.
     *
     * @param records os registros a considerar, já recortados por quem chamou.
     * @param query o texto procurado, como o cliente o escreveu.
     * @throws IllegalArgumentException quando a consulta é vazia ou só espaços.
     */
    fun search(records: List<KnowledgeRecord>, query: String): KnowledgeSearchResult
}
