package io.prumo.mcp.workspace.infrastructure

import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.storage.JsonStore
import io.prumo.mcp.storage.LocalStorageProvider
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Persistência dos workspaces, um diretório por workspace.
 *
 * Toda leitura é endereçada por identificador. Não existe consulta que, partindo de um workspace,
 * alcance o conteúdo de outro — o isolamento nasce do formato de acesso, não de uma verificação
 * que alguém possa esquecer de chamar.
 */
class WorkspaceStore(
    private val storage: LocalStorageProvider,
    private val json: JsonStore = JsonStore(),
) {

    fun load(workspaceId: String): Workspace? {
        val root = storage.workspaceRoot(workspaceId)
        val descriptor = json.read(root.resolve(WORKSPACE_FILE), serializer<WorkspaceDescriptor>())
            ?: return null
        val repositories = json.read(root.resolve(REPOSITORIES_FILE), serializer<RepositoryList>())
            ?.repositories
            .orEmpty()
        val documentation = json.read(root.resolve(DOCUMENTATION_FILE), serializer<DocumentationList>())
            ?.documentation
            .orEmpty()
        val policies = json.read(root.resolve(POLICIES_FILE), serializer<WorkspacePolicies>())
            ?: WorkspacePolicies.DENY_ALL

        return Workspace(
            id = descriptor.id,
            name = descriptor.name,
            type = descriptor.type,
            repositories = repositories,
            documentation = documentation,
            policies = policies,
            createdAt = descriptor.createdAt,
            updatedAt = descriptor.updatedAt,
        )
    }

    fun save(workspace: Workspace) {
        val root = storage.workspaceRoot(workspace.id)
        json.write(
            root.resolve(WORKSPACE_FILE),
            serializer<WorkspaceDescriptor>(),
            WorkspaceDescriptor(
                id = workspace.id,
                name = workspace.name,
                type = workspace.type,
                createdAt = workspace.createdAt,
                updatedAt = workspace.updatedAt,
            ),
        )
        json.write(
            root.resolve(REPOSITORIES_FILE),
            serializer<RepositoryList>(),
            RepositoryList(workspace.repositories),
        )
        json.write(
            root.resolve(DOCUMENTATION_FILE),
            serializer<DocumentationList>(),
            DocumentationList(workspace.documentation),
        )
        json.write(root.resolve(POLICIES_FILE), serializer<WorkspacePolicies>(), workspace.policies)
    }

    fun delete(workspaceId: String) {
        val root = storage.workspaceRoot(workspaceId)
        if (!Files.exists(root)) {
            return
        }
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    /**
     * Enumera os workspaces conhecidos.
     *
     * Operação administrativa, para a tela de gerenciamento. **Nunca** deve ser exposta por uma
     * ferramenta MCP: conhecer os demais workspaces já é uma quebra de isolamento, mesmo que o
     * conteúdo deles permaneça inacessível.
     */
    fun listForAdministration(): List<WorkspaceSummary> {
        val root = storage.workspacesRoot()
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.list(root).use { paths ->
            paths.filter(Files::isDirectory)
                .map { it.toSummaryOrNull() }
                .toList()
                .filterNotNull()
                .sortedBy { it.name }
        }
    }

    private fun Path.toSummaryOrNull(): WorkspaceSummary? =
        json.read(resolve(WORKSPACE_FILE), serializer<WorkspaceDescriptor>())
            ?.let { WorkspaceSummary(id = it.id, name = it.name, type = it.type) }
            ?.takeIf { it.id == name }

    private companion object {
        const val WORKSPACE_FILE = "workspace.json"
        const val REPOSITORIES_FILE = "repositories.json"
        const val POLICIES_FILE = "policies.json"
        const val DOCUMENTATION_FILE = "documentation.json"
    }
}

data class WorkspaceSummary(
    val id: String,
    val name: String,
    val type: WorkspaceType,
)

@Serializable
private data class WorkspaceDescriptor(
    val id: String,
    val name: String,
    val type: WorkspaceType,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class RepositoryList(
    val repositories: List<RepositoryBinding> = emptyList(),
)

@Serializable
private data class DocumentationList(
    val documentation: List<DocumentationSource> = emptyList(),
)
