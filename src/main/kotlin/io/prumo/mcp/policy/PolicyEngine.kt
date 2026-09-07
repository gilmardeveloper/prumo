package io.prumo.mcp.policy

import io.prumo.mcp.workspace.domain.AccessMode
import kotlinx.serialization.Serializable

/** Operações que o Prumo submete a decisão de política. */
enum class PolicyAction {
    READ_REPOSITORY,
    WRITE_REPOSITORY,
    READ_DOCUMENTATION,
    QUERY_DATABASE,
    WRITE_DATABASE,
    EXECUTE_PROCESS,
    WRITE_GIT,
}

/** Capacidades que um recurso trazido de fora precisa declarar e o usuário precisa conceder. */
@Serializable
enum class Capability {
    REPOSITORY_READ,
    DATASOURCE_QUERY,
    DOCUMENTATION_READ,
    PROCESS_EXECUTE,
}

/** Políticas de um workspace. Todas negam por padrão. */
@Serializable
data class WorkspacePolicies(
    val referenceWrite: Boolean = false,
    val databaseWrite: Boolean = false,
    val processExecution: Boolean = false,
    val gitWrite: Boolean = false,
) {
    companion object {
        val DENY_ALL = WorkspacePolicies()
    }
}

data class PolicyRequest(
    val action: PolicyAction,
    val policies: WorkspacePolicies,
    val repositoryAccess: AccessMode? = null,
    val databaseAccess: AccessMode? = null,
    /** Capacidades concedidas ao pack que originou a chamada; vazio para tools nativas. */
    val grantedCapabilities: Set<Capability> = emptySet(),
    /** Verdadeiro quando a chamada vem de um recurso instalado pelo usuário, não do Core Toolkit. */
    val fromUserResource: Boolean = false,
)

sealed interface PolicyDecision {

    data object Allowed : PolicyDecision

    data class Denied(val reason: String) : PolicyDecision

    val allowed: Boolean get() = this is Allowed
}

class PolicyViolationException(val decision: PolicyDecision.Denied) : SecurityException(decision.reason)

/**
 * Ponto único de decisão sobre o que é permitido.
 *
 * A resposta padrão é a negação: ausência de regra que autorize equivale a negar.
 */
object PolicyEngine {

    fun evaluate(request: PolicyRequest): PolicyDecision {
        capabilityFor(request.action)?.let { required ->
            if (request.fromUserResource && required !in request.grantedCapabilities) {
                return deny("the resource did not declare the ${required.name} capability, or it was not granted")
            }
        }

        return when (request.action) {
            PolicyAction.READ_REPOSITORY -> requireRepositoryBinding(request)

            PolicyAction.WRITE_REPOSITORY -> when {
                request.repositoryAccess == null -> deny("no repository was resolved for this call")
                request.repositoryAccess == AccessMode.READ_ONLY ->
                    deny("the repository is bound as READ_ONLY in this workspace")
                !request.policies.referenceWrite && request.fromUserResource ->
                    deny("workspace policy does not allow user resources to write to repositories")
                else -> PolicyDecision.Allowed
            }

            PolicyAction.READ_DOCUMENTATION -> PolicyDecision.Allowed

            PolicyAction.QUERY_DATABASE -> when (request.databaseAccess) {
                null -> deny("no datasource was resolved for this call")
                else -> PolicyDecision.Allowed
            }

            PolicyAction.WRITE_DATABASE -> when {
                !request.policies.databaseWrite ->
                    deny("workspace policy denies database writes")
                request.databaseAccess != AccessMode.READ_WRITE ->
                    deny("the datasource is configured as READ_ONLY")
                else -> PolicyDecision.Allowed
            }

            PolicyAction.EXECUTE_PROCESS ->
                if (request.policies.processExecution) {
                    PolicyDecision.Allowed
                } else {
                    deny("workspace policy denies process execution")
                }

            PolicyAction.WRITE_GIT ->
                if (request.policies.gitWrite) {
                    PolicyDecision.Allowed
                } else {
                    deny("workspace policy denies Git write operations")
                }
        }
    }

    fun require(request: PolicyRequest) {
        val decision = evaluate(request)
        if (decision is PolicyDecision.Denied) {
            throw PolicyViolationException(decision)
        }
    }

    private fun requireRepositoryBinding(request: PolicyRequest): PolicyDecision =
        if (request.repositoryAccess == null) {
            deny("no repository was resolved for this call")
        } else {
            PolicyDecision.Allowed
        }

    private fun capabilityFor(action: PolicyAction): Capability? = when (action) {
        PolicyAction.READ_REPOSITORY, PolicyAction.WRITE_REPOSITORY -> Capability.REPOSITORY_READ
        PolicyAction.QUERY_DATABASE, PolicyAction.WRITE_DATABASE -> Capability.DATASOURCE_QUERY
        PolicyAction.READ_DOCUMENTATION -> Capability.DOCUMENTATION_READ
        PolicyAction.EXECUTE_PROCESS -> Capability.PROCESS_EXECUTE
        PolicyAction.WRITE_GIT -> null
    }

    private fun deny(reason: String) = PolicyDecision.Denied("Prumo MCP denied the operation: $reason.")
}
