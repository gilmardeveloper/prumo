package io.prumo.mcp.ui

import io.prumo.mcp.documentation.DocumentAuthority
import io.prumo.mcp.documentation.DocumentationKind
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.WorkspaceType

/**
 * Chave de tradução de cada enum que aparece na interface do Prumo.
 *
 * Fica na camada de interface, e não dentro dos enums: `RepositoryRole`, `AccessMode` e os demais
 * são domínio persistido em disco e publicado pela superfície MCP, onde o valor continua sendo o
 * identificador em inglês. O `when` é exaustivo de propósito — valor novo no enum não compila até
 * ganhar rótulo.
 *
 * `SslMode` não entra aqui: `prefer`, `verify-ca` e os demais são o vocabulário do próprio
 * PostgreSQL, e traduzir atrapalha quem confere contra a documentação do banco.
 */
val WorkspaceType.labelKey: String
    get() = when (this) {
        WorkspaceType.STANDALONE -> "workspace.type.standalone"
        WorkspaceType.LEGACY_MAINTENANCE -> "workspace.type.legacyMaintenance"
        WorkspaceType.GREENFIELD -> "workspace.type.greenfield"
        WorkspaceType.MODERNIZATION -> "workspace.type.modernization"
        WorkspaceType.DISTRIBUTED_SYSTEM -> "workspace.type.distributedSystem"
        WorkspaceType.CUSTOM -> "workspace.type.custom"
    }

val RepositoryRole.labelKey: String
    get() = when (this) {
        RepositoryRole.PRIMARY -> "repository.role.primary"
        RepositoryRole.REFERENCE -> "repository.role.reference"
        RepositoryRole.LEGACY_REFERENCE -> "repository.role.legacyReference"
        RepositoryRole.RELATED_COMPONENT -> "repository.role.relatedComponent"
    }

val AccessMode.labelKey: String
    get() = when (this) {
        AccessMode.READ_ONLY -> "access.mode.readOnly"
        AccessMode.READ_WRITE -> "access.mode.readWrite"
    }

val DocumentationKind.labelKey: String
    get() = when (this) {
        DocumentationKind.FILE -> "documentation.kind.file"
        DocumentationKind.DIRECTORY -> "documentation.kind.directory"
    }

val DocumentAuthority.labelKey: String
    get() = when (this) {
        DocumentAuthority.OFFICIAL -> "documentation.authority.official"
        DocumentAuthority.REFERENCE -> "documentation.authority.reference"
        DocumentAuthority.GENERATED -> "documentation.authority.generated"
    }
