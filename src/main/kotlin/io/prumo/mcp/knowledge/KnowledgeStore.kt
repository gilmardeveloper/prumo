package io.prumo.mcp.knowledge

/**
 * Onde o conhecimento destilado é guardado.
 *
 * A interface não menciona o motor de propósito: o armazenamento corrente é um módulo da IDE que não
 * é API sancionada, e trocá-lo não pode obrigar a reescrever o domínio.
 *
 * Toda operação é endereçada por workspace. Não existe consulta que, partindo de um workspace,
 * alcance o conhecimento de outro.
 */
interface KnowledgeStore {

    /** Grava ou substitui um registro. */
    fun put(workspaceId: String, record: KnowledgeRecord)

    /**
     * Substitui, de uma vez, tudo o que havia sido destilado de uma fonte.
     *
     * É o que uma redestilação faz quando a fonte mudou: o que estava lá sai, o que veio entra. Ou
     * os dois acontecem, ou nenhum — meio caminho deixaria a base descrevendo uma fonte que já não
     * existe naquele estado.
     */
    fun replaceFromSource(workspaceId: String, sourceId: String, records: List<KnowledgeRecord>)

    /**
     * Um registro pelo identificador.
     *
     * @throws UnsupportedSchemaException quando o registro foi gravado num formato desconhecido.
     */
    fun get(workspaceId: String, id: String): KnowledgeRecord?

    /** Remove um registro. Devolve falso quando ele não existia. */
    fun remove(workspaceId: String, id: String): Boolean

    /** Todos os registros do workspace, em ordem estável de identificador. */
    fun list(workspaceId: String): List<KnowledgeRecord>

    /** Os registros marcados com a etiqueta, comparada sem depender de maiúsculas. */
    fun byTag(workspaceId: String, tag: String): List<KnowledgeRecord>

    /** Os registros destilados de uma fonte — o que responde "o que eu já sei sobre este arquivo?". */
    fun bySource(workspaceId: String, sourceId: String): List<KnowledgeRecord>
}

/** Lançada quando o armazenamento não pôde ser lido ou escrito. */
class KnowledgeStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
