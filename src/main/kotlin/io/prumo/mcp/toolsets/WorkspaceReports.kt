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
    /** Remote normalizado (`host/org/nome`), nulo quando o vínculo não tem Git. Nunca a URL crua. */
    val remoteIdentity: String? = null,
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
 * Construídas fora do adaptador MCP e sem tipo algum da IDE, porque a garantia que importa aqui é
 * verificável apenas sobre dado: nenhuma resposta carrega caminho absoluto, credencial ou vestígio
 * de outro workspace. O caminho local de um repositório existe para o plugin resolver arquivos e
 * termina no plugin — o cliente recebe identificadores.
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
     * As decisões vêm do `PolicyEngine`, não de uma cópia das flags do workspace: uma segunda
     * leitura das mesmas regras acabaria divergindo daquela que de fato barra a operação.
     *
     * As ações de banco são avaliadas sem datasource resolvido, porque a pergunta é sobre o
     * workspace e não sobre uma conexão específica.
     */
    fun policy(context: WorkspaceContext): WorkspacePolicyResponse =
        WorkspacePolicyResponse(
            workspaceId = context.workspace.id,
            currentRepositoryAccessMode = context.currentRepository.accessMode.name,
            decisions = PolicyAction.entries.map { action ->
                val decision = PolicyEngine.evaluate(
                    PolicyRequest(
                        action = action,
                        policies = context.policies,
                        repositoryAccess = context.currentRepository.accessMode,
                    ),
                )
                PolicyDecisionResponse(
                    action = action.name,
                    allowed = decision.allowed,
                    reason = (decision as? PolicyDecision.Denied)?.reason,
                )
            },
        )

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
        // O remote cru pode carregar usuário e token embutidos na URL; a forma normalizada
        // identifica o repositório e descarta a credencial.
        remoteIdentity = gitRemote?.let(RepositoryFingerprint.Companion::normalizeRemote),
    )
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

        val current = RepositoryFingerprint.of(
            GitRepositoryProbe.readOriginRemote(repositoryRoot),
            repositoryRoot,
        )
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
