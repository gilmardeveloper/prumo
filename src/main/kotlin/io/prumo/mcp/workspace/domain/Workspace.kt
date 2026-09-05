package io.prumo.mcp.workspace.domain

import io.prumo.mcp.policy.WorkspacePolicies
import kotlinx.serialization.Serializable

/**
 * Tipo do workspace.
 *
 * Serve a configuração, apresentação e políticas padrão. Não existe comportamento que diverge só
 * porque o tipo é um ou outro: regra artificial por tipo é defeito de projeto, não recurso.
 */
@Serializable
enum class WorkspaceType {
    STANDALONE,
    LEGACY_MAINTENANCE,
    GREENFIELD,
    MODERNIZATION,
    DISTRIBUTED_SYSTEM,
    CUSTOM,
}

/** Papel de um repositório dentro do workspace. */
@Serializable
enum class RepositoryRole {
    PRIMARY,
    TARGET,
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
 * `localPath` existe para o plugin resolver arquivos; jamais é entregue a um cliente MCP quando um
 * identificador resolve o mesmo problema.
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
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid repository id '$id'." }
        require(name.isNotBlank()) { "Repository name must not be blank." }
        require(localPath.isNotBlank()) { "Repository local path must not be blank." }
    }

    val writable: Boolean get() = accessMode == AccessMode.READ_WRITE
}

/**
 * Fronteira lógica e de segurança dentro da qual um cliente de IA pode trabalhar.
 *
 * Um workspace nunca referencia outro: a ausência dessa relação é o que sustenta o isolamento.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val type: WorkspaceType,
    val repositories: List<RepositoryBinding> = emptyList(),
    val policies: WorkspacePolicies = WorkspacePolicies.DENY_ALL,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid workspace id '$id'." }
        require(name.isNotBlank()) { "Workspace name must not be blank." }
        val duplicated = repositories.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicated repository ids in workspace '$id': $duplicated." }
    }

    fun repository(repositoryId: String): RepositoryBinding? =
        repositories.firstOrNull { it.id == repositoryId }

    fun repositoriesWith(role: RepositoryRole): List<RepositoryBinding> =
        repositories.filter { it.role == role }
}

/** Identificadores viram nome de diretório e chave de resolução; travessia de caminho é barrada aqui. */
internal val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
