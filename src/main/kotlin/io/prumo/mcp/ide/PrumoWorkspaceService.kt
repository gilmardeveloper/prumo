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
import io.prumo.mcp.documentation.signatureOfSource
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

    private val indexedSources = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Garante que a fonte está indexada, e reindexa quando ela muda.
     *
     * A marca guardada é a assinatura da fonte — tamanho e data de cada arquivo legível dentro dela.
     * Sem isso, documento corrigido continuaria respondendo pelo texto antigo até a IDE reiniciar, e
     * fonte que ainda não existia nunca seria tentada de novo.
     */
    fun ensureIndexed(workspaceId: String, source: DocumentationSource) {
        val chave = "$workspaceId|${source.id}"
        val assinatura = signatureOfSource(source)
        if (indexedSources[chave] == assinatura) {
            return
        }
        val chunks = chunksOfSource(source, DocumentationReader::extractFile)
        documentIndex.replaceSource(workspaceId, source.id, chunks)
        indexedSources[chave] = assinatura
    }

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
