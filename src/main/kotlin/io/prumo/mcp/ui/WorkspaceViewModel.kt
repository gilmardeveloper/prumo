package io.prumo.mcp.ui

import io.prumo.mcp.workspace.application.WorkspaceResolution

/** O que o painel do Prumo mostra, como dado puro e sem Swing. */
sealed interface WorkspaceViewModel {

    /** Estado inicial, enquanto o workspace é lido do disco fora da thread de interface. */
    data object Loading : WorkspaceViewModel

    /**
     * A leitura falhou.
     *
     * Carrega **chave**, nunca a mensagem da exceção: ela costuma trazer o caminho do arquivo que
     * falhou, e caminho de disco não vai para a tela. O detalhe vai para o log da IDE.
     */
    data class Failed(val messageKey: String) : WorkspaceViewModel

    data class NotConfigured(val projectName: String) : WorkspaceViewModel

    data class Ambiguous(val projectName: String, val workspaceCount: Int) : WorkspaceViewModel

    data class Configured(
        val workspaceId: String,
        val workspaceName: String,
        val workspaceTypeKey: String,
        val currentRepositoryName: String,
        val repositories: List<RepositoryRow>,
        val policies: List<PolicyRow>,
        /** Quantos packs propostos por cliente MCP esperam decisão do desenvolvedor. */
        val pendingPacks: Int = 0,
        /** Os packs já instalados neste workspace, na ordem em que a tela os mostra. */
        val installedPacks: List<PackRow> = emptyList(),
        /** Os bancos vinculados a este workspace. */
        val dataSources: List<DataSourceRow> = emptyList(),
    ) : WorkspaceViewModel

    /**
     * Um banco na tela.
     *
     * Não carrega host, porta, usuário nem nome do banco: identificar o servidor não é necessário
     * para o dono reconhecer a fonte, e o que não chega à tela não vaza por ela.
     */
    data class DataSourceRow(
        val name: String,
        val accessModeKey: String,
        val defaultSchema: String?,
        val personalDataObfuscated: Boolean,
    )

    data class PackRow(
        val packId: String,
        val title: String,
        val version: String,
    )

    data class RepositoryRow(
        val name: String,
        val roleKey: String,
        val accessModeKey: String,
        val current: Boolean,
    )

    /**
     * A política é identificada por **chave**, não por frase pronta.
     *
     * O modelo da tela é dado puro e testável sem a IDE; se ele carregasse texto traduzido, o
     * idioma da interface entraria no núcleo e o teste passaria a depender de locale.
     */
    data class PolicyRow(val labelKey: String, val allowed: Boolean)

    companion object {

        fun from(
            resolution: WorkspaceResolution,
            pendingPacks: Int = 0,
            installedPacks: List<PackRow> = emptyList(),
        ): WorkspaceViewModel = when (resolution) {
            is WorkspaceResolution.NotConfigured -> NotConfigured(resolution.projectName)

            is WorkspaceResolution.Ambiguous -> Ambiguous(
                resolution.projectName,
                resolution.workspaceIds.size,
            )

            is WorkspaceResolution.Resolved -> {
                val context = resolution.context
                Configured(
                    workspaceId = context.workspace.id,
                    workspaceName = context.workspace.name,
                    workspaceTypeKey = context.workspace.type.labelKey,
                    currentRepositoryName = context.currentRepository.name,
                    repositories = context.workspace.repositories.map {
                        RepositoryRow(
                            name = it.name,
                            roleKey = it.role.labelKey,
                            accessModeKey = it.accessMode.labelKey,
                            current = it.id == context.currentRepository.id,
                        )
                    },
                    pendingPacks = pendingPacks,
                    installedPacks = installedPacks,
                    dataSources = context.workspace.datasources.map {
                        DataSourceRow(
                            name = it.name,
                            accessModeKey = it.accessMode.labelKey,
                            defaultSchema = it.defaultSchema,
                            personalDataObfuscated = it.obfuscatePersonalData,
                        )
                    },
                    policies = listOf(
                        PolicyRow("policy.referenceWrite", context.policies.referenceWrite),
                        PolicyRow("policy.databaseWrite", context.policies.databaseWrite),
                        PolicyRow("policy.processExecution", context.policies.processExecution),
                        PolicyRow("policy.gitWrite", context.policies.gitWrite),
                    ),
                )
            }
        }
    }
}
