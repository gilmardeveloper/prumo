package io.prumo.mcp.workspace.domain

import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.policy.WorkspacePolicies
import kotlinx.serialization.Serializable

/** Tipo do workspace, usado na configuração, na apresentação e nas políticas padrão. */
@Serializable
enum class WorkspaceType {
    STANDALONE,
    LEGACY_MAINTENANCE,
    GREENFIELD,
    MODERNIZATION,
    DISTRIBUTED_SYSTEM,
    CUSTOM,
}

/**
 * Papel de um repositório dentro do workspace.
 *
 * Descreve o repositório para o cliente de IA e não concede acesso: quem concede é o [AccessMode].
 * Valor gravado que não exista mais aqui é resolvido por [RepositoryRoleSerializer].
 */
@Serializable(with = RepositoryRoleSerializer::class)
enum class RepositoryRole {
    /** O que está sendo construído, incluindo o projeto aberto na IDE. */
    PRIMARY,
    REFERENCE,
    LEGACY_REFERENCE,
    RELATED_COMPONENT,
}

@Serializable
enum class AccessMode {
    READ_ONLY,
    READ_WRITE,
}

/**
 * Vínculo entre um workspace e um repositório local.
 *
 * `localPath` existe para o plugin resolver arquivos e não é entregue a um cliente MCP.
 */
@Serializable
data class RepositoryBinding(
    val id: String,
    val name: String,
    val localPath: String,
    val gitRemote: String? = null,
    val role: RepositoryRole,
    val accessMode: AccessMode,
    val branchPolicy: String? = null,
    val fingerprint: String? = null,
    /** O que este repositorio e, escrito pelo desenvolvedor e entregue ao cliente de IA. */
    val description: String? = null,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid repository id '$id'." }
        require(name.isNotBlank()) { "Repository name must not be blank." }
        require(localPath.isNotBlank()) { "Repository local path must not be blank." }
        require((description?.length ?: 0) <= MAX_DESCRIPTION_LENGTH) {
            "Repository description must not exceed $MAX_DESCRIPTION_LENGTH characters."
        }
    }

    val writable: Boolean get() = accessMode == AccessMode.READ_WRITE

    companion object {
        const val MAX_DESCRIPTION_LENGTH = 500
    }
}

/**
 * Fronteira lógica e de segurança dentro da qual um cliente de IA pode trabalhar.
 *
 * Um workspace nunca referencia outro.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val type: WorkspaceType,
    val repositories: List<RepositoryBinding> = emptyList(),
    val documentation: List<DocumentationSource> = emptyList(),
    val datasources: List<DataSourceProfile> = emptyList(),
    val policies: WorkspacePolicies = WorkspacePolicies.DENY_ALL,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid workspace id '$id'." }
        require(name.isNotBlank()) { "Workspace name must not be blank." }
        val duplicated = repositories.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicated repository ids in workspace '$id': $duplicated." }
        val duplicatedDocs = documentation.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicatedDocs.isEmpty()) { "Duplicated documentation ids in workspace '$id': $duplicatedDocs." }
        val duplicatedSources = datasources.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicatedSources.isEmpty()) { "Duplicated datasource ids in workspace '$id': $duplicatedSources." }
    }

    fun repository(repositoryId: String): RepositoryBinding? =
        repositories.firstOrNull { it.id == repositoryId }

    fun repositoriesWith(role: RepositoryRole): List<RepositoryBinding> =
        repositories.filter { it.role == role }

    fun datasource(datasourceId: String): DataSourceProfile? =
        datasources.firstOrNull { it.id == datasourceId }
}

/** Identificadores viram nome de diretório e chave de resolução; travessia de caminho é barrada aqui. */
internal val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
