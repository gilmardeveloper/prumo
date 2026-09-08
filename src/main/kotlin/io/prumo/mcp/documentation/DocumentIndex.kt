package io.prumo.mcp.documentation

/**
 * Um pedaço de documento pronto para ser indexado, com a fonte de onde ele veio.
 *
 * @property vector representação vetorial do texto, ou nulo quando não há modelo disponível — sem
 *   ele o pedaço continua indexado e continua sendo achado por palavra.
 */
data class IndexedChunk(
    val documentationId: String,
    val path: String,
    val chunk: DocumentChunk,
    val vector: FloatArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is IndexedChunk &&
                    documentationId == other.documentationId &&
                    path == other.path &&
                    chunk == other.chunk &&
                    vector.contentEqualsOrNull(other.vector)
                )

    override fun hashCode(): Int =
        (((documentationId.hashCode() * 31) + path.hashCode()) * 31 + chunk.hashCode()) * 31 +
            (vector?.contentHashCode() ?: 0)
}

private fun FloatArray?.contentEqualsOrNull(other: FloatArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

/**
 * Um pedaço que atendeu à busca, com a coordenada para citar e a linha para pedir a vizinhança.
 *
 * @property score o quanto ele responde à consulta, comparável apenas dentro do mesmo resultado.
 * @property semantic verdadeiro quando o pedaço veio pela proximidade vetorial, e não só por
 *   palavra: é a diferença entre "o documento diz isto" e "o documento tem esta palavra".
 */
data class DocumentHit(
    val documentationId: String,
    val path: String,
    val coordinate: String,
    val text: String,
    val firstLine: Int,
    val lastLine: Int,
    val score: Float,
    val semantic: Boolean,
)

/**
 * Recuperação de trechos de documentação.
 *
 * A interface não menciona o motor de propósito, pelo mesmo motivo de `KnowledgeSearch`: quem o
 * implementa depende de um módulo da IDE, e trocá-lo não pode obrigar a reescrever o domínio.
 *
 * O que se devolve é sempre o texto **verbatim** do documento. O vetor decide qual trecho aparece;
 * ele não produz, não resume e não reescreve o trecho.
 */
interface DocumentIndex {

    /** Substitui no índice tudo o que veio de [documentationId], deixando as outras fontes intactas. */
    fun replaceSource(documentationId: String, chunks: List<IndexedChunk>)

    /** Remove do índice tudo o que veio daquela fonte. */
    fun removeSource(documentationId: String)

    /**
     * Os trechos que respondem à consulta, do mais forte para o menos.
     *
     * @param query o texto procurado, como o cliente o escreveu.
     * @param vector a consulta em forma vetorial, ou nulo quando não há modelo — a busca continua
     *   funcionando por palavra.
     * @param maxResults quantos trechos devolver no máximo.
     */
    fun search(query: String, vector: FloatArray?, maxResults: Int): List<DocumentHit>

    /** Quantos pedaços estão indexados agora. */
    fun size(): Int
}
