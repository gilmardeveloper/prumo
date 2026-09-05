package io.prumo.mcp.policy

import io.prumo.mcp.workspace.domain.AccessMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyEngineTest {

    private fun request(
        action: PolicyAction,
        policies: WorkspacePolicies = WorkspacePolicies.DENY_ALL,
        repositoryAccess: AccessMode? = null,
        databaseAccess: AccessMode? = null,
        capabilities: Set<Capability> = emptySet(),
        fromUserResource: Boolean = false,
    ) = PolicyRequest(action, policies, repositoryAccess, databaseAccess, capabilities, fromUserResource)

    @Test
    fun `repositorio somente leitura recusa escrita`() {
        val decision = PolicyEngine.evaluate(
            request(PolicyAction.WRITE_REPOSITORY, repositoryAccess = AccessMode.READ_ONLY),
        )

        assertTrue(decision is PolicyDecision.Denied)
        assertTrue((decision as PolicyDecision.Denied).reason.contains("READ_ONLY"))
    }

    @Test
    fun `repositorio de escrita permite escrita`() {
        assertTrue(
            PolicyEngine.evaluate(
                request(PolicyAction.WRITE_REPOSITORY, repositoryAccess = AccessMode.READ_WRITE),
            ).allowed,
        )
    }

    @Test
    fun `sem repositorio resolvido nada e permitido`() {
        listOf(PolicyAction.READ_REPOSITORY, PolicyAction.WRITE_REPOSITORY).forEach { action ->
            assertTrue(PolicyEngine.evaluate(request(action)) is PolicyDecision.Denied, action.name)
        }
    }

    @Test
    fun `escrita em banco e negada por padrao mesmo com datasource de escrita`() {
        val decision = PolicyEngine.evaluate(
            request(PolicyAction.WRITE_DATABASE, databaseAccess = AccessMode.READ_WRITE),
        )

        assertTrue(decision is PolicyDecision.Denied)
    }

    @Test
    fun `escrita em banco exige politica e datasource de escrita`() {
        assertTrue(
            PolicyEngine.evaluate(
                request(
                    PolicyAction.WRITE_DATABASE,
                    policies = WorkspacePolicies(databaseWrite = true),
                    databaseAccess = AccessMode.READ_ONLY,
                ),
            ) is PolicyDecision.Denied,
        )
        assertTrue(
            PolicyEngine.evaluate(
                request(
                    PolicyAction.WRITE_DATABASE,
                    policies = WorkspacePolicies(databaseWrite = true),
                    databaseAccess = AccessMode.READ_WRITE,
                ),
            ).allowed,
        )
    }

    @Test
    fun `consulta em banco somente leitura e permitida`() {
        assertTrue(
            PolicyEngine.evaluate(
                request(PolicyAction.QUERY_DATABASE, databaseAccess = AccessMode.READ_ONLY),
            ).allowed,
        )
    }

    @Test
    fun `caminho externo, execucao de processo e escrita Git sao negados por padrao`() {
        listOf(
            PolicyAction.ACCESS_EXTERNAL_PATH,
            PolicyAction.EXECUTE_PROCESS,
            PolicyAction.WRITE_GIT,
        ).forEach { action ->
            assertTrue(PolicyEngine.evaluate(request(action)) is PolicyDecision.Denied, action.name)
        }
    }

    @Test
    fun `recurso do usuario sem a capacidade concedida e barrado`() {
        val decision = PolicyEngine.evaluate(
            request(
                PolicyAction.EXECUTE_PROCESS,
                policies = WorkspacePolicies(processExecution = true),
                fromUserResource = true,
            ),
        )

        assertTrue(decision is PolicyDecision.Denied)
        assertTrue((decision as PolicyDecision.Denied).reason.contains("PROCESS_EXECUTE"))
    }

    @Test
    fun `recurso do usuario com a capacidade concedida segue as demais regras`() {
        assertTrue(
            PolicyEngine.evaluate(
                request(
                    PolicyAction.EXECUTE_PROCESS,
                    policies = WorkspacePolicies(processExecution = true),
                    capabilities = setOf(Capability.PROCESS_EXECUTE),
                    fromUserResource = true,
                ),
            ).allowed,
        )
    }

    @Test
    fun `capacidade concedida nao substitui a politica do workspace`() {
        val decision = PolicyEngine.evaluate(
            request(
                PolicyAction.EXECUTE_PROCESS,
                policies = WorkspacePolicies.DENY_ALL,
                capabilities = setOf(Capability.PROCESS_EXECUTE),
                fromUserResource = true,
            ),
        )

        assertTrue(decision is PolicyDecision.Denied)
    }

    @Test
    fun `recurso do usuario nao escreve em repositorio sem a politica correspondente`() {
        val decision = PolicyEngine.evaluate(
            request(
                PolicyAction.WRITE_REPOSITORY,
                repositoryAccess = AccessMode.READ_WRITE,
                capabilities = setOf(Capability.REPOSITORY_READ),
                fromUserResource = true,
            ),
        )

        assertTrue(decision is PolicyDecision.Denied)
    }

    @Test
    fun `require lanca quando a decisao e negativa`() {
        val failure = assertThrows(PolicyViolationException::class.java) {
            PolicyEngine.require(request(PolicyAction.EXECUTE_PROCESS))
        }

        assertEquals(false, failure.decision.allowed)
    }
}
