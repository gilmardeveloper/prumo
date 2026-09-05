package io.prumo.mcp.pack.application

import io.prumo.mcp.pack.domain.KnowledgeItem
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.repository.PathSecurityValidator
import io.prumo.mcp.storage.JsonStore
import io.prumo.mcp.storage.LocalStorageProvider
import kotlinx.serialization.serializer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** Falha esperada ao alcançar um pack ou o conteúdo dele. */
class PackAccessException(message: String) : IllegalArgumentException(message)

/** Um trecho de conhecimento que casou com a busca, com o suficiente para a LLM decidir se serve. */
data class KnowledgeMatch(
    val packId: String,
    val itemId: String,
    val title: String,
    val tags: List<String>,
    val line: Int,
    val excerpt: String,
    val score: Int,
)

/**
 * Persistência dos Prumo Packs, sempre **dentro do diretório do workspace** e nunca dentro do
 * repositório do usuário (P8).
 *
 * O endereçamento é por `workspaceId` + `packId`, como o resto do produto: não existe consulta que,
 * partindo de um workspace, alcance o pack de outro. O isolamento nasce do formato de acesso.
 */
class PackStore(
    private val storage: LocalStorageProvider,
    private val json: JsonStore = JsonStore(),
) {

    fun list(workspaceId: String): List<PackManifest> {
        val root = packsRoot(workspaceId)
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.list(root).use { paths ->
            paths.filter(Files::isDirectory)
                .map { manifestOrNull(it) }
                .toList()
                .filterNotNull()
                .sortedBy { it.id }
        }
    }

    fun load(workspaceId: String, packId: String): PackManifest? =
        manifestOrNull(packRoot(workspaceId, packId))

    fun save(workspaceId: String, manifest: PackManifest) {
        json.write(
            packRoot(workspaceId, manifest.id).resolve(MANIFEST_FILE),
            serializer<PackManifest>(),
            manifest,
        )
    }

    /** Grava um arquivo de conhecimento dentro do pack. O caminho vem do manifesto, nunca do cliente. */
    fun writeKnowledge(workspaceId: String, packId: String, item: KnowledgeItem, content: String) {
        val file = knowledgeFile(workspaceId, packId, item)
        Files.createDirectories(file.parent)
        Files.writeString(file, content, StandardCharsets.UTF_8)
    }

    fun readKnowledge(workspaceId: String, packId: String, itemId: String): String {
        val manifest = load(workspaceId, packId)
            ?: throw PackAccessException("Pack '$packId' is not installed in this workspace.")
        val item = manifest.knowledge(itemId)
            ?: throw PackAccessException("Pack '$packId' has no knowledge item '$itemId'.")
        val file = knowledgeFile(workspaceId, packId, item)
        if (!Files.isRegularFile(file)) {
            throw PackAccessException("The file of knowledge item '$itemId' is missing from pack '$packId'.")
        }
        return Files.readString(file, StandardCharsets.UTF_8)
    }

    fun remove(workspaceId: String, packId: String) {
        val root = packRoot(workspaceId, packId)
        if (!Files.exists(root)) {
            return
        }
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    /**
     * Busca determinística: texto, título e etiquetas.
     *
     * Não há embedding, vector store nem ranqueamento por modelo — a mesma pergunta devolve sempre
     * o mesmo resultado, e quem interpreta é a LLM. A pontuação é explicável: título vale mais que
     * etiqueta, que vale mais que corpo, e empate se desfaz por identificador para a ordem não
     * depender do sistema de arquivos.
     */
    fun searchKnowledge(
        workspaceId: String,
        query: String,
        packId: String? = null,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<KnowledgeMatch> {
        require(query.isNotBlank()) { "The search query must not be blank." }
        val packs = packId?.let { listOfNotNull(load(workspaceId, it)) } ?: list(workspaceId)

        return packs.flatMap { manifest ->
            manifest.knowledge.mapNotNull { item -> match(workspaceId, manifest.id, item, query) }
        }
            .sortedWith(compareByDescending<KnowledgeMatch> { it.score }.thenBy { it.packId }.thenBy { it.itemId })
            .take(maxResults)
    }

    private fun match(workspaceId: String, packId: String, item: KnowledgeItem, query: String): KnowledgeMatch? {
        val needle = query.trim()
        var score = 0
        if (item.title.default.contains(needle, ignoreCase = true) ||
            item.title.translations.values.any { it.contains(needle, ignoreCase = true) }
        ) {
            score += TITLE_WEIGHT
        }
        if (item.tags.any { it.contains(needle, ignoreCase = true) }) {
            score += TAG_WEIGHT
        }

        val file = knowledgeFile(workspaceId, packId, item)
        var line = 0
        var excerpt = ""
        if (Files.isRegularFile(file)) {
            Files.readAllLines(file, StandardCharsets.UTF_8).forEachIndexed { index, text ->
                if (line == 0 && text.contains(needle, ignoreCase = true)) {
                    line = index + 1
                    excerpt = text.trim().take(EXCERPT_LENGTH)
                    score += BODY_WEIGHT
                }
            }
        }

        if (score == 0) {
            return null
        }
        return KnowledgeMatch(
            packId = packId,
            itemId = item.id,
            title = item.title.default,
            tags = item.tags,
            line = line,
            excerpt = excerpt,
            score = score,
        )
    }

    /**
     * O arquivo de um item, resolvido contra a raiz do pack.
     *
     * Passa pelo mesmo validador das leituras de repositório: um manifesto que traga `..` ou um
     * caminho absoluto não alcança nada fora do próprio pack.
     */
    private fun knowledgeFile(workspaceId: String, packId: String, item: KnowledgeItem): Path =
        PathSecurityValidator.resolve(packRoot(workspaceId, packId), item.file)

    private fun manifestOrNull(root: Path): PackManifest? =
        json.read(root.resolve(MANIFEST_FILE), serializer<PackManifest>())
            ?.takeIf { it.id == root.name }

    private fun packsRoot(workspaceId: String): Path = storage.workspaceRoot(workspaceId).resolve(PACKS_DIRECTORY)

    private fun packRoot(workspaceId: String, packId: String): Path {
        require(packId.matches(SAFE_ID)) { "Invalid pack id '$packId'." }
        return packsRoot(workspaceId).resolve(packId)
    }

    private companion object {
        const val PACKS_DIRECTORY = "packs"
        const val MANIFEST_FILE = "manifest.json"
        const val DEFAULT_MAX_RESULTS = 20
        const val EXCERPT_LENGTH = 200
        const val TITLE_WEIGHT = 3
        const val TAG_WEIGHT = 2
        const val BODY_WEIGHT = 1
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
