package io.prumo.mcp.ui

import io.prumo.mcp.workspace.application.WorkspaceResolution

/**
 * O que o painel do Prumo mostra.
 *
 * Modelado como dado puro, sem Swing, para que a garantia mais importante da tela seja testável:
 * o painel do workspace corrente jamais exibe informação de outro workspace, nem a existência dele.
 */
sealed interface WorkspaceViewModel {

    data class NotConfigured(val projectName: String) : WorkspaceViewModel

    data class Ambiguous(val projectName: String, val workspaceCount: Int) : WorkspaceViewModel

    data class Configured(
        val workspaceId: String,
        val workspaceName: String,
        val workspaceType: String,
        val currentRepositoryName: String,
        val repositories: List<RepositoryRow>,
        val policies: List<PolicyRow>,
        /** Quantos packs propostos por cliente MCP esperam decisão do desenvolvedor. */
        val pendingPacks: Int = 0,
    ) : WorkspaceViewModel

    data class RepositoryRow(
        val name: String,
        val role: String,
        val accessMode: String,
        val current: Boolean,
    )

    data class PolicyRow(val label: String, val allowed: Boolean)

    companion object {

        fun from(resolution: WorkspaceResolution, pendingPacks: Int = 0): WorkspaceViewModel = when (resolution) {
            is WorkspaceResolution.NotConfigured -> NotConfigured(resolution.projectName)

            // A tela diz que há ambiguidade e quantos vínculos existem, mas não quais: nomear os
            // outros workspaces já seria contar sobre eles.
            is WorkspaceResolution.Ambiguous -> Ambiguous(
                resolution.projectName,
                resolution.workspaceIds.size,
            )

            is WorkspaceResolution.Resolved -> {
                val context = resolution.context
                Configured(
                    workspaceId = context.workspace.id,
                    workspaceName = context.workspace.name,
                    workspaceType = context.workspace.type.name,
                    currentRepositoryName = context.currentRepository.name,
                    repositories = context.workspace.repositories.map {
                        RepositoryRow(
                            name = it.name,
                            role = it.role.name,
                            accessMode = it.accessMode.name,
                            current = it.id == context.currentRepository.id,
                        )
                    },
                    pendingPacks = pendingPacks,
                    policies = listOf(
                        PolicyRow("Reference writes", context.policies.referenceWrite),
                        PolicyRow("Database writes", context.policies.databaseWrite),
                        PolicyRow("External path access", context.policies.externalPathAccess),
                        PolicyRow("Process execution", context.policies.processExecution),
                        PolicyRow("Git writes", context.policies.gitWrite),
                    ),
                )
            }
        }
    }
}
