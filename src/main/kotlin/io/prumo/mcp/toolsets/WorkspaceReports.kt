package io.prumo.mcp.toolsets

import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.documentation.SupportedDocumentFormats
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.policy.PolicyDecision
import io.prumo.mcp.policy.PolicyEngine
import io.prumo.mcp.policy.PolicyRequest
import io.prumo.mcp.repository.GitRepositoryProbe
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.domain.RepositoryBinding
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Serializable
data class RepositoryResponse(
    val repositoryId: String,
    val name: String,
    val role: String,
    val accessMode: String,
    val writable: Boolean,
    val current: Boolean,
    /** Remote normalizado (`host/org/nome`), nulo quando o vínculo não tem Git. */
    val remoteIdentity: String? = null,
    /** Texto livre escrito pelo desenvolvedor sobre o que este repositório é. */
    val description: String? = null,
    /** Caminhos que o Prumo recusa ler, listar e varrer neste repositório. Regra, não pedido. */
    val excludedPaths: List<String> = emptyList(),
)

@Serializable
data class WorkspaceContextResponse(
    val workspaceId: String,
    val workspaceName: String,
    val workspaceType: String,
    val currentRepository: RepositoryResponse,
    val repositoryCount: Int,
    val documentationSourceCount: Int,
)

@Serializable
data class PolicyDecisionResponse(
    val action: String,
    val allowed: Boolean,
    val reason: String? = null,
    /** Preenchido nas ações de banco: a decisão vale por datasource, não para o workspace inteiro. */
    val datasources: List<DataSourceDecisionResponse>? = null,
)

@Serializable
data class DataSourceDecisionResponse(
    val datasourceId: String,
    val allowed: Boolean,
    val reason: String? = null,
)

@Serializable
data class WorkspacePolicyResponse(
    val workspaceId: String,
    val currentRepositoryAccessMode: String,
    val decisions: List<PolicyDecisionResponse>,
)

@Serializable
data class RepositoriesResponse(
    val workspaceId: String,
    val currentRepositoryId: String,
    val repositories: List<RepositoryResponse>,
)

@Serializable
data class DocumentationSourceResponse(
    val documentationId: String,
    val name: String,
    val kind: String,
    val authority: String,
    val available: Boolean,
    /** Falso para formatos apenas catalogados, como PDF: o documento é conhecido, o texto não é lido. */
    val textExtractionSupported: Boolean,
)

@Serializable
data class DocumentationSourcesResponse(
    val workspaceId: String,
    val sources: List<DocumentationSourceResponse>,
)

@Serializable
enum class CheckStatus { OK, WARNING, ERROR }

@Serializable
enum class PreparationStatus { READY, WARNING, ERROR }

@Serializable
data class PreparationCheck(
    val check: String,
    val status: CheckStatus,
    val message: String,
)

@Serializable
data class WorkspacePreparationResponse(
    val workspaceId: String,
    val status: PreparationStatus,
    val checks: List<PreparationCheck>,
)

/**
 * Respostas das tools de workspace.
 *
 * Nenhuma resposta carrega caminho absoluto, credencial ou vestígio de outro workspace: o cliente
 * recebe identificadores.
 */
object WorkspaceReports {

    fun context(context: WorkspaceContext): WorkspaceContextResponse =
        WorkspaceContextResponse(
            workspaceId = context.workspace.id,
            workspaceName = context.workspace.name,
            workspaceType = context.workspace.type.name,
            currentRepository = context.currentRepository.toResponse(current = true),
            repositoryCount = context.workspace.repositories.size,
            documentationSourceCount = context.workspace.documentation.size,
        )

    /**
     * As decisões vêm do `PolicyEngine`, não de uma cópia das flags do workspace.
     *
     * As ações de banco são avaliadas sem datasource resolvido, porque a pergunta é sobre o workspace.
     */
    fun policy(context: WorkspaceContext): WorkspacePolicyResponse =
        WorkspacePolicyResponse(
            workspaceId = context.workspace.id,
            currentRepositoryAccessMode = context.currentRepository.accessMode.name,
            decisions = PolicyAction.entries.map { action ->
                if (action in DATABASE_ACTIONS) databaseDecision(context, action) else decisionFor(context, action)
            },
        )

    private fun decisionFor(context: WorkspaceContext, action: PolicyAction): PolicyDecisionResponse {
        val decision = PolicyEngine.evaluate(
            PolicyRequest(
                action = action,
                policies = context.policies,
                repositoryAccess = context.currentRepository.accessMode,
            ),
        )
        return PolicyDecisionResponse(
            action = action.name,
            allowed = decision.allowed,
            reason = (decision as? PolicyDecision.Denied)?.reason,
        )
    }

    /**
     * Decide uma ação de banco contra cada datasource vinculado, não contra uma chamada sem
     * datasource resolvido.
     *
     * Avaliar sem datasource devolvia recusa em workspace onde a consulta funciona, e um cliente que
     * lesse a política antes de consultar concluía que o banco estava fechado. A ação é permitida
     * quando qualquer datasource a permite, e o detalhe por datasource acompanha a decisão.
     */
    private fun databaseDecision(context: WorkspaceContext, action: PolicyAction): PolicyDecisionResponse {
        val datasources = context.workspace.datasources
        if (datasources.isEmpty()) {
            return PolicyDecisionResponse(
                action = action.name,
                allowed = false,
                reason = "No database is bound to this workspace.",
                datasources = emptyList(),
            )
        }

        val perDatasource = datasources.map { profile ->
            val decision = PolicyEngine.evaluate(
                PolicyRequest(
                    action = action,
                    policies = context.policies,
                    repositoryAccess = context.currentRepository.accessMode,
                    databaseAccess = profile.accessMode,
                ),
            )
            DataSourceDecisionResponse(
                datasourceId = profile.id,
                allowed = decision.allowed,
                reason = (decision as? PolicyDecision.Denied)?.reason,
            )
        }
        val permitido = perDatasource.filter { it.allowed }.map { it.datasourceId }

        return PolicyDecisionResponse(
            action = action.name,
            allowed = permitido.isNotEmpty(),
            reason = if (permitido.isNotEmpty()) null else "No bound database allows this action.",
            datasources = perDatasource,
        )
    }

    fun repositories(context: WorkspaceContext): RepositoriesResponse =
        RepositoriesResponse(
            workspaceId = context.workspace.id,
            currentRepositoryId = context.currentRepository.id,
            repositories = context.workspace.repositories.map {
                it.toResponse(current = it.id == context.currentRepository.id)
            },
        )

    fun documentationSources(context: WorkspaceContext): DocumentationSourcesResponse =
        DocumentationSourcesResponse(
            workspaceId = context.workspace.id,
            sources = context.workspace.documentation.map { source ->
                val location = source.locationOrNull()
                DocumentationSourceResponse(
                    documentationId = source.id,
                    name = source.name,
                    kind = source.kind.name,
                    authority = source.authority.name,
                    available = location != null && Files.exists(location),
                    textExtractionSupported = location != null &&
                        SupportedDocumentFormats.isReadableAsText(location),
                )
            },
        )

    private fun RepositoryBinding.toResponse(current: Boolean) = RepositoryResponse(
        repositoryId = id,
        name = name,
        role = role.name,
        accessMode = accessMode.name,
        writable = writable,
        current = current,
        // O remote cru pode carregar usuário e token na URL; a forma normalizada descarta a credencial.
        remoteIdentity = gitRemote?.let(RepositoryFingerprint.Companion::normalizeRemote),
        description = description,
        excludedPaths = excludedPaths,
    )

    private val DATABASE_ACTIONS = setOf(PolicyAction.QUERY_DATABASE, PolicyAction.WRITE_DATABASE)
}

/**
 * Validação determinística do workspace corrente.
 *
 * Apenas lê: confere o que está vinculado contra o que existe no disco e devolve o veredito. Não
 * executa `git pull`, `checkout`, `reset` nem qualquer mutação — preparar é constatar, não corrigir.
 */
object WorkspacePreparation {

    fun evaluate(context: WorkspaceContext): WorkspacePreparationResponse {
        val checks = buildList {
            add(policyCheck(context))
            context.workspace.repositories.forEach { add(repositoryCheck(it, context)) }
            context.workspace.documentation.forEach { add(documentationCheck(it)) }
        }
        return WorkspacePreparationResponse(
            workspaceId = context.workspace.id,
            status = aggregate(checks),
            checks = checks,
        )
    }

    private fun policyCheck(context: WorkspaceContext): PreparationCheck {
        val decision = PolicyEngine.evaluate(
            PolicyRequest(
                action = PolicyAction.READ_REPOSITORY,
                policies = context.policies,
                repositoryAccess = context.currentRepository.accessMode,
            ),
        )
        return when (decision) {
            is PolicyDecision.Denied -> PreparationCheck(
                check = "policy",
                status = CheckStatus.ERROR,
                message = decision.reason,
            )

            PolicyDecision.Allowed -> PreparationCheck(
                check = "policy",
                status = CheckStatus.OK,
                message = "The current repository can be read within this workspace.",
            )
        }
    }

    private fun repositoryCheck(binding: RepositoryBinding, context: WorkspaceContext): PreparationCheck {
        val check = "repository:${binding.id}"
        val root = binding.localPathOrNull()
        if (root == null || !Files.isDirectory(root)) {
            return PreparationCheck(
                check = check,
                status = CheckStatus.ERROR,
                message = "The directory bound to repository '${binding.name}' is no longer " +
                    "accessible. Rebind it in the Prumo MCP tool window.",
            )
        }

        val repositoryRoot = GitRepositoryProbe.findRepositoryRoot(root)
            ?: return PreparationCheck(
                check = check,
                status = CheckStatus.WARNING,
                message = "Repository '${binding.name}' is bound to a directory that is not a Git " +
                    "repository, so its identity depends on the directory name.",
            )

        val current = RepositoryFingerprint.forDirectory(repositoryRoot)
        val recorded = binding.fingerprint
        if (recorded != null && recorded != current.value) {
            return PreparationCheck(
                check = check,
                status = CheckStatus.WARNING,
                message = "The identity of repository '${binding.name}' changed since it was bound " +
                    "to this workspace. Confirm the binding still points to the intended repository.",
            )
        }

        val position = if (binding.id == context.currentRepository.id) "the current repository" else binding.role.name
        return PreparationCheck(
            check = check,
            status = CheckStatus.OK,
            message = "Repository '${binding.name}' is available as $position " +
                "in ${binding.accessMode.name} mode.",
        )
    }

    private fun documentationCheck(source: DocumentationSource): PreparationCheck {
        val check = "documentation:${source.id}"
        val location = source.locationOrNull()
        if (location == null || !Files.exists(location)) {
            return PreparationCheck(
                check = check,
                status = CheckStatus.WARNING,
                message = "Documentation source '${source.name}' is no longer accessible and will " +
                    "not be available to this workspace.",
            )
        }
        if (Files.isRegularFile(location) && !SupportedDocumentFormats.isReadableAsText(location)) {
            return PreparationCheck(
                check = check,
                status = CheckStatus.OK,
                message = "Documentation source '${source.name}' is catalogued, but Prumo does not " +
                    "extract text from this format.",
            )
        }
        return PreparationCheck(
            check = check,
            status = CheckStatus.OK,
            message = "Documentation source '${source.name}' is available as ${source.authority.name}.",
        )
    }

    private fun aggregate(checks: List<PreparationCheck>): PreparationStatus = when {
        checks.any { it.status == CheckStatus.ERROR } -> PreparationStatus.ERROR
        checks.any { it.status == CheckStatus.WARNING } -> PreparationStatus.WARNING
        else -> PreparationStatus.READY
    }
}

private fun RepositoryBinding.localPathOrNull(): Path? = pathOrNull(localPath)

private fun DocumentationSource.locationOrNull(): Path? = pathOrNull(location)

/** O caminho gravado na configuração pode ter sido escrito em outro sistema operacional. */
private fun pathOrNull(value: String): Path? = try {
    Path.of(value)
} catch (_: InvalidPathException) {
    null
}
