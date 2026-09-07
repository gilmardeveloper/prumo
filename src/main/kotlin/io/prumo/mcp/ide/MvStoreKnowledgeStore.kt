package io.prumo.mcp.ide

import io.prumo.mcp.knowledge.KnowledgeRecord
import io.prumo.mcp.knowledge.KnowledgeStore
import io.prumo.mcp.knowledge.KnowledgeStoreException
import io.prumo.mcp.knowledge.UnsupportedSchemaException
import io.prumo.mcp.knowledge.supportsSchema
import io.prumo.mcp.storage.LocalStorageProvider
import kotlinx.serialization.json.Json
import org.h2.mvstore.MVStore
import org.h2.mvstore.tx.Transaction
import org.h2.mvstore.tx.TransactionStore
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Armazenamento do conhecimento destilado sobre o MVStore do H2, que vem com a IDE.
 *
 * Cada escrita roda numa transação: duas chamadas simultâneas ao mesmo registro não se sobrepõem em
 * silêncio, que é o modo de falha do restante do armazenamento do produto. O arquivo é aberto uma
 * vez por workspace e mantido aberto — o MVStore trava o arquivo enquanto está aberto, então
 * abrir e fechar a cada chamada transformaria concorrência em erro de arquivo em uso.
 */
class MvStoreKnowledgeStore(
    private val storage: LocalStorageProvider,
) : KnowledgeStore, AutoCloseable {

    private val stores = ConcurrentHashMap<String, TransactionStore>()

    override fun put(workspaceId: String, record: KnowledgeRecord) {
        inTransaction(workspaceId) { transaction ->
            transaction.openMap<String, String>(RECORDS).put(record.id, json.encodeToString(record))
        }
    }

    override fun replaceFromSource(workspaceId: String, sourceId: String, records: List<KnowledgeRecord>) {
        inTransaction(workspaceId) { transaction ->
            val map = transaction.openMap<String, String>(RECORDS)
            map.entries
                .filter { decode(it.value).provenance.sourceId == sourceId }
                .forEach { map.remove(it.key) }
            records.forEach { map.put(it.id, json.encodeToString(it)) }
        }
    }

    override fun get(workspaceId: String, id: String): KnowledgeRecord? =
        inTransaction(workspaceId) { transaction ->
            transaction.openMap<String, String>(RECORDS)[id]?.let(::decode)
        }

    override fun remove(workspaceId: String, id: String): Boolean =
        inTransaction(workspaceId) { transaction ->
            transaction.openMap<String, String>(RECORDS).remove(id) != null
        }

    override fun list(workspaceId: String): List<KnowledgeRecord> = all(workspaceId)

    override fun byTag(workspaceId: String, tag: String): List<KnowledgeRecord> =
        all(workspaceId).filter { record -> record.tags.any { it.equals(tag, ignoreCase = true) } }

    override fun bySource(workspaceId: String, sourceId: String): List<KnowledgeRecord> =
        all(workspaceId).filter { it.provenance.sourceId == sourceId }

    override fun close() {
        stores.values.forEach { runCatching { it.close() } }
        stores.clear()
    }

    /**
     * Os registros do workspace, em ordem de identificador.
     *
     * O recorte por etiqueta e por fonte varre esta lista em vez de manter índice próprio: índice
     * paralelo é mais uma coisa que pode divergir do dado, e a base de um workspace é da ordem de
     * centenas de registros, não de milhões.
     */
    private fun all(workspaceId: String): List<KnowledgeRecord> =
        // O mapa do MVStore é ordenado por chave, então a listagem já sai em ordem de identificador.
        inTransaction(workspaceId) { transaction ->
            transaction.openMap<String, String>(RECORDS)
                .entries
                .map { decode(it.value) }
        }

    private fun decode(payload: String): KnowledgeRecord {
        val record = try {
            json.decodeFromString<KnowledgeRecord>(payload)
        } catch (cause: Exception) {
            throw KnowledgeStoreException("A knowledge record could not be read as JSON.", cause)
        }
        if (!supportsSchema(record.schemaVersion)) {
            throw UnsupportedSchemaException(record.schemaVersion)
        }
        return record
    }

    private fun <T> inTransaction(workspaceId: String, block: (Transaction) -> T): T {
        val transaction = transactionsFor(workspaceId).begin()
        return try {
            block(transaction).also { transaction.commit() }
        } catch (failure: Throwable) {
            // O isolamento do MVStore já impede que a escrita não commitada seja lida por outra
            // transação; o rollback existe para a transação não ficar aberta pendurada no store.
            runCatching { transaction.rollback() }
            throw failure
        }
    }

    private fun transactionsFor(workspaceId: String): TransactionStore =
        stores.computeIfAbsent(workspaceId) {
            val directory = storage.workspaceRoot(workspaceId).resolve(DIRECTORY)
            try {
                Files.createDirectories(directory)
                val store = MVStore.Builder()
                    .fileName(directory.resolve(FILE).toString())
                    .open()
                TransactionStore(store).also { transactions -> transactions.init() }
            } catch (cause: Exception) {
                throw KnowledgeStoreException(
                    "Prumo could not open the knowledge store of workspace '$workspaceId'.",
                    cause,
                )
            }
        }

    private companion object {
        const val DIRECTORY = "knowledge"
        const val FILE = "knowledge.mv.db"
        const val RECORDS = "records"

        val json = Json { encodeDefaults = true }
    }
}
