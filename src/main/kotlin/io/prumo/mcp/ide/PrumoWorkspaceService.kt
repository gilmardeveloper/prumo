package io.prumo.mcp.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.knowledge.KnowledgeSearch
import io.prumo.mcp.knowledge.KnowledgeStore
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.documentation.DocumentIndex
import io.prumo.mcp.documentation.DocumentationReader
import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.documentation.chunksOfSource
import io.prumo.mcp.documentation.IndexedChunk
import io.prumo.mcp.documentation.TextEmbedder
import io.prumo.mcp.documentation.TextRole
import io.prumo.mcp.documentation.signatureOfSource
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.platform.SystemEnvironmentProbe
import io.prumo.mcp.credential.PasswordSafeCredentialProvider
import io.prumo.mcp.repository.GitRepositoryProbe
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.storage.LocalStorageProvider
import io.prumo.mcp.workspace.application.CurrentWorkspaceContextService
import io.prumo.mcp.workspace.application.ProjectDescriptor
import io.prumo.mcp.workspace.application.WorkspaceResolution
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import java.nio.file.Path

/**
 * Ponte entre a IDE e o núcleo do Prumo.
 *
 * Único ponto em que um `Project` da plataforma vira o descritor neutro que o núcleo entende.
 */
@Service(Service.Level.APP)
class PrumoWorkspaceService {

    private val log = com.intellij.openapi.diagnostic.logger<PrumoWorkspaceService>()

    val storage: LocalStorageProvider = FileSystemStorageProvider.forCurrentSystem()
    val store: WorkspaceStore = WorkspaceStore(storage)
    val audit: AuditLog = AuditLog(storage)

    /** A base de conhecimento destilado, uma por workspace, aberta sob demanda. */
    val knowledge: KnowledgeStore = MvStoreKnowledgeStore(storage)

    /** A recuperação por relevância sobre essa base. Não guarda estado entre chamadas. */
    val knowledgeSearch: KnowledgeSearch = LuceneKnowledgeSearch()

    /**
     * O índice dos trechos de documentação, um por instalação.
     *
     * Diferente do índice da memória, este guarda estado entre chamadas: montá-lo custa extrair
     * documento grande, e refazê-lo a cada busca devolveria o custo que o cache existe para evitar.
     */
    val documentIndex: DocumentIndex = LuceneDocumentIndex()

    /** A guarda do modelo local, e o descritor que o produto sabe buscar. */
    private val directories = PrumoDirectories.resolve(SystemEnvironmentProbe)

    val embeddingModels: EmbeddingModelStore = EmbeddingModelStore(directories)

    private val indexedSources = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Quantos pedaços vão ao modelo de uma vez. */
    private val EMBEDDING_BATCH = 16

    private object EmbeddingModel {
        /**
         * O modelo que o Prumo sabe buscar.
         *
         * Multilíngue e quantizado: 384 dimensões, cerca de 130 MB somando o tokenizador. Os resumos
         * são os do artefato publicado, e é por eles que o download é conferido — arquivo vindo da
         * rede sem conferência é código de origem desconhecida na máquina de quem programa.
         */
        val DESCRIPTOR = ModelDescriptor(
            id = "multilingual-e5-small-int8",
            uri = java.net.URI(
                "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx",
            ),
            sha256 = "sha256:f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
            sizeBytes = 118_308_185,
            tokenizerUri = java.net.URI(
                "https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/tokenizer.json",
            ),
            tokenizerSha256 = "sha256:0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
            tokenizerSizeBytes = 17_082_730,
        )
    }

    private var embedderState: Pair<java.nio.file.Path, TextEmbedder>? = null

    /**
     * O embutidor local, quando há modelo instalado.
     *
     * Devolve nulo — e não um vetor de mentira — quando o modelo não está na máquina ou o motor não
     * carregou. É essa ausência que a busca declara ao cliente, e é o que mantém a resposta honesta:
     * sem modelo, não achar significa não ter a palavra, e não significa não ter o assunto.
     */
    @Synchronized
    fun embedder(): TextEmbedder? {
        if (!EmbeddingRuntime.available) {
            return null
        }
        val estado = embeddingModels.state(EmbeddingModel.DESCRIPTOR)
        val modelo = estado.path
        val tokenizador = estado.tokenizerPath
        if (!estado.installed || modelo == null || tokenizador == null) {
            fecharEmbutidor()
            return null
        }
        embedderState?.let { (caminho, embutidor) -> if (caminho == modelo) return embutidor }
        fecharEmbutidor()
        return runCatching { OnnxTextEmbedder(modelo, tokenizador, directories.cache.resolve("djl")) }
            .onSuccess { embedderState = modelo to it }
            .onFailure { log.warn("O embutidor local nao pode ser aberto; a busca segue por palavra.", it) }
            .getOrNull()
    }

    /** Descarta o embutidor aberto, para o índice voltar a ser montado sem vetor. */
    @Synchronized
    fun forgetEmbedder() = fecharEmbutidor()

    /** O modelo que o produto sabe buscar, para a janela dizer o tamanho antes de baixar. */
    val embeddingModelDescriptor: ModelDescriptor get() = EmbeddingModel.DESCRIPTOR

    /**
     * Esquece o que já foi indexado, para o acervo ser montado de novo.
     *
     * Chamado quando o modelo entra ou sai: os trechos precisam ganhar ou perder o vetor, e remendar
     * o índice pela metade deixaria parte do acervo achável por sentido e parte não.
     */
    fun forgetIndexing() = indexedSources.clear()

    private fun fecharEmbutidor() {
        (embedderState?.second as? AutoCloseable)?.let { runCatching { it.close() } }
        embedderState = null
    }

    /**
     * Garante que a fonte está indexada, e reindexa quando ela muda.
     *
     * A marca guardada é a assinatura da fonte — tamanho e data de cada arquivo legível dentro dela.
     * Sem isso, documento corrigido continuaria respondendo pelo texto antigo até a IDE reiniciar, e
     * fonte que ainda não existia nunca seria tentada de novo.
     */
    fun ensureIndexed(workspaceId: String, source: DocumentationSource): Boolean {
        val chave = "$workspaceId|${source.id}"
        val assinatura = signatureOfSource(source)
        if (indexedSources[chave] == assinatura) {
            return false
        }
        val chunks = withVectors(chunksOfSource(source, DocumentationReader::extractFile))
        documentIndex.replaceSource(workspaceId, source.id, chunks)
        indexedSources[chave] = assinatura
        return true
    }

    /**
     * Os mesmos pedaços, agora com vetor, quando há modelo instalado.
     *
     * Sem modelo eles vão como estão, e o índice continua achando por palavra. Falha na vetorização
     * não derruba a indexação: o acervo entra sem vetor e a busca diz que a parte semântica não está
     * disponível — pior que achar menos é não achar nada.
     */
    private fun withVectors(chunks: List<IndexedChunk>): List<IndexedChunk> {
        val embutidor = embedder() ?: return chunks
        return runCatching {
            chunks.chunked(EMBEDDING_BATCH).flatMap { lote ->
                val vetores = embutidor.embed(lote.map { it.chunk.text }, TextRole.PASSAGE)
                lote.mapIndexed { indice, pedaco -> pedaco.copy(vector = vetores[indice]) }
            }
        }.getOrElse { falha ->
            log.warn("A vetorizacao falhou; o acervo foi indexado sem vetor.", falha)
            chunks
        }
    }

    /** O vetor da consulta, ou nulo quando não há modelo — a busca segue por palavra. */
    fun embedQuery(query: String): FloatArray? =
        embedder()?.let { embutidor -> runCatching { embutidor.embed(listOf(query), TextRole.QUERY).single() }.getOrNull() }

    /** Se a fonte já está indexada e atualizada, sem indexar nada. */
    fun isIndexed(workspaceId: String, source: DocumentationSource): Boolean =
        indexedSources["$workspaceId|${source.id}"] == signatureOfSource(source)

    val credentials: CredentialProvider = PasswordSafeCredentialProvider()
    private val contextService = CurrentWorkspaceContextService(store)

    fun describe(project: Project): ProjectDescriptor {
        val basePath = project.basePath?.let(Path::of)
        val repositoryRoot = basePath?.let(GitRepositoryProbe::findRepositoryRoot)
        return ProjectDescriptor(
            name = project.name,
            basePath = repositoryRoot ?: basePath,
            gitRemote = repositoryRoot?.let(GitRepositoryProbe::readOriginRemote),
        )
    }

    fun resolve(project: Project): WorkspaceResolution = contextService.resolve(describe(project))

    fun require(project: Project) = contextService.require(describe(project))

    companion object {
        fun getInstance(): PrumoWorkspaceService = service()
    }
}
