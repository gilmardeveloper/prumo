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
        /** As chamadas mais recentes registradas na trilha, da mais antiga para a mais nova. */
        val activity: List<ActivityRow> = emptyList(),
        /** O que os clientes de IA destilaram e guardaram neste workspace. */
        val memory: List<MemoryRow> = emptyList(),
    ) : WorkspaceViewModel

    /**
     * Um registro da base de conhecimento, como a tela o mostra.
     *
     * Carrega o veredicto de frescor porque é o que diz ao desenvolvedor se aquilo ainda descreve a
     * fonte — e é a informação que justifica a IA ter escrito sozinha.
     */
    data class MemoryRow(
        val knowledgeId: String,
        val title: String,
        val sourceId: String,
        val freshness: String,
        val author: String,
        val updatedAt: String,
        /** A fonte está entre os caminhos que o desenvolvedor excluiu deste workspace. */
        val outOfReach: Boolean = false,
    ) {
        /**
         * O que a linha diz sobre a fonte.
         *
         * O alcance vem antes do frescor: registro fora de alcance carimba `ORPHAN`, que diria ao
         * desenvolvedor que a fonte sumiu quando foi ele quem a pôs fora de alcance.
         */
        val state: String get() = if (outOfReach) OUT_OF_REACH else freshness

        companion object {
            const val OUT_OF_REACH = "OUT_OF_REACH"
        }
    }

    /**
     * Uma chamada registrada na trilha de auditoria.
     *
     * Traz o que aconteceu, não o que foi lido: nem conteúdo de arquivo, nem linha de resultado,
     * nem valor de parâmetro — a trilha nunca os guardou.
     */
    data class ActivityRow(
        val timestamp: String,
        val tool: String,
        val operation: String,
        val result: String,
        val durationMillis: Long,
    )

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
            activity: List<ActivityRow> = emptyList(),
            memory: List<MemoryRow> = emptyList(),
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
                    activity = activity,
                    memory = memory,
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
