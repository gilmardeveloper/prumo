package io.prumo.mcp.knowledge

/**
 * O que o workspace tem ao alcance, no instante em que um registro é oferecido.
 *
 * Chega achatado de propósito: a decisão de admitir é lógica pura e testável, e quem resolve as
 * fontes de verdade é a fronteira.
 */
data class AdmissionContext(
    val documentationIds: Set<String>,
    val repositoryIds: Set<String>,
    val existingRecords: Int,
)

/** O veredicto sobre um registro oferecido à base. */
sealed interface Admission {

    data object Accepted : Admission

    /** A recusa carrega o motivo em inglês, porque ela chega ao cliente de IA. */
    data class Rejected(val reason: String) : Admission

    val accepted: Boolean get() = this is Accepted
}

/**
 * Decide se um registro pode entrar na base.
 *
 * Esta é a garantia que substitui o revisor humano. No fluxo dos pacotes, quem lê o texto antes de
 * aceitar é o desenvolvedor na tela de consentimento; aqui a IA grava sozinha, e o que se pode
 * afirmar de forma determinística não é que o conteúdo é bom — é que ele **deriva de uma fonte que
 * já estava ao alcance do cliente**, e que cabe nos limites da casa.
 */
object KnowledgeAdmission {

    const val MAX_BODY_LENGTH = 40_000
    const val MAX_TITLE_LENGTH = 200
    const val MAX_TAGS = 12
    const val MAX_TAG_LENGTH = 40
    const val MAX_RECORDS_PER_WORKSPACE = 2_000

    private val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun evaluate(record: KnowledgeRecord, context: AdmissionContext): Admission {
        if (!supportsSchema(record.schemaVersion)) {
            return reject("this Prumo writes schema version ${KnowledgeRecord.CURRENT_SCHEMA_VERSION}")
        }
        if (!record.id.matches(IDENTIFIER)) {
            return reject("the id must be 1 to 64 characters of letters, digits, hyphen or underscore")
        }
        if (record.title.isBlank()) {
            return reject("the title is empty")
        }
        if (record.title.length > MAX_TITLE_LENGTH) {
            return reject("the title is longer than $MAX_TITLE_LENGTH characters")
        }
        if (record.body.isBlank()) {
            return reject("the body is empty: a record with no content is invisible to every search")
        }
        if (record.body.length > MAX_BODY_LENGTH) {
            return reject("the body is longer than $MAX_BODY_LENGTH characters: distil it into smaller records")
        }
        if (record.tags.size > MAX_TAGS) {
            return reject("more than $MAX_TAGS tags")
        }
        record.tags.firstOrNull { it.isBlank() || it.length > MAX_TAG_LENGTH }?.let {
            return reject("a tag is empty or longer than $MAX_TAG_LENGTH characters")
        }
        if (context.existingRecords >= MAX_RECORDS_PER_WORKSPACE) {
            return reject("this workspace already holds $MAX_RECORDS_PER_WORKSPACE records: remove some first")
        }
        return admitProvenance(record.provenance, context)
    }

    /**
     * A regra que sustenta a escrita autônoma: só entra o que aponta para uma fonte deste workspace.
     *
     * Um registro sem essa âncora não seria conhecimento derivado — seria conteúdo trazido de fora,
     * que é o que os pacotes já cobrem, e que exige o consentimento do desenvolvedor.
     */
    private fun admitProvenance(provenance: Provenance, context: AdmissionContext): Admission {
        if (provenance.sourceId.isBlank()) {
            return reject("the provenance has no source id")
        }
        val known = when (provenance.sourceKind) {
            SourceKind.DOCUMENTATION -> context.documentationIds
            SourceKind.REPOSITORY -> context.repositoryIds
        }
        if (provenance.sourceId !in known) {
            return reject(
                "source '${provenance.sourceId}' is not ${provenance.sourceKind.name.lowercase()} " +
                    "of this workspace: knowledge can only be derived from what is already in reach",
            )
        }
        if (provenance.stamp.sha256.isBlank()) {
            return reject("the provenance has no source stamp, so freshness could never be answered")
        }
        val first = provenance.firstLine
        val last = provenance.lastLine
        if ((first != null && first < 1) || (last != null && last < 1)) {
            return reject("line numbers start at 1: a range below that cites nothing")
        }
        if (first != null && last != null && first > last) {
            return reject("the line range ends before it starts")
        }
        return Admission.Accepted
    }

    private fun reject(reason: String) = Admission.Rejected("Prumo did not store this record: $reason.")
}
