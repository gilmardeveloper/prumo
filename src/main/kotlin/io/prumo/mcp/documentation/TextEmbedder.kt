package io.prumo.mcp.documentation

import kotlin.math.sqrt

/**
 * Transforma texto em vetor, para a busca por sentido.
 *
 * A interface não menciona o motor de propósito, pelo mesmo motivo de `KnowledgeSearch` e de
 * `DocumentIndex`: quem a implementa depende de biblioteca nativa, e trocá-la não pode obrigar a
 * reescrever o domínio.
 *
 * O que o embutidor faz é **ordenar**, nunca redigir: o vetor decide qual trecho aparece, e o trecho
 * devolvido continua sendo o texto verbatim da fonte.
 */
/**
 * Para que serve o texto que se vai vetorizar.
 *
 * Não é detalhe de implementação: o modelo desta família é treinado com o papel declarado no começo
 * do texto, e trecho indexado e pergunta feita ocupam espaços diferentes. Quem chama diz o papel;
 * como ele é escrito é problema de quem conhece o modelo.
 */
enum class TextRole { PASSAGE, QUERY }

interface TextEmbedder {

    /** Quantas dimensões tem o vetor. Vetor de dimensão diferente não entra no mesmo índice. */
    val dimensions: Int

    /**
     * Os vetores dos textos, na mesma ordem em que vieram.
     *
     * @param role o papel de todos os textos deste lote.
     * @throws IllegalArgumentException quando a lista é vazia.
     */
    fun embed(texts: List<String>, role: TextRole): List<FloatArray>
}

/**
 * A média dos vetores de cada token, ignorando o enchimento.
 *
 * O modelo devolve um vetor por token; o que representa o trecho é a média dos que são texto de
 * verdade. Somar o enchimento junto aproximaria todos os trechos entre si, e a busca perderia a
 * capacidade de distinguir o que é próximo do que só é longo.
 *
 * @param tokens vetores por token, na ordem da sequência.
 * @param mask 1 para token de texto, 0 para enchimento.
 * @throws IllegalArgumentException quando as duas listas não têm o mesmo tamanho.
 */
fun meanPool(tokens: List<FloatArray>, mask: LongArray): FloatArray {
    require(tokens.isNotEmpty()) { "There is nothing to pool." }
    require(tokens.size == mask.size) { "Tokens and mask must have the same length." }

    val soma = FloatArray(tokens.first().size)
    var contados = 0
    tokens.forEachIndexed { indice, token ->
        if (mask[indice] != 0L) {
            contados++
            token.forEachIndexed { posicao, valor -> soma[posicao] += valor }
        }
    }
    if (contados == 0) {
        return soma
    }
    return FloatArray(soma.size) { soma[it] / contados }
}

/**
 * O vetor reduzido a comprimento 1.
 *
 * O índice compara por cosseno, que é produto interno entre vetores de comprimento 1. Normalizar
 * aqui, e não no índice, mantém a comparação estável para quem quer que consulte.
 */
fun l2Normalize(vector: FloatArray): FloatArray {
    val comprimento = sqrt(vector.fold(0.0) { soma, valor -> soma + valor * valor }).toFloat()
    if (comprimento == 0f) {
        return vector.copyOf()
    }
    return FloatArray(vector.size) { vector[it] / comprimento }
}
