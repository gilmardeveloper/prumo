package io.prumo.mcp.knowledge

import kotlinx.serialization.Serializable

/**
 * Um trecho de conhecimento destilado por um cliente de IA a partir de uma fonte do workspace.
 *
 * O registro nunca substitui a fonte: ele a cita. Por isso a procedência é obrigatória, e por isso
 * nenhuma leitura devolve o corpo sem o veredicto de frescor ao lado.
 */
@Serializable
data class KnowledgeRecord(
    /** Formato em que este registro foi gravado. Versão desconhecida é recusada na leitura. */
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val provenance: Provenance,
    /** Qual cliente gravou, na forma estável de `ClientIdentity.label`. */
    val author: String,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        /**
         * O formato corrente.
         *
         * Sobe quando um campo muda de significado ou deixa de ser preenchido do mesmo jeito. Como a
         * base é derivada e descartável, não há migração: registro de formato desconhecido é
         * recusado e redestilado.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Se este produto sabe ler um registro gravado naquele formato.
 *
 * Estrito de propósito, ao contrário do armazenamento do workspace, que ignora campo desconhecido e
 * o apaga na regravação seguinte. Aqui a base é reconstruível a partir das fontes, então recusar é
 * mais barato que degradar em silêncio — e formato **mais novo** que o corrente também é recusado,
 * porque uma versão anterior do plugin não tem como saber o que ela não conhece.
 */
fun supportsSchema(version: Int): Boolean = version == KnowledgeRecord.CURRENT_SCHEMA_VERSION

/** Lançada quando um registro no disco está num formato que este produto não sabe ler. */
class UnsupportedSchemaException(val version: Int) : RuntimeException(
    "This knowledge record was written in schema version $version, and this version of Prumo reads " +
        "version ${KnowledgeRecord.CURRENT_SCHEMA_VERSION}. Distil it again from the source.",
)
