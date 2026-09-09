package io.prumo.mcp.documentation

/**
 * Um pedaço de documento do tamanho que a recuperação indexa, com a coordenada da fonte.
 *
 * @property firstLine primeira linha do documento extraído que entrou neste pedaço, contada a
 *   partir de 1 — é por ela que se pede a vizinhança pela janela de leitura.
 */
data class DocumentChunk(
    val coordinate: SourceCoordinate,
    val text: String,
    val firstLine: Int,
    val lastLine: Int,
)

/**
 * Corta um documento extraído em pedaços indexáveis.
 *
 * Duas regras sustentam o resultado. O pedaço **nunca cruza a fronteira da coordenada**: página,
 * aba, parágrafo e slide começam pedaço novo, porque um pedaço que atravessa duas páginas não tem
 * endereço para citar. E o corte é determinístico: o mesmo documento produz sempre os mesmos
 * pedaços, o que faz o índice ser reconstruível sem virar outra coisa.
 *
 * A sobreposição existe porque a frase que responde à pergunta costuma cair na emenda entre dois
 * pedaços; sem ela, ela some dos dois.
 *
 * @param maxChars tamanho alvo de um pedaço, em caracteres.
 * @param overlapChars quanto do fim de um pedaço reaparece no começo do seguinte, dentro da mesma
 *   coordenada.
 * @throws IllegalArgumentException quando os tamanhos não fazem sentido entre si.
 */
fun chunksOf(
    document: ExtractedDocument,
    maxChars: Int = DEFAULT_MAX_CHARS,
    overlapChars: Int = DEFAULT_OVERLAP_CHARS,
): List<DocumentChunk> {
    require(maxChars >= 1) { "maxChars must be 1 or greater." }
    require(overlapChars >= 0) { "overlapChars must be 0 or greater." }
    require(overlapChars < maxChars) { "overlapChars must be smaller than maxChars." }

    val chunks = mutableListOf<DocumentChunk>()
    var atual = StringBuilder()
    var coordenada: SourceCoordinate? = null
    var primeira = 0
    var ultima = 0

    fun fechar() {
        val coordenadaAtual = coordenada ?: return
        if (atual.isNotBlank()) {
            chunks.add(DocumentChunk(coordenadaAtual, atual.toString().trim(), primeira, ultima))
        }
        atual = StringBuilder()
    }

    document.lines.forEachIndexed { indice, line ->
        val numero = indice + 1
        if (coordenada != line.coordinate) {
            fechar()
            coordenada = line.coordinate
            primeira = numero
        }
        if (atual.isNotEmpty() && atual.length + line.text.length + 1 > maxChars) {
            fechar()
            val cauda = chunks.lastOrNull()
                ?.takeIf { it.coordinate == line.coordinate }
                ?.text
                ?.takeLast(overlapChars)
                .orEmpty()
            atual.append(cauda)
            primeira = numero
        }
        if (atual.isNotEmpty()) {
            atual.append('\n')
        }
        atual.append(line.text)
        ultima = numero
    }
    fechar()
    return chunks
}

/**
 * Tamanho alvo de um pedaço.
 *
 * Medido sobre o MOS do eSocial, com 20 perguntas em linguagem natural: 400 caracteres deu 9 acertos
 * em 20, 800 deu 13, 1.200 deu 11 e 2.000 deu 12. É a alavanca de maior efeito medido da família —
 * maior que trocar o modelo por um 2,4 vezes maior, que piorou.
 */
const val DEFAULT_MAX_CHARS = 800

/** Quanto do pedaço anterior reaparece no seguinte. */
const val DEFAULT_OVERLAP_CHARS = 100
