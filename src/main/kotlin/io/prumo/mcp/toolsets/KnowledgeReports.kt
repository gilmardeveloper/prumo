package io.prumo.mcp.toolsets

import io.prumo.mcp.knowledge.Freshness
import io.prumo.mcp.knowledge.KnowledgeRecord
import kotlinx.serialization.Serializable

@Serializable
data class ProvenanceResponse(
    val sourceKind: String,
    val sourceId: String,
    val path: String? = null,
    val firstLine: Int? = null,
    val lastLine: Int? = null,
)

@Serializable
data class KnowledgeRecordResponse(
    val knowledgeId: String,
    val title: String,
    val tags: List<String>,
    /** De onde veio. Nunca ausente: registro sem procedência não entra na base. */
    val provenance: ProvenanceResponse,
    /** `FRESH`, `STALE` ou `ORPHAN`, recalculado agora contra a fonte. */
    val freshness: String,
    /** Qual cliente de IA gravou. */
    val author: String,
    val updatedAt: String,
    /** O texto destilado. Ausente na busca, presente na leitura. */
    val body: String? = null,
    val truncated: Boolean = false,
    /**
     * O quanto este registro responde à consulta de texto.
     *
     * Ausente quando a busca não teve texto: sem consulta não há o que pontuar. Só é comparável
     * entre os resultados da mesma chamada — não é nota, é posição relativa.
     */
    val score: Float? = null,
)

@Serializable
data class KnowledgeRecallResponse(
    val workspaceId: String,
    val storedCount: Int,
    val matchCount: Int,
    /**
     * Quantos registros da base têm a fonte fora de alcance, e por isso não são buscáveis nem
     * legíveis.
     *
     * Conta a base inteira, como [storedCount], e não o recorte da consulta: um número que variasse
     * com o texto procurado diria quais palavras existem dentro do que o desenvolvedor excluiu.
     */
    val outOfReachCount: Int = 0,
    val byFreshness: Map<String, Int>,
    val results: List<KnowledgeRecordResponse>,
    val truncated: Boolean,
    /**
     * Os termos em que a consulta de texto foi reduzida, presentes só quando ela não casou nada.
     *
     * Lista vazia diz que a consulta inteira era palavra comum e não sobrou nada para procurar;
     * lista preenchida diz que se procurou por aqueles termos e nenhum registro os tem. As duas
     * situações pedem correções diferentes, e sem elas o cliente só vê resultado vazio.
     */
    val searchedTerms: List<String>? = null,
)

@Serializable
data class KnowledgeForgetResponse(
    val knowledgeId: String,
    /** Verdadeiro quando havia um registro e ele saiu. */
    val removed: Boolean,
    val message: String? = null,
)

@Serializable
data class KnowledgeWriteResponse(
    val knowledgeId: String,
    val stored: Boolean,
    /** Preenchido só quando o registro foi recusado, com o motivo e o que corrigir. */
    val message: String? = null,
)

/**
 * Um registro que atendeu à busca: o que ele é, o quanto ele responde e como está a fonte dele.
 *
 * @property score ausente quando a busca não teve texto.
 */
data class KnowledgeMatch(
    val record: KnowledgeRecord,
    val freshness: Freshness,
    val score: Float? = null,
)

/**
 * Respostas da base de conhecimento.
 *
 * Nenhuma delas devolve conteúdo sem a procedência e o frescor ao lado: o destilado cita a fonte,
 * não a substitui, e é o veredicto que permite ao cliente decidir se volta a ela.
 */
object KnowledgeReports {

    const val MAX_RESULTS = 50
    const val MAX_BODY_CHARS = 8_000

    fun search(
        workspaceId: String,
        stored: Int,
        matches: List<KnowledgeMatch>,
        maxResults: Int,
        searchedTerms: List<String>? = null,
        outOfReach: Int = 0,
    ): KnowledgeRecallResponse {
        val window = matches.take(maxResults.coerceIn(1, MAX_RESULTS))
        return KnowledgeRecallResponse(
            workspaceId = workspaceId,
            storedCount = stored,
            matchCount = matches.size,
            outOfReachCount = outOfReach,
            byFreshness = matches.groupingBy { it.freshness.name }.eachCount().toSortedMap(),
            results = window.map { summary(it.record, it.freshness).copy(score = it.score) },
            truncated = window.size < matches.size,
            searchedTerms = searchedTerms?.takeIf { matches.isEmpty() },
        )
    }

    /** Um resultado de busca: tudo menos o corpo, que se busca por identificador. */
    fun summary(record: KnowledgeRecord, freshness: Freshness): KnowledgeRecordResponse =
        KnowledgeRecordResponse(
            knowledgeId = record.id,
            title = record.title,
            tags = record.tags,
            provenance = provenance(record),
            freshness = freshness.name,
            author = record.author,
            updatedAt = record.updatedAt,
        )

    /** O registro inteiro, com o corpo recortado quando ele passa do teto de leitura. */
    fun detail(record: KnowledgeRecord, freshness: Freshness): KnowledgeRecordResponse =
        summary(record, freshness).copy(
            body = record.body.take(MAX_BODY_CHARS),
            truncated = record.body.length > MAX_BODY_CHARS,
        )

    private fun provenance(record: KnowledgeRecord) = ProvenanceResponse(
        sourceKind = record.provenance.sourceKind.name,
        sourceId = record.provenance.sourceId,
        path = record.provenance.path,
        firstLine = record.provenance.firstLine,
        lastLine = record.provenance.lastLine,
    )
}
